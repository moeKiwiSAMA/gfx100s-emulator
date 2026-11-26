package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.desktop.audio.*
import com.github.nyabanban.fuji.emulator.runtime.ControlServer
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.LineUnavailableException
import kotlin.math.abs
import kotlin.test.*

class HostAudioDuplexAndLoopbackTest {
    private class StubPorts : Ports {
        private val map = linkedMapOf<String, Any>()
        override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
            map[name] = port
        }
        override fun <T : Any> find(name: String, type: Class<T>): T? = map[name]?.let(type::cast)
        override fun removeOwner(owner: String) = map.clear()
    }

    /** Stub I2sAudioPort exposing A1-compatible `pushRxSamples` & `rxStats` overrides. */
    private class DuplexStubI2sPort : I2sAudioPort {
        private val txQueue = ArrayDeque<Short>()
        private val rxQueue = ArrayDeque<Short>()
        private var totalRx = 0L
        private var overruns = 0L
        private val rxCapacityFrames = 4096

        @Synchronized
        override fun pushTxSamples(channel: Int, pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int, tick: Long) {
            for (i in 0 until frames * 2) txQueue.addLast(pcmStereoInterleaved[i])
        }

        @Synchronized
        override fun pullRxSamples(channel: Int, pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int, tick: Long): Int {
            val avail = minOf(frames, rxQueue.size / 2)
            for (i in 0 until avail * 2) pcmStereoInterleaved[i] = rxQueue.removeFirst()
            if (avail < frames) pcmStereoInterleaved.fill(0, avail * 2, frames * 2)
            return frames
        }

        @Synchronized
        override fun drainPlaybackPcm(maxFrames: Int): AudioPcmChunk? {
            if (txQueue.isEmpty()) return null
            val avail = minOf(maxFrames, txQueue.size / 2)
            val out = ShortArray(avail * 2)
            for (i in 0 until avail * 2) out[i] = txQueue.removeFirst()
            return AudioPcmChunk(0, 48_000, 16, out, avail, 1L, 0L)
        }

        @Synchronized
        override fun playbackStats() = AudioPlaybackStats(0L, 0L, 0L, txQueue.size / 2, 1L, 1, null)

        @Synchronized
        override fun pushRxSamples(pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int): Int {
            for (i in 0 until frames) {
                if (rxQueue.size / 2 >= rxCapacityFrames) {
                    rxQueue.removeFirst()
                    rxQueue.removeFirst()
                    overruns++
                }
                rxQueue.addLast(pcmStereoInterleaved[i * 2])
                rxQueue.addLast(pcmStereoInterleaved[i * 2 + 1])
            }
            totalRx += frames
            return frames
        }

        @Synchronized
        override fun rxStats(): AudioCaptureStats = AudioCaptureStats(totalRx, rxQueue.size / 2, overruns, 0L)
    }

    private class MockNativeBackend(
        override val available: Boolean = true,
        override val backendName: String = "WASAPI",
    ) : NativeAudioBackend {
        val openedOutputDevices = CopyOnWriteArrayList<Pair<String, Int>>()
        val openedInputRequests = CopyOnWriteArrayList<Triple<String, Boolean, Int>>()
        val outputWrittenBytes = AtomicInteger(0)
        var loopbackSampleL: Short = 6000
        var loopbackSampleR: Short = -4500
        var micSampleL: Short = 3000
        var micSampleR: Short = 2000

        override fun enumerate(direction: AudioDeviceDirection): List<AudioDeviceDescriptor> = when (direction) {
            AudioDeviceDirection.OUTPUT -> listOf(
                AudioDeviceDescriptor("wasapi:spk-0", "Speakers (Realtek)", true, backendName),
                AudioDeviceDescriptor("wasapi:hp-1", "Headphones (USB DAC)", false, backendName),
            )
            AudioDeviceDirection.INPUT -> listOf(
                AudioDeviceDescriptor("wasapi:mic-0", "USB Condenser Mic", true, backendName),
            )
            AudioDeviceDirection.LOOPBACK -> listOf(
                AudioDeviceDescriptor("wasapi:spk-0", "Speakers (Realtek) [Loopback]", true, backendName),
                AudioDeviceDescriptor("wasapi:hp-1", "Headphones (USB DAC) [Loopback]", false, backendName),
            )
        }

        override fun openOutput(deviceId: String, sampleRateHz: Int, channels: Int, targetLatencyMs: Int): OpenedAudioOutput {
            openedOutputDevices += deviceId to targetLatencyMs
            val line = object : AudioOutputLine {
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    outputWrittenBytes.addAndGet(length)
                    return length
                }
                override fun close() {}
            }
            val name = enumerate(AudioDeviceDirection.OUTPUT)
                .firstOrNull { if (deviceId.isBlank()) it.isDefault else it.id == deviceId }?.name ?: deviceId
            return OpenedAudioOutput(line, backendName, deviceId, name, targetLatencyMs)
        }

        override fun openInput(
            deviceId: String,
            loopback: Boolean,
            sampleRateHz: Int,
            channels: Int,
            targetLatencyMs: Int,
        ): OpenedAudioInput {
            openedInputRequests += Triple(deviceId, loopback, targetLatencyMs)
            val lVal = if (loopback) loopbackSampleL else micSampleL
            val rVal = if (loopback) loopbackSampleR else micSampleR
            val line = object : AudioInputLine {
                @Volatile private var closed = false
                override fun readFrames(pcmStereoInterleaved: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
                    if (closed) return -1
                    for (i in 0 until maxFrames) {
                        pcmStereoInterleaved[i * 2] = lVal
                        pcmStereoInterleaved[i * 2 + 1] = rVal
                    }
                    Thread.sleep(5L)
                    return maxFrames
                }
                override fun latencyMs(): Int = targetLatencyMs
                override fun close() { closed = true }
            }
            val dir = if (loopback) AudioDeviceDirection.LOOPBACK else AudioDeviceDirection.INPUT
            val name = enumerate(dir).firstOrNull { if (deviceId.isBlank()) it.isDefault else it.id == deviceId }?.name ?: deviceId
            return OpenedAudioInput(
                line = line,
                backendMode = if (loopback) "NATIVE_${backendName}_LOOPBACK" else "NATIVE_$backendName",
                deviceId = deviceId,
                deviceName = name,
                actualLatencyMs = targetLatencyMs,
            )
        }
    }

    @Test
    fun configValidationSupports10msLatencyAndExtendedM10Keys() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val base = Formats.read(repo.resolve("tools/release/emulator-release-linux.yaml"))

        val validAudio = mapOf(
            "enabled" to true,
            "volume" to 0.9,
            "muted" to false,
            "bufferMs" to 10,
            "outputDeviceId" to "wasapi:hp-1",
            "backendPreference" to "AUTO",
            "inputMode" to "OUTPUT_LOOPBACK",
            "inputDeviceId" to "wasapi:spk-0",
            "inputGainDb" to 6.0,
            "inputMuted" to false,
            "inputMonitor" to true,
            "testToneHz" to 440.0,
        )
        validateLaunchConfiguration(base + ("audio" to validAudio))
        val resolved = AudioPlaybackConfig.resolve(mapOf("audio" to validAudio))
        assertEquals(10, resolved.bufferMs)
        assertEquals(AudioInputMode.OUTPUT_LOOPBACK, resolved.inputMode)
        assertEquals(6.0, resolved.inputGainDb)
        assertTrue(resolved.inputMonitor)

        // Reject out-of-range bufferMs / inputGainDb / unknown inputMode
        assertFailsWith<IllegalArgumentException> {
            AudioPlaybackConfig.validateConfigValue(mapOf("bufferMs" to 9))
        }
        assertFailsWith<IllegalArgumentException> {
            AudioPlaybackConfig.validateConfigValue(mapOf("inputGainDb" to 30.0))
        }
        assertFailsWith<IllegalArgumentException> {
            AudioPlaybackConfig.validateConfigValue(mapOf("inputMode" to "BOGUS"))
        }
    }

    @Test
    fun gainSoftLimiterMuteAndVuMeteringBehaveDeterministically() {
        // 0 dB passes below knee linearly
        val linearBuf = shortArrayOf(8192, -4096, 8192, -4096)
        val (pL, pR) = HostAudioCapture.processPcmInPlace(linearBuf, frames = 2, gainDb = 0.0, muted = false)
        assertTrue(abs(linearBuf[0].toInt() - 8192) <= 2)
        assertTrue(abs(linearBuf[1].toInt() - (-4096)) <= 2)
        assertEquals(8192.0 / 32768.0, pL, 0.002)
        assertEquals(4096.0 / 32768.0, pR, 0.002)

        // +24 dB gain (~15.85x) drives into tanh soft limiter without integer wraparound or hard overflow
        val hotBuf = shortArrayOf(12000, -12000)
        val (hotL, hotR) = HostAudioCapture.processPcmInPlace(hotBuf, frames = 1, gainDb = 24.0, muted = false)
        assertTrue(hotBuf[0] in 28000..32767, "Expected soft-limited positive peak, got ${hotBuf[0]}")
        assertTrue(hotBuf[1] in -32768..-28000, "Expected soft-limited negative peak, got ${hotBuf[1]}")
        assertTrue(hotL in 0.85..1.0 && hotR in 0.85..1.0)

        // Mute zeroes buffer and reports zero VU peaks
        val muteBuf = shortArrayOf(15000, -15000)
        val (mL, mR) = HostAudioCapture.processPcmInPlace(muteBuf, frames = 1, gainDb = 12.0, muted = true)
        assertEquals(0.toShort(), muteBuf[0])
        assertEquals(0.toShort(), muteBuf[1])
        assertEquals(0.0, mL)
        assertEquals(0.0, mR)
    }

    @Test
    fun deviceCatalogDetectsLoopbackMixersAndMergesNativeAndJavaSoundLists() {
        assertTrue(AudioDeviceCatalog.isLoopbackMixerName("Monitor of Built-in Audio Analog Stereo"))
        assertTrue(AudioDeviceCatalog.isLoopbackMixerName("Stereo Mix (Realtek High Definition Audio)"))
        assertTrue(AudioDeviceCatalog.isLoopbackMixerName("立体声混音 (Realtek Audio)"))
        assertTrue(AudioDeviceCatalog.isLoopbackMixerName("CABLE Output (VB-Audio Virtual Cable)"))
        assertFalse(AudioDeviceCatalog.isLoopbackMixerName("USB Condenser Microphone"))

        val native = MockNativeBackend(available = true, backendName = "WASAPI")
        val catalog = AudioDeviceCatalog(
            nativeBackend = native,
            javaSoundOutputsProvider = {
                listOf(AudioDeviceDescriptor("js:out:Fallback", "Fallback JavaSound", true, "JAVASOUND"))
            },
            javaSoundInputsProvider = {
                listOf(AudioDeviceDescriptor("js:in:JsMic", "JS Mic", true, "JAVASOUND")) to
                    listOf(AudioDeviceDescriptor("js:loop:Stereo Mix", "Stereo Mix", true, "JAVASOUND"))
            },
            parecLoopbackOpener = null,
        )

        val listed = catalog.listDevices()
        assertEquals(true, listed["nativeAvailable"])
        assertEquals("WASAPI", listed["nativeBackend"])
        @Suppress("UNCHECKED_CAST")
        val outputs = listed["outputs"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val inputs = listed["inputs"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val loopbacks = listed["loopbacks"] as List<Map<String, Any?>>

        assertEquals(3, outputs.size)
        assertEquals("wasapi:spk-0", outputs[0]["id"])
        assertEquals(true, outputs[0]["isDefault"])
        assertEquals(false, outputs[2]["isDefault"]) // native default wins over JavaSound default
        assertEquals(2, inputs.size)
        assertEquals(3, loopbacks.size)
        assertEquals("wasapi:spk-0", loopbacks[0]["id"])
    }

    @Test
    fun fullDuplexLoopbackMicTestToneHotDeviceSwitchAndMonitorPassthroughWorkEndToEnd() {
        val ports = StubPorts()
        val i2s = DuplexStubI2sPort()
        ports.register("test", "soc-isp-setup.i2s-audio", I2sAudioPort::class.java, i2s)

        val native = MockNativeBackend(available = true, backendName = "WASAPI")
        val catalog = AudioDeviceCatalog(
            nativeBackend = native,
            javaSoundOutputsProvider = { emptyList() },
            javaSoundInputsProvider = { emptyList<AudioDeviceDescriptor>() to emptyList() },
            parecLoopbackOpener = null,
        )
        val renderedOut = ByteArrayOutputStream()

        HostAudioPlayback(
            portsProvider = { ports },
            initialConfig = AudioPlaybackConfig(enabled = true, volume = 1.0, bufferMs = 10),
            onRenderedPcm = { buf, len -> synchronized(renderedOut) { renderedOut.write(buf, 0, len) } },
            deviceCatalog = catalog,
        ).use { audio ->
            audio.bindPorts(ports)
            assertEquals(AudioBackendMode.ACTIVE_LINE, audio.startIfEnabled())
            assertEquals("WASAPI", audio.status()["activeBackend"])
            assertEquals(10, audio.status()["actualLatencyMs"])
            assertEquals(1, native.openedOutputDevices.size)

            // 1. Hot-switch output device + latency via applyPatch without restarting emulator
            val patchedOut = audio.applyPatch(mapOf("outputDeviceId" to "wasapi:hp-1", "bufferMs" to 20))
            assertEquals("wasapi:hp-1", patchedOut["outputDeviceId"])
            assertEquals("Headphones (USB DAC)", patchedOut["outputDeviceName"])
            assertEquals(20, patchedOut["actualLatencyMs"])
            assertEquals(2, native.openedOutputDevices.size)

            // 2. Enable OUTPUT_LOOPBACK capturing PC audio output into I2S RX ring + monitor passthrough
            val loopStatus = audio.applyPatch(
                mapOf(
                    "inputMode" to "OUTPUT_LOOPBACK",
                    "inputDeviceId" to "wasapi:spk-0",
                    "inputGainDb" to 0.0,
                    "inputMonitor" to true,
                ),
            )
            assertEquals("OUTPUT_LOOPBACK", loopStatus["inputMode"])
            assertEquals("NATIVE_WASAPI_LOOPBACK", loopStatus["inputBackendMode"])
            assertEquals(true, native.openedInputRequests.last().second) // loopback == true

            // Wait for loopback capture frames to land in I2S RX ring and pull them from guest I2S RX
            val deadline = System.currentTimeMillis() + 3000L
            while ((audio.status()["inputFramesCaptured"] as Long) < 480L && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L)
            }
            val st = audio.status()
            assertTrue((st["inputFramesCaptured"] as Long) >= 480L)
            assertTrue((st["inputPeakLeft"] as Double) > 0.1)
            assertTrue((st["inputPeakRight"] as Double) > 0.1)

            @Suppress("UNCHECKED_CAST")
            val rxStats = st["rxStats"] as Map<String, Any?>
            assertTrue((rxStats["totalRxFrames"] as Long) >= 480L)

            val guestRx = ShortArray(64 * 2)
            i2s.pullRxSamples(0, guestRx, 64)
            assertTrue(abs(guestRx[0].toInt() - 6000) <= 4, "Expected loopback L=6000 in guest I2S RX, got ${guestRx[0]}")
            assertTrue(abs(guestRx[1].toInt() - (-4500)) <= 4, "Expected loopback R=-4500 in guest I2S RX, got ${guestRx[1]}")

            // Monitor passthrough should have delivered non-zero audio to host output even while I2S TX is idle
            val monDeadline = System.currentTimeMillis() + 2000L
            while (synchronized(renderedOut) { renderedOut.size() } == 0 && System.currentTimeMillis() < monDeadline) {
                audio.pumpOnce()
                Thread.sleep(10L)
            }
            val monBytes = synchronized(renderedOut) { renderedOut.toByteArray() }
            assertTrue(monBytes.isNotEmpty() && monBytes.any { it != 0.toByte() }, "Expected non-zero monitor passthrough audio")

            // 3. Hot-switch to HOST_MIC
            val micStatus = audio.applyPatch(mapOf("inputMode" to "HOST_MIC", "inputDeviceId" to "wasapi:mic-0", "inputMonitor" to false))
            assertEquals("HOST_MIC", micStatus["inputMode"])
            assertEquals("NATIVE_WASAPI", micStatus["inputBackendMode"])
            assertEquals(false, native.openedInputRequests.last().second) // loopback == false

            // 4. Switch to TEST_TONE (440 Hz) and verify deterministic sine generation
            val toneStatus = audio.applyPatch(mapOf("inputMode" to "TEST_TONE", "testToneHz" to 440.0, "inputGainDb" to 6.0))
            assertEquals("TEST_TONE", toneStatus["inputMode"])
            assertEquals("TEST_TONE", toneStatus["inputBackendMode"])
            val capturedNow = audio.capture.captureOnce()
            assertEquals(HostAudioCapture.CAPTURE_CHUNK_FRAMES, capturedNow)
            assertTrue(audio.capture.peakLeft > 0.35 && audio.capture.peakRight > 0.35)

            // 5. Disable input cleanly
            val offStatus = audio.applyPatch(mapOf("inputMode" to "DISABLED"))
            assertEquals("DISABLED", offStatus["inputMode"])
            assertEquals("DISABLED", offStatus["inputBackendMode"])
            assertEquals(0.0, offStatus["inputPeakLeft"])
        }

        val leaked = Thread.getAllStackTraces().keys.filter {
            (it.name == "fuji-audio-playback" || it.name == "fuji-audio-capture") && it.isAlive
        }
        assertTrue(leaked.isEmpty(), "Expected zero leaked audio daemon threads: $leaked")
    }

    @Test
    fun headlessInputAndLoopbackDegradeSilentlyAndControlServerExposesDevicesAndPatch() {
        val failingCatalog = AudioDeviceCatalog(
            nativeBackend = UnavailableNativeAudioBackend,
            javaSoundOutputsProvider = { emptyList() },
            javaSoundInputsProvider = { emptyList<AudioDeviceDescriptor>() to emptyList() },
            javaSoundOutputOpener = { _, _, _ -> throw LineUnavailableException("No headless output") },
            javaSoundInputOpener = { _, loopback, _, _ ->
                throw LineUnavailableException(if (loopback) "No headless loopback" else "No headless mic")
            },
            parecLoopbackOpener = null,
        )

        val audio = HostAudioPlayback(
            portsProvider = { null },
            initialConfig = AudioPlaybackConfig(enabled = false),
            deviceCatalog = failingCatalog,
        )
        val stubControl = object : ControlClient {
            override fun snapshot() = CompletableFuture.completedFuture(mapOf<String, Any?>("components" to emptyList<Any>()))
            override fun patch(id: String, revision: Long, changes: Map<String, Any>) =
                CompletableFuture.completedFuture(mapOf<String, Any?>("id" to id, "revision" to revision + 1))
        }
        val lastPatchRef = AtomicReference<Map<String, Any?>>()

        ControlServer(
            control = stubControl,
            port = 0,
            audioStatus = { audio.status() },
            audioDevices = { audio.listDevices() },
            audioUpdate = { patch ->
                lastPatchRef.set(patch)
                audio.applyPatch(patch)
            },
        ).use { server ->
            audio.use {
                val port = server.address.port
                val http = HttpClient.newHttpClient()

                // 1. GET /api/audio/devices
                val devResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/audio/devices")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, devResp.statusCode(), devResp.body())
                val devJson = Formats.json.readValue(devResp.body(), Map::class.java)
                assertEquals(false, devJson["nativeAvailable"])
                assertEquals("NONE", devJson["nativeBackend"])
                assertTrue(devJson["outputs"] is List<*>)
                assertTrue(devJson["inputs"] is List<*>)
                assertTrue(devJson["loopbacks"] is List<*>)

                // 2. POST /api/audio enabling OUTPUT_LOOPBACK on headless container degrades gracefully without 500
                val postResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/audio"))
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                """{"enabled":true,"bufferMs":10,"backendPreference":"AUTO","inputMode":"OUTPUT_LOOPBACK","inputGainDb":3.5}""",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, postResp.statusCode(), postResp.body())
                val body = Formats.json.readValue(postResp.body(), Map::class.java)
                assertEquals("HEADLESS_SILENT", body["activeBackend"])
                assertEquals("OUTPUT_LOOPBACK", body["inputMode"])
                assertEquals("HEADLESS_SILENT", body["inputBackendMode"])
                assertNotNull(body["degradationReason"])
                assertNotNull(body["inputDegradationReason"])
                assertEquals(3.5, (body["inputGainDb"] as Number).toDouble(), 0.001)
                assertEquals(10, (body["bufferMs"] as Number).toInt())

                // 3. Invalid patch key rejected cleanly with HTTP 400
                val badResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/audio"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"unknownAudioKey":123}"""))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(400, badResp.statusCode(), badResp.body())
            }
        }
    }

    @Test
    fun testTonePacingMaintainsZeroOverrunsAtLowRtrAndZeroUnderrunsDuringHighRtrBursts() {
        val rxRingCapacity = 16_384
        val rxQueue = ArrayDeque<Short>()
        var totalRx = 0L
        var rxOverruns = 0L
        var rxUnderruns = 0L
        var captureActive = false
        var fallback: ((Int, ShortArray, Int) -> Int)? = null

        val i2s = object : I2sAudioPort {
            override fun pushTxSamples(channel: Int, pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int, tick: Long) {}
            override fun drainPlaybackPcm(maxFrames: Int): AudioPcmChunk? = null
            override fun playbackStats() = AudioPlaybackStats(0L, 0L, 0L, 0, 0L, 0, null)

            @Synchronized
            override fun pushRxSamples(pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int): Int {
                captureActive = true
                for (i in 0 until frames) {
                    if (rxQueue.size / 2 >= rxRingCapacity) {
                        rxQueue.removeFirst()
                        rxQueue.removeFirst()
                        rxOverruns++
                    }
                    rxQueue.addLast(pcmStereoInterleaved[i * 2])
                    rxQueue.addLast(pcmStereoInterleaved[i * 2 + 1])
                }
                totalRx += frames
                return frames
            }

            override fun pullRxSamples(channel: Int, pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int, tick: Long): Int {
                val (fromRing, fb) = synchronized(this) {
                    val count = minOf(frames, rxQueue.size / 2)
                    for (i in 0 until count * 2) pcmStereoInterleaved[i] = rxQueue.removeFirst()
                    count to fallback
                }
                val remaining = frames - fromRing
                if (remaining > 0) {
                    if (fb != null) {
                        val tail = ShortArray(remaining * 2)
                        val got = fb(channel, tail, remaining)
                        System.arraycopy(tail, 0, pcmStereoInterleaved, fromRing * 2, got * 2)
                        if (got < remaining) {
                            synchronized(this) {
                                if (captureActive) rxUnderruns += (remaining - got).toLong()
                            }
                        }
                    } else {
                        pcmStereoInterleaved.fill(0, fromRing * 2, frames * 2)
                        synchronized(this) {
                            if (captureActive) rxUnderruns += remaining.toLong()
                        }
                    }
                }
                return frames
            }

            @Synchronized
            override fun rxStats(): AudioCaptureStats = AudioCaptureStats(totalRx, rxQueue.size / 2, rxOverruns, rxUnderruns)

            @Synchronized
            override fun setRxCaptureActive(active: Boolean) {
                captureActive = active
                if (!active) rxQueue.clear()
            }

            @Synchronized
            override fun setRxFallbackProvider(provider: ((Int, ShortArray, Int) -> Int)?) {
                fallback = provider
            }
        }

        val ports = StubPorts().apply { register("test", "soc-isp-setup.i2s-audio", I2sAudioPort::class.java, i2s) }
        val silentCatalog = AudioDeviceCatalog(
            nativeBackend = UnavailableNativeAudioBackend,
            javaSoundOutputsProvider = { emptyList() },
            javaSoundInputsProvider = { emptyList<AudioDeviceDescriptor>() to emptyList() },
            parecLoopbackOpener = null,
        )

        HostAudioPlayback(
            portsProvider = { ports },
            initialConfig = AudioPlaybackConfig(enabled = false),
            deviceCatalog = silentCatalog,
        ).use { audio ->
            audio.bindPorts(ports)
            // 1. Disabled input: guest pulling silence accumulates 0 underruns
            i2s.pullRxSamples(0, ShortArray(512 * 2), 512)
            assertEquals(0L, i2s.rxStats().rxUnderruns)

            // 2. Enable TEST_TONE and let background loop run for 250ms while guest pulls slowly (~0.25x RTR)
            audio.applyPatch(mapOf("inputMode" to "TEST_TONE", "testToneHz" to 440.0, "inputGainDb" to 0.0))
            Thread.sleep(250L)
            val lowRtrStats = i2s.rxStats()
            assertEquals(0L, lowRtrStats.rxOverruns, "TEST_TONE must not overrun RX ring under low RTR: $lowRtrStats")
            assertEquals(0L, lowRtrStats.rxUnderruns, "TEST_TONE must not underrun RX ring: $lowRtrStats")
            assertTrue(
                lowRtrStats.queuedRxFrames in 480..(HostAudioCapture.TARGET_TEST_TONE_QUEUE_FRAMES + HostAudioCapture.CAPTURE_CHUNK_FRAMES),
                "Expected bounded target fill, got ${lowRtrStats.queuedRxFrames}",
            )

            // 3. Simulate a huge high-RTR WFI burst draining 12,000 frames at once (> queuedRxFrames)
            val burst = ShortArray(12_000 * 2)
            i2s.pullRxSamples(0, burst, 12_000)
            val afterBurst = i2s.rxStats()
            assertEquals(0L, afterBurst.rxUnderruns, "Demand-driven TEST_TONE fallback must keep rxUnderruns == 0 on high-RTR bursts")
            assertTrue(burst.any { abs(it.toInt()) > 4_000 }, "Expected full-amplitude 440Hz sine across entire burst")

            // 4. Disable cleanly
            audio.applyPatch(mapOf("inputMode" to "DISABLED"))
            assertEquals(0, i2s.rxStats().queuedRxFrames)
            i2s.pullRxSamples(0, ShortArray(256 * 2), 256)
            assertEquals(0L, i2s.rxStats().rxUnderruns)
        }
    }

    @Test
    fun sceneAudioModeFeedsI2sRxRingAndMonitorWithoutHostMicrophoneHardware() {
        val ports = StubPorts()
        val i2s = DuplexStubI2sPort()
        var sceneEnabled = true
        val sceneAudio = object : SceneAudioProvider {
            override val sceneAudioEnabled: Boolean get() = sceneEnabled
            override val sceneAudioName: String get() = "Milk Frog 3D Laugh (48 kHz Stereo)"
            override fun sampleAudioStereo48k(pcmStereoInterleaved: ShortArray, frames: Int, pose: CameraPose3D): Int {
                for (i in 0 until frames) {
                    pcmStereoInterleaved[i * 2] = 7200
                    pcmStereoInterleaved[i * 2 + 1] = -5400
                }
                return frames
            }
        }
        ports.register("test", "soc-isp-setup.i2s-audio", I2sAudioPort::class.java, i2s)
        ports.register("cmos", SceneAudioProvider.PORT_NAME, SceneAudioProvider::class.java, sceneAudio)

        val native = MockNativeBackend(available = true, backendName = "WASAPI")
        val catalog = AudioDeviceCatalog(
            nativeBackend = native,
            javaSoundOutputsProvider = { emptyList() },
            javaSoundInputsProvider = { emptyList<AudioDeviceDescriptor>() to emptyList() },
            parecLoopbackOpener = null,
        )
        val renderedOut = ByteArrayOutputStream()

        HostAudioPlayback(
            portsProvider = { ports },
            initialConfig = AudioPlaybackConfig(enabled = true, volume = 1.0, bufferMs = 10),
            onRenderedPcm = { buf, len -> synchronized(renderedOut) { renderedOut.write(buf, 0, len) } },
            deviceCatalog = catalog,
        ).use { audio ->
            audio.bindPorts(ports)
            audio.startIfEnabled()

            val patched = audio.applyPatch(mapOf("inputMode" to "SCENE_AUDIO", "inputMonitor" to true, "inputGainDb" to 0.0))
            assertEquals("SCENE_AUDIO", patched["inputMode"])
            assertEquals("SCENE_AUDIO", patched["inputBackendMode"])

            val pushed = audio.capture.captureOnce()
            assertEquals(HostAudioCapture.CAPTURE_CHUNK_FRAMES, pushed)
            assertTrue(audio.capture.peakLeft > 0.15 && audio.capture.peakRight > 0.10)

            val rxPull = ShortArray(HostAudioCapture.CAPTURE_CHUNK_FRAMES * 2)
            i2s.pullRxSamples(0, rxPull, HostAudioCapture.CAPTURE_CHUNK_FRAMES)
            assertTrue(abs(rxPull[0].toInt() - 7200) <= 4, "Expected SCENE_AUDIO left PCM on I2S RX, got ${rxPull[0]}")
            assertTrue(abs(rxPull[1].toInt() - (-5400)) <= 4, "Expected SCENE_AUDIO right PCM on I2S RX, got ${rxPull[1]}")

            // The playback worker may already have drained this block before the manual pump.
            audio.pumpOnce()
            val monitor = ByteBuffer.wrap(renderedOut.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            assertTrue(monitor.remaining() >= 4, "Expected a rendered stereo frame")
            assertTrue(abs(monitor.short.toInt() - 7200) <= 4, "Incorrect scene monitor left channel")
            assertTrue(abs(monitor.short.toInt() + 5400) <= 4, "Incorrect scene monitor right channel")

            // Disabling scene audio outputs clean silence without errors
            sceneEnabled = false
            audio.capture.captureOnce()
            assertEquals(0.0, audio.capture.peakLeft)
            assertEquals(0.0, audio.capture.peakRight)
        }
    }
}
