package com.github.nyabanban.fuji.emulator.desktop

import java.util.Base64
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnreadSd1ReplacementTest {
    private val previous = "a".repeat(64)
    private val selected = "b".repeat(64)
    private val replacement = UnreadSd1Replacement(previous, selected)
    private val idle = mapOf("state" to "IDLE", "pendingDataOrBusy" to false, "appCommandPending" to false,
        "interfaceConditionAccepted" to false, "revision" to 0, "acceptedCommands" to 0, "completedCommands" to 0)
    private val slot = mapOf("slot" to 0, "command" to 0, "irqHigh" to false, "rawNormalStatus" to 0, "errorStatus" to 0,
        "pending" to null, "deadline" to null, "resetDeadline" to null, "clockDeadline" to null, "fault" to null, "card" to idle)
    private val second = slot + mapOf("slot" to 1, "card" to null)
    private val host = mapOf("kind" to "transaction", "started" to true, "addressExtension" to true,
        "inputCanonicalSha256" to previous, "commandCount" to 0L, "completedCommands" to 0, "failedTransactions" to 0,
        "transferCount" to 0, "transferredBytes" to 0, "mediaBytes" to 0, "initialClockDeadlines" to listOf(null, null),
        "slots" to listOf(slot, second))
    private val readerSlot = mapOf("slot" to 1, "inserted" to true, "readOnly" to false, "sizeBytes" to 4294967296L, "sha256" to previous)
    private val emptySlot = mapOf("slot" to 2, "inserted" to false)
    private fun reader(card: Map<String, Any>, other: Map<String, Any> = emptySlot) = mapOf("slots" to listOf(card, other))

    @Test fun changesOnlyTheSelectedDigest() {
        val saved = reader(readerSlot)
        val live = reader(readerSlot + ("sha256" to selected))
        assertEquals(live, replacement.readerPayload(saved, live))
        assertEquals(reader(readerSlot), saved)
        for (change in listOf("readOnly" to true, "sizeBytes" to 512, "inserted" to false, "sha256" to previous)) {
            assertFailsWith<IllegalArgumentException> { replacement.readerPayload(saved, reader(readerSlot + ("sha256" to selected) + change)) }
        }
        for (change in listOf("modifiedSectors" to mapOf("0" to "dirty"), "sha256" to selected, "sizeBytes" to 512)) {
            assertFailsWith<IllegalArgumentException> { replacement.readerPayload(reader(readerSlot + change), live) }
        }
        assertFailsWith<IllegalArgumentException> {
            replacement.readerPayload(reader(readerSlot, emptySlot + ("inserted" to true)), live)
        }
    }

    @Test fun hostRequiresUnusedMatchingProtocolAndBothBanksQuiescent() {
        replacement.checkHost(host, host)
        for (field in listOf("commandCount", "completedCommands", "failedTransactions", "transferCount", "transferredBytes", "mediaBytes")) {
            assertFailsWith<IllegalArgumentException> { replacement.checkHost(host + (field to 1), host) }
            assertFailsWith<IllegalArgumentException> { replacement.checkHost(host - field, host) }
        }
        assertFailsWith<IllegalArgumentException> { replacement.checkHost(host, host + ("inputCanonicalSha256" to selected)) }
        for (index in 0..1) {
            for (field in listOf("pending", "deadline", "clockDeadline", "resetDeadline", "fault", "irqHigh", "errorStatus", "rawNormalStatus")) {
                val banks = listOf(slot, second).toMutableList()
                banks[index] = banks[index] + (field to 1)
                assertFailsWith<IllegalArgumentException> { replacement.checkHost(host + ("slots" to banks), host) }
            }
        }
        for (change in listOf("state" to "TRANSFER", "pendingDataOrBusy" to true, "acceptedCommands" to 1, "revision" to 1)) {
            val used = slot + ("card" to idle + change)
            assertFailsWith<IllegalArgumentException> { replacement.checkHost(host + ("slots" to listOf(used, second)), host) }
        }
    }

    @Test fun rejectsSdInterruptsOnEitherRoute() {
        val fields = listOf("levels", "pending", "active")
        val clear = fields.associateWith { Base64.getEncoder().encodeToString(ByteArray(60)) }
        SdSnapshotInsertion.checkSdInterrupts(mapOf("shared" to clear))
        for (field in fields) for (line in listOf(230, 237)) {
            val bits = ByteArray(60)
            bits[(line - 32) / 8] = (1 shl ((line - 32) % 8)).toByte()
            val set = clear + (field to Base64.getEncoder().encodeToString(bits))
            assertFailsWith<IllegalArgumentException> { SdSnapshotInsertion.checkSdInterrupts(mapOf("shared" to set)) }
        }
    }

    @Test fun requiresExplicitDistinctIdentitiesAndOrdinaryDeviceBindings() {
        for (digest in listOf(previous, "", "0".repeat(63), "g".repeat(64))) {
            assertFailsWith<IllegalArgumentException> { UnreadSd1Replacement(previous, digest) }
        }
        assertFailsWith<IllegalArgumentException> {
            EmulatorSnapshot.RestoreOptions(unreadSd1 = replacement, allowCompatibleProfileChanges = setOf("soc-sd-host"))
        }
        assertFailsWith<IllegalArgumentException> {
            EmulatorSnapshot.RestoreOptions(unreadSd1 = replacement, allowPromotedDiscoveryRegions = true)
        }
    }
}
