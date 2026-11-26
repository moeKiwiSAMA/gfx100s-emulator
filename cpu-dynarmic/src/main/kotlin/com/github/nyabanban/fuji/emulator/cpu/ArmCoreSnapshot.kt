package com.github.nyabanban.fuji.emulator.cpu

/** Full AArch32 Cortex-A7 architectural register snapshot across banked modes, CP15, and VFP/NEON. */
object ArmCoreSnapshot {
    const val SCHEMA = 1
    private const val SPSR = 13
    private const val D0 = 14

    private data class ModeSpec(val name: String, val code: Long, val hasFiqRegs: Boolean, val hasSpsr: Boolean)
    private data class Cp15Spec(val name: String, val opc1: Int, val crn: Int, val crm: Int, val opc2: Int)

    private val modes = listOf(
        ModeSpec("sys", 0x1fL, hasFiqRegs = false, hasSpsr = false),
        ModeSpec("fiq", 0x11L, hasFiqRegs = true, hasSpsr = true),
        ModeSpec("irq", 0x12L, hasFiqRegs = false, hasSpsr = true),
        ModeSpec("svc", 0x13L, hasFiqRegs = false, hasSpsr = true),
        ModeSpec("abt", 0x17L, hasFiqRegs = false, hasSpsr = true),
        ModeSpec("und", 0x1bL, hasFiqRegs = false, hasSpsr = true),
    )

    private val cp15Specs = listOf(
        Cp15Spec("sctlr", 0, 1, 0, 0),
        Cp15Spec("cpacr", 0, 1, 0, 2),
        Cp15Spec("ttbr0", 0, 2, 0, 0),
        Cp15Spec("ttbr1", 0, 2, 0, 1),
        Cp15Spec("ttbcr", 0, 2, 0, 2),
        Cp15Spec("dacr", 0, 3, 0, 0),
        Cp15Spec("dfsr", 0, 5, 0, 0),
        Cp15Spec("ifsr", 0, 5, 0, 1),
        Cp15Spec("dfar", 0, 6, 0, 0),
        Cp15Spec("ifar", 0, 6, 0, 2),
        Cp15Spec("prrr", 0, 10, 2, 0),
        Cp15Spec("nmrr", 0, 10, 2, 1),
        Cp15Spec("vbar", 0, 12, 0, 0),
        Cp15Spec("fcseidr", 0, 13, 0, 0),
        Cp15Spec("contextidr", 0, 13, 0, 1),
        Cp15Spec("tpidrurw", 0, 13, 0, 2),
        Cp15Spec("tpidruro", 0, 13, 0, 3),
        Cp15Spec("tpidrprw", 0, 13, 0, 4),
    )

    private fun u32(raw: Any?): Long {
        require(raw is Byte || raw is Short || raw is Int || raw is Long) { "u32 integer required: $raw" }
        val value = (raw as Number).toLong()
        require(value in 0..0xffff_ffffL) { "u32 out of range: $value" }
        return value
    }

    fun normalize(snapshot: Map<*, *>): Map<String, Any> {
        require((snapshot["schema"] as? Number)?.toInt() == SCHEMA) { "unsupported core snapshot schema" }
        val unbankedRaw = snapshot["unbanked"] as? Map<*, *> ?: error("missing unbanked registers")
        val banksRaw = snapshot["banks"] as? Map<*, *> ?: error("missing banked registers")
        val cp15Raw = snapshot["cp15"] as? Map<*, *> ?: error("missing cp15 registers")
        val vfpRaw = snapshot["floatingPoint"] as? Map<*, *> ?: error("missing floatingPoint registers")

        val targetCpsr = u32(unbankedRaw["cpsr"])
        val targetMode = targetCpsr and 0x1fL
        require(targetMode in setOf(0x10L, 0x11L, 0x12L, 0x13L, 0x17L, 0x1bL, 0x1fL)) {
            "unqualified ARM target mode: 0x${targetMode.toString(16)}"
        }
        val unbanked = linkedMapOf<String, Long>()
        for (regIndex in 0..7) unbanked["r$regIndex"] = u32(unbankedRaw["r$regIndex"])
        unbanked["pc"] = u32(unbankedRaw["pc"])
        unbanked["cpsr"] = targetCpsr

        val banks = linkedMapOf<String, Map<String, Long>>()
        for (spec in modes) {
            val rowRaw = banksRaw[spec.name] as? Map<*, *> ?: error("missing bank ${spec.name}")
            val row = linkedMapOf<String, Long>()
            if (spec.name == "sys" || spec.hasFiqRegs) {
                for (regIndex in 8..12) row["r$regIndex"] = u32(rowRaw["r$regIndex"])
            }
            row["sp"] = u32(rowRaw["sp"])
            row["lr"] = u32(rowRaw["lr"])
            if (spec.hasSpsr) row["spsr"] = u32(rowRaw["spsr"])
            banks[spec.name] = row
        }

        val cp15 = linkedMapOf<String, Long>()
        for (spec in cp15Specs) {
            cp15[spec.name] = u32(cp15Raw[spec.name])
        }

        val dRegsRaw = vfpRaw["dRegisters"] as? List<*> ?: error("missing dRegisters")
        require(dRegsRaw.size == 32) { "expected 32 dRegisters" }
        val dRegs = (0..31).map { index ->
            val hex = dRegsRaw[index] as? String ?: error("dRegister $index must be hex string")
            require(hex.length == 16) { "dRegister $index must be 16 hex chars" }
            java.lang.Long.parseUnsignedLong(hex, 16).toULong().toString(16).padStart(16, '0')
        }
        val vfp = linkedMapOf<String, Any>(
            "fpexc" to u32(vfpRaw["fpexc"]),
            "fpscr" to u32(vfpRaw["fpscr"]),
            "dRegisters" to dRegs,
        )
        return linkedMapOf(
            "schema" to SCHEMA,
            "unbanked" to unbanked,
            "banks" to banks,
            "cp15" to cp15,
            "floatingPoint" to vfp,
        )
    }

