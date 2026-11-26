package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import com.github.nyabanban.fuji.emulator.firmware.SdInitContracts
import com.github.nyabanban.fuji.emulator.firmware.SdTransactionContracts
import com.github.nyabanban.fuji.emulator.runtime.ComponentHost
import java.util.Base64

class SdSnapshotInsertion internal constructor(
    private val legacyFile: String,
    private val previousImageName: String,
    private val imageSha: String,
    private val inputSha: String,
    private val detectLow: Boolean,
    private val readOnly: Boolean = true,
) {
    companion object {
        internal fun checkSdInterrupts(saved: Map<String, Any?>) {
            for (field in listOf("pending", "levels", "active")) {
                val bits = Base64.getDecoder().decode(saved.row("shared").getValue(field) as String)
                require(bits.size == 60 && listOf(230, 237).all { line ->
                    val bit = line - 32
                    bits[bit / 8].toInt() and (1 shl (bit % 8)) == 0
                }) { "SD interrupt route conflict in $field" }
            }
        }

        private fun Map<*, *>.row(key: String): Map<String, Any?> {
            val value = this[key]
            require(value is Map<*, *> && value.keys.all { it is String }) { "Missing SD snapshot object $key" }
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }

        private fun Map<*, *>.number(key: String): Long {
            val value = this[key]
            require(value is Long || value is Int || value is Short || value is Byte) { "Missing integral SD field $key" }
            return (value as Number).toLong()
        }
    }

    internal fun receipt() = mapOf("readerSlot" to 1, "imageSha256" to imageSha, "inputSha256" to inputSha,
        "readOnly" to readOnly, "gpio10Bit4LowSelected" to detectLow, "profile" to SdTransactionContracts.ADDRESS_PROFILE)

    internal fun checkRoutes(saved: Map<String, String>, live: Map<String, String>, discovery: Any?) {
        require(saved["soc-sd-host"] == SdInitContracts.PHY_PROFILE && live["soc-sd-host"] == SdTransactionContracts.ADDRESS_PROFILE)
        if (discovery == null) return
        val regions = (discovery as Map<*, *>).row("regions")
        val windows = SdTransactionContracts.windows(SdTransactionContracts.ADDRESS_PROFILE)
        for (key in regions.keys) {
            val region = regions.row(key)
            if (windows.any { it.address < region.number("endExclusive") && region.number("base") < it.address + it.size }) {
                require(region.number("accessAttempts") == 0L && region.number("position") == 0L &&
                    region["retainedWritten"] == false && region["fixedSelected"] == false && region["scriptTailSelection"] == null) {
                    "SD insertion overlaps used discovery route $key"
                }
            }
        }
    }

    internal fun payload(id: String, saved: Map<String, Any?>, component: StatefulComponent): Map<String, Any?>? =
        when (id) {
            "sdcard-reader" -> readerPayload(saved, component.saveSnapshot())
            "soc-sd-host" -> hostPayload(saved, component.saveSnapshot())
            "soc-gpio" -> {
                if (detectLow) require(saved.row("scalars").number("input10") and 0x10 != 0L) {
                    "Selected SD1 detect input is already low"
                }
                null
            }
            "soc-gic" -> {
                checkSdInterrupts(saved)
                null
            }
            else -> null
        }

    internal fun readerPayload(saved: Map<String, Any?>, live: Map<String, Any?>): Map<String, Any?> {
        val slots = live.getValue("slots") as List<*>
        val card = slots.first() as Map<*, *>
        require(slots.size == 2 && card["inserted"] == true && card["readOnly"] == readOnly && card["sha256"] == imageSha)
        require("modifiedSectors" !in card || card["modifiedSectors"] == emptyMap<String, String>())
        require((slots[1] as Map<*, *>)["inserted"] == false)
        val emptyCard = mapOf("slot" to 1, "inserted" to false, "readOnly" to true, "sizeBytes" to null, "sha256" to null)
        val oldScalars = live.row("scalarValues") + mapOf("slot1Inserted" to false, "slot1Image" to previousImageName,
            "slot1ReadOnly" to true)
        require(saved == live + mapOf("scalarValues" to oldScalars, "slots" to listOf(emptyCard, slots[1]))) {
            "Insertion requires matching empty reader slots and prefix settings"
        }
        return live
    }

    internal fun hostPayload(saved: Map<String, Any?>, live: Map<String, Any?>): Map<String, Any?> {
        require(saved["kind"] == "init" && live["kind"] == "transaction" && live["started"] == true && live["addressExtension"] == true)
        val fresh = live.row("legacySnapshot")
        fun rebind(prefix: Map<String, Any?>, delegate: Map<String, Any?>): Map<String, Any?> {
            val scalars = delegate.row("scalarValues")
            require(prefix.row("scalarValues") == scalars + ("inputFile" to legacyFile) &&
                prefix["inputCanonicalSha256"] == delegate["inputCanonicalSha256"]) { "Incompatible v5 SD input" }
            return prefix + ("scalarValues" to scalars)
        }
        val clock = saved.row("clockSnapshot")
        val freshClock = fresh.row("clockSnapshot")
        val control = clock.row("controlSnapshot")
        val reboundClock = rebind(clock, freshClock) + ("controlSnapshot" to rebind(control, freshClock.row("controlSnapshot")))
        val legacy = rebind(saved, fresh) + ("clockSnapshot" to reboundClock)
        require(live.number("commandCount") == 0L)
        val slots = (live.getValue("slots") as List<*>).mapIndexed { slot, raw ->
            val bank = raw as Map<*, *>
            require(bank["pending"] == null && bank["deadline"] == null && bank["clockDeadline"] == null &&
                bank["resetDeadline"] == null && bank["fault"] == null) { "SD bank already active" }
            val words = saved.row("initWords").row(slot.toString())
            val clockKey = "$slot:0"
            val inheritedClock = clock.row("clocks").row(clockKey)
            require(inheritedClock.number("storedWord") == bank.number("clockControl") && inheritedClock.number("consumedSamples") == 0L) {
                "Cannot replace a modified or consumed v5 transaction clock"
            }
            require(words.number("present4024") == 0x20000L && words.number("host4028") and 0x18 == 0L) {
                "Conflicting prefix SD presence or DMA mode"
            }
            bank + mapOf("blockCount" to words["block4006"], "hostControl" to words["host4028"],
                "control2" to control.row("registers").row(slot.toString())["control403e"])
        }
        return live + mapOf("legacySnapshot" to legacy, "slots" to slots)
    }

    internal fun applyDetect(host: ComponentHost) {
        if (!detectLow) return
        val gpio = host.components().single { it.descriptor.id == "soc-gpio" }
        val before = gpio.component.state().number("input10")
        require(before and 0x10 != 0L) { "Selected SD1 detect input is already low" }
        host.patch("soc-gpio", gpio.revision, mapOf("input10" to (before and 0x10L.inv()))).join()
    }
}
