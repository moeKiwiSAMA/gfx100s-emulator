package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SensorPort
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Arrays
import java.util.Base64
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Explicit behavioral discovery support for T80 (movdc) live-view ISP register banks:
 * - 0xFFF6D000..0xFFF6D7FC & 0xFFF6D800..0xFFF6DFFC (two 512-word LUT banks, 011B514C/011B52CC & 011B5364/011B54E4)
 * - 0xFFF72000..0xFFF7212C (bank 0, disjoint from gfx100s.fff72028.config-words.candidate's 68 write words)
 * - 0xFFF73000..0xFFF7312C (bank 1, +0x1000 stride from bank 0)
 * - 0xFFF74000..0xFFF7402C (bank 2 / merger output)
 *
 * All intervals added to DiscoveryBus are strictly disjoint from gfx100s.fff72028.config-words.candidate
 * ([0xFFF72028, 0xFFF730BC)). DAT-verified live-view registers inside [0xFFF72028, 0xFFF730BC) that are
 * not declared by gfx100s.fff72028.config-words.candidate are handled via handlesInterior(address)
 * without overlapping intervals or mutating the candidate's retained config-word backing.
 */
internal class ImageLiveViewBanks private constructor(
    private val lut6d: ByteArray,
    private val registerValues: LongArray,
) {
    constructor() : this(ByteArray(LUT_6D_SIZE), LongArray(REGISTER_ADDRESSES.size))

    fun access(address: Long, write: Boolean, value: Long): Triple<Long, Long, Long>? {
        if (address in LUT_6D_BASE until LUT_6D_BASE + LUT_6D_SIZE) {
            if (address and 3L != 0L) return null
            val offset = (address - LUT_6D_BASE).toInt()
            val prior = readLittleWord(lut6d, offset)
            if (write) {
                writeLittleWord(lut6d, offset, value)
                return Triple(value, prior, value)
            }
            return Triple(prior, prior, prior)
        }
        val slot = Arrays.binarySearch(REGISTER_ADDRESSES, address)
        if (slot < 0) return null
        val prior = observableWord(address, slot)
        if (write) {
            registerValues[slot] = value
            val next = observableWord(address, slot)
            return Triple(value, prior, next)
        }
        return Triple(prior, prior, prior)
    }

    private fun observableWord(address: Long, slot: Int): Long {
        val stored = registerValues[slot]
        return when (address) {
            // 011BEAA0(0/1) and 011BEB28 write 0 and poll bit 0 == 1 until reset completes.
            0xfff72000L, 0xfff73000L, 0xfff74000L -> stored or 1L
            // 011BFFE0 clears bit 28 of 20D4/30D4 then polls bit 8 (0x100) == 1 on 20DC/30DC.
            0xfff720dcL -> statusWithIdleBit(stored, 0xfff720d4L)
            0xfff730dcL -> statusWithIdleBit(stored, 0xfff730d4L)
            // 011C0AF8 clears bit 28 of 4024 then polls bit 8 (0x100) == 1 on 4028.
            0xfff74028L -> statusWithIdleBit(stored, 0xfff74024L)
            else -> stored
        }
    }

    private fun statusWithIdleBit(stored: Long, enableAddress: Long): Long {
        val enableSlot = Arrays.binarySearch(REGISTER_ADDRESSES, enableAddress)
        val enabled = (registerValues[enableSlot] and ENABLE_BIT_28) != 0L
        val cleared = stored and IDLE_BIT_8.inv()
        return if (enabled) cleared else cleared or IDLE_BIT_8
    }

    private var rawSensorScratch: ByteArray? = null
    private var yuvFrameScratch: ByteArray? = null

    fun readRegister(address: Long): Long {
        val slot = Arrays.binarySearch(REGISTER_ADDRESSES, address)
        require(slot >= 0) { "Unknown $KEY register: 0x${address.toString(16)}" }
        return registerValues[slot]
    }

    fun pumpLiveViewDma(
        tick: Long,
        sensor: SensorPort?,
        memory: DmaMemory?,
        lens: LensParameters? = null,
    ): Boolean {
        if (sensor == null || memory == null) return false
        val addr = readRegister(0xfff730c4L)
        val strideLong = readRegister(0xfff730d0L)
        val sizeWord = readRegister(0xfff73018L)
        val ctrl = readRegister(0xfff730d4L)
        if (addr == 0L || (ctrl and ENABLE_BIT_28) == 0L) return false
        val width = ((sizeWord and 0xffffL) + 1L).toInt()
        val height = (((sizeWord ushr 16) and 0xffffL) + 1L).toInt()
        if (width !in 2..1024 || width % 2 != 0 || height !in 1..768) return false
        if (strideLong !in (width * 2L)..MAX_STRIDE_BYTES) return false
        val stride = strideLong.toInt()
        val byteCountLong = (height - 1).toLong() * strideLong + width * 2L
        if (byteCountLong !in 1L..MAX_FRAME_BYTES) return false
        val byteCount = byteCountLong.toInt()

        // Validate DMA target mapping BEFORE exposing a frame so unmapped RAM fails safe
        // without prematurely exhausting loop=false sensors or halting the guest VM.
        try {
            memory.check(addr, byteCountLong, true)
        } catch (_: IllegalArgumentException) {
            return false
        }

        val rawCapacity = maxOf(DEFAULT_SENSOR_WIDTH * DEFAULT_SENSOR_HEIGHT * 2, width * height * 2)
        val rawBytes = rawSensorScratch?.takeIf { it.size >= rawCapacity }
            ?: ByteArray(rawCapacity).also { rawSensorScratch = it }
        val rawBuf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val exposedBytes = try {
            sensor.expose(Exposure(tick, NOMINAL_DURATION_NANOS, NOMINAL_ISO, lens ?: DEFAULT_LENS), rawBuf)
        } catch (_: IllegalStateException) {
            // Unconfigured sourceFile ("") or unimplemented sourceKind: leave RAM untouched.
            return false
        }
        if (exposedBytes <= 0) {
            // Finite loop=false exhausted after first exposure: retain last valid frame in RAM.
            return false
        }

        val (srcW, srcH, is16Bit) = when (exposedBytes) {
            DEFAULT_SENSOR_WIDTH * DEFAULT_SENSOR_HEIGHT * 2 -> Triple(DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, true)
            width * height * 2 -> Triple(width, height, true)
            DEFAULT_SENSOR_WIDTH * DEFAULT_SENSOR_HEIGHT -> Triple(DEFAULT_SENSOR_WIDTH, DEFAULT_SENSOR_HEIGHT, false)
            width * height -> Triple(width, height, false)
            else -> throw IllegalArgumentException("Unsupported CMOS expose byte count $exposedBytes for ${width}x${height}")
        }

        val frameBytes = yuvFrameScratch?.takeIf { it.size >= byteCount }
            ?: ByteArray(byteCount).also { yuvFrameScratch = it }

        demosaicBayerToUyvy(rawBytes, srcW, srcH, is16Bit, frameBytes, width, height, stride)

        memory.write(addr, ByteBuffer.wrap(frameBytes, 0, byteCount))
        return true
    }

    fun receipt(): Map<String, Any?> = linkedMapOf(
        "profile" to PROFILE,
        "confidence" to "HYPOTHESIS",
        "lut6dSha256" to sha256Hex(lut6d),
        "nonZeroRegisterCount" to registerValues.count { it != 0L },
    )

    fun snapshot(): Map<String, Any?> {
        val encodedLut = if (lut6d.any { it != 0.toByte() }) Base64.getEncoder().encodeToString(lut6d) else null
        val savedRegisters = linkedMapOf<String, Long>()
        for (i in REGISTER_ADDRESSES.indices) {
            if (registerValues[i] != 0L) {
                savedRegisters["0x%08x".format(REGISTER_ADDRESSES[i])] = registerValues[i]
            }
        }
        return linkedMapOf(
            "profile" to PROFILE,
            "confidence" to "HYPOTHESIS",
            "lut6dBase64" to encodedLut,
            "registers" to savedRegisters,
        )
    }

    companion object {
        const val KEY = "imageLiveViewBanks"
        const val PROFILE = "t80-isp-banks-v1"
        const val FFF72028_REGION = "gfx100s.fff72028.config-words.candidate"

        const val REGION_6D000 = "diagnostic.image-liveview.fff6d000"
        const val REGION_72000 = "diagnostic.image-liveview.fff72000"
        const val REGION_730C0 = "diagnostic.image-liveview.fff730c0"
        const val REGION_74000 = "diagnostic.image-liveview.fff74000"
        const val PRIMARY_REGION = REGION_72000

        const val LUT_6D_BASE = 0xfff6d000L
        const val LUT_6D_SIZE = 0x1000
        const val BANK_72000_BASE = 0xfff72000L
        const val BANK_72000_SIZE = 0x28
        const val BANK_730C0_BASE = 0xfff730c0L
        const val BANK_730C0_SIZE = 0x70
        const val BANK_74000_BASE = 0xfff74000L
        const val BANK_74000_SIZE = 0x30

        private const val ENABLE_BIT_28 = 0x10000000L
        private const val IDLE_BIT_8 = 0x100L
        private const val DEFAULT_SENSOR_WIDTH = 1024
        private const val DEFAULT_SENSOR_HEIGHT = 576
        private const val MAX_STRIDE_BYTES = 65536L
        private const val MAX_FRAME_BYTES = 64L * 1024L * 1024L
        private const val NOMINAL_DURATION_NANOS = 1000L
        private const val NOMINAL_ISO = 100
        private val DEFAULT_LENS = LensParameters("GF63mmF2.8", 63.0, 2.8, 10.0, 0.9, 0.0)

        private val LINEAR_TO_SRGB = IntArray(4096) { idx ->
            val c = idx / 4095.0
            val srgb = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
            (srgb * 255.0).roundToInt().coerceIn(0, 255)
        }

        private fun sample12(raw: ByteArray, srcW: Int, x: Int, y: Int, is16Bit: Boolean): Int {
            val idx = y * srcW + x
            return if (is16Bit) {
                val off = idx * 2
                val v16 = (raw[off].toInt() and 0xff) or ((raw[off + 1].toInt() and 0xff) shl 8)
                v16 ushr 4
            } else {
                val v8 = raw[idx].toInt() and 0xff
                (v8 * 4095) / 255
            }
        }

        internal fun demosaicBayerToUyvy(
            raw: ByteArray,
            srcW: Int,
            srcH: Int,
            is16Bit: Boolean,
            dst: ByteArray,
            dstW: Int,
            dstH: Int,
            stride: Int,
            yyuv422: Boolean = true,
        ) {
            val iY0 = if (yyuv422) 0 else 1
            val iY1 = if (yyuv422) 1 else 3
            val iU = if (yyuv422) 2 else 0
            val iV = if (yyuv422) 3 else 2
            val sameDims = srcW == dstW && srcH == dstH
            val fitW: Int
            val fitH: Int
            val offX: Int
            val offY: Int
            if (sameDims) {
                fitW = dstW
                fitH = dstH
                offX = 0
                offY = 0
            } else {
                val scale = minOf(dstW.toDouble() / srcW.toDouble(), dstH.toDouble() / srcH.toDouble())
                fitW = (srcW * scale).roundToInt().coerceIn(1, dstW)
                fitH = (srcH * scale).roundToInt().coerceIn(1, dstH)
                offX = (dstW - fitW) / 2
                offY = (dstH - fitH) / 2
            }
            val endX = offX + fitW
            val endY = offY + fitH
            val maxX = srcW - 1
            val maxY = srcH - 1
            for (y in 0 until dstH) {
                val rowOff = y * stride
                val inY = y in offY until endY
                if (!inY) {
                    var x = 0
                    while (x < dstW) {
                        val outIdx = rowOff + x * 2
                        dst[outIdx + iU] = 0x80.toByte()
                        dst[outIdx + iY0] = 0
                        dst[outIdx + iV] = 0x80.toByte()
                        dst[outIdx + iY1] = 0
                        x += 2
                    }
                } else {
                    val sy = if (sameDims) y else (((y - offY).toLong() * srcH) / fitH).toInt().coerceIn(0, maxY)
                    val yPrev = (sy - 1).coerceAtLeast(0)
                    val yNext = (sy + 1).coerceAtMost(maxY)
                    val syOdd = (sy and 1) != 0
                    var x = 0
                    while (x < dstW) {
                        val inX0 = x in offX until endX
                        val inX1 = (x + 1) in offX until endX
                        val outIdx = rowOff + x * 2
                        if (!inX0 && !inX1) {
                            dst[outIdx + iU] = 0x80.toByte()
                            dst[outIdx + iY0] = 0
                            dst[outIdx + iV] = 0x80.toByte()
                            dst[outIdx + iY1] = 0
                        } else {
                            var r0 = 0
                            var g0 = 0
                            var b0 = 0
                            var y0 = 0
                            if (inX0) {
                                val sx0 = if (sameDims) x else (((x - offX).toLong() * srcW) / fitW).toInt().coerceIn(0, maxX)
                                val rgb0 = demosaicPixelSrgb(raw, srcW, maxX, sx0, sy, yPrev, yNext, syOdd, is16Bit)
                                r0 = rgb0.first
                                g0 = rgb0.second
                                b0 = rgb0.third
                                y0 = ((77 * r0 + 150 * g0 + 29 * b0 + 128) shr 8).coerceIn(0, 255)
                            }
                            var r1 = 0
                            var g1 = 0
                            var b1 = 0
                            var y1 = 0
                            if (inX1) {
                                val sx1 = if (sameDims) x + 1 else ((((x + 1) - offX).toLong() * srcW) / fitW).toInt().coerceIn(0, maxX)
                                val rgb1 = demosaicPixelSrgb(raw, srcW, maxX, sx1, sy, yPrev, yNext, syOdd, is16Bit)
                                r1 = rgb1.first
                                g1 = rgb1.second
                                b1 = rgb1.third
                                y1 = ((77 * r1 + 150 * g1 + 29 * b1 + 128) shr 8).coerceIn(0, 255)
                            }
                            val avgR: Int
                            val avgG: Int
                            val avgB: Int
                            if (inX0 && inX1) {
                                avgR = (r0 + r1) ushr 1
                                avgG = (g0 + g1) ushr 1
                                avgB = (b0 + b1) ushr 1
                            } else if (inX0) {
                                avgR = r0
                                avgG = g0
                                avgB = b0
                            } else {
                                avgR = r1
                                avgG = g1
                                avgB = b1
                            }
                            val u = (((-43 * avgR - 85 * avgG + 128 * avgB + 128) shr 8) + 128).coerceIn(0, 255)
                            val v = (((128 * avgR - 107 * avgG - 21 * avgB + 128) shr 8) + 128).coerceIn(0, 255)
                            dst[outIdx + iU] = u.toByte()
                            dst[outIdx + iY0] = y0.toByte()
                            dst[outIdx + iV] = v.toByte()
                            dst[outIdx + iY1] = y1.toByte()
                        }
                        x += 2
                    }
                }
                if (y < dstH - 1 && stride > dstW * 2) {
                    Arrays.fill(dst, rowOff + dstW * 2, rowOff + stride, 0.toByte())
                }
            }
        }

        private fun demosaicPixelSrgb(
            raw: ByteArray,
            srcW: Int,
            maxX: Int,
            sx: Int,
            sy: Int,
            yPrev: Int,
            yNext: Int,
            syOdd: Boolean,
            is16Bit: Boolean,
        ): Triple<Int, Int, Int> {
            val xPrev = (sx - 1).coerceAtLeast(0)
            val xNext = (sx + 1).coerceAtMost(maxX)
            val sxOdd = (sx and 1) != 0
            val c = sample12(raw, srcW, sx, sy, is16Bit)
            val rLin: Int
            val gLin: Int
            val bLin: Int
            if (!syOdd && !sxOdd) {
                // R site
                rLin = c
                gLin = (sample12(raw, srcW, xPrev, sy, is16Bit) +
                    sample12(raw, srcW, xNext, sy, is16Bit) +
                    sample12(raw, srcW, sx, yPrev, is16Bit) +
                    sample12(raw, srcW, sx, yNext, is16Bit) + 2) ushr 2
                bLin = (sample12(raw, srcW, xPrev, yPrev, is16Bit) +
                    sample12(raw, srcW, xNext, yPrev, is16Bit) +
                    sample12(raw, srcW, xPrev, yNext, is16Bit) +
                    sample12(raw, srcW, xNext, yNext, is16Bit) + 2) ushr 2
            } else if (!syOdd && sxOdd) {
                // Gr site
                rLin = (sample12(raw, srcW, xPrev, sy, is16Bit) + sample12(raw, srcW, xNext, sy, is16Bit) + 1) ushr 1
                gLin = c
                bLin = (sample12(raw, srcW, sx, yPrev, is16Bit) + sample12(raw, srcW, sx, yNext, is16Bit) + 1) ushr 1
            } else if (syOdd && !sxOdd) {
                // Gb site
                rLin = (sample12(raw, srcW, sx, yPrev, is16Bit) + sample12(raw, srcW, sx, yNext, is16Bit) + 1) ushr 1
                gLin = c
                bLin = (sample12(raw, srcW, xPrev, sy, is16Bit) + sample12(raw, srcW, xNext, sy, is16Bit) + 1) ushr 1
            } else {
                // B site
                rLin = (sample12(raw, srcW, xPrev, yPrev, is16Bit) +
                    sample12(raw, srcW, xNext, yPrev, is16Bit) +
                    sample12(raw, srcW, xPrev, yNext, is16Bit) +
                    sample12(raw, srcW, xNext, yNext, is16Bit) + 2) ushr 2
                gLin = (sample12(raw, srcW, xPrev, sy, is16Bit) +
                    sample12(raw, srcW, xNext, sy, is16Bit) +
                    sample12(raw, srcW, sx, yPrev, is16Bit) +
                    sample12(raw, srcW, sx, yNext, is16Bit) + 2) ushr 2
                bLin = c
            }
            return Triple(
                LINEAR_TO_SRGB[rLin.coerceIn(0, 4095)],
                LINEAR_TO_SRGB[gLin.coerceIn(0, 4095)],
                LINEAR_TO_SRGB[bLin.coerceIn(0, 4095)],
            )
        }

        val REGION_IDS = setOf(REGION_6D000, REGION_72000, REGION_730C0, REGION_74000)

        private val PROHIBITED_730C0 = listOf(
            AddressInterval(0xfff730e8L, 0xfff73100L),
            AddressInterval(0xfff7310cL, 0xfff73110L),
        )

        private val INTERIOR_ADDRESSES: LongArray = buildList {
            // Bank 0 DAT-verified words inside [0xFFF72028, 0xFFF730BC) outside fff72028 wordRuns
            add(0xfff7202cL)
            add(0xfff72030L)
            for (offset in 0xc0..0xe4 step 4) add(0xfff72000L + offset)
            for (offset in 0x100..0x108 step 4) add(0xfff72000L + offset)
            for (offset in 0x110..0x12c step 4) add(0xfff72000L + offset)
            // Bank 1 DAT-verified words inside [0xFFF72028, 0xFFF730BC) outside fff72028 wordRuns
            for (offset in 0x00..0x24 step 4) add(0xfff73000L + offset)
            add(0xfff7302cL)
            add(0xfff73030L)
        }.sorted().toLongArray()

        private val REGISTER_ADDRESSES: LongArray = buildList {
            for (offset in 0x00..0x24 step 4) add(0xfff72000L + offset)
            for (address in INTERIOR_ADDRESSES) add(address)
            for (offset in 0xc0..0xe4 step 4) add(0xfff73000L + offset)
            for (offset in 0x100..0x108 step 4) add(0xfff73000L + offset)
            for (offset in 0x110..0x12c step 4) add(0xfff73000L + offset)
            for (offset in 0x00..0x2c step 4) add(0xfff74000L + offset)
        }.sorted().toLongArray()

        private val REGISTER_BY_HEX: Map<String, Int> = REGISTER_ADDRESSES.withIndex()
            .associate { (index, address) -> "0x%08x".format(address) to index }

        val INTERIOR_EVIDENCE = listOf(
            "DAT section4 011BEAA0..011C0DC4 & section5 0236691C..02366B90/0236D834: bank 0/1 interior registers inside [0xFFF72028, 0xFFF730BC)",
            "HYPOTHESIS $PROFILE: 0xFFF73000 read asserts bit 0 ready; 0xFFF720DC read asserts bit 8 idle when 0xFFF720D4 bit 28 is clear; all other DAT offsets retain 32-bit words",
        )

        fun handlesInterior(address: Long): Boolean = Arrays.binarySearch(INTERIOR_ADDRESSES, address) >= 0

        fun regions(selection: Any): List<DiscoveryRegion> {
            require(selection == PROFILE) { "Unknown $KEY selection: $selection" }
            return listOf(
                DiscoveryRegion(
                    id = REGION_6D000,
                    deviceId = "section4-011b514c",
                    base = LUT_6D_BASE,
                    length = LUT_6D_SIZE,
                    strategy = DiscoveryStrategy.IMAGE_LIVEVIEW_BANKS,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    evidence = listOf(
                        "DAT section4 011B514C/011B52CC (0xFFF6D000..0xFFF6D7FC) & 011B5364/011B54E4 (0xFFF6D800..0xFFF6DFFC), 512 words each",
                        "HYPOTHESIS $PROFILE: two contiguous 512-word retained 32-bit LUT banks",
                    ),
                ),
                DiscoveryRegion(
                    id = REGION_72000,
                    deviceId = "section4-011beaa0",
                    base = BANK_72000_BASE,
                    length = BANK_72000_SIZE,
                    strategy = DiscoveryStrategy.IMAGE_LIVEVIEW_BANKS,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    evidence = listOf(
                        "DAT section4 011BEAA0(0) resets 0xFFF72000 (write 0, poll bit 0 == 1); 011BEE30..011BF35C configure 0xFFF72004..0xFFF72024",
                        "HYPOTHESIS $PROFILE: 0xFFF72000 read asserts bit 0 ready; 0xFFF72004..0xFFF72024 retain 32-bit words; abuts fff72028 without overlap",
                    ),
                ),
                DiscoveryRegion(
                    id = REGION_730C0,
                    deviceId = "section4-011bed54",
                    base = BANK_730C0_BASE,
                    length = BANK_730C0_SIZE,
                    strategy = DiscoveryStrategy.IMAGE_LIVEVIEW_BANKS,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    prohibited = PROHIBITED_730C0,
                    evidence = listOf(
                        "DAT section4 011BF95C/011BEAD8/011BED54/011BFFE0/011C00D0 & section5 02366AB8..02366B30: bank 1 tail 0xFFF730C0..0xFFF7312C with holes 0xE8..0xFC and 0x10C",
                        "HYPOTHESIS $PROFILE: 0xFFF730DC read asserts bit 8 idle when 0xFFF730D4 bit 28 is clear; other DAT offsets retain 32-bit words",
                    ),
                ),
                DiscoveryRegion(
                    id = REGION_74000,
                    deviceId = "section4-011beb28",
                    base = BANK_74000_BASE,
                    length = BANK_74000_SIZE,
                    strategy = DiscoveryStrategy.IMAGE_LIVEVIEW_BANKS,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    evidence = listOf(
                        "DAT section4 011BEB28 (0xFFF74000 write 0, poll bit 0 == 1), 011C0288/011C0AF8 (0xFFF74028 RMW/poll bit 8), 011C0314..011C0978 & section5 02366B34..02366B90: 0xFFF74000..0xFFF7402C",
                        "HYPOTHESIS $PROFILE: 0xFFF74000 read asserts bit 0 ready; 0xFFF74028 read asserts bit 8 idle when 0xFFF74024 bit 28 is clear; other DAT offsets retain 32-bit words",
                    ),
                ),
            )
        }

        fun validRegion(region: DiscoveryRegion): Boolean {
            if (region.widths != setOf(4) || region.alignment != 4 || !region.littleEndian ||
                region.initial != null || region.fixedValue != null || region.samples != null ||
                region.retainedDiagnostic != null || region.selectionValues != null) {
                return false
            }
            return when (region.id) {
                REGION_6D000 -> region.base == LUT_6D_BASE && region.length == LUT_6D_SIZE && region.prohibited.isEmpty()
                REGION_72000 -> region.base == BANK_72000_BASE && region.length == BANK_72000_SIZE && region.prohibited.isEmpty()
                REGION_730C0 -> region.base == BANK_730C0_BASE && region.length == BANK_730C0_SIZE && region.prohibited == PROHIBITED_730C0
                REGION_74000 -> region.base == BANK_74000_BASE && region.length == BANK_74000_SIZE && region.prohibited.isEmpty()
                else -> false
            }
        }

        fun restore(raw: Any?): ImageLiveViewBanks {
            require(raw is Map<*, *>) { "Missing $KEY snapshot state" }
            require(raw.keys == setOf("profile", "confidence", "lut6dBase64", "registers")) {
                "Unexpected $KEY snapshot keys"
            }
            require(raw["profile"] == PROFILE && raw["confidence"] == "HYPOTHESIS") {
                "Invalid $KEY snapshot identity"
            }
            val lutEncoded = raw["lut6dBase64"]
            val restoredLut = if (lutEncoded == null) {
                ByteArray(LUT_6D_SIZE)
            } else {
                require(lutEncoded is String && lutEncoded.isNotEmpty()) { "Invalid lut6dBase64" }
                val decoded = Base64.getDecoder().decode(lutEncoded)
                require(decoded.size == LUT_6D_SIZE) { "Restored lut6d size differs" }
                decoded
            }
            val rawRegisters = raw["registers"] as? Map<*, *> ?: error("Missing $KEY registers map")
            val restoredRegisters = LongArray(REGISTER_ADDRESSES.size)
            for ((key, value) in rawRegisters) {
                val slot = REGISTER_BY_HEX[key as? String] ?: error("Unknown $KEY register key: $key")
                require(value is Int || value is Long) { "Register value for $key must be an integer" }
                val word = (value as Number).toLong()
                require(word in 0L..0xffffffffL) { "Register value for $key outside u32" }
                restoredRegisters[slot] = word
            }
            return ImageLiveViewBanks(restoredLut, restoredRegisters)
        }

        private fun readLittleWord(bytes: ByteArray, offset: Int): Long =
            ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

        private fun writeLittleWord(bytes: ByteArray, offset: Int, value: Long) {
            ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
