package com.github.nyabanban.fuji.emulator.cpu

/** Read-only current native floating-point register inspection for [ArmCpuBackend]. */
object FloatingPointDiagnostics {
    const val FPEXC = ArmReg.FPEXC
    const val FPSCR = ArmReg.FPSCR
    const val D0 = ArmReg.D0

    /** Full stored register bits; direct native reads work even when guest FP access is disabled. */
    fun readRegisters(cpu: ArmCpuBackend): Map<String, Any> = mapOf(
        "controls" to read(cpu),
        "dRegisters" to (0..31).map { cpu.register(D0 + it).toULong().toString(16).padStart(16, '0') },
    )

    fun read(cpu: ArmCpuBackend): Map<String, Long> {
        val cpacr = cpu.readCp(15, 0, 1, 0, 2) and 0xffffffffL
        val fpexc = cpu.register(FPEXC) and 0xffffffffL
        return linkedMapOf(
            "cpacr" to cpacr,
            "fpexc" to fpexc,
            "fpscr" to (cpu.register(FPSCR) and 0xffffffffL),
            "cp10AccessBits" to ((cpacr ushr 20) and 3),
            "cp11AccessBits" to ((cpacr ushr 22) and 3),
            "fpexcBit30" to ((fpexc ushr 30) and 1),
        )
    }
}
