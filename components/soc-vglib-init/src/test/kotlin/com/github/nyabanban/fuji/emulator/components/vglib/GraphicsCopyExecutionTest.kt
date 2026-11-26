package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.GraphicsSuccessors
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.*
import org.junit.jupiter.api.Test

class GraphicsCopyExecutionTest {
    private val bytes = 0x300000
    private val source = 0xffd00000L
    private val destination = 0x400000L
    private val alternate = destination + bytes
    private val config = mapOf("profile" to GraphicsCopyExecution.PROFILE, "sourceBase" to source,
        "destinationBase" to destination, "surfaceBytes" to bytes, "submissionLimit" to 2)
    private val words = longArrayOf(source,destination,0x03000400,0x03000400,1,1,0,0,0)
    private val execution = GraphicsCopyExecution(config)

    private inner class Ram : DmaMemory {
        val input = ByteArray(bytes) { (it xor (it ushr 9) xor (it ushr 17)).toByte() }
        val output = ByteArray(bytes + 2) { 0x55 }
        val alternateOutput = ByteArray(bytes + 2) { 0x33 }
        val operations = mutableListOf<String>()
        var deny = ""
        var shortSource = false
        var corruptReadback = false
        override fun check(address: Long, size: Long, write: Boolean) {
            assertEquals(bytes.toLong(),size)
            val operation = "check:$address:$write"
            operations += operation
            require(operation != deny)
        }
        override fun read(address: Long, destination: ByteBuffer) {
            operations += "read:$address"
            when (address) {
                source -> destination.put(input,0,if (shortSource) bytes-1 else bytes)
                this@GraphicsCopyExecutionTest.destination, alternate -> {
                    val selected = if (address == alternate) alternateOutput else output
                    destination.put(selected,1,bytes)
                    if (corruptReadback) destination.put(bytes/2,(selected[1+bytes/2].toInt() xor 1).toByte())
                }
                else -> error("Unexpected graphics copy read")
            }
        }
        override fun write(address: Long, source: ByteBuffer) {
            require(address == destination || address == alternate)
            operations += "write:$address"
            source.get(if (address == alternate) alternateOutput else output,1,bytes)
        }
    }

    @Test fun copiesNonuniformSourceChecksBothEndsAndReadsBackActualDestination() {
        val ram = Ram()
        val original = ram.input.copyOf()
        val proof = execution.execute(ram,words)
        assertEquals(listOf("check:$source:false","check:$destination:true","check:$destination:false",
            "read:$source","write:$destination","read:$destination"),ram.operations)
        assertContentEquals(original,ram.input)
        assertContentEquals(original,ram.output.copyOfRange(1,bytes+1))
        assertEquals(0x55,ram.output.first().toInt())
        assertEquals(0x55,ram.output.last().toInt())
        val hex = HexFormat.of()
        assertEquals(hex.formatHex(MessageDigest.getInstance("SHA-256").digest(original)),proof["sha256"])
        assertEquals(proof["sha256"],proof["sourceSha256"])
        assertEquals(hex.formatHex(original,0,16),proof["firstBytes"])
        assertEquals(hex.formatHex(original,bytes-16,bytes),proof["lastBytes"])
        assertEquals("NONE",proof["completionEvents"])
        assertEquals(proof,execution.restore(proof + ("bytes" to bytes),words.toList()))
        for (bad in listOf(proof + ("source" to destination), proof + ("sourceSha256" to "0".repeat(64)),
                proof + ("firstBytes" to ""), proof + ("bytes" to 0))) {
            assertFails { execution.restore(bad,words.toList()) }
        }
        ram.corruptReadback = true
        assertFailsWith<IllegalStateException> { execution.execute(ram,words) }
    }

    @Test fun rejectsUnqualifiedRegistersAndSharedRamHolesBeforeWriting() {
        val ram = Ram()
        for (index in words.indices) {
            val bad = words.copyOf()
            bad[index] = bad[index] xor 1L
            assertFailsWith<IllegalArgumentException> { execution.execute(ram,bad) }
        }
        assertTrue(ram.operations.isEmpty())
        for (deny in listOf("check:$source:false","check:$destination:true","check:$destination:false")) {
            ram.deny = deny
            assertFails { execution.execute(ram,words) }
        }
        assertTrue(ram.operations.none { it.startsWith("write:") || it.startsWith("read:") })
        ram.deny = ""
        ram.shortSource = true
        assertFailsWith<IllegalStateException> { execution.execute(ram,words) }
        assertTrue(ram.output.all { it.toInt() == 0x55 })
    }

