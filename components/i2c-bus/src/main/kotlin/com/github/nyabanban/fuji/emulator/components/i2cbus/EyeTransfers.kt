package com.github.nyabanban.fuji.emulator.components.i2cbus

import com.github.nyabanban.fuji.emulator.api.I2cConnection

internal class EyeTransfers(raw: Any?) {
    companion object {
        const val KIND = "eye-sensor-finite-v1"
    }

    private data class Step(val operation: String, val value: Int = 0)

    val transfers: List<Map<String,List<Int>>>
    private val steps: List<Step>
    val size get() = steps.size
    var cursor = 0
    var owner: String? = null
        private set

    init {
        require(raw is List<*> && raw.size in 1..128)
        transfers = raw.map { row ->
            require(row is Map<*,*> && row.keys == setOf("write","read"))
            val write = bytes(row["write"],1..5)
            val read = bytes(row["read"],0..2)
            val valid = when (write.first()) {
                0xb3 -> write.size == 1 && read.size == 1
                0xb8 -> write.size == 1 && read.size == 2
                0xa8 -> write.size == 5 && read.isEmpty()
                0xe5 -> write.size == 1 && read.isEmpty()
                else -> false
            }
            require(valid) { "Unsupported eye sensor transfer" }
            mapOf("write" to write, "read" to read)
        }
        steps = buildList {
            for (transfer in transfers) {
                add(Step("address",0x72))
                transfer.getValue("write").forEach { add(Step("write",it)) }
                val read = transfer.getValue("read")
                if (read.isNotEmpty()) add(Step("address",0x73))
                read.forEach { add(Step("read",it)) }
                add(Step("stop"))
            }
        }
    }

    private fun bytes(raw: Any?, length: IntRange): List<Int> {
        require(raw is List<*> && raw.size in length)
        return raw.map { value ->
            require(value is Byte || value is Short || value is Int || value is Long)
            (value as Number).toLong().also { require(it in 0..255) }.toInt()
        }
    }

    fun connect(name: String): I2cConnection {
        require(name == "soc-i2c.controller1")
        check(owner == null)
        owner = name
        return object : I2cConnection {
            private var closed = false
            private fun live() = check(!closed)
            private fun expect(operation: String, value: Int? = null): Int {
                live()
                val step = steps.getOrNull(cursor)
                require(step != null && step.operation == operation && (value == null || step.value == value)) {
                    "Eye sensor step $cursor expected $step, received $operation $value"
                }
                return step.value
            }
            override fun address(byte: Int): Boolean {
                live()
                require(byte in 0..255)
                if (byte ushr 1 != 0x39) {
                    require(idle())
                    return false
                }
                expect("address",byte)
                cursor++
                return true
            }
            override fun checkWrite(byte: Int) { expect("write",byte) }
            override fun write(byte: Int) { checkWrite(byte); cursor++ }
            override fun peekRead() = expect("read")
            override fun consumeRead(expected: Int) { expect("read",expected); cursor++ }
            private fun idle() = cursor == 0 || steps[cursor-1].operation == "stop"
            override fun stop() {
                live()
                if (!idle()) { expect("stop"); cursor++ }
            }
            override fun close() {
                if (!closed) { closed = true; owner = null }
            }
        }
    }
}
