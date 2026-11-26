package com.github.nyabanban.fuji.emulator.desktop

internal object GraphicsFillIrqAdmission {
    fun verify(state: Map<String, Any?>, backing: Map<*, *>, base: Long, app: ByteArray): Map<String, Any> {
        val declaration = backing["fillIrq92"] as Map<*, *>
        val contract = mapOf("setting" to "graphicsFillIrq92Tick", "sink" to "soc-gic.lines", "line" to 92,
            "trigger" to "level", "capacity" to 1, "statusReads" to listOf(0x04000000, 0), "confidence" to "HYPOTHESIS")
        require(contract.all { declaration[it.key] == it.value })
        val tail = state["graphicsFill"] as Map<*, *>
        require(tail["execution"] != null && tail["statusReads"] == listOf(0x04000000L, 0L))
        val tick = deadline(state["graphicsFillIrq92Tick"])
        require(state["fillIrq92"] == mapOf("deadline" to tick, "firedAt" to null, "cleared" to false, "confidence" to "HYPOTHESIS"))
        verifyVglibWords("Graphics IRQ92 callback", base, app, mapOf(
            0x1401e30 to listOf(0xe92d4bf0L), 0x1401e44 to listOf(0xe5954034L),
            0x1401e9c to listOf(0xe5953034L), 0x1401ecc to listOf(0xe3140301L, 0x0afffff1L),
            0x1401edc to listOf(0xe3a0001cL, 0xebffffa2L), 0x1401ef8 to listOf(0xe5823000L),
            0x1401f04 to listOf(0xe5930000L, 0xebfad0ecL)))
        return contract + mapOf("deadline" to tick, "source" to declaration["source"] as String)
    }

    fun verifyCopy(state: Map<String, Any?>, backing: Map<*, *>, base: Long, app: ByteArray): Map<String, Any> {
        val fill = backing["graphicsFillTail"] as? Map<*, *>
        val registers = fill?.get("copyRegisters") as? Map<*, *>
        val declaration = registers?.get("copyIrq92") as? Map<*, *>
        val contract = mapOf("setting" to "graphicsCopyIrq92Tick", "sink" to "soc-gic.lines", "line" to 92,
            "trigger" to "level", "capacity" to 1, "statusReads" to listOf(0x40000000, 0), "control" to 0xc18,
            "clearValue" to 0, "confidence" to "HYPOTHESIS")
        require(declaration != null && contract.all { declaration[it.key] == it.value }) { "Copy IRQ92 declaration differs" }
        val source = declaration["source"]
        require(source is String && source.isNotBlank()) { "Copy IRQ92 declaration requires a source" }
        val tick = deadline(state["graphicsCopyIrq92Tick"])
        require(tick > deadline(state["graphicsFillIrq92Tick"]))
        val tail = state["graphicsFill"] as Map<*, *>
        require(tail["copyExecution"] != null && tail["copyReadbacks"] == emptyList<Any>())
        require(state["copyIrq92"] == mapOf("deadline" to tick, "firedAt" to null, "statusReadTicks" to emptyList<Long>(),
            "clearedAt" to null, "confidence" to "HYPOTHESIS"))
        verifyVglibWords("Graphics IRQ92 copy callback", base, app, mapOf(
            0x1401ec4 to listOf(0xe3140101L, 0x1affffeeL),
            0x1401e88 to listOf(0xe1a02007L, 0xe1a01006L, 0xe3000c18L, 0xebffffb5L, 0xea00000bL)))
        return contract + mapOf("deadline" to tick, "source" to source)
    }

    private fun deadline(raw: Any?): Long {
        require(raw is Int || raw is Long)
        return (raw as Number).toLong().also { require(it in 1..0xffffffffL) }
    }
}