    @Test fun refusesOverlappingUnalignedAndOverflowingConfiguration() {
        for (change in listOf("sourceBase" to destination+4, "destinationBase" to destination+1,
                "sourceBase" to source+4, "surfaceBytes" to bytes+4, "submissionLimit" to GraphicsSuccessors.SUBMISSION_LIMIT + 1)) {
            assertFails { GraphicsCopyExecution(config + change) }
        }
        assertEquals(2, GraphicsCopyExecution(execution.settings()).submissionLimit)
    }

    @Test fun mode2SwizzlesWholeWordsLikeTheDatFallbackAndRestoresItsOwnLayout() {
        val tileSource = 0x10000L
        val tileDestination = 0x20000L
        val pair = mapOf("sourceBase" to tileSource, "destinationBase" to tileDestination)
        val selected = GraphicsCopyExecution(config + ("mode2Morton8x8" to pair))
        val command = longArrayOf(tileSource,tileDestination,0x2000f0,0x2000f0,2,1,0,0,0)
        val linear = ByteBuffer.allocate(30720).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(7680) { linear.putInt(it) }
        val result = ByteArray(30722) { 42 }
        val checks = mutableListOf<Pair<Long, Boolean>>()
        var denyReadback = true
        var corruptReadback = false
        val memory = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                assertEquals(30720L, size)
                checks += address to write
                require(!denyReadback || address != tileDestination || write)
            }
            override fun read(address: Long, destination: ByteBuffer) {
                when (address) {
                    tileSource -> destination.put(linear.array())
                    tileDestination -> {
                        destination.put(result,1,30720)
                        if (corruptReadback) destination.put(100, 42)
                    }
                    else -> error("unexpected source")
                }
            }
            override fun write(address: Long, source: ByteBuffer) {
                assertEquals(tileDestination,address)
                source.get(result,1,30720)
            }
        }
        assertFails { execution.execute(memory,command) }
        for (index in command.indices) {
            assertFails { selected.execute(memory,command.copyOf().also { it[index] = it[index] xor 1L }) }
        }
        assertTrue(checks.isEmpty())
        assertFails { selected.execute(memory,command) }
        assertEquals(listOf(tileSource to false, tileDestination to true, tileDestination to false), checks)
        assertTrue(result.all { it.toInt() == 42 })
        denyReadback = false
        val receipt = selected.execute(memory,command)
        assertEquals("d536d355243cbed5f6aa24d30ba9123f1206dda921d9316993eb623041a383ab",receipt["sha256"])
        assertNotEquals(receipt["sourceSha256"],receipt["sha256"])
        assertEquals(42,result.first().toInt())
        assertEquals(42,result.last().toInt())
        assertEquals(receipt,selected.restore(receipt,command.toList()))
        assertFails { selected.restore(receipt + ("expectedSha256" to receipt.getValue("sourceSha256")),command.toList()) }
        assertFails { selected.restore(receipt + ("rows" to 31),command.toList()) }
        assertFails { execution.restore(receipt,command.toList()) }
        selected.requireCompatiblePrefix(execution)
        assertFails { execution.requireCompatiblePrefix(selected) }
        val moved = GraphicsCopyExecution(config + ("mode2Morton8x8" to pair + ("destinationBase" to 0x30000L)))
        assertFails { moved.requireCompatiblePrefix(selected) }
        for (bad in listOf(tileSource+4, tileDestination+1, 0xfffffff0L)) {
            assertFails { GraphicsCopyExecution(config + ("mode2Morton8x8" to pair + ("destinationBase" to bad))) }
        }
        corruptReadback = true
        assertFailsWith<IllegalStateException> { selected.execute(memory,command) }
    }

    @Test fun additionalSurfacesMustBeFiniteAlignedDisjointU32Ranges() {
        for (invalid in listOf(null, emptyList<Long>(), listOf(alternate.toDouble()), listOf(alternate + 1),
                listOf(source), listOf(destination), listOf(alternate, alternate + bytes - 4),
                listOf(source + 4), listOf(-4L), List(16) { alternate + it.toLong() * bytes })) {
            assertFailsWith<IllegalArgumentException> {
                GraphicsCopyExecution(config + ("additionalDestinationBases" to invalid))
            }
        }
        val maximum = List(15) { alternate + it.toLong() * bytes }
        assertEquals(maximum, GraphicsCopyExecution(config + ("additionalDestinationBases" to maximum))
            .settings()["additionalDestinationBases"])
    }

    @Test fun workspaceChecksWholeTransfersAndRestoresTheSubmittedDimensions() {
        val base = 0xffff0000L
        val size = 65536
        val settings = mapOf("base" to base, "bytes" to size)
        val selected = GraphicsCopyExecution(config + ("mode2Workspace" to settings))
        val legacy = GraphicsCopyExecution(config + ("mode2Morton8x8" to mapOf("sourceBase" to base, "destinationBase" to base + 32768)))
        selected.requireCompatiblePrefix(legacy)
        assertFails { legacy.requireCompatiblePrefix(selected) }
        val smaller = GraphicsCopyExecution(config + ("mode2Workspace" to settings + ("bytes" to 60000)))
        assertFails { smaller.requireCompatiblePrefix(legacy) }
        assertFails { smaller.requireCompatiblePrefix(selected) }
        val checks = mutableListOf<Triple<Long,Long,Boolean>>()
        val data = ByteArray(size)
        var hole = false
        val ram = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                checks += Triple(address,size,write)
                require(!hole || write || address == base)
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(data,(address-base).toInt(),destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                source.get(data,(address-base).toInt(),source.remaining())
            }
        }
        val command = longArrayOf(base,base+size-8192,0x200040,0x200040,2,1,0,0,0)
        for (packed in listOf(0, 0x200000, 0x000040, 0x210040, 0x280040, 0x280088, 0x280098, 0x300090,
                0x1f0040, 0x20003f, 0x2000f8, 0xffffffffL, 0x100200040L)) {
            assertFails { selected.execute(ram, command.copyOf().also { it[2] = packed; it[3] = packed }) }
        }
        for ((index, value) in listOf(0 to base-4, 0 to base+1, 1 to base+size-8192+4,
                1 to base+8192-4, 1 to base, 3 to 0x180040L, 4 to 1L, 6 to 1L)) {
            assertFails { selected.execute(ram, command.copyOf().also { it[index] = value }) }
        }
        assertTrue(checks.isEmpty())
        val buffer = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(2048) { buffer.putInt(it) }
        hole = true
        assertFails { selected.execute(ram, command) }
        assertTrue(data.takeLast(8192).all { it == 0.toByte() })
        hole = false
        checks.clear()
        val proof = selected.execute(ram,command)
        assertEquals(listOf(Triple(base,8192L,false), Triple(command[1],8192L,true), Triple(command[1],8192L,false)), checks)
        assertEquals("79825dfb45b4c98c070248817cf3efd9b706cf8da951f3cc24744a3f68a1f141",proof["sha256"])
        assertEquals(256,proof["sourceStrideBytes"])
        assertEquals(32,proof["rows"])
        assertEquals(proof,selected.restore(proof,command.toList()))
        assertFails { selected.restore(proof,command.toMutableList().also { it[2] = 0x100080; it[3] = it[2] }) }
        val short = longArrayOf(base,base+2560,0x100028,0x100028,2,1,0,0,0)
        val shorter = selected.execute(ram,short)
        assertEquals("40ca60832db533dab9b2150d8a07f0e2b51fb8bf29f9f56e74cd35ac1dc8447a",shorter["sha256"])
        val tile128x48 = longArrayOf(base,base+32768,0x300080,0x300080,2,1,0,0,0)
        val tile128Receipt = selected.execute(ram,tile128x48)
        assertEquals(512,tile128Receipt["sourceStrideBytes"])
        assertEquals(48,tile128Receipt["rows"])
        assertEquals(tile128Receipt,selected.restore(tile128Receipt,tile128x48.toList()))
        assertEquals(160,shorter["sourceStrideBytes"])
        assertEquals(16,shorter["rows"])
        assertEquals(shorter,selected.restore(shorter,short.toList()))

        buffer.clear()
        repeat(144 * 40) { buffer.putInt(it) }
        val area = longArrayOf(base,base+32768,0x280090,0x280090,2,1,0,0,0)
        val areaProof = selected.execute(ram,area)
        assertEquals("cdfe6754ef3bdefc7c20dbb00bc7c2313a19f9331f004731a34b664cdc00dd88",areaProof["sha256"])
        assertEquals(23040L,areaProof["bytes"])
        assertEquals(576,areaProof["sourceStrideBytes"])
        assertEquals(40,areaProof["rows"])
        assertEquals(areaProof,selected.restore(areaProof,area.toList()))
        assertFails { legacy.restore(areaProof,area.toList()) }
        assertFails { selected.restore(areaProof + ("rows" to 32),area.toList()) }
    }

    @Test fun resumesLegacyReceiptSelectsNewDestinationAndRestoresSpentBudgetWithoutDma() {
        val ram = Ram()
        fun device(settings: Map<String, Any>): Pair<StatefulComponent, MmioDevice> {
            val host = LcdcFunctionalTest.Context()
            host.entries["cpu.memory"] = ram
            val context = object : ComponentContext by host {
                override fun readData(relative: String) = if (relative != "graphics-fill.yaml") host.readData(relative)
                else mapOf("profile" to GraphicsFillTail.PROFILE, "initialWords" to List(4) { 0 },
                    "copyInitialWords" to words.toList(), "statusReads" to emptyList<Int>(),
                    "accessLimit" to 16, "copyExecution" to settings)
            }
            val component = VglibInitFactory().create(context, mapOf("profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank", GraphicsFillTail.SETTING to GraphicsFillTail.PROFILE))
            return component as StatefulComponent to host.entries.getValue("soc-vglib-init.registers") as MmioDevice
        }
        val (legacy, firstBank) = device(config)
        repeat(18) { firstBank.read(0,4); firstBank.write(0,4,0) }
        firstBank.write(0xc18,4,1)
        val firstOutput = ram.output.copyOf()
        firstBank.write(0xc04,4,alternate)
        val prefix = legacy.saveSnapshot()
        assertFailsWith<IllegalArgumentException> { firstBank.write(0xc18,4,1) }
        assertEquals(prefix, legacy.saveSnapshot())

        val selected = config + ("additionalDestinationBases" to listOf(alternate))
        val (extended, bank) = device(selected)
        ram.operations.clear()
        extended.restoreSnapshot(prefix)
        assertTrue(ram.operations.isEmpty())
        assertEquals(alternate, bank.read(0xc04,4))
        ram.input.reverse()
        ram.deny = "check:$alternate:false"
        val before = extended.saveSnapshot()
        assertFailsWith<IllegalArgumentException> { bank.write(0xc18,4,1) }
        assertEquals(before, extended.saveSnapshot())
        assertTrue(ram.alternateOutput.all { it.toInt() == 0x33 })
        ram.deny = ""
        ram.operations.clear()
        bank.write(0xc18,4,1)
        assertEquals(listOf("check:$source:false","check:$alternate:true","check:$alternate:false",
            "read:$source","write:$alternate","read:$alternate"), ram.operations)
        assertContentEquals(firstOutput, ram.output)
        assertContentEquals(ram.input, ram.alternateOutput.copyOfRange(1,bytes+1))
        val spent = extended.saveSnapshot()
        val fill = spent["graphicsFill"] as Map<*, *>
        val receipts = fill["copyReadbacks"] as List<*>
        assertEquals(listOf(destination, alternate), receipts.map { (it as Map<*, *>)["destination"] })
        assertNotEquals((receipts.first() as Map<*, *>)["sha256"], (receipts.last() as Map<*, *>)["sha256"])

        val (resumed, resumedBank) = device(selected)
        ram.operations.clear()
        resumed.restoreSnapshot(spent)
        assertFailsWith<IllegalStateException> { resumedBank.write(0xc18,4,1) }
        assertFailsWith<IllegalArgumentException> { legacy.restoreSnapshot(spent) }
        for (invalid in listOf(fill + ("copyExecution" to config), fill + ("copyReadbacks" to receipts.reversed()))) {
            assertFailsWith<IllegalArgumentException> { resumed.restoreSnapshot(spent + ("graphicsFill" to invalid)) }
        }
        assertEquals(spent, resumed.saveSnapshot())
        assertTrue(ram.operations.isEmpty())
        val (increased, increasedBank) = device(selected + ("submissionLimit" to 3))
        increased.restoreSnapshot(spent)
        val promoted = increased.saveSnapshot()
        assertEquals(receipts, (promoted["graphicsFill"] as Map<*, *>)["copyReadbacks"])
        assertFailsWith<IllegalArgumentException> { resumed.restoreSnapshot(promoted) }
        val forged = fill + ("copyExecution" to selected + ("submissionLimit" to 1))
        assertFailsWith<IllegalArgumentException> { increased.restoreSnapshot(spent + ("graphicsFill" to forged)) }
        assertEquals(promoted, increased.saveSnapshot())
        assertTrue(ram.operations.isEmpty())
        increasedBank.write(0xc18,4,1)
        assertFailsWith<IllegalStateException> { increasedBank.write(0xc18,4,1) }
    }

    @Test fun mode2TileProfileAllowsAlignedTilesUpTo1024x768WithinWorkspaceAndUpgradesLegacyPrefix() {
        val wsBase = 0x10000000L
        val wsBytes = 8 * 1024 * 1024
        val ws = mapOf("base" to wsBase, "bytes" to wsBytes)
        // Requires mode2Workspace and valid profile value
        assertFailsWith<IllegalArgumentException> {
            GraphicsCopyExecution(config + (GraphicsCopyExecution.TILE_PROFILE_SETTING to GraphicsCopyExecution.ALIGNED_8X8_TILES))
        }
        assertFailsWith<IllegalArgumentException> {
            GraphicsCopyExecution(config + ("mode2Workspace" to ws) + (GraphicsCopyExecution.TILE_PROFILE_SETTING to "unknown-profile"))
        }

        val legacy = GraphicsCopyExecution(config + ("mode2Workspace" to ws))
        val extended = GraphicsCopyExecution(config + ("mode2Workspace" to ws) +
            (GraphicsCopyExecution.TILE_PROFILE_SETTING to GraphicsCopyExecution.ALIGNED_8X8_TILES))
        // Legacy -> extended upgrade allowed; extended -> legacy downgrade forbidden
        extended.requireCompatiblePrefix(legacy)
        assertFailsWith<IllegalArgumentException> { legacy.requireCompatiblePrefix(extended) }
        assertEquals(GraphicsCopyExecution.ALIGNED_8X8_TILES, extended.settings()[GraphicsCopyExecution.TILE_PROFILE_SETTING])

        val mem = ByteArray(wsBytes)
        val ram = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address >= wsBase && address + size <= wsBase + wsBytes)
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(mem, (address - wsBase).toInt(), destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                source.get(mem, (address - wsBase).toInt(), source.remaining())
            }
        }
        for (packed in listOf(0x3000b0L, 0x300090L, 0x280088L, 0x03000400L)) {
            val w = (packed and 0xffffL).toInt()
            val h = (packed ushr 16).toInt()
            val byteLen = w * h * 4
            val cmd = longArrayOf(wsBase, wsBase + byteLen, packed, packed, 2, 1, 0, 0, 0)
            val receipt = extended.execute(ram, cmd)
            assertEquals(w * 4, receipt["sourceStrideBytes"])
            assertEquals(h, receipt["rows"])
            assertEquals(receipt, extended.restore(receipt, cmd.toList()))
            if (packed != 0x03000400L) {
                assertFailsWith<IllegalArgumentException> { legacy.execute(ram, cmd) }
            }
        }
        // Reject unaligned or out-of-bounds dimensions even with aligned tile profile
        for (badPacked in listOf(0x00080004L, 0x00040008L, 0x00090008L, 0x00080009L, 0x03080400L, 0x03000408L, 0L)) {
            val cmd = longArrayOf(wsBase, wsBase + 0x400000L, badPacked, badPacked, 2, 1, 0, 0, 0)
            assertFailsWith<IllegalArgumentException> { extended.execute(ram, cmd) }
        }
    }
}
