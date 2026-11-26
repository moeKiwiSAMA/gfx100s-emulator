package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.MmioDevice

/**
 * Opt-in read-only USB VBUS cable-detect status word at 0xFFF4A300 (bit 0).
 *
 * DAT consumers in GFX100S 2.13:
 * - 0x012EA70C (called from 0x012EB51C via kernel read32 0x0005AFD8/0x0005AFE0 during SYS:INITAPL):
 *   stores `read32(0xFFF4A300) & 1` into `*(u8*)0x01A23808`; when bit 0 == 1 posts event 0x3044 via 0x012EA630.
 * - 0x021AA588: returns `read32(0xFFF4A300) & 1`.
 * - 0x021AA5A4: gates Synopsys DWC3 `DSTS` (`0xFFB0C70C`) speed decode on `read32(0xFFF4A300) & 1`.
 *
 * Zero writes exist anywhere in firmware. Default state is detached (`0L`).
 */
internal class UsbVbusA300Input(private val presentProvider: () -> Boolean) : MmioDevice {
    override val clockOrder = 70

    private fun word(): Long = if (presentProvider()) 1L else 0L

    override fun read(offset: Long, size: Int): Long {
        require(offset == 0L && size == 4) {
            "FFF4A300 usb-vbus-a300 status word requires exact aligned 32-bit read"
        }
        return word()
    }

    override fun write(offset: Long, size: Int, value: Long): Unit =
        error("FFF4A300 usb-vbus-a300 status word is read-only")

    fun state(): Map<String, Any> = linkedMapOf(
        "profile" to PROFILE,
        "present" to presentProvider(),
        "word" to word(),
        "basis" to "DAT read-only bit0 USB VBUS consumers at 0x012EA70C (SYS:INITAPL -> 0x01A23808 / event 0x3044), 0x021AA588, and 0x021AA5A4 (DWC3 DSTS 0xFFB0C70C); default 0 = detached; no read side effects",
    )

    fun saveSnapshot(): Map<String, Any> = linkedMapOf(
        "profile" to PROFILE,
        "present" to presentProvider(),
    )

    fun validateSnapshot(raw: Any?, expectedPresent: Boolean) {
        val section = raw as? Map<*, *> ?: throw IllegalArgumentException("Snapshot usbVbusA300State requires map")
        require(section.keys == SNAPSHOT_KEYS) { "Unexpected usbVbusA300State snapshot keys" }
        require(section["profile"] == PROFILE) { "Unsupported usbVbusA300State snapshot profile" }
        val present = section["present"] as? Boolean
            ?: throw IllegalArgumentException("Snapshot usbVbusA300State present requires boolean")
        require(present == expectedPresent) {
            "Snapshot usbVbusA300State present differs from scalar usbVbusPresent"
        }
    }

    companion object {
        const val PROFILE = "retained-banks-usb-vbus-experimental-v1"
        const val PORT = "soc-system-control.usb-vbus-a300"
        const val STATUS_A200_V2_PORT = "soc-system-control.status-a200-v2"
        private val SNAPSHOT_KEYS = setOf("profile", "present")
    }
}
