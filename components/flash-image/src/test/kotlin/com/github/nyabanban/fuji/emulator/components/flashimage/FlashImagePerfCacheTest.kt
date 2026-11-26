package com.github.nyabanban.fuji.emulator.components.flashimage

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.*

class FlashImagePerfCacheTest {
    @TempDir
    lateinit var tempRoot: Path

    private fun sha256Hex(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes, offset, length)
        return HexFormat.of().formatHex(md.digest())
    }

    private class CountingUpdatePort(private val raw: ByteArray) : UpdateDataPort {
        override val size: Long = raw.size.toLong()
        override val sha256: String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))
        var bytesRead: Long = 0L
            private set

        override fun read(offset: Long, destination: ByteBuffer) {
            val n = destination.remaining()
            bytesRead += n
            destination.put(raw, offset.toInt(), n)
        }
    }

    private class TestContext(
        override val directory: Path,
        override val relaxedMode: Boolean,
    ) : ComponentContext {
        val claims = linkedMapOf<String, Any>()
        override val clock = EmulationClock { 0L }
        override fun readData(relative: String): Map<String, Any?> = error("unexpected readData($relative)")
        override fun dataPath(relative: String): Path =
            directory.resolve(relative).normalize().also { require(it.startsWith(directory)) }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
    }

    private fun buildRawDat(payloadBytes: Int, seed: Int = 17): Pair<ByteArray, String> {
        val raw = ByteArray(0x274 + payloadBytes) { idx -> ((idx * 31 + seed) and 0xff).toByte() }
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 6).putInt(0x24c, payloadBytes)
        val inverted = ByteArray(payloadBytes) { i -> raw[0x274 + i].toInt().inv().toByte() }
        return raw to sha256Hex(inverted)
    }

    @Test
    fun coldBootReadsAndMetadataNeverScanFullDatPayloadWhileShaRemainsExact() {
        FlashImageFactory.clearMemoryDigestCacheForTesting()
        val payloadLen = 2 * 1024 * 1024 + 13 // non-8-byte-aligned to test Long+tail inversion
        val (raw, expectedInvertedSha) = buildRawDat(payloadLen)
        val port = CountingUpdatePort(raw)
        val ctx = TestContext(tempRoot.resolve("lazy-boot"), relaxedMode = false)
        ctx.claims["test.update"] = port

        val factory = FlashImageFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "sourcePort" to "test.update",
            "tailProfile" to "dat-end-block-ff-experimental-v1",
        )
        factory.create(ctx, cfg).use { comp ->
            val ro = checkNotNull(ctx.ports.find("flash.image", ReadOnlyImagePort::class.java))
            assertEquals(payloadLen.toLong(), ro.length)
            assertNotNull(ro.experimentalTail)
            val buf = ByteBuffer.allocate(4096)
            ro.read(0L, buf)
            assertEquals(4096, buf.position())
            for (i in 0 until 4096) {
                assertEquals(raw[0x274 + i].toInt().inv().toByte(), buf.array()[i], "Mismatch at byte $i")
            }
            // Direct ByteBuffer read path must also invert accurately
            val direct = ByteBuffer.allocateDirect(37)
            ro.read(100L, direct)
            direct.flip()
            for (i in 0 until 37) {
                assertEquals(raw[0x274 + 100 + i].toInt().inv().toByte(), direct.get(i))
            }
            // Cold-boot metadata + reads MUST NOT trigger 2 MiB SHA scan!
            assertEquals(0x274L + 4096L + 37L, port.bytesRead)
            assertFalse(Files.exists(ctx.directory.resolve("dat-digest-cache.json")))

            // Explicit sha256/state() computes exact digest on demand
            assertEquals(expectedInvertedSha, ro.sha256)
            assertEquals(expectedInvertedSha, comp.state()["sha256"])
            assertEquals(0x274L + 4096L + 37L + payloadLen.toLong(), port.bytesRead)
            // Strict mode must NEVER write disk cache
            assertFalse(Files.exists(ctx.directory.resolve("dat-digest-cache.json")))
        }
    }

    @Test
    fun relaxedModePersistsCryptographicDigestCacheAndWarnsOnCorruptCache() {
        FlashImageFactory.clearMemoryDigestCacheForTesting()
        val dir = tempRoot.resolve("relaxed-cache")
        val payloadLen = 512 * 1024 + 5
        val (raw, expectedSha) = buildRawDat(payloadLen, seed = 42)
        val factory = FlashImageFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "sourcePort" to "test.update",
        )

        // Boot 1: cold cache miss computes SHA-256 and persists dat-digest-cache.json
        val port1 = CountingUpdatePort(raw)
        val ctx1 = TestContext(dir, relaxedMode = true)
        ctx1.claims["test.update"] = port1
        factory.create(ctx1, cfg).use { comp ->
            assertEquals(expectedSha, comp.state()["sha256"])
            assertEquals(0x274L + payloadLen.toLong(), port1.bytesRead)
        }
        val cacheFile = dir.resolve("dat-digest-cache.json")
        assertTrue(Files.isRegularFile(cacheFile), "Expected relaxed digest cache file")

        // Boot 2 (simulated cold process restart: clear in-memory cache): hits disk cache with 0 payload reads!
        FlashImageFactory.clearMemoryDigestCacheForTesting()
        val port2 = CountingUpdatePort(raw)
        val ctx2 = TestContext(dir, relaxedMode = true)
        ctx2.claims["test.update"] = port2
        factory.create(ctx2, cfg).use { comp ->
            assertEquals(expectedSha, comp.state()["sha256"])
            assertEquals(0x274L, port2.bytesRead, "Expected 0 payload scan bytes on disk cache hit")
        }

        // Swapping DAT content invalidates cache cryptographically via source.sha256
        FlashImageFactory.clearMemoryDigestCacheForTesting()
        val (rawChanged, expectedChangedSha) = buildRawDat(payloadLen, seed = 99)
        assertNotEquals(expectedSha, expectedChangedSha)
        val port3 = CountingUpdatePort(rawChanged)
        val ctx3 = TestContext(dir, relaxedMode = true)
        ctx3.claims["test.update"] = port3
        factory.create(ctx3, cfg).use { comp ->
            assertEquals(expectedChangedSha, comp.state()["sha256"])
            assertEquals(0x274L + payloadLen.toLong(), port3.bytesRead)
        }

        // Corrupt cache file emits explicit [FALLBACK][flash-image-cache] WARNING and recomputes cleanly
        FlashImageFactory.clearMemoryDigestCacheForTesting()
        Files.writeString(cacheFile, "{corrupt-json")
        val warnings = mutableListOf<String>()
        val logger = Logger.getLogger(FlashImageFactory::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() >= Level.WARNING.intValue()) {
                    warnings += record.message.orEmpty()
                }
            }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            val port4 = CountingUpdatePort(rawChanged)
            val ctx4 = TestContext(dir, relaxedMode = true)
            ctx4.claims["test.update"] = port4
            factory.create(ctx4, cfg).use { comp ->
                assertEquals(expectedChangedSha, comp.state()["sha256"])
            }
        } finally {
            logger.removeHandler(handler)
        }
        assertTrue(
            warnings.any { it.contains("[FALLBACK][flash-image-cache]") },
            "Expected explicit [FALLBACK][flash-image-cache] WARNING, got: $warnings",
        )
    }

    @Test
    fun benchmarkColdBootMetadataVsEager64MiBScan() {
        FlashImageFactory.clearMemoryDigestCacheForTesting()
        val payloadLen = 16 * 1024 * 1024
        val (raw, expectedSha) = buildRawDat(payloadLen, seed = 7)
        val port = CountingUpdatePort(raw)
        val ctx = TestContext(tempRoot.resolve("bench"), relaxedMode = true)
        ctx.claims["test.update"] = port
        val factory = FlashImageFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf("sourcePort" to "test.update")

        val t0 = System.nanoTime()
        factory.create(ctx, cfg).use { comp ->
            val ro = checkNotNull(ctx.ports.find("flash.image", ReadOnlyImagePort::class.java))
            val page = ByteBuffer.allocate(4096)
            ro.read(0L, page)
            val lazyBootMs = (System.nanoTime() - t0) / 1e6

            val tHash0 = System.nanoTime()
            val computed = ro.sha256
            val fastHashMs = (System.nanoTime() - tHash0) / 1e6
            assertEquals(expectedSha, computed)

            val tCached0 = System.nanoTime()
            val cached = comp.state()["sha256"]
            val cachedMs = (System.nanoTime() - tCached0) / 1e6
            assertEquals(expectedSha, cached)

            println(
                "PERF_W9_FLASH_BOOT payloadMiB=16 lazyFirstReadMs=${"%.3f".format(lazyBootMs)} " +
                    "fastHashMs=${"%.2f".format(fastHashMs)} cachedStateMs=${"%.3f".format(cachedMs)}",
            )
            assertTrue(lazyBootMs < 500.0, "Cold-boot first read stalled: ${lazyBootMs}ms")
        }
    }
}
