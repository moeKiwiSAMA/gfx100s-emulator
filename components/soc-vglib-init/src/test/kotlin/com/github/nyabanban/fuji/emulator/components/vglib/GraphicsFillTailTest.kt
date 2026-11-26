package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class GraphicsFillTailTest {
    private class Fixture(selected: Boolean, val execution: Map<String, Any>? = null, val copyWords: List<Int>? = null,
                          val commandWords: List<Int>? = null) : ComponentContext {
        private val entries = mutableMapOf<String,Any>()
        var pc = 0x1401df0L
        override val clock = EmulationClock { 50L }
        override val directory = Path.of(".")
        override val events: EventChannel get() = error("No graphics event expected")
        override val ports = object : Ports {
            override fun <T:Any> register(owner:String,name:String,type:Class<T>,port:T) {
                check(entries.putIfAbsent(name,port) == null)
            }
            override fun <T:Any> find(name:String,type:Class<T>):T? = if(name == "cpu.execution-location")
                type.cast(ExecutionLocationSource { ExecutionLocation(0,pc) }) else entries[name]?.let(type::cast)
            override fun removeOwner(owner:String) = entries.clear()
        }
        override fun dataPath(relative:String) = directory.resolve(relative)
        override fun readData(relative:String) = if(relative == "graphics-fill.yaml") mapOf("profile" to GraphicsFillTail.PROFILE,
            "initialWords" to listOf(10,20,30,40),"statusReads" to listOf(7),"accessLimit" to 12) +
            (execution?.let { mapOf("execution" to it) } ?: emptyMap()) +
            (commandWords?.let { mapOf("commandInitialWords" to it) } ?: emptyMap()) +
            (copyWords?.let { mapOf("copyInitialWords" to it) } ?: emptyMap()) else mapOf("schema" to 1,"profile" to VglibInitFactory.PROFILE,
            "readConfidence" to "HYPOTHESIS","transactions" to List(18){mapOf("offset" to 0,"readValue" to 1,"writeValue" to 2)})
        val component = VglibInitFactory().create(this,mapOf("profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
            "inputFile" to "bank") + if(selected) mapOf(GraphicsFillTail.SETTING to GraphicsFillTail.PROFILE) else emptyMap()) as StatefulComponent
        val bank = checkNotNull(ports.find("soc-vglib-init.registers",MmioDevice::class.java))
    }

    @Test fun copyRegistersRestoreOnlyDeclaredWordsAndRecordTheSubmittedValues() {
        val original = Fixture(true)
        repeat(18) { original.bank.read(0,4); original.bank.write(0,4,2) }
        assertFails { original.bank.read(0xc00,4) }
        val selected = Fixture(true, copyWords = (1..9).toList())
        selected.component.restoreSnapshot(original.component.saveSnapshot())
        assertEquals(1L, selected.bank.read(0xc00,4))
        selected.bank.write(0xc00,4,0x681000)
        selected.bank.write(0xc04,4,0x701000)
        selected.bank.write(0xc18,4,1)
        val saved = selected.component.saveSnapshot()
        assertFails { selected.bank.write(0xc14,4,1) }
        assertFails { selected.bank.read(0xc00,2) }
        assertEquals(saved, selected.component.saveSnapshot())
        selected.component.restoreSnapshot(saved)
        assertEquals(0x701000L, selected.bank.read(0xc04,4))
        val state = selected.component.state()["graphicsFill"] as Map<*,*>
        assertEquals(listOf(listOf(0x681000L,0x701000L,3L,4L,5L,1L,7L,8L,9L)), state["copySubmissions"])
        assertFails { original.component.restoreSnapshot(saved) }
    }

    @Test fun commandStartsRetainBothDescriptorsAcrossLegacyAndMidCommandRestore() {
        val old = Fixture(true)
        repeat(18) { old.bank.read(0,4); old.bank.write(0,4,2) }
        val prefix = old.component.saveSnapshot()
        assertFails { old.bank.read(0x1214,4) }
        val initial = (2..9).toList()
        val first = Fixture(true,commandWords = initial)
        first.component.restoreSnapshot(prefix)
        assertEquals(2L,first.bank.read(0x1214,4))
        first.bank.write(0x1214,4,0x100ffff)
        first.bank.write(0x1298,4,0)
        first.bank.write(0x10d4,4,0xabc0)
        first.bank.write(0x10dc,4,19)
        val saved = first.component.saveSnapshot()
        val second = Fixture(true,commandWords = initial)
        second.component.restoreSnapshot(saved)
        assertFails { old.component.restoreSnapshot(saved) }
        assertFails { second.bank.write(0x10d0,4,2) }
        assertFails { second.bank.write(0x10dc,4,0x80000000L) }
        assertFails { second.bank.read(0x10d8,4) }
        assertFails { second.bank.read(0x10dc,2) }
        val tail = saved["graphicsFill"] as Map<*, *>
        assertFails { second.component.restoreSnapshot(saved + ("graphicsFill" to tail - "commandInitialWords")) }
        assertEquals(saved,second.component.saveSnapshot())
        second.bank.write(0x10d0,4,1)
        second.bank.write(0x10e4,4,0xf000)
        second.bank.write(0x10ec,4,27)
        second.bank.write(0x10e0,4,1)
        val end = second.component.saveSnapshot()
        first.component.restoreSnapshot(end)
        val state = first.component.state()["graphicsFill"] as Map<*, *>
        val starts = (state["commandStarts"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf(0xabc0L to 19L,0xf000L to 27L), starts.map { it["address"] to it["countWord"] })
        assertEquals(listOf(0x10d0L,0x10e0L),starts.map { it["offset"] })
        assertEquals(emptyList<Any>(),state["submissions"])
        assertEquals(1L,first.bank.read(0x10d0,4))
    }

    @Test fun twoRegisterSubmissionsPreflightAndReadOnlyTheirTargetsAcrossRestore() {
        val config = mapOf("profile" to GraphicsFillExecution.PROFILE, "submissionLimit" to 2, "surfaceBase" to 128, "surfaceBytes" to 64)
        val retained = Fixture(true)
        repeat(18) { retained.bank.read(0,4); retained.bank.write(0,4,2) }
        retained.bank.write(16,4,132)
        retained.bank.write(20,4,156)
        retained.bank.write(24,4,0xfedcba98)
        val prefix = retained.component.saveSnapshot()
        val selected = Fixture(true,config)
        selected.component.restoreSnapshot(prefix)
        val bytes = ByteArray(256) { 0x55 }
        var denied = true
        val operations = mutableListOf<String>()
        val memory = object: DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                operations += "check:$address:$size:$write"
                require(!(denied && !write && address == 132L))
            }
            override fun read(address: Long, destination: ByteBuffer) {
                operations += "read:$address"
                destination.put(bytes,address.toInt(),destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                operations += "write:$address"
                source.get(bytes,address.toInt(),source.remaining())
            }
        }
        selected.ports.register("test","cpu.memory",DmaMemory::class.java,memory)
        val before = selected.component.saveSnapshot()
        fun submit() = selected.bank.write(28,4,0x0f100201)
        fun refuses() {
            val unchanged = bytes.copyOf()
            assertFails { submit() }
            assertEquals(before,selected.component.saveSnapshot())
            assertContentEquals(unchanged,bytes)
        }
        assertFailsWith<IllegalArgumentException> { selected.bank.write(28,4,0x0f100211) }
        assertEquals(before,selected.component.saveSnapshot())
        assertTrue(operations.isEmpty())
        refuses()
        assertFalse(operations.any { it.startsWith("write:") })
        denied = false
        operations.clear()
        submit()
        assertEquals(listOf("check:132:24:true","check:132:24:false","write:132","read:132"),operations)
        assertContentEquals(ByteArray(24) { byteArrayOf(0x98.toByte(),0xba.toByte(),0xdc.toByte(),0xfe.toByte())[it%4] },bytes.copyOfRange(132,156))
        assertEquals(0x55,bytes[131].toInt())
        assertEquals(0x55,bytes[156].toInt())
        val saved = selected.component.saveSnapshot()
        val tail = saved["graphicsFill"] as Map<*,*>
        val proof = (tail["readbacks"] as List<*>).single() as Map<*,*>
        assertEquals("98badcfe".repeat(4),proof["firstBytes"])
        assertEquals("NONE",proof["completionEvents"])
        val restored = Fixture(true,config)
        restored.component.restoreSnapshot(saved)
        assertEquals(saved,restored.component.saveSnapshot())
        restored.ports.register("test","cpu.memory",DmaMemory::class.java,memory)
        restored.bank.write(16,4,164)
        restored.bank.write(20,4,196)
        operations.clear()
        assertFailsWith<IllegalArgumentException> { restored.bank.write(28,4,0x0f100201) }
        assertTrue(operations.isEmpty())
        restored.bank.write(20,4,172)
        restored.bank.write(24,4,0x44332211)
        restored.bank.write(28,4,0x0f100201)
        assertEquals(listOf("check:164:8:true","check:164:8:false","write:164","read:164"),operations)
        val twice = restored.component.saveSnapshot()
        val receipts = (twice["graphicsFill"] as Map<*,*>)["readbacks"] as List<*>
        assertEquals(proof,receipts.first())
        assertEquals("1122334411223344",(receipts.last() as Map<*,*>)["firstBytes"])
        val afterSecond = Fixture(true,config)
        afterSecond.component.restoreSnapshot(twice)
        assertEquals(twice,afterSecond.component.saveSnapshot())
        val increased = Fixture(true, config + ("submissionLimit" to 3))
        operations.clear()
        increased.component.restoreSnapshot(twice)
        val promoted = increased.component.saveSnapshot()
        val promotedTail = promoted["graphicsFill"] as Map<*, *>
        assertEquals(receipts, promotedTail["readbacks"])
        assertEquals(3, (promotedTail["execution"] as Map<*, *>)["submissionLimit"])
        assertFailsWith<IllegalArgumentException> { afterSecond.component.restoreSnapshot(promoted) }
        val forged = promotedTail + ("execution" to config + ("submissionLimit" to 1))
        assertFailsWith<IllegalArgumentException> { increased.component.restoreSnapshot(twice + ("graphicsFill" to forged)) }
        assertEquals(promoted, increased.component.saveSnapshot())
        assertTrue(operations.isEmpty())
        increased.ports.register("test","cpu.memory",DmaMemory::class.java,memory)
        increased.bank.write(28,4,GraphicsFillExecution.CONTROL)
        assertFailsWith<IllegalStateException> { increased.bank.write(28,4,GraphicsFillExecution.CONTROL) }
        val appended = config + mapOf("submissionLimit" to 3, "additionalSurfaces" to listOf(mapOf("surfaceBase" to 208, "surfaceBytes" to 16)))
        val branch = Fixture(true, appended)
        branch.component.restoreSnapshot(twice)
        branch.ports.register("test","cpu.memory",DmaMemory::class.java,memory)
        branch.bank.write(16,4,208)
        branch.bank.write(20,4,224)
        operations.clear()
        branch.bank.write(28,4,GraphicsFillExecution.CONTROL)
        assertEquals(listOf("check:208:16:true","check:208:16:false","write:208","read:208"), operations)
        assertEquals(0x55,bytes[207].toInt())
        assertEquals(0x55,bytes[224].toInt())
        assertContentEquals(ByteArray(16) { byteArrayOf(0x11,0x22,0x33,0x44)[it%4] },bytes.copyOfRange(208,224))
        val extended = branch.component.saveSnapshot()
        val extendedTail = extended["graphicsFill"] as Map<*, *>
        assertEquals(receipts, (extendedTail["readbacks"] as List<*>).take(2))
        operations.clear()
        branch.component.restoreSnapshot(extended)
        val falsePrefix = extendedTail + ("execution" to (appended - "additionalSurfaces"))
        assertFails { branch.component.restoreSnapshot(extended + ("graphicsFill" to falsePrefix)) }
        assertFails { increased.component.restoreSnapshot(extended) }
        assertEquals(extended, branch.component.saveSnapshot())
        assertTrue(operations.isEmpty())
        assertFailsWith<IllegalStateException> { restored.bank.write(28,4,0x0f100201) }
        assertEquals(7L,restored.bank.read(52,4))
        val invalidProof = proof + ("sha256" to "")
        assertFails { restored.component.restoreSnapshot(saved + ("graphicsFill" to (tail + ("readbacks" to listOf(invalidProof))))) }
        assertFails { retained.component.restoreSnapshot(saved) }
        retained.bank.write(28,4,0x0f100201)
        assertFails { selected.component.restoreSnapshot(retained.component.saveSnapshot()) }
    }

    @Test fun expandedHeapSurfaceMustContainEachSavedRangeInFull() {
        fun model(vararg ranges: Pair<Int, Int>) = GraphicsFillExecution(mapOf(
            "profile" to GraphicsFillExecution.PROFILE, "submissionLimit" to 4, "surfaceBase" to 128, "surfaceBytes" to 16,
            "additionalSurfaces" to ranges.map { (base, bytes) -> mapOf("surfaceBase" to base, "surfaceBytes" to bytes) }))
        val saved = model(256 to 16, 272 to 16)
        model(252 to 36).requireCompatiblePrefix(saved)
        assertFailsWith<IllegalArgumentException> { model(252 to 32).requireCompatiblePrefix(saved) }
        assertFailsWith<IllegalArgumentException> { model(252 to 16, 268 to 20).requireCompatiblePrefix(saved) }
    }

    @Test fun fillMustFitOneDeclaredRangeEvenWhenTwoRangesTouch() {
        val config = mapOf("profile" to GraphicsFillExecution.PROFILE, "submissionLimit" to 4, "surfaceBase" to 128, "surfaceBytes" to 16)
        fun surface(base: Long, bytes: Long = 16) = mapOf("surfaceBase" to base, "surfaceBytes" to bytes)
        for (invalid in listOf(null, emptyList<Any>(), listOf(surface(128)), listOf(surface(144),surface(148)),
                listOf(surface(145)), listOf(surface(144,0)), listOf(surface(0xfffffff4L)), List(16) { surface(256+16L*it) })) {
            assertFailsWith<IllegalArgumentException> { GraphicsFillExecution(config + ("additionalSurfaces" to invalid)) }
        }
        val model = GraphicsFillExecution(config + ("additionalSurfaces" to listOf(surface(144),surface(176))))
        val noDma = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) = error("Unexpected preflight")
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
            override fun read(address: Long, destination: ByteBuffer) = error("Unexpected read")
        }
        for ((start,end) in listOf(140L to 148L,156L to 180L,172L to 180L,176L to 196L)) {
            assertFailsWith<IllegalArgumentException> { model.execute(noDma,longArrayOf(start,end,0),GraphicsFillExecution.CONTROL) }
        }
    }

    @Test fun explicitSpanAllowsOnlyTheSubmittedRangeAcrossTheSeam() {
        val config = mapOf("profile" to GraphicsFillExecution.PROFILE, "submissionLimit" to 2,
            "surfaceBase" to 128L, "surfaceBytes" to 16L, "additionalSurfaces" to listOf(
                mapOf("surfaceBase" to 256L,"surfaceBytes" to 16L),mapOf("surfaceBase" to 272L,"surfaceBytes" to 32L)))
        fun model(start: Long, end: Long) = GraphicsFillExecution(config +
            ("crossSurfaceFills" to listOf(mapOf("start" to start,"end" to end))))
        val selected = model(268,280)
        val legacy = GraphicsFillExecution(config)
        selected.requireCompatiblePrefix(legacy)
        assertFails { legacy.requireCompatiblePrefix(selected) }
        assertFails { model(140,260) }
        assertFails { model(268,308) }
        assertFails { model(268,279) }
        val bytes = ByteArray(320) { 0x55 }
        val checked = mutableListOf<Boolean>()
        val memory = object : DmaMemory {
            override fun check(address: Long,size: Long,write: Boolean) {
                assertEquals(268L,address)
                assertEquals(12L,size)
                checked += write
            }
            override fun write(address: Long,source: ByteBuffer) {
                assertEquals(listOf(true,false),checked)
                source.get(bytes,address.toInt(),source.remaining())
            }
            override fun read(address: Long,destination: ByteBuffer) {
                destination.put(bytes,address.toInt(),destination.remaining())
            }
        }
        val control = GraphicsFillExecution.CONTROL
        assertFailsWith<IllegalArgumentException> { selected.execute(memory,longArrayOf(264,280,0),control) }
        assertFailsWith<IllegalArgumentException> { selected.execute(memory,longArrayOf(268,276,0),control) }
        assertTrue(checked.isEmpty())
        val receipt = selected.execute(memory,longArrayOf(268,280,0x11223344),control)
        assertEquals(0x55,bytes[267].toInt())
        assertEquals(0x55,bytes[280].toInt())
        assertContentEquals(byteArrayOf(0x44,0x33,0x22,0x11),bytes.copyOfRange(272,276))
        val submission = mapOf("control" to control,"start" to 268L,"end" to 280L,"packed" to 0x11223344L)
        assertEquals(receipt,GraphicsFillExecution(selected.settings()).restore(receipt,submission))
        assertFails { legacy.restore(receipt,submission) }
    }

    @Test fun retainedWordsAndStatusCursorRestoreWithActualSubmissionHistory() {
        val legacy = Fixture(false)
        repeat(18) {
            assertEquals(1L,legacy.bank.read(0,4))
            legacy.bank.write(0,4,2)
        }
        val exhausted = legacy.component.saveSnapshot()
        assertFailsWith<IllegalStateException> { legacy.bank.read(16,4) }
        val selected = Fixture(true)
        selected.component.restoreSnapshot(exhausted)
        assertEquals(exhausted["sequence"],selected.component.saveSnapshot()["sequence"])
        fun refuses(action: () -> Unit) {
            val before = selected.component.saveSnapshot()
            assertFails { action() }
            assertEquals(before,selected.component.saveSnapshot())
        }
        refuses { selected.bank.read(16,2) }
        refuses { selected.bank.read(17,4) }
        refuses { selected.bank.write(52,4,0) }
        refuses { selected.bank.write(16,4,0x100000000L) }
        assertEquals(10L,selected.bank.read(16,4))
        selected.bank.write(16,4,256)
        selected.bank.write(20,4,1024)
        selected.bank.write(24,4,255)
        selected.bank.write(28,4,3)
        assertEquals(7L,selected.bank.read(52,4))
        val half = selected.component.saveSnapshot()
        val fresh = Fixture(true)
        fresh.component.restoreSnapshot(half)
        assertEquals(half,fresh.component.saveSnapshot())
        refuses { selected.component.restoreSnapshot(half + ("graphicsFill" to listOf(51L))) }
        refuses { selected.bank.read(52,4) }
        assertEquals(255L,selected.bank.read(24,4))
        selected.bank.write(16,4,2048)
        selected.bank.write(20,4,4096)
        selected.bank.write(24,4,0xabcd)
        selected.bank.write(28,4,0)
        selected.bank.write(28,4,9)
        val state = (selected.component as HardwareComponent).state()["graphicsFill"] as Map<*, *>
        val commits = (state["submissions"] as List<*>).map { it as Map<*,*> }
        assertEquals(listOf(256L,2048L),commits.map { it["start"] })
        assertEquals(listOf(1024L,4096L),commits.map { it["end"] })
        assertEquals(listOf(255L,0xabcdL),commits.map { it["packed"] })
        assertEquals(listOf(3L,9L),commits.map { it["control"] })
        refuses { selected.bank.read(16,4) }
        assertFails { legacy.component.restoreSnapshot(selected.component.saveSnapshot()) }
    }

    @Test fun commandPairLimitUpTo64AndRgbaSolidUpgradeRestore() {
        val baseSettings = mapOf(
            "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
            "inputFile" to "bank",
            GraphicsFillTail.SETTING to GraphicsFillTail.PROFILE,
        )
        fun createWithData(exec: String): StatefulComponent {
            val baseCtx = LcdcFunctionalTest.Context().also { it.entries.clear() }
            val ctx = object : ComponentContext by baseCtx {
                private val portsMap = mutableMapOf<String, Any>()
                override val ports = object : Ports {
                    override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                        check(portsMap.putIfAbsent(name, port) == null)
                    }
                    override fun <T : Any> find(name: String, type: Class<T>): T? = portsMap[name]?.let(type::cast)
                    override fun removeOwner(owner: String) = portsMap.clear()
                }
                override fun readData(relative: String): Map<String, Any?> = when (relative) {
                    "graphics-fill.yaml" -> mapOf(
                        "profile" to GraphicsFillTail.PROFILE,
                        "initialWords" to listOf(10, 20, 30, 40),
                        "statusReads" to listOf(7),
                        "accessLimit" to 12,
                        "copyInitialWords" to List(9) { 0 },
                        "commandInitialWords" to List(8) { 0 },
                        "commandPairLimit" to 64,
                        "copyExecution" to mapOf(
                            "profile" to "same-geometry-shared-ram-copy-experimental-v1",
                            "sourceBase" to 0x06893000L,
                            "destinationBase" to 0x06140000L,
                            "surfaceBytes" to 0x300000L,
                            "submissionLimit" to 4,
                            "mode2Workspace" to mapOf("base" to 0x06fe6000L, "bytes" to 0xa00000L),
                        ),
                        "commandExecution" to exec,
                    )
                    else -> mapOf(
                        "schema" to 1,
                        "profile" to VglibInitFactory.PROFILE,
                        "readConfidence" to "HYPOTHESIS",
                        "transactions" to List(18) { mapOf("offset" to 0, "readValue" to 1, "writeValue" to 2) },
                    )
                }
            }
            return VglibInitFactory().create(ctx, baseSettings) as StatefulComponent
        }
        val older = createWithData(SolidCommandRects.PROFILE)
        val saved = older.saveSnapshot()
        val newer = createWithData(SolidCommandRects.RGBA_PROFILE)
        newer.restoreSnapshot(saved)
        assertEquals(SolidCommandRects.RGBA_PROFILE, ((newer.state()["graphicsFill"] as Map<*, *>)["commandExecution"]))
    }
}
