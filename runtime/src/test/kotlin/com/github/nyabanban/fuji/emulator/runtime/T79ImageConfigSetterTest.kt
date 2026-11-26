package com.github.nyabanban.fuji.emulator.runtime

import kotlin.test.*
import org.junit.jupiter.api.Test

class T79ImageConfigSetterTest {
    private var core = 1
    private var pc = 0x5afe0L
    private var lr = 0L
    private val owner = object : DiscoveryContext {
        override fun core() = core
        override fun pc() = pc
        override fun lr() = lr
        override fun tick() = 0L
    }
    private var bank = T79ImageConfigSetter()

    private fun read(offset: Int, call: Int) {
        pc = 0x5afe0
        lr = call + 4L
        assertEquals(0L, bank.access(offset, 4, false, 0, owner))
    }

    private fun roundTrip() {
        val snapshot = bank.snapshot()
        val receipt = bank.receipt()
        bank = T79ImageConfigSetter.restore(snapshot)
        assertEquals(receipt, bank.receipt())
        assertEquals(snapshot, bank.snapshot())
    }

    private fun reject(offset: Int, width: Int = 4, write: Boolean = false, value: Long = 0) {
        val before = bank.snapshot()
        assertNull(bank.access(offset, width, write, value, owner))
        assertEquals(before, bank.snapshot())
    }

    @Test fun rejectsUnqualifiedReadsWithoutMovingTheCursor() {
        lr = 0x11b9720
        reject(0x020, width = 2)
        reject(0x021)
        reject(0x0c)
        core = 0
        reject(0x020)
        core = 1
        pc = 0x5af98
        reject(0x020)
        reject(0x020, write = true)
        pc = 0x5afe0
        lr += 4
        reject(0x020)
        lr = 0x11b9b7c
        reject(0x020)
        read(0x020, 0x11b971c)
        roundTrip()
        reject(0x020)
    }

    private fun configuration(enabled: Boolean) {
        read(0x020, if (enabled) 0x11b971c else 0x11b9600)
        roundTrip()
        val accepted = linkedMapOf<String, Long>()
        fun write(offset: Int, call: Int, value: Long) {
            pc = 0x5af98
            lr = call + 4L
            reject(offset, width = 1, write = true, value = value)
            reject(offset, write = true, value = 0x100000000)
            assertEquals(value, bank.access(offset, 4, true, value, owner))
            accepted[offset.toString(16)] = value
            assertEquals(accepted, bank.receipt()["writtenWords"])
            roundTrip()
        }
        write(0x020, if (enabled) 0x11b9728 else 0x11b960c, if (enabled) 1 else 0)
        val activeCalls = listOf(0x11b976c, 0x11b9784, 0x11b97b8, 0x11b97f8, 0x11b97f8, 0x11b97f8,
            0x11b9828, 0x11b9858, 0x11b9898, 0x11b98d0, 0x11b9910, 0x11b9948)
        val payloads = listOf(0x0abc0defL, 0x789, 0x07770300, 0x01230045, 2, 1,
            2, 0x00ab1240, 0x00200808, 0x00ab2d40, 0x00030708, 0x00ff0940)
        activeCalls.forEachIndexed { index, call ->
            val offset = 0x024 + index * 4
            val selected = if (enabled) call else 0x11b961c + 0x10 * index
            lr = selected + 4L
            reject(offset + 4, write = true)
            if (!enabled) reject(offset, write = true, value = 1)
            write(offset, selected, if (enabled) payloads[index] else 0)
        }
        write(0x054, 0x11b96ec, 0xfed)
        write(0x058, 0x11bd27c, 0x01000100)
        repeat(6) { group ->
            val offset = 0x09c + 16 * group
            write(offset, 0x11b9df8, 0x80022345L + group)
            write(offset + 4, 0x11b9e14, 0x0fea0123L + group)
            write(offset + 8, 0x11b9e30, 0x0fef0fffL - group)
            write(offset + 12, 0x11b9e5c, 0x0a0b0cddL - group)
        }
        write(0x0fc, 0x11ba138, 0x01010157)
        write(0x100, 0x11ba168, 0x01ff01ff)
        write(0x104, 0x11ba198, 2)
        lr = 0x11bab7c
        reject(0x130, write = true, value = 0x60000)
        reject(0x130, write = true, value = 0x0400)
        write(0x130, 0x11bab78, 0x503ff)
        assertTrue(bank.complete)
        reject(0x130, write = true, value = 0x503ff)
        pc = 0x5afe0
        lr = 0x11b9b7c
        reject(0x020)
    }

    @Test fun retainsEveryEnabledSetterAndCommitsOnce() = configuration(true)
    @Test fun clearBranchZerosOnlyItsOwnFields() = configuration(false)

    @Test fun rejectsInconsistentOrForgedSnapshot() {
        val empty = bank.snapshot()
        for (bad in listOf(empty + ("enabled" to "false"), empty + ("writes" to listOf(0)),
            empty + ("profile" to "other"), empty - "enabled")) {
            assertFailsWith<IllegalArgumentException> { T79ImageConfigSetter.restore(bad) }
        }
        read(0x020, 0x11b971c)
        for (bad in listOf(listOf(0L), listOf(1L, -1L), listOf(1L, 0x1000L), listOf(1.5), List(44) { 1L })) {
            assertFailsWith<IllegalArgumentException> { T79ImageConfigSetter.restore(bank.snapshot() + ("writes" to bad)) }
        }
    }
}
