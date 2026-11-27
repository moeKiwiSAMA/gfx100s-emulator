package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.desktop.audio.*
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.LineUnavailableException
import kotlin.math.sin
import kotlin.test.*

class HostAudioPlaybackTest {
    private class StubPorts : Ports {
        private val map = linkedMapOf<String, Any>()
        override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
            map[name] = port
        }
        override fun <T : Any> find(name: String, type: Class<T>): T? = map[name]?.let(type::cast)
        override fun removeOwner(owner: String) {
            map.clear()
        }
    }

    private class StubI2sPort : I2sAudioPort {
        private val queue = ArrayDeque<Short>()
        private var txFrames = 0L
        private var seq = 0L
        @Synchronized
        override fun pushTxSamples(channel: Int, pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int, tick: Long) {
            for (i in 0 until frames * 2) queue.addLast(pcmStereoInterleaved[i])
            txFrames += frames
            seq++
        }
        @Synchronized
        override fun pullRxSamples(channel: Int, pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int, tick: Long): Int {
            pcmStereoInterleaved.fill(0, 0, frames * 2)
            return frames
        }
        @Synchronized
        override fun drainPlaybackPcm(maxFrames: Int): AudioPcmChunk? {
            if (queue.isEmpty()) return null
            val avail = minOf(maxFrames, queue.size / 2)
            val out = ShortArray(avail * 2)
            for (i in 0 until avail * 2) out[i] = queue.removeFirst()
            return AudioPcmChunk(0, 48_000, 16, out, avail, seq, 0L)
        }
        @Synchronized
        override fun playbackStats() = AudioPlaybackStats(txFrames, txFrames, 0L, queue.size / 2, seq, 1, null)
    }

    private class StubCodec(var route: AudioOutputRoute) : AudioCodecControlPort {
        override fun exchange16(mosi: Int): Int = 0
        override fun readRegister(address: Int): Int = 0
        override fun outputRoute(): AudioOutputRoute = route
        override fun resetCount(): Long = 1L
        override fun dsp2ProgramWriteCount(): Int = 2560
        override fun dsp2DataWriteCount(): Int = 1570
        override fun dsp2CalibrationWriteCount(): Int = 0
    }

    @Test
    fun queuedCodecAudioUsesTheSampleRouteAndLiveHostMuteAndVolume() {
        val ports = StubPorts()
        val audible = AudioOutputRoute(false, 0, true, 121, 115, false, true, true,
            1f, 0.5f, AudioSinkKind.HEADPHONE_STEREO)
        val boardCodec = StubCodec(audible.copy(activeSink = AudioSinkKind.MUTED, dacMuted = true))
        val queue = ArrayDeque<RoutedAudioPcm>()
        val i2s = object : RoutedI2sOutput, I2sAudioPort by StubI2sPort() {
            override fun bindOutputCodec(codec: AudioCodecControlPort?) { assertSame(boardCodec, codec) }
            override fun drainRoutedCodecPcm(maxFrames: Int): RoutedAudioPcm? = queue.removeFirstOrNull()
        }
        ports.register("test", "soc-isp-setup.i2s-audio", I2sAudioPort::class.java, i2s)
        ports.register("test", "wm8962.codec", AudioCodecControlPort::class.java, boardCodec)
        val rendered = ByteArrayOutputStream()
        HostAudioPlayback({ ports }, AudioPlaybackConfig(enabled = false),
            AudioLineProvider { _, _ -> error("offline test must not open an endpoint") },
            { bytes, count -> rendered.write(bytes, 0, count) }).use { host ->
            host.bindPorts(ports)
            val pulse = AudioPcmChunk(0, 48_000, 16, shortArrayOf(4000, -4000), 1, 1, 1125)
            repeat(3) { queue.addLast(RoutedAudioPcm(pulse, audible)) }
            host.applyPatch(mapOf("bufferMs" to 10, "volume" to 0.5))
            assertTrue(host.pumpOnce())
            host.setMuted(true)
            assertTrue(host.pumpOnce())
            host.applyPatch(mapOf("bufferMs" to 120, "muted" to false, "volume" to 1.0))
            assertTrue(host.pumpOnce())
            queue.addLast(RoutedAudioPcm(pulse, boardCodec.route))
            boardCodec.route = audible
            assertTrue(host.pumpOnce())
            assertFalse(host.pumpOnce())
            val pcm = ByteBuffer.wrap(rendered.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = ShortArray(pcm.remaining())
            pcm.get(samples)
            assertContentEquals(shortArrayOf(2000, -1000, 0, 0, 4000, -2000, 0, 0), samples)
        }
    }

    @Test
    fun launchConfigValidationAcceptsOptionalAudioAndRejectsInvalidValues() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val base = Formats.read(repo.resolve("tools/release/emulator-release-linux.yaml"))

        validateLaunchConfiguration(base)
        validateLaunchConfiguration(base + ("audio" to true))
        validateLaunchConfiguration(base + ("audio" to false))
        validateLaunchConfiguration(base + ("audio" to mapOf("enabled" to true, "volume" to 0.75, "muted" to false, "bufferMs" to 80)))

        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(base + ("audio" to mapOf("enabled" to true, "volume" to 1.5)))
        }
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(base + ("audio" to mapOf("enabled" to true, "bufferMs" to 5)))
        }
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(base + ("audio" to mapOf("unknown" to true)))
        }
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(base + ("audio" to "on"))
        }
    }

    @Test
    fun headlessNoSoundcardDegradesSilentlyWithoutThrowingOrLeakingThreads() {
        val ports = StubPorts()
        val i2s = StubI2sPort()
        ports.register("test", "soc-isp-setup.i2s-audio", I2sAudioPort::class.java, i2s)

        val renderedBytes = ByteArrayOutputStream()
        val failingProvider = AudioLineProvider { _, _ ->
            throw LineUnavailableException("Simulated headless container with no ALSA/PulseAudio device")
        }

        HostAudioPlayback(
            portsProvider = { ports },
            initialConfig = AudioPlaybackConfig(enabled = true, volume = 0.5),
            lineProvider = failingProvider,
            onRenderedPcm = { buf, len -> renderedBytes.write(buf, 0, len) },
        ).use { playback ->
            val mode = playback.startIfEnabled()
            assertEquals(AudioBackendMode.HEADLESS_SILENT, mode)
            assertNotNull(playback.degradationReason)

            // Push 64 frames and verify silent backend still drains & scales cleanly without throwing
            val samples = ShortArray(128) { idx -> if (idx % 2 == 0) 2000.toShort() else (-2000).toShort() }
            i2s.pushTxSamples(0, samples, 64)
            awaitFramesPlayed(playback, 64L)
            assertEquals(64L, playback.status()["framesPlayed"])
            assertEquals(256, renderedBytes.size())
            val firstL = ByteBuffer.wrap(renderedBytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).short
            assertEquals(1000.toShort(), firstL) // volume 0.5 applied
        }

        val leaked = Thread.getAllStackTraces().keys.filter { it.name == "fuji-audio-playback" && it.isAlive }
        assertTrue(leaked.isEmpty(), "Expected zero leaked fuji-audio-playback threads")
    }

    @Test
    fun shortOutputWritesDeliverEveryFrameAfterTemporaryBackpressure() {
        val ports = StubPorts()
        val i2s = StubI2sPort()
        ports.register("test", "soc-isp-setup.i2s-audio", I2sAudioPort::class.java, i2s)
        val received = ByteArrayOutputStream()
        var attempts = 0
        val output = object : AudioOutputLine {
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                attempts++
                if (attempts == 2) return 0
                val accepted = minOf(8, length)
                received.write(bytes, offset, accepted)
                return accepted
            }
            override fun close() = Unit
        }
        val samples = ShortArray(100) { (it * 103 - 3000).toShort() }
        val expected = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { expected.putShort(it) }
        HostAudioPlayback(
            { ports }, AudioPlaybackConfig(enabled = true, volume = 1.0),
            lineProvider = AudioLineProvider { _, _ -> output },
        ).use { playback ->
            playback.startIfEnabled()
            i2s.pushTxSamples(0, samples, samples.size / 2)
            awaitFramesPlayed(playback, (samples.size / 2).toLong())
            assertContentEquals(expected.array(), received.toByteArray())
        }
    }

    @Test
    fun permanentlyStalledOutputDegradesAndReportsOnlyAcceptedFrames() {
        val ports = StubPorts()
        val i2s = StubI2sPort()
        ports.register("test", "soc-isp-setup.i2s-audio", I2sAudioPort::class.java, i2s)
        val output = object : AudioOutputLine {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = if (offset == 0) 4 else 0
            override fun close() = Unit
        }
        HostAudioPlayback(
            { ports }, AudioPlaybackConfig(enabled = true, bufferMs = 10),
            lineProvider = AudioLineProvider { _, _ -> output },
        ).use { playback ->
            playback.startIfEnabled()
            i2s.pushTxSamples(0, ShortArray(64), 32)
            awaitFramesPlayed(playback, 1)
            assertEquals(AudioBackendMode.HEADLESS_SILENT, playback.backendMode)
            assertContains(playback.degradationReason.orEmpty(), "no progress")
        }
    }

    @Test
    fun stalledOutputCanBeStoppedWithoutCountingUnwrittenFrames() {
        val ports = StubPorts()
        val i2s = StubI2sPort()
        ports.register("test", "soc-isp-setup.i2s-audio", I2sAudioPort::class.java, i2s)
        val attempted = CountDownLatch(1)
        val output = object : AudioOutputLine {
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                attempted.countDown()
                return 0
            }
            override fun close() = Unit
        }
        HostAudioPlayback(
            { ports }, AudioPlaybackConfig(enabled = true),
            lineProvider = AudioLineProvider { _, _ -> output },
        ).use { playback ->
            playback.startIfEnabled()
            i2s.pushTxSamples(0, ShortArray(64), 32)
            assertTrue(attempted.await(3, TimeUnit.SECONDS))
            playback.stop()
            assertEquals(0L, playback.status()["framesPlayed"])
            assertEquals(AudioBackendMode.DISABLED, playback.backendMode)
        }
    }

    @Test
    fun mockSourceDataLineAppliesCodecRoutingMasterVolumeMuteAndGpio35mmJacks() {
        val ports = StubPorts()
        val i2s = StubI2sPort()
        val gpioEvents = CopyOnWriteArrayList<Triple<Int, Int, Boolean>>()
        val codec = StubCodec(
            AudioOutputRoute(
                speakerEnabled = true,
                speakerVolume = 0x79,
                headphoneEnabled = false,
                headphoneVolumeLeft = 0,
                headphoneVolumeRight = 0,
                dacMuted = false,
                sysclkEnabled = true,
                dsp2Enabled = true,
                effectiveLeftGain = 1.0f,
                effectiveRightGain = 1.0f,
                activeSink = AudioSinkKind.SPEAKER_MONO,
            ),
        )
        ports.register("test", "soc-isp-setup.i2s-audio", I2sAudioPort::class.java, i2s)
        ports.register("test", "soc-spi.wm8962.codec", AudioCodecControlPort::class.java, codec)
        ports.register("test", "soc-gpio.inputs", GpioInputPort::class.java, GpioInputPort { p, pin, high ->
            gpioEvents += Triple(p, pin, high)
        })

        val captured = ByteArrayOutputStream()
        var lineClosed = false
        val mockProvider = AudioLineProvider { format, bufferBytes ->
            assertEquals(48_000f, format.sampleRate)
            assertEquals(16, format.sampleSizeInBits)
            assertEquals(2, format.channels)
            assertFalse(format.isBigEndian)
            assertTrue(bufferBytes >= 2048)
            object : AudioOutputLine {
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    captured.write(bytes, offset, length)
                    return length
                }
                override fun close() {
                    lineClosed = true
                }
            }
        }

        HostAudioPlayback(
            portsProvider = { ports },
            initialConfig = AudioPlaybackConfig(enabled = true, volume = 1.0),
            lineProvider = mockProvider,
        ).use { playback ->
            assertEquals(AudioBackendMode.ACTIVE_LINE, playback.startIfEnabled())

            // 1. Speaker mono mode mixes L=4000, R=0 -> (2000, 2000)
            i2s.pushTxSamples(0, shortArrayOf(4000, 0), 1)
            awaitFramesPlayed(playback, 1L)
            val monoBuf = ByteBuffer.wrap(captured.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2000.toShort(), monoBuf.short)
            assertEquals(2000.toShort(), monoBuf.short)
            captured.reset()

            // 2. Headphone stereo mode preserves distinct L/R channels & scales by masterVolume
            codec.route = codec.route.copy(
                speakerEnabled = false,
                headphoneEnabled = true,
                effectiveLeftGain = 1.0f,
                effectiveRightGain = 0.5f,
                activeSink = AudioSinkKind.HEADPHONE_STEREO,
            )
            playback.setVolume(0.5)
            i2s.pushTxSamples(0, shortArrayOf(4000, 4000), 1)
            awaitFramesPlayed(playback, 2L)
            val stereoBuf = ByteBuffer.wrap(captured.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2000.toShort(), stereoBuf.short) // 4000 * 1.0 * 0.5
            assertEquals(1000.toShort(), stereoBuf.short) // 4000 * 0.5 * 0.5
            captured.reset()

            // 3. Mute zeroes output bytes
            playback.setMuted(true)
            i2s.pushTxSamples(0, shortArrayOf(12000, -12000), 1)
            awaitFramesPlayed(playback, 3L)
            assertTrue(captured.toByteArray().all { it == 0.toByte() })

            // 4. 3.5mm jack GPIO helpers:
            //    - Headphone jack (Bank 8 Pin 4, DAT 0x01812DC4): active-HIGH (plugged=true, unplugged=false)
            //    - External mic jack (Bank 6 Pin 4, DAT 0x0123C790): active-LOW (plugged=false, unplugged=true)
            assertTrue(playback.setHeadphonePlugged(true))
            assertTrue(playback.setExtMicPlugged(true))
            assertTrue(playback.setHeadphonePlugged(false))
            assertEquals(
                listOf(
                    Triple(HostAudioPlayback.HEADPHONE_GPIO_PORT, HostAudioPlayback.HEADPHONE_GPIO_PIN, true),
                    Triple(HostAudioPlayback.EXT_MIC_GPIO_PORT, HostAudioPlayback.EXT_MIC_GPIO_PIN, false),
                    Triple(HostAudioPlayback.HEADPHONE_GPIO_PORT, HostAudioPlayback.HEADPHONE_GPIO_PIN, false),
                ),
                gpioEvents,
            )
        }
        assertTrue(lineClosed)
    }

    @Test
    fun endToEndFirmware48kHzBeepAndShutterPlaybackThroughDmaI2sWm8962AndHostAudio(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val fixtures = IntegrationTest()
        val home = fixtures.fixture()
        try {
            // Configure soc-spi SPI4 -> WM8962, soc-isp-setup -> dual-I2S PCM DMA, soc-dma -> I2S0/I2S1 TX routes
            Formats.atomicYaml(
                home.resolve("compoments/soc-spi/soc-spi.yaml"),
                mapOf(
                    "profile" to "spi-fifo-no-peer-experimental-v1",
                    "peerPort" to "",
                    "portsFile" to "ports.json",
                    "continuousFixedMiso" to false,
                ),
            )
            java.nio.file.Files.writeString(
                home.resolve("compoments/soc-spi/ports.json"),
                Formats.jsonText(
                    mapOf(
                        "profile" to "spi-additional-ports-experimental-v1",
                        "controllers" to listOf(
                            mapOf("index" to 4, "peerPort" to "soc-spi.wm8962"),
                        ),
                    ),
                ),
            )
            Formats.atomicYaml(
                home.resolve("compoments/soc-isp-setup/soc-isp-setup.yaml"),
                mapOf(
                    "profile" to "isp-setup-retained-experimental-v1",
                    "initialFill" to 0,
                    "poll7801cProfile" to "fff7801c-setup-repeat-v1",
                    "i2sAudioProfile" to "dual-i2s-pcm-dma-experimental-v1",
                ),
            )
            Formats.atomicYaml(
                home.resolve("compoments/soc-dma/soc-dma.yaml"),
                mapOf(
                    "profile" to "standalone-experimental-v1",
                    "memoryPort" to "test.audio.ram",
                    "interruptPort" to "test.audio.gic",
                    "destinationPeersFile" to "destination-ports.yaml",
                ),
            )
            Formats.atomicYaml(
                home.resolve("compoments/soc-dma/destination-ports.yaml"),
                mapOf(
                    "schema" to 1,
                    "profile" to "named-destination-ports-experimental-v1",
                    "ports" to listOf("soc-isp-setup.i2s0-dma-tx", "soc-isp-setup.i2s1-dma-tx"),
                ),
            )

            val config = mapOf<String, Any>(
                "schema" to 1,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
                "firmwareFile" to tempRoot.resolve("unused.dat").toString(),
                "nativeLibrary" to tempRoot.resolve("unused.so").toString(),
                "instructionBudget" to 1000,
                "enabledDevices" to listOf("soc-spi", "soc-isp-setup", "soc-dma"),
            )

            // Extract real GFX100S 2.13 48 kHz focus beep & electronic shutter 1 waveforms if section4.bin is present locally;
            // fall back to deterministic 48 kHz 16-bit stereo synthetic waveforms otherwise (CI with no DAT).
            val section4 = repo.resolve("firmware-data/gfx100s/2.13/decoded/section4.bin")
            val focusBeepBytes = FirmwareEmbeddedWaveforms.readRawBytesIfPresent(section4, FirmwareEmbeddedWaveforms.UI_BEEP_FOCUS)
                ?: synthetic48kStereoTone(frames = 2400, freqHz = 2000.0, amplitude = 4599)
            val shutterBytes = FirmwareEmbeddedWaveforms.readRawBytesIfPresent(section4, FirmwareEmbeddedWaveforms.ESHUTTER_1_FULL)
                ?: synthetic48kStereoTone(frames = 2048, freqHz = 880.0, amplitude = 24000)

            assertTrue(FirmwareEmbeddedWaveforms.peakAmplitude16(focusBeepBytes) >= 4000)
            assertTrue(FirmwareEmbeddedWaveforms.peakAmplitude16(shutterBytes) >= 16000)

            // Back DMA source RAM at 0x1A234000 (matching TSK_SOUNDIO ring buffer A)
            val ramBase = 0x1A23_4000L
            val ramBacking = ByteArray(maxOf(focusBeepBytes.size, shutterBytes.size) + 4096)
            val firedIrqs = CopyOnWriteArrayList<Pair<Int, Boolean>>()

            EmulatorRuntime(home, config).use { runtime ->
                assertFalse(runtime.audioPlayback.enabled, "Host audio must be OFF by default")

                // Register RAM & GIC sink peers for DMA transfers on owner thread
                runtime.owner.submit {
                    runtime.host.ports.register("test", "test.audio.gic", InterruptSink::class.java, object : InterruptSink {
                        override fun setLine(irq: Int, high: Boolean) {
                            firedIrqs += irq to high
                        }
                    })
                    runtime.host.ports.register("test", "test.audio.ram", DmaMemory::class.java, object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            require(address >= ramBase && address + size <= ramBase + ramBacking.size)
                        }
                        override fun read(address: Long, destination: ByteBuffer) {
                            val off = (address - ramBase).toInt()
                            destination.put(ramBacking, off, destination.remaining())
                        }
                        override fun write(address: Long, source: ByteBuffer) {
                            val off = (address - ramBase).toInt()
                            source.get(ramBacking, off, source.remaining())
                        }
                    })

                    // 1. Initialize WM8962 via SPI4: reset, SYSCLK, DSP2, mono Class-D speaker @ 0 dB
                    val spi4 = runtime.host.ports.find("soc-spi.spi4", MmioDevice::class.java) as ScheduledMmioDevice
                    spi4.write(0x00L, 4, 0x000FL) // 16-bit
                    spi4.write(0x10L, 4, 28L)     // divider 28
                    spi4.write(0x04L, 4, 0x000AL) // master enable
                    var tick = 0L
                    fun sendSpi4(word: Int) {
                        spi4.write(0x08L, 4, word.toLong())
                        tick = checkNotNull(spi4.nextDeadline())
                        spi4.beforeInstruction(tick)
                        spi4.read(0x08L, 4)
                    }
                    for ((reg, value) in listOf(
                        0x000F to 0x6243,
                        0x0008 to 0x09E4,
                        0x0300 to 0x0009,
                        0x001A to 0x0010, // SPKOUTL_ENA
                        0x0031 to 0x0040, // Class-D enable
                        0x0028 to 0x0179, // SPKOUTL 0 dB
                    )) {
                        sendSpi4(reg)
                        sendSpi4(value)
                    }
                    val codec = assertNotNull(runtime.host.ports.find("soc-spi.wm8962.codec", AudioCodecControlPort::class.java))
                    assertEquals(AudioSinkKind.SPEAKER_MONO, codec.outputRoute().activeSink)

                    // 2. Arm I2S Ch 0 TX (0xFFF4F004, section4 @ 0x011E1AC0 / 0x011E1B00) in 16-bit TX DMA mode
                    val c0Low = runtime.host.ports.find("soc-isp-setup.ctrl0-low", MmioDevice::class.java)!!
                    val c0High = runtime.host.ports.find("soc-isp-setup.ctrl0-high", MmioDevice::class.java)!!
                    c0Low.write(0x00L, 4, 0x1000254BL)
                    c0Low.write(0x04L, 4, 15L) // 16-bit
                    c0Low.write(0x10L, 4, 0x0001_0000L) // TX enable
                    c0High.write(0x08L, 4, 0x0100_0000L) // TX DMA enable

                    // 3. Stream focus beep then electronic shutter over soc-dma HW Ch 7 (req 29, burst 16, GIC 214) -> 0xFFF4F004
                    val dmaMux = runtime.host.ports.find("soc-dma.mux", MmioDevice::class.java)!!
                    val dmaRegs = runtime.host.ports.find("soc-dma.registers", MmioDevice::class.java) as ScheduledMmioDevice
                    // HW Ch 7 is lane 1 of mux word index 3 (strobe bit 24 = 0x01000000, bits 13:8 = req 29)
                    dmaMux.write(3L * 4L, 4, 0x0100_0000L or (29L shl 8))

                    fun streamViaDmaCh7(pcmBytes: ByteArray) {
                        pcmBytes.copyInto(ramBacking, 0)
                        val base = 7L * 0x1000L
                        dmaRegs.write(base + 0x10L, 4, (pcmBytes.size - 1).toLong()) // count - 1
                        dmaRegs.write(base + 0x14L, 4, ramBase)                      // source RAM
                        dmaRegs.write(base + 0x18L, 4, 0xFFF4F004L)                  // I2S0 TX FIFO (+0x04)
                        dmaRegs.write(base + 0x1CL, 4, 0x0002_0F00L)                 // 32-bit burst-16 incrementing src
                        dmaRegs.write(base + 0x20L, 4, 0x0002_0F04L)                 // 32-bit burst-16 fixed dst
                        dmaRegs.write(base + 0x24L, 4, 0L)                           // non-chained
                        dmaRegs.write(base + 0x28L, 4, 0x1020_1011L)                 // start TX DMA
                        val doneAt = checkNotNull(dmaRegs.nextDeadline())
                        dmaRegs.beforeInstruction(doneAt)
                        // Clear completion status & deassert GIC 214 before next submission
                        dmaRegs.write(base + 0x30L, 4, 1L)
                        dmaRegs.beforeInstruction(doneAt + 1L)
                    }

                    streamViaDmaCh7(focusBeepBytes)
                    streamViaDmaCh7(shutterBytes)
                }.join()
                assertTrue(firedIrqs.count { it == (214 to true) } == 2, "Expected GIC 214 (DMA HW Ch 7) to fire twice, got $firedIrqs")

                // 4. Drain captured PCM through HostAudioPlayback (mock SourceDataLine) and verify bit-exact stereo frames
                val playedOut = ByteArrayOutputStream()
                HostAudioPlayback(
                    portsProvider = { runtime.host.ports },
                    initialConfig = AudioPlaybackConfig(enabled = true, volume = 1.0),
                    lineProvider = AudioLineProvider { _, _ ->
                        object : AudioOutputLine {
                            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                                playedOut.write(bytes, offset, length)
                                return length
                            }
                            override fun close() {}
                        }
                    },
                ).use { playback ->
                    runtime.owner.submit { playback.bindPorts(runtime.host.ports) }.join()
                    assertEquals(AudioBackendMode.ACTIVE_LINE, playback.startIfEnabled())
                    val expectedTotalBytes = focusBeepBytes.size + shutterBytes.size
                    val expectedFrames = (expectedTotalBytes / 4).toLong()
                    val deadlineMs = System.currentTimeMillis() + 3000L
                    while ((playback.status()["framesPlayed"] as Long) < expectedFrames && System.currentTimeMillis() < deadlineMs) {
                        Thread.sleep(5L)
                    }
                    val actualBytes = playedOut.toByteArray()
                    assertEquals(expectedTotalBytes, actualBytes.size)
                    // Because GFX100S embedded waveforms are L==R mono-in-stereo and SPKOUTL is 0 dB @ volume 1.0,
                    // peak amplitude and frame count must match the firmware waveform!
                    assertEquals(
                        maxOf(
                            FirmwareEmbeddedWaveforms.peakAmplitude16(focusBeepBytes),
                            FirmwareEmbeddedWaveforms.peakAmplitude16(shutterBytes),
                        ),
                        FirmwareEmbeddedWaveforms.peakAmplitude16(actualBytes),
                    )
                    assertEquals(expectedFrames, playback.status()["framesPlayed"])
                }

                // 5. Verify ControlServer GET/POST /api/audio endpoints
                runtime.serve()
                val port = runtime.server!!.address.port
                val http = HttpClient.newHttpClient()
                val getResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/audio")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, getResp.statusCode(), getResp.body())
                val statusJson = Formats.json.readValue(getResp.body(), Map::class.java)
                assertEquals(false, statusJson["enabled"])
                assertEquals(true, statusJson["i2sPortBound"])
                assertEquals(true, statusJson["codecPortBound"])

                val postResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/audio"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"volume":0.6,"muted":true}"""))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, postResp.statusCode(), postResp.body())
                val updatedJson = Formats.json.readValue(postResp.body(), Map::class.java)
                assertEquals(0.6, (updatedJson["volume"] as Number).toDouble(), 0.001)
                assertEquals(true, updatedJson["muted"])
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    private fun awaitFramesPlayed(playback: HostAudioPlayback, expectedFrames: Long, timeoutMs: Long = 3000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while ((playback.status()["framesPlayed"] as Long) < expectedFrames && System.currentTimeMillis() < deadline) {
            playback.pumpOnce()
            Thread.sleep(5L)
        }
        assertEquals(expectedFrames, playback.status()["framesPlayed"], "Timed out waiting for $expectedFrames audio frames")
    }

    private fun synthetic48kStereoTone(frames: Int, freqHz: Double, amplitude: Int): ByteArray {
        val bb = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val s = (sin(2.0 * Math.PI * freqHz * i / 48_000.0) * amplitude).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            bb.putShort(s)
            bb.putShort(s)
        }
        return bb.array()
    }
}
