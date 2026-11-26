package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.MmioDevice

/** Opt-in HYPOTHESIS read-only word input at 0xFFF4A200 (status-a200); default absent refuses. */
internal class StatusA200Input private constructor(private val value: Long) : MmioDevice {
    override val clockOrder = 70

    override fun read(offset: Long, size: Int): Long {
        require(offset == 0L && size == 4) { "FFF4A200 status-a200 input requires its exact aligned read-only word" }
        return value
    }

    override fun write(offset: Long, size: Int, value: Long): Unit =
        error("FFF4A200 status-a200 input is read-only")

    fun state(): Map<String, Any> = mapOf(
        "profile" to PROFILE,
        "value" to value,
        "basis" to "Explicit unmeasured HYPOTHESIS u32 status-a200 input (0xFFF4A200); consumer 0x01277C58 tests bit0; no read side effects or event/IRQ/DMA/reset/ready law",
    )

    fun saveSnapshot(): Map<String, Any> = linkedMapOf("profile" to PROFILE, "value" to value)

    fun validateSnapshot(raw: Any?) {
        val section = raw as? Map<*, *> ?: throw IllegalArgumentException("Snapshot statusA200Fff4a200 requires map")
        require(section.keys == SNAPSHOT_KEYS) { "Unexpected statusA200Fff4a200 snapshot keys" }
        require(section["profile"] == PROFILE) { "Unsupported statusA200Fff4a200 snapshot profile" }
        val rawWord = section["value"]
        require(rawWord is Byte || rawWord is Short || rawWord is Int || rawWord is Long) {
            "Snapshot statusA200Fff4a200 value requires integer"
        }
        val converted = (rawWord as Number).toLong()
        require(converted == value) { "Snapshot statusA200Fff4a200 value differs from live configuration" }
    }

    companion object {
        const val PROFILE = "status-a200-fff4a200-experimental-v1"
        const val PORT = "soc-system-control.status-a200"
        private val SNAPSHOT_KEYS = setOf("profile", "value")

        fun load(context: ComponentContext, file: String): StatusA200Input? {
            if (file.isEmpty()) return null
            require(file.isNotBlank()) { "FFF4A200 status-a200 input file cannot be whitespace" }
            val data = context.readData(file)
            require(data.keys == setOf("schema", "profile", "value") && data["schema"] == 1 && data["profile"] == PROFILE) {
                "invalid FFF4A200 status-a200 input schema"
            }
            val raw = data["value"]
            require(raw is Byte || raw is Short || raw is Int || raw is Long) {
                "FFF4A200 status-a200 input requires an unsigned 32-bit integer"
            }
            val value = (raw as Number).toLong()
            require(value in 0..0xffffffffL) { "FFF4A200 status-a200 input exceeds u32" }
            return StatusA200Input(value)
        }
    }
}
