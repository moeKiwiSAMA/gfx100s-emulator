package com.github.nyabanban.fuji.emulator.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object LcdcIrq118Dat {
    private val windows = mapOf(
        0x016d5520 to listOf(0x0126fb84L, 0x0126fb6cL),
        0x016d5530 to listOf(0x012708a8L, 0x012708a8L),
        0x0126fb84 to listOf(0xe92d4800L, 0xe28db004L, 0xe3a01001L, 0xe1a00001L, 0xebffff7cL, 0xe8bd8800L),
        0x012708a8 to listOf(0xe92d4800L, 0xe28db004L, 0xe3a00001L, 0xebfffee7L, 0xe8bd8800L),
    )

    fun verify(base: Long, section: ByteArray) {
        require(base == 0x01021000L) { "LCD IRQ118 section4 load address differs" }
        verifyVglibWords("LCD IRQ118", base, section, windows)
    }
}

internal fun verifyVglibWords(label: String, base: Long, section: ByteArray, windows: Map<Int,List<Long>>) {
        val bytes = ByteBuffer.wrap(section).order(ByteOrder.LITTLE_ENDIAN)
        for ((start, words) in windows) {
            for ((index, word) in words.withIndex()) {
                val pc = start + index * 4
                val offset = (pc - base).toInt()
                require(offset <= section.size - 4 && bytes.getInt(offset).toUInt().toLong() == word) {
                    "$label DAT word differs at ${pc.toString(16)}"
                }
            }
        }
    }
