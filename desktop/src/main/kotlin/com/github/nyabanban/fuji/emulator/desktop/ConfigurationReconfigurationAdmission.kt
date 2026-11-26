package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.firmware.BaseParameterContracts
import com.github.nyabanban.fuji.emulator.firmware.ConfigurationReconfigurationContracts
import com.github.nyabanban.fuji.emulator.firmware.DeviceWindow
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage

/** Full initial live declaration and bound ports precede the additional DAT contract. No legacy projection. */
internal object ConfigurationReconfigurationAdmission {
    const val PROFILE = "configuration-pair-reconfiguration-experimental-v2"
    const val KEY = "configurationReconfiguration"
    private val indices = listOf(3, 9, 12)
    private val groups = listOf(10, 16, 19)
    private val bases = listOf(0xff704000L, 0xff706000L, 0xfffae000L)
    private fun integer(value: Any?): Long {
        require(value is Byte || value is Short || value is Int || value is Long) { "reconfiguration declaration requires exact integers" }
        return (value as Number).toLong()
    }
    fun qualify(image: FirmwareImage, state: Map<String, Any?>, ports: List<String>, windows: List<DeviceWindow>): Map<String, Any?>? =
        qualify(state, ports, windows) { ConfigurationReconfigurationContracts.observe(image) }

    internal fun qualify(state: Map<String, Any?>, ports: List<String>, windows: List<DeviceWindow>, observe: () -> Map<String, Any?>): Map<String, Any?>? {
        if (state["profile"] != PROFILE) {
            require("reconfiguration" !in state) { "reconfiguration declaration requires its independent profile" }
            return null
        }
        require(state["baseProfile"] == BaseParameterContracts.PROFILE && state["baseParametersEnabled"] == true &&
            (state["baseInputFile"] as? String)?.isNotBlank() == true) { "reconfiguration requires the explicit original base input" }
        val initial = integer(state["initialValue"]).also { require(it in 0..511) }
        val delay = integer(state["completionTicks"]).also { require(it in 1..1000000) }
        val base = state["baseParameters"] as? Map<*, *> ?: error("reconfiguration base state missing")
        val inputs = base["inputs"] as? Map<*, *> ?: error("reconfiguration explicit base inputs missing")
        require(inputs.keys == setOf("initialPairs", "initialStatus", "requestTicks", "settleTicks"))
        fun words(key: String, low: Long, high: Long): List<Long> = (inputs[key] as? List<*> ?: error("base input array missing")).also { require(it.size == 13) }.map { integer(it).also { n -> require(n in low..high) } }
        val initialStatus = words("initialStatus", 0, 1)
        val requestTicks = words("requestTicks", 1, 1000000)
        val settleTicks = words("settleTicks", 1, 1000000)
        val pairs = (inputs["initialPairs"] as? List<*> ?: error("base pairs missing")).also { require(it.size == 13) }.map { row ->
            (row as? List<*> ?: error("base pair missing")).also { require(it.size == 2) }.map { integer(it).also { n -> require(n in 0..0xffffffffL) } }
        }
        val expectedBase = mapOf(
            "profile" to BaseParameterContracts.PROFILE, "groupIndices" to BaseParameterContracts.groupIndices, "inputs" to inputs,
            "staged" to pairs, "applied" to pairs, "status" to initialStatus, "written" to List(13) { 0 }, "settled" to List(13) { false }, "pending" to List(13) { null },
            "eventCount" to 0L, "eventCapacity" to 256, "eventLogTruncated" to false, "events" to emptyList<Any>(),
            "readCount" to 0L, "writeCount" to 0L, "accessCount" to 0L, "accessCapacity" to 512, "accessLogTruncated" to false, "accesses" to emptyList<Any>(),
            "basis" to "Selected-DAT word transfers/poll masks; explicit opaque u32 initial pairs/status and unmeasured request/settle delays. No physical clock/reset/ready/IRQ effects")
        require(base == expectedBase) { "reconfiguration requires the complete unmodified initial base state" }
        val counts = listOf(4,3,4,4,4,4,4,4,1,1,2,1,1,4,3,2,1,3,1,1)
        require(state["groupCounts"] == counts && state["staged"] == List(counts.sum()) { initial } && state["applied"] == state["staged"] &&
            state["written"] == List(20) { false } && state["pending"] == List(20) { null } && state["eventCount"] == 0L &&
            state["events"] == emptyList<Any>() && state["eventLogTruncated"] == false && state["physicalClockResetEffects"] == false && state["irq"] == false) {
            "reconfiguration requires the complete initial nine-bit declaration"
        }
        val model = mapOf("profile" to PROFILE, "eligibleBaseIndices" to indices, "eligibleGroups" to groups,
            "commands" to listOf(5,6), "requestMask" to 7L, "statusMask" to 1L, "completionTicks" to delay,
            "generations" to listOf(0L,0L,0L), "contexts" to listOf(null,null,null), "eventCount" to 0L, "eventCapacity" to 256,
            "eventLogTruncated" to false, "events" to emptyList<Any>(), "causalMapping" to "HYPOTHESIS", "timing" to "HYPOTHESIS", "physicalFunction" to "UNKNOWN",
            "physicalClockResetEffects" to false, "irq" to false)
        require(state["reconfiguration"] == model) { "reconfiguration JAR full declaration differs" }
        require(state.keys == setOf("profile","initialValue","completionTicks","baseProfile","baseInputFile","groupCounts","staged","applied","pending","written","events","eventCount","eventLogTruncated","physicalClockResetEffects","irq","baseParametersEnabled","baseParameters","reconfiguration")) { "reconfiguration initial state has unexpected fields" }
        val expectedPorts = BaseParameterContracts.bases.indices.map { "soc-configuration.base-status$it" }
        require(ports == expectedPorts) { "reconfiguration status port claims differ" }
        BaseParameterContracts.windows(windows)
        groups.forEachIndexed { slot, group ->
            require(windows.single { it.port == "soc-configuration.group$group" } == DeviceWindow("soc-configuration", bases[slot], 0x84, "soc-configuration.group$group")) { "reconfiguration actual group binding differs" }
        }
        val dat = observe()
        require(dat["eligibleBaseIndices"] == indices && dat["eligibleGroups"] == groups && dat["eligibleBases"] == bases && dat["commands"] == listOf(5,6)) { "reconfiguration DAT domains differ from model" }
        val command6 = dat["command6"] as? Map<*, *> ?: error("reconfiguration command6 contract missing")
        require(command6["commandPollMask"] == 7 && command6["statusOffset"] == 240 && command6["statusPollMask"] == 1) { "reconfiguration DAT completion fields differ" }
        return mapOf("profile" to PROFILE, "dat" to dat, "model" to model, "baseInputs" to inputs,
            "modelLaw" to "HYPOTHESIS: 5 creates a generation with the original applied pair, opens after explicit completionTicks without changing pair/F0/nine-bit applied; 6 requires that open generation and both newly written u32 words, latches those guest words, acknowledges at requestTicks and applies them/F0 at settleTicks, then consumes the context. Reissue/reentry and legacy commands within this group context are not supported by this model. Other groups remain independent.",
            "delayInputs" to mapOf("completionTicks" to delay,"requestTicks" to requestTicks,"settleTicks" to settleTicks),
            "physicalFunction" to "UNKNOWN", "irq" to false, "physicalClockResetEffects" to false, "legacyContractsUnchanged" to true)
    }
}
