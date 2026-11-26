package com.github.nyabanban.fuji.emulator.components.gic

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class GicChooseCacheTest {
    private class Context : ComponentContext {
        val claims=linkedMapOf<String,Any>()
        override val directory=Path.of(".").toAbsolutePath()
        override val clock=EmulationClock { 123L }
        override val ports=object : Ports {
            override fun<T:Any> register(owner:String,name:String,type:Class<T>,port:T) { check(claims.putIfAbsent(name,port)==null) }
            override fun<T:Any> find(name:String,type:Class<T>):T?=claims[name]?.let(type::cast)
            override fun removeOwner(owner:String) {}
        }
        override val events=object : EventChannel {
            override fun publish(event:ComponentEvent) {}
            override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable {}
        }
        override fun readData(relative:String):Map<String,Any?> = error("No data inputs")
        override fun dataPath(relative:String)=directory.resolve(relative)
    }
    private class Fixture(cluster:Boolean) : AutoCloseable {
        val context=Context()
        private val factory=GicFactory()
        val hardware=factory.create(context,factory.descriptor.settings.associate { it.key to it.default } +
            mapOf("coreProfile" to if(cluster) "four-context-functional-experimental-v1" else "", "sgiProfile" to if(cluster) "" else "single-core-experimental-v1"))
        val d=checkNotNull(context.ports.find("soc-gic.distributor",MmioDevice::class.java))
        val c=checkNotNull(context.ports.find("soc-gic.interface",MmioDevice::class.java))
        val cpu=checkNotNull(context.ports.find("soc-gic.cpu",CpuInterruptPort::class.java))
        val cores=context.ports.find("soc-gic.cores",CoreInterruptPort::class.java)
        val lines=checkNotNull(context.ports.find("soc-gic.lines",InterruptSink::class.java))
        val coreCount=if(cluster) 4 else 1
        fun select(core:Int) { if(cores!=null) cores.selectCore(core) else require(core==0) }
        fun configure() {
            d.write(0,4,1)
            repeat(coreCount) { select(it); c.write(0,4,1); c.write(4,4,255) }
            select(0)
        }
        fun checkChoices(vararg expected:Int?) {
            require(expected.size==coreCount)
            val state=hardware.state()
            val rows=coreStates(state)
            repeat(coreCount) { core ->
                select(core)
                val oracle=Scan(rows,core)
                assertEquals(expected[core],oracle.choose(),"independent scan core=$core")
                repeat(8) { assertEquals(expected[core],cpu.choose(),"repeat choose core=$core") }
                assertEquals(expected[core],rows[core]["eligible"],"state eligible core=$core")
                assertEquals(rows[core],coreStates(hardware.state())[core],"choose is passive core=$core")
            }
            select(0)
        }
        override fun close() { hardware.close() }
    }

    /** The old full scan, reconstructed from public state; never calls production choose. */
    private class Scan(rows:List<Map<*,*>>,private val core:Int) {
        private val row=rows[core]
        private val control=(row["control"] as Number).toInt()
        private val cpuControl=(row["cpuControl"] as Number).toInt()
        private val pmr=(row["priorityMask"] as Number).toInt()
        private val bpr=(row["binaryPoint"] as Number).toInt()
        private val enabled=flags(row,"enabled")
        private val pending=flags(row,"pending")
        private val active=BooleanArray(512).also { result ->
            rows.forEachIndexed { owner,state -> (state["active"] as List<*>).forEach { raw ->
                val irq=(raw as Number).toInt() and 1023
                if(irq>=32 || owner==core) result[irq]=true
            } }
        }
        private val priorities=(row["priorities"] as List<*>).map { (it as Number).toInt() }.toIntArray()
        private val targets=(row["targets"] as List<*>).map { (it as Number).toInt() }.toIntArray()
        private val stack=(row["active"] as List<*>).map { (it as Number).toInt() }.toIntArray()
        fun choose():Int? {
            if(control==0 || cpuControl==0) return null
            val mask=(255 shl (bpr+1)) and 255
            var running=256
            for(token in stack) running=minOf(running,priorities[token and 1023] and mask)
            var chosen:Int?=null
            for(irq in 0..511) if(enabled[irq] && pending[irq] && !active[irq] && targets[irq] and (1 shl core)!=0 && priorities[irq]<pmr && (priorities[irq] and mask)<running) {
                if(chosen==null || priorities[irq]<priorities[chosen]) chosen=irq
            }
            return chosen
        }
        private fun flags(row:Map<*,*>,key:String)=BooleanArray(512).also { result -> (row[key] as List<*>).forEach { result[(it as Number).toInt()]=true } }
    }
    companion object {
        @Volatile private var consumed=0L
        private fun coreStates(state:Map<String,Any?>):List<Map<*,*>> =
            (state["cores"] as? List<*>)?.map { it as Map<*,*> } ?: listOf(state)
    }

    @Test fun everyControlAndArrayMutationRefreshesNullAndNonNullChoices() {
        for(cluster in listOf(false,true)) Fixture(cluster).use { f ->
            fun check(irq:Int?)=if(cluster) f.checkChoices(irq,null,null,null) else f.checkChoices(irq)
            check(null) // An initial null must not mark an uncomputed cache as valid.
            f.configure(); check(null)
            f.d.write(0x104,4,(1L shl 8) or (1L shl 9)); check(null)
            f.lines.setLine(40,true); check(40)
            f.lines.setLine(41,true); check(40) // Equal priority keeps the lowest IRQ ID.
            f.d.write(0x428,2,0x2040); check(41)
            f.c.write(4,4,0x20); check(null)
            f.c.write(4,4,0x21); check(41)
            f.c.write(0,4,0); check(null)
            f.c.write(0,4,1); check(41)
            f.d.write(0,4,0); check(null)
            f.d.write(0,4,1); check(41)
            f.d.write(0x184,4,1L shl 9); check(null)
            f.c.write(4,4,255); check(40)
            f.lines.setLine(40,false); check(null)
            f.d.write(0x204,4,1L shl 8); check(40)
            f.d.write(0x284,4,1L shl 8); check(null)
            f.lines.setLine(40,true); check(40)
            f.d.write(0xc08,4,2L shl 16); check(null) // Existing high level stops being level-pending.
            f.lines.setLine(40,false); check(null)
            f.lines.setLine(40,true); check(40) // A fresh edge sets pending.
            f.d.write(0x284,4,1L shl 8); check(null)
            f.d.write(0xc08,4,0); check(40)
            f.d.write(0x828,1,0); check(null)
            f.d.write(0x828,1,1); check(40)
            f.d.write(0x100,4,1L shl 20); f.d.write(0x200,4,1L shl 20); check(20)
            f.d.write(0x280,4,1L shl 20); check(40)
            f.d.write(0x180,4,1L shl 20); check(40)
            f.lines.setLine(40,false); check(null)
        }
    }

    @Test fun sharedSpiMutationsInvalidateEveryCachedCoreIncludingActiveOwnership() {
        Fixture(true).use { f ->
            f.configure(); f.checkChoices(null,null,null,null)
            f.d.write(0x104,4,1L shl 5); f.d.write(0x825,1,15); f.lines.setLine(37,true)
            f.checkChoices(37,37,37,37)
            f.select(2); assertEquals(37L,f.c.read(0xc,4)); f.checkChoices(null,null,null,null)
            f.select(1); f.d.write(0x825,1,8); f.checkChoices(null,null,null,null)
            val before=f.hardware.state()
            assertFails { f.c.write(0x10,4,37) }; assertEquals(before,f.hardware.state())
            f.select(2); f.c.write(0x10,4,37); f.checkChoices(null,null,null,37)
            f.lines.setLine(37,false); f.checkChoices(null,null,null,null)
            f.select(3); f.d.write(0x204,4,1L shl 5); f.checkChoices(null,null,null,37)
            f.select(0); f.d.write(0x184,4,1L shl 5); f.checkChoices(null,null,null,null)
        }
    }

    @Test fun acknowledgeEoiBinaryPointAndPriorityChangesPreserveNestedPreemption() {
        for(cluster in listOf(false,true)) Fixture(cluster).use { f ->
            fun check(irq:Int?)=if(cluster) f.checkChoices(irq,null,null,null) else f.checkChoices(irq)
            f.configure(); f.d.write(0x104,4,(1L shl 8) or (1L shl 9) or (1L shl 10))
            f.d.write(0x428,4,0x00302040); f.lines.setLine(40,true); check(40)
            assertEquals(40L,f.c.read(0xc,4)); check(null)
            f.lines.setLine(41,true); check(41)
            f.c.write(8,4,6); check(null)
            f.c.write(8,4,0); check(41)
            assertEquals(41L,f.c.read(0xc,4)); check(null)
            f.lines.setLine(42,true); check(null)
            f.d.write(0x42a,1,0x10); check(42) // Priority changes can make an already-pending IRQ preempt.
            f.d.write(0x42a,1,0x30); check(null)
            f.lines.setLine(41,false); f.c.write(0x10,4,41); check(42)
            assertEquals(42L,f.c.read(0xc,4)); check(null)
            f.lines.setLine(42,false); f.c.write(0x10,4,42); check(null)
            f.lines.setLine(40,false); f.c.write(0x10,4,40); check(null)
            val before=f.hardware.state()
            for((offset,size,value) in listOf(Triple(0L,4,2L),Triple(4L,4,256L),Triple(8L,4,8L),Triple(0x10L,4,40L),Triple(0xcL,4,0L),Triple(4L,2,0L))) {
                assertFails { f.c.write(offset,size,value) }; assertEquals(before,f.hardware.state())
            }
            for((offset,size,value) in listOf(Triple(0L,4,2L),Triple(0xc08L,4,1L),Triple(0x304L,4,1L),Triple(0x401L,2,0L))) {
                assertFails { f.d.write(offset,size,value) }; assertEquals(before,f.hardware.state())
            }
            check(null)
        }
    }

    @Test fun privateStateAndMultipleSgiSendersRefreshOnlyTheirValidChoices() {
        Fixture(true).use { f ->
            f.configure(); f.checkChoices(null,null,null,null)
            f.select(0); f.d.write(0xf00,4,0x00020005); f.checkChoices(null,5,null,null)
            f.select(3); f.d.write(0xf00,4,0x00020005); f.checkChoices(null,5,null,null)
            f.select(1); assertEquals(5L,f.c.read(0xc,4)); f.checkChoices(null,null,null,null)
            f.select(1); f.c.write(0x10,4,5); f.checkChoices(null,5,null,null)
            f.select(1); assertEquals(0xc05L,f.c.read(0xc,4)); f.checkChoices(null,null,null,null)
            f.select(1); val before=f.hardware.state(); assertFails { f.c.write(0x10,4,5) }; assertEquals(before,f.hardware.state())
            f.c.write(0x10,4,0xc05); f.checkChoices(null,null,null,null)
            f.select(2); f.d.write(0x100,4,1L shl 20); f.d.write(0x200,4,1L shl 20); f.checkChoices(null,null,20,null)
            f.select(2); f.d.write(0x414,1,255); f.checkChoices(null,null,null,null)
            f.select(2); f.d.write(0x414,1,254); f.checkChoices(null,null,20,null)
            f.select(2); f.c.write(4,4,254); f.checkChoices(null,null,null,null)
        }
        Fixture(false).use { f ->
            f.configure(); f.checkChoices(null)
            f.d.write(0xf00,4,0x02000003); f.checkChoices(3)
            assertEquals(3L,f.c.read(0xc,4)); f.checkChoices(null)
            f.c.write(0x10,4,3); f.checkChoices(null)
        }
    }

    @Test fun repeatedChooseTimingComparesFullScanOracleWithCachedPortWithoutWholeFirmware() {
        Fixture(true).use { f ->
            f.configure(); f.d.write(0x13c,4,1L shl 31); f.d.write(0x9ff,1,1)
            for(pending in listOf(false,true)) {
                f.lines.setLine(511,pending); f.select(0)
                val scan=Scan(coreStates(f.hardware.state()),0)
                val expected=if(pending)511 else null
                assertEquals(expected,scan.choose()); assertEquals(expected,f.cpu.choose())
                fun measure(count:Int,choose:()->Int?):Long {
                    val started=System.nanoTime()
                    repeat(count) { consumed=consumed+(choose() ?: 1023) }
                    return System.nanoTime()-started
                }
                measure(20000,scan::choose); measure(20000,f.cpu::choose)
                val old=LongArray(5); val cached=LongArray(5)
                repeat(5) { round ->
                    if(round%2==0) { old[round]=measure(100000,scan::choose); cached[round]=measure(100000,f.cpu::choose) }
                    else { cached[round]=measure(100000,f.cpu::choose); old[round]=measure(100000,scan::choose) }
                }
                assertEquals(expected,scan.choose()); assertEquals(expected,f.cpu.choose())
                println("GIC_CHOOSE_ORACLE_MICRO pending=$pending iterations=100000 warmup=20000 oldScanNanos=${old.toList()} cachedPortNanos=${cached.toList()} consumed=$consumed scope=unchanged-state-only-with-volatile-consumer-not-whole-firmware")
            }
            assertTrue(consumed>0)
        }
    }

    @Test fun targetedInvalidationEdgeFallingEdgeAndPrimitivePortsMatchOracle() {
        Fixture(true).use { f ->
            val edgeSink = assertIs<EdgeInterruptSink>(f.lines)
            f.configure()
            // Configure SPI 40 (edge, target core 1, prio 0x40) and SPI 41 (edge, target core 2, prio 0x30)
            f.d.write(0xc08, 4, (2L shl 16) or (2L shl 18)) // edge for 40, 41
            f.d.write(0x828, 2, 0x0402) // target 40 -> core1 (mask 2), 41 -> core2 (mask 4)
            f.d.write(0x428, 2, 0x3040) // prio 40 -> 0x40, 41 -> 0x30
            f.d.write(0x104, 4, (1L shl 8) or (1L shl 9)) // enable 40, 41
            f.checkChoices(null, null, null, null)
            assertEquals(1, edgeSink.edgeStateBits(40)) // edge=1, high=0, pending=0
            assertFalse(f.cpu.hasPending())
            assertEquals(-1, f.cpu.chooseOrNegative())

            // Rising + falling edge on edge-triggered SPI 40 must keep pending=true and target only core 1
            f.lines.setLine(40, true)
            assertEquals(7, edgeSink.edgeStateBits(40)) // edge=1, high=1, pending=1
            f.lines.setLine(40, false)
            assertEquals(5, edgeSink.edgeStateBits(40)) // edge=1, high=0, pending=1
            f.checkChoices(null, 40, null, null)
            f.select(1)
            assertTrue(f.cpu.hasPending())
            assertEquals(40, f.cpu.chooseOrNegative())

            // Changing priority of non-pending SPI 41 must not corrupt core 1; changing priority of pending SPI 40 must refresh core 1
            f.d.write(0x429, 1, 0x10)
            f.checkChoices(null, 40, null, null)
            f.select(1)
            f.c.write(4, 4, 0x35) // PMR=0x35 masks prio 0x40 on core 1 only
            f.checkChoices(null, null, null, null)
            f.d.write(0x428, 1, 0x20) // lowering pending SPI 40 priority below PMR=0x35 immediately unmasks core 1
            f.checkChoices(null, 40, null, null)

            // Retargeting pending SPI 40 from core 1 -> core 3 invalidates BOTH old core 1 and new core 3
            f.d.write(0x828, 1, 0x08)
            f.checkChoices(null, null, null, 40)
            f.select(3)
            assertEquals(40L, f.c.read(0xc, 4)) // IAR ack on core 3
            f.checkChoices(null, null, null, null)
            f.select(3)
            f.c.write(0x10, 4, 40L) // EOI on core 3
            f.checkChoices(null, null, null, null)
        }
    }

    @Test fun nativeShadowRoundTripAndSimulatedNativeIarEoirPriorityMutationsMatchOracle() {
        Fixture(true).use { f ->
            val cluster = assertIs<GicFactory.ClusterGic>(f.hardware)
            f.configure()
            // Configure SPI 40 (edge, core 1, prio 0x40), SPI 41 (level, core 2, prio 0x30), SGI 5 on core 2 from core 0
            f.d.write(0xc08, 4, 2L shl 16) // edge for 40
            f.d.write(0x828, 2, 0x0402)    // 40 -> core 1, 41 -> core 2
            f.d.write(0x428, 2, 0x3040)    // prio 40=0x40, 41=0x30
            f.d.write(0x104, 4, (1L shl 8) or (1L shl 9)) // enable 40, 41
            f.lines.setLine(40, true)
            f.lines.setLine(40, false)
            f.lines.setLine(41, true)
            f.select(0)
            f.d.write(0xf00, 4, 0x00040005) // SGI 5 -> core 2 (mask 0x4) from core 0

            java.lang.foreign.Arena.ofConfined().use { arena ->
                val seg = arena.allocate(GicV2ShadowLayout.SIZE, 8L)
                seg.fill(0.toByte())
                cluster.bindNativeShadow(seg)

                // Verify initial Java -> native push matches exact C-ABI layout
                assertEquals(1, seg.get(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_ACTIVE))
                assertEquals(4, seg.get(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_CORE_COUNT))
                assertEquals(1, seg.get(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_DIST_CTRL))
                val spi40Bit = 1L shl 40
                val spi41Bit = 1L shl 41
                assertEquals(spi40Bit or spi41Bit, seg.get(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_SHARED_ENABLED))
                assertEquals(spi40Bit, seg.get(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_SHARED_PENDING))
                assertEquals(spi41Bit, seg.get(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_SHARED_LEVELS))
                assertEquals(0x40, seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, GicV2ShadowLayout.OFF_SHARED_PRIORITY + 8L).toInt() and 0xFF)
                assertEquals(0x30, seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, GicV2ShadowLayout.OFF_SHARED_PRIORITY + 9L).toInt() and 0xFF)
                val c1Base = GicV2ShadowLayout.OFF_CORES + 1L * GicV2ShadowLayout.CORE_STRIDE
                val c2Base = GicV2ShadowLayout.OFF_CORES + 2L * GicV2ShadowLayout.CORE_STRIDE
                assertEquals(1, seg.get(GicV2ShadowLayout.LITTLE_INT, c1Base + GicV2ShadowLayout.CORE_OFF_IRQ_PENDING))
                assertEquals(1, seg.get(GicV2ShadowLayout.LITTLE_INT, c2Base + GicV2ShadowLayout.CORE_OFF_IRQ_PENDING))
                assertEquals(1, seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, c2Base + GicV2ShadowLayout.CORE_OFF_SGI_SENDERS + 5L).toInt() and 0xFF)

                // 1. Simulate native C++ GICC_IAR on Core 1 acknowledging SPI 40
                seg.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_SHARED_PENDING, 0L)
                seg.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_SHARED_ACTIVE, spi40Bit)
                seg.set(GicV2ShadowLayout.LITTLE_INT, c1Base + GicV2ShadowLayout.CORE_OFF_ACTIVE_STACK_SIZE, 1)
                seg.set(GicV2ShadowLayout.LITTLE_INT, c1Base + GicV2ShadowLayout.CORE_OFF_ACTIVE_STACK, 40)
                seg.set(GicV2ShadowLayout.LITTLE_INT, c1Base + GicV2ShadowLayout.CORE_OFF_IRQ_PENDING, 0)
                seg.set(GicV2ShadowLayout.LITTLE_INT, c1Base + GicV2ShadowLayout.CORE_OFF_DIRTY_FLAG, 1)
                seg.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_DIRTY_SPI_MASK, GicV2ShadowLayout.DIRTY_SPI_PEND_ACT)
                seg.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_NATIVE_SEQ, 1L)

                f.checkChoices(null, null, 5, null)
                assertEquals(0, seg.get(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_DIRTY_SPI_MASK))
                assertEquals(0, seg.get(GicV2ShadowLayout.LITTLE_INT, c1Base + GicV2ShadowLayout.CORE_OFF_DIRTY_FLAG))

                // 2. Simulate native C++ GICC_EOIR on Core 1 completing SPI 40 + GICD_IPRIORITYR raising SGI 5 prio to 0x50 on Core 2
                seg.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_SHARED_ACTIVE, 0L)
                seg.set(GicV2ShadowLayout.LITTLE_INT, c1Base + GicV2ShadowLayout.CORE_OFF_ACTIVE_STACK_SIZE, 0)
                seg.set(GicV2ShadowLayout.LITTLE_INT, c1Base + GicV2ShadowLayout.CORE_OFF_DIRTY_FLAG, 1)
                seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, c2Base + GicV2ShadowLayout.CORE_OFF_LOCAL_PRIORITY + 5L, 0x50.toByte())
                seg.set(GicV2ShadowLayout.LITTLE_INT, c2Base + GicV2ShadowLayout.CORE_OFF_DIRTY_FLAG, 1)
                seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, GicV2ShadowLayout.OFF_SHARED_PRIORITY + 9L, 0x20.toByte())
                seg.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_DIRTY_SPI_MASK, GicV2ShadowLayout.DIRTY_SPI_PEND_ACT or GicV2ShadowLayout.DIRTY_SPI_PRIO)
                seg.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_NATIVE_SEQ, 2L)

                // SPI 41 (prio 0x20) now beats SGI 5 (prio 0x50) on Core 2!
                f.checkChoices(null, null, 41, null)

                // 3. Snapshot round-trip resets native_seq cleanly and keeps shadow coherent
                val snap = cluster.saveSnapshot()
                cluster.restoreSnapshot(snap)
                assertEquals(0L, seg.get(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_NATIVE_SEQ))
                assertEquals(1, seg.get(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_ACTIVE))
                f.checkChoices(null, null, 41, null)

                cluster.bindNativeShadow(null)
                assertEquals(0, seg.get(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_ACTIVE))
            }
        }
    }
}
