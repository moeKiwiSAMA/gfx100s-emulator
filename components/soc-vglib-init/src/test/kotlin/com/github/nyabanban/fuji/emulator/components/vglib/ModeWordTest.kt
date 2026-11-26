package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class ModeWordTest {
    private class Context(val sample:Any?=0xa5a5ffffL,val mask:Any?=0xfffffcffL,val extra:Map<String,Any?> = emptyMap()):ComponentContext {
        private val entries=HashMap<String,Any>()
        var tick=0L;var failLocation=false;var pc=0x5afe0L
        override val directory=Path.of(".")
        override val ports=object:Ports {
            override fun<T:Any> register(owner:String,name:String,type:Class<T>,port:T){check(entries.putIfAbsent(name,port)==null)}
            override fun<T:Any> find(name:String,type:Class<T>):T? = if(name=="cpu.execution-location")type.cast(ExecutionLocationSource{if(failLocation)error("location failed")else ExecutionLocation(2,pc)})else entries[name]?.let(type::cast)
            override fun removeOwner(owner:String){entries.clear()}
        }
        override val clock=EmulationClock{tick}
        override val events=object:EventChannel {override fun publish(event:ComponentEvent)=error("no mode word effects");override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
        override fun dataPath(relative:String)=directory.resolve(relative)
        override fun readData(relative:String):Map<String,Any?> = if(relative=="bank")mapOf("schema" to 1,"profile" to VglibInitFactory.PROFILE,"readConfidence" to "HYPOTHESIS","transactions" to List(18){mapOf("offset" to 0,"readValue" to 0,"writeValue" to 0)})else mapOf("schema" to 1,"profile" to ModeWordInput.PROFILE,"readConfidence" to "HYPOTHESIS","readSample" to sample,"sourcePreserveMask" to mask) + extra
    }
    @Test fun finiteMaskedPairRefusesWithoutConsumptionAndAbsentSelectionPreservesOldShape() {
        val f=VglibInitFactory();val disabled=Context();val d=f.create(disabled,mapOf("profile" to "disabled","inputFile" to "","controlWordInputFile" to ""))
        assertFalse(d.state().containsKey("controlWordInputFile"));assertFalse(d.state().containsKey("modeWord"));assertTrue(d.additionalMmioPorts().isEmpty())
        val old=Context();val a=f.create(old,mapOf("profile" to VglibInitFactory.PROFILE,"inputFile" to "bank","controlWordInputFile" to ""))
        assertFalse(a.state().containsKey("controlWordInputFile"));assertFalse(a.state().containsKey("modeWord"));assertEquals(listOf("soc-vglib-init.registers"),a.additionalMmioPorts())
        val c=Context();val component=f.create(c,mapOf("profile" to VglibInitFactory.PROFILE,"inputFile" to "bank","controlWordInputFile" to "mode"));val p=c.ports.find(ModeWordInput.PORT,MmioDevice::class.java)!!
        fun refused(call:()->Unit){val before=component.state();assertFails{call()};assertEquals(before,component.state())}
        refused{p.write(0,4,0)};refused{p.read(1,4)};refused{p.read(4,4)};refused{p.read(0,2)}
        c.tick=-1;refused{p.read(0,4)};c.tick=1;c.failLocation=true;refused{p.read(0,4)};c.failLocation=false
        assertEquals(0xa5a5ffffL,p.read(0,4));refused{p.read(0,4)};refused{p.write(0,4,0xa5a5ffffL)};refused{p.write(0,4,-1)};refused{p.write(0,4,0x100000000)}
        c.tick=-1;refused{p.write(0,4,0xa5a5fcffL)};c.tick=2;c.failLocation=true;refused{p.write(0,4,0xa5a5fcffL)};c.failLocation=false
        p.write(0,4,0xa5a5fcffL);refused{p.read(0,4)};refused{p.write(0,4,0xa5a5fcffL)}
        val mode=component.state()["modeWord"] as Map<*,*>;assertEquals(2,mode["transactionCount"]);assertEquals(0,mode["remainingTransactions"]);assertEquals(false,mode["eventLogTruncated"]);assertTrue(component.interruptRoutes().isEmpty());assertEquals(0,component.state()["transactionCount"])
        for(bad in listOf(null,-1L,0x100000000L,"0",1.5))assertFails{ModeWordInput.load(Context(bad),"mode")}
        assertFails{ModeWordInput.load(Context(0,0x100000000L),"mode")};assertFails{f.create(Context(),mapOf("profile" to "disabled","inputFile" to "","controlWordInputFile" to "mode"))}
    }
    @Test fun legacyPrefixAndHalfPairRestoreIntoExactCase4Tail() {
        val config = mapOf("profile" to VglibInitFactory.FUNCTIONAL_PROFILE,"inputFile" to "bank","controlWordInputFile" to "mode")
        val oldContext = Context()
        val old = VglibInitFactory().create(oldContext,config) as StatefulComponent
        val oldPort = oldContext.ports.find(ModeWordInput.PORT,MmioDevice::class.java)!!
        oldPort.writeAfterRead()
        val prefix = old.saveSnapshot()
        val second = 0xffff83abL
        val extra = mapOf("profile" to ModeWordInput.TWO_PAIR_PROFILE,"secondReadSample" to second)
        val context = Context(extra = extra)
        val extended = VglibInitFactory().create(context,config) as StatefulComponent
        val port = context.ports.find(ModeWordInput.PORT,MmioDevice::class.java)!!
        extended.restoreSnapshot(prefix)
        val before = extended.saveSnapshot()
        context.pc = 0x5af98
        assertFailsWith<IllegalArgumentException> { port.read(0,4) }
        assertEquals(before,extended.saveSnapshot())
        context.pc = 0x5afe0
        assertEquals(second,port.read(0,4))
        val half = extended.saveSnapshot()
        assertFailsWith<IllegalArgumentException> { port.write(0,4,second and 0xfffffcffL) }
        context.pc = 0x5af98
        assertFailsWith<IllegalArgumentException> { port.write(0,4,second) }
        assertEquals(half,extended.saveSnapshot())
        extended.restoreSnapshot(half)
        port.write(0,4,second and 0xfffffcffL)
        val done = extended.saveSnapshot()
        extended.restoreSnapshot(done)
        assertFailsWith<IllegalStateException> { port.read(0,4) }
        assertFailsWith<IllegalArgumentException> { old.restoreSnapshot(done) }
        val changed = VglibInitFactory().create(Context(extra = extra + ("secondReadSample" to 0L)),config) as StatefulComponent
        assertFailsWith<IllegalArgumentException> { changed.restoreSnapshot(half) }
        val differentFirst = VglibInitFactory().create(Context(sample = 0L, extra = extra),config) as StatefulComponent
        assertFailsWith<IllegalArgumentException> { differentFirst.restoreSnapshot(prefix) }
        assertFailsWith<IllegalArgumentException> { VglibInitFactory().create(Context(extra = extra), config + ("profile" to VglibInitFactory.PROFILE)) }
    }

    @Test fun thirdPairResumesV2AndAppendsOnlyAfterTheEntireSelectedPrefix() {
        val second = mapOf("profile" to ModeWordInput.TWO_PAIR_PROFILE, "secondReadSample" to 0L)
        val context = Context(extra = second)
        val legacy = ModeWordInput.load(context, "mode")!!
        repeat(2) {
            context.pc = 0x5afe0
            val sample = legacy.read(0, 4)
            context.pc = 0x5af98
            legacy.write(0, 4, sample and 0xfffffcffL)
        }
        val prefix = legacy.saveSnapshotState()
        fun tail(vararg samples: Long): Pair<Context, ModeWordInput> {
            val selected = Context(extra = second + mapOf("profile" to ModeWordInput.TAIL_PROFILE,
                "additionalReadSamples" to samples.toList()))
            return selected to ModeWordInput.load(selected, "mode")!!
        }
        val (location, third) = tail(0xffff03ff)
        third.applySnapshotState(third.parseSnapshotState(prefix))
        location.pc = 0x5af98
        assertFailsWith<IllegalArgumentException> { third.read(0, 4) }
        location.pc = 0x5afe0
        assertEquals(0xffff03ff, third.read(0, 4))
        val half = third.saveSnapshotState()
        assertFailsWith<IllegalArgumentException> { third.write(0, 4, 0xffff00ff) }
        location.pc = 0x5af98
        assertFailsWith<IllegalArgumentException> { third.write(0, 4, 0xffff03ff) }
        assertEquals(half, third.saveSnapshotState())
        third.write(0, 4, 0xffff00ff)
        assertFailsWith<IllegalStateException> { third.read(0, 4) }
        val (nextLocation, fourth) = tail(0xffff03ff, 7)
        fourth.applySnapshotState(fourth.parseSnapshotState(half))
        nextLocation.pc = 0x5af98
        fourth.write(0, 4, 0xffff00ff)
        nextLocation.pc = 0x5afe0
        assertEquals(7L, fourth.read(0, 4))
        val partial = fourth.saveSnapshotState()
        assertFailsWith<IllegalArgumentException> { third.parseSnapshotState(partial) }
        val changed = tail(0xffff03ff, 8).second
        val unused = fourth.apply { applySnapshotState(parseSnapshotState(half)) }.saveSnapshotState()
        assertFailsWith<IllegalArgumentException> { changed.parseSnapshotState(unused) }
        assertFailsWith<IllegalArgumentException> { third.parseSnapshotState(prefix + ("position" to 5)) }
        assertFailsWith<IllegalArgumentException> { tail() }
        assertFailsWith<IllegalArgumentException> { tail(-1L) }
        assertFailsWith<IllegalArgumentException> { tail(*LongArray(17)) }
        assertEquals(36, tail(*LongArray(16)).second.state()["transactionCapacity"])
    }

    @Test fun continuousModeRetainsModeWordPastFiniteCapacity() {
        val ctx = Context(sample = 0x112203ffL)
        val input = ModeWordInput.load(ctx, "mode", continuous = true)!!
        ctx.pc = 0x5afe0
        assertEquals(0x112203ffL, input.read(0, 4))
        ctx.pc = 0x5af98
        input.write(0, 4, 0x112200ffL)
        // Past the finite 2-transaction capacity, continuous mode retains written values across repeated cycles
        ctx.pc = 0x5afe0
        assertEquals(0x112200ffL, input.read(0, 4))
        ctx.pc = 0x5af98
        input.write(0, 4, 0x112202ffL)
        ctx.pc = 0x5afe0
        assertEquals(0x112202ffL, input.read(0, 4))
    }

    @Test fun continuousModeAcceptsDirectModeSwitchRmwAtAnyPcAndRoundTripsSnapshot() {
        val ctx = Context(
            sample = 0L,
            extra = mapOf(
                "profile" to ModeWordInput.TAIL_PROFILE,
                "secondReadSample" to 0L,
                "additionalReadSamples" to listOf(0L, 0L),
            ),
        )
        val input = ModeWordInput.load(ctx, "mode", continuous = true)!!
        // First pair runs through normal helper PC
        ctx.pc = 0x5afe0
        assertEquals(0L, input.read(0, 4))
        ctx.pc = 0x5af98
        input.write(0, 4, 0L)
        // Mode switch at direct firmware PC (0x01255730 / 0x0125587c) with non-masked value 0x200
        // must transition cleanly to retained mode instead of failing helper-PC/direction checks
        ctx.pc = 0x01255730
        assertEquals(0L, input.read(0, 4))
        ctx.pc = 0x0125587c
        input.write(0, 4, 0x200L)
        assertEquals(0x200L, input.read(0, 4))
        input.write(0, 4, 0L)
        assertEquals(0L, input.read(0, 4))

        val snap = input.saveSnapshotState()
        val restored = ModeWordInput.load(ctx, "mode", continuous = true)!!
        restored.applySnapshotState(restored.parseSnapshotState(snap))
        assertEquals(0L, restored.read(0, 4))
        restored.write(0, 4, 0x200L)
        assertEquals(0x200L, restored.read(0, 4))
    }

    private fun MmioDevice.writeAfterRead() {
        write(0,4,read(0,4) and 0xfffffcffL)
    }
}
