package com.github.nyabanban.fuji.emulator.components.ispsetup

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.*

class DualI2sAudioTest {
    private class TestContext : ComponentContext {
        var currentTick = 1000L
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { currentTick }
        override fun readData(relative: String): Map<String, Any?> = emptyMap()
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null) { "Duplicate port $name" }
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private val factory = IspSetupFactory()

    private fun config(
        i2sAudioProfile: String = IspSetupFactory.I2S_AUDIO_PROFILE,
        pollProfile: String = IspSetupFactory.POLL_REPEAT,
    ): Map<String, Any> = mapOf(
        "profile" to IspSetupFactory.PROFILE,
        "initialFill" to 0L,
        "poll7801cProfile" to pollProfile,
        "i2sAudioProfile" to i2sAudioProfile,
    )

    @Test
    fun legacyProfileOmitsAudioPortsAndKeepsSnapshotKeysUnchanged() {
        val ctx = TestContext()
        val comp = factory.create(ctx, config(i2sAudioProfile = "")) as StatefulComponent
        assertNull(ctx.ports.find(IspSetupFactory.PORT_I2S_AUDIO, I2sAudioPort::class.java))
        assertNull(ctx.ports.find(IspSetupFactory.PORT_I2S0_DMA_TX, DmaDestinationPeer::class.java))
        assertNull(ctx.ports.find(IspSetupFactory.PORT_I2S1_DMA_TX, DmaDestinationPeer::class.java))
        val snap = comp.saveSnapshot()
        assertFalse("i2sAudio" in snap)
        val scalars = snap["scalars"] as Map<*, *>
        assertFalse("i2sAudioProfile" in scalars)

        // Legacy snapshot promotes cleanly into opt-in dual-I2S audio profile
        val ctx2 = TestContext()
        val promoted = factory.create(ctx2, config(i2sAudioProfile = IspSetupFactory.I2S_AUDIO_PROFILE)) as StatefulComponent
        promoted.restoreSnapshot(snap)
        val audio = assertIs<I2sAudioPort>(ctx2.ports.find(IspSetupFactory.PORT_I2S_AUDIO, I2sAudioPort::class.java))
        assertEquals(0L, audio.playbackStats().totalTxFrames)
        (comp as HardwareComponent).close()
        (promoted as HardwareComponent).close()
    }

