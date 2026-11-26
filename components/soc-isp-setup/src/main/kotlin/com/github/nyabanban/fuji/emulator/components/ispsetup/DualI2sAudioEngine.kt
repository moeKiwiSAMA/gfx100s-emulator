package com.github.nyabanban.fuji.emulator.components.ispsetup

import com.github.nyabanban.fuji.emulator.api.AddressWindow
import com.github.nyabanban.fuji.emulator.api.AudioCaptureStats
import com.github.nyabanban.fuji.emulator.api.AudioPcmChunk
import com.github.nyabanban.fuji.emulator.api.AudioPlaybackStats
import com.github.nyabanban.fuji.emulator.api.DmaSourcePeer
import com.github.nyabanban.fuji.emulator.api.CameraTimedI2sRxPort
import com.github.nyabanban.fuji.emulator.api.I2sAudioPort
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.RangedDmaDestinationPeer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dual-channel SoC I2S/PCM audio capture & DMA destination engine (`0xFFF4F000` Ch 0 & `0xFFF78000` Ch 1).
 *
 * Register semantics verified against GFX100S 2.13 `section4.bin` (`0x011E1440..0x011E1B4C`) and
 * `TSK_SOUNDIO` (`0x01297800..0x01299600`):
 * - `+0x00`: RX FIFO data port (DMA source, 32-bit word; getter `0x011E1A80`)
 * - `+0x04`: TX FIFO data port (DMA destination & PIO write, 32-bit word; getter `0x011E1AC0`, writer `0x011E1B00`)
 * - `+0x08`: I2S format & master/slave clock mode (`Ch0=0x1000254B`, `Ch1=0x1000252B`)
 * - `+0x0C`: Word length / frame slot width (`mode 0->7` 8-bit, `1->15` 16-bit, `2->23` 24-bit, `3->31` 32-bit)
 * - `+0x10`: Channel enable / mask (`0x3`)
 * - `+0x14`: Extended control / mute (`0`)
 * - `+0x18`: TX/RX transport start/stop (`bit 16` = TX enable, `bit 24` = RX enable, `bit 0` = master run)
 * - `+0x1C`: Soft reset (`bit 0` self-clearing)
 * - `+0x20`: FIFO watermarks & flush (`TX=0x7D3F0F0F`, `RX=0x7F3D0F0F`, `FULL_DUPLEX=0x7D3D0F0F`)
 * - `+0x28`: TX/RX DMA request enable (`bit 24` = TX DMA enable, `bit 8` = RX DMA enable)
 *
 * DMA mapping (`section4.bin @ 0x015A7FE4`): HW Ch 4 (req `28`, GIC `211`), HW Ch 7 (req `29`, GIC `214`),
 * HW Ch 8 (req `30`, GIC `215`).
 */
