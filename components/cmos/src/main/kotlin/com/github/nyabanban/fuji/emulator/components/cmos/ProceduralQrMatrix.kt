package com.github.nyabanban.fuji.emulator.components.cmos

import kotlin.math.abs

/**
 * Self-contained pure-Kotlin ISO/IEC 18004 QR code matrix encoder (Versions 1–3, ECC Level L, Byte mode).
 *
 * Used by [ProceduralOpticsSceneSource] (`scene://qr-ball`) to synthesize a genuinely scannable QR code
 * on the distant 3D studio wall without external image files or third-party dependencies.
 */
internal object ProceduralQrMatrix {
    private data class VersionSpec(
        val version: Int,
        val size: Int,
        val dataCodewords: Int,
        val eccCodewords: Int,
        val alignmentCenters: IntArray,
    )

    private val VERSIONS = listOf(
        VersionSpec(1, 21, 19, 7, intArrayOf()),
        VersionSpec(2, 25, 34, 10, intArrayOf(6, 18)),
        VersionSpec(3, 29, 55, 15, intArrayOf(6, 22)),
    )

    private object Gf256 {
        val exp = IntArray(512)
        val log = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                exp[i] = x
                log[x] = i
                x = x shl 1
                if ((x and 0x100) != 0) x = x xor 0x11d
            }
            for (i in 255 until 512) exp[i] = exp[i - 255]
        }

        fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

        fun generatorPoly(eccCount: Int): IntArray {
            var g = intArrayOf(1)
            for (i in 0 until eccCount) {
                val next = IntArray(g.size + 1)
                val factor = exp[i]
                for (j in g.indices) {
                    next[j] = next[j] xor g[j]
                    next[j + 1] = next[j + 1] xor mul(g[j], factor)
                }
                g = next
            }
            return g
        }

        fun rsEncode(data: IntArray, eccCount: Int): IntArray {
            val gen = generatorPoly(eccCount)
            val rem = IntArray(data.size + eccCount)
            data.copyInto(rem, 0)
            for (i in data.indices) {
                val coef = rem[i]
                if (coef != 0) {
                    for (j in 1 until gen.size) {
                        rem[i + j] = rem[i + j] xor mul(gen[j], coef)
                    }
                }
            }
            return rem.copyOfRange(data.size, rem.size)
        }
    }

    private fun formatBits(eccLevelBits: Int, maskId: Int): Int {
        val data = ((eccLevelBits and 3) shl 3) or (maskId and 7)
        var rem = data shl 10
        val poly = 0x537
        for (bit in 14 downTo 10) {
            if ((rem and (1 shl bit)) != 0) {
                rem = rem xor (poly shl (bit - 10))
            }
        }
        return ((data shl 10) or rem) xor 0x5412
    }

    private fun maskBit(maskId: Int, row: Int, col: Int): Boolean = when (maskId) {
        0 -> (row + col) % 2 == 0
        1 -> row % 2 == 0
        2 -> col % 3 == 0
        3 -> (row + col) % 3 == 0
        4 -> ((row / 2) + (col / 3)) % 2 == 0
        5 -> ((row * col) % 2) + ((row * col) % 3) == 0
        6 -> (((row * col) % 2) + ((row * col) % 3)) % 2 == 0
        7 -> (((row + col) % 2) + ((row * col) % 3)) % 2 == 0
        else -> false
    }

    private fun buildFunctionMask(spec: VersionSpec): Array<BooleanArray> {
        val n = spec.size
        val fn = Array(n) { BooleanArray(n) }

        fun markRect(r0: Int, c0: Int, h: Int, w: Int) {
            for (r in maxOf(0, r0) until minOf(n, r0 + h)) {
                for (c in maxOf(0, c0) until minOf(n, c0 + w)) {
                    fn[r][c] = true
                }
            }
        }

        markRect(0, 0, 8, 8)
        markRect(0, n - 8, 8, 8)
        markRect(n - 8, 0, 8, 8)
        for (i in 8 until n - 8) {
            fn[6][i] = true
            fn[i][6] = true
        }
        for (r in spec.alignmentCenters) {
            for (c in spec.alignmentCenters) {
                if ((r == 6 && c == 6) || (r == 6 && c == n - 7) || (r == n - 7 && c == 6)) continue
                markRect(r - 2, c - 2, 5, 5)
            }
        }
        fn[n - 8][8] = true
        for (i in 0..8) {
            fn[8][i] = true
            fn[i][8] = true
        }
        for (i in 0..7) fn[8][n - 1 - i] = true
        for (i in 0..6) fn[n - 1 - i][8] = true
        return fn
    }

    private fun drawFunctionModules(spec: VersionSpec, modules: Array<BooleanArray>) {
        val n = spec.size
        fun drawFinder(topR: Int, leftC: Int) {
            for (dr in 0 until 7) {
                for (dc in 0 until 7) {
                    val border = dr == 0 || dr == 6 || dc == 0 || dc == 6
                    val center = dr in 2..4 && dc in 2..4
                    modules[topR + dr][leftC + dc] = border || center
                }
            }
        }
        drawFinder(0, 0)
        drawFinder(0, n - 7)
        drawFinder(n - 7, 0)

        for (i in 8 until n - 8) {
            val dark = i % 2 == 0
            modules[6][i] = dark
            modules[i][6] = dark
        }

        for (r in spec.alignmentCenters) {
            for (c in spec.alignmentCenters) {
                if ((r == 6 && c == 6) || (r == 6 && c == n - 7) || (r == n - 7 && c == 6)) continue
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        modules[r + dr][c + dc] = abs(dr) == 2 || abs(dc) == 2 || (dr == 0 && dc == 0)
                    }
                }
            }
        }
        modules[n - 8][8] = true
    }

    private fun writeFormatInfo(n: Int, modules: Array<BooleanArray>, fmt: Int) {
        val tlCoords = listOf(
            8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8,
            7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8,
        )
        for (bitIdx in 0..14) {
            val bit = ((fmt ushr (14 - bitIdx)) and 1) != 0
            val (r, c) = tlCoords[bitIdx]
            modules[r][c] = bit
        }
        for (i in 0..6) {
            val bit = ((fmt ushr (14 - i)) and 1) != 0
            modules[n - 1 - i][8] = bit
        }
        for (i in 7..14) {
            val bit = ((fmt ushr (14 - i)) and 1) != 0
            modules[8][n - 15 + i] = bit
        }
    }

    private fun scoreMaskPenalty(m: Array<BooleanArray>): Int {
        val n = m.size
        var penalty = 0
        for (r in 0 until n) {
            var runLen = 1
            for (c in 1 until n) {
                if (m[r][c] == m[r][c - 1]) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty++
                } else {
                    runLen = 1
                }
            }
        }
        for (c in 0 until n) {
            var runLen = 1
            for (r in 1 until n) {
                if (m[r][c] == m[r - 1][c]) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty++
                } else {
                    runLen = 1
                }
            }
        }
        var darkCount = 0
        for (r in 0 until n) for (c in 0 until n) if (m[r][c]) darkCount++
        val pct = (darkCount * 100) / (n * n)
        penalty += (abs(pct - 50) / 5) * 10
        return penalty
    }

    /** Encodes UTF-8 [payload] (`<= 53` bytes) into a square QR module matrix (`true` = dark). */
    fun encode(payload: String): Array<BooleanArray> {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val spec = VERSIONS.firstOrNull { bytes.size + 2 <= it.dataCodewords }
            ?: throw IllegalArgumentException("QR payload too long (${bytes.size} B) for QR v1-v3 L")
        val bits = ArrayList<Int>(spec.dataCodewords * 8)
        fun pushBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add((value ushr i) and 1)
        }
        pushBits(0b0100, 4) // Byte mode
        pushBits(bytes.size, 8)
        for (b in bytes) pushBits(b.toInt() and 0xff, 8)
        val capacityBits = spec.dataCodewords * 8
        pushBits(0, minOf(4, capacityBits - bits.size))
        while (bits.size % 8 != 0) bits.add(0)
        var padIndex = 0
        while (bits.size < capacityBits) {
            pushBits(if (padIndex % 2 == 0) 0xEC else 0x11, 8)
            padIndex++
        }
        val dataCodewords = IntArray(spec.dataCodewords) { idx ->
            var v = 0
            for (b in 0..7) v = (v shl 1) or bits[idx * 8 + b]
            v
        }
        val eccCodewords = Gf256.rsEncode(dataCodewords, spec.eccCodewords)
        val allCodewords = dataCodewords + eccCodewords
        val allBits = BooleanArray(allCodewords.size * 8) { i ->
            ((allCodewords[i / 8] ushr (7 - (i % 8))) and 1) != 0
        }

        val n = spec.size
        val fn = buildFunctionMask(spec)
        var bestMatrix: Array<BooleanArray>? = null
        var bestPenalty = Int.MAX_VALUE
        for (maskId in 0..7) {
            val m = Array(n) { BooleanArray(n) }
            drawFunctionModules(spec, m)
            writeFormatInfo(n, m, formatBits(0b01, maskId))
            var bitCursor = 0
            var col = n - 1
            var upward = true
            while (col > 0) {
                if (col == 6) col--
                val rows = if (upward) (n - 1 downTo 0) else (0 until n)
                for (r in rows) {
                    for (dc in 0..1) {
                        val c = col - dc
                        if (!fn[r][c]) {
                            val dataBit = if (bitCursor < allBits.size) allBits[bitCursor++] else false
                            m[r][c] = dataBit xor maskBit(maskId, r, c)
                        }
                    }
                }
                upward = !upward
                col -= 2
            }
            val penalty = scoreMaskPenalty(m)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMatrix = m
            }
        }
        return checkNotNull(bestMatrix)
    }
}
