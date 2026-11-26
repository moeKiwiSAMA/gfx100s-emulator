package com.github.nyabanban.fuji.emulator.components.sdhost

import org.junit.jupiter.api.Test
import kotlin.test.*

class SdCardProtocolTest {
    private fun identityInput(): Map<String, Any?> = mapOf(
        "basis" to "SYNTHETIC", "capacityBytes" to 1024L * 1024,
        "highCapacity" to false, "readOnly" to false, "rca" to 0x1234,
        "cidHex" to cid(), "ocrVoltageWindow" to 0xff8000L, "supportsHighSpeed" to true,
    )
    private fun cid(): String {
        val bytes = ByteArray(15) { (it * 17 + 3).toByte() }
        var crc = 0
        for (b in bytes) for (i in 7 downTo 0) {
            val feedback = (b.toInt() ushr i and 1) xor (crc ushr 6 and 1)
            crc = crc shl 1 and 127
            if (feedback != 0) crc = crc xor 9
        }
        return (bytes + ((crc shl 1) or 1).toByte()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun card(input: Map<String, Any?> = identityInput()) = SdCardProtocol(SdCardIdentity.parse(input))
    private fun command(c: SdCardProtocol, index: Int, arg: Long = 0, size: Int = 0, blocks: Int = 0, auto: Boolean = false): SdCardCommand {
        val p = c.prepare(index, arg, size, blocks, auto); p.accept(); p.complete(); return p
    }
    private fun initialize(c: SdCardProtocol) {
        command(c, 0); command(c, 8, 0x1aa); command(c, 55)
        command(c, 41, 0x40ff8000); command(c, 2); command(c, 3)
    }
    private fun selected(c: SdCardProtocol) { initialize(c); command(c, 7, 0x12340000) }
    private fun acmd(c: SdCardProtocol, index: Int, arg: Long = 0, size: Int = 0): SdCardCommand {
        command(c, 55, 0x12340000); return command(c, index, arg, size, if (size > 0) 1 else 0)
    }
    private fun field(bytes: ByteArray, high: Int, low: Int): Long {
        var value = 0L
        for (bit in high downTo low) value = value shl 1 or ((bytes[bytes.lastIndex - bit / 8].toInt() ushr (bit % 8)) and 1).toLong()
        return value
    }
    private fun unpack(response: LongArray): ByteArray = ByteArray(16).also { bytes ->
        for (i in 0..14) {
            val shift = (14 - i) * 8
            bytes[i] = (response[shift / 32] ushr (shift % 32)).toByte()
        }
    }

    @Test fun identityRejectsInexactCapacityMalformedCidAndMissingSyntheticInputs() {
        val valid = identityInput(); assertEquals(valid, SdCardIdentity.parse(valid).snapshot())
        val bad = valid.keys.map { valid - it } + listOf(
            valid + ("extra" to 1), valid + ("capacityBytes" to 1024.0), valid + ("capacityBytes" to 512L),
            valid + ("capacityBytes" to 123L), valid + ("highCapacity" to true),
            valid + ("rca" to 0), valid + ("ocrVoltageWindow" to 0x80000000L),
            valid + ("cidHex" to "00".repeat(16)), valid + ("basis" to "OBS"),
        )
        for (input in bad) assertFails { SdCardIdentity.parse(input) }
        for (size in listOf(0x1010L, 0xff5fL, 0xffffL, 0x3ffeffL)) {
            assertEquals((size + 1) * 524288, SdCardIdentity.parse(valid + mapOf("highCapacity" to true, "capacityBytes" to (size + 1) * 524288)).capacityBytes)
        }
        for (size in listOf(0x100fL, 0xff60L, 0xfffeL, 0x3fff00L))
            assertFails { SdCardIdentity.parse(valid + mapOf("highCapacity" to true, "capacityBytes" to (size + 1) * 524288)) }
    }

    @Test fun purePrepareInitializationR2AndR6UseActualDeclaredIdentity() {
        val c = card(); val initial = c.snapshot()
        val cmd8 = c.prepare(8, 0x1ab, 0, 0, false)
        assertEquals(initial, c.snapshot()); assertEquals(0x1abL, cmd8.response[0])
        cmd8.accept(); cmd8.complete(); command(c, 55)
        val acmd41 = command(c, 41, 0xff8000)
        assertEquals(0x80ff8000L, acmd41.response[0]); assertEquals(1, c.snapshot()["stateCode"])
        val readyState = c.snapshot()
        assertFails { c.prepare(55, 0, 0, 0, false) }; assertEquals(readyState, c.snapshot())
        val cid = command(c, 2)
        assertEquals(SdResponseKind.LONG, cid.responseKind)
        assertContentEquals(ByteArray(15) { (it * 17 + 3).toByte() }, unpack(cid.response).copyOf(15))
        assertEquals(0L, cid.response[3] ushr 24)
        val rca = command(c, 3)
        assertEquals(0x12340400L, rca.response[0]) // IDENT when CMD3 received.
        val csd = unpack(command(c, 9, 0x12340000).response)
        val capacity = (field(csd, 73, 62) + 1) * (1L shl (field(csd, 49, 47).toInt() + 2)) * (1L shl field(csd, 83, 80).toInt())
        assertEquals(1024L * 1024, capacity)
        assertEquals(1L, field(csd, 79, 79))
        val large = card(identityInput() + ("capacityBytes" to 2L * 1024 * 1024 * 1024))
        initialize(large)
        val largeCsd = unpack(command(large, 9, 0x12340000).response)
        assertEquals(10L, field(largeCsd, 83, 80))
        assertEquals(10L, field(largeCsd, 25, 22))
        assertEquals(1L, field(largeCsd, 79, 79))
        val select = c.prepare(7, 0x12340000, 0, 0, false)
        assertEquals(0x700L, select.response[0]); assertTrue(select.busy)
        select.accept(); assertEquals("TRANSFER", c.snapshot()["state"])
        assertEquals(true, c.snapshot()["pendingDataOrBusy"])
        select.complete(); assertEquals(0x900L, c.status())
    }

    @Test fun highCapacityRequiresHostCapacitySupportAndAdvertisesCoherentCsd() {
        val size = (0x1010L + 1) * 524288
        val c = card(identityInput() + mapOf("highCapacity" to true, "capacityBytes" to size))
        command(c, 55); assertEquals(0xff8000L, command(c, 41, 0x40ff8000).response[0])
        command(c, 8, 0x1aa); command(c, 55)
        assertEquals(0xff8000L, command(c, 41, 0xff8000).response[0])
        command(c, 55); assertEquals(0xc0ff8000L, command(c, 41, 0x40ff8000).response[0])
        command(c, 2); command(c, 3)
        val csd = unpack(command(c, 9, 0x12340000).response)
        assertEquals(1L, field(csd, 127, 126)); assertEquals(size, (field(csd, 69, 48) + 1) * 524288)
        command(c, 7, 0x12340000)
        val read = c.prepare(17, 3, 512, 1, false)
        assertEquals(1536L, read.mediaOffset)
    }

    @Test fun sdscAcceptsObservedCapabilityRequestWithoutVoltageSwitchOrPrepareMutation() {
        val c = card(); command(c, 8, 0x1aa); command(c, 55)
        val before = c.snapshot()
        val plan = c.prepare(41, 0x51ff8000, 0, 0, false)
        assertEquals(before, c.snapshot())
        assertEquals(0x80ff8000L, plan.response[0]) // ready, CCS=0, S18A=0.
        assertEquals(SdResponseKind.SHORT, plan.responseKind)
        assertEquals(SdDataDirection.NONE, plan.direction); assertFalse(plan.busy)
        plan.accept()
        assertEquals("READY", c.snapshot()["state"])
        assertEquals(false, c.snapshot()["appCommandPending"])
        assertEquals(3L, c.snapshot()["acceptedCommands"]); assertEquals(2L, c.snapshot()["completedCommands"])
        assertEquals(before["selectedFunctions"], c.snapshot()["selectedFunctions"])
        plan.complete(); assertEquals(3L, c.snapshot()["completedCommands"])
        val ready = c.snapshot()
        assertFailsWith<IllegalStateException> { c.prepare(11, 0, 0, 0, false) }
        assertEquals(ready, c.snapshot(), "S18R cannot make CMD11 supported")
    }

    @Test fun capabilityQueriesAndSdhcKeepExistingReadinessGatesWhileSdxcXpcRefuses() {
        // Use the existing CSD v2 type boundaries, not a rounded nominal capacity threshold.
        for (size in listOf(0x1010L, 0xff5fL, 0xffffL, 0x3ffeffL)) {
            val c = card(identityInput() + mapOf("highCapacity" to true, "capacityBytes" to (size + 1) * 524288))
            command(c, 55)
            val beforeQuery = c.snapshot(); val query = c.prepare(41, 0x51000000, 0, 0, false)
            assertEquals(beforeQuery, c.snapshot()); assertEquals(0xff8000L, query.response[0])
            query.accept(); query.complete(); assertEquals("IDLE", c.snapshot()["state"])
            if (size >= 0xffff) {
                command(c, 8, 0x1aa); command(c, 55)
                val before = c.snapshot()
                val error = assertFailsWith<IllegalArgumentException> { c.prepare(41, 0x51ff8000, 0, 0, false) }
                assertTrue(error.message!!.contains("SDXC XPC")); assertEquals(before, c.snapshot())
                // S18R with XPC=0 remains a valid non-UHS request, including for SDXC.
                assertEquals(0xc0ff8000L, command(c, 41, 0x41ff8000).response[0])
            } else {
                command(c, 55); assertEquals(0xff8000L, command(c, 41, 0x51ff8000).response[0]) // no CMD8
                command(c, 8, 0x1aa); command(c, 55)
                assertEquals(0xff8000L, command(c, 41, 0x11ff8000).response[0]) // no HCS
                command(c, 55); assertEquals(0xc0ff8000L, command(c, 41, 0x51ff8000).response[0])
            }
            assertEquals("READY", c.snapshot()["state"])
            assertEquals(listOf(0, 0, 0, 0, 0, 0), c.snapshot()["selectedFunctions"])
        }
        val sdsc = card(); command(sdsc, 55)
        assertEquals(0xff8000L, command(sdsc, 41, 0x51000000).response[0])
        assertEquals("IDLE", sdsc.snapshot()["state"])
    }

    @Test fun capabilityAdmissionRejectsUnsupportedBitsVoltageAndMissingPrefixWithoutConsumption() {
        val c = card(identityInput() + ("ocrVoltageWindow" to 0x8000L))
        val unprefixed = c.snapshot()
        assertFailsWith<IllegalStateException> { c.prepare(41, 0x51ff8000, 0, 0, false) }
        assertEquals(unprefixed, c.snapshot())
        command(c, 55); val before = c.snapshot()
        // SDUC HO2T, eSD Fast Boot, unsupported bit31 and a reserved low bit stay outside v6.
        for (argument in listOf(0x59ff8000L, 0x71ff8000L, 0xd1ff8000L, 0x51ff8001L)) {
            val error = assertFailsWith<IllegalArgumentException> { c.prepare(41, argument, 0, 0, false) }
            assertTrue(error.message!!.contains("capability/voltage bits")); assertEquals(before, c.snapshot())
        }
        val voltage = assertFailsWith<IllegalArgumentException> { c.prepare(41, 0x51010000, 0, 0, false) }
        assertTrue(voltage.message!!.contains("incompatible ACMD41 voltage")); assertEquals(before, c.snapshot())
        command(c, 41, 0x51008000)
        val ready = c.snapshot()
        assertFailsWith<IllegalArgumentException> { c.prepare(55, 0, 0, 0, false) }
        assertEquals(ready, c.snapshot())
    }

    @Test fun appPrefixIsConsumedOnlyOnAcceptAndUndefinedAcmdFallsBack() {
        val c = card(); selected(c)
        repeat(2) { assertEquals(0x920L, command(c, 55, 0x12340000).response[0]) }
        val before = c.snapshot()
        assertFails { c.prepare(51, 0, 64, 1, false) }; assertEquals(before, c.snapshot())
        val scr = c.prepare(51, 0, 8, 1, false)
        assertEquals(before, c.snapshot()); assertEquals(0x920L, scr.response[0])
        scr.accept(); assertEquals(false, c.snapshot()["appCommandPending"])
        assertEquals("SENDING", c.snapshot()["state"]); scr.complete()
        assertContentEquals(byteArrayOf(2, 5, 0, 0, 0, 0, 0, 0), scr.payload)
        command(c, 55, 0x12340000)
        val read = command(c, 17, 0, 512, 1)
        assertEquals(0x900L, read.response[0]); assertNull(read.payload)
        command(c, 55, 0x12340000)
        val prefixed = c.snapshot(); assertFails { c.prepare(22, 0, 4, 1, false) }; assertEquals(prefixed, c.snapshot())
        command(c, 0); assertEquals("IDLE", c.snapshot()["state"])
    }

    @Test fun scrStatusAndBusWidthAreExactShortDataTransactions() {
        val c = card(); selected(c)
        assertEquals(8L, acmd(c, 51, size = 8).dataBytes)
        val status1 = acmd(c, 13, size = 64)
        assertEquals(0L, field(status1.payload!!, 511, 510)); assertEquals(64L, status1.dataBytes)
        acmd(c, 6, 2)
        assertEquals(2L, field(acmd(c, 13, size = 64).payload!!, 511, 510))
        command(c, 55, 0x12340000); val before = c.snapshot()
        assertFails { c.prepare(6, 1, 0, 0, false) }; assertEquals(before, c.snapshot())
    }

    @Test fun switchFunctionCheckSwitchKeepAndAllOrNoneFollowPayloadFields() {
        val c = card(); selected(c)
        val check = command(c, 6, 0x00fffff1, 64, 1).payload!!
        assertEquals(0x8003L, field(check, 415, 400)); assertEquals(1L, field(check, 379, 376))
        assertEquals(listOf(0, 0, 0, 0, 0, 0), c.snapshot()["selectedFunctions"])
        val switch = c.prepare(6, 0x80fffff1, 64, 1, false); switch.accept()
        assertEquals(listOf(0, 0, 0, 0, 0, 0), c.snapshot()["selectedFunctions"])
        switch.complete(); assertEquals(listOf(1, 0, 0, 0, 0, 0), c.snapshot()["selectedFunctions"])
        assertEquals(1L, field(command(c, 6, 0x00ffffff, 64, 1).payload!!, 379, 376))
        val invalid = command(c, 6, 0x80ffff20, 64, 1).payload!! // group1=0, unsupported group2=2
        assertEquals(0L, field(invalid, 511, 496)); assertEquals(15L, field(invalid, 383, 380))
        assertEquals(listOf(1, 0, 0, 0, 0, 0), c.snapshot()["selectedFunctions"])
        command(c, 0); assertEquals(listOf(0, 0, 0, 0, 0, 0), c.snapshot()["selectedFunctions"])
    }

    @Test fun mediaPlansStayPendingUntilActualCompletionAndStopCanRecover() {
        val c = card(); selected(c)
        val before = c.snapshot(); val read = c.prepare(18, 512, 512, 3, true)
        assertEquals(before, c.snapshot()); assertEquals(512L, read.mediaOffset); assertEquals(1536L, read.dataBytes)
        read.accept(); assertEquals("SENDING", c.snapshot()["state"])
        command(c, 13, 0x12340000) // status command does not invalidate pending data.
        read.complete(); assertEquals("TRANSFER", c.snapshot()["state"])
        val write = c.prepare(25, 0, 512, 2, true)
        assertTrue(write.busy); assertEquals(SdDataDirection.WRITE, write.direction)
        write.accept(); assertEquals("RECEIVE", c.snapshot()["state"])
        command(c, 12); assertEquals("TRANSFER", c.snapshot()["state"])
        assertFails { write.complete() }
        command(c, 18, 0, 512, 2, false)
        assertEquals("SENDING", c.snapshot()["state"])
        val stopRead = c.prepare(12, 0, 0, 0, false)
        assertEquals(5L, stopRead.response[0] ushr 9 and 15)
        stopRead.accept(); assertEquals("TRANSFER", c.snapshot()["state"])
        assertEquals(true, c.snapshot()["pendingDataOrBusy"]); stopRead.complete()
        command(c, 17, 0, 512, 1); assertEquals("TRANSFER", c.snapshot()["state"])
    }

    @Test fun invalidRangeWidthsModesAndReadonlyRefuseBeforeAnyMutation() {
        val c = card(); selected(c)
        val before = c.snapshot()
        for (call in listOf<() -> Unit>(
            { c.prepare(17, 1, 512, 1, false) }, { c.prepare(18, 1048064, 512, 2, true) },
            { c.prepare(17, 0, 512, 2, false) }, { c.prepare(24, 0, 4, 1, false) },
            { c.prepare(18, 0xffffffff, 512, Int.MAX_VALUE, true) }, { c.prepare(6, 0x01000000, 64, 1, false) },
            { c.prepare(13, 0x56780000, 0, 0, false) }, { c.prepare(16, 1024, 0, 0, false) },
            { c.prepare(38, 0, 0, 0, false) }, { c.prepare(13, 0x12340000, 0, 0, true) },
            { c.prepare(12, 0, 0, 0, false) }, // TRAN CMD12 is illegal for this non-SDUC card.
            { c.prepare(7, 0x12340000, 0, 0, false) }, // Addressed selection requires STANDBY.
        )) { assertFails { call() }; assertEquals(before, c.snapshot()) }
        val ro = card(identityInput() + ("readOnly" to true)); selected(ro); val roBefore = ro.snapshot()
        val denied = ro.prepare(24, 0, 512, 1, false)
        assertTrue(denied.writeProtectViolation)
        assertEquals(SdDataDirection.NONE, denied.direction)
        assertEquals(0L, denied.dataBytes)
        assertNull(denied.mediaOffset)
        assertEquals(roBefore, ro.snapshot())
        val voltage = card(identityInput() + ("ocrVoltageWindow" to 0x8000L))
        command(voltage, 55); val voltageBefore = voltage.snapshot()
        assertFails { voltage.prepare(41, 0x10000L, 0, 0, false) }; assertEquals(voltageBefore, voltage.snapshot())
    }

    @Test fun commandsCannotDoubleAcceptCompleteOrMutatePreparedMetadata() {
        val c = card(); selected(c)
        val old = c.prepare(13, 0x12340000, 0, 0, false)
        command(c, 13, 0x12340000); assertFails { old.accept() }
        val read = c.prepare(17, 0, 512, 1, false)
        assertFails { read.complete() }; read.accept(); assertFails { read.accept() }
        read.complete(); assertFails { read.complete() }
        val switch = c.prepare(6, 0xffffff, 64, 1, false)
        val payload = switch.payload!!; payload.fill(0x7f); val response = switch.response; response.fill(0)
        assertEquals(100L, field(switch.payload!!, 511, 496)); assertEquals(0x900L, switch.response[0])
        switch.accept(); command(c, 0); assertFails { switch.complete() }
    }
}
