package com.github.nyabanban.fuji.emulator.components.jpeg

import com.github.nyabanban.fuji.emulator.api.IspStillReadoutPort
import com.github.nyabanban.fuji.emulator.api.*
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.util.logging.Logger

/**
 * Hardware JPEG codec peripheral (`soc-jpeg`):
 * - Decoder (`0xFFFA1000..0xFFFA1128`, size=0x12C, GIC IRQ 136): register layout & handshake derived
 *   from GXUP0004.DAT section4.bin (011FE788..011FEBD8) and section5.bin (ISR 021E0F0C..021E1430).
 * - Opt-in Encoder (`0xFFFA0000..0xFFFA0130`, size=0x134, GIC IRQ 135, `soc-jpeg-codec-experimental-v2`):
 *   22 aligned 32-bit registers derived from GXUP0004.DAT section4.bin (011FEBE0..011FF878) and
 *   section5.bin (open 021E15FC, config 021E173C, start 021E16D8, ISR 021E1434, RECC 0233C850).
 *   Reset/IRQ timing & DMA completion delay remain HYPOTHESIS.
 */
class SocJpegFactory : ComponentFactory {
    companion object {
        private val LOGGER = Logger.getLogger(SocJpegFactory::class.java.name)
        const val PROFILE = "soc-jpeg-decoder-experimental-v1"
        const val CODEC_PROFILE = "soc-jpeg-codec-experimental-v2"
        const val PORT_ID = "soc-jpeg.registers"
        const val DECODER_V2_PORT_ID = "soc-jpeg.decoder-v2"
        const val ENCODER_PORT_ID = "soc-jpeg.encoder"
        const val BASE_ADDRESS = 0xFFFA1000L
        const val WINDOW_SIZE = 0x12CL
        const val ENCODER_BASE_ADDRESS = 0xFFFA0000L
        const val ENCODER_WINDOW_SIZE = 0x134L
        const val IRQ_LINE = 136
        const val ENCODER_IRQ_LINE = 135
        const val DEFAULT_DELAY_TICKS = 64L
        const val MAX_JPEG_BYTES = 128L * 1024L * 1024L
        // 102MP GFX100S (11648x8736) with 15840-pixel hardware stride requires 276,748,096 B (> 256 MiB); allow 512 MiB.
        const val MAX_SOURCE_YC_BYTES = 512L * 1024L * 1024L
        private const val DMA_CHUNK_BYTES = 8 * 1024 * 1024

        // Decoder registers (0xFFFA1000..0xFFFA1128)
        private const val OFF_CTRL = 0x000L
        private const val OFF_INT_STATUS = 0x004L
        private const val OFF_INT_MASK = 0x008L
        private const val OFF_ERR_STAT1 = 0x010L
        private const val OFF_ERR_STAT2 = 0x014L
        private const val OFF_STAT_18 = 0x018L
        private const val OFF_STAT_1C = 0x01CL
        private const val OFF_DOWNSCALE = 0x0A0L
        private const val OFF_MEM_YC_ADDR = 0x100L
        private const val OFF_MEM_YC_SIZE = 0x104L
        private const val OFF_DMA_WRITE_PTR = 0x108L
        private const val OFF_MEM_JPEG_ADDR = 0x10CL
        private const val OFF_MEM_JPEG_SIZE = 0x110L
        private const val OFF_DMA_READ_PTR = 0x114L
        private const val OFF_DMA_CTRL = 0x118L
        private const val OFF_MODE = 0x11CL
        private const val OFF_CROP_EN = 0x120L
        private const val OFF_CROP_X = 0x124L
        private const val OFF_CROP_Y = 0x128L

        // Encoder registers (0xFFFA0000..0xFFFA0130, 22 DAT-verified offsets)
        private const val ENC_CTRL = 0x000L
        private const val ENC_FORMAT_CTRL = 0x00CL
        private const val ENC_INT_STATUS = 0x024L
        private const val ENC_INT_MASK = 0x028L
        private const val ENC_SUBSAMPLE = 0x040L
        private const val ENC_SRC_SIZE = 0x044L
        private const val ENC_QSCALE = 0x048L
        private const val ENC_DRI = 0x080L
        private const val ENC_QTBL_CTRL = 0x084L
        private const val ENC_QTBL_DATA = 0x08CL
        private const val ENC_OUT_BYTES = 0x094L
        private const val ENC_MARKER_CTRL = 0x0B0L
        private const val ENC_MISC = 0x0F8L
        private const val ENC_SRC_YC_ADDR = 0x100L
        private const val ENC_DST_JPEG_ADDR = 0x10CL
        private const val ENC_DST_JPEG_MAX = 0x110L
        private const val ENC_DMA_STAT = 0x114L
        private const val ENC_DMA_CTRL = 0x118L
        private const val ENC_MODE = 0x11CL
        private const val ENC_SWAP_CTRL = 0x120L
        private const val ENC_WINDOW_DIM = 0x12CL
        private const val ENC_LINE_PITCH = 0x130L

        private const val CTRL_START = 0x00000001L
        private const val CTRL_RESET = 0x00000100L
        private const val INT_DECODE_DONE = 0x00000010L
        private const val INT_ENCODE_DONE = 0x00000010L
        private const val INT_DMA_STOP_READY = 0x00010000L
        private const val INT_ERROR = 0x00020000L
        private const val INT_CHUNK_READY = 0x01000000L
        private const val IRQ_MASKABLE_BITS = INT_DECODE_DONE or INT_ERROR or INT_CHUNK_READY
        private const val ENC_IRQ_MASKABLE_BITS = INT_ENCODE_DONE or 0x00FF_0000L

        private val VALID_OFFSETS = listOf(
            OFF_CTRL,
            OFF_INT_STATUS,
            OFF_INT_MASK,
            OFF_ERR_STAT1,
            OFF_ERR_STAT2,
            OFF_STAT_18,
            OFF_STAT_1C,
            OFF_DOWNSCALE,
            OFF_MEM_YC_ADDR,
            OFF_MEM_YC_SIZE,
            OFF_DMA_WRITE_PTR,
            OFF_MEM_JPEG_ADDR,
            OFF_MEM_JPEG_SIZE,
            OFF_DMA_READ_PTR,
            OFF_DMA_CTRL,
            OFF_MODE,
            OFF_CROP_EN,
            OFF_CROP_X,
            OFF_CROP_Y,
        )

        val ENCODER_VALID_OFFSETS = listOf(
            ENC_CTRL,
            ENC_FORMAT_CTRL,
            ENC_INT_STATUS,
            ENC_INT_MASK,
            ENC_SUBSAMPLE,
            ENC_SRC_SIZE,
            ENC_QSCALE,
            ENC_DRI,
            ENC_QTBL_CTRL,
            ENC_QTBL_DATA,
            ENC_OUT_BYTES,
            ENC_MARKER_CTRL,
            ENC_MISC,
            ENC_SRC_YC_ADDR,
            ENC_DST_JPEG_ADDR,
            ENC_DST_JPEG_MAX,
            ENC_DMA_STAT,
            ENC_DMA_CTRL,
            ENC_MODE,
            ENC_SWAP_CTRL,
            ENC_WINDOW_DIM,
            ENC_LINE_PITCH,
        )

        private val OFFSET_KEYS = VALID_OFFSETS.associateWith { "0x%03x".format(it) }
        private val ENCODER_OFFSET_KEYS = ENCODER_VALID_OFFSETS.associateWith { "0x%03x".format(it) }

        val DESCRIPTOR = ComponentDescriptor(
            id = "soc-jpeg",
            version = "0.0.1",
            settings = listOf(
                Setting("profile", ValueKind.TEXT, "disabled", choices = listOf("disabled", PROFILE, CODEC_PROFILE), live = false),
                Setting("completionDelayTicks", ValueKind.INTEGER, DEFAULT_DELAY_TICKS, minimum = 1.0, maximum = 1_000_000.0, live = false),
            ),
        )
    }

    override val descriptor: ComponentDescriptor = DESCRIPTOR

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        SocJpegComponent(context, config)

