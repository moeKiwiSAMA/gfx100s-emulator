package com.github.nyabanban.fuji.emulator.api

/**
 * Read-only internal ISP color-pipeline snapshot port exposed by `soc-isp` (`"soc-isp.color-state"`)
 * so sibling hardware encoders (`soc-jpeg`) can apply active real-silicon WB gains and Film Simulation
 * registers (`0xFFF68000`) while keeping the CPU-facing `soc-isp.bank-68000` MMIO port strictly write-only.
 */
interface IspColorStatePort {
    val gainR1024: Int
    val gainG1024: Int
    val gainB1024: Int
    /** Programmed 12-bit linear to 8-bit output transfer curves, sampled on the owner thread. */
    val toneCurves: IspToneCurves? get() = null
    val ccmQ10: IntArray?
    val chromaMatQ10: IntArray?
    val monochromeMode: Boolean
    val monoToneDeltaCb10: Int
    val monoToneDeltaCr10: Int

    companion object {
        const val PORT_ID = "soc-isp.color-state"
    }
}

/** A snapshot of one set of programmed RGB transfer curves. */
class IspToneCurves(val red: IntArray, val green: IntArray, val blue: IntArray, val signature: Long) {
    init {
        require(red.size == 4096 && green.size == 4096 && blue.size == 4096)
    }
}
