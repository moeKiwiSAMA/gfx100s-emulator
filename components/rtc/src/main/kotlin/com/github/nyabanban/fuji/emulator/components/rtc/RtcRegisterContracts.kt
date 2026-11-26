package com.github.nyabanban.fuji.emulator.components.rtc

import com.github.nyabanban.fuji.emulator.api.PowerRegisterContract

/** Stable implementation IDs checked against the central YAML logical dictionary at assembly. */
internal fun rtcRegisterContracts(scriptedStageReads: Boolean): List<PowerRegisterContract> = listOf(
    PowerRegisterContract("reg.rtc.stage-low", 0x1d, 16, scriptedStageReads, true),
    PowerRegisterContract("reg.rtc.stage-high", 0x1e, 16, scriptedStageReads, true),
    PowerRegisterContract("reg.rtc.counter-low", 0x23, 16, true, false),
    PowerRegisterContract("reg.rtc.counter-high", 0x24, 16, true, false),
    PowerRegisterContract("reg.rtc.control", 0x27, 16, true, true),
)
