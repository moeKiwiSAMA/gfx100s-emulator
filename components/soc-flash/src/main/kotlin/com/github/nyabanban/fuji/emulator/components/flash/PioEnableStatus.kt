package com.github.nyabanban.fuji.emulator.components.flash

/** Functional latch only. Initial state/timing HYP; no array writes or measured chip/packing law. */
internal class PioEnableStatus(private val supportWrsr: Boolean = false) {
    data class Operation(val opcode: Int, val selector: Int, val txBytes: Int = 0, val txData: Long = 0L)
    private val enabled = BooleanArray(2)
    private val statusReg = LongArray(2)
    private val configReg = LongArray(2)
    private var closed = false
    fun live() { check(!closed) { "PIO enable/status component closed" } }
    fun prepare(control: Long, command: Long, txData: Long = 0L): Operation {
        live()
        val selector = ((control ushr 10) and 15).toInt()
        require(selector in 0..1 && control and 0x40000000L == 0L) { "PIO enable/status selector or packed mode unqualified" }
        val opcode = (command ushr 24).toInt()
        if (supportWrsr && opcode == 1) {
            val hasTx = (command and 0x8000L) != 0L
            val txBytes = (((command ushr 12) and 7L) + 1L).toInt()
            val hasRx = (command and 0x800000L) != 0L
            val hasAddr = (command and 0x80000L) != 0L
            require(hasTx && !hasRx && !hasAddr && txBytes in 1..2 && (command and 0x00ff0ffEL) == 0L) {
                "PIO WRSR command shape unqualified"
            }
            require(enabled[selector]) { "PIO WRSR requires completed write enable" }
            return Operation(1, selector, txBytes, txData and 0xffffL)
        }
        val validatedOpcode = when (command) {
            0x06000001L -> 6
            0x05800001L -> 5
            0x058b0001L -> if (supportWrsr) 5 else error("PIO enable/status command shape unqualified")
            else -> error("PIO enable/status command shape unqualified")
        }
        return Operation(validatedOpcode, selector)
    }
    /** Null means TX-only: retain prior RX. Factory calls this only at the declared deadline. */
    fun isEnabled(selector:Int):Boolean { live();require(selector in 0..1);return enabled[selector] }
    fun consumeEnable(selector:Int) { live();require(isEnabled(selector)) { "flash write enable missing" };enabled[selector]=false }
    fun complete(operation: Operation, busy:Boolean=false): Long? {
        live()
        require(operation.selector in 0..1 && (operation.opcode in listOf(5,6) || (supportWrsr && operation.opcode == 1)))
        return when (operation.opcode) {
            6 -> { enabled[operation.selector] = true; null }
            1 -> {
                statusReg[operation.selector] = operation.txData and 0xfcL
                if (operation.txBytes >= 2) {
                    configReg[operation.selector] = (operation.txData ushr 8) and 0xffL
                }
                enabled[operation.selector] = false
                null
            }
            else -> (statusReg[operation.selector] and 0xfcL) or (if (enabled[operation.selector]) 2L else 0L) or (if (busy) 1L else 0L)
        }
    }
    fun close() { closed = true }
    fun state(): Map<String, Any> = mapOf(
        "writeEnabledBySelector" to enabled.toList(), "closed" to closed,
        "scope" to if (supportWrsr) {
            "HYP initial-false selector0/1 latch + WRSR status/config latch;06 sets WEL,01 latches SR/CR and clears WEL,05 reports SR|WEL|WIP."
        } else {
            "HYP initial-false selector0/1 latch;06 sets at completion,05 reports bit1. Bit30/other commands refused; array mutation/physical reset/geometry unqualified."
        },
    ) + if (supportWrsr) {
        mapOf(
            "statusRegisterBySelector" to statusReg.toList(),
            "configRegisterBySelector" to configReg.toList(),
        )
    } else {
        emptyMap()
    }
    fun saveSnapshot(): Map<String, Any?> {
        live()
        val base = linkedMapOf<String, Any?>("writeEnabledBySelector" to enabled.toList())
        if (supportWrsr) {
            base["statusRegisterBySelector"] = statusReg.toList()
            base["configRegisterBySelector"] = configReg.toList()
        }
        return base
    }
    data class ValidatedSnapshot(val enabled: BooleanArray, val statusReg: LongArray, val configReg: LongArray)
    fun preflightSnapshot(raw: Any?): ValidatedSnapshot {
        live()
        val row = raw as? Map<*, *> ?: throw IllegalArgumentException("Missing PioEnableStatus snapshot")
        val expectedKeys = if (supportWrsr) {
            setOf("writeEnabledBySelector", "statusRegisterBySelector", "configRegisterBySelector")
        } else {
            setOf("writeEnabledBySelector")
        }
        require(row.keys == expectedKeys) { "Unexpected PioEnableStatus snapshot keys" }
        val flags = row["writeEnabledBySelector"] as? List<*>
            ?: throw IllegalArgumentException("Missing writeEnabledBySelector list")
        require(flags.size == enabled.size && flags.all { flag -> flag is Boolean }) {
            "writeEnabledBySelector must contain two booleans"
        }
        val nextEnabled = BooleanArray(enabled.size) { selectorIndex -> flags[selectorIndex] as Boolean }
        val nextStatus = LongArray(statusReg.size)
        val nextConfig = LongArray(configReg.size)
        if (supportWrsr) {
            val rawSr = row["statusRegisterBySelector"] as? List<*> ?: throw IllegalArgumentException("Missing statusRegisterBySelector")
            val rawCr = row["configRegisterBySelector"] as? List<*> ?: throw IllegalArgumentException("Missing configRegisterBySelector")
            require(rawSr.size == statusReg.size && rawCr.size == configReg.size) { "Invalid status/config register snapshot length" }
            for (i in 0 until statusReg.size) {
                val sr = (rawSr[i] as? Number)?.toLong() ?: throw IllegalArgumentException("Invalid statusRegisterBySelector[$i]")
                val cr = (rawCr[i] as? Number)?.toLong() ?: throw IllegalArgumentException("Invalid configRegisterBySelector[$i]")
                require(sr in 0L..0xfcL && (sr and 3L) == 0L && cr in 0L..0xffL) { "Out-of-range status/config register[$i]" }
                nextStatus[i] = sr
                nextConfig[i] = cr
            }
        }
        return ValidatedSnapshot(nextEnabled, nextStatus, nextConfig)
    }
    fun applySnapshot(validated: ValidatedSnapshot) {
        live()
        require(validated.enabled.size == enabled.size) { "Invalid validated PioEnableStatus size" }
        validated.enabled.copyInto(enabled)
        validated.statusReg.copyInto(statusReg)
        validated.configReg.copyInto(configReg)
    }
}
