package com.github.nyabanban.fuji.emulator.components.dram

import com.github.nyabanban.fuji.emulator.api.MmioDevice
import java.util.Arrays

/**
 * Factory cold-start DRAM0 / DDR PHY0 / CTL0 / Arbiter / Bridge model owned by `soc-dram`.
 *
 * Opt-in profile `gfx100s-dram-factory-coldstart-v1` promotes the GFX100S 2.13 Boot ROM &
 * Stage-1 IPL DDR bring-up registers out of the runtime diagnostic shim into `soc-dram`:
 * - `soc-dram.coldstart-preinit` (`0xFF505200..0xFF50525C`, 24 x 4 B)
 * - `soc-dram.coldstart-commit` (`0xFF505384`, 4 B commit trigger: write 0/1, poll returns 0)
 * - `soc-dram.coldstart-ctl0` (`0xFF507000..0xFF507C44`, 31 exact offsets, 4-stage init FSM)
 * - `soc-dram.coldstart-phy0` (`0xFF602000..0xFF60475C`, 686 exact offsets, hole `0x035C..0x1FFF` refused)
 * - `soc-dram.coldstart-arbiter` (`0xFF400000..0xFF402C94`, 392 exact offsets)
 * - `soc-dram.coldstart-bridge` (`0xFF480000..0xFF483008`, 8 exact offsets)
 * - `soc-dram.coldstart-stage1-flag` (`0xFB000000`, 4 B Stage-1 pre-DDR flag)
 *
 * All unlisted offsets, unaligned accesses, and non-32-bit widths fail closed.
 */
internal class DramColdStartController {
    private var closed = false
    private val preInitWords = LongArray(PREINIT_WORDS)
    private var commitPending = false
    private var commitCount = 0L
    private var stage1Flag = 0L
    private var initStage = 0
    private var ctl0AckPending = false
    val isInitComplete: Boolean
        get() = initStage >= 4 && !ctl0AckPending
    private val ctl0Words = LongArray(CTL0_OFFSETS.size)
    private val phy0Words = LongArray(PHY0_WORD_COUNT)
    private val arbiterWords = LongArray(ARBITER_OFFSETS.size)
    private val bridgeWords = LongArray(BRIDGE_OFFSETS.size)

    val preInitDevice: MmioDevice = object : MmioDevice {
        override val clockOrder = 9
        override fun read(offset: Long, size: Int): Long {
            checkOpen()
            val slot = preInitSlot(offset, size)
            return preInitWords[slot]
        }

        override fun write(offset: Long, size: Int, value: Long) {
            checkOpen()
            val slot = preInitSlot(offset, size)
            preInitWords[slot] = checkWord(value)
        }
    }

    val commitDevice: MmioDevice = object : MmioDevice {
        override val clockOrder = 9
        override fun read(offset: Long, size: Int): Long {
            checkOpen()
            require(offset == 0L && size == 4) { "DRAM coldstart commit requires aligned 32-bit word at offset 0" }
            if (commitPending) {
                commitPending = false
                commitCount++
            }
            return 0L
        }

        override fun write(offset: Long, size: Int, value: Long) {
            checkOpen()
            require(offset == 0L && size == 4) { "DRAM coldstart commit requires aligned 32-bit word at offset 0" }
            val word = checkWord(value)
            require(word == 0L || word == 1L) { "DRAM coldstart commit accepts only 0 or 1, got 0x${word.toString(16)}" }
            commitPending = (word == 1L)
        }
    }

    val ctl0Device: MmioDevice = object : MmioDevice {
        override val clockOrder = 9
        override fun read(offset: Long, size: Int): Long {
            checkOpen()
            val (off, slot) = ctl0Slot(offset, size)
            return observableCtl0Word(off, slot)
        }

        override fun write(offset: Long, size: Int, value: Long) {
            checkOpen()
            val (off, slot) = ctl0Slot(offset, size)
            val word = checkWord(value)
            ctl0Words[slot] = word
            when (off) {
                0x0c18 -> if ((word and 0x4000_0000L) != 0L && initStage < 1) {
                    initStage = 1
                }
                0x0040 -> if (initStage >= 1 && (word == 0x3L || word == 0x10L) && initStage < 2) {
                    initStage = 2
                }
                0x0360 -> if (initStage >= 3 && word == 0xff00_c00dL) {
                    initStage = 4
                    ctl0AckPending = true
                }
                0x0364 -> if ((word and 1L) != 0L) {
                    ctl0AckPending = false
                }
            }
        }
    }