internal class DualI2sAudioEngine(
    private val clockProvider: () -> Long,
    ringCapacityFrames: Int = DEFAULT_RING_CAPACITY_FRAMES,
    rxRingCapacityFrames: Int = DEFAULT_RX_RING_CAPACITY_FRAMES,
    private val movieExposureTick: () -> Long? = { null },
) : I2sAudioPort, CameraTimedI2sRxPort {
    companion object {
        private const val TICKS_PER_AUDIO_FRAME = 54_000_000L / 48_000L
        const val PORT_AUDIO = "soc-isp-setup.i2s-audio"
        const val PORT_I2S0_DMA_TX = "soc-isp-setup.i2s0-dma-tx"
        const val PORT_I2S1_DMA_TX = "soc-isp-setup.i2s1-dma-tx"
        const val PORT_I2S0_DMA_RX = "soc-isp-setup.i2s0-dma-rx"
        const val PORT_I2S1_DMA_RX = "soc-isp-setup.i2s1-dma-rx"
        const val PORT_I2S0_FIFO = "soc-isp-setup.i2s0-fifo"
        const val PORT_I2S1_FIFO = "soc-isp-setup.i2s1-fifo"

        // Real-silicon FIFO addresses verified from section4.bin:
        //   0x011E1A80 -> RX FIFO (+0x00: 0xFFF4F000 / 0xFFF78000, DMA source in TSK_SOUNDIO @ 0x01298D8C)
        //   0x011E1AC0 / 0x011E1B00 -> TX FIFO (+0x04: 0xFFF4F004 / 0xFFF78004, DMA dest @ 0x01299088 / 0x01299474)
        const val I2S0_RX_ADDRESS = 0xFFF4F000L
        const val I2S0_TX_ADDRESS = 0xFFF4F004L
        const val I2S1_RX_ADDRESS = 0xFFF78000L
        const val I2S1_TX_ADDRESS = 0xFFF78004L
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000
        const val DEFAULT_RING_CAPACITY_FRAMES = 48_000 // 1.0 second at 48 kHz stereo
        const val DEFAULT_RX_RING_CAPACITY_FRAMES = 16_384 // ~341 ms at 48 kHz stereo

        const val TX_START_BIT = 0x0001_0000L
        const val RX_START_BIT = 0x0100_0000L
        const val TX_DMA_ENABLE_BIT = 0x0100_0000L
        const val RX_DMA_ENABLE_BIT = 0x0000_0100L

        val ALLOWED_DMA_REQUESTS = setOf(28, 29, 30)
        private const val MAX_DMA_TRANSFER_BYTES = 1_048_576L // 1 MiB ceiling per single DMA transfer

        private val SNAPSHOT_KEYS = setOf(
            "totalTxFrames", "totalTxWords", "droppedFrames", "sequence",
            "lastNonZeroFrameTick", "channels",
        )
        private val CHANNEL_SNAPSHOT_KEYS = setOf(
            "txEnabled", "rxEnabled", "txDmaEnabled", "rxDmaEnabled",
            "txArmedTick", "wordBitDepth", "txWords", "rxWords",
        )

        /**
         * Decode bit depth from I2S `+0x0C` word-length register (`section4 @ 0x011E17DC` & `0x011E1A2C`).
         * Firmware writes `(bits - 1)` in bits `[4:0]` (`0x011E17DC`) or `[9:5]` (`0x011E1A2C`):
         * `7 -> 8-bit`, `15 -> 16-bit`, `23 -> 24-bit`, `31 -> 32-bit`.
         * Defaults to 16-bit when uninitialized (`0`).
         */
        internal fun decodeWordBitDepth(wordLengthReg: Long): Int {
            val low5 = (wordLengthReg and 0x1FL).toInt()
            val encoded = if (low5 != 0) low5 else ((wordLengthReg ushr 5) and 0x1FL).toInt()
            return when {
                encoded == 0 -> 16
                encoded <= 7 -> 8
                encoded <= 15 -> 16
                encoded <= 23 -> 24
                else -> 32
            }
        }

        private fun requireLong(value: Any?, field: String, min: Long, max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) {
                "I2S snapshot field $field requires integer"
            }
            val parsed = (value as Number).toLong()
            require(parsed in min..max) { "I2S snapshot field $field out of range: $parsed" }
            return parsed
        }
    }

    internal class ChannelState(val index: Int, val txBaseAddress: Long, val rxBaseAddress: Long) {
        var txEnabled = false
        var rxEnabled = false
        var txDmaEnabled = false
        var rxDmaEnabled = false
        var txArmedTick: Long? = null
        var rxArmedTick: Long? = null
        var wordBitDepth = 16
        var txWords = 0L
        var rxWords = 0L
        // Pending odd 24/32-bit sample left-channel accumulator for dual-word stereo pairs
        var hasPendingLeftSample = false
        var pendingLeftSample: Short = 0
        // Pending right-channel sample for 24/32-bit PIO RX dual-word stereo reads
        var hasPendingRxRightSample = false
        var pendingRxRightSample: Short = 0

        fun isTxReady(): Boolean = txEnabled || txDmaEnabled
        fun isRxReady(): Boolean = rxEnabled || rxDmaEnabled

        fun reset() {
            txEnabled = false
            rxEnabled = false
            txDmaEnabled = false
            rxDmaEnabled = false
            txArmedTick = null
            rxArmedTick = null
            hasPendingLeftSample = false
            pendingLeftSample = 0
            hasPendingRxRightSample = false
            pendingRxRightSample = 0
        }
    }

    private val channels = arrayOf(
        ChannelState(0, I2S0_TX_ADDRESS, I2S0_RX_ADDRESS),
        ChannelState(1, I2S1_TX_ADDRESS, I2S1_RX_ADDRESS),
    )

    // Lock-guarded circular stereo TX buffer (2 shorts per frame: interleaved L, R)
    private val capacityFrames = ringCapacityFrames.also { require(it in 64..480_000) }
    private val ringSamples = ShortArray(capacityFrames * 2)
    private var ringReadIndex = 0
    private var ringWriteIndex = 0
    private var ringQueuedFrames = 0
    private var ringLastChannel = 0
    private var ringLastBitDepth = 16
    private var ringLastTick = 0L

    private var totalTxFrames = 0L
    private var totalTxWords = 0L
    private var droppedFrames = 0L
    private var sequence = 0L
    private var hasLastNonZeroFrameTick = false
    private var lastNonZeroFrameTickValue = 0L

    // Dedicated lock-guarded zero-alloc circular stereo RX buffer (host capture -> guest I2S0/I2S1 RX)
    private val rxLock = Any()
    private val rxCapacityFrames = rxRingCapacityFrames.also { require(it in 64..480_000) }
    private val rxRingSamples = ShortArray(rxCapacityFrames * 2)
    private var rxRingReadIndex = 0
    private var rxRingWriteIndex = 0
    private var rxRingQueuedFrames = 0
    private var totalRxFrames = 0L
    private var rxOverruns = 0L
    private var rxUnderruns = 0L
    @Volatile
    private var rxCaptureActive = false

    // Optional deterministic RX test / on-demand generator (null = silence)
    @Volatile
    var rxProvider: ((channel: Int, out: ShortArray, frames: Int) -> Int)? = null

    @Volatile
    private var timedSource: ((ShortArray, Int, Long) -> Boolean)? = null

    override fun setTimedRxSource(source: ((ShortArray, Int, Long) -> Boolean)?) {
        timedSource = source
    }

    private fun channel(index: Int): ChannelState {
        require(index == 0 || index == 1) { "I2S channel must be 0 or 1, got $index" }
        return channels[index]
    }

    @Synchronized
    fun onLowRegisterWrite(channelIndex: Int, offset: Long, value: Long) {
        val ch = channel(channelIndex)
        when (offset) {
            0x04L -> { // +0x0C word length / slot width
                ch.wordBitDepth = decodeWordBitDepth(value)
                ch.hasPendingLeftSample = false
                ch.hasPendingRxRightSample = false
            }
            0x10L -> { // +0x18 TX/RX transport start/stop
                val nextTx = (value and TX_START_BIT) != 0L
                val nextRx = (value and RX_START_BIT) != 0L
                val wasTxReady = ch.isTxReady()
                val wasRxReady = ch.isRxReady()
                ch.txEnabled = nextTx
                ch.rxEnabled = nextRx
                updateArmedTick(ch, wasTxReady, wasRxReady)
            }
        }
    }

    @Synchronized
    fun onHighRegisterWrite(channelIndex: Int, offset: Long, value: Long) {
        val ch = channel(channelIndex)
        when (offset) {
            0x00L -> { // +0x20 FIFO watermark / flush
                ch.hasPendingLeftSample = false
                ch.hasPendingRxRightSample = false
            }
            0x08L -> { // +0x28 TX/RX DMA request enable
                val nextTxDma = (value and TX_DMA_ENABLE_BIT) != 0L
                val nextRxDma = (value and RX_DMA_ENABLE_BIT) != 0L
                val wasTxReady = ch.isTxReady()
                val wasRxReady = ch.isRxReady()
                ch.txDmaEnabled = nextTxDma
                ch.rxDmaEnabled = nextRxDma
                updateArmedTick(ch, wasTxReady, wasRxReady)
            }
        }
    }

    @Synchronized
    fun onSoftReset(channelIndex: Int) {
        channel(channelIndex).reset()
    }

    private fun updateArmedTick(ch: ChannelState, wasTxReady: Boolean, wasRxReady: Boolean) {
        val nowTxReady = ch.isTxReady()
        if (nowTxReady && !wasTxReady) {
            ch.txArmedTick = clockProvider()
        } else if (!nowTxReady) {
            ch.txArmedTick = null
            ch.hasPendingLeftSample = false
        }
        val nowRxReady = ch.isRxReady()
        if (nowRxReady && !wasRxReady) {
            ch.rxArmedTick = clockProvider()
        } else if (!nowRxReady) {
            ch.rxArmedTick = null
        }
    }

    /** Decode a single 32-bit little-endian TX FIFO word into 16-bit stereo frames and enqueue. */
    @Synchronized
    fun pushTxFifoWord(channelIndex: Int, word32: Long, tick: Long = clockProvider()) {
        pushTxFifoWordLocked(channel(channelIndex), channelIndex, word32.toInt(), tick)
    }

    private fun pushTxFifoWordLocked(ch: ChannelState, channelIndex: Int, raw: Int, tick: Long) {
        ch.txWords++
        totalTxWords++
        when (ch.wordBitDepth) {
            8 -> {
                // Packed 8-bit signed stereo (low byte L, byte 1 R) upscaled to 16-bit
                val left = ((raw and 0xFF).toByte().toInt() shl 8).toShort()
                val right = (((raw ushr 8) and 0xFF).toByte().toInt() shl 8).toShort()
                enqueueFrameLocked(channelIndex, left, right, 8, tick)
            }
            16 -> {
                // GFX100S native 48 kHz 16-bit stereo packed as 0xRRRRLLLL (L = low16, R = high16)
                val left = (raw and 0xFFFF).toShort()
                val right = ((raw ushr 16) and 0xFFFF).toShort()
                enqueueFrameLocked(channelIndex, left, right, 16, tick)
            }
            24 -> {
                // 24-bit signed sample per 32-bit FIFO word (L then R alternating); scale down to 16-bit
                val signed24 = (raw shl 8) shr 8
                val sample16 = (signed24 shr 8).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                if (!ch.hasPendingLeftSample) {
                    ch.pendingLeftSample = sample16
                    ch.hasPendingLeftSample = true
                } else {
                    ch.hasPendingLeftSample = false
                    enqueueFrameLocked(channelIndex, ch.pendingLeftSample, sample16, 24, tick)
                }
            }
            else -> {
                // 32-bit signed sample per FIFO word (L then R alternating); top 16 bits
                val sample16 = (raw shr 16).toShort()
                if (!ch.hasPendingLeftSample) {
                    ch.pendingLeftSample = sample16
                    ch.hasPendingLeftSample = true
                } else {
                    ch.hasPendingLeftSample = false
                    enqueueFrameLocked(channelIndex, ch.pendingLeftSample, sample16, 32, tick)
                }
            }
        }
    }

    @Synchronized
    internal fun pushTxFifoBytes(channelIndex: Int, bytes: ByteArray, length: Int, tick: Long) {
        require(length % 4 == 0 && length in 0..bytes.size) { "I2S TX byte payload must be 4-byte aligned" }
        val ch = channel(channelIndex)
        var off = 0
        while (off < length) {
            val raw = (bytes[off].toInt() and 0xFF) or
                ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                (bytes[off + 3].toInt() shl 24)
            pushTxFifoWordLocked(ch, channelIndex, raw, tick)
            off += 4
        }
    }

    private fun enqueueFrameLocked(channelIndex: Int, left: Short, right: Short, bitDepth: Int, tick: Long) {
        if (ringQueuedFrames == capacityFrames) {
            // Bounded overwrite of oldest frame so emulation never stalls or allocates unboundedly
            var nextRead = ringReadIndex + 1
            if (nextRead == capacityFrames) nextRead = 0
            ringReadIndex = nextRead
            ringQueuedFrames--
            droppedFrames++
        }
        val offset = ringWriteIndex shl 1
        ringSamples[offset] = left
        ringSamples[offset + 1] = right
        var nextWrite = ringWriteIndex + 1
        if (nextWrite == capacityFrames) nextWrite = 0
        ringWriteIndex = nextWrite
        ringQueuedFrames++
        totalTxFrames++
        sequence++
        ringLastChannel = channelIndex
        ringLastBitDepth = bitDepth
        ringLastTick = tick
        if (left != 0.toShort() || right != 0.toShort()) {
            hasLastNonZeroFrameTick = true
            lastNonZeroFrameTickValue = tick
        }
    }

    @Synchronized
    override fun pushTxSamples(
        channel: Int,
        pcmStereoInterleaved: ShortArray,
        frames: Int,
        sampleRateHz: Int,
        bitDepth: Int,
        tick: Long,
    ) {
        require(channel == 0 || channel == 1) { "I2S channel must be 0 or 1, got $channel" }
        require(frames >= 0 && pcmStereoInterleaved.size >= frames * 2) { "Insufficient interleaved stereo PCM buffer" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        for (i in 0 until frames) {
            enqueueFrameLocked(channel, pcmStereoInterleaved[i * 2], pcmStereoInterleaved[i * 2 + 1], bitDepth, tick)
        }
    }

    override fun pushRxSamples(
        pcmStereoInterleaved: ShortArray,
        frames: Int,
        sampleRateHz: Int,
        bitDepth: Int,
    ): Int {
        require(frames >= 0 && pcmStereoInterleaved.size >= frames * 2) { "Insufficient RX stereo PCM buffer" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(bitDepth > 0) { "bitDepth must be positive" }
        if (frames == 0) return 0
        synchronized(rxLock) {
            rxCaptureActive = true
            if (frames >= rxCapacityFrames) {
                val dropped = rxRingQueuedFrames.toLong() + (frames - rxCapacityFrames).toLong()
                rxOverruns += dropped
                val startFrame = frames - rxCapacityFrames
                System.arraycopy(pcmStereoInterleaved, startFrame * 2, rxRingSamples, 0, rxCapacityFrames * 2)
                rxRingReadIndex = 0
                rxRingWriteIndex = 0
                rxRingQueuedFrames = rxCapacityFrames
            } else {
                val overflow = rxRingQueuedFrames + frames - rxCapacityFrames
                if (overflow > 0) {
                    rxRingReadIndex += overflow
                    if (rxRingReadIndex >= rxCapacityFrames) rxRingReadIndex -= rxCapacityFrames
                    rxRingQueuedFrames -= overflow
                    rxOverruns += overflow.toLong()
                }
                val firstFrames = minOf(frames, rxCapacityFrames - rxRingWriteIndex)
                System.arraycopy(pcmStereoInterleaved, 0, rxRingSamples, rxRingWriteIndex * 2, firstFrames * 2)
                val secondFrames = frames - firstFrames
                if (secondFrames > 0) {
                    System.arraycopy(pcmStereoInterleaved, firstFrames * 2, rxRingSamples, 0, secondFrames * 2)
                }
                rxRingWriteIndex += frames
                if (rxRingWriteIndex >= rxCapacityFrames) rxRingWriteIndex -= rxCapacityFrames
                rxRingQueuedFrames += frames
            }
            totalRxFrames += frames.toLong()
        }
        return frames
    }

    override fun pullRxSamples(
        channel: Int,
        pcmStereoInterleaved: ShortArray,
        frames: Int,
        sampleRateHz: Int,
        bitDepth: Int,
        tick: Long,
    ): Int {
        require(channel == 0 || channel == 1) { "I2S channel must be 0 or 1, got $channel" }
        require(frames >= 0 && pcmStereoInterleaved.size >= frames * 2) { "Insufficient RX stereo PCM buffer" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        if (frames == 0) return 0

        if (timedSource?.invoke(pcmStereoInterleaved, frames, tick) == true) {
            synchronized(rxLock) { totalRxFrames += frames.toLong() }
            return frames
        }
        val fromRing: Int
        synchronized(rxLock) {
            fromRing = minOf(frames, rxRingQueuedFrames)
            if (fromRing > 0) {
                val firstFrames = minOf(fromRing, rxCapacityFrames - rxRingReadIndex)
                System.arraycopy(rxRingSamples, rxRingReadIndex * 2, pcmStereoInterleaved, 0, firstFrames * 2)
                val secondFrames = fromRing - firstFrames
                if (secondFrames > 0) {
                    System.arraycopy(rxRingSamples, 0, pcmStereoInterleaved, firstFrames * 2, secondFrames * 2)
                }
                rxRingReadIndex += fromRing
                if (rxRingReadIndex >= rxCapacityFrames) rxRingReadIndex -= rxCapacityFrames
                rxRingQueuedFrames -= fromRing
            }
        }
        if (fromRing == frames) return frames

        val remaining = frames - fromRing
        val custom = rxProvider
        if (custom != null) {
            if (fromRing == 0) {
                val provided = custom.invoke(channel, pcmStereoInterleaved, frames).coerceIn(0, frames)
                if (provided < frames) {
                    pcmStereoInterleaved.fill(0, provided * 2, frames * 2)
                    synchronized(rxLock) { rxUnderruns += (frames - provided).toLong() }
                }
                return frames
            }
            val tail = ShortArray(remaining * 2)
            val provided = custom.invoke(channel, tail, remaining).coerceIn(0, remaining)
            if (provided > 0) {
                System.arraycopy(tail, 0, pcmStereoInterleaved, fromRing * 2, provided * 2)
            }
            if (provided < remaining) {
                pcmStereoInterleaved.fill(0, (fromRing + provided) * 2, frames * 2)
                synchronized(rxLock) { rxUnderruns += (remaining - provided).toLong() }
            }
            return frames
        }

        pcmStereoInterleaved.fill(0, fromRing * 2, frames * 2)
        if (rxCaptureActive) {
            synchronized(rxLock) {
                if (rxCaptureActive) rxUnderruns += remaining.toLong()
            }
        }
        return frames
    }

    override fun setRxCaptureActive(active: Boolean) {
        synchronized(rxLock) {
            rxCaptureActive = active
            if (!active) {
                rxRingReadIndex = 0
                rxRingWriteIndex = 0
                rxRingQueuedFrames = 0
            }
        }
    }

    override fun setRxFallbackProvider(
        provider: ((channel: Int, pcmStereoInterleaved: ShortArray, frames: Int) -> Int)?,
    ) {
        rxProvider = provider
    }

    override fun rxStats(): AudioCaptureStats = synchronized(rxLock) {
        AudioCaptureStats(
            totalRxFrames = totalRxFrames,
            queuedRxFrames = rxRingQueuedFrames,
            rxOverruns = rxOverruns,
            rxUnderruns = rxUnderruns,
        )
    }

    @Synchronized
    override fun drainPlaybackPcm(maxFrames: Int): AudioPcmChunk? {
        require(maxFrames > 0) { "maxFrames must be positive" }
        if (ringQueuedFrames == 0) return null
        val count = minOf(maxFrames, ringQueuedFrames)
        val out = ShortArray(count * 2)
        val firstFrames = minOf(count, capacityFrames - ringReadIndex)
        System.arraycopy(ringSamples, ringReadIndex * 2, out, 0, firstFrames * 2)
        val secondFrames = count - firstFrames
        if (secondFrames > 0) {
            System.arraycopy(ringSamples, 0, out, firstFrames * 2, secondFrames * 2)
        }
        ringReadIndex += count
        if (ringReadIndex >= capacityFrames) ringReadIndex -= capacityFrames
        ringQueuedFrames -= count
        return AudioPcmChunk(
            channel = ringLastChannel,
            sampleRateHz = DEFAULT_SAMPLE_RATE_HZ,
            bitDepth = ringLastBitDepth,
            samples = out,
            frames = count,
            sequence = sequence,
            tick = ringLastTick,
        )
    }

    @Synchronized
    override fun playbackStats(): AudioPlaybackStats {
        var mask = 0
        if (channels[0].isTxReady()) mask = mask or 1
        if (channels[1].isTxReady()) mask = mask or 2
        return AudioPlaybackStats(
            totalTxFrames = totalTxFrames,
            totalTxWords = totalTxWords,
            droppedFrames = droppedFrames,
            queuedFrames = ringQueuedFrames,
            sequence = sequence,
            activeChannelMask = mask,
            lastNonZeroFrameTick = if (hasLastNonZeroFrameTick) lastNonZeroFrameTickValue else null,
        )
    }

    /** Create an MMIO FIFO device for PIO access to `+0x00` (RX RO) and `+0x04` (TX WO). */
    fun createFifoMmioDevice(channelIndex: Int): MmioDevice {
        val ch = channel(channelIndex)
        val pioStereo = ShortArray(2)
        return object : MmioDevice {
            override val clockOrder = 78
            override val clockIndex = channelIndex

            override fun read(offset: Long, size: Int): Long {
                require(size == 4 && offset == 0x00L) { "I2S$channelIndex FIFO read requires aligned 32-bit RX (+0x00)" }
                val bitDepth: Int
                val hasPendingRight: Boolean
                val pendingRight: Short
                synchronized(this@DualI2sAudioEngine) {
                    bitDepth = ch.wordBitDepth
                    if ((bitDepth == 24 || bitDepth == 32) && ch.hasPendingRxRightSample) {
                        hasPendingRight = true
                        pendingRight = ch.pendingRxRightSample
                        ch.hasPendingRxRightSample = false
                    } else {
                        hasPendingRight = false
                        pendingRight = 0
                    }
                    ch.rxWords++
                }
                if (hasPendingRight) {
                    return if (bitDepth == 24) {
                        (pendingRight.toInt() shl 8).toLong() and 0x00FF_FFFFL
                    } else {
                        (pendingRight.toInt() shl 16).toLong() and 0xFFFF_FFFFL
                    }
                }
                pullRxSamples(channelIndex, pioStereo, 1, DEFAULT_SAMPLE_RATE_HZ, bitDepth, clockProvider())
                return when (bitDepth) {
                    8 -> {
                        val l = (pioStereo[0].toInt() shr 8) and 0xFF
                        val r = (pioStereo[1].toInt() shr 8) and 0xFF
                        (l or (r shl 8)).toLong()
                    }
                    16 -> {
                        (pioStereo[0].toInt() and 0xFFFF).toLong() or
                            ((pioStereo[1].toInt() and 0xFFFF).toLong() shl 16)
                    }
                    24 -> {
                        synchronized(this@DualI2sAudioEngine) {
                            ch.pendingRxRightSample = pioStereo[1]
                            ch.hasPendingRxRightSample = true
                        }
                        (pioStereo[0].toInt() shl 8).toLong() and 0x00FF_FFFFL
                    }
                    else -> {
                        synchronized(this@DualI2sAudioEngine) {
                            ch.pendingRxRightSample = pioStereo[1]
                            ch.hasPendingRxRightSample = true
                        }
                        (pioStereo[0].toInt() shl 16).toLong() and 0xFFFF_FFFFL
                    }
                }
            }

            override fun write(offset: Long, size: Int, value: Long) {
                require(size == 4 && offset == 0x04L) { "I2S$channelIndex FIFO write requires aligned 32-bit TX (+0x04)" }
                require(value in 0L..0xFFFF_FFFFL) { "I2S$channelIndex TX word outside u32" }
                pushTxFifoWord(channelIndex, value, clockProvider())
            }
        }
    }

    /** Create a dedicated `RangedDmaDestinationPeer` for I2S Ch 0 TX (`0xFFF4F004`) or Ch 1 TX (`0xFFF78004`). */
    fun createDmaTxPeer(channelIndex: Int): RangedDmaDestinationPeer {
        val ch = channel(channelIndex)
        val portName = if (channelIndex == 0) PORT_I2S0_DMA_TX else PORT_I2S1_DMA_TX
        val window = AddressWindow(portName, ch.txBaseAddress, 4L)
        return ChannelDmaTxPeer(ch, window)
    }

    /** Create a dedicated `DmaSourcePeer` for I2S Ch 0 RX (`0xFFF4F000`) or Ch 1 RX (`0xFFF78000`) movie audio capture. */
    fun createDmaRxPeer(channelIndex: Int): DmaSourcePeer {
        val ch = channel(channelIndex)
        return ChannelDmaRxPeer(ch)
    }

    private inner class ChannelDmaRxPeer(
        private val ch: ChannelState,
    ) : DmaSourcePeer {
        private var leaseOwner: Int = -1
        private var leaseLen: Int = 0
        private var scratchPcm: ShortArray = ShortArray(0)
        private var scratchBytes: ByteArray = ByteArray(0)
        private var readCursor = 0
        private var previousArmTick = Long.MIN_VALUE
        private var previousClaimTick = Long.MIN_VALUE
        private var nextSampleTick = Long.MIN_VALUE
        private var reservedEndTick = Long.MIN_VALUE
        private var captureStartDelay = 0L

        override fun overlaps(address: Long, size: Long): Boolean =
            size > 0L && address < ch.rxBaseAddress + 4L && address + size > ch.rxBaseAddress

        override fun check(address: Long, size: Long) {
            require(address == ch.rxBaseAddress) {
                "I2S${ch.index} DMA RX source address mismatch: 0x${address.toString(16)}"
            }
            require(size in 4L..MAX_DMA_TRANSFER_BYTES && size % 4L == 0L) {
                "I2S${ch.index} DMA RX transfer size must be 4-byte aligned within 4..$MAX_DMA_TRANSFER_BYTES, got $size"
            }
        }

        override fun acceptedAt(): Long? = synchronized(this@DualI2sAudioEngine) {
            if (ch.isRxReady()) ch.rxArmedTick ?: clockProvider() else null
        }

        override fun claim(channel: Int, address: Long, size: Long): Long? {
            check(address, size)
            val at = acceptedAt() ?: return null
            check(leaseOwner < 0 || leaseOwner == channel) {
                "I2S${ch.index} DMA RX peer already leased by channel $leaseOwner"
            }
            val byteLen = size.toInt()
            val words = byteLen ushr 2
            val bitDepth = synchronized(this@DualI2sAudioEngine) { ch.wordBitDepth }
            val frames = when (bitDepth) {
                24, 32 -> (words + 1) ushr 1
                else -> words
            }
            val neededShorts = frames shl 1
            if (scratchPcm.size < neededShorts) scratchPcm = ShortArray(neededShorts)
            if (scratchBytes.size < byteLen) scratchBytes = ByteArray(byteLen)
            val pcm = scratchPcm
            val packed = scratchBytes
            // acceptedAt() dates the start of the level-sensitive RX request. It stays asserted
            // across multiple DMA descriptors. PCM progresses by one 54 MHz / 48 kHz period per frame.
            val now = clockProvider()
            val duration = frames * TICKS_PER_AUDIO_FRAME
            if (at != previousArmTick || now < previousClaimTick) {
                // The firmware gives the first movie picture and PCM sample matching zero PTS.
                // RX DMA claims follow exposure. Preserve that measured acquisition delay across descriptors.
                val exposure = if (timedSource != null) movieExposureTick() else null
                captureStartDelay = now - (exposure?.takeIf { it in at..now } ?: now)
            }
            val captureTick = now - captureStartDelay
            val start = if (at != previousArmTick || now < previousClaimTick || nextSampleTick < captureTick - duration) {
                captureTick
            } else {
                nextSampleTick
            }
            previousArmTick = at
            previousClaimTick = now
            reservedEndTick = start + duration
            pullRxSamples(ch.index, pcm, frames, DEFAULT_SAMPLE_RATE_HZ, bitDepth, start)
            var outOff = 0
            when (bitDepth) {
                8 -> {
                    for (i in 0 until words) {
                        val l = (pcm[i shl 1].toInt() shr 8) and 0xFF
                        val r = (pcm[(i shl 1) + 1].toInt() shr 8) and 0xFF
                        packed[outOff] = l.toByte()
                        packed[outOff + 1] = r.toByte()
                        packed[outOff + 2] = 0
                        packed[outOff + 3] = 0
                        outOff += 4
                    }
                }
                16 -> {
                    for (i in 0 until words) {
                        val l = pcm[i shl 1].toInt()
                        val r = pcm[(i shl 1) + 1].toInt()
                        packed[outOff] = l.toByte()
                        packed[outOff + 1] = (l ushr 8).toByte()
                        packed[outOff + 2] = r.toByte()
                        packed[outOff + 3] = (r ushr 8).toByte()
                        outOff += 4
                    }
                }
                24 -> {
                    for (i in 0 until words) {
                        val s = pcm[i].toInt()
                        packed[outOff] = 0
                        packed[outOff + 1] = s.toByte()
                        packed[outOff + 2] = (s ushr 8).toByte()
                        packed[outOff + 3] = 0
                        outOff += 4
                    }
                }
                else -> {
                    for (i in 0 until words) {
                        val s = pcm[i].toInt()
                        packed[outOff] = 0
                        packed[outOff + 1] = 0
                        packed[outOff + 2] = s.toByte()
                        packed[outOff + 3] = (s ushr 8).toByte()
                        outOff += 4
                    }
                }
            }
            leaseOwner = channel
            leaseLen = byteLen
            readCursor = 0
            return at
        }

        override fun release(channel: Int) {
            if (leaseOwner == channel) {
                leaseOwner = -1
                leaseLen = 0
                readCursor = 0
            }
        }

        override fun read(channel: Int, address: Long, destination: ByteBuffer) {
            require(leaseOwner == channel && address == ch.rxBaseAddress) {
                "I2S${ch.index} DMA RX read without active lease"
            }
            val count = destination.remaining()
            require(readCursor + count <= leaseLen) { "I2S${ch.index} DMA RX read out of bounds" }
            destination.put(scratchBytes, readCursor, count)
            readCursor += count
        }

        override fun consume(channel: Int) {
            require(leaseOwner == channel) { "I2S${ch.index} DMA RX consume without active lease" }
            nextSampleTick = reservedEndTick
            val words = (leaseLen ushr 2).toLong()
            synchronized(this@DualI2sAudioEngine) {
                ch.rxWords += words
            }
            leaseOwner = -1
            leaseLen = 0
            readCursor = 0
        }

        override fun pacedBytesPerFrame(): Int = synchronized(this@DualI2sAudioEngine) {
            if (ch.wordBitDepth >= 24) 8 else 4
        }
    }

    private inner class ChannelDmaTxPeer(
        private val ch: ChannelState,
        private val window: AddressWindow,
    ) : RangedDmaDestinationPeer {
        private var leaseOwner: Int = -1
        private var leaseSize: Int = 0
        private var scratchBuffer: ByteArray = ByteArray(0)

        override fun destinationRanges(): List<AddressWindow> = listOf(window)

        override fun matches(address: Long): Boolean = address == window.base

        override fun check(address: Long, size: Long, width: Int, request: Int) {
            require(address == window.base) { "I2S${ch.index} DMA destination address mismatch: 0x${address.toString(16)}" }
            require(width == 4) { "I2S${ch.index} DMA requires 32-bit FIFO transfers, got width=$width" }
            require(size in 4L..MAX_DMA_TRANSFER_BYTES && size % 4L == 0L) {
                "I2S${ch.index} DMA transfer size must be 4-byte aligned within 4..$MAX_DMA_TRANSFER_BYTES, got $size"
            }
            require(request in ALLOWED_DMA_REQUESTS) {
                "I2S${ch.index} DMA requires audio request line in $ALLOWED_DMA_REQUESTS, got $request"
            }
        }

        override fun acceptedAt(): Long? = synchronized(this@DualI2sAudioEngine) {
            if (ch.isTxReady()) ch.txArmedTick ?: clockProvider() else null
        }

        override fun claim(channel: Int, address: Long, size: Long, width: Int, request: Int): Long? {
            check(address, size, width, request)
            val at = acceptedAt() ?: return null
            check(leaseOwner < 0 || leaseOwner == channel) {
                "I2S${ch.index} DMA TX peer already leased by channel $leaseOwner"
            }
            val len = size.toInt()
            if (scratchBuffer.size < len) scratchBuffer = ByteArray(len)
            leaseOwner = channel
            leaseSize = len
            return at
        }

        override fun release(channel: Int) {
            if (leaseOwner == channel) {
                leaseOwner = -1
                leaseSize = 0
            }
        }

        override fun write(channel: Int, address: Long, offset: Long, source: ByteBuffer) {
            require(leaseOwner == channel && address == window.base) { "I2S${ch.index} DMA TX write without active lease" }
            val count = source.remaining()
            require(offset >= 0L && offset + count <= leaseSize) { "I2S${ch.index} DMA TX write out of bounds" }
            source.get(scratchBuffer, offset.toInt(), count)
        }

        override fun readBack(channel: Int, address: Long, offset: Long, destination: ByteBuffer) {
            require(leaseOwner == channel && address == window.base) { "I2S${ch.index} DMA TX readBack without active lease" }
            val count = destination.remaining()
            require(offset >= 0L && offset + count <= leaseSize) { "I2S${ch.index} DMA TX readBack out of bounds" }
            destination.put(scratchBuffer, offset.toInt(), count)
        }

        override fun finish(channel: Int, completedAt: Long, observedAt: Long) {
            require(leaseOwner == channel) { "I2S${ch.index} DMA TX finish without active lease" }
            val len = leaseSize
            leaseOwner = -1
            leaseSize = 0
            pushTxFifoBytes(ch.index, scratchBuffer, len, completedAt)
        }

        override fun pacedBytesPerFrame(): Int = synchronized(this@DualI2sAudioEngine) {
            if (ch.wordBitDepth >= 24) 8 else 4
        }
    }

    @Synchronized
    fun state(): Map<String, Any?> {
        val stats = playbackStats()
        val rx = rxStats()
        return linkedMapOf(
            "sampleRateHz" to DEFAULT_SAMPLE_RATE_HZ,
            "totalTxFrames" to stats.totalTxFrames,
            "totalTxWords" to stats.totalTxWords,
            "droppedFrames" to stats.droppedFrames,
            "queuedFrames" to stats.queuedFrames,
            "sequence" to stats.sequence,
            "activeChannelMask" to stats.activeChannelMask,
            "lastNonZeroFrameTick" to stats.lastNonZeroFrameTick,
            "rxStats" to linkedMapOf(
                "totalRxFrames" to rx.totalRxFrames,
                "queuedRxFrames" to rx.queuedRxFrames,
                "rxOverruns" to rx.rxOverruns,
                "rxUnderruns" to rx.rxUnderruns,
            ),
            "channels" to channels.map { ch ->
                linkedMapOf(
                    "index" to ch.index,
                    "txBaseAddress" to ch.txBaseAddress,
                    "txEnabled" to ch.txEnabled,
                    "rxEnabled" to ch.rxEnabled,
                    "txDmaEnabled" to ch.txDmaEnabled,
                    "rxDmaEnabled" to ch.rxDmaEnabled,
                    "txArmedTick" to ch.txArmedTick,
                    "wordBitDepth" to ch.wordBitDepth,
                    "txWords" to ch.txWords,
                    "rxWords" to ch.rxWords,
                )
            },
        )
    }

    @Synchronized
    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "totalTxFrames" to totalTxFrames,
        "totalTxWords" to totalTxWords,
        "droppedFrames" to droppedFrames,
        "sequence" to sequence,
        "lastNonZeroFrameTick" to if (hasLastNonZeroFrameTick) lastNonZeroFrameTickValue else null,
        "channels" to channels.map { ch ->
            linkedMapOf(
                "txEnabled" to ch.txEnabled,
                "rxEnabled" to ch.rxEnabled,
                "txDmaEnabled" to ch.txDmaEnabled,
                "rxDmaEnabled" to ch.rxDmaEnabled,
                "txArmedTick" to ch.txArmedTick,
                "wordBitDepth" to ch.wordBitDepth,
                "txWords" to ch.txWords,
                "rxWords" to ch.rxWords,
            )
        },
    )

    data class ParsedChannel(
        val txEnabled: Boolean,
        val rxEnabled: Boolean,
        val txDmaEnabled: Boolean,
        val rxDmaEnabled: Boolean,
        val txArmedTick: Long?,
        val wordBitDepth: Int,
        val txWords: Long,
        val rxWords: Long,
    )

    data class ParsedSnapshot(
        val totalTxFrames: Long,
        val totalTxWords: Long,
        val droppedFrames: Long,
        val sequence: Long,
        val lastNonZeroFrameTick: Long?,
        val channels: List<ParsedChannel>,
    )

    fun parseSnapshot(raw: Any?): ParsedSnapshot {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid i2sAudio snapshot")
        require(map.keys == SNAPSHOT_KEYS) { "Unexpected i2sAudio snapshot keys" }
        val txFrames = requireLong(map["totalTxFrames"], "totalTxFrames", 0L, Long.MAX_VALUE)
        val txWords = requireLong(map["totalTxWords"], "totalTxWords", 0L, Long.MAX_VALUE)
        val dropped = requireLong(map["droppedFrames"], "droppedFrames", 0L, Long.MAX_VALUE)
        val seq = requireLong(map["sequence"], "sequence", 0L, Long.MAX_VALUE)
        val lastNonZero = map["lastNonZeroFrameTick"]?.let { requireLong(it, "lastNonZeroFrameTick", 0L, Long.MAX_VALUE) }
        val rawChannels = map["channels"] as? List<*> ?: throw IllegalArgumentException("i2sAudio channels requires list")
        require(rawChannels.size == 2) { "i2sAudio snapshot requires exactly 2 channels" }
        val parsedChannels = rawChannels.mapIndexed { idx, item ->
            val cm = item as? Map<*, *> ?: throw IllegalArgumentException("i2sAudio channel[$idx] requires map")
            require(cm.keys == CHANNEL_SNAPSHOT_KEYS) { "Unexpected i2sAudio channel[$idx] snapshot keys" }
            val txEn = cm["txEnabled"] as? Boolean ?: throw IllegalArgumentException("txEnabled requires boolean")
            val rxEn = cm["rxEnabled"] as? Boolean ?: throw IllegalArgumentException("rxEnabled requires boolean")
            val txDma = cm["txDmaEnabled"] as? Boolean ?: throw IllegalArgumentException("txDmaEnabled requires boolean")
            val rxDma = cm["rxDmaEnabled"] as? Boolean ?: throw IllegalArgumentException("rxDmaEnabled requires boolean")
            val armed = cm["txArmedTick"]?.let { requireLong(it, "txArmedTick", 0L, Long.MAX_VALUE) }
            val bits = requireLong(cm["wordBitDepth"], "wordBitDepth", 8L, 32L).toInt()
            require(bits in setOf(8, 16, 24, 32)) { "Unsupported i2sAudio wordBitDepth: $bits" }
            val chTxWords = requireLong(cm["txWords"], "txWords", 0L, Long.MAX_VALUE)
            val chRxWords = requireLong(cm["rxWords"], "rxWords", 0L, Long.MAX_VALUE)
            ParsedChannel(txEn, rxEn, txDma, rxDma, armed, bits, chTxWords, chRxWords)
        }
        return ParsedSnapshot(txFrames, txWords, dropped, seq, lastNonZero, parsedChannels)
    }

    @Synchronized
    fun applySnapshot(parsed: ParsedSnapshot?) {
        ringReadIndex = 0
        ringWriteIndex = 0
        ringQueuedFrames = 0
        synchronized(rxLock) {
            rxRingReadIndex = 0
            rxRingWriteIndex = 0
            rxRingQueuedFrames = 0
            totalRxFrames = 0L
            rxOverruns = 0L
            rxUnderruns = 0L
            rxCaptureActive = false
        }
        if (parsed == null) {
            totalTxFrames = 0L
            totalTxWords = 0L
            droppedFrames = 0L
            sequence = 0L
            hasLastNonZeroFrameTick = false
            lastNonZeroFrameTickValue = 0L
            for (ch in channels) {
                ch.reset()
                ch.wordBitDepth = 16
                ch.txWords = 0L
                ch.rxWords = 0L
            }
            return
        }
        totalTxFrames = parsed.totalTxFrames
        totalTxWords = parsed.totalTxWords
        droppedFrames = parsed.droppedFrames
        sequence = parsed.sequence
        val restoredTick = parsed.lastNonZeroFrameTick
        hasLastNonZeroFrameTick = restoredTick != null
        lastNonZeroFrameTickValue = restoredTick ?: 0L
        for (i in channels.indices) {
            val src = parsed.channels[i]
            val dst = channels[i]
            dst.txEnabled = src.txEnabled
            dst.rxEnabled = src.rxEnabled
            dst.txDmaEnabled = src.txDmaEnabled
            dst.rxDmaEnabled = src.rxDmaEnabled
            dst.txArmedTick = src.txArmedTick
            dst.wordBitDepth = src.wordBitDepth
            dst.txWords = src.txWords
            dst.rxWords = src.rxWords
            dst.hasPendingLeftSample = false
            dst.pendingLeftSample = 0
            dst.hasPendingRxRightSample = false
            dst.pendingRxRightSample = 0
        }
    }
}
