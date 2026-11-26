package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.LcdcLayerOutputState
import com.github.nyabanban.fuji.emulator.api.PanelBacklightPort
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal class LcdcFirstScanout(
    private val context: ComponentContext,
    val frameLimit: Int,
    val profile: String,
    val displayPort: String = "display.main",
    val continuous: Boolean = false,
    val strictActiveOutputGate: Boolean = false,
) {
    companion object {
        const val SETTING = "firstScanout"
        const val EVF_SETTING = "evfScanout"
        const val STRICT_ACTIVE_GATE_SETTING = "strictActiveOutputGate"
        const val PROFILE = "lcdc1-osd0-main-640x480-rgb565le-v1"
        const val RGBA = "lcdc1-osd0-1024x768-rgba-nearest-v1"
        const val PIC_YUV_OSD0 = "lcdc1-pic-yuv-osd0-nearest-v1"
        const val LIMIT_SETTING = "scanoutFrameLimit"
        const val EVF_LIMIT_SETTING = "evfScanoutFrameLimit"
        const val MAX_FRAMES = 256
        /** `LCDC1` `+0x008` (`LCDC1_LAYER_EN`) bit masks verified in GFX100S 2.13 `section4.bin`. */
        const val LAYER_EN_PIC1 = 0x00000010L
        const val LAYER_EN_PIC0 = 0x00000100L
        const val LAYER_EN_ACTIVE_GATE = 0x00001000L
        const val LAYER_EN_OSD0 = 0x00010000L
        const val BLANK_BLACK_ARGB = 0xff000000.toInt()
        const val BLANK_GRAY_ARGB = 0xff808080.toInt()
        private const val SOURCE_WIDTH = 1024
        private const val SOURCE_HEIGHT = 768
        private const val STRIDE = SOURCE_WIDTH * 4
        private const val EXTENT = (SOURCE_HEIGHT shl 16) or SOURCE_WIDTH
        /**
         * OSD0 chroma-key control word (S4:0x0126BB44: YCbCr transparent-black chroma-key enable).
         * Used on every live Shooting HUD, Menu, and PLAY frame (0x008 = 0x11000/0x11010), as well as
         * pre-display init (0x008 = 0x10000 without bit 12) and OOBE/START power-off shutdown
         * transitioning from 0x1fc7f088 while PIC1 is disabled.
         */
        const val OSD0_CHROMA_KEY_CONTROL = 0x20080001L
        const val OSD0_OOBE_OPAQUE_CONTROL = 0x1fc7f088L
        const val OSD0_FWUP_WHITE_BGCOLOR = 0x200803fcL
        private val geometryOffsets = intArrayOf(0x50, 0x54, 0x58, 0x5c)
        private val INT_LE_HANDLE: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)

        internal fun classifyOsd0Mode(reg030: Long): String = when (reg030) {
            0L -> "off"
            OSD0_CHROMA_KEY_CONTROL -> "transparent-chroma-key"
            OSD0_OOBE_OPAQUE_CONTROL -> "opaque"
            else -> "custom"
        }

        /**
         * Decodes hardware `LCDC1 + 0x030` 10-bit YCbCr `BGCOLOR` backdrop (verified in GFX100S 2.13
         * `section4.bin` `0x0126ba90..0x0126bb38`):
         * - `[9:0] = Y` (`Y8 << 2`, or `1` when `0`)
         * - `[19:10] = Cb` (`(Cb8 << 2) + 512`)
         * - `[29:20] = Cr` (`(Cr8 << 2) + 512`)
         * Examples: `0x20080001` -> `#000000`, `0x1FC7F088` -> `#212320`, `0x200803FC` -> `#FFFFFF`.
         */
        internal fun decodeBgColor(reg030: Long): Int {
            if (reg030 == 0L || reg030 == OSD0_CHROMA_KEY_CONTROL) return BLANK_BLACK_ARGB
            val y = ((reg030 and 0x3ffL) ushr 2).toInt()
            val d = ((((reg030 ushr 10) and 0x3ffL).toInt() - 512) shr 2)
            val e = ((((reg030 ushr 20) and 0x3ffL).toInt() - 512) shr 2)
            val r = ((256 * y + 359 * e + 128) shr 8).coerceIn(0, 255)
            val g = ((256 * y - 88 * d - 183 * e + 128) shr 8).coerceIn(0, 255)
            val b = ((256 * y + 454 * d + 128) shr 8).coerceIn(0, 255)
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }

        internal fun signExtend13(raw: Long): Int = ((raw.toInt() and 0x1FFF) shl 19) shr 19

        internal fun yuv422UyvyToRgb(bytes: ByteArray, pairOffset: Int, oddPixel: Boolean): Int {
            val u = bytes[pairOffset].toInt() and 0xff
            val y0 = bytes[pairOffset + 1].toInt() and 0xff
            val v = bytes[pairOffset + 2].toInt() and 0xff
            val y1 = bytes[pairOffset + 3].toInt() and 0xff
            // Unwritten zero-filled UYVY DRAM (0x00000000) and the DAT-proven GFX100S 2.13 Milbeaut
            // Playback pillarbox/letterbox fill words (section5 @ 0x021cdea0..0x021cdeb0 -> section6
            // @ 0x0436d200: 0x80800000 LE = [00 00 80 80] = Y0Y1CbCr black and 0x00008080 LE =
            // [80 80 00 00] = CbCrY0Y1 black) are out-of-gamut in BT.601 UYVY; map all three margin
            // fill sentinels to pure black so non-4:3 Playback margins render clean black.
            if ((u or y0 or v or y1) == 0 ||
                (u == 0 && y0 == 0 && v == 0x80 && y1 == 0x80) ||
                (u == 0x80 && y0 == 0x80 && v == 0 && y1 == 0)
            ) {
                return 0
            }
            val y = if (oddPixel) y1 else y0
            val d = u - 128
            val e = v - 128
            val r = ((256 * y + 359 * e + 128) shr 8).coerceIn(0, 255)
            val g = ((256 * y - 88 * d - 183 * e + 128) shr 8).coerceIn(0, 255)
            val b = ((256 * y + 454 * d + 128) shr 8).coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or b
        }

        internal fun resampleUyvyQ12(
            src: ByteArray,
            srcW: Int,
            srcH: Int,
            srcStrideBytes: Int,
            scaleX: Int,
            scaleY: Int,
            dst: ByteArray,
            dstW: Int,
            dstH: Int,
        ) {
            val maxSrcX = srcW - 1
            val maxSrcY = srcH - 1
            val dstStride = dstW * 2
            for (dy in 0 until dstH) {
                val sy = ((dy.toLong() * scaleY) ushr 12).toInt().coerceIn(0, maxSrcY)
                val srcRow = sy * srcStrideBytes
                val dstRow = dy * dstStride
                var dx = 0
                while (dx < dstW) {
                    val sx0 = ((dx.toLong() * scaleX) ushr 12).toInt().coerceIn(0, maxSrcX)
                    val sx1 = (((dx + 1).toLong() * scaleX) ushr 12).toInt().coerceIn(0, maxSrcX)
                    val p0 = srcRow + (sx0 and 1.inv()) * 2
                    val p1 = srcRow + (sx1 and 1.inv()) * 2

                    var u0 = src[p0].toInt() and 0xff
                    var y0a = src[p0 + 1].toInt() and 0xff
                    var v0 = src[p0 + 2].toInt() and 0xff
                    var y0b = src[p0 + 3].toInt() and 0xff
                    if ((u0 or y0a or v0 or y0b) == 0 ||
                        (u0 == 0 && y0a == 0 && v0 == 0x80 && y0b == 0x80) ||
                        (u0 == 0x80 && y0a == 0x80 && v0 == 0 && y0b == 0)
                    ) {
                        u0 = 0x80; y0a = 0; v0 = 0x80; y0b = 0
                    }
                    val outY0 = if ((sx0 and 1) != 0) y0b else y0a

                    var u1 = src[p1].toInt() and 0xff
                    var y1a = src[p1 + 1].toInt() and 0xff
                    var v1 = src[p1 + 2].toInt() and 0xff
                    var y1b = src[p1 + 3].toInt() and 0xff
                    if ((u1 or y1a or v1 or y1b) == 0 ||
                        (u1 == 0 && y1a == 0 && v1 == 0x80 && y1b == 0x80) ||
                        (u1 == 0x80 && y1a == 0x80 && v1 == 0 && y1b == 0)
                    ) {
                        u1 = 0x80; y1a = 0; v1 = 0x80; y1b = 0
                    }
                    val outY1 = if ((sx1 and 1) != 0) y1b else y1a

                    val outP = dstRow + dx * 2
                    dst[outP] = ((u0 + u1) ushr 1).toByte()
                    dst[outP + 1] = outY0.toByte()
                    dst[outP + 2] = ((v0 + v1) ushr 1).toByte()
                    dst[outP + 3] = outY1.toByte()
                    dx += 2
                }
            }
        }
    }

    data class Cursor(val upper: Long? = null, val publishedAddress: Long? = null, val publishedFrames: Int = 0,
                      val pendingSlot: Boolean = false)
    init {
        require(displayPort.isNotBlank()) { "LCD scanout requires displayPort" }
    }
    var cursor = Cursor()
        private set
    var outputActive: Boolean = false
        private set
    var blankReason: String? = null
        private set
    var activeGateBlankCount: Long = 0L
        private set
    private val byteCount = if (profile == PROFILE) 640 * 480 * 2 else STRIDE * SOURCE_HEIGHT
    private fun validAddress(address: Long) = address in 1..(0x1_0000_0000L - byteCount) &&
        address % (if (profile == PROFILE) 2 else 4) == 0L
    private fun validCompositeAddress(address: Long) = address in 1..(0x1_0000_0000L - 4L) && address % 4L == 0L
    private val raw by lazy { ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN) }
    private var targetWidth = 640
    private var targetHeight = 480
    private var pixels = IntArray(0)
    private var sourceRows = IntArray(0)
    private var sourceCols = IntArray(0)
    private var mappedSourceWidth = SOURCE_WIDTH
    private var mappedSourceHeight = SOURCE_HEIGHT
    private var lastCanvasWidth = SOURCE_WIDTH
    private var lastCanvasHeight = SOURCE_HEIGHT
    private var osdScratch = ByteArray(0)
    private var osdFlipScratch = ByteArray(0)
    private var pic0Scratch = ByteArray(0)
    private var pic1Scratch = ByteArray(0)
    private var pic1ScaledScratch = ByteArray(0)
    private var sawOobeOpaqueOsd = false
    private var evfPeer: LcdcFirstScanout? = null
    private var cachedBacklightPort: PanelBacklightPort? = null
    private var backlightPortLookedUp = false
    private val compositeCfgScratch = IntArray(20)
    private val colorMatrixScratch = IntArray(9)
    private var premulFallbackScratch = IntArray(0)
    private val picturePostProcessor = LcdcPicturePostProcessor()
    private var directArgbPreSink: ((width: Int, height: Int, writer: (MemorySegment) -> Unit) -> Boolean)? = null
    private var mirrorDirectSinkToDisplayPort: Boolean = true

    internal fun attachEvfPeer(peer: LcdcFirstScanout?) {
        evfPeer = peer
    }

    /**
     * Attaches an optional off-heap direct `INT_ARGB_PRE` frame sink (e.g. a triple-buffered
     * `AsyncDisplayBridge` or JavaFX `PixelBuffer` direct `MemorySegment` target). When present,
     * [publishComposite] renders directly into the sink's off-heap segment in a single SIMD pass.
     */
    internal fun attachDirectArgbPreSink(
        sink: ((width: Int, height: Int, writer: (MemorySegment) -> Unit) -> Boolean)?,
        mirrorToDisplayPort: Boolean = true,
    ) {
        directArgbPreSink = sink
        mirrorDirectSinkToDisplayPort = mirrorToDisplayPort
    }

    internal fun blankToBlack(reason: String) {
        if (!outputActive && blankReason == reason) return
        val port = display()
        pixels.fill(BLANK_BLACK_ARGB)
        port.presentArgb(pixels, targetWidth, targetHeight)
        outputActive = false
        blankReason = reason
        sawOobeOpaqueOsd = false
        cursor = Cursor(publishedAddress = cursor.publishedAddress, publishedFrames = cursor.publishedFrames + 1)
    }

    private fun display(): DisplayPort {
        val port = requireNotNull(context.ports.find(displayPort, DisplayPort::class.java)) {
            "LCD scanout requires $displayPort"
        }
        val supported = if (profile == PROFILE) {
            port.width == 640 && port.height == 480
        } else {
            (port.width == 640 && port.height == 480) ||
                (port.width == SOURCE_WIDTH && port.height == SOURCE_HEIGHT) ||
                (port.width == 1280 && port.height == 960) ||
                (port.width == 1600 && port.height == 1200)
        }
        require(supported) {
            if (profile == PROFILE) "LCD scanout requires a 640x480 display"
            else "LCD scanout requires a 640x480, ${SOURCE_WIDTH}x${SOURCE_HEIGHT}, 1280x960, or 1600x1200 display"
        }
        if (pixels.size != port.width * port.height) {
            targetWidth = port.width
            targetHeight = port.height
            pixels = IntArray(targetWidth * targetHeight)
            mappedSourceWidth = 0
            mappedSourceHeight = 0
            ensureSourceMapping(SOURCE_WIDTH, SOURCE_HEIGHT)
        }
        return port
    }

    private fun ensureSourceMapping(canvasWidth: Int, canvasHeight: Int) {
        if (sourceRows.size != targetHeight || sourceCols.size != targetWidth ||
            mappedSourceWidth != canvasWidth || mappedSourceHeight != canvasHeight
        ) {
            sourceRows = IntArray(targetHeight) { row -> ((2 * row + 1) * canvasHeight) / (2 * targetHeight) }
            sourceCols = IntArray(targetWidth) { column -> ((2 * column + 1) * canvasWidth) / (2 * targetWidth) }
            mappedSourceWidth = canvasWidth
            mappedSourceHeight = canvasHeight
        }
    }

    fun bind() { display() }

    fun write(offset: Long, value: Long, words: LongArray, writeCounts: LongArray) {
        if (!continuous && cursor.publishedFrames == frameLimit) return
        if (profile == PIC_YUV_OSD0) {
            val peer = evfPeer
            if (continuous && peer != null) {
                // Real GFX100S 2.13 drives both Rear LCD (1024px active, +0x014 low16=0x0400)
                // and EVF OLED (1280px active, +0x014 low16=0x0500) via LCDC1 (0xFFFA9000).
                val hActive = ((if (offset == 0x014L) value else words[0x014 / 4]) and 0xFFFFL).toInt()
                if (hActive == 1280) {
                    if (outputActive) blankToBlack("routed-to-evf")
                    peer.write(offset, value, words, writeCounts)
                    return
                } else if (peer.outputActive) {
                    peer.blankToBlack("routed-to-lcd")
                }
            }
            // LCDC +0x004 (Run/Stop Control): transitioning 1 -> 0 stops scanout on this controller
            // (used by lcdevf T66 when muting the inactive LCDC0/LCDC1 during EVF <-> LCD switching).
            if (continuous && offset == 0x004L && (value and 1L) == 0L &&
                writeCounts[0x004 / 4] > 0L && (words[0x004 / 4] and 1L) != 0L &&
                cursor.publishedFrames > 0
            ) {
                val main = display()
                pixels.fill(BLANK_BLACK_ARGB)
                main.presentArgb(pixels, targetWidth, targetHeight)
                outputActive = false
                blankReason = "run-stop-muted"
                sawOobeOpaqueOsd = false
                cursor = Cursor(publishedAddress = cursor.publishedAddress, publishedFrames = cursor.publishedFrames + 1)
                return
            }
            if (offset != 0x600L || (value and 1L) == 0L) return
            if (writeCounts[0x008 / 4] == 0L) return
            val reg008 = words[0x008 / 4]
            val osdCtrl = words[0x030 / 4]
            val osdAddr = ((words[0x040 / 4] and 3L) shl 32) or (words[0x044 / 4] and 0xffffffffL)
            val pic0Addr = ((words[0x130 / 4] and 3L) shl 32) or (words[0x134 / 4] and 0xffffffffL)
            val pic0SizeWord = words[0x144 / 4]
            val pic1Addr = ((words[0x164 / 4] and 3L) shl 32) or (words[0x168 / 4] and 0xffffffffL)
            val pic1SizeWord = words[0x178 / 4]
            val osd0Bit = (reg008 and LAYER_EN_OSD0) != 0L
            val activeOutputGate = (reg008 and LAYER_EN_ACTIVE_GATE) != 0L
            val pic0Bit = (reg008 and LAYER_EN_PIC0) != 0L
            val pic1Bit = (reg008 and LAYER_EN_PIC1) != 0L
            val pic0Enabled = pic0Bit && pic0Addr != 0L && pic0SizeWord != 0L
            val pic1Enabled = pic1Bit && pic1Addr != 0L && pic1SizeWord != 0L
            val anyPicEnabled = pic0Enabled || pic1Enabled
            val enforceGate = continuous || strictActiveOutputGate
            // Bit 12 (0x00001000) gates OSD0 output/blending; when PIC0/PIC1 is also enabled (e.g. Playback
            // "Info Off" 0x00010010), clearing bit 12 hides OSD0 while the picture plane renders cleanly.
            if (enforceGate && !activeOutputGate && !anyPicEnabled) {
                sawOobeOpaqueOsd = false
                outputActive = false
                val anyPlaneBit = osd0Bit || pic1Bit || pic0Bit
                if (anyPlaneBit) {
                    activeGateBlankCount++
                    blankReason = "active-output-gate-cleared"
                    // Real GFX100S LCDC1 hardware requires +0x008 bit 12 (0x00001000) to drive the panel.
                    // Preserve stock pre-display init (publishedFrames==0 && 0x030==0x20080001) unless strictActiveOutputGate=true;
                    // stock firmware power-off/mute (0x030==0x20080001 after frames published, !strictActiveOutputGate) blanks to pure black,
                    // while bare 0x00010000 with active opaque OSD0 (or strictActiveOutputGate=true) blanks to flat gray (0xFF808080)!
                    val isStockPreInit = !strictActiveOutputGate && cursor.publishedFrames == 0 && osdCtrl == OSD0_CHROMA_KEY_CONTROL
                    val isStockMutedBlank = !strictActiveOutputGate && cursor.publishedFrames > 0 && osdCtrl == OSD0_CHROMA_KEY_CONTROL
                    if (!isStockPreInit) {
                        val main = display()
                        pixels.fill(if (isStockMutedBlank) BLANK_BLACK_ARGB else BLANK_GRAY_ARGB)
                        main.presentArgb(pixels, targetWidth, targetHeight)
                        val blankAddr = cursor.publishedAddress
                            ?: osdAddr.takeIf(::validCompositeAddress)
                            ?: pic1Addr.takeIf(::validCompositeAddress)
                        if (blankAddr != null) {
                            cursor = Cursor(publishedAddress = blankAddr, publishedFrames = cursor.publishedFrames + 1)
                        }
                    }
                } else {
                    blankReason = "planes-disabled"
                    if (cursor.publishedFrames > 0) {
                        val main = display()
                        pixels.fill(BLANK_BLACK_ARGB)
                        main.presentArgb(pixels, targetWidth, targetHeight)
                        cursor = Cursor(publishedAddress = cursor.publishedAddress, publishedFrames = cursor.publishedFrames + 1)
                    }
                }
                return
            }
            // Only treat an OOBE/START opaque-to-chroma transition (0x1FC7F088 -> 0x20080001 while PIC1 is off)
            // as power-off OSD mute. Live OSD0-only shooting modes (e.g. STILL DISP/BACK Info Display, where
            // firmware clears PIC1 bit 4 -> 0x11000 while keeping OSD0 + bit 12 + 0x20080001 active) must render OSD0!
            val osdBlanked = continuous && osdCtrl == OSD0_CHROMA_KEY_CONTROL &&
                (!anyPicEnabled && sawOobeOpaqueOsd)
            if (osdCtrl == OSD0_OOBE_OPAQUE_CONTROL && (reg008 and (LAYER_EN_OSD0 or LAYER_EN_ACTIVE_GATE)) == (LAYER_EN_OSD0 or LAYER_EN_ACTIVE_GATE)) {
                sawOobeOpaqueOsd = true
            } else if (anyPicEnabled) {
                sawOobeOpaqueOsd = false
            }
            val osdEnabled = osd0Bit && (!enforceGate || activeOutputGate) && osdCtrl != 0L && !osdBlanked && osdAddr != 0L
            if (!osdEnabled && !anyPicEnabled) {
                if (continuous && cursor.publishedFrames > 0) {
                    val main = display()
                    pixels.fill(BLANK_BLACK_ARGB)
                    main.presentArgb(pixels, targetWidth, targetHeight)
                    outputActive = false
                    blankReason = if (osdBlanked) "osd0-chroma-blank" else "planes-disabled"
                    cursor = Cursor(publishedAddress = cursor.publishedAddress, publishedFrames = cursor.publishedFrames + 1)
                }
                return
            }
            if (osdEnabled && (geometryOffsets.any { writeCounts[it / 4] == 0L } || writeCounts[0x060 / 4] == 0L)) return
            if (pic0Enabled && (writeCounts[0x138 / 4] == 0L || writeCounts[0x148 / 4] == 0L)) return
            if (pic1Enabled && (writeCounts[0x16c / 4] == 0L || writeCounts[0x17c / 4] == 0L)) return
            publishComposite(
                words,
                writeCounts,
                osdEnabled, osdAddr,
                pic0Enabled, pic0Addr, pic0SizeWord,
                pic1Enabled, pic1Addr, pic1SizeWord,
                opaqueOsdBackdrop = osdCtrl == OSD0_OOBE_OPAQUE_CONTROL,
                backdropArgb = decodeBgColor(osdCtrl),
            )
            return
        }
        if (offset == 0x40L) {
            cursor = cursor.copy(upper = value and 3, pendingSlot = false)
            return
        }
        if (profile == RGBA && offset == 0x60L && cursor.pendingSlot) {
            if (geometryOffsets.any { writeCounts[it / 4] == 0L }) return
            require(words[0x50 / 4] == 0x00010400L) { "Unsupported LCD OSD format or pitch" }
            require(words[0x54 / 4] == EXTENT.toLong() && words[0x58 / 4] == 0L &&
                words[0x5c / 4] == EXTENT.toLong()) { "Unsupported LCD OSD geometry or window" }
            require(value == 0x10001000L) { "LCD OSD requires unit Q12 scale" }
            publish(words[0x44 / 4])
            return
        }
        if (offset == 0x44L && cursor.pendingSlot) cursor = cursor.copy(pendingSlot = false)
        val upper = cursor.upper ?: return
        if (offset != 0x44L) {
            cursor = cursor.copy(upper = null)
            return
        }
        val address = (upper shl 32) or value
        if (address == 0L) {
            cursor = cursor.copy(upper = null)
            return
        }
        if (profile == RGBA) {
            require(words[0x40 / 4] == 0L && validAddress(address)) { "LCD RGBA payload outside aligned physical RAM address space" }
            cursor = cursor.copy(upper = null, pendingSlot = true)
        } else publish(address)
    }

    private fun publishComposite(
        words: LongArray,
        writeCounts: LongArray,
        osdEnabled: Boolean,
        osdAddr: Long,
        pic0Enabled: Boolean,
        pic0Addr: Long,
        pic0SizeWord: Long,
        pic1Enabled: Boolean,
        pic1Addr: Long,
        pic1SizeWord: Long,
        opaqueOsdBackdrop: Boolean = false,
        backdropArgb: Int = BLANK_BLACK_ARGB,
    ) {
        var osdWidth = SOURCE_WIDTH
        var osdHeight = SOURCE_HEIGHT
        var osdStrideBytes = STRIDE
        var osdByteCount = byteCount.toLong()
        var osdFlipH = false
        var osdFlipV = false
        val flexibleGeometry = continuous || displayPort == "display.evf"
        if (osdEnabled) {
            if (!flexibleGeometry) {
                require(words[0x040 / 4] == 0L && validAddress(osdAddr)) { "LCD RGBA payload outside aligned physical RAM address space" }
                require(words[0x050 / 4] == 0x00010400L) { "Unsupported LCD OSD format or pitch" }
                require(words[0x054 / 4] == EXTENT.toLong() && words[0x058 / 4] == 0L &&
                    words[0x05c / 4] == EXTENT.toLong()) { "Unsupported LCD OSD geometry or window" }
                require(words[0x060 / 4] == 0x10001000L) { "LCD OSD requires unit Q12 scale" }
            } else {
                // GFX100S LCDC1 +0x050: [15:0]=pitchPx, [23:16]=pixelFormat (1=RGBA8888),
                // [25:24]=hardware OSD0 H/V flip (0x03010400 in Portrait CW +90° for 180° flip, BUG-V3-2-1).
                val word50 = words[0x050 / 4]
                osdFlipH = (word50 and 0x0100_0000L) != 0L
                osdFlipV = (word50 and 0x0200_0000L) != 0L
                val osdFormat = (word50 ushr 16) and 0xFFL
                val osdPitchPixels = (word50 and 0xFFFFL).toInt()
                val osdSrcWord = words[0x054 / 4]
                osdWidth = (osdSrcWord and 0xFFFFL).toInt()
                osdHeight = ((osdSrcWord ushr 16) and 0xFFFFL).toInt()
                val osdDstWord = words[0x05C / 4]
                val osdDstW = (osdDstWord and 0xFFFFL).toInt()
                val osdDstH = ((osdDstWord ushr 16) and 0xFFFFL).toInt()
                require(osdFormat == 1L && (word50 and 0xFC00_0000L) == 0L && osdPitchPixels in 2..2048) {
                    "Unsupported LCD OSD format or pitch"
                }
                require(osdWidth in 2..2048 && osdHeight in 1..2048 && osdPitchPixels >= osdWidth &&
                    words[0x058 / 4] == 0L && osdDstW == osdWidth && osdDstH == osdHeight) {
                    "Unsupported LCD OSD geometry or window"
                }
                require(words[0x060 / 4] == 0x10001000L) { "LCD OSD requires unit Q12 scale" }
                osdStrideBytes = osdPitchPixels * 4
                osdByteCount = (osdHeight - 1).toLong() * osdStrideBytes + osdWidth.toLong() * 4L
                require(words[0x040 / 4] == 0L && osdAddr in 1..(0x1_0000_0000L - osdByteCount) && osdAddr % 4L == 0L) {
                    "LCD RGBA payload outside aligned physical RAM address space"
                }
            }
            lastCanvasWidth = osdWidth
            lastCanvasHeight = osdHeight
        }
        var canvasWidth = if (osdEnabled) osdWidth else lastCanvasWidth
        var canvasHeight = if (osdEnabled) osdHeight else lastCanvasHeight
        var pic0Width = 0
        var pic0Height = 0
        var pic0X = 0
        var pic0Y = 0
        var pic0StrideBytes = 0
        var pic0ByteCount = 0L
        if (pic0Enabled) {
            pic0Width = (pic0SizeWord and 0xFFFFL).toInt()
            pic0Height = ((pic0SizeWord ushr 16) and 0xFFFFL).toInt()
            pic0X = (words[0x140 / 4] and 0xFFFFL).toInt()
            pic0Y = ((words[0x140 / 4] ushr 16) and 0xFFFFL).toInt()
            pic0StrideBytes = (words[0x138 / 4] and 0xFFFL).toInt() * 8
            val pic0ExtentWord = words[0x13C / 4]
            val pic0ExtW = (pic0ExtentWord and 0xFFFFL).toInt()
            val pic0ExtH = ((pic0ExtentWord ushr 16) and 0xFFFFL).toInt()
            if (!osdEnabled && flexibleGeometry) {
                canvasWidth = maxOf(canvasWidth, pic0X + pic0Width, pic0ExtW)
                canvasHeight = maxOf(canvasHeight, pic0Y + pic0Height, pic0ExtH)
            }
            require(pic0Width in 2..canvasWidth && pic0Width % 2 == 0 && pic0Height in 1..canvasHeight) {
                "Unsupported LCD PIC0 dimensions"
            }
            require(pic0X >= 0 && pic0Y >= 0 && pic0X + pic0Width <= canvasWidth && pic0Y + pic0Height <= canvasHeight) {
                "Unsupported LCD PIC0 window placement"
            }
            require(pic0StrideBytes >= pic0Width * 2) { "Unsupported LCD PIC0 stride" }
            require(words[0x148 / 4] == 0x10001000L) { "LCD PIC0 requires unit Q12 scale" }
            if (pic0ExtentWord != 0L) {
                require(pic0ExtW in pic0Width..canvasWidth && pic0ExtH in pic0Height..canvasHeight) {
                    "Unsupported LCD PIC0 output extent"
                }
            }
            pic0ByteCount = (pic0Height - 1).toLong() * pic0StrideBytes + pic0Width.toLong() * 2L
            require(words[0x130 / 4] == 0L && pic0Addr in 1..(0x1_0000_0000L - pic0ByteCount) && pic0Addr % 4L == 0L) {
                "LCD PIC0 payload outside aligned physical RAM address space"
            }
        }
        var pic1Width = 0
        var pic1Height = 0
        var pic1X = 0
        var pic1Y = 0
        var pic1StrideBytes = 0
        var pic1Alpha = 0
        var pic1ByteCount = 0L
        var pic1ScaleX = 0x1000
        var pic1ScaleY = 0x1000
        var pic1SrcWidth = 0
        var pic1SrcHeight = 0
        if (pic1Enabled) {
            pic1Width = (pic1SizeWord and 0xFFFFL).toInt()
            pic1Height = ((pic1SizeWord ushr 16) and 0xFFFFL).toInt()
            pic1X = (words[0x174 / 4] and 0xFFFFL).toInt()
            pic1Y = ((words[0x174 / 4] ushr 16) and 0xFFFFL).toInt()
            pic1StrideBytes = (words[0x16C / 4] and 0xFFFL).toInt() * 8
            pic1Alpha = (words[0x160 / 4] and 0xFFL).toInt()
            val pic1ExtentWord = words[0x170 / 4]
            val pic1ExtW = (pic1ExtentWord and 0xFFFFL).toInt()
            val pic1ExtH = ((pic1ExtentWord ushr 16) and 0xFFFFL).toInt()
            val pic1ScaleWord = words[0x17C / 4]
            val pic1UnitScale = pic1ScaleWord == 0x10001000L
            if (pic1UnitScale) {
                if (!osdEnabled && flexibleGeometry) {
                    canvasWidth = maxOf(canvasWidth, pic1X + pic1Width, pic1ExtW)
                    canvasHeight = maxOf(canvasHeight, pic1Y + pic1Height, pic1ExtH)
                }
                require(pic1Width in 2..canvasWidth && pic1Width % 2 == 0 && pic1Height in 1..canvasHeight) {
                    "Unsupported LCD PIC1 dimensions"
                }
                require(pic1X in 0 until canvasWidth && pic1Y in 0 until canvasHeight) {
                    "Unsupported LCD PIC1 window placement"
                }
                require(pic1StrideBytes >= pic1Width * 2) { "Unsupported LCD PIC1 stride" }
                if (pic1ExtentWord != 0L) {
                    require(pic1ExtW in pic1Width..canvasWidth && pic1ExtH in pic1Height..canvasHeight) {
                        "Unsupported LCD PIC1 output extent"
                    }
                }
                // MOV playback places a valid 1024x768 surface at y=96. Its bottom falls outside
                // the display. Validate the programmed surface first, then fetch only visible UYVY pairs.
                pic1Width = minOf(pic1Width, canvasWidth - pic1X) and -2
                pic1Height = minOf(pic1Height, canvasHeight - pic1Y)
                require(pic1Width >= 2) { "No complete LCD PIC1 pixel pair in the scanout window" }
                pic1SrcWidth = pic1Width
                pic1SrcHeight = pic1Height
                pic1ByteCount = (pic1Height - 1).toLong() * pic1StrideBytes + pic1Width.toLong() * 2L
            } else {
                // Real GFX100S 2.13 LCDC1 PIC1 hardware Q12 scaler (BUG-V3-8-8): during Playback
                // 9/100-frame grid and zoom transitions (e.g. +0x170=0x02d00404 [1028x720 src],
                // +0x178=0x03000444 [1092x768 scaled out], +0x17C=0x0f000f0f [Q12 step]),
                // +0x170 holds the DRAM source extent, +0x178 holds the scaled output size
                // (clipped to canvas), and +0x17C encodes (scaleY_Q12 << 16) | scaleX_Q12.
                pic1ScaleX = (pic1ScaleWord and 0xFFFFL).toInt()
                pic1ScaleY = ((pic1ScaleWord ushr 16) and 0xFFFFL).toInt()
                require(pic1ScaleX in 0x0100..0x8000 && pic1ScaleY in 0x0100..0x8000) {
                    "LCD PIC1 requires valid Q12 scale"
                }
                require(pic1Width in 2..4096 && pic1Height in 1..4096) {
                    "Unsupported LCD PIC1 dimensions"
                }
                require(pic1X in 0 until canvasWidth && pic1Y in 0 until canvasHeight) {
                    "Unsupported LCD PIC1 window placement"
                }
                pic1SrcWidth = if (pic1ExtentWord != 0L && pic1ExtW > 0) {
                    pic1ExtW
                } else {
                    (((pic1Width.toLong() * pic1ScaleX) + 2048L) ushr 12).toInt().coerceAtLeast(2) and 1.inv()
                }
                pic1SrcHeight = if (pic1ExtentWord != 0L && pic1ExtH > 0) {
                    pic1ExtH
                } else {
                    (((pic1Height.toLong() * pic1ScaleY) + 2048L) ushr 12).toInt().coerceAtLeast(1)
                }
                require(pic1SrcWidth in 2..4096 && pic1SrcWidth % 2 == 0 && pic1SrcHeight in 1..4096) {
                    "Unsupported LCD PIC1 source dimensions"
                }
                require(pic1StrideBytes >= pic1SrcWidth * 2) { "Unsupported LCD PIC1 stride" }
                pic1ByteCount = (pic1SrcHeight - 1).toLong() * pic1StrideBytes + pic1SrcWidth.toLong() * 2L
            }
            require(words[0x164 / 4] == 0L && pic1Addr in 1..(0x1_0000_0000L - pic1ByteCount) && pic1Addr % 4L == 0L) {
                "LCD PIC1 payload outside aligned physical RAM address space"
            }
        }
        val main = display()
        ensureSourceMapping(canvasWidth, canvasHeight)
        val memory = requireNotNull(context.ports.find("cpu.memory", DmaMemory::class.java)) { "LCD scanout requires cpu.memory" }
        fun checkReadable(address: Long, len: Long): Boolean {
            if (!continuous) {
                memory.check(address, len, false)
                return true
            }
            return try {
                memory.check(address, len, false)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }
        val viewAssistActive = picturePostProcessor.updateDisplayLut(words, writeCounts)
        val pic0Zebra = LcdcPicturePostProcessor.parseZebra(words, writeCounts, 0x154, 0x158, 0x15C)
        val pic1Zebra = LcdcPicturePostProcessor.parseZebra(words, writeCounts, 0x188, 0x18C, 0x190)
        val matrixMode = resolveColorMatrixAndBacklight(words, writeCounts, colorMatrixScratch)
        val activeMatrix = if (matrixMode != 0) colorMatrixScratch else null

        val directRam = memory as? DirectGuestRamPort
        val allowDirectNative = directRam != null && NativeRenderBridge.isAvailable
        val pic0DirectSeg = if (allowDirectNative && pic0Enabled && !viewAssistActive && !pic0Zebra.enabled && checkReadable(pic0Addr, pic0ByteCount)) {
            directRam.sliceDirect(pic0Addr, pic0ByteCount, write = false)
        } else null
        val pic1DirectSeg = if (allowDirectNative && pic1Enabled && pic1ScaleX == 0x1000 && pic1ScaleY == 0x1000 && !viewAssistActive && !pic1Zebra.enabled && checkReadable(pic1Addr, pic1ByteCount)) {
            directRam.sliceDirect(pic1Addr, pic1ByteCount, write = false)
        } else null
        val osdDirectSeg = if (allowDirectNative && osdEnabled && !osdFlipH && !osdFlipV && checkReadable(osdAddr, osdByteCount)) {
            directRam.sliceDirect(osdAddr, osdByteCount, write = false)
        } else null

        var pic0Bytes = if (pic0DirectSeg == null && pic0Enabled && checkReadable(pic0Addr, pic0ByteCount)) {
            val len = pic0ByteCount.toInt()
            if (pic0Scratch.size < len) pic0Scratch = ByteArray(len)
            val buf = ByteBuffer.wrap(pic0Scratch, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            memory.read(pic0Addr, buf)
            require(buf.position() == len) { "Incomplete LCD PIC0 scanout payload" }
            pic0Scratch
        } else null
        val rawPic1Bytes = if (pic1DirectSeg == null && pic1Enabled && checkReadable(pic1Addr, pic1ByteCount)) {
            val len = pic1ByteCount.toInt()
            if (pic1Scratch.size < len) pic1Scratch = ByteArray(len)
            val buf = ByteBuffer.wrap(pic1Scratch, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            memory.read(pic1Addr, buf)
            require(buf.position() == len) { "Incomplete LCD PIC1 scanout payload" }
            pic1Scratch
        } else null
        var pic1Bytes: ByteArray?
        if (rawPic1Bytes != null && (pic1ScaleX != 0x1000 || pic1ScaleY != 0x1000)) {
            val outW = minOf(pic1Width, canvasWidth - pic1X) and 1.inv()
            val outH = minOf(pic1Height, canvasHeight - pic1Y)
            if (outW >= 2 && outH >= 1) {
                val outStride = outW * 2
                val outLen = outStride * outH
                if (pic1ScaledScratch.size < outLen) pic1ScaledScratch = ByteArray(outLen)
                resampleUyvyQ12(
                    src = rawPic1Bytes,
                    srcW = pic1SrcWidth,
                    srcH = pic1SrcHeight,
                    srcStrideBytes = pic1StrideBytes,
                    scaleX = pic1ScaleX,
                    scaleY = pic1ScaleY,
                    dst = pic1ScaledScratch,
                    dstW = outW,
                    dstH = outH,
                )
                pic1Width = outW
                pic1Height = outH
                pic1StrideBytes = outStride
                pic1Bytes = pic1ScaledScratch
            } else {
                pic1Bytes = null
            }
        } else {
            pic1Bytes = rawPic1Bytes
        }
        val rawOsdPayload = if (osdDirectSeg == null && osdEnabled && checkReadable(osdAddr, osdByteCount)) {
            val len = osdByteCount.toInt()
            val payload = if (len == byteCount) {
                raw.clear()
            } else {
                if (osdScratch.size < len) osdScratch = ByteArray(len)
                ByteBuffer.wrap(osdScratch, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            }
            memory.read(osdAddr, payload)
            require(payload.position() == len) { "Incomplete LCD scanout payload" }
            payload.flip()
            payload
        } else null
        var osdPayload: ByteBuffer?
        val effectiveOsdStrideBytes: Int
        if (rawOsdPayload != null && (osdFlipH || osdFlipV)) {
            val flippedRowBytes = osdWidth * 4
            val flippedTotalBytes = flippedRowBytes * osdHeight
            if (osdFlipScratch.size < flippedTotalBytes) osdFlipScratch = ByteArray(flippedTotalBytes)
            val srcArr = rawOsdPayload.array()
            val srcBase = rawOsdPayload.arrayOffset()
            for (dstY in 0 until osdHeight) {
                val srcY = if (osdFlipV) (osdHeight - 1 - dstY) else dstY
                val srcRowOff = srcBase + srcY * osdStrideBytes
                val dstRowOff = dstY * flippedRowBytes
                if (!osdFlipH) {
                    System.arraycopy(srcArr, srcRowOff, osdFlipScratch, dstRowOff, flippedRowBytes)
                } else {
                    var dstX = 0
                    var srcX = osdWidth - 1
                    while (dstX < osdWidth) {
                        val sOff = srcRowOff + (srcX shl 2)
                        val dOff = dstRowOff + (dstX shl 2)
                        osdFlipScratch[dOff] = srcArr[sOff]
                        osdFlipScratch[dOff + 1] = srcArr[sOff + 1]
                        osdFlipScratch[dOff + 2] = srcArr[sOff + 2]
                        osdFlipScratch[dOff + 3] = srcArr[sOff + 3]
                        dstX++
                        srcX--
                    }
                }
            }
            osdPayload = ByteBuffer.wrap(osdFlipScratch, 0, flippedTotalBytes).order(ByteOrder.LITTLE_ENDIAN)
            effectiveOsdStrideBytes = flippedRowBytes
        } else {
            osdPayload = rawOsdPayload
            effectiveOsdStrideBytes = osdStrideBytes
        }
        val hasPic0 = pic0DirectSeg != null || pic0Bytes != null
        val hasPic1 = pic1DirectSeg != null || pic1Bytes != null
        val hasOsd = osdDirectSeg != null || osdPayload != null
        if (continuous && !hasPic0 && !hasPic1 && !hasOsd) {
            return
        }
        if (pic0Bytes != null && (viewAssistActive || pic0Zebra.enabled)) {
            picturePostProcessor.processUyvyPlaneInPlace(
                pic0Bytes, pic0Width, pic0Height, pic0StrideBytes, viewAssistActive, pic0Zebra, cursor.publishedFrames,
            )
        }
        if (pic1Bytes != null && (viewAssistActive || pic1Zebra.enabled)) {
            picturePostProcessor.processUyvyPlaneInPlace(
                pic1Bytes, pic1Width, pic1Height, pic1StrideBytes, viewAssistActive, pic1Zebra, cursor.publishedFrames,
            )
        }
        val anyDirectPlane = pic0DirectSeg != null || pic1DirectSeg != null || osdDirectSeg != null
        fun materializeDirectPlanesForScalarFallback() {
            if (pic0DirectSeg != null && pic0Bytes == null) {
                val len = pic0ByteCount.toInt()
                if (pic0Scratch.size < len) pic0Scratch = ByteArray(len)
                MemorySegment.copy(pic0DirectSeg, ValueLayout.JAVA_BYTE, 0L, pic0Scratch, 0, len)
                pic0Bytes = pic0Scratch
            }
            if (pic1DirectSeg != null && pic1Bytes == null) {
                val len = pic1ByteCount.toInt()
                if (pic1Scratch.size < len) pic1Scratch = ByteArray(len)
                MemorySegment.copy(pic1DirectSeg, ValueLayout.JAVA_BYTE, 0L, pic1Scratch, 0, len)
                pic1Bytes = pic1Scratch
            }
            if (osdDirectSeg != null && osdPayload == null) {
                val len = osdByteCount.toInt()
                if (osdScratch.size < len) osdScratch = ByteArray(len)
                MemorySegment.copy(osdDirectSeg, ValueLayout.JAVA_BYTE, 0L, osdScratch, 0, len)
                osdPayload = ByteBuffer.wrap(osdScratch, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            }
        }
        val sink = directArgbPreSink
        val hasWindows = (words[LcdcFrameOverlay.ENABLE / 4].toInt() and LcdcFrameOverlay.WINDOW_MASK) != 0
        fun paintWindows(target: java.nio.IntBuffer) = LcdcFrameOverlay.paint(
            words, canvasWidth, canvasHeight, targetWidth, targetHeight, colorMatrixScratch, matrixMode, target,
        )
        var presentedViaDirectSink = false
        if (sink != null) {
            presentedViaDirectSink = sink(targetWidth, targetHeight) { dstPreSeg ->
                if (anyDirectPlane && NativeRenderBridge.hasDirectArgbPreComposite) {
                    val osdSeg = osdDirectSeg ?: osdPayload?.array()?.let(MemorySegment::ofArray) ?: MemorySegment.NULL
                    val pic0Seg = pic0DirectSeg ?: pic0Bytes?.let(MemorySegment::ofArray) ?: MemorySegment.NULL
                    val pic1Seg = pic1DirectSeg ?: pic1Bytes?.let(MemorySegment::ofArray) ?: MemorySegment.NULL
                    val osdBaseOff = if (osdDirectSeg != null) 0 else (osdPayload?.arrayOffset() ?: 0)
                    val cfg = populateCompositeCfg(
                        osdBaseOff, effectiveOsdStrideBytes, canvasWidth, canvasHeight,
                        pic0X, pic0Y, pic0Width, pic0Height, pic0StrideBytes,
                        pic1X, pic1Y, pic1Width, pic1Height, pic1StrideBytes,
                        pic1Alpha, opaqueOsdBackdrop, backdropArgb, matrixMode,
                    )
                    val nativeRes = NativeRenderBridge.compositeLcdcToArgbPreDirect(
                        osdSegment = osdSeg,
                        pic0Segment = pic0Seg,
                        pic1Segment = pic1Seg,
                        sourceRows = sourceRows,
                        sourceCols = sourceCols,
                        colorMatrixQ10 = activeMatrix,
                        cfg = cfg,
                        outputSegment = dstPreSeg,
                    )
                    if (nativeRes >= 0) {
                        if (hasWindows) paintWindows(dstPreSeg.asByteBuffer().order(ByteOrder.nativeOrder()).asIntBuffer())
                        return@sink
                    }
                }
                materializeDirectPlanesForScalarFallback()
                compositePlanesToArgbPre(
                    osdPayload = osdPayload,
                    osdStrideBytes = effectiveOsdStrideBytes,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    pic0Bytes = pic0Bytes,
                    pic0X = pic0X,
                    pic0Y = pic0Y,
                    pic0Width = pic0Width,
                    pic0Height = pic0Height,
                    pic0StrideBytes = pic0StrideBytes,
                    pic1Bytes = pic1Bytes,
                    pic1X = pic1X,
                    pic1Y = pic1Y,
                    pic1Width = pic1Width,
                    pic1Height = pic1Height,
                    pic1StrideBytes = pic1StrideBytes,
                    pic1Alpha = pic1Alpha,
                    opaqueOsdBackdrop = opaqueOsdBackdrop,
                    backdropArgb = backdropArgb,
                    colorMatrixQ10 = activeMatrix,
                    matrixMode = matrixMode,
                    outputSegment = dstPreSeg,
                    allowNative = true,
                )
                if (hasWindows) paintWindows(dstPreSeg.asByteBuffer().order(ByteOrder.nativeOrder()).asIntBuffer())
            }
        }
        if (!presentedViaDirectSink || mirrorDirectSinkToDisplayPort) {
            var compositedDirect = false
            if (anyDirectPlane) {
                val osdSeg = osdDirectSeg ?: osdPayload?.array()?.let(MemorySegment::ofArray) ?: MemorySegment.NULL
                val pic0Seg = pic0DirectSeg ?: pic0Bytes?.let(MemorySegment::ofArray) ?: MemorySegment.NULL
                val pic1Seg = pic1DirectSeg ?: pic1Bytes?.let(MemorySegment::ofArray) ?: MemorySegment.NULL
                val osdBaseOff = if (osdDirectSeg != null) 0 else (osdPayload?.arrayOffset() ?: 0)
                val cfg = populateCompositeCfg(
                    osdBaseOff, effectiveOsdStrideBytes, canvasWidth, canvasHeight,
                    pic0X, pic0Y, pic0Width, pic0Height, pic0StrideBytes,
                    pic1X, pic1Y, pic1Width, pic1Height, pic1StrideBytes,
                    pic1Alpha, opaqueOsdBackdrop, backdropArgb, matrixMode,
                )
                compositedDirect = NativeRenderBridge.compositePlanesWithMatrixDirect(
                    osdSegment = osdSeg,
                    pic0Segment = pic0Seg,
                    pic1Segment = pic1Seg,
                    sourceRows = sourceRows,
                    sourceCols = sourceCols,
                    colorMatrixQ10 = activeMatrix,
                    cfg = cfg,
                    output = pixels,
                )
            }
            if (!compositedDirect) {
                materializeDirectPlanesForScalarFallback()
                compositePlanesAndMatrix(
                    osdPayload = osdPayload,
                    osdStrideBytes = effectiveOsdStrideBytes,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    pic0Bytes = pic0Bytes,
                    pic0X = pic0X,
                    pic0Y = pic0Y,
                    pic0Width = pic0Width,
                    pic0Height = pic0Height,
                    pic0StrideBytes = pic0StrideBytes,
                    pic1Bytes = pic1Bytes,
                    pic1X = pic1X,
                    pic1Y = pic1Y,
                    pic1Width = pic1Width,
                    pic1Height = pic1Height,
                    pic1StrideBytes = pic1StrideBytes,
                    pic1Alpha = pic1Alpha,
                    opaqueOsdBackdrop = opaqueOsdBackdrop,
                    backdropArgb = backdropArgb,
                    colorMatrixQ10 = activeMatrix,
                    matrixMode = matrixMode,
                    output = pixels,
                    allowNative = true,
                )
            }
            if (hasWindows) paintWindows(java.nio.IntBuffer.wrap(pixels))
            main.presentArgb(pixels, targetWidth, targetHeight)
        }
        outputActive = true
        blankReason = null
        val publishedAddr = if (hasOsd) osdAddr else if (hasPic1) pic1Addr else pic0Addr
        cursor = Cursor(publishedAddress = publishedAddr, publishedFrames = cursor.publishedFrames + 1)
    }

    private fun populateCompositeCfg(
        osdBaseOffset: Int,
        osdStrideBytes: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        pic0X: Int,
        pic0Y: Int,
        pic0Width: Int,
        pic0Height: Int,
        pic0StrideBytes: Int,
        pic1X: Int,
        pic1Y: Int,
        pic1Width: Int,
        pic1Height: Int,
        pic1StrideBytes: Int,
        pic1Alpha: Int,
        opaqueOsdBackdrop: Boolean,
        backdropArgb: Int,
        matrixMode: Int,
    ): IntArray {
        val cfg = compositeCfgScratch
        cfg[0] = osdBaseOffset
        cfg[1] = osdStrideBytes
        cfg[2] = pic0X
        cfg[3] = pic0Y
        cfg[4] = pic0Width
        cfg[5] = pic0Height
        cfg[6] = pic0StrideBytes
        cfg[7] = pic1X
        cfg[8] = pic1Y
        cfg[9] = pic1Width
        cfg[10] = pic1Height
        cfg[11] = pic1StrideBytes
        cfg[12] = pic1Alpha
        cfg[13] = if (opaqueOsdBackdrop) 1 else 0
        cfg[14] = backdropArgb
        cfg[15] = canvasWidth
        cfg[16] = canvasHeight
        cfg[17] = targetWidth
        cfg[18] = targetHeight
        cfg[19] = matrixMode
        return cfg
    }

    /**
     * Single-pass fused 3-plane + Q10 color matrix composite emitting premultiplied `INT_ARGB_PRE`
     * directly into an off-heap [MemorySegment] (e.g. JavaFX `PixelBuffer` backing memory).
     *
     * @return `true` if every output pixel had `alpha == 0xFF`, `false` if any translucent pixel
     *   was premultiplied in-register.
     */
    internal fun compositePlanesToArgbPre(
        osdPayload: ByteBuffer?,
        osdStrideBytes: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        pic0Bytes: ByteArray?,
        pic0X: Int,
        pic0Y: Int,
        pic0Width: Int,
        pic0Height: Int,
        pic0StrideBytes: Int,
        pic1Bytes: ByteArray?,
        pic1X: Int,
        pic1Y: Int,
        pic1Width: Int,
        pic1Height: Int,
        pic1StrideBytes: Int,
        pic1Alpha: Int,
        opaqueOsdBackdrop: Boolean,
        backdropArgb: Int,
        colorMatrixQ10: IntArray?,
        matrixMode: Int,
        outputSegment: MemorySegment,
        allowNative: Boolean = true,
    ): Boolean {
        ensureSourceMapping(canvasWidth, canvasHeight)
        val osdBytes = osdPayload?.array()
        val osdBaseOffset = osdPayload?.arrayOffset() ?: 0
        if (allowNative) {
            val cfg = populateCompositeCfg(
                osdBaseOffset, osdStrideBytes, canvasWidth, canvasHeight,
                pic0X, pic0Y, pic0Width, pic0Height, pic0StrideBytes,
                pic1X, pic1Y, pic1Width, pic1Height, pic1StrideBytes,
                pic1Alpha, opaqueOsdBackdrop, backdropArgb, matrixMode,
            )
            val nativeRes = NativeRenderBridge.compositeLcdcToArgbPre(
                osdBytes = osdBytes,
                pic0Bytes = pic0Bytes,
                pic1Bytes = pic1Bytes,
                sourceRows = sourceRows,
                sourceCols = sourceCols,
                colorMatrixQ10 = colorMatrixQ10,
                cfg = cfg,
                outputSegment = outputSegment,
            )
            if (nativeRes >= 0) {
                return nativeRes != 0
            }
        }
        val count = targetWidth * targetHeight
        val tmp = if (premulFallbackScratch.size == count) premulFallbackScratch else IntArray(count).also { premulFallbackScratch = it }
        val allOpaque = compositePlanesToArgbPre(
            osdPayload = osdPayload,
            osdStrideBytes = osdStrideBytes,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            pic0Bytes = pic0Bytes,
            pic0X = pic0X,
            pic0Y = pic0Y,
            pic0Width = pic0Width,
            pic0Height = pic0Height,
            pic0StrideBytes = pic0StrideBytes,
            pic1Bytes = pic1Bytes,
            pic1X = pic1X,
            pic1Y = pic1Y,
            pic1Width = pic1Width,
            pic1Height = pic1Height,
            pic1StrideBytes = pic1StrideBytes,
            pic1Alpha = pic1Alpha,
            opaqueOsdBackdrop = opaqueOsdBackdrop,
            backdropArgb = backdropArgb,
            colorMatrixQ10 = colorMatrixQ10,
            matrixMode = matrixMode,
            output = tmp,
            allowNative = false,
        )
        MemorySegment.copy(tmp, 0, outputSegment, ValueLayout.JAVA_INT_UNALIGNED, 0L, count)
        return allOpaque
    }

    /**
     * Heap `IntArray` overload of [compositePlanesToArgbPre] (single-pass native SIMD or Kotlin scalar fallback).
     */
    internal fun compositePlanesToArgbPre(
        osdPayload: ByteBuffer?,
        osdStrideBytes: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        pic0Bytes: ByteArray?,
        pic0X: Int,
        pic0Y: Int,
        pic0Width: Int,
        pic0Height: Int,
        pic0StrideBytes: Int,
        pic1Bytes: ByteArray?,
        pic1X: Int,
        pic1Y: Int,
        pic1Width: Int,
        pic1Height: Int,
        pic1StrideBytes: Int,
        pic1Alpha: Int,
        opaqueOsdBackdrop: Boolean,
        backdropArgb: Int,
        colorMatrixQ10: IntArray?,
        matrixMode: Int,
        output: IntArray,
        allowNative: Boolean = true,
    ): Boolean {
        ensureSourceMapping(canvasWidth, canvasHeight)
        val osdBytes = osdPayload?.array()
        val osdBaseOffset = osdPayload?.arrayOffset() ?: 0
        if (allowNative) {
            val cfg = populateCompositeCfg(
                osdBaseOffset, osdStrideBytes, canvasWidth, canvasHeight,
                pic0X, pic0Y, pic0Width, pic0Height, pic0StrideBytes,
                pic1X, pic1Y, pic1Width, pic1Height, pic1StrideBytes,
                pic1Alpha, opaqueOsdBackdrop, backdropArgb, matrixMode,
            )
            val nativeRes = NativeRenderBridge.compositeLcdcToArgbPre(
                osdBytes = osdBytes,
                pic0Bytes = pic0Bytes,
                pic1Bytes = pic1Bytes,
                sourceRows = sourceRows,
                sourceCols = sourceCols,
                colorMatrixQ10 = colorMatrixQ10,
                cfg = cfg,
                output = output,
            )
            if (nativeRes >= 0) {
                return nativeRes != 0
            }
        }
        compositePlanesAndMatrix(
            osdPayload = osdPayload,
            osdStrideBytes = osdStrideBytes,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            pic0Bytes = pic0Bytes,
            pic0X = pic0X,
            pic0Y = pic0Y,
            pic0Width = pic0Width,
            pic0Height = pic0Height,
            pic0StrideBytes = pic0StrideBytes,
            pic1Bytes = pic1Bytes,
            pic1X = pic1X,
            pic1Y = pic1Y,
            pic1Width = pic1Width,
            pic1Height = pic1Height,
            pic1StrideBytes = pic1StrideBytes,
            pic1Alpha = pic1Alpha,
            opaqueOsdBackdrop = opaqueOsdBackdrop,
            backdropArgb = backdropArgb,
            colorMatrixQ10 = colorMatrixQ10,
            matrixMode = matrixMode,
            output = output,
            allowNative = false,
        )
        var allOpaque = true
        val count = targetWidth * targetHeight
        for (i in 0 until count) {
            val px = output[i]
            val a = px ushr 24
            if (a != 255) {
                allOpaque = false
                if (a == 0) {
                    output[i] = 0
                } else {
                    val r = div255Rounded(((px ushr 16) and 0xff) * a)
                    val g = div255Rounded(((px ushr 8) and 0xff) * a)
                    val b = div255Rounded((px and 0xff) * a)
                    output[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return allOpaque
    }

    internal fun compositePlanesAndMatrix(
        osdPayload: ByteBuffer?,
        osdStrideBytes: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        pic0Bytes: ByteArray?,
        pic0X: Int,
        pic0Y: Int,
        pic0Width: Int,
        pic0Height: Int,
        pic0StrideBytes: Int,
        pic1Bytes: ByteArray?,
        pic1X: Int,
        pic1Y: Int,
        pic1Width: Int,
        pic1Height: Int,
        pic1StrideBytes: Int,
        pic1Alpha: Int,
        opaqueOsdBackdrop: Boolean,
        backdropArgb: Int,
        colorMatrixQ10: IntArray?,
        matrixMode: Int,
        output: IntArray,
        allowNative: Boolean = true,
    ) {
        ensureSourceMapping(canvasWidth, canvasHeight)
        val osdBytes = osdPayload?.array()
        val osdBaseOffset = osdPayload?.arrayOffset() ?: 0
        if (allowNative) {
            val cfg = compositeCfgScratch
            cfg[0] = osdBaseOffset
            cfg[1] = osdStrideBytes
            cfg[2] = pic0X
            cfg[3] = pic0Y
            cfg[4] = pic0Width
            cfg[5] = pic0Height
            cfg[6] = pic0StrideBytes
            cfg[7] = pic1X
            cfg[8] = pic1Y
            cfg[9] = pic1Width
            cfg[10] = pic1Height
            cfg[11] = pic1StrideBytes
            cfg[12] = pic1Alpha
            cfg[13] = if (opaqueOsdBackdrop) 1 else 0
            cfg[14] = backdropArgb
            cfg[15] = canvasWidth
            cfg[16] = canvasHeight
            cfg[17] = targetWidth
            cfg[18] = targetHeight
            cfg[19] = matrixMode
            if (NativeRenderBridge.compositePlanesWithMatrix(
                    osdBytes = osdBytes,
                    pic0Bytes = pic0Bytes,
                    pic1Bytes = pic1Bytes,
                    sourceRows = sourceRows,
                    sourceCols = sourceCols,
                    colorMatrixQ10 = colorMatrixQ10,
                    cfg = cfg,
                    output = output,
                )
            ) {
                return
            }
        }
        if (pic0Bytes != null) {
            compositeDualPicAndOsd0(
                osdPayload, osdStrideBytes, canvasWidth, canvasHeight,
                pic0Bytes, pic0X, pic0Y, pic0Width, pic0Height, pic0StrideBytes,
                pic1Bytes, pic1X, pic1Y, pic1Width, pic1Height, pic1StrideBytes, pic1Alpha,
                opaqueOsdBackdrop, output, backdropArgb,
            )
        } else {
            compositePic1AndOsd0(
                osdPayload, osdStrideBytes, canvasWidth, canvasHeight,
                pic1Bytes, pic1X, pic1Y, pic1Width, pic1Height, pic1StrideBytes, pic1Alpha,
                opaqueOsdBackdrop, output, allowNative = allowNative, backdropArgb = backdropArgb,
            )
        }
        if (matrixMode != 0 && colorMatrixQ10 != null) {
            applyResolvedColorMatrixScalar(colorMatrixQ10, matrixMode, output)
        }
    }

    /**
     * Resolves real-silicon GFX100S 2.13 post-composition color & brightness modulation into
     * a 9-element Q10 integer matrix (`outMatrix`):
     * - Returns `0` when completely identity/untouched (zero per-pixel overhead).
     * - Returns `1` when diagonal-only (`c00, c11, c22` gain/backlight).
     * - Returns `2` when full 3×3 Q10 RGB color matrix is active.
     */
    private fun resolveColorMatrixAndBacklight(words: LongArray, writeCounts: LongArray, outMatrix: IntArray): Int {
        var m00 = 1024; var m01 = 0; var m02 = 0
        var m10 = 0; var m11 = 1024; var m12 = 0
        var m20 = 0; var m21 = 0; var m22 = 1024
        val matrixWritten = writeCounts[0x4E8 / 4] > 0L && writeCounts[0x4F0 / 4] > 0L && writeCounts[0x4F8 / 4] > 0L
        if (matrixWritten) {
            val w4e8 = words[0x4E8 / 4]
            val w4ec = words[0x4EC / 4]
            val w4f0 = words[0x4F0 / 4]
            val w4f4 = words[0x4F4 / 4]
            val w4f8 = words[0x4F8 / 4]
            val w4fc = words[0x4FC / 4]
            val r00 = signExtend13(w4e8); val r01 = signExtend13(w4e8 ushr 16); val r02 = signExtend13(w4ec)
            val r10 = signExtend13(w4f0); val r11 = signExtend13(w4f0 ushr 16); val r12 = signExtend13(w4f4)
            val r20 = signExtend13(w4f8); val r21 = signExtend13(w4f8 ushr 16); val r22 = signExtend13(w4fc)
            if ((r00 or r01 or r02 or r10 or r11 or r12 or r20 or r21 or r22) != 0) {
                m00 = r00; m01 = r01; m02 = r02
                m10 = r10; m11 = r11; m12 = r12
                m20 = r20; m21 = r21; m22 = r22
            }
        }
        var gainR = 1.0f
        var gainG = 1.0f
        var gainB = 1.0f
        if (writeCounts[0x500 / 4] > 0L && writeCounts[0x504 / 4] > 0L && writeCounts[0x508 / 4] > 0L) {
            val gR = signExtend13(words[0x500 / 4])
            val gG = signExtend13(words[0x504 / 4] ushr 16)
            val gB = signExtend13(words[0x508 / 4])
            if ((gR or gG or gB) != 0) {
                if (gR != 1024) gainR *= (gR / 1024.0f)
                if (gG != 1024) gainG *= (gG / 1024.0f)
                if (gB != 1024) gainB *= (gB / 1024.0f)
            }
        }
        val blPort = if (backlightPortLookedUp) {
            cachedBacklightPort
        } else {
            backlightPortLookedUp = true
            context.ports.find(PanelBacklightPort.PORT_NAME, PanelBacklightPort::class.java).also { cachedBacklightPort = it }
        }
        if (blPort != null) {
            val bl = blPort.backlightState()
            if (displayPort == "display.evf") {
                gainR *= bl.evfGain * (bl.evfGainR / 128.0f)
                gainG *= bl.evfGain * (bl.evfGainG / 128.0f)
                gainB *= bl.evfGain * (bl.evfGainB / 128.0f)
            } else {
                gainR *= bl.lcdGain
                gainG *= bl.lcdGain
                gainB *= bl.lcdGain
                // Physical BOE Rear LCD panel optical white-point (measured from GFX100S 2.13
                // Boot #1 factory LCD COLOR=0 matrix row sums [875, 988, 863] / 1024, where
                // R and B are attenuated ~13-18% below G for BOE LED primaries): restores
                // neutral sRGB white point on host displays while keeping LCD COLOR and LCD
                // COLOR ADJUSTMENT faithful. On Boot #2 (post-OOBE), firmware 0x0126c4e0
                // programs an already-neutral white-point matrix (row sums [1025, 997, 1008]);
                // only apply the BOE inverse white-point when the active 3x3 matrix exhibits
                // BOE's green-dominant white-point attenuation (BUG-V3-8-5).
                val rowSumR = m00 + m01 + m02
                val rowSumG = m10 + m11 + m12
                val rowSumB = m20 + m21 + m22
                val hasBoeGreenWhitePointBias = rowSumG >= 950 &&
                    rowSumR in 750..920 &&
                    rowSumB in 750..920 &&
                    (rowSumG - rowSumR) >= 64 &&
                    (rowSumG - rowSumB) >= 64
                if (matrixWritten && bl.lcdUpdates > 0L && hasBoeGreenWhitePointBias) {
                    gainR *= 1024.0f / 875.0f
                    gainG *= 1024.0f / 988.0f
                    gainB *= 1024.0f / 863.0f
                }
            }
        }
        val c00 = if (gainR == 1.0f) m00 else (m00 * gainR).roundToInt()
        val c01 = if (gainR == 1.0f) m01 else (m01 * gainR).roundToInt()
        val c02 = if (gainR == 1.0f) m02 else (m02 * gainR).roundToInt()
        val c10 = if (gainG == 1.0f) m10 else (m10 * gainG).roundToInt()
        val c11 = if (gainG == 1.0f) m11 else (m11 * gainG).roundToInt()
        val c12 = if (gainG == 1.0f) m12 else (m12 * gainG).roundToInt()
        val c20 = if (gainB == 1.0f) m20 else (m20 * gainB).roundToInt()
        val c21 = if (gainB == 1.0f) m21 else (m21 * gainB).roundToInt()
        val c22 = if (gainB == 1.0f) m22 else (m22 * gainB).roundToInt()

        val isDiagonal = (c01 or c02 or c10 or c12 or c20 or c21) == 0
        if (isDiagonal && c00 == 1024 && c11 == 1024 && c22 == 1024) {
            return 0
        }
        outMatrix[0] = c00; outMatrix[1] = c01; outMatrix[2] = c02
        outMatrix[3] = c10; outMatrix[4] = c11; outMatrix[5] = c12
        outMatrix[6] = c20; outMatrix[7] = c21; outMatrix[8] = c22
        return if (isDiagonal) 1 else 2
    }

    private fun applyResolvedColorMatrixScalar(c: IntArray, matrixMode: Int, output: IntArray) {
        val len = output.size
        if (matrixMode == 1) {
            val c00 = c[0]; val c11 = c[4]; val c22 = c[8]
            for (i in 0 until len) {
                val p = output[i]
                var r = (((p ushr 16) and 0xFF) * c00 + 512) shr 10
                var g = (((p ushr 8) and 0xFF) * c11 + 512) shr 10
                var b = ((p and 0xFF) * c22 + 512) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                output[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return
        }
        val c00 = c[0]; val c01 = c[1]; val c02 = c[2]
        val c10 = c[3]; val c11 = c[4]; val c12 = c[5]
        val c20 = c[6]; val c21 = c[7]; val c22 = c[8]
        for (i in 0 until len) {
            val p = output[i]
            val sr = (p ushr 16) and 0xFF
            val sg = (p ushr 8) and 0xFF
            val sb = p and 0xFF
            var r = (c00 * sr + c01 * sg + c02 * sb + 512) shr 10
            var g = (c10 * sr + c11 * sg + c12 * sb + 512) shr 10
            var b = (c20 * sr + c21 * sg + c22 * sb + 512) shr 10
            if (r < 0) r = 0 else if (r > 255) r = 255
            if (g < 0) g = 0 else if (g > 255) g = 255
            if (b < 0) b = 0 else if (b > 255) b = 255
            output[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun compositeDualPicAndOsd0(
        osdPayload: ByteBuffer?,
        osdStrideBytes: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        pic0Bytes: ByteArray,
        pic0X: Int,
        pic0Y: Int,
        pic0Width: Int,
        pic0Height: Int,
        pic0StrideBytes: Int,
        pic1Bytes: ByteArray?,
        pic1X: Int,
        pic1Y: Int,
        pic1Width: Int,
        pic1Height: Int,
        pic1StrideBytes: Int,
        pic1Alpha: Int,
        opaqueOsdBackdrop: Boolean,
        output: IntArray,
        backdropArgb: Int = BLANK_BLACK_ARGB,
    ) {
        val outWidth = targetWidth
        val outHeight = targetHeight
        val osdBytes = osdPayload?.array()
        val osdBaseOffset = osdPayload?.arrayOffset() ?: 0
        val native1To1 = outWidth == canvasWidth && outHeight == canvasHeight
        val pic0Right = pic0X + pic0Width
        val pic0Bottom = pic0Y + pic0Height
        val pic1Right = pic1X + pic1Width
        val pic1Bottom = pic1Y + pic1Height
        val suppressPics = osdBytes != null && opaqueOsdBackdrop
        val pic0Active = !suppressPics
        val srcPic1 = if (!suppressPics && pic1Alpha > 0) pic1Bytes else null
        val rows = sourceRows
        val cols = sourceCols
        for (row in 0 until outHeight) {
            val sourceRow = if (native1To1) row else rows[row]
            val inPic0Y = pic0Active && sourceRow in pic0Y until pic0Bottom
            val inPic1Y = srcPic1 != null && sourceRow in pic1Y until pic1Bottom
            val pic0RowOffset = if (inPic0Y) (sourceRow - pic0Y) * pic0StrideBytes else 0
            val pic1RowOffset = if (inPic1Y) (sourceRow - pic1Y) * pic1StrideBytes else 0
            val osdRowByteBase = if (osdBytes != null) osdBaseOffset + sourceRow * osdStrideBytes else 0
            val outRowOffset = row * outWidth
            for (column in 0 until outWidth) {
                val sourceColumn = if (native1To1) column else cols[column]
                var osdArgb = 0
                var osdA = 0
                if (osdBytes != null) {
                    osdArgb = Integer.rotateRight(INT_LE_HANDLE.get(osdBytes, osdRowByteBase + (sourceColumn shl 2)) as Int, 8)
                    osdA = osdArgb ushr 24
                    if (osdA == 255) {
                        output[outRowOffset + column] = osdArgb
                        continue
                    }
                }
                var bg = backdropArgb
                if (inPic0Y && sourceColumn in pic0X until pic0Right) {
                    val localX = sourceColumn - pic0X
                    val pairOffset = pic0RowOffset + (localX and 1.inv()) * 2
                    bg = (0xff shl 24) or yuv422UyvyToRgb(pic0Bytes, pairOffset, (localX and 1) != 0)
                }
                if (inPic1Y && sourceColumn in pic1X until pic1Right) {
                    val localX = sourceColumn - pic1X
                    val pairOffset = pic1RowOffset + (localX and 1.inv()) * 2
                    val rgb = yuv422UyvyToRgb(srcPic1, pairOffset, (localX and 1) != 0)
                    bg = if (pic1Alpha >= 255) {
                        (0xff shl 24) or rgb
                    } else {
                        val inv = 255 - pic1Alpha
                        val pr = div255Rounded(((rgb ushr 16) and 0xff) * pic1Alpha + ((bg ushr 16) and 0xff) * inv)
                        val pg = div255Rounded(((rgb ushr 8) and 0xff) * pic1Alpha + ((bg ushr 8) and 0xff) * inv)
                        val pb = div255Rounded((rgb and 0xff) * pic1Alpha + (bg and 0xff) * inv)
                        (0xff shl 24) or (pr shl 16) or (pg shl 8) or pb
                    }
                }
                output[outRowOffset + column] = if (osdA == 0) {
                    bg
                } else {
                    val inv = 255 - osdA
                    val outR = div255Rounded(((osdArgb ushr 16) and 0xff) * osdA + ((bg ushr 16) and 0xff) * inv)
                    val outG = div255Rounded(((osdArgb ushr 8) and 0xff) * osdA + ((bg ushr 8) and 0xff) * inv)
                    val outB = div255Rounded((osdArgb and 0xff) * osdA + (bg and 0xff) * inv)
                    (0xff shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
        }
    }

    internal fun compositePic1AndOsd0(
        osdPayload: ByteBuffer?,
        osdStrideBytes: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        pic1Bytes: ByteArray?,
        pic1X: Int,
        pic1Y: Int,
        pic1Width: Int,
        pic1Height: Int,
        pic1StrideBytes: Int,
        pic1Alpha: Int,
        opaqueOsdBackdrop: Boolean,
        output: IntArray,
        allowNative: Boolean = true,
        backdropArgb: Int = BLANK_BLACK_ARGB,
    ) {
        val outWidth = targetWidth
        val outHeight = targetHeight
        val osdBytes = osdPayload?.array()
        val osdBaseOffset = osdPayload?.arrayOffset() ?: 0
        if (allowNative && backdropArgb == BLANK_BLACK_ARGB && NativeRenderBridge.compositePic1AndOsd0(
                osdBytes = osdBytes,
                osdBaseOffset = osdBaseOffset,
                osdStrideBytes = osdStrideBytes,
                pic1Bytes = pic1Bytes,
                pic1X = pic1X,
                pic1Y = pic1Y,
                pic1Width = pic1Width,
                pic1Height = pic1Height,
                pic1StrideBytes = pic1StrideBytes,
                pic1Alpha = pic1Alpha,
                opaqueOsdBackdrop = opaqueOsdBackdrop,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                sourceRows = sourceRows,
                sourceCols = sourceCols,
                outWidth = outWidth,
                outHeight = outHeight,
                output = output,
            )
        ) {
            return
        }
        val native1To1 = outWidth == canvasWidth && outHeight == canvasHeight
        val pic1Right = pic1X + pic1Width
        val pic1Bottom = pic1Y + pic1Height
        val pic1Active = pic1Bytes != null && pic1Alpha > 0 && !(osdBytes != null && opaqueOsdBackdrop)
        val bgR = (backdropArgb ushr 16) and 0xff
        val bgG = (backdropArgb ushr 8) and 0xff
        val bgB = backdropArgb and 0xff
        val rows = sourceRows
        val cols = sourceCols
        for (row in 0 until outHeight) {
            val sourceRow = if (native1To1) row else rows[row]
            val inPicY = pic1Active && sourceRow in pic1Y until pic1Bottom
            val outRowOffset = row * outWidth
            if (osdBytes == null) {
                if (!inPicY) {
                    output.fill(backdropArgb, outRowOffset, outRowOffset + outWidth)
                    continue
                }
                val picRowOffset = (sourceRow - pic1Y) * pic1StrideBytes
                val srcPic = pic1Bytes
                for (column in 0 until outWidth) {
                    val sourceColumn = if (native1To1) column else cols[column]
                    var bg = backdropArgb
                    if (sourceColumn in pic1X until pic1Right) {
                        val localX = sourceColumn - pic1X
                        val pairOffset = picRowOffset + (localX and 1.inv()) * 2
                        val rgb = yuv422UyvyToRgb(srcPic, pairOffset, (localX and 1) != 0)
                        bg = if (pic1Alpha >= 255) {
                            (0xff shl 24) or rgb
                        } else {
                            val inv = 255 - pic1Alpha
                            val pr = div255Rounded(((rgb ushr 16) and 0xff) * pic1Alpha + bgR * inv)
                            val pg = div255Rounded(((rgb ushr 8) and 0xff) * pic1Alpha + bgG * inv)
                            val pb = div255Rounded((rgb and 0xff) * pic1Alpha + bgB * inv)
                            (0xff shl 24) or (pr shl 16) or (pg shl 8) or pb
                        }
                    }
                    output[outRowOffset + column] = bg
                }
                continue
            }
            val osdRowByteBase = osdBaseOffset + sourceRow * osdStrideBytes
            if (!inPicY) {
                for (column in 0 until outWidth) {
                    val sourceColumn = if (native1To1) column else cols[column]
                    val argb = Integer.rotateRight(INT_LE_HANDLE.get(osdBytes, osdRowByteBase + (sourceColumn shl 2)) as Int, 8)
                    val a = argb ushr 24
                    output[outRowOffset + column] = when (a) {
                        255 -> argb
                        0 -> backdropArgb
                        else -> {
                            val inv = 255 - a
                            val outR = div255Rounded(((argb ushr 16) and 0xff) * a + bgR * inv)
                            val outG = div255Rounded(((argb ushr 8) and 0xff) * a + bgG * inv)
                            val outB = div255Rounded((argb and 0xff) * a + bgB * inv)
                            (0xff shl 24) or (outR shl 16) or (outG shl 8) or outB
                        }
                    }
                }
                continue
            }
            val picRowOffset = (sourceRow - pic1Y) * pic1StrideBytes
            val srcPic = pic1Bytes
            for (column in 0 until outWidth) {
                val sourceColumn = if (native1To1) column else cols[column]
                val argb = Integer.rotateRight(INT_LE_HANDLE.get(osdBytes, osdRowByteBase + (sourceColumn shl 2)) as Int, 8)
                val a = argb ushr 24
                if (a == 255) {
                    output[outRowOffset + column] = argb
                    continue
                }
                var bg = backdropArgb
                if (sourceColumn in pic1X until pic1Right) {
                    val localX = sourceColumn - pic1X
                    val pairOffset = picRowOffset + (localX and 1.inv()) * 2
                    val rgb = yuv422UyvyToRgb(srcPic, pairOffset, (localX and 1) != 0)
                    bg = if (pic1Alpha >= 255) {
                        (0xff shl 24) or rgb
                    } else {
                        val inv = 255 - pic1Alpha
                        val pr = div255Rounded(((rgb ushr 16) and 0xff) * pic1Alpha + bgR * inv)
                        val pg = div255Rounded(((rgb ushr 8) and 0xff) * pic1Alpha + bgG * inv)
                        val pb = div255Rounded((rgb and 0xff) * pic1Alpha + bgB * inv)
                        (0xff shl 24) or (pr shl 16) or (pg shl 8) or pb
                    }
                }
                output[outRowOffset + column] = if (a == 0) {
                    bg
                } else {
                    val inv = 255 - a
                    val outR = div255Rounded(((argb ushr 16) and 0xff) * a + ((bg ushr 16) and 0xff) * inv)
                    val outG = div255Rounded(((argb ushr 8) and 0xff) * a + ((bg ushr 8) and 0xff) * inv)
                    val outB = div255Rounded((argb and 0xff) * a + (bg and 0xff) * inv)
                    (0xff shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
        }
    }

    private fun publish(address: Long) {
        require(validAddress(address)) { "LCD payload outside aligned physical RAM address space" }
        val main = display()
        val memory = requireNotNull(context.ports.find("cpu.memory", DmaMemory::class.java)) { "LCD scanout requires cpu.memory" }
        memory.check(address, byteCount.toLong(), false)
        val payload = raw.clear()
        memory.read(address, payload)
        require(payload.position() == byteCount) { "Incomplete LCD scanout payload" }
        payload.flip()
        if (profile == RGBA) decodeRgba(payload, pixels) else decodeRgb565(payload, pixels)
        main.presentArgb(pixels, targetWidth, targetHeight)
        outputActive = true
        cursor = Cursor(publishedAddress = address, publishedFrames = cursor.publishedFrames + 1)
    }

    private fun decodeRgba(payload: ByteBuffer, output: IntArray) {
        val bytes = payload.array()
        val base = payload.arrayOffset()
        if (NativeRenderBridge.decodeRgba(
                srcBytes = bytes,
                srcBaseOffset = base,
                srcStrideBytes = STRIDE,
                sourceRows = sourceRows,
                sourceCols = sourceCols,
                srcWidth = SOURCE_WIDTH,
                srcHeight = SOURCE_HEIGHT,
                outWidth = targetWidth,
                outHeight = targetHeight,
                output = output,
            )
        ) {
            return
        }
        if (targetWidth == SOURCE_WIDTH && targetHeight == SOURCE_HEIGHT) {
            for (index in 0 until SOURCE_WIDTH * SOURCE_HEIGHT) {
                val rgba = INT_LE_HANDLE.get(bytes, base + (index shl 2)) as Int
                output[index] = Integer.rotateRight(rgba, 8)
            }
            return
        }
        val rows = sourceRows
        val cols = sourceCols
        for (row in 0 until targetHeight) {
            val rowBase = base + rows[row] * STRIDE
            val outBase = row * targetWidth
            for (column in 0 until targetWidth) {
                val rgba = INT_LE_HANDLE.get(bytes, rowBase + (cols[column] shl 2)) as Int
                output[outBase + column] = Integer.rotateRight(rgba, 8)
            }
        }
    }

    private fun decodeRgb565(payload: ByteBuffer, output: IntArray) {
        for (index in output.indices) {
            val packed = payload.short.toInt() and 0xffff
            val red = packed ushr 11
            val green = (packed ushr 5) and 63
            val blue = packed and 31
            output[index] = (0xff shl 24) or (((red shl 3) or (red ushr 2)) shl 16) or
                (((green shl 2) or (green ushr 4)) shl 8) or (blue shl 3) or (blue ushr 2)
        }
    }

    fun snapshot() = buildMap<String, Any?> {
        put("upper", cursor.upper)
        put("publishedAddress", cursor.publishedAddress)
        if (profile == RGBA) put("pendingSlot", cursor.pendingSlot)
        if (frameLimit > 1) put("publishedFrames", cursor.publishedFrames)
    }

    fun state() = snapshot() + buildMap<String, Any?> {
        if (continuous) put("outputActive", outputActive)
        put("confidence", "HYPOTHESIS")
    }

    fun parse(raw: Any?, savedLimit: Int, savedProfile: String): Cursor {
        val saved = VglibSnapshotCodec.requireMap(raw, "scanout")
        val firstFrameKeys = setOf("upper", "publishedAddress")
        require(saved.keys == firstFrameKeys + listOfNotNull("publishedFrames".takeIf { savedLimit > 1 },
            "pendingSlot".takeIf { savedProfile == RGBA }))
        val pending = savedProfile == RGBA && VglibSnapshotCodec.requireBoolean(saved["pendingSlot"], "pendingSlot")
        val upper = saved["upper"]?.let { VglibSnapshotCodec.requireLong(it, "upper") }
        val address = saved["publishedAddress"]?.let { VglibSnapshotCodec.requireLong(it, "publishedAddress") }
        val maxCount = if (continuous) Int.MAX_VALUE else savedLimit
        val count = if (savedLimit > 1) VglibSnapshotCodec.requireInt(saved["publishedFrames"], "publishedFrames", 0..maxCount)
            else if (address == null) 0 else 1
        require((count == 0) == (address == null))
        require(upper == null || (upper in 0..3 && (continuous || count < savedLimit)))
        require(address == null || if (savedProfile == PIC_YUV_OSD0) validCompositeAddress(address) else validAddress(address))
        require(!pending || (upper == null && (continuous || count < savedLimit)))
        return Cursor(upper, address, count, pending)
    }

    fun restore(saved: Cursor) {
        cursor = saved
        outputActive = saved.publishedFrames > 0
        blankReason = null
        activeGateBlankCount = 0L
        sawOobeOpaqueOsd = false
    }

    fun checkBank(saved: Cursor, bank: VglibSnapshotCodec.ParsedLcdcFunctionalState, savedProfile: String = profile) {
        if (savedProfile == PIC_YUV_OSD0) {
            if (!continuous && saved.publishedFrames > 0) {
                require(bank.writeCounts[0x600 / 4] >= saved.publishedFrames)
            }
            return
        }
        val upperIndex = 0x40 / 4
        val lowIndex = 0x44 / 4
        if (saved.upper != null) require(bank.writeCounts[upperIndex] > 0 && (bank.words[upperIndex] and 3) == saved.upper)
        val pairs = saved.publishedFrames + if (saved.pendingSlot) 1 else 0
        if (saved.pendingSlot) require(bank.words[upperIndex] == 0L && validAddress(bank.words[lowIndex]))
        require(bank.writeCounts[upperIndex] >= pairs + (if (saved.upper != null) 1 else 0) &&
            bank.writeCounts[lowIndex] >= pairs)
    }
}
