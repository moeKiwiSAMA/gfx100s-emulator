package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.WritableImageState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Extends [FlashPartitionQuartetContracts] (`494..497`) with the two contiguous DAT-verified
 * `flags=4` BKUP ping-pong pairs (`part-16..19`, blocks `490..493`, `0x03D40000..0x03DC0000`):
 * - Slot 1 (`0x0129f684`): `part-16` (`490`, `0x03D40000`) / `part-17` (`491`, `0x03D60000`), `4096 B` records
 * - Slot 0 (`0x0129f838`): `part-18` (`492`, `0x03D80000`) / `part-19` (`493`, `0x03DA0000`), `256 B` shutdown records
 *
 * Together with Slot 2 (`494..495`) and Slot 3 (`496..497`), this forms the full contiguous
 * 1 MiB BKUP domain `[0x03D40000, 0x03E40000)` managed by `section4 @ 0x0129f9dc` / `0x012a0d90`.
 */
object FlashPartitionOctetContracts {
    const val PROFILE = "dat-partition-octet-experimental-v3"
    private const val APP = 0x1021000L
    private data class Span(val start: Long, val size: Int, val sha256: String)
    private val spans = listOf(
        Span(0x129f684, 104, "5b4c86aecbc395fbbe631146e1980c41344acba3ceb580328c1329694e43635d"),
        Span(0x129f838, 104, "55dd48008df766348417df3bca0bf7bbedcd5614394d47ae3e370381b673e0e1"),
        Span(0x17477a8, 48, "320bf8f33ec327cf5292bb1ddb6c17f598788243560bd17cf85c761cedd61b49"),
    )
    val wordAddresses: List<Long> get() = FlashPartitionQuartetContracts.wordAddresses +
        spans.flatMap { (it.start until it.start + it.size step 4).toList() }