    val phy0Device: MmioDevice = object : MmioDevice {
        override val clockOrder = 90
        override fun read(offset: Long, size: Int): Long {
            checkOpen()
            val (off, slot) = phy0Slot(offset, size)
            return observablePhy0Word(off, slot)
        }

        override fun write(offset: Long, size: Int, value: Long) {
            checkOpen()
            val (off, slot) = phy0Slot(offset, size)
            val word = checkWord(value)
            phy0Words[slot] = word
            if (off == 0x0000 && initStage >= 2 && (word and 1L) != 0L && initStage < 3) {
                initStage = 3
            }
        }
    }

    val arbiterDevice: MmioDevice = object : MmioDevice {
        override val clockOrder = 9
        override fun read(offset: Long, size: Int): Long {
            checkOpen()
            return arbiterWords[arbiterSlot(offset, size)]
        }

        override fun write(offset: Long, size: Int, value: Long) {
            checkOpen()
            arbiterWords[arbiterSlot(offset, size)] = checkWord(value)
        }
    }

    val bridgeDevice: MmioDevice = object : MmioDevice {
        override val clockOrder = 9
        override fun read(offset: Long, size: Int): Long {
            checkOpen()
            return bridgeWords[bridgeSlot(offset, size)]
        }

        override fun write(offset: Long, size: Int, value: Long) {
            checkOpen()
            bridgeWords[bridgeSlot(offset, size)] = checkWord(value)
        }
    }

    val stage1FlagDevice: MmioDevice = object : MmioDevice {
        override val clockOrder = 9
        override fun read(offset: Long, size: Int): Long {
            checkOpen()
            require(offset == 0L && size == 4) { "DRAM coldstart stage1-flag requires aligned 32-bit word at offset 0" }
            return stage1Flag
        }

        override fun write(offset: Long, size: Int, value: Long) {
            checkOpen()
            require(offset == 0L && size == 4) { "DRAM coldstart stage1-flag requires aligned 32-bit word at offset 0" }
            stage1Flag = checkWord(value)
        }
    }

    fun close() {
        closed = true
    }

    private fun checkOpen() {
        check(!closed) { "DRAM coldstart controller closed" }
    }

    private fun checkWord(value: Long): Long {
        require(value in 0L..0xffff_ffffL) { "DRAM coldstart word out of u32 range: $value" }
        return value
    }

    private fun preInitSlot(offset: Long, size: Int): Int {
        require(size == 4 && offset >= 0L && offset < PREINIT_SIZE && (offset and 3L) == 0L) {
            "DRAM coldstart pre-init requires aligned 32-bit word within [0, 0x${PREINIT_SIZE.toString(16)})"
        }
        return (offset ushr 2).toInt()
    }

    private fun ctl0Slot(offset: Long, size: Int): Pair<Int, Int> {
        require(size == 4 && offset >= 0L && offset < CTL0_SIZE && (offset and 3L) == 0L) {
            "DRAM coldstart CTL0 requires aligned 32-bit offset within [0, 0x${CTL0_SIZE.toString(16)})"
        }
        val off = offset.toInt()
        val slot = Arrays.binarySearch(CTL0_OFFSETS, off)
        require(slot >= 0) { "Unlisted DRAM coldstart CTL0 offset: 0x${off.toString(16)}" }
        return off to slot
    }

    private fun observableCtl0Word(offset: Int, slot: Int): Long = when (offset) {
        0x0c18 -> if (initStage >= 1) 1L else ctl0Words[slot]
        0x0044 -> 0L
        0x0364 -> if (ctl0AckPending) 1L else 0L
        else -> ctl0Words[slot]
    }

    private fun phy0Slot(offset: Long, size: Int): Pair<Int, Int> {
        require(size == 4 && offset >= 0L && offset < PHY0_SIZE && (offset and 3L) == 0L) {
            "DRAM coldstart PHY0 requires aligned 32-bit offset within [0, 0x${PHY0_SIZE.toString(16)})"
        }
        val off = offset.toInt()
        val slot = when {
            off in 0 until (PHY0_LO_WORDS * 4) -> off ushr 2
            off in PHY0_HI_OFFSET until (PHY0_HI_OFFSET + PHY0_HI_WORDS * 4) ->
                PHY0_LO_WORDS + ((off - PHY0_HI_OFFSET) ushr 2)
            else -> -1
        }
        require(slot >= 0) { "Prohibited or unlisted DRAM coldstart PHY0 offset: 0x${off.toString(16)}" }
        return off to slot
    }

    private fun observablePhy0Word(offset: Int, slot: Int): Long {
        val stored = phy0Words[slot]
        return when (offset) {
            0x2670 -> if (initStage >= 1) stored or 0x0003_0000L else stored
            0x2674, 0x2678 -> if (initStage >= 1) stored or 0x0003_0003L else stored
            0x0110 -> if (initStage >= 3) stored or 0x0000_0001L else stored
            0x244c, 0x2620 -> if (initStage >= 3) stored or 0x0001_0001L else stored
            0x20dc, 0x22dc -> if (initStage >= 3) (stored and 0x1000L.inv()) or 0x0000_0300L else stored
            else -> stored
        }
    }

