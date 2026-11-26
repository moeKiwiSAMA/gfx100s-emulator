package com.github.nyabanban.fuji.emulator.desktop.e2e

import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Self-contained pure-Kotlin ISO/IEC 18004 QR code encoder and decoder (Versions 1–3, ECC Level L, Byte mode).
 * Zero external library dependencies; operates directly on [BufferedImage] and ARGB [IntArray] frames.
 */
object QrTestCodec {
    private data class VersionSpec(
        val version: Int,
        val size: Int,
        val totalCodewords: Int,
        val dataCodewords: Int,
        val eccCodewords: Int,
        val alignmentCenters: IntArray,
    )

    private val VERSIONS = listOf(
        VersionSpec(1, 21, 26, 19, 7, intArrayOf()),
        VersionSpec(2, 25, 44, 34, 10, intArrayOf(6, 18)),
        VersionSpec(3, 29, 70, 55, 15, intArrayOf(6, 22)),
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
        fun div(a: Int, b: Int): Int {
            require(b != 0)
            return if (a == 0) 0 else exp[(log[a] - log[b] + 255) % 255]
        }

        fun powAlpha(power: Int): Int = exp[((power % 255) + 255) % 255]

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

        /**
         * Berlekamp-Massey + Chien search + Forney correction over GF(256).
         * Returns corrected codeword array, or null if uncorrectable.
         */
        fun rsDecode(received: IntArray, eccCount: Int): IntArray? {
            val n = received.size
            val syndromes = IntArray(eccCount)
            var anyError = false
            for (j in 0 until eccCount) {
                var sum = 0
                val root = exp[j]
                for (k in 0 until n) {
                    sum = mul(sum, root) xor received[k]
                }
                syndromes[j] = sum
                if (sum != 0) anyError = true
            }
            if (!anyError) return received.copyOf()

            var lambda = intArrayOf(1)
            var prev = intArrayOf(1)
            var l = 0
            var m = 1
            var b = 1
            for (r in 0 until eccCount) {
                var d = syndromes[r]
                for (i in 1..l) {
                    if (i < lambda.size) {
                        d = d xor mul(lambda[i], syndromes[r - i])
                    }
                }
                if (d == 0) {
                    m++
                } else {
                    val temp = lambda.copyOf()
                    val scale = div(d, b)
                    val shifted = IntArray(maxOf(lambda.size, prev.size + m))
                    lambda.copyInto(shifted)
                    for (i in prev.indices) {
                        shifted[i + m] = shifted[i + m] xor mul(scale, prev[i])
                    }
                    lambda = shifted
                    if (2 * l <= r) {
                        l = r + 1 - l
                        prev = temp
                        b = d
                        m = 1
                    } else {
                        m++
                    }
                }
            }

            if (l > eccCount / 2) return null
            // Trim trailing zeros
            var deg = lambda.size - 1
            while (deg > 0 && lambda[deg] == 0) deg--
            if (deg != l || l == 0) return null

            // Chien search: roots of Lambda(x) = sum lambda[i] x^i at x = alpha^{-pos}
            val errorPositions = ArrayList<Int>(l)
            for (pos in 0 until n) {
                val xInv = powAlpha(-pos)
                var eval = 0
                var xPow = 1
                for (i in 0..deg) {
                    eval = eval xor mul(lambda[i], xPow)
                    xPow = mul(xPow, xInv)
                }
                if (eval == 0) errorPositions.add(pos)
            }
            if (errorPositions.size != l) return null

            // Omega(x) = (S(x) * Lambda(x)) mod x^eccCount
            val omega = IntArray(eccCount)
            for (i in 0 until eccCount) {
                var acc = 0
                for (j in 0..minOf(i, deg)) {
                    acc = acc xor mul(syndromes[i - j], lambda[j])
                }
                omega[i] = acc
            }

            val corrected = received.copyOf()
            for (pos in errorPositions) {
                val xInv = powAlpha(-pos)
                var num = 0
                var xPow = 1
                for (i in 0 until eccCount) {
                    num = num xor mul(omega[i], xPow)
                    xPow = mul(xPow, xInv)
                }
                var den = 0
                var x2Pow = 1
                var i = 1
                while (i <= deg) {
                    den = den xor mul(lambda[i], x2Pow)
                    x2Pow = mul(x2Pow, mul(xInv, xInv))
                    i += 2
                }
                if (den == 0) return null
                val magnitude = mul(powAlpha(pos), div(num, den))
                val idx = n - 1 - pos
                if (idx !in corrected.indices) return null
                corrected[idx] = corrected[idx] xor magnitude
            }

            // Re-verify syndromes
            for (j in 0 until eccCount) {
                var sum = 0
                val root = exp[j]
                for (k in 0 until n) sum = mul(sum, root) xor corrected[k]
                if (sum != 0) return null
            }
            return corrected
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

    private fun decodeFormatBits(rawBits: Int): Pair<Int, Int>? {
        var bestDist = Int.MAX_VALUE
        var bestEcc = -1
        var bestMask = -1
        for (ecc in 0..3) {
            for (mask in 0..7) {
                val candidate = formatBits(ecc, mask)
                val dist = Integer.bitCount(rawBits xor candidate)
                if (dist < bestDist) {
                    bestDist = dist
                    bestEcc = ecc
                    bestMask = mask
                }
            }
        }
        return if (bestDist <= 3) bestEcc to bestMask else null
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

        // Finder patterns + separators
        markRect(0, 0, 8, 8)
        markRect(0, n - 8, 8, 8)
        markRect(n - 8, 0, 8, 8)
        // Timing patterns
        for (i in 8 until n - 8) {
            fn[6][i] = true
            fn[i][6] = true
        }
        // Alignment patterns
        for (r in spec.alignmentCenters) {
            for (c in spec.alignmentCenters) {
                if ((r == 6 && c == 6) || (r == 6 && c == n - 7) || (r == n - 7 && c == 6)) continue
                markRect(r - 2, c - 2, 5, 5)
            }
        }
        // Dark module + format info
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
        // Copy 1 (TL)
        val tlCoords = listOf(
            8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8,
            7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8,
        )
        for (bitIdx in 0..14) {
            val bit = ((fmt ushr (14 - bitIdx)) and 1) != 0
            val (r, c) = tlCoords[bitIdx]
            modules[r][c] = bit
        }
        // Copy 2 (BL col 8 + TR row 8)
        for (i in 0..6) {
            val bit = ((fmt ushr (14 - i)) and 1) != 0
            modules[n - 1 - i][8] = bit
        }
        for (i in 7..14) {
            val bit = ((fmt ushr (14 - i)) and 1) != 0
            modules[8][n - 15 + i] = bit
        }
    }

    private fun encodeModuleMatrix(payload: ByteArray): Array<BooleanArray> {
        val spec = VERSIONS.firstOrNull { payload.size + 2 <= it.dataCodewords }
            ?: throw IllegalArgumentException("Payload too long (${payload.size} B) for QR v1-v3 L (max 53 B)")
        val bits = ArrayList<Int>(spec.dataCodewords * 8)
        fun pushBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add((value ushr i) and 1)
        }
        pushBits(0b0100, 4) // Byte mode
        pushBits(payload.size, 8)
        for (b in payload) pushBits(b.toInt() and 0xff, 8)
        val capacityBits = spec.dataCodewords * 8
        val terminator = minOf(4, capacityBits - bits.size)
        pushBits(0, terminator)
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

    fun encodeToImage(
        payload: String,
        modulePixels: Int = 10,
        quietZoneModules: Int = 4,
    ): BufferedImage = encodeToImage(payload.toByteArray(Charsets.UTF_8), modulePixels, quietZoneModules)

    fun encodeToRgbImage(
        payload: String,
        modulePixels: Int = 10,
        quietZoneModules: Int = 4,
    ): BufferedImage {
        val argb = encodeToImage(payload, modulePixels, quietZoneModules)
        val rgb = BufferedImage(argb.width, argb.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        g.drawImage(argb, 0, 0, null)
        g.dispose()
        return rgb
    }

    fun encodeToImage(
        payload: ByteArray,
        modulePixels: Int = 10,
        quietZoneModules: Int = 4,
    ): BufferedImage {
        require(modulePixels >= 2 && quietZoneModules >= 2)
        val matrix = encodeModuleMatrix(payload)
        val totalModules = matrix.size + 2 * quietZoneModules
        val dim = totalModules * modulePixels
        val image = BufferedImage(dim, dim, BufferedImage.TYPE_INT_ARGB)
        val pixels = IntArray(dim * dim) { 0xFFFFFFFF.toInt() }
        for (r in matrix.indices) {
            for (c in matrix.indices) {
                if (matrix[r][c]) {
                    val py0 = (r + quietZoneModules) * modulePixels
                    val px0 = (c + quietZoneModules) * modulePixels
                    for (dy in 0 until modulePixels) {
                        val rowOffset = (py0 + dy) * dim + px0
                        for (dx in 0 until modulePixels) {
                            pixels[rowOffset + dx] = 0xFF000000.toInt()
                        }
                    }
                }
            }
        }
        image.setRGB(0, 0, dim, dim, pixels, 0, dim)
        return image
    }

    fun decode(image: BufferedImage): String? {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return decode(pixels, image.width, image.height)
    }

    fun decode(argb: IntArray, width: Int, height: Int): String? {
        if (width < 21 || height < 21 || argb.size != width * height) return null
        val luma = IntArray(argb.size)
        var minL = 255
        var maxL = 0
        for (i in argb.indices) {
            val px = argb[i]
            val r = (px ushr 16) and 0xff
            val g = (px ushr 8) and 0xff
            val b = px and 0xff
            val y = (r * 77 + g * 150 + b * 29) ushr 8
            luma[i] = y
            if (y < minL) minL = y
            if (y > maxL) maxL = y
        }
        if (maxL - minL < 32) return null

        val threshold = otsuThreshold(luma)
        val dark = BooleanArray(luma.size) { luma[it] < threshold }
        val finders = detectFinderPatterns(dark, width, height)
        if (finders.size < 3) return null

        // Try candidate finder triples ordered by right-triangle quality
        val triples = ArrayList<Pair<Double, Triple<FinderCandidate, FinderCandidate, FinderCandidate>>>()
        for (i in 0 until finders.size) {
            for (j in i + 1 until finders.size) {
                for (k in j + 1 until finders.size) {
                    val scored = orientFinderTriple(finders[i], finders[j], finders[k])
                    if (scored != null) triples.add(scored)
                }
            }
        }
        triples.sortBy { it.first }

        for ((_, triple) in triples.take(12)) {
            val (tl, tr, bl) = triple
            val avgModule = (tl.moduleSize + tr.moduleSize + bl.moduleSize) / 3.0
            val distTop = hypot(tr.x - tl.x, tr.y - tl.y)
            val distLeft = hypot(bl.x - tl.x, bl.y - tl.y)
            val estModules = ((distTop + distLeft) / 2.0) / avgModule + 7.0
            val candidates = VERSIONS.sortedBy { abs(it.size - estModules) }
            for (spec in candidates) {
                if (abs(spec.size - estModules) > 3.5) continue
                val decoded = sampleAndDecode(dark, width, height, spec, tl, tr, bl)
                if (decoded != null) return decoded
            }
        }
        return null
    }

    private fun otsuThreshold(luma: IntArray): Int {
        val hist = IntArray(256)
        for (v in luma) hist[v]++
        val total = luma.size.toDouble()
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * hist[i]
        var sumB = 0.0
        var wB = 0.0
        var maxVar = -1.0
        var bestThresh = 128
        for (t in 0 until 255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > maxVar) {
                maxVar = between
                bestThresh = ((mB + mF) * 0.5).roundToInt()
            }
        }
        return bestThresh
    }

    private data class FinderCandidate(val x: Double, val y: Double, val moduleSize: Double, val count: Int)

    private fun isFinderRatio(runs: IntArray): Boolean {
        var total = 0
        for (r in runs) {
            if (r <= 0) return false
            total += r
        }
        if (total < 7) return false
        val unit = total / 7.0
        val tol = unit * 0.55
        return abs(runs[0] - unit) <= tol &&
            abs(runs[1] - unit) <= tol &&
            abs(runs[2] - 3.0 * unit) <= 3.0 * tol &&
            abs(runs[3] - unit) <= tol &&
            abs(runs[4] - unit) <= tol
    }

    private fun verticalCrossCheck(
        dark: BooleanArray,
        width: Int,
        height: Int,
        cx: Int,
        startY: Int,
        maxRun: Int,
        expectedTotal: Double,
    ): Double? {
        if (cx !in 0 until width || startY !in 0 until height || !dark[startY * width + cx]) return null
        val runs = IntArray(5)
        var y = startY
        while (y >= 0 && dark[y * width + cx]) { runs[2]++; y-- }
        while (y >= 0 && !dark[y * width + cx] && runs[1] <= maxRun) { runs[1]++; y-- }
        while (y >= 0 && dark[y * width + cx] && runs[0] <= maxRun) { runs[0]++; y-- }
        var yDown = startY + 1
        while (yDown < height && dark[yDown * width + cx]) { runs[2]++; yDown++ }
        while (yDown < height && !dark[yDown * width + cx] && runs[3] <= maxRun) { runs[3]++; yDown++ }
        while (yDown < height && dark[yDown * width + cx] && runs[4] <= maxRun) { runs[4]++; yDown++ }
        if (!isFinderRatio(runs)) return null
        val total = runs.sum().toDouble()
        if (abs(total - expectedTotal) > expectedTotal * 0.35) return null
        return yDown - runs[4] - runs[3] - runs[2] / 2.0
    }

    private fun horizontalCrossCheck(
        dark: BooleanArray,
        width: Int,
        height: Int,
        startX: Int,
        cy: Int,
        maxRun: Int,
        expectedTotal: Double,
    ): Double? {
        if (startX !in 0 until width || cy !in 0 until height || !dark[cy * width + startX]) return null
        val runs = IntArray(5)
        val rowOff = cy * width
        var x = startX
        while (x >= 0 && dark[rowOff + x]) { runs[2]++; x-- }
        while (x >= 0 && !dark[rowOff + x] && runs[1] <= maxRun) { runs[1]++; x-- }
        while (x >= 0 && dark[rowOff + x] && runs[0] <= maxRun) { runs[0]++; x-- }
        var xRight = startX + 1
        while (xRight < width && dark[rowOff + xRight]) { runs[2]++; xRight++ }
        while (xRight < width && !dark[rowOff + xRight] && runs[3] <= maxRun) { runs[3]++; xRight++ }
        while (xRight < width && dark[rowOff + xRight] && runs[4] <= maxRun) { runs[4]++; xRight++ }
        if (!isFinderRatio(runs)) return null
        val total = runs.sum().toDouble()
        if (abs(total - expectedTotal) > expectedTotal * 0.35) return null
        return xRight - runs[4] - runs[3] - runs[2] / 2.0
    }

    private fun detectFinderPatterns(dark: BooleanArray, width: Int, height: Int): List<FinderCandidate> {
        val candidates = ArrayList<FinderCandidate>()
        for (y in 0 until height) {
            val rowOff = y * width
            val runs = IntArray(5)
            var state = 0
            for (x in 0 until width) {
                val isDark = dark[rowOff + x]
                if (isDark) {
                    if (state == 1 || state == 3) state++
                    runs[state]++
                } else {
                    if (state == 0) {
                        if (runs[0] > 0) {
                            state = 1
                            runs[1] = 1
                        }
                    } else if (state == 2) {
                        state = 3
                        runs[3] = 1
                    } else if (state == 4) {
                        if (isFinderRatio(runs)) {
                            val total = runs.sum().toDouble()
                            val cx = (x - runs[4] - runs[3] - runs[2] / 2.0).roundToInt()
                            val cy = verticalCrossCheck(dark, width, height, cx, y, runs[2] * 2, total)
                            if (cy != null) {
                                val refinedX = horizontalCrossCheck(dark, width, height, cx, cy.roundToInt(), runs[2] * 2, total)
                                if (refinedX != null) {
                                    val modSize = total / 7.0
                                    val mergedIdx = candidates.indexOfFirst {
                                        abs(it.x - refinedX) <= modSize * 2.5 &&
                                            abs(it.y - cy) <= modSize * 2.5 &&
                                            abs(it.moduleSize - modSize) <= maxOf(1.5, modSize * 0.4)
                                    }
                                    if (mergedIdx >= 0) {
                                        val old = candidates[mergedIdx]
                                        val c = old.count + 1
                                        candidates[mergedIdx] = FinderCandidate(
                                            (old.x * old.count + refinedX) / c,
                                            (old.y * old.count + cy) / c,
                                            (old.moduleSize * old.count + modSize) / c,
                                            c,
                                        )
                                    } else {
                                        candidates.add(FinderCandidate(refinedX, cy, modSize, 1))
                                    }
                                }
                            }
                        }
                        runs[0] = runs[2]
                        runs[1] = runs[3]
                        runs[2] = runs[4]
                        runs[3] = 1
                        runs[4] = 0
                        state = 3
                    } else {
                        runs[state]++
                    }
                }
            }
        }
        return candidates.sortedByDescending { it.count }
    }

    private fun orientFinderTriple(
        a: FinderCandidate,
        b: FinderCandidate,
        c: FinderCandidate,
    ): Pair<Double, Triple<FinderCandidate, FinderCandidate, FinderCandidate>>? {
        val dAb = hypot(a.x - b.x, a.y - b.y)
        val dAc = hypot(a.x - c.x, a.y - c.y)
        val dBc = hypot(b.x - c.x, b.y - c.y)
        val (tl, p1, p2) = when {
            dBc >= dAb && dBc >= dAc -> Triple(a, b, c)
            dAc >= dAb && dAc >= dBc -> Triple(b, a, c)
            else -> Triple(c, a, b)
        }
        val leg1 = hypot(p1.x - tl.x, p1.y - tl.y)
        val leg2 = hypot(p2.x - tl.x, p2.y - tl.y)
        if (leg1 < 10.0 || leg2 < 10.0) return null
        val ratio = abs(leg1 - leg2) / maxOf(leg1, leg2)
        if (ratio > 0.20) return null
        val dot = ((p1.x - tl.x) * (p2.x - tl.x) + (p1.y - tl.y) * (p2.y - tl.y)) / (leg1 * leg2)
        if (abs(dot) > 0.25) return null
        // Cross product (tr - tl) x (bl - tl) > 0 in screen coords (x right, y down)
        val cross = (p1.x - tl.x) * (p2.y - tl.y) - (p1.y - tl.y) * (p2.x - tl.x)
        val (tr, bl) = if (cross > 0) p1 to p2 else p2 to p1
        val score = ratio + abs(dot)
        return score to Triple(tl, tr, bl)
    }

    private fun sampleAndDecode(
        dark: BooleanArray,
        width: Int,
        height: Int,
        spec: VersionSpec,
        tl: FinderCandidate,
        tr: FinderCandidate,
        bl: FinderCandidate,
    ): String? {
        val n = spec.size
        val span = (n - 7).toDouble()
        val ux = (tr.x - tl.x) / span
        val uy = (tr.y - tl.y) / span
        val vx = (bl.x - tl.x) / span
        val vy = (bl.y - tl.y) / span

        fun sampleModule(row: Int, col: Int): Boolean {
            val du = col - 3.0
            val dv = row - 3.0
            val cx = tl.x + du * ux + dv * vx
            val cy = tl.y + du * uy + dv * vy
            var darkVotes = 0
            var totalVotes = 0
            for (sy in -1..1) {
                for (sx in -1..1) {
                    val px = (cx + sx * 0.2 * (ux + vx)).roundToInt()
                    val py = (cy + sy * 0.2 * (uy + vy)).roundToInt()
                    if (px in 0 until width && py in 0 until height) {
                        totalVotes++
                        if (dark[py * width + px]) darkVotes++
                    }
                }
            }
            return totalVotes > 0 && darkVotes * 2 >= totalVotes
        }

        val grid = Array(n) { r -> BooleanArray(n) { c -> sampleModule(r, c) } }

        // Read both copies of format info
        val tlCoords = listOf(
            8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8,
            7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8,
        )
        var fmt1 = 0
        for ((r, c) in tlCoords) fmt1 = (fmt1 shl 1) or (if (grid[r][c]) 1 else 0)
        var fmt2 = 0
        for (i in 0..6) fmt2 = (fmt2 shl 1) or (if (grid[n - 1 - i][8]) 1 else 0)
        for (i in 7..14) fmt2 = (fmt2 shl 1) or (if (grid[8][n - 15 + i]) 1 else 0)

        val fmtDecoded = decodeFormatBits(fmt1) ?: decodeFormatBits(fmt2) ?: return null
        val (eccLevelBits, maskId) = fmtDecoded
        if (eccLevelBits != 0b01) return null // Level L

        val fn = buildFunctionMask(spec)
        val rawBits = ArrayList<Boolean>(spec.totalCodewords * 8)
        var col = n - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            val rows = if (upward) (n - 1 downTo 0) else (0 until n)
            for (r in rows) {
                for (dc in 0..1) {
                    val c = col - dc
                    if (!fn[r][c]) {
                        rawBits.add(grid[r][c] xor maskBit(maskId, r, c))
                    }
                }
            }
            upward = !upward
            col -= 2
        }
        if (rawBits.size < spec.totalCodewords * 8) return null
        val codewords = IntArray(spec.totalCodewords) { idx ->
            var v = 0
            for (b in 0..7) v = (v shl 1) or (if (rawBits[idx * 8 + b]) 1 else 0)
            v
        }
        val corrected = Gf256.rsDecode(codewords, spec.eccCodewords) ?: return null
        val bitStream = BooleanArray(spec.dataCodewords * 8) { idx ->
            ((corrected[idx / 8] ushr (7 - (idx % 8))) and 1) != 0
        }
        var bitPos = 0
        fun readBits(count: Int): Int? {
            if (bitPos + count > bitStream.size) return null
            var v = 0
            repeat(count) { v = (v shl 1) or (if (bitStream[bitPos++]) 1 else 0) }
            return v
        }
        val mode = readBits(4) ?: return null
        if (mode != 0b0100) return null
        val charCount = readBits(8) ?: return null
        if (charCount < 0 || bitPos + charCount * 8 > bitStream.size) return null
        val bytes = ByteArray(charCount) {
            (readBits(8) ?: return null).toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
