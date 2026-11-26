package com.github.nyabanban.fuji.emulator.desktop

class UnreadSd1Replacement(val previousSha256: String, val selectedSha256: String) {
    init {
        val digest = Regex("[0-9a-f]{64}")
        require(previousSha256.matches(digest) && selectedSha256.matches(digest) && previousSha256 != selectedSha256) {
            "Select distinct previous and replacement SD1 SHA-256 digests"
        }
    }

    internal fun checkHost(saved: Map<String, Any?>, live: Map<String, Any?>) {
        require(saved["kind"] == "transaction" && saved["started"] == true && saved["addressExtension"] == true)
        require(saved["inputCanonicalSha256"] is String && saved["inputCanonicalSha256"] == live["inputCanonicalSha256"]) {
            "SD host input differs on unread media replacement"
        }
        val counters = listOf("commandCount", "completedCommands", "failedTransactions", "transferCount", "transferredBytes", "mediaBytes")
        require(counters.all { saved[it] == 0 || saved[it] == 0L }) { "SD host already used" }
        require(saved["initialClockDeadlines"] == listOf(null, null)) { "SD clock transition pending" }
        val slots = saved["slots"] as? List<*> ?: error("Missing SD host slots")
        require(slots.size == 2)
        for ((index, raw) in slots.withIndex()) {
            val bank = raw as Map<*, *>
            val idleFields = listOf("pending", "deadline", "resetDeadline", "clockDeadline", "fault")
            require(bank["slot"] == index && idleFields.all { it in bank && bank[it] == null } &&
                bank["irqHigh"] == false && listOf("command", "rawNormalStatus", "errorStatus").all { bank[it] == 0 }) {
                "SD slot $index has a pending transaction or status"
            }
            if (index == 0) {
                val card = bank["card"] as? Map<*, *> ?: error("Missing SD1 card state")
                require(card["state"] == "IDLE" && card["pendingDataOrBusy"] == false &&
                    card["appCommandPending"] == false && card["interfaceConditionAccepted"] == false &&
                    listOf("revision", "acceptedCommands", "completedCommands").all { card[it] == 0 || card[it] == 0L }) {
                    "SD1 card already used"
                }
            } else require("card" in bank && bank["card"] == null) { "Expected empty slot 2" }
        }
    }

    internal fun readerPayload(saved: Map<String, Any?>, live: Map<String, Any?>): Map<String, Any?> {
        val slots = saved["slots"] as? List<*> ?: error("Missing SD reader slots")
        require(slots.size == 2)
        val card = slots.first() as Map<*, *>
        require(card["slot"] == 1 && card["inserted"] == true && card["readOnly"] == false &&
            card["sizeBytes"] == 4294967296L && card["sha256"] == previousSha256 && "modifiedSectors" !in card &&
            (slots[1] as Map<*, *>)["inserted"] == false) { "Expected the selected previous SDHC in slot 1 and empty slot 2" }
        val selected = saved + ("slots" to listOf(card + ("sha256" to selectedSha256), slots[1]))
        require(selected == live) { "Mounted replacement differs from selected SD1 contents or reader configuration" }
        return selected
    }
}