    /** Captures full architectural Cortex-A7 state for [core] directly from a live [Dynarmic] engine. */
    fun capture(dyn: Dynarmic, core: Int): Map<String, Any> {
        val baseCpsr = dyn.getCpsr(core) and 0xffff_ffffL
        val baseMode = baseCpsr and 0x1fL
        require(baseMode in setOf(0x10L, 0x11L, 0x12L, 0x13L, 0x17L, 0x1bL, 0x1fL)) {
            "unqualified ARM mode for Dynarmic snapshot: 0x${baseMode.toString(16)}"
        }
        val unbanked = linkedMapOf<String, Long>()
        for (r in 0..7) unbanked["r$r"] = dyn.getReg(core, r) and 0xffff_ffffL
        unbanked["pc"] = dyn.getReg(core, 15) and 0xffff_ffffL
        unbanked["cpsr"] = baseCpsr

        val banks = linkedMapOf<String, Map<String, Long>>()
        for (spec in modes) {
            val modeCode = spec.code.toInt()
            val row = linkedMapOf<String, Long>()
            if (spec.name == "sys" || spec.hasFiqRegs) {
                for (r in 8..12) row["r$r"] = dyn.getBankedReg(core, modeCode, r) and 0xffff_ffffL
            }
            row["sp"] = dyn.getBankedReg(core, modeCode, 13) and 0xffff_ffffL
            row["lr"] = dyn.getBankedReg(core, modeCode, 14) and 0xffff_ffffL
            if (spec.hasSpsr) {
                row["spsr"] = dyn.getBankedReg(core, modeCode, 16) and 0xffff_ffffL
            }
            banks[spec.name] = row
        }

        val cp15 = linkedMapOf<String, Long>()
        for (spec in cp15Specs) {
            cp15[spec.name] = dyn.getCp15(core, spec.opc1, spec.crn, spec.crm, spec.opc2) and 0xffff_ffffL
        }

        val vfp = linkedMapOf<String, Any>(
            "fpexc" to (dyn.getFpexc(core) and 0xffff_ffffL),
            "fpscr" to (dyn.getFpscr(core) and 0xffff_ffffL),
            "dRegisters" to (0..31).map { d -> dyn.getDreg(core, d).toULong().toString(16).padStart(16, '0') },
        )
        return linkedMapOf(
            "schema" to SCHEMA,
            "unbanked" to unbanked,
            "banks" to banks,
            "cp15" to cp15,
            "floatingPoint" to vfp,
        )
    }

    /** Restores full architectural Cortex-A7 state onto [core] in a live [Dynarmic] engine. */
    fun restore(dyn: Dynarmic, core: Int, snapshot: Map<*, *>) {
        val normalized = normalize(snapshot)
        val unbanked = normalized["unbanked"] as Map<*, *>
        val banks = normalized["banks"] as Map<*, *>
        val cp15 = normalized["cp15"] as Map<*, *>
        val vfp = normalized["floatingPoint"] as Map<*, *>
        val targetCpsr = u32(unbanked["cpsr"])
        val targetPc = u32(unbanked["pc"])

        for (spec in cp15Specs) {
            dyn.setCp15(core, spec.opc1, spec.crn, spec.crm, spec.opc2, u32(cp15[spec.name]))
        }

        val dRegs = vfp["dRegisters"] as? List<*> ?: error("missing dRegisters")
        require(dRegs.size == 32) { "expected 32 dRegisters" }
        dyn.setFpexc(core, 0x4000_0000L)
        for (i in 0..31) {
            val hex = dRegs[i] as? String ?: error("dRegister $i must be hex string")
            require(hex.length == 16) { "dRegister $i must be 16 hex chars" }
            dyn.setDreg(core, i, java.lang.Long.parseUnsignedLong(hex, 16))
        }
        dyn.setFpscr(core, u32(vfp["fpscr"]))
        dyn.setFpexc(core, u32(vfp["fpexc"]))

        // Set target CPSR first so subsequent setBankedReg updates both live regs (for active mode)
        // and inactive mode banks coherently without spurious mode-switch side effects.
        dyn.setCpsr(core, targetCpsr)
        for (spec in modes) {
            val modeCode = spec.code.toInt()
            val row = banks[spec.name] as? Map<*, *> ?: error("missing bank ${spec.name}")
            if (spec.name == "sys" || spec.hasFiqRegs) {
                for (r in 8..12) dyn.setBankedReg(core, modeCode, r, u32(row["r$r"]))
            }
            dyn.setBankedReg(core, modeCode, 13, u32(row["sp"]))
            dyn.setBankedReg(core, modeCode, 14, u32(row["lr"]))
            if (spec.hasSpsr) {
                dyn.setBankedReg(core, modeCode, 16, u32(row["spsr"]))
            }
        }
        for (r in 0..7) dyn.setReg(core, r, u32(unbanked["r$r"]))
        dyn.setReg(core, 15, targetPc)
        check((dyn.getCpsr(core) and 0xffff_ffffL) == targetCpsr) { "restored Dynarmic CPSR mismatch on core $core" }
        check((dyn.getReg(core, 15) and 0xffff_ffffL) == targetPc) { "restored Dynarmic PC mismatch on core $core" }
    }
}