    private fun arbiterSlot(offset: Long, size: Int): Int {
        require(size == 4 && offset >= 0L && offset < ARBITER_SIZE && (offset and 3L) == 0L) {
            "DRAM coldstart arbiter requires aligned 32-bit offset within [0, 0x${ARBITER_SIZE.toString(16)})"
        }
        val off = offset.toInt()
        val slot = Arrays.binarySearch(ARBITER_OFFSETS, off)
        require(slot >= 0) { "Unlisted DRAM coldstart arbiter offset: 0x${off.toString(16)}" }
        return slot
    }

    private fun bridgeSlot(offset: Long, size: Int): Int {
        require(size == 4 && offset >= 0L && offset < BRIDGE_SIZE && (offset and 3L) == 0L) {
            "DRAM coldstart bridge requires aligned 32-bit offset within [0, 0x${BRIDGE_SIZE.toString(16)})"
        }
        val off = offset.toInt()
        val slot = Arrays.binarySearch(BRIDGE_OFFSETS, off)
        require(slot >= 0) { "Unlisted DRAM coldstart bridge offset: 0x${off.toString(16)}" }
        return slot
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "profile" to PROFILE,
        "confidence" to "HYPOTHESIS",
        "preInitNonZeroCount" to preInitWords.count { it != 0L },
        "commitPending" to commitPending,
        "commitCount" to commitCount,
        "stage1FlagFb000000" to stage1Flag,
        "dramInitStage" to initStage,
        "ctl0AckPending" to ctl0AckPending,
        "ctl0NonZeroCount" to ctl0Words.count { it != 0L },
        "phy0NonZeroCount" to phy0Words.count { it != 0L },
        "arbiterNonZeroCount" to arbiterWords.count { it != 0L },
        "bridgeNonZeroCount" to bridgeWords.count { it != 0L },
        "coldStartChannel" to DramHardwareGeometry.channel(0).toStateMap(),
        "hardwareGeometry" to DramHardwareGeometry.toStateMap(),
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "profile" to PROFILE,
        "preInitWords" to preInitWords.toList(),
        "commitPending" to commitPending,
        "commitCount" to commitCount,
        "stage1Flag" to stage1Flag,
        "initStage" to initStage,
        "ctl0AckPending" to ctl0AckPending,
        "ctl0Words" to ctl0Words.toList(),
        "phy0Words" to phy0Words.toList(),
        "arbiterWords" to arbiterWords.toList(),
        "bridgeWords" to bridgeWords.toList(),
    )

    class ValidatedSnapshot(
        val preInitWords: LongArray,
        val commitPending: Boolean,
        val commitCount: Long,
        val stage1Flag: Long,
        val initStage: Int,
        val ctl0AckPending: Boolean,
        val ctl0Words: LongArray,
        val phy0Words: LongArray,
        val arbiterWords: LongArray,
        val bridgeWords: LongArray,
    )

    fun validateSnapshot(raw: Any?): ValidatedSnapshot {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("DRAM coldstart snapshot requires map")
        require(map.keys == SNAPSHOT_KEYS) { "Unexpected DRAM coldstart snapshot keys" }
        require(map["profile"] == PROFILE) { "DRAM coldstart snapshot profile mismatch" }
        val pending = map["commitPending"] as? Boolean
            ?: throw IllegalArgumentException("DRAM coldstart snapshot commitPending requires boolean")
        val count = exactLong(map["commitCount"], "commitCount", 0L, Long.MAX_VALUE)
        val flag = exactLong(map["stage1Flag"], "stage1Flag", 0L, 0xffff_ffffL)
        val stage = exactLong(map["initStage"], "initStage", 0L, 4L).toInt()
        val ack = map["ctl0AckPending"] as? Boolean
            ?: throw IllegalArgumentException("DRAM coldstart snapshot ctl0AckPending requires boolean")
        require(!ack || stage == 4) { "DRAM coldstart snapshot ctl0AckPending requires initStage==4" }
        return ValidatedSnapshot(
            preInitWords = exactWordList(map["preInitWords"], "preInitWords", PREINIT_WORDS),
            commitPending = pending,
            commitCount = count,
            stage1Flag = flag,
            initStage = stage,
            ctl0AckPending = ack,
            ctl0Words = exactWordList(map["ctl0Words"], "ctl0Words", CTL0_OFFSETS.size),
            phy0Words = exactWordList(map["phy0Words"], "phy0Words", PHY0_WORD_COUNT),
            arbiterWords = exactWordList(map["arbiterWords"], "arbiterWords", ARBITER_OFFSETS.size),
            bridgeWords = exactWordList(map["bridgeWords"], "bridgeWords", BRIDGE_OFFSETS.size),
        )
    }

