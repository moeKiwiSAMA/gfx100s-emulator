package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.MmioDevice

/**
 * Factory Boot ROM cold-start system-control registers owned by `soc-system-control`.
 *
 * Opt-in profile `retained-banks-bootrom-coldstart-v1` exposes:
 * - `soc-system-control.coldstart-8180` (`0xFFF48180`, 4 B): retained 32-bit control word
 *   written by Boot ROM init-table entry 1 (`bank-00[0x20]`).
 * - `soc-system-control.boot-status-mailbox` (`0xFFF4A100..0xFFF4A10F`, 16 B = 4 x 4 B):
 *   4-core Boot Status Mailbox (`CPU0: 0x900D / 0x0BAD`, `CPU1..CPU3: 0x1000900D / 0x2000900D / 0x3000900D`).
 *
 * Composes cleanly alongside existing `soc-system-control.boot-strap` (`0xFFF4A000`).
 */
internal class BootColdStartMailbox {
    private var ctrl8180: Long = 0L
    private val bootStatusWords = LongArray(MAILBOX_WORDS)

    val ctrl8180Device: MmioDevice = object : MmioDevice {
        override val clockOrder = 70
        override fun read(offset: Long, size: Int): Long {
            require(offset == 0L && size == 4) { "FFF48180 control word requires exact aligned 32-bit access" }
            return ctrl8180
        }

        override fun write(offset: Long, size: Int, value: Long) {
            require(offset == 0L && size == 4 && value in 0L..0xffff_ffffL) {
                "FFF48180 control word requires aligned 32-bit u32 write"
            }
            ctrl8180 = value
        }
    }

    val bootStatusMailboxDevice: MmioDevice = object : MmioDevice {
        override val clockOrder = 70
        private fun slot(offset: Long, size: Int): Int {
            require(size == 4 && offset >= 0L && offset < MAILBOX_SIZE && (offset and 3L) == 0L) {
                "FFF4A100..FFF4A10F boot status mailbox requires aligned 32-bit core slot"
            }
            return (offset ushr 2).toInt()
        }

        override fun read(offset: Long, size: Int): Long = bootStatusWords[slot(offset, size)]

        override fun write(offset: Long, size: Int, value: Long) {
            val coreSlot = slot(offset, size)
            require(value in 0L..0xffff_ffffL) { "Boot status mailbox word exceeds u32" }
            bootStatusWords[coreSlot] = value
        }
    }

    fun state(): Map<String, Any> = linkedMapOf(
        "profile" to PROFILE,
        "ctrl8180" to ctrl8180,
        "bootStatusWords" to bootStatusWords.toList(),
        "basis" to "FILE/DAT boot-rom.bin 0xFFF00250..0xFFF002E4 & bank-00[0x20]: retained 0xFFF48180 word and 4-core 0xFFF4A100..0xFFF4A10F boot status mailbox (HYPOTHESIS zero reset)",
    )

    fun saveSnapshot(): Map<String, Any> = linkedMapOf(
        "profile" to PROFILE,
        "ctrl8180" to ctrl8180,
        "bootStatusWords" to bootStatusWords.toList(),
    )

    class ValidatedSnapshot(
        val ctrl8180: Long,
        val bootStatusWords: LongArray,
    )

    fun validateSnapshot(raw: Any?): ValidatedSnapshot {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Snapshot bootColdStartState requires map")
        require(map.keys == SNAPSHOT_KEYS) { "Unexpected bootColdStartState snapshot keys" }
        require(map["profile"] == PROFILE) { "Unsupported bootColdStartState snapshot profile" }
        val word8180 = exactU32(map["ctrl8180"], "bootColdStartState.ctrl8180")
        val rawWords = map["bootStatusWords"] as? List<*>
            ?: throw IllegalArgumentException("Snapshot bootColdStartState.bootStatusWords requires list")
        require(rawWords.size == MAILBOX_WORDS) {
            "Snapshot bootColdStartState.bootStatusWords requires $MAILBOX_WORDS entries"
        }
        val words = LongArray(MAILBOX_WORDS) { idx ->
            exactU32(rawWords[idx], "bootColdStartState.bootStatusWords[$idx]")
        }
        return ValidatedSnapshot(word8180, words)
    }

    fun applySnapshot(validated: ValidatedSnapshot) {
        ctrl8180 = validated.ctrl8180
        validated.bootStatusWords.copyInto(bootStatusWords)
    }

    companion object {
        const val PROFILE = "retained-banks-bootrom-coldstart-v1"
        const val CTRL_8180_PORT = "soc-system-control.coldstart-8180"
        const val BOOT_STATUS_MAILBOX_PORT = "soc-system-control.boot-status-mailbox"
        const val MAILBOX_WORDS = 4
        const val MAILBOX_SIZE = MAILBOX_WORDS * 4L

        private val SNAPSHOT_KEYS = setOf("profile", "ctrl8180", "bootStatusWords")

        private fun exactU32(value: Any?, field: String): Long {
            require(value is Byte || value is Short || value is Int || value is Long) {
                "Snapshot field $field requires integer"
            }
            val n = (value as Number).toLong()
            require(n in 0L..0xffff_ffffL) { "Snapshot field $field out of u32 range" }
            return n
        }
    }
}