    fun observe(app: ByteArray): Map<String, Any> {
        val parent = FlashPartitionQuartetContracts.observe(app)
        fun bytes(pc: Long, n: Int): ByteArray {
            require(pc >= APP && pc % 4 == 0L && n > 0 && pc - APP + n <= app.size) {
                "partition-octet DAT span outside initialized app"
            }
            return app.copyOfRange((pc - APP).toInt(), (pc - APP + n).toInt())
        }
        fun word(pc: Long) = ByteBuffer.wrap(bytes(pc, 4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        fun half(w: Long) = (w and 4095) or (w ushr 4 and 0xf000)
        fun pointer(pc: Long) = half(word(pc)) or (half(word(pc + 4)) shl 16)
        fun immediate(pc: Long): Long {
            val w = word(pc)
            return Integer.rotateRight((w and 255).toInt(), ((w ushr 8 and 15) * 2).toInt()).toLong() and 0xffffffffL
        }
        fun branch(pc: Long) = pc + 8 + ((word(pc).toInt() shl 8) shr 6)

        for (s in spans) {
            require(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(s.start, s.size))) == s.sha256) {
                "partition-octet DAT span differs at " + s.start.toString(16)
            }
        }
        val table = parent.getValue("table") as Long
        val shift = parent.getValue("logicalBlockShift") as Long
        val aperture = parent.getValue("apertureBase") as Long
        val flag = parent.getValue("initializedFlag") as Long

        fun decodePair(selectorEntry: Long, expectedBlocks: List<Long>): List<Map<String, Any>> {
            require(
                pointer(selectorEntry) == flag &&
                    pointer(selectorEntry + 0x2c) == table &&
                    pointer(selectorEntry + 0x4c) == table &&
                    immediate(selectorEntry + 0x14) == 0L &&
                    immediate(selectorEntry + 0x1c) == 1L &&
                    branch(selectorEntry + 0x44) == 0x129df0cL,
            ) { "partition-octet selector differs at 0x${selectorEntry.toString(16)}" }
            val rows = listOf(
                table + (word(selectorEntry + 0x38) and 4095),
                table + (word(selectorEntry + 0x58) and 4095),
            )
            require(
                table + (word(selectorEntry + 0x34) and 4095) == rows[0] + 4 &&
                    table + (word(selectorEntry + 0x54) and 4095) == rows[1] + 4 &&
                    rows[1] - rows[0] == 12L,
            ) { "partition-octet row offsets differ at 0x${selectorEntry.toString(16)}" }
            val decoded = rows.mapIndexed { mode, row ->
                val count = word(row)
                val first = word(row + 4)
                val offset = first shl shift.toInt()
                val length = count shl shift.toInt()
                mapOf(
                    "selectorEntry" to selectorEntry,
                    "mode" to mode,
                    "rowAddress" to row,
                    "countBlocks" to count,
                    "firstBlock" to first,
                    "thirdWordFlags" to word(row + 8),
                    "offset" to offset,
                    "length" to length,
                    "rawAddress" to (aperture + offset),
                )
            }
            require(
                decoded.map { it["firstBlock"] } == expectedBlocks &&
                    decoded.all { it["countBlocks"] == 1L && it["thirdWordFlags"] == 4L },
            ) { "partition-octet decoded blocks differ at 0x${selectorEntry.toString(16)}" }
            return decoded
        }

        val slot1 = decodePair(0x129f684L, listOf(490L, 491L))
        val slot0 = decodePair(0x129f838L, listOf(492L, 493L))
        val upper = (parent.getValue("partitions") as List<*>).map { it as Map<*, *> }
        val start = slot1.first().getValue("offset") as Long
        val end = parent.getValue("endExclusive") as Long
        require(
            start == 0x3d40000L &&
                (slot1.last().getValue("offset") as Long) + (slot1.last().getValue("length") as Long) == slot0.first().getValue("offset") &&
                (slot0.last().getValue("offset") as Long) + (slot0.last().getValue("length") as Long) == parent.getValue("writableOffset") &&
                end == 0x3e40000L &&
                end - start == 0x100000L,
        ) { "partition-octet contiguous 490..497 span mismatch" }

        return parent + mapOf(
            "profile" to PROFILE,
            "criticalWords" to wordAddresses.size,
            "slot1SelectorEntry" to 0x129f684L,
            "slot0SelectorEntry" to 0x129f838L,
            "partitions" to (slot1 + slot0 + upper),
            "writableOffset" to start,
            "writableLength" to (end - start),
            "endExclusive" to end,
            "basis" to "Verified contiguous DAT BKUP octet blocks 490..497 (part-16..23): Slot 1 (0x0129f684, blks 490..491, flags=4), Slot 0 shutdown (0x0129f838, blks 492..493, flags=4), Slot 2 BlockA (0x0129fdc8, blks 494..495, flags=3), Slot 3 BlockB (0x012a001c, blks 496..497, flags=3).",
        )
    }

    fun verify(image: FirmwareImage, copy: WritableImageState): Map<String, Any> {
        require(
            image.metadata.id == "gfx100s-213" &&
                image.metadata.sha256 == "ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12",
        ) { "partition-octet requires qualified selected firmware" }
        val plan = observe(image.decode("section4"))
        val start = plan.getValue("writableOffset") as Long
        val size = plan.getValue("writableLength") as Long
        require(
            copy.writableOffset == start &&
                copy.writableLength == size &&
                copy.offset >= 0 &&
                copy.length in 1..0x8000000L &&
                copy.offset <= 0x8000000L - copy.length &&
                copy.offset <= start &&
                Math.addExact(copy.offset, copy.length) >= start + size &&
                !copy.pending &&
                !copy.closed &&
                copy.failure == null &&
                copy.generation >= 0,
        ) { "selected partition-octet writable range/lifecycle differs from DAT" }
        return plan + mapOf(
            "selectedWritableInput" to copy,
            "scope" to "Explicit private COW permission for contiguous DAT BKUP blocks 490..497 (part-16..23, 0x03D40000..0x03E40000); all other flash holes remain read-only/refused.",
        )
    }
}