    fun applySnapshot(validated: ValidatedSnapshot) {
        validated.preInitWords.copyInto(preInitWords)
        commitPending = validated.commitPending
        commitCount = validated.commitCount
        stage1Flag = validated.stage1Flag
        initStage = validated.initStage
        ctl0AckPending = validated.ctl0AckPending
        validated.ctl0Words.copyInto(ctl0Words)
        validated.phy0Words.copyInto(phy0Words)
        validated.arbiterWords.copyInto(arbiterWords)
        validated.bridgeWords.copyInto(bridgeWords)
    }

    companion object {
        const val PROFILE = "gfx100s-dram-factory-coldstart-v1"

        const val PREINIT_PORT = "soc-dram.coldstart-preinit"
        const val COMMIT_PORT = "soc-dram.coldstart-commit"
        const val CTL0_PORT = "soc-dram.coldstart-ctl0"
        const val PHY0_PORT = "soc-dram.coldstart-phy0"
        const val ARBITER_PORT = "soc-dram.coldstart-arbiter"
        const val BRIDGE_PORT = "soc-dram.coldstart-bridge"
        const val STAGE1_FLAG_PORT = "soc-dram.coldstart-stage1-flag"

        val ALL_PORTS: List<String> = listOf(
            PREINIT_PORT,
            COMMIT_PORT,
            CTL0_PORT,
            PHY0_PORT,
            ARBITER_PORT,
            BRIDGE_PORT,
            STAGE1_FLAG_PORT,
        )

        const val PREINIT_WORDS = 24
        const val PREINIT_SIZE = PREINIT_WORDS * 4
        const val CTL0_SIZE = 0xc44
        const val PHY0_SIZE = 0x275c
        const val PHY0_LO_WORDS = 215
        const val PHY0_HI_OFFSET = 0x2000
        const val PHY0_HI_WORDS = 471
        const val PHY0_WORD_COUNT = PHY0_LO_WORDS + PHY0_HI_WORDS
        const val ARBITER_SIZE = 0x2c94
        const val BRIDGE_SIZE = 0x3008

        val CTL0_OFFSETS: IntArray = intArrayOf(
            0x000, 0x004, 0x008, 0x00c, 0x030, 0x040, 0x044, 0x050,
            0x058, 0x064, 0x068, 0x084, 0x100, 0x104, 0x108, 0x200,
            0x204, 0x210, 0x214, 0x240, 0x360, 0x364, 0x600, 0x604,
            0x624, 0x690, 0x698, 0xc18, 0xc20, 0xc30, 0xc40,
        )

        private val ARBITER_BLOCK_OFFSETS: IntArray = intArrayOf(
            0x04, 0x20, 0x40, 0x44, 0x48, 0x4c, 0x50,
            0x60, 0x64, 0x68, 0x6c, 0x70, 0x80, 0x84, 0x88, 0x8c, 0x90,
        )

        val ARBITER_OFFSETS: IntArray = buildList {
            add(0x10)
            for (block in 0 until 23) {
                val blockBase = block * 0x200
                for (off in ARBITER_BLOCK_OFFSETS) {
                    add(blockBase + off)
                }
            }
        }.sorted().toIntArray()

        val BRIDGE_OFFSETS: IntArray = intArrayOf(
            0x0000, 0x0004, 0x1000, 0x1004,
            0x2000, 0x2004, 0x3000, 0x3004,
        )

        private val SNAPSHOT_KEYS = setOf(
            "profile",
            "preInitWords",
            "commitPending",
            "commitCount",
            "stage1Flag",
            "initStage",
            "ctl0AckPending",
            "ctl0Words",
            "phy0Words",
            "arbiterWords",
            "bridgeWords",
        )

        private fun exactLong(value: Any?, field: String, min: Long, max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) {
                "DRAM coldstart snapshot field $field requires integer"
            }
            val n = (value as Number).toLong()
            require(n in min..max) { "DRAM coldstart snapshot field $field out of range: $n" }
            return n
        }

        private fun exactWordList(raw: Any?, field: String, expectedSize: Int): LongArray {
            val list = raw as? List<*> ?: throw IllegalArgumentException("DRAM coldstart snapshot field $field requires list")
            require(list.size == expectedSize) {
                "DRAM coldstart snapshot field $field requires $expectedSize entries, got ${list.size}"
            }
            return LongArray(expectedSize) { idx ->
                exactLong(list[idx], "$field[$idx]", 0L, 0xffff_ffffL)
            }
        }
    }
}
