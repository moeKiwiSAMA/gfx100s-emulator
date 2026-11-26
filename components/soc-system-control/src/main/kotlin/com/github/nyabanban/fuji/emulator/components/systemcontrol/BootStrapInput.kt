package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.MmioDevice

/** Opt-in HYPOTHESIS boot-strap input at 0xFFF4A000; default absent refuses without silent zero returns. */
internal class BootStrapInput private constructor(private val value: Long) : MmioDevice {
    override val clockOrder = 70
    override fun read(offset: Long, size: Int): Long {
        require(offset == 0L && size == 4) { "FFF4A000 boot-strap input requires its exact aligned read-only word" }
        return value
    }
    override fun write(offset: Long, size: Int, value: Long): Unit = error("FFF4A000 boot-strap input is read-only")
    fun state(): Map<String, Any> = mapOf(
        "profile" to PROFILE,
        "value" to value,
        "basis" to "Explicit unmeasured HYPOTHESIS u32 boot-strap input (0xFFF4A000); no read side effects or event/IRQ/DMA/reset/ready law",
    )
    fun saveSnapshot(): Map<String, Any> = linkedMapOf("profile" to PROFILE, "value" to value)
    fun validateSnapshot(raw: Any?) {
        val section = raw as? Map<*, *> ?: throw IllegalArgumentException("Snapshot bootStrapFff4a000 requires map")
        require(section.keys == SNAPSHOT_KEYS) { "Unexpected bootStrapFff4a000 snapshot keys" }
        require(section["profile"] == PROFILE) { "Unsupported bootStrapFff4a000 snapshot profile" }
        val rawWord = section["value"]
        require(rawWord is Byte || rawWord is Short || rawWord is Int || rawWord is Long) {
            "Snapshot bootStrapFff4a000 value requires integer"
        }
        val converted = (rawWord as? Number)?.toLong() ?: throw IllegalArgumentException("Snapshot bootStrapFff4a000 value requires integer")
        require(converted == value) { "Snapshot bootStrapFff4a000 value differs from live configuration" }
    }

    companion object {
        const val PROFILE = "boot-strap-fff4a000-experimental-v1"
        const val PORT = "soc-system-control.boot-strap"
        private val SNAPSHOT_KEYS = setOf("profile", "value")
        fun load(context: ComponentContext, file: String): BootStrapInput? {
            if (file.isEmpty()) return null
            require(file.isNotBlank()) { "FFF4A000 boot-strap input file cannot be whitespace" }
            val data = context.readData(file)
            require(data.keys == setOf("schema", "profile", "value") && data["schema"] == 1 && data["profile"] == PROFILE) {
                "invalid FFF4A000 boot-strap input schema"
            }
            val raw = data["value"]
            require(raw is Byte || raw is Short || raw is Int || raw is Long) {
                "FFF4A000 boot-strap input requires an unsigned 32-bit integer"
            }
            val value = (raw as Number).toLong()
            require(value in 0..0xffffffffL) { "FFF4A000 boot-strap input exceeds u32" }
            return BootStrapInput(value)
        }
    }
}