    @Test
    fun dualI2sDmaTxPeersCapture16BitAnd24BitPcmEnforceRequestLinesAndSupportReadback() {
        val ctx = TestContext()
        val comp = factory.create(ctx, config()) as StatefulComponent
        val c0Low = ctx.ports.find(IspSetupFactory.PORT_CTRL0_LOW, MmioDevice::class.java)!!
        val c0High = ctx.ports.find(IspSetupFactory.PORT_CTRL0_HIGH, MmioDevice::class.java)!!
        val c1Low = ctx.ports.find(IspSetupFactory.PORT_CTRL1_LOW, MmioDevice::class.java)!!
        val c1High = ctx.ports.find(IspSetupFactory.PORT_CTRL1_HIGH, MmioDevice::class.java)!!
        val audio = assertIs<I2sAudioPort>(ctx.ports.find(IspSetupFactory.PORT_I2S_AUDIO, I2sAudioPort::class.java))
        val dma0 = assertIs<RangedDmaDestinationPeer>(ctx.ports.find(IspSetupFactory.PORT_I2S0_DMA_TX, DmaDestinationPeer::class.java))
        val dma1 = assertIs<RangedDmaDestinationPeer>(ctx.ports.find(IspSetupFactory.PORT_I2S1_DMA_TX, DmaDestinationPeer::class.java))

        // Immutable endpoint claims match DAT 0xFFF4F004 (Ch0 TX) & 0xFFF78004 (Ch1 TX) (section4 @ 0x011E1AC0 / 0x011E1B00)
        assertEquals(listOf(AddressWindow(IspSetupFactory.PORT_I2S0_DMA_TX, 0xFFF4F004L, 4L)), dma0.destinationRanges())
        assertEquals(listOf(AddressWindow(IspSetupFactory.PORT_I2S1_DMA_TX, 0xFFF78004L, 4L)), dma1.destinationRanges())
        assertTrue(dma0.matches(0xFFF4F004L))
        assertFalse(dma0.matches(0xFFF78004L))
        assertTrue(dma1.matches(0xFFF78004L))

        // Before arming, acceptedAt() is null and invalid DMA request line is refused
        assertNull(dma0.acceptedAt())
        assertFailsWith<IllegalArgumentException> { dma0.check(0xFFF4F004L, 16L, 4, 12) }

        // Program I2S Ch 0 identically to TSK_SOUNDIO (16-bit packed stereo: +0x0C=15, +0x18=TX, +0x28=TX DMA)
        ctx.currentTick = 5000L
        c0Low.write(0x00L, 4, 0x1000254BL) // +0x08 format
        c0Low.write(0x04L, 4, 0x0000000FL) // +0x0C 16-bit (15 = 16-1)
        c0Low.write(0x10L, 4, DualI2sAudioEngine.TX_START_BIT) // +0x18 TX start
        c0High.write(0x00L, 4, 0x7D3F0F0FL) // +0x20 TX watermark
        c0High.write(0x08L, 4, DualI2sAudioEngine.TX_DMA_ENABLE_BIT) // +0x28 TX DMA enable
        assertEquals(5000L, dma0.acceptedAt())

        // Stream 4 stereo 16-bit frames over DMA HW Ch 7 (request 29) -> 0xFFF4F004
        val payload16 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1000); putShort(-1000)
            putShort(4599); putShort(4599)
            putShort(-16000); putShort(16000)
            putShort(32767); putShort(-32768)
        }.array()
        assertEquals(5000L, dma0.claim(7, 0xFFF4F004L, 16L, 4, 29))
        dma0.write(7, 0xFFF4F004L, 0L, ByteBuffer.wrap(payload16))
        val readback = ByteArray(16)
        dma0.readBack(7, 0xFFF4F004L, 0L, ByteBuffer.wrap(readback))
        assertContentEquals(payload16, readback)
        dma0.finish(7, 5020L, 5020L)

        val chunk0 = assertNotNull(audio.drainPlaybackPcm(16))
        assertEquals(0, chunk0.channel)
        assertEquals(48_000, chunk0.sampleRateHz)
        assertEquals(16, chunk0.bitDepth)
        assertEquals(4, chunk0.frames)
        assertContentEquals(shortArrayOf(1000, -1000, 4599, 4599, -16000, 16000, 32767, -32768), chunk0.samples)

        // Program I2S Ch 1 in 24-bit mode (+0x0C=23 -> 24-bit signed, 1 sample per 32-bit word, L then R)
        ctx.currentTick = 6000L
        c1Low.write(0x00L, 4, 0x1000252BL)
        c1Low.write(0x04L, 4, 23L) // 24-bit
        c1Low.write(0x10L, 4, DualI2sAudioEngine.TX_START_BIT)
        c1High.write(0x08L, 4, DualI2sAudioEngine.TX_DMA_ENABLE_BIT)
        assertEquals(6000L, dma1.acceptedAt())

        // 2 stereo frames = 4 x 24-bit words = 16 bytes
        val payload24 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x00123400) // L0 -> 0x1234 = 4660
            putInt(0x00FED000) // R0 (negative 24-bit -77824) -> -304
            putInt(0x007FFF00) // L1 -> 32767
            putInt(0x00800000) // R1 (-8388608) -> -32768
        }.array()
        assertEquals(6000L, dma1.claim(8, 0xFFF78004L, 16L, 4, 30))
        dma1.write(8, 0xFFF78004L, 0L, ByteBuffer.wrap(payload24))
        dma1.finish(8, 6030L, 6030L)

        val chunk1 = assertNotNull(audio.drainPlaybackPcm(16))
        assertEquals(1, chunk1.channel)
        assertEquals(24, chunk1.bitDepth)
        assertEquals(2, chunk1.frames)
        assertContentEquals(shortArrayOf(0x1234.toShort(), (-304).toShort(), 32767, -32768), chunk1.samples)

        // Snapshot round-trip preserves counters and channel state
        val snap = comp.saveSnapshot()
        assertTrue("i2sAudio" in snap)
        val ctx2 = TestContext()
        val comp2 = factory.create(ctx2, config()) as StatefulComponent
        comp2.restoreSnapshot(snap)
        val audio2 = assertIs<I2sAudioPort>(ctx2.ports.find(IspSetupFactory.PORT_I2S_AUDIO, I2sAudioPort::class.java))
        assertEquals(6L, audio2.playbackStats().totalTxFrames)
        assertEquals(8L, audio2.playbackStats().totalTxWords)
        assertEquals(3, audio2.playbackStats().activeChannelMask)

        // Soft reset on ctrl1-poll (+0x1C) disarms I2S Ch 1
        val poll1 = ctx2.ports.find(IspSetupFactory.PORT_CTRL1_POLL, MmioDevice::class.java)!!
        poll1.write(0L, 4, 1L)
        assertEquals(0L, poll1.read(0L, 4))
        assertEquals(1, audio2.playbackStats().activeChannelMask)

        (comp as HardwareComponent).close()
        (comp2 as HardwareComponent).close()
    }

    @Test
    fun pioFifoAndRingOverflowAccountingWorkDeterministically() {
        val engine = DualI2sAudioEngine({ 42L }, ringCapacityFrames = 64)
        val fifo0 = engine.createFifoMmioDevice(0)
        // Push 70 frames into 64-frame ring -> 6 oldest frames dropped (TX FIFO is +0x04)
        for (i in 0 until 70) {
            val word = (i and 0xFFFF).toLong() or (((-i) and 0xFFFF).toLong() shl 16)
            fifo0.write(0x04L, 4, word)
        }
        val stats = engine.playbackStats()
        assertEquals(70L, stats.totalTxFrames)
        assertEquals(6L, stats.droppedFrames)
        assertEquals(64, stats.queuedFrames)
        val drained = assertNotNull(engine.drainPlaybackPcm(128))
        assertEquals(64, drained.frames)
        assertEquals(6.toShort(), drained.samples[0]) // oldest 0..5 were evicted
        assertEquals((-6).toShort(), drained.samples[1])
        assertEquals(69.toShort(), drained.samples[63 * 2])

        // RX PIO read (+0x00) returns deterministic silence by default
        assertEquals(0L, fifo0.read(0x00L, 4))
    }

    @Test
    fun unlimitedPoll7801cAndI2sDmaRxSourcePeersCaptureMovieAudio24BitAnd16Bit() {
        val ctx = TestContext()
        val comp = factory.create(ctx, config(pollProfile = IspSetupFactory.POLL_UNLIMITED)) as StatefulComponent
        val poll1 = ctx.ports.find(IspSetupFactory.PORT_CTRL1_POLL, MmioDevice::class.java)!!
        val c0Low = ctx.ports.find(IspSetupFactory.PORT_CTRL0_LOW, MmioDevice::class.java)!!
        val c0High = ctx.ports.find(IspSetupFactory.PORT_CTRL0_HIGH, MmioDevice::class.java)!!
        val rx0 = assertIs<DmaSourcePeer>(ctx.ports.find(IspSetupFactory.PORT_I2S0_DMA_RX, DmaSourcePeer::class.java))
        val rx1 = assertIs<DmaSourcePeer>(ctx.ports.find(IspSetupFactory.PORT_I2S1_DMA_RX, DmaSourcePeer::class.java))

        // Unlimited soft-reset profile supports 10+ STILL<->MOVIE cycles without exhausting at pos=4
        for (cycle in 0 until 10) {
            poll1.write(0L, 4, 1L)
            assertEquals(0L, poll1.read(0L, 4))
            assertEquals(0L, poll1.read(0L, 4))
        }

        // RX DMA source peer rejects before arming and verifies exact DAT FIFO address
        assertTrue(rx0.overlaps(DualI2sAudioEngine.I2S0_RX_ADDRESS, 4L))
        assertTrue(rx1.overlaps(DualI2sAudioEngine.I2S1_RX_ADDRESS, 4L))
        assertNull(rx0.acceptedAt())
        assertFailsWith<IllegalArgumentException> { rx0.check(DualI2sAudioEngine.I2S0_TX_ADDRESS, 8L) }

        // Arm I2S0 RX DMA in 24-bit mode (exact MOVIE mode configuration observed in live probe)
        ctx.currentTick = 9000L
        c0Low.write(0x04L, 4, 23L) // 24-bit
        c0Low.write(0x10L, 4, DualI2sAudioEngine.RX_START_BIT)
        c0High.write(0x08L, 4, DualI2sAudioEngine.RX_DMA_ENABLE_BIT)
        assertEquals(9000L, rx0.acceptedAt())

        // Claim 16 bytes (4 x 24-bit words = 2 stereo frames) on DMA HW Ch 4
        assertEquals(9000L, rx0.claim(4, DualI2sAudioEngine.I2S0_RX_ADDRESS, 16L))
        val dst = ByteArray(16) { 0x55 }
        rx0.read(4, DualI2sAudioEngine.I2S0_RX_ADDRESS, ByteBuffer.wrap(dst))
        assertContentEquals(ByteArray(16), dst) // deterministic silence by default
        rx0.consume(4)

        // Snapshot upgrade from POLL_REPEAT -> POLL_UNLIMITED works cleanly
        val ctxOld = TestContext()
        val oldRepeat = factory.create(ctxOld, config(pollProfile = IspSetupFactory.POLL_REPEAT)) as StatefulComponent
        val oldPoll = ctxOld.ports.find(IspSetupFactory.PORT_CTRL1_POLL, MmioDevice::class.java)!!
        repeat(2) {
            oldPoll.write(0L, 4, 1L)
            assertEquals(0L, oldPoll.read(0L, 4))
        }
        val oldSnap = oldRepeat.saveSnapshot()
        val ctxNew = TestContext()
        val upgraded = factory.create(ctxNew, config(pollProfile = IspSetupFactory.POLL_UNLIMITED)) as StatefulComponent
        upgraded.restoreSnapshot(oldSnap)
        val newPoll = ctxNew.ports.find(IspSetupFactory.PORT_CTRL1_POLL, MmioDevice::class.java)!!
        newPoll.write(0L, 4, 1L)
        assertEquals(0L, newPoll.read(0L, 4))

        (comp as HardwareComponent).close()
        (oldRepeat as HardwareComponent).close()
        (upgraded as HardwareComponent).close()
    }

    @Test
    fun rxRingPushPullRoundtripHandlesWraparoundBoundedOverrunAndUnderrunSilence() {
        val engine = DualI2sAudioEngine({ 100L }, ringCapacityFrames = 64, rxRingCapacityFrames = 64)
        assertEquals(AudioCaptureStats(), engine.rxStats())

        // 1. Push 40 frames, pull 24, push 40 (wraps past 64-frame boundary), pull remaining 56
        val batchA = ShortArray(40 * 2) { idx -> (1000 + idx).toShort() }
        assertEquals(40, engine.pushRxSamples(batchA, 40))
        assertEquals(AudioCaptureStats(totalRxFrames = 40L, queuedRxFrames = 40, rxOverruns = 0L, rxUnderruns = 0L), engine.rxStats())

        val out1 = ShortArray(24 * 2)
        assertEquals(24, engine.pullRxSamples(0, out1, 24))
        for (i in 0 until 24 * 2) assertEquals(batchA[i], out1[i])

        val batchB = ShortArray(40 * 2) { idx -> (5000 + idx).toShort() }
        assertEquals(40, engine.pushRxSamples(batchB, 40))
        assertEquals(56, engine.rxStats().queuedRxFrames)

        val out2 = ShortArray(56 * 2)
        assertEquals(56, engine.pullRxSamples(1, out2, 56))
        for (i in 0 until 16 * 2) assertEquals(batchA[24 * 2 + i], out2[i])
        for (i in 0 until 40 * 2) assertEquals(batchB[i], out2[16 * 2 + i])
        assertEquals(0, engine.rxStats().queuedRxFrames)
        assertEquals(0L, engine.rxStats().rxOverruns)
        assertEquals(0L, engine.rxStats().rxUnderruns)

        // 2. Bounded overrun (overwrite-oldest): push 50 then 30 into 64-frame ring -> 16 oldest dropped
        val ovA = ShortArray(50 * 2) { idx -> (idx + 1).toShort() }
        val ovB = ShortArray(30 * 2) { idx -> (2000 + idx).toShort() }
        assertEquals(50, engine.pushRxSamples(ovA, 50))
        assertEquals(30, engine.pushRxSamples(ovB, 30))
        val afterOv = engine.rxStats()
        assertEquals(64, afterOv.queuedRxFrames)
        assertEquals(16L, afterOv.rxOverruns)

        val ovOut = ShortArray(64 * 2)
        assertEquals(64, engine.pullRxSamples(0, ovOut, 64))
        // First surviving frame is ovA[16]
        assertEquals(ovA[16 * 2], ovOut[0])
        assertEquals(ovA[16 * 2 + 1], ovOut[1])
        // Tail 30 frames are ovB[0..29]
        assertEquals(ovB[0], ovOut[34 * 2])
        assertEquals(ovB[29 * 2 + 1], ovOut[63 * 2 + 1])

        // Single push larger than ring capacity retains newest 64 frames and accounts overruns
        val huge = ShortArray(80 * 2) { idx -> (3000 + idx).toShort() }
        assertEquals(80, engine.pushRxSamples(huge, 80))
        assertEquals(64, engine.rxStats().queuedRxFrames)
        assertEquals(16L + 16L, engine.rxStats().rxOverruns)
        val hugeOut = ShortArray(64 * 2)
        assertEquals(64, engine.pullRxSamples(0, hugeOut, 64))
        assertEquals(huge[16 * 2], hugeOut[0])
        assertEquals(huge[79 * 2 + 1], hugeOut[63 * 2 + 1])

        // 3. Partial underrun fills tail with silence and records rxUnderruns with zero stall
        val small = shortArrayOf(111, -222, 333, -444)
        assertEquals(2, engine.pushRxSamples(small, 2))
        val underOut = ShortArray(5 * 2) { 0x7777 }
        assertEquals(5, engine.pullRxSamples(0, underOut, 5))
        assertContentEquals(shortArrayOf(111, -222, 333, -444, 0, 0, 0, 0, 0, 0), underOut)
        assertEquals(3L, engine.rxStats().rxUnderruns)

        // 4. Custom rxProvider fallback is honored when RX ring is empty
        engine.rxProvider = { _, out, count ->
            for (i in 0 until count * 2) out[i] = 77
            count
        }
        val provOut = ShortArray(3 * 2)
        assertEquals(3, engine.pullRxSamples(0, provOut, 3))
        assertTrue(provOut.all { it == 77.toShort() })
        assertEquals(3L, engine.rxStats().rxUnderruns) // unchanged when rxProvider supplies frames
    }

    @Test
    fun rxRingFeedsI2sDmaAndPioReadsAcross16Bit24BitAnd32BitPackingAndPreservesSnapshots() {
        val ctx = TestContext()
        val comp = factory.create(ctx, config(pollProfile = IspSetupFactory.POLL_UNLIMITED)) as StatefulComponent
        val c0Low = ctx.ports.find(IspSetupFactory.PORT_CTRL0_LOW, MmioDevice::class.java)!!
        val c0High = ctx.ports.find(IspSetupFactory.PORT_CTRL0_HIGH, MmioDevice::class.java)!!
        val c1Low = ctx.ports.find(IspSetupFactory.PORT_CTRL1_LOW, MmioDevice::class.java)!!
        val c1High = ctx.ports.find(IspSetupFactory.PORT_CTRL1_HIGH, MmioDevice::class.java)!!
        val audio = assertIs<I2sAudioPort>(ctx.ports.find(IspSetupFactory.PORT_I2S_AUDIO, I2sAudioPort::class.java))
        val rx0 = assertIs<DmaSourcePeer>(ctx.ports.find(IspSetupFactory.PORT_I2S0_DMA_RX, DmaSourcePeer::class.java))
        val rx1 = assertIs<DmaSourcePeer>(ctx.ports.find(IspSetupFactory.PORT_I2S1_DMA_RX, DmaSourcePeer::class.java))
        val fifo0 = assertIs<MmioDevice>(ctx.ports.find(IspSetupFactory.PORT_I2S0_FIFO, MmioDevice::class.java))

        // Arm both I2S0 and I2S1 RX DMA
        ctx.currentTick = 12_000L
        c0Low.write(0x10L, 4, DualI2sAudioEngine.RX_START_BIT)
        c0High.write(0x08L, 4, DualI2sAudioEngine.RX_DMA_ENABLE_BIT)
        c1Low.write(0x10L, 4, DualI2sAudioEngine.RX_START_BIT)
        c1High.write(0x08L, 4, DualI2sAudioEngine.RX_DMA_ENABLE_BIT)

        // 1. 16-bit packed stereo over I2S0 DMA RX (L in low16, R in high16)
        c0Low.write(0x04L, 4, 15L) // 16-bit
        assertEquals(2, audio.pushRxSamples(shortArrayOf(0x1234, (-0x1111).toShort(), 32767, -32768), 2))
        assertEquals(12_000L, rx0.claim(4, DualI2sAudioEngine.I2S0_RX_ADDRESS, 8L))
        val buf16 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        rx0.read(4, DualI2sAudioEngine.I2S0_RX_ADDRESS, buf16)
        rx0.consume(4)
        buf16.flip()
        assertEquals(0x1234.toShort(), buf16.short)
        assertEquals((-0x1111).toShort(), buf16.short)
        assertEquals(32767.toShort(), buf16.short)
        assertEquals((-32768).toShort(), buf16.short)

        // 2. 24-bit per-sample 32-bit words over I2S0 DMA RX (exact movie audio capture format)
        c0Low.write(0x04L, 4, 23L) // 24-bit
        assertEquals(2, audio.pushRxSamples(shortArrayOf(0x1234, (-304).toShort(), 32767, -32768), 2))
        assertEquals(12_000L, rx0.claim(4, DualI2sAudioEngine.I2S0_RX_ADDRESS, 16L))
        val buf24 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        rx0.read(4, DualI2sAudioEngine.I2S0_RX_ADDRESS, buf24)
        rx0.consume(4)
        buf24.flip()
        assertEquals(0x00123400, buf24.int)
        assertEquals(0x00FED000, buf24.int)
        assertEquals(0x007FFF00, buf24.int)
        assertEquals(0x00800000, buf24.int)

        // 3. 32-bit per-sample words over I2S1 DMA RX
        c1Low.write(0x04L, 4, 31L) // 32-bit
        assertEquals(1, audio.pushRxSamples(shortArrayOf(0x2345, (-0x3456).toShort()), 1))
        assertEquals(12_000L, rx1.claim(7, DualI2sAudioEngine.I2S1_RX_ADDRESS, 8L))
        val buf32 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        rx1.read(7, DualI2sAudioEngine.I2S1_RX_ADDRESS, buf32)
        rx1.consume(7)
        buf32.flip()
        assertEquals(0x23450000, buf32.int)
        assertEquals((-0x3456) shl 16, buf32.int)

        // 4. PIO +0x00 RX reads in 16-bit and 24-bit modes consume pushed host RX frames
        c0Low.write(0x04L, 4, 15L) // 16-bit
        assertEquals(1, audio.pushRxSamples(shortArrayOf(0x0ABC, 0x1DEF), 1))
        assertEquals(0x1DEF_0ABCL, fifo0.read(0x00L, 4))

        c0Low.write(0x04L, 4, 23L) // 24-bit: 2 consecutive PIO reads return L then R
        assertEquals(1, audio.pushRxSamples(shortArrayOf(0x0555, (-16).toShort()), 1))
        assertEquals(0x00055500L, fifo0.read(0x00L, 4))
        assertEquals(0x00FFF000L, fifo0.read(0x00L, 4))

        // 5. Snapshot save/restore remains 100% compatible and clears transient RX ring cleanly
        assertEquals(1, audio.pushRxSamples(shortArrayOf(99, -99), 1))
        assertEquals(1, audio.rxStats().queuedRxFrames)
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext()
        val comp2 = factory.create(ctx2, config(pollProfile = IspSetupFactory.POLL_UNLIMITED)) as StatefulComponent
        comp2.restoreSnapshot(snap)
        val audio2 = assertIs<I2sAudioPort>(ctx2.ports.find(IspSetupFactory.PORT_I2S_AUDIO, I2sAudioPort::class.java))
        assertEquals(AudioCaptureStats(), audio2.rxStats())

        // 6. Legacy I2sAudioPort implementations inherit safe additive default methods
        val legacyPort = object : I2sAudioPort {
            override fun pushTxSamples(channel: Int, pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int, tick: Long) {}
            override fun pullRxSamples(channel: Int, pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int, tick: Long): Int = 0
            override fun drainPlaybackPcm(maxFrames: Int): AudioPcmChunk? = null
            override fun playbackStats(): AudioPlaybackStats = AudioPlaybackStats(0L, 0L, 0L, 0, 0L, 0, null)
        }
        assertEquals(0, legacyPort.pushRxSamples(shortArrayOf(1, 2), 1))
        assertEquals(AudioCaptureStats(), legacyPort.rxStats())

        (comp as HardwareComponent).close()
        (comp2 as HardwareComponent).close()
    }

    @Test
    fun clockedSceneDmaDatesEachPcmBlockAndRetriesWithoutSkippingSamples() {
        var tick = 10_000L
        val engine = DualI2sAudioEngine(clockProvider = { tick })
        val starts = mutableListOf<Long>()
        engine.setTimedRxSource { out, frames, start ->
            starts += start
            out.fill(100, 0, frames * 2)
            true
        }
        engine.onLowRegisterWrite(0, 0x04L, 15L)
        engine.onLowRegisterWrite(0, 0x10L, DualI2sAudioEngine.RX_START_BIT)
        val rx = engine.createDmaRxPeer(0)
        fun claim() = assertNotNull(rx.claim(4, DualI2sAudioEngine.I2S0_RX_ADDRESS, 16L))
        claim()
        rx.consume(4)
        tick += 4_499L
        claim()
        rx.release(4)
        claim()
        rx.consume(4)
        tick += 200_000L
        claim()
        assertEquals(listOf(10_000L, 14_500L, 14_500L, tick), starts)
    }

    @Test
    fun movieDmaDatesFirstSampleAtExposureAndPreservesTheDelayAcrossSmallDescriptors() {
        var tick = 10_000L
        val exposure = 21_000L
        val starts = mutableListOf<Long>()
        val engine = DualI2sAudioEngine({ tick }, movieExposureTick = { exposure })
        engine.setTimedRxSource { _, _, start -> starts.add(start) }
        engine.onLowRegisterWrite(0, 0x04L, 15L)
        engine.onLowRegisterWrite(0, 0x10L, DualI2sAudioEngine.RX_START_BIT)
        val rx = engine.createDmaRxPeer(0)
        fun read() {
            assertNotNull(rx.claim(4, DualI2sAudioEngine.I2S0_RX_ADDRESS, 16L))
            rx.consume(4)
        }
        tick = 30_000L
        read()
        tick += 4_500L
        read()
        engine.onLowRegisterWrite(0, 0x10L, 0L)
        tick = 80_000L
        engine.onLowRegisterWrite(0, 0x10L, DualI2sAudioEngine.RX_START_BIT)
        // The previous take's exposure predates this peripheral arm.
        tick = 82_000L
        read()
        assertEquals(listOf(exposure, exposure + 4_500L, tick), starts)
    }

    @Test
    fun reusedDmaScratchBuffersAndRingWrapAroundDrainPreserveExactPcm() {
        val engine = DualI2sAudioEngine(clockProvider = { 100L }, ringCapacityFrames = 64, rxRingCapacityFrames = 64)
        engine.onLowRegisterWrite(0, 0x04L, 15L) // 16-bit
        engine.onLowRegisterWrite(0, 0x10L, DualI2sAudioEngine.TX_START_BIT or DualI2sAudioEngine.RX_START_BIT)
        engine.onHighRegisterWrite(0, 0x08L, DualI2sAudioEngine.TX_DMA_ENABLE_BIT or DualI2sAudioEngine.RX_DMA_ENABLE_BIT)

        val tx = engine.createDmaTxPeer(0)
        val rx = engine.createDmaRxPeer(0)

        // Advance ring read/write indices near wrap-around boundary (48 frames written & drained)
        val prime = ByteBuffer.allocate(48 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 48) { prime.putShort((i + 1).toShort()); prime.putShort((-(i + 1)).toShort()) }
        assertNotNull(tx.claim(7, DualI2sAudioEngine.I2S0_TX_ADDRESS, 48L * 4L, 4, 29))
        tx.write(7, DualI2sAudioEngine.I2S0_TX_ADDRESS, 0L, ByteBuffer.wrap(prime.array()))
        tx.finish(7, 110L, 110L)
        assertEquals(48, assertNotNull(engine.drainPlaybackPcm(48)).frames)

        // Write 32 frames crossing the 64-frame wrap boundary using reused TX scratch buffer (smaller size than 48*4)
        val wrapPayload = ByteBuffer.allocate(32 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 32) { wrapPayload.putShort((1000 + i).toShort()); wrapPayload.putShort((-2000 - i).toShort()) }
        assertNotNull(tx.claim(7, DualI2sAudioEngine.I2S0_TX_ADDRESS, 32L * 4L, 4, 29))
        tx.write(7, DualI2sAudioEngine.I2S0_TX_ADDRESS, 0L, ByteBuffer.wrap(wrapPayload.array()))
        tx.finish(7, 120L, 120L)

        val drained = assertNotNull(engine.drainPlaybackPcm(64))
        assertEquals(32, drained.frames)
        for (i in 0 until 32) {
            assertEquals((1000 + i).toShort(), drained.samples[i * 2])
            assertEquals((-2000 - i).toShort(), drained.samples[i * 2 + 1])
        }

        // Reuse RX scratch buffer across growing then shrinking 16-bit and 8-bit beats
        val rxIn = ShortArray(20) { idx -> if (idx % 2 == 0) (0x1200 + idx * 16).toShort() else (-0x2400 - idx * 16).toShort() }
        assertEquals(10, engine.pushRxSamples(rxIn, 10))
        assertNotNull(rx.claim(6, DualI2sAudioEngine.I2S0_RX_ADDRESS, 24L)) // 6 frames
        val b6 = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        rx.read(6, DualI2sAudioEngine.I2S0_RX_ADDRESS, b6)
        rx.consume(6)
        b6.flip()
        for (f in 0 until 6) {
            assertEquals(rxIn[f * 2], b6.short)
            assertEquals(rxIn[f * 2 + 1], b6.short)
        }
        assertNotNull(rx.claim(6, DualI2sAudioEngine.I2S0_RX_ADDRESS, 16L)) // 4 frames (reuses buffer)
        val b4 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        rx.read(6, DualI2sAudioEngine.I2S0_RX_ADDRESS, b4)
        rx.consume(6)
        b4.flip()
        for (f in 0 until 4) {
            assertEquals(rxIn[(6 + f) * 2], b4.short)
            assertEquals(rxIn[(6 + f) * 2 + 1], b4.short)
        }
    }

    @Test
    fun inactiveRxCaptureReturnsIdleSilenceWithoutSpuriousUnderrunsAndHonorsFallbackProvider() {
        val engine = DualI2sAudioEngine({ 200L }, ringCapacityFrames = 64, rxRingCapacityFrames = 64)
        val out = ShortArray(16 * 2) { 0x5555 }

        // Before host capture is enabled, guest I2S0 RX DMA pulls idle silence with zero spurious rxUnderruns
        assertEquals(16, engine.pullRxSamples(0, out, 16))
        assertTrue(out.all { it == 0.toShort() })
        assertEquals(0L, engine.rxStats().rxUnderruns)
        assertEquals(0L, engine.rxStats().rxOverruns)

        // Enabling capture with empty ring records real underruns
        engine.setRxCaptureActive(true)
        assertEquals(8, engine.pullRxSamples(0, out, 8))
        assertEquals(8L, engine.rxStats().rxUnderruns)

        // Installing an on-demand fallback provider (e.g., TEST_TONE) feeds frames with zero additional underruns
        engine.setRxFallbackProvider { _, buf, frames ->
            for (i in 0 until frames * 2) buf[i] = 4096
            frames
        }
        assertEquals(16, engine.pullRxSamples(0, out, 16))
        assertTrue(out.all { it == 4096.toShort() })
        assertEquals(8L, engine.rxStats().rxUnderruns)

        // Disabling capture clears fallback/queued frames and resumes idle silence without underruns
        engine.setRxFallbackProvider(null)
        engine.pushRxSamples(ShortArray(4 * 2) { 99 }, 4)
        assertEquals(4, engine.rxStats().queuedRxFrames)
        engine.setRxCaptureActive(false)
        assertEquals(0, engine.rxStats().queuedRxFrames)
        assertEquals(16, engine.pullRxSamples(0, out, 16))
        assertTrue(out.all { it == 0.toShort() })
        assertEquals(8L, engine.rxStats().rxUnderruns)
    }

    @Test
    fun pacedBytesPerFrameReflectsWordBitDepthFor16BitAnd24BitModes() {
        val ctx = TestContext()
        val comp = factory.create(ctx, config())
        val c0Low = ctx.ports.find(IspSetupFactory.PORT_CTRL0_LOW, MmioDevice::class.java)!!
        val c1Low = ctx.ports.find(IspSetupFactory.PORT_CTRL1_LOW, MmioDevice::class.java)!!
        val tx0 = assertIs<RangedDmaDestinationPeer>(ctx.ports.find(IspSetupFactory.PORT_I2S0_DMA_TX, DmaDestinationPeer::class.java))
        val tx1 = assertIs<RangedDmaDestinationPeer>(ctx.ports.find(IspSetupFactory.PORT_I2S1_DMA_TX, DmaDestinationPeer::class.java))
        val rx0 = assertIs<DmaSourcePeer>(ctx.ports.find(IspSetupFactory.PORT_I2S0_DMA_RX, DmaSourcePeer::class.java))
        val rx1 = assertIs<DmaSourcePeer>(ctx.ports.find(IspSetupFactory.PORT_I2S1_DMA_RX, DmaSourcePeer::class.java))

        // Default 16-bit packed stereo: 4 bytes (1 word) per stereo frame
        assertEquals(4, tx0.pacedBytesPerFrame())
        assertEquals(4, rx0.pacedBytesPerFrame())

        // 24-bit Movie I2S (+0x0C = 23): 8 bytes (2 words: L + R) per stereo frame
        c0Low.write(0x04L, 4, 23L)
        c1Low.write(0x04L, 4, 23L)
        assertEquals(8, tx0.pacedBytesPerFrame())
        assertEquals(8, rx0.pacedBytesPerFrame())
        assertEquals(8, tx1.pacedBytesPerFrame())
        assertEquals(8, rx1.pacedBytesPerFrame())

        // Back to 16-bit STILL beep (+0x0C = 15): 4 bytes per stereo frame
        c0Low.write(0x04L, 4, 15L)
        assertEquals(4, tx0.pacedBytesPerFrame())
        assertEquals(4, rx0.pacedBytesPerFrame())
        comp.close()
    }
}
