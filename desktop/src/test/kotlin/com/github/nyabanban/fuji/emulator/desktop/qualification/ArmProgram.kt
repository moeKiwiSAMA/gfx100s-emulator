package com.github.nyabanban.fuji.emulator.desktop.qualification

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small test-only A32 fixture builder; never ships in desktop.jar. */
class ArmProgram {
    private val words = ArrayList<Int>()
    private val literals = ArrayList<Pair<Int, Int>>()
    fun emit(word: Long) { words.add(word.toInt()) }
    fun pad(address: Int) {
        require(address % 4 == 0 && address >= words.size * 4)
        while (words.size * 4 < address) emit(0xe1a00000)
    }
    fun load(register: Int, value: Long) {
        literals.add(words.size to value.toInt())
        emit(0xe59f0000L or (register.toLong() shl 12))
    }
    fun write(address: Long, value: Long) {
        load(0, address)
        load(1, value)
        emit(0xe5801000)
    }
    fun finish(): ByteArray {
        val result = words.toMutableList()
        literals.forEach { (index, value) ->
            val distance = result.size * 4 - (index * 4 + 8)
            require(distance in 0..4095)
            result[index] = result[index] or distance
            result.add(value)
        }
        return ByteBuffer.allocate(result.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { result.forEach { putInt(it) } }
            .array()
    }
}