    private class SocJpegComponent(
        context: ComponentContext,
        config: Map<String, Any>,
    ) : ScalarComponent("soc-jpeg", context, config), StatefulComponent {
        private val profile = config.getValue("profile") as String
        private val enabled = profile == PROFILE || profile == CODEC_PROFILE
        private val encoderEnabled = profile == CODEC_PROFILE
        private val decoderPortId = if (encoderEnabled) DECODER_V2_PORT_ID else PORT_ID
        private val completionDelayTicks = (config.getValue("completionDelayTicks") as Number).toLong()

        private var started = false
        private var closed = false
        private var sink: InterruptSink? = null
        private var memory: DmaMemory? = null

        // Decoder state (0xFFFA1000, IRQ 136)
        private val regs = LinkedHashMap<Long, Long>().apply {
            resetDecoderRegisters()
        }
        private var pendingTick: Long? = null
        private var irqAsserted = false
        private var decodeCount = 0L
        private var errorCount = 0L
        private var lastBytesWritten = 0L
        private var lastBytesRead = 0L

        // Encoder state (0xFFFA0000, IRQ 135; active only in CODEC_PROFILE)
        private val encRegs = LinkedHashMap<Long, Long>().apply {
            resetEncoderRegisters()
        }
        private var encoderPendingTick: Long? = null
        private var encoderIrqAsserted = false
        private var encodeCount = 0L
        private var encoderErrorCount = 0L
        private var lastEncoderError: String? = null
        // A genuine retry is the next submission for the same descriptor and identical visible
        // source bytes, with changed quantization. Unrelated successes cannot clear an error.
        private data class Overflow(val tick: Long, val identity: List<Long>, val quantization: List<Int>, val attempts: Long)
        private var pendingOverflow: Overflow? = null
        private var recoveredOverflows = 0L
        private var lastEncoderRecovery: String? = null
        private var lastEncodedBytes = 0L
        private var lastSourceBytes = 0L
        private var qtblWriteCursor = 0
        private val lumaQtblBytes = ByteArray(64)
        private var lumaQtblWordsLoaded = 0
        private var loggedMissingExposureFallback = false
        private var loggedZeroSourceFallback = false
        private var cachedOpticalFrame: JpegUyvyCodec.OpticalBgrFrame? = null
        private var cachedOpticalSensor: SensorPort? = null
        private var cachedOpticalTick: Long = Long.MIN_VALUE
        private var cachedOpticalKey: Long = 0L

        private val OPTICAL_CACHE_TTL_TICKS = 81_000_000L // 1.5 vSec at 54 MHz virtual clock

        private val decoderDevice = object : TickAwareMmioDevice {
            override val clockOrder: Int = 86
            override val clockIndex: Int = 0

            override fun nextDeadline(): Long? = if (enabled && !closed) pendingTick else null
            override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE

            override fun read(offset: Long, size: Int): Long {
                check(!closed) { "soc-jpeg closed" }
                validateDecoderAccess(offset, size)
                val value = regs.getValue(offset)
                return if (offset == OFF_DMA_CTRL) {
                    value and 0x4L.inv()
                } else {
                    value
                }
            }

            override fun write(offset: Long, size: Int, value: Long) {
                check(!closed) { "soc-jpeg closed" }
                validateDecoderAccess(offset, size)
                require(value in 0L..0xFFFF_FFFFL) { "soc-jpeg register value out of u32 range: $value" }
                val u32 = value and 0xFFFF_FFFFL
                when (offset) {
                    OFF_CTRL -> {
                        if ((u32 and CTRL_RESET) != 0L) {
                            softResetDecoder()
                        } else {
                            regs[OFF_CTRL] = u32
                            if ((u32 and CTRL_START) != 0L &&
                                regs.getValue(OFF_MEM_JPEG_ADDR) != 0L &&
                                regs.getValue(OFF_MEM_JPEG_SIZE) > 0L &&
                                regs.getValue(OFF_MEM_YC_ADDR) != 0L
                            ) {
                                pendingTick = context.clock.ticks() + completionDelayTicks
                            }
                        }
                    }
                    OFF_INT_STATUS -> {
                        regs[OFF_INT_STATUS] = regs.getValue(OFF_INT_STATUS) and u32.inv()
                        updateDecoderIrq()
                    }
                    OFF_INT_MASK -> {
                        regs[OFF_INT_MASK] = u32
                        updateDecoderIrq()
                    }
                    OFF_ERR_STAT1, OFF_ERR_STAT2 -> {
                        regs[offset] = regs.getValue(offset) and u32.inv()
                    }
                    OFF_STAT_18, OFF_STAT_1C, OFF_DMA_WRITE_PTR, OFF_DMA_READ_PTR -> {
                        regs[offset] = u32
                    }
                    OFF_DMA_CTRL -> {
                        regs[OFF_DMA_CTRL] = u32
                        if ((u32 and 1L) != 0L) {
                            pendingTick = null
                            regs[OFF_CTRL] = regs.getValue(OFF_CTRL) and CTRL_START.inv()
                            regs[OFF_INT_STATUS] = regs.getValue(OFF_INT_STATUS) or INT_DMA_STOP_READY
                            updateDecoderIrq()
                        }
                    }
                    else -> {
                        regs[offset] = u32
                    }
                }
            }

            override fun beforeInstruction(tick: Long) {
                if (!enabled || closed) return
                val due = pendingTick ?: return
                if (tick < due) return
                pendingTick = null
                regs[OFF_CTRL] = regs.getValue(OFF_CTRL) and CTRL_START.inv()
                executeDecode()
            }
        }

        private val encoderDevice = object : TickAwareMmioDevice {
            override val clockOrder: Int = 85
            override val clockIndex: Int = 0

            override fun nextDeadline(): Long? = if (encoderEnabled && !closed) encoderPendingTick else null
            override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE

            override fun read(offset: Long, size: Int): Long {
                check(!closed) { "soc-jpeg closed" }
                validateEncoderAccess(offset, size)
                val value = encRegs.getValue(offset)
                return if (offset == ENC_DMA_CTRL) {
                    value and 0x4L.inv()
                } else {
                    value
                }
            }

            override fun write(offset: Long, size: Int, value: Long) {
                check(!closed) { "soc-jpeg closed" }
                validateEncoderAccess(offset, size)
                require(value in 0L..0xFFFF_FFFFL) { "soc-jpeg encoder register value out of u32 range: $value" }
                val u32 = value and 0xFFFF_FFFFL
                when (offset) {
                    ENC_CTRL -> {
                        if ((u32 and CTRL_RESET) != 0L) {
                            softResetEncoder()
                        } else {
                            encRegs[ENC_CTRL] = u32
                            if ((u32 and CTRL_START) != 0L) {
                                encoderPendingTick = context.clock.ticks() + completionDelayTicks
                            }
                        }
                    }
                    ENC_INT_STATUS -> {
                        encRegs[ENC_INT_STATUS] = encRegs.getValue(ENC_INT_STATUS) and u32.inv()
                        updateEncoderIrq()
                    }
                    ENC_INT_MASK -> {
                        encRegs[ENC_INT_MASK] = u32
                        updateEncoderIrq()
                    }
                    ENC_QTBL_CTRL -> {
                        encRegs[ENC_QTBL_CTRL] = u32
                        if ((u32 and 0x100L) != 0L) {
                            qtblWriteCursor = 0
                            if ((u32 and 1L) == 0L) {
                                lumaQtblWordsLoaded = 0
                            }
                        }
                    }
                    ENC_QTBL_DATA -> {
                        encRegs[ENC_QTBL_DATA] = u32
                        val ctrl = encRegs.getValue(ENC_QTBL_CTRL)
                        if ((ctrl and 0x100L) != 0L && qtblWriteCursor < 32) {
                            if ((ctrl and 1L) == 0L && qtblWriteCursor < 16) {
                                val base = qtblWriteCursor * 4
                                lumaQtblBytes[base] = ((u32 ushr 24) and 0xFFL).toByte()
                                lumaQtblBytes[base + 1] = ((u32 ushr 16) and 0xFFL).toByte()
                                lumaQtblBytes[base + 2] = ((u32 ushr 8) and 0xFFL).toByte()
                                lumaQtblBytes[base + 3] = (u32 and 0xFFL).toByte()
                                lumaQtblWordsLoaded = qtblWriteCursor + 1
                            }
                            qtblWriteCursor++
                        }
                    }
                    ENC_DMA_CTRL -> {
                        encRegs[ENC_DMA_CTRL] = u32
                        if ((u32 and 3L) != 0L) {
                            encoderPendingTick = null
                            encRegs[ENC_CTRL] = encRegs.getValue(ENC_CTRL) and CTRL_START.inv()
                            encRegs[ENC_INT_STATUS] = encRegs.getValue(ENC_INT_STATUS) or INT_DMA_STOP_READY
                            updateEncoderIrq()
                        }
                    }
                    else -> {
                        encRegs[offset] = u32
                    }
                }
            }

            override fun beforeInstruction(tick: Long) {
                if (!encoderEnabled || closed) return
                val due = encoderPendingTick ?: return
                if (tick < due) return
                encoderPendingTick = null
                encRegs[ENC_CTRL] = encRegs.getValue(ENC_CTRL) and CTRL_START.inv()
                executeEncode()
            }
        }

        init {
            require(profile == "disabled" || profile == PROFILE || profile == CODEC_PROFILE) {
                "Unknown soc-jpeg profile: $profile"
            }
            if (enabled) {
                require(context.ports.find(decoderPortId, MmioDevice::class.java) == null) { "Duplicate port $decoderPortId" }
                context.ports.register(id, decoderPortId, MmioDevice::class.java, decoderDevice)
            }
            if (encoderEnabled) {
                require(context.ports.find(ENCODER_PORT_ID, MmioDevice::class.java) == null) { "Duplicate port $ENCODER_PORT_ID" }
                context.ports.register(id, ENCODER_PORT_ID, MmioDevice::class.java, encoderDevice)
            }
        }

        private fun LinkedHashMap<Long, Long>.resetDecoderRegisters() {
            for (off in VALID_OFFSETS) {
                this[off] = if (off == OFF_INT_MASK) 0xFFFF_FFFFL else 0L
            }
        }

        private fun LinkedHashMap<Long, Long>.resetEncoderRegisters() {
            for (off in ENCODER_VALID_OFFSETS) {
                this[off] = if (off == ENC_INT_MASK) 0xFFFF_FFFFL else 0L
            }
        }

        private fun softResetDecoder() {
            regs.resetDecoderRegisters()
            pendingTick = null
            lastBytesWritten = 0L
            lastBytesRead = 0L
            updateDecoderIrq()
        }

        private fun softResetEncoder() {
            encRegs.resetEncoderRegisters()
            encoderPendingTick = null
            lastEncodedBytes = 0L
            lastSourceBytes = 0L
            qtblWriteCursor = 0
            lumaQtblWordsLoaded = 0
            updateEncoderIrq()
        }

        private fun validateDecoderAccess(offset: Long, size: Int) {
            require(size == 4 && offset % 4L == 0L && offset in regs) {
                "Invalid soc-jpeg MMIO access offset=0x${offset.toString(16)} size=$size"
            }
        }

        private fun validateEncoderAccess(offset: Long, size: Int) {
            require(size == 4 && offset % 4L == 0L && offset in encRegs) {
                "Invalid soc-jpeg encoder MMIO access offset=0x${offset.toString(16)} size=$size"
            }
        }

        private fun activeDecoderIrq(): Boolean {
            val status = regs.getValue(OFF_INT_STATUS)
            val mask = regs.getValue(OFF_INT_MASK)
            return (status and mask.inv() and IRQ_MASKABLE_BITS) != 0L
        }

        private fun activeEncoderIrq(): Boolean {
            val status = encRegs.getValue(ENC_INT_STATUS)
            val mask = encRegs.getValue(ENC_INT_MASK)
            return (status and mask.inv() and ENC_IRQ_MASKABLE_BITS) != 0L
        }

        private fun updateDecoderIrq() {
            val high = activeDecoderIrq()
            if (high != irqAsserted) {
                irqAsserted = high
                sink?.setLine(IRQ_LINE, high)
            }
        }

        private fun updateEncoderIrq() {
            val high = activeEncoderIrq()
            if (high != encoderIrqAsserted) {
                encoderIrqAsserted = high
                sink?.setLine(ENCODER_IRQ_LINE, high)
            }
        }

        private fun failDecode() {
            errorCount++
            regs[OFF_INT_STATUS] = regs.getValue(OFF_INT_STATUS) or INT_ERROR
            updateDecoderIrq()
        }

        private fun failEncode(reason: String, overflowBytes: Int? = null) {
            encoderErrorCount++
            lastEncoderError = buildString {
                append("tick=${context.clock.ticks()} $reason")
                for ((name, offset) in listOf("src" to ENC_SRC_YC_ADDR, "dst" to ENC_DST_JPEG_ADDR,
                    "dstMax" to ENC_DST_JPEG_MAX, "srcSize" to ENC_SRC_SIZE, "window" to ENC_WINDOW_DIM,
                    "pitch" to ENC_LINE_PITCH, "qscale" to ENC_QSCALE)) {
                    append(" $name=0x${encRegs.getValue(offset).toString(16)}")
                }
                if (overflowBytes != null) append(" requiredBytes=$overflowBytes")
            }
            LOGGER.warning("[JPEG-ENCODE] $lastEncoderError")
            if (overflowBytes != null) {
                encRegs[ENC_OUT_BYTES] = overflowBytes.toLong() and 0xFFFF_FFFFL
                encRegs[ENC_INT_STATUS] = encRegs.getValue(ENC_INT_STATUS) or INT_ENCODE_DONE or INT_ERROR
            } else {
                encRegs[ENC_OUT_BYTES] = 0L
                encRegs[ENC_INT_STATUS] = encRegs.getValue(ENC_INT_STATUS) or INT_ERROR
            }
            updateEncoderIrq()
        }

        private fun isAllZeroSegment(seg: MemorySegment, len: Long): Boolean {
            if (len <= 0L) return true
            if (len >= 8L) {
                val lastWord = (len - 8L) and 7L.inv()
                if (seg.get(ValueLayout.JAVA_LONG_UNALIGNED, 0L) != 0L) return false
                if (seg.get(ValueLayout.JAVA_LONG_UNALIGNED, (len ushr 1) and 7L.inv()) != 0L) return false
                if (seg.get(ValueLayout.JAVA_LONG_UNALIGNED, (len ushr 2) and 7L.inv()) != 0L) return false
                if (seg.get(ValueLayout.JAVA_LONG_UNALIGNED, ((len * 3L) ushr 2) and 7L.inv()) != 0L) return false
                if (seg.get(ValueLayout.JAVA_LONG_UNALIGNED, lastWord) != 0L) return false
            }
            var off = 0L
            val alignedEnd = len and 7L.inv()
            while (off < alignedEnd) {
                if (seg.get(ValueLayout.JAVA_LONG_UNALIGNED, off) != 0L) return false
                off += 8L
            }
            while (off < len) {
                if (seg.get(ValueLayout.JAVA_BYTE, off) != 0.toByte()) return false
                off++
            }
            return true
        }

        private fun dmaReadChunked(dma: DmaMemory, address: Long, size: Int): ByteArray {
            val bytes = ByteArray(size)
            val direct = (dma as? DirectGuestRamPort)?.sliceDirect(address, size.toLong(), write = false)
            if (direct != null) {
                MemorySegment.copy(direct, ValueLayout.JAVA_BYTE, 0L, bytes, 0, size)
                return bytes
            }
            var done = 0
            while (done < size) {
                val chunk = minOf(DMA_CHUNK_BYTES, size - done)
                val slice = ByteBuffer.wrap(bytes, done, chunk)
                dma.read(address + done.toLong(), slice)
                require(slice.remaining() == 0) { "Incomplete soc-jpeg DMA read" }
                done += chunk
            }
            return bytes
        }

        private fun dmaWriteChunked(dma: DmaMemory, address: Long, bytes: ByteArray) {
            if (dma is DirectGuestRamPort) {
                val direct = dma.sliceDirect(address, bytes.size.toLong(), write = true)
                if (direct != null) {
                    MemorySegment.copy(bytes, 0, direct, ValueLayout.JAVA_BYTE, 0L, bytes.size)
                    dma.notifyDirectWrite(address, bytes.size.toLong())
                    return
                }
            }
            var done = 0
            while (done < bytes.size) {
                val chunk = minOf(DMA_CHUNK_BYTES, bytes.size - done)
                val slice = ByteBuffer.wrap(bytes, done, chunk)
                dma.write(address + done.toLong(), slice)
                require(slice.remaining() == 0) { "Incomplete soc-jpeg DMA write" }
                done += chunk
            }
        }

        private fun executeDecode() {
            val mode = regs.getValue(OFF_MODE) and 0xFL
            if (mode == 1L) {
                failDecode()
                return
            }

            val memJpegAddr = regs.getValue(OFF_MEM_JPEG_ADDR)
            val memJpegSize = regs.getValue(OFF_MEM_JPEG_SIZE)
            val memYcAddr = regs.getValue(OFF_MEM_YC_ADDR)
            val memYcSize = regs.getValue(OFF_MEM_YC_SIZE)
            if (memJpegAddr == 0L || memJpegSize <= 0L || memJpegSize > MAX_JPEG_BYTES ||
                memJpegAddr > 0x1_0000_0000L - memJpegSize || memYcAddr == 0L
            ) {
                failDecode()
                return
            }

            val dma = memory ?: checkNotNull(context.ports.find("cpu.memory", DmaMemory::class.java)) {
                "soc-jpeg decode requires cpu.memory"
            }.also { memory = it }
            dma.check(memJpegAddr, memJpegSize, false)

            val downscaleShift = (regs.getValue(OFF_DOWNSCALE) and 0x3L).toInt()
            val cropEnabled = (regs.getValue(OFF_CROP_EN) and 1L) != 0L
            val cropX = regs.getValue(OFF_CROP_X)
            val cropY = regs.getValue(OFF_CROP_Y)
            val cropXStart = (cropX and 0xFFFFL).toInt()
            val cropXEnd = ((cropX ushr 16) and 0xFFFFL).toInt()
            val cropYStart = (cropY and 0xFFFFL).toInt()
            val cropYEnd = ((cropY ushr 16) and 0xFFFFL).toInt()

            if (dma is DirectGuestRamPort) {
                when (val directRes = JpegUyvyCodec.decodeDirectToGuestRam(
                    ram = dma,
                    memJpegAddr = memJpegAddr,
                    memJpegSize = memJpegSize.toInt(),
                    memYcAddr = memYcAddr,
                    memYcSize = memYcSize,
                    downscaleShift = downscaleShift,
                    cropEnabled = cropEnabled,
                    cropXStart = cropXStart,
                    cropXEnd = cropXEnd,
                    cropYStart = cropYStart,
                    cropYEnd = cropYEnd,
                )) {
                    is JpegUyvyCodec.DecodeDirectResult.Error -> {
                        failDecode()
                        return
                    }
                    is JpegUyvyCodec.DecodeDirectResult.Success -> {
                        val outSize = directRes.bytesWritten.toLong()
                        lastBytesWritten = outSize
                        lastBytesRead = memJpegSize
                        decodeCount++
                        regs[OFF_DMA_WRITE_PTR] = (memYcAddr + outSize) and 0xFFFF_FFFFL
                        regs[OFF_DMA_READ_PTR] = (memJpegAddr + memJpegSize) and 0xFFFF_FFFFL
                        regs[OFF_INT_STATUS] = regs.getValue(OFF_INT_STATUS) or INT_DECODE_DONE
                        updateDecoderIrq()
                        return
                    }
                    null -> {
                        // Fall through cleanly to chunked DmaMemory decode path.
                    }
                }
            }

            val jpegBytes = dmaReadChunked(dma, memJpegAddr, memJpegSize.toInt())

            when (val res = JpegUyvyCodec.decodeToMemYc(
                jpegBytes = jpegBytes,
                memYcSize = memYcSize,
                downscaleShift = downscaleShift,
                cropEnabled = cropEnabled,
                cropXStart = cropXStart,
                cropXEnd = cropXEnd,
                cropYStart = cropYStart,
                cropYEnd = cropYEnd,
            )) {
                is JpegUyvyCodec.DecodeResult.Error -> {
                    failDecode()
                }
                is JpegUyvyCodec.DecodeResult.Success -> {
                    val outSize = res.uyvyBytes.size.toLong()
                    if (memYcAddr > 0x1_0000_0000L - outSize) {
                        failDecode()
                        return
                    }
                    dma.check(memYcAddr, outSize, true)
                    dmaWriteChunked(dma, memYcAddr, res.uyvyBytes)
                    lastBytesWritten = outSize
                    lastBytesRead = memJpegSize
                    decodeCount++
                    regs[OFF_DMA_WRITE_PTR] = (memYcAddr + outSize) and 0xFFFF_FFFFL
                    regs[OFF_DMA_READ_PTR] = (memJpegAddr + memJpegSize) and 0xFFFF_FFFFL
                    regs[OFF_INT_STATUS] = regs.getValue(OFF_INT_STATUS) or INT_DECODE_DONE
                    updateDecoderIrq()
                }
            }
        }

        private fun tableQuality(sum: Int): Float? {
            if (sum == 0) return null
            val scale = (sum / 64.0) * (100.0 / 50.5)
            val quality = if (scale <= 100.0) (200.0 - scale) / 200.0 else 50.0 / scale
            return quality.toFloat().coerceIn(0.05f, 0.98f)
        }

        private fun savedQuality(quant: List<Int>): Float =
            (if (quant.size == 65) tableQuality(quant.sum() - quant.first()) else null)
                ?: JpegUyvyCodec.qualityFromQscale(quant.first())

        private fun executeEncode() {
            val previous = pendingOverflow
            pendingOverflow = null
            val axiHigh = context.ports.find("soc-isp.axi-high34", MmioDevice::class.java)
            val srcHi = axiHigh?.let { runCatching { it.read(0x40L, 4) and 3L }.getOrNull() } ?: 0L
            val dstHi = axiHigh?.let { runCatching { it.read(0x44L, 4) and 3L }.getOrNull() } ?: 0L
            val srcAddr = encRegs.getValue(ENC_SRC_YC_ADDR)
            val dstAddr = encRegs.getValue(ENC_DST_JPEG_ADDR)
            val dstMax = encRegs.getValue(ENC_DST_JPEG_MAX)
            val srcSizeWord = encRegs.getValue(ENC_SRC_SIZE)
            val winDimWord = encRegs.getValue(ENC_WINDOW_DIM)
            val winW = (winDimWord and 0xFFFFL).toInt()
            val winH = ((winDimWord ushr 16) and 0xFFFFL).toInt()
            val srcW = (srcSizeWord and 0xFFFFL).toInt()
            val srcH = ((srcSizeWord ushr 16) and 0xFFFFL).toInt()
            val width = if (winW > 0 && winH > 0) winW else srcW
            val height = if (winW > 0 && winH > 0) winH else srcH
            val pitchReg = (encRegs.getValue(ENC_LINE_PITCH) and 0xFFFFL).toInt()
            // DAT 0x011ff814 / 0x021e1800 writes pixel stride (e.g. 160 for 160x120); legacy unit tests pass byte stride (width*2).
            val linePitch = when {
                pitchReg <= 0 -> width * 2
                pitchReg in width until width * 2 -> pitchReg * 2
                else -> pitchReg
            }

            if (srcAddr == 0L || dstAddr == 0L || width <= 0 || height <= 0 || linePitch < width * 2) {
                failEncode("invalid source, destination or geometry")
                return
            }
            val srcBytesNeeded = (height - 1).toLong() * linePitch.toLong() + width.toLong() * 2L
            if (srcBytesNeeded <= 0L || srcBytesNeeded > MAX_SOURCE_YC_BYTES ||
                (srcHi == 0L && srcAddr > 0x1_0000_0000L - srcBytesNeeded) ||
                (dstMax > 0L && (dstMax > MAX_JPEG_BYTES || dstAddr > 0x1_0000_0000L - dstMax))
            ) {
                failEncode("transfer exceeds the address or size limit")
                return
            }

            val dma = memory ?: checkNotNull(context.ports.find("cpu.memory", DmaMemory::class.java)) {
                "soc-jpeg encode requires cpu.memory"
            }.also { memory = it }
            val qscale = (encRegs.getValue(ENC_QSCALE) and 0xFFL).toInt()
            val qtblQualityOverride: Float? = if (qscale == 0 && lumaQtblWordsLoaded == 16) {
                var sum = 0
                for (b in lumaQtblBytes) sum += (b.toInt() and 0xFF)
                tableQuality(sum)
            } else null

            val mappedSrc32 = srcHi == 0L && runCatching {
                dma.check(srcAddr, srcBytesNeeded, false)
            }.isSuccess

            fun encodeFromLiveOpticalSensor(sensor: SensorPort): JpegUyvyCodec.EncodeResult {
                val lens = context.ports.find("mount", MountPort::class.java)?.attachedLens()
                    ?: context.ports.find("lens", LensPort::class.java)?.parameters()
                val expPort = context.ports.find(Imx461ExposurePort.PORT_ID, Imx461ExposurePort::class.java)
                if (expPort == null && !loggedMissingExposureFallback) {
                    loggedMissingExposureFallback = true
                    LOGGER.warning("[FALLBACK][SocJpegFactory] ${Imx461ExposurePort.PORT_ID} unavailable; encoding optical JPEG with nominal exposure")
                }
                val captured = context.ports.find(IspStillReadoutPort.PORT_ID, IspStillReadoutPort::class.java)?.currentReadout()
                val effExp = Imx461ExposurePort.computeEffectiveExposure(expPort)
                var gainR1024 = 1024
                var gainG1024 = 1024
                var gainB1024 = 1024
                var ccmQ10: IntArray? = null
                var chromaMatQ10: IntArray? = null
                var monochromeMode = false
                var monoToneDeltaCb10 = 0
                var monoToneDeltaCr10 = 0
                val ispColor = context.ports.find(IspColorStatePort.PORT_ID, IspColorStatePort::class.java)
                val toneCurves = ispColor?.toneCurves
                if (ispColor != null) {
                    gainR1024 = captured?.gainR1024 ?: ispColor.gainR1024
                    gainG1024 = captured?.gainG1024 ?: ispColor.gainG1024
                    gainB1024 = captured?.gainB1024 ?: ispColor.gainB1024
                    ccmQ10 = ispColor.ccmQ10
                    chromaMatQ10 = ispColor.chromaMatQ10
                    monochromeMode = ispColor.monochromeMode
                    monoToneDeltaCb10 = ispColor.monoToneDeltaCb10
                    monoToneDeltaCr10 = ispColor.monoToneDeltaCr10
                } else {
                    val bank68 = context.ports.find("soc-isp.bank-68000", MmioDevice::class.java)
                    if (bank68 != null) {
                        val lvGain0 = runCatching { bank68.read(0x108L, 4) }.getOrDefault(0L)
                        val lvGain1 = runCatching { bank68.read(0x10cL, 4) }.getOrDefault(0L)
                        val rawR = (lvGain0 and 0x7FFFL).toInt()
                        val rawG = ((lvGain0 ushr 16) and 0x7FFFL).toInt()
                        val rawB = (lvGain1 and 0x7FFFL).toInt()
                        if (rawR > 0 && rawG > 0 && rawB > 0) {
                            gainR1024 = ((rawR.toLong() * 1024L) / 919L).toInt().coerceIn(64, 8192)
                            gainG1024 = ((rawG.toLong() * 1024L) / 512L).toInt().coerceIn(64, 8192)
                            gainB1024 = ((rawB.toLong() * 1024L) / 972L).toInt().coerceIn(64, 8192)
                        }
                    }
                }
                var cropFactor35mm = 1.0
                val lv1 = context.ports.find("soc-isp.live-view-1", MmioDevice::class.java)
                val bank6e = context.ports.find("soc-isp.bank-6e000", MmioDevice::class.java)
                if (lv1 != null) {
                    val inSize = runCatching { lv1.read(0x00cL, 4) }.getOrDefault(0L)
                    val outSize = runCatching { lv1.read(0x018L, 4) }.getOrDefault(0L)
                    val hScale = runCatching { lv1.read(0x01cL, 4) }.getOrDefault(0L)
                    val inW = (inSize and 0xFFFFL).toInt() + 1
                    val outW = (outSize and 0xFFFFL).toInt() + 1
                    val hDecim = maxOf(1, ((hScale ushr 16) and 0xFFFFL).toInt())
                    val hStepQ12 = (hScale and 0xFFFFL).toInt()
                    if (inSize != 0L && outSize != 0L && hScale != 0L && inW >= 64 && outW >= 2 && hStepQ12 > 0) {
                        val reg04 = bank6e?.let { runCatching { it.read(0x004L, 4) }.getOrDefault(0L) } ?: 0L
                        val reg14 = bank6e?.let { runCatching { it.read(0x014L, 4) }.getOrDefault(0L) } ?: 0L
                        val c1StartX = ((reg14 ushr 16) and 0xFFFFL).toInt()
                        val c1W = (reg14 and 0xFFFFL).toInt()
                        val focusCheckSensorRoi = (c1StartX >= 128 && c1W >= 256) || ((reg04 and 1L) != 0L && c1W in 256..1024)
                        if (!focusCheckSensorRoi) {
                            val resizerFracW = (outW.toDouble() * hDecim.toDouble() * (hStepQ12.toDouble() / 4096.0)) / inW.toDouble()
                            if (resizerFracW in 0.72..0.88) {
                                cropFactor35mm = (1.0 / resizerFracW).coerceIn(1.0, 2.0)
                            }
                        }
                    }
                }
                val nowTick = context.clock.ticks()
                var cacheKey = captured?.tick ?: (effExp.durationNanos * 31L + effExp.iso)
                cacheKey = cacheKey * 31L + gainR1024
                cacheKey = cacheKey * 31L + gainG1024
                cacheKey = cacheKey * 31L + gainB1024
                cacheKey = cacheKey * 31L + (if (monochromeMode) 1L else 0L)
                cacheKey = cacheKey * 31L + monoToneDeltaCb10
                cacheKey = cacheKey * 31L + monoToneDeltaCr10
                cacheKey = cacheKey * 31L + (toneCurves?.signature ?: 0L)
                cacheKey = cacheKey * 31L + (ccmQ10?.contentHashCode() ?: 0)
                cacheKey = cacheKey * 31L + (chromaMatQ10?.contentHashCode() ?: 0)
                cacheKey = cacheKey * 31L + (lens?.hashCode() ?: 0)

                val cached = cachedOpticalFrame
                val frame = if (
                    cached != null &&
                    cachedOpticalSensor === sensor &&
                    cachedOpticalKey == cacheKey &&
                    cachedOpticalTick != Long.MIN_VALUE &&
                    (nowTick - cachedOpticalTick) in 0L..OPTICAL_CACHE_TTL_TICKS
                ) {
                    cached
                } else {
                    when (
                        val exposed = JpegUyvyCodec.exposeAndDemosaicOpticalSensor(
                            tick = nowTick,
                            sensor = sensor,
                            lens = lens,
                            durationNanos = effExp.durationNanos,
                            iso = effExp.iso,
                            gainR1024 = gainR1024,
                            gainG1024 = gainG1024,
                            gainB1024 = gainB1024,
                            ccmQ10 = ccmQ10,
                            chromaMatQ10 = chromaMatQ10,
                            monochromeMode = monochromeMode,
                            monoToneDeltaCb10 = monoToneDeltaCb10,
                            monoToneDeltaCr10 = monoToneDeltaCr10,
                            toneCurves = toneCurves,
                            captured = captured,
                            fallbackWidthHint = width,
                            fallbackHeightHint = height,
                        )
                    ) {
                        is JpegUyvyCodec.OpticalExposeResult.Error ->
                            return JpegUyvyCodec.EncodeResult.Error(exposed.message)
                        is JpegUyvyCodec.OpticalExposeResult.Success -> {
                            cachedOpticalFrame = exposed.frame
                            cachedOpticalSensor = sensor
                            cachedOpticalTick = nowTick
                            cachedOpticalKey = cacheKey
                            exposed.frame
                        }
                    }
                }
                return JpegUyvyCodec.encodeFromOpticalBgr(
                    frame = frame,
                    width = width,
                    height = height,
                    qscale = qscale,
                    maxBytes = dstMax,
                    qualityOverride = qtblQualityOverride,
                    cropFactor = cropFactor35mm,
                )
            }

            fun resolveEncodeDstAddr(outSize: Long): Long? {
                val fullDstAddr = (dstHi shl 32) or dstAddr
                if (outSize > MAX_JPEG_BYTES || dstAddr > 0x1_0000_0000L - outSize) {
                    return null
                }
                return if (dstHi != 0L) {
                    if (runCatching { dma.check(fullDstAddr, outSize, true) }.isSuccess) {
                        fullDstAddr
                    } else {
                        LOGGER.warning(
                            "[FALLBACK][SocJpegFactory] 34-bit AXI JPEG destination 0x${fullDstAddr.toString(16)} (dstHi=$dstHi, dst=0x${dstAddr.toString(16)}) is unmapped in cpu.memory; falling back to low-32-bit address",
                        )
                        dma.check(dstAddr, outSize, true)
                        dstAddr
                    }
                } else {
                    dma.check(dstAddr, outSize, true)
                    dstAddr
                }
            }

            fun quantization(): List<Int> = buildList {
                add(qscale)
                if (qscale == 0 && lumaQtblWordsLoaded == 16) lumaQtblBytes.forEach { add(it.toInt() and 255) }
            }
            fun identity(): List<Long>? {
                // Check the bytes as well as the DMA slots: the movie recorder reuses both addresses.
                if (!mappedSrc32 || srcBytesNeeded > 1_048_576L) return null
                val crc = java.util.zip.CRC32C()
                val source = (dma as? DirectGuestRamPort)?.sliceDirect(srcAddr, srcBytesNeeded, false)
                if (source != null) crc.update(source.asByteBuffer()) else crc.update(dmaReadChunked(dma, srcAddr, srcBytesNeeded.toInt()))
                return listOf((srcHi shl 32) or srcAddr, (dstHi shl 32) or dstAddr, dstMax, srcSizeWord, winDimWord,
                    linePitch.toLong(), encRegs.getValue(ENC_SUBSAMPLE), encRegs.getValue(ENC_MODE), encRegs.getValue(ENC_SWAP_CTRL), crc.value)
            }
            fun matchesPrevious(key: List<Long>?, quant: List<Int>): Boolean =
                previous != null && previous.identity == key && savedQuality(quant) < savedQuality(previous.quantization) &&
                    context.clock.ticks() - previous.tick in 0L..54_000_000L
            fun overflow(bytes: Int) {
                val key = identity()
                if (key != null) {
                    val quant = quantization()
                    val attempts = if (previous != null && matchesPrevious(key, quant)) previous.attempts + 1 else 1
                    pendingOverflow = Overflow(context.clock.ticks(), key, quant, attempts)
                }
                val quality = qtblQualityOverride ?: JpegUyvyCodec.qualityFromQscale(qscale)
                failEncode("encoded JPEG exceeds output buffer (quality=$quality tableWords=$lumaQtblWordsLoaded)", overflowBytes = bytes)
            }
            fun finishEncodeSuccess(outSize: Long) {
                if (previous != null && matchesPrevious(identity(), quantization())) {
                    recoveredOverflows += previous.attempts
                    lastEncoderRecovery = "overflowTick=${previous.tick} doneTick=${context.clock.ticks()} " +
                        "src=0x${srcAddr.toString(16)} dst=0x${dstAddr.toString(16)} bytes=$outSize capacity=$dstMax attempts=${previous.attempts} " +
                        "quality=${savedQuality(previous.quantization)}->${qtblQualityOverride ?: JpegUyvyCodec.qualityFromQscale(qscale)}"
                    LOGGER.info("[JPEG-RETRY] $lastEncoderRecovery")
                }
                lastSourceBytes = srcBytesNeeded
                lastEncodedBytes = outSize
                encodeCount++
                encRegs[ENC_OUT_BYTES] = outSize and 0xFFFF_FFFFL
                encRegs[ENC_DMA_STAT] = (dstAddr + outSize) and 0xFFFF_FFFFL
                encRegs[ENC_INT_STATUS] = encRegs.getValue(ENC_INT_STATUS) or INT_ENCODE_DONE
                updateEncoderIrq()
            }

            if (mappedSrc32 && dma is DirectGuestRamPort) {
                val srcSeg = dma.sliceDirect(srcAddr, srcBytesNeeded, write = false)
                if (srcSeg != null) {
                    val allZero = isAllZeroSegment(srcSeg, srcBytesNeeded)
                    val liveSensor = if (allZero) context.ports.find("cmos", SensorPort::class.java) else null
                    if (liveSensor == null) {
                        val directRes = JpegUyvyCodec.encodeDirectFromSegment(
                            srcUyvy = srcSeg,
                            width = width,
                            height = height,
                            linePitchBytes = linePitch,
                            qscale = qscale,
                            maxBytes = dstMax,
                            qualityOverride = qtblQualityOverride,
                        ) { nativeJpeg, actualBytes ->
                            val outSize = actualBytes.toLong()
                            val targetDstAddr = resolveEncodeDstAddr(outSize) ?: return@encodeDirectFromSegment false
                            val dstSeg = dma.sliceDirect(targetDstAddr, outSize, write = true)
                                ?: return@encodeDirectFromSegment false
                            MemorySegment.copy(nativeJpeg, 0L, dstSeg, 0L, outSize)
                            dma.notifyDirectWrite(targetDstAddr, outSize)
                            true
                        }
                        when (directRes) {
                            is JpegUyvyCodec.EncodeDirectResult.DirectWritten -> {
                                finishEncodeSuccess(directRes.encodedBytes.toLong())
                                return
                            }
                            is JpegUyvyCodec.EncodeDirectResult.Success -> {
                                val outSize = directRes.jpegBytes.size.toLong()
                                val targetDstAddr = resolveEncodeDstAddr(outSize)
                                if (targetDstAddr == null) {
                                    failEncode("unmapped JPEG destination")
                                    return
                                }
                                dmaWriteChunked(dma, targetDstAddr, directRes.jpegBytes)
                                finishEncodeSuccess(outSize)
                                return
                            }
                            is JpegUyvyCodec.EncodeDirectResult.Overflow -> {
                                lastSourceBytes = srcBytesNeeded
                                lastEncodedBytes = directRes.encodedBytes.toLong()
                                overflow(directRes.encodedBytes)
                                return
                            }
                            is JpegUyvyCodec.EncodeDirectResult.Error -> {
                                failEncode(directRes.message)
                                return
                            }
                            null -> {
                                // Native JPEG unavailable; fall through to standard JVM fallback path.
                            }
                        }
                    }
                }
            }

            val res = if (mappedSrc32) {
                val uyvyBytes = dmaReadChunked(dma, srcAddr, srcBytesNeeded.toInt())
                var allZero = true
                for (b in uyvyBytes) {
                    if (b != 0.toByte()) {
                        allZero = false
                        break
                    }
                }
                val liveSensor = if (allZero) context.ports.find("cmos", SensorPort::class.java) else null
                if (liveSensor != null) {
                    // BUG-V3-8-4: During STILL capture, firmware encodes the 160x120 EXIF thumbnail
                    // and 640x480 DCF screen-nail (e.g. 0xbfefe000 / 0xbfdd0000) populated on real
                    // silicon by RECC_S. Because RECC_S does not render pixels into DRAM, the buffer
                    // remains all-zero; synthesize from the active CMOS optical sensor instead.
                    val msg = "[FALLBACK][SocJpegFactory] 32-bit JPEG encode source 0x${srcAddr.toString(16)} (${width}x$height) is unwritten all-zero DRAM; synthesizing from CMOS optical sensor"
                    if (width <= 640 && height <= 480) {
                        LOGGER.fine(msg)
                    } else if (!loggedZeroSourceFallback) {
                        loggedZeroSourceFallback = true
                        LOGGER.warning(msg)
                    }
                    encodeFromLiveOpticalSensor(liveSensor)
                } else {
                    JpegUyvyCodec.encodeFromMemYc(
                        uyvyBytes = uyvyBytes,
                        width = width,
                        height = height,
                        linePitchBytes = linePitch,
                        qscale = qscale,
                        maxBytes = dstMax,
                        qualityOverride = qtblQualityOverride,
                    )
                }
            } else {
                val sensor = context.ports.find("cmos", SensorPort::class.java)
                if (sensor == null) {
                    JpegUyvyCodec.EncodeResult.Error(
                        "Unmapped/high-AXI JPEG encode source 0x${srcHi.toString(16)}_${srcAddr.toString(16)} without CMOS sensor",
                    )
                } else {
                    encodeFromLiveOpticalSensor(sensor)
                }
            }

            when (res) {
                is JpegUyvyCodec.EncodeResult.Error -> failEncode(res.message)
                is JpegUyvyCodec.EncodeResult.Overflow -> {
                    lastSourceBytes = srcBytesNeeded
                    lastEncodedBytes = res.encodedBytes.toLong()
                    overflow(res.encodedBytes)
                }
                is JpegUyvyCodec.EncodeResult.Success -> {
                    val outSize = res.jpegBytes.size.toLong()
                    val targetDstAddr = resolveEncodeDstAddr(outSize)
                    if (targetDstAddr == null) {
                        failEncode("unmapped JPEG destination")
                        return
                    }
                    dmaWriteChunked(dma, targetDstAddr, res.jpegBytes)
                    finishEncodeSuccess(outSize)
                }
            }
        }

        override fun start() {
            check(!closed) { "soc-jpeg closed" }
            if (enabled) {
                // Note: cpu.memory is registered by FirmwareSession after ComponentHost.load(), so resolve lazily on DMA.
                memory = context.ports.find("cpu.memory", DmaMemory::class.java)
                sink = checkNotNull(context.ports.find("soc-gic.lines", InterruptSink::class.java)) {
                    "soc-jpeg requires soc-gic.lines"
                }
                sink?.setLine(IRQ_LINE, irqAsserted)
                if (encoderEnabled) {
                    sink?.setLine(ENCODER_IRQ_LINE, encoderIrqAsserted)
                }
            }
            started = true
        }

        override fun close() {
            if (started && enabled) {
                if (irqAsserted) {
                    irqAsserted = false
                    sink?.setLine(IRQ_LINE, false)
                }
                if (encoderEnabled && encoderIrqAsserted) {
                    encoderIrqAsserted = false
                    sink?.setLine(ENCODER_IRQ_LINE, false)
                }
            }
            closed = true
        }

        override fun validateSettings(settings: Map<String, Any>) {
            check(!closed) { "soc-jpeg closed" }
            require(settings.all { values[it.key] == it.value }) { "soc-jpeg settings require restart" }
        }

        override fun applySettings(settings: Map<String, Any>) {
            validateSettings(settings)
        }

        override fun additionalMmioPorts(): List<String> = when {
            encoderEnabled -> listOf(DECODER_V2_PORT_ID, ENCODER_PORT_ID)
            enabled -> listOf(PORT_ID)
            else -> emptyList()
        }

        override fun interruptRoutes(): List<InterruptRoute> = when {
            encoderEnabled -> listOf(InterruptRoute("soc-gic.lines", ENCODER_IRQ_LINE..IRQ_LINE))
            enabled -> listOf(InterruptRoute("soc-gic.lines", IRQ_LINE..IRQ_LINE))
            else -> emptyList()
        }

        override fun saveSnapshot(): Map<String, Any?> {
            require(!closed) { "soc-jpeg closed" }
            val regMap = linkedMapOf<String, Long>()
            for (off in VALID_OFFSETS) {
                regMap[OFFSET_KEYS.getValue(off)] = regs.getValue(off)
            }
            val base = linkedMapOf<String, Any?>(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalarValues" to saveScalarValues(),
                "enabled" to enabled,
                "registers" to regMap,
                "pendingTick" to pendingTick,
                "irqAsserted" to irqAsserted,
                "decodeCount" to decodeCount,
                "errorCount" to errorCount,
                "lastBytesWritten" to lastBytesWritten,
                "lastBytesRead" to lastBytesRead,
            )
            if (encoderEnabled) {
                val encRegMap = linkedMapOf<String, Long>()
                for (off in ENCODER_VALID_OFFSETS) {
                    encRegMap[ENCODER_OFFSET_KEYS.getValue(off)] = encRegs.getValue(off)
                }
                base["encoder"] = linkedMapOf(
                    "registers" to encRegMap,
                    "pendingTick" to encoderPendingTick,
                    "irqAsserted" to encoderIrqAsserted,
                    "encodeCount" to encodeCount,
                    "errorCount" to encoderErrorCount,
                    "lastEncodedBytes" to lastEncodedBytes,
                    "lastSourceBytes" to lastSourceBytes,
                    "qtblWriteCursor" to qtblWriteCursor,
                    "lumaQtbl" to lumaQtblBytes.map { it.toInt() and 255 },
                    "lumaQtblWordsLoaded" to lumaQtblWordsLoaded,
                    "recoveredOverflows" to recoveredOverflows,
                    "pendingOverflow" to pendingOverflow?.let { mapOf("tick" to it.tick, "key" to it.identity, "quant" to it.quantization, "attempts" to it.attempts) },
                )
            }
            return base
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            require(!closed) { "soc-jpeg closed" }
            val baseKeys = setOf(
                "schema",
                "componentId",
                "scalarValues",
                "enabled",
                "registers",
                "pendingTick",
                "irqAsserted",
                "decodeCount",
                "errorCount",
                "lastBytesWritten",
                "lastBytesRead",
            )
            val v2Keys = baseKeys + "encoder"
            val hasEncoderBlock = "encoder" in data
            require(data.keys == baseKeys || (hasEncoderBlock && data.keys == v2Keys)) {
                "Unexpected soc-jpeg snapshot keys"
            }
            require(parseIntegralInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported soc-jpeg snapshot schema" }
            require(data["componentId"] == id) { "Mismatched soc-jpeg snapshot componentId" }
            val savedEnabled = data["enabled"] as? Boolean
                ?: throw IllegalArgumentException("Invalid soc-jpeg enabled flag")
            if (hasEncoderBlock) {
                require(encoderEnabled) { "Cannot restore soc-jpeg codec-v2 snapshot into non-v2 profile $profile" }
            }
            val upgradingFromDisabled = !savedEnabled && enabled
            val upgradingV1ToV2 = savedEnabled && !hasEncoderBlock && encoderEnabled
            require(savedEnabled == enabled || upgradingFromDisabled) { "soc-jpeg enabled state differs" }
            val compatibleNonLiveKeys = if (upgradingFromDisabled || upgradingV1ToV2) setOf("profile") else emptySet()
            val rawScalars = validateScalarValues(data["scalarValues"], values, compatibleNonLiveKeys)

            val rawRegs = data["registers"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid soc-jpeg registers map")
            require(rawRegs.keys == OFFSET_KEYS.values.toSet()) { "Unexpected soc-jpeg register keys in snapshot" }
            val nextRegs = LinkedHashMap<Long, Long>()
            for (off in VALID_OFFSETS) {
                val key = OFFSET_KEYS.getValue(off)
                nextRegs[off] = parseUnsigned32(rawRegs[key], "registers.$key")
            }

            val nextPendingTick = data["pendingTick"]?.let { raw ->
                parseIntegralLong(raw, "pendingTick").also { require(it >= 0L) { "Negative pendingTick" } }
            }
            if (!savedEnabled) require(nextPendingTick == null) { "Disabled soc-jpeg cannot have pendingTick" }

            val nextIrqAsserted = data["irqAsserted"] as? Boolean
                ?: throw IllegalArgumentException("Invalid soc-jpeg irqAsserted")
            if (!savedEnabled) require(!nextIrqAsserted) { "Disabled soc-jpeg cannot assert IRQ" }

            val nextDecodeCount = parseIntegralLong(data["decodeCount"], "decodeCount").also { require(it >= 0L) }
            val nextErrorCount = parseIntegralLong(data["errorCount"], "errorCount").also { require(it >= 0L) }
            val nextBytesWritten = parseIntegralLong(data["lastBytesWritten"], "lastBytesWritten").also { require(it >= 0L) }
            val nextBytesRead = parseIntegralLong(data["lastBytesRead"], "lastBytesRead").also { require(it >= 0L) }
            if (upgradingFromDisabled) {
                require(nextDecodeCount == 0L && nextErrorCount == 0L && nextBytesWritten == 0L && nextBytesRead == 0L) {
                    "Disabled soc-jpeg upgrade requires idle counters"
                }
            }

            val nextEncRegs = LinkedHashMap<Long, Long>().apply { resetEncoderRegisters() }
            var nextEncPendingTick: Long? = null
            var nextEncIrqAsserted = false
            var nextEncodeCount = 0L
            var nextEncErrorCount = 0L
            var nextEncodedBytes = 0L
            var nextSourceBytes = 0L
            var nextQtblCursor = 0
            var nextLuma = ByteArray(64)
            var nextLumaWords = 0
            var nextRecovered = 0L
            var nextOverflow: Overflow? = null
            if (hasEncoderBlock) {
                val encBlock = data["encoder"] as? Map<*, *>
                    ?: throw IllegalArgumentException("Invalid soc-jpeg encoder snapshot block")
                val expectedEncKeys = setOf(
                    "registers",
                    "pendingTick",
                    "irqAsserted",
                    "encodeCount",
                    "errorCount",
                    "lastEncodedBytes",
                    "lastSourceBytes",
                    "qtblWriteCursor",
                )
                require(encBlock.keys == expectedEncKeys || encBlock.keys == expectedEncKeys + setOf("recoveredOverflows", "pendingOverflow", "lumaQtbl", "lumaQtblWordsLoaded")) { "Unexpected soc-jpeg encoder snapshot keys" }
                val rawEncRegs = encBlock["registers"] as? Map<*, *>
                    ?: throw IllegalArgumentException("Invalid soc-jpeg encoder registers map")
                require(rawEncRegs.keys == ENCODER_OFFSET_KEYS.values.toSet()) {
                    "Unexpected soc-jpeg encoder register keys in snapshot"
                }
                for (off in ENCODER_VALID_OFFSETS) {
                    val key = ENCODER_OFFSET_KEYS.getValue(off)
                    nextEncRegs[off] = parseUnsigned32(rawEncRegs[key], "encoder.registers.$key")
                }
                nextEncPendingTick = encBlock["pendingTick"]?.let { raw ->
                    parseIntegralLong(raw, "encoder.pendingTick").also { require(it >= 0L) }
                }
                nextEncIrqAsserted = encBlock["irqAsserted"] as? Boolean
                    ?: throw IllegalArgumentException("Invalid soc-jpeg encoder.irqAsserted")
                nextEncodeCount = parseIntegralLong(encBlock["encodeCount"], "encoder.encodeCount").also { require(it >= 0L) }
                nextEncErrorCount = parseIntegralLong(encBlock["errorCount"], "encoder.errorCount").also { require(it >= 0L) }
                if ("lumaQtbl" in encBlock) {
                    val table = encBlock["lumaQtbl"] as List<*>
                    require(table.size == 64)
                    nextLuma = ByteArray(64) { parseIntegralInt(table[it], "lumaQtbl").also { value -> require(value in 0..255) }.toByte() }
                    nextLumaWords = parseIntegralInt(encBlock["lumaQtblWordsLoaded"], "lumaQtblWordsLoaded").also { require(it in 0..16) }
                }
                nextRecovered = encBlock["recoveredOverflows"]?.let { parseIntegralLong(it, "recoveredOverflows") } ?: 0L
                require(nextRecovered in 0..nextEncErrorCount)
                nextOverflow = encBlock["pendingOverflow"]?.let { value ->
                    val entry = value as Map<*, *>
                    val key = (entry["key"] as List<*>).map { parseIntegralLong(it, "overflow.key") }
                    val quant = (entry["quant"] as List<*>).map { parseIntegralInt(it, "overflow.quant") }
                    val attempts = parseIntegralLong(entry["attempts"], "overflow.attempts")
                    require(key.size == 10 && quant.size in setOf(1, 65) && quant.all { it in 0..255 } &&
                        attempts in 1..(nextEncErrorCount - nextRecovered))
                    Overflow(parseIntegralLong(entry["tick"], "overflow.tick"), key, quant, attempts)
                }
                nextEncodedBytes = parseIntegralLong(encBlock["lastEncodedBytes"], "encoder.lastEncodedBytes").also { require(it >= 0L) }
                nextSourceBytes = parseIntegralLong(encBlock["lastSourceBytes"], "encoder.lastSourceBytes").also { require(it >= 0L) }
                nextQtblCursor = parseIntegralInt(encBlock["qtblWriteCursor"], "encoder.qtblWriteCursor").also { require(it in 0..32) }
            }

            restoreScalarValues(rawScalars, DESCRIPTOR, compatibleNonLiveKeys)
            regs.clear()
            regs.putAll(nextRegs)
            pendingTick = nextPendingTick
            irqAsserted = nextIrqAsserted
            decodeCount = nextDecodeCount
            errorCount = nextErrorCount
            lastBytesWritten = nextBytesWritten
            lastBytesRead = nextBytesRead

            encRegs.clear()
            encRegs.putAll(nextEncRegs)
            encoderPendingTick = nextEncPendingTick
            encoderIrqAsserted = nextEncIrqAsserted
            encodeCount = nextEncodeCount
            encoderErrorCount = nextEncErrorCount
            lastEncoderError = null
            lastEncoderRecovery = null
            nextLuma.copyInto(lumaQtblBytes)
            lumaQtblWordsLoaded = nextLumaWords
            recoveredOverflows = nextRecovered
            pendingOverflow = nextOverflow
            lastEncodedBytes = nextEncodedBytes
            lastSourceBytes = nextSourceBytes
            qtblWriteCursor = nextQtblCursor

            if (started && enabled) {
                sink?.setLine(IRQ_LINE, irqAsserted)
                if (encoderEnabled) {
                    sink?.setLine(ENCODER_IRQ_LINE, encoderIrqAsserted)
                }
            }
        }

        override fun state(): Map<String, Any?> {
            val base = linkedMapOf<String, Any?>(
                "enabled" to enabled,
                "closed" to closed,
                "irqLine" to IRQ_LINE,
                "irqAsserted" to irqAsserted,
                "pendingTick" to pendingTick,
                "decodeCount" to decodeCount,
                "errorCount" to errorCount,
                "lastBytesWritten" to lastBytesWritten,
                "lastBytesRead" to lastBytesRead,
            )
            if (encoderEnabled) {
                base["encoderEnabled"] = true
                base["encoderIrqLine"] = ENCODER_IRQ_LINE
                base["encoderIrqAsserted"] = encoderIrqAsserted
                base["encoderPendingTick"] = encoderPendingTick
                base["encodeCount"] = encodeCount
                base["encoderErrorCount"] = encoderErrorCount
                base["encoderRecoveredOverflowCount"] = recoveredOverflows
                base["encoderUnrecoveredErrorCount"] = encoderErrorCount - recoveredOverflows
                base["encoderOverflowPending"] = pendingOverflow != null
                lastEncoderRecovery?.let { base["lastEncoderRecovery"] = it }
                lastEncoderError?.let { base["lastEncoderError"] = it }
                base["lastEncodedBytes"] = lastEncodedBytes
                base["lastSourceBytes"] = lastSourceBytes
            }
            return super.state() + base
        }

        private fun parseIntegralLong(value: Any?, fieldName: String): Long {
            require(value is Byte || value is Short || value is Int || value is Long) {
                "Integral value required for $fieldName"
            }
            return (value as Number).toLong()
        }

        private fun parseIntegralInt(value: Any?, fieldName: String): Int {
            val number = parseIntegralLong(value, fieldName)
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Out of Int range for $fieldName" }
            return number.toInt()
        }

        private fun parseUnsigned32(value: Any?, fieldName: String): Long =
            parseIntegralLong(value, fieldName).also {
                require(it in 0L..0xFFFF_FFFFL) { "Unsigned u32 required for $fieldName" }
            }

        private fun validateScalarValues(
            rawScalars: Any?,
            currentValues: Map<String, Any>,
            compatibleNonLiveKeys: Set<String> = emptySet(),
        ): Map<*, *> {
            val scalarMap = rawScalars as? Map<*, *>
                ?: throw IllegalArgumentException("Missing scalarValues for soc-jpeg")
            require(scalarMap.keys == currentValues.keys) { "Scalar setting keys differ in snapshot for soc-jpeg" }
            for (setting in DESCRIPTOR.settings) {
                val rawSetting = scalarMap[setting.key]
                    ?: throw IllegalArgumentException("Null setting ${setting.key} in soc-jpeg")
                val validated = setting.validate(rawSetting)
                if (setting.key !in compatibleNonLiveKeys) {
                    require(currentValues[setting.key] == validated) {
                        "Restart-only setting ${setting.key} differs in snapshot for soc-jpeg"
                    }
                }
            }
            return scalarMap
        }
    }
}
