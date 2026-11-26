package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ControlClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsyncPerfProfilerTest {
    private class StubControlClient : ControlClient {
        override fun snapshot(): CompletableFuture<Map<String, Any?>> =
            CompletableFuture.completedFuture(mapOf("components" to emptyList<Any>()))

        override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> =
            CompletableFuture.completedFuture(emptyMap())
    }

    @Test
    fun disabledByDefaultHasZeroIntrusionAndBaselineReportsZeros() {
        val telemetry = FirmwareTelemetry()
        assertFalse(telemetry.perfProfilingEnabled, "Profiler must default to OFF for zero discovery/strict intrusion")
        assertEquals(0L, telemetry.sliceCount)
        assertEquals(0L, telemetry.mmioCalls)
        assertEquals(0L, telemetry.cpuNativeNanos)

        AsyncPerfProfiler(telemetry).use { profiler ->
            assertFalse(profiler.isRunning())
            val report = profiler.reportMap()
            assertEquals(false, report["active"])
            assertEquals(0L, report["totalSamples"])
            assertEquals(0L, report["attemptsDelta"])
            assertEquals(0L, report["virtualTicksDelta"])
            assertFalse(telemetry.perfProfilingEnabled)
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun deterministicSamplingAggregatesRtrMipsWfiMmioPagesHostFramesAndGuestPcs(@TempDir tempDir: Path) {
        val telemetry = FirmwareTelemetry()
        var now = 1_000_000_000L
        val logged = mutableListOf<String>()
        val pageLabels = mapOf(
            0xFFF3C000L to "soc-timer/soc-dma",
            0xFFFF1000L to "soc-gic",
        )
        val profiler = AsyncPerfProfiler(
            telemetry = telemetry,
            ownerThreadProvider = { Thread.currentThread() },
            ticksPerRealSecond = 54_000_000.0,
            clockNanos = { now },
            pageNameResolver = { pageLabels[it] },
            infoSink = logged::add,
        )

        // Pre-existing activity before profiler starts must be excluded by baseline delta math!
        telemetry.attempts = 1_000_000L
        telemetry.virtualTick = 1_000_000L
        telemetry.sliceCount = 50L
        telemetry.sliceExecutedInstructions = 1_000_000L
        telemetry.recordMmioAccess(0xFFF3C348L, false, 1000L)

        // Simulate deterministic profiling window without background thread races:
        // Capture baseline via sampleOnce at t0, then advance 2.0 wall seconds.
        telemetry.markSessionStarted(now)
        telemetry.perfProfilingEnabled = true
        telemetry.currentCore = 0
        telemetry.currentPc = 0x01234000L
        telemetry.currentPhase = FirmwareTelemetry.PHASE_CPU_NATIVE
        telemetry.markStage(FirmwareStage.STAGE_2_KERNEL_RTOS)
        profiler.sampleOnce(now)

        // Advance 1.0s wall time: 20M instructions executed + 34M WFI skipped = 54M virtual ticks (exact 1.0x RTR over 1s!)
        now += TimeUnit.SECONDS.toNanos(1L)
        telemetry.attempts += 20_000_000L
        telemetry.virtualTick += 54_000_000L
        telemetry.cpuNativeNanos += 600_000_000L
        telemetry.wfiJumpNanos += 50_000_000L
        telemetry.wfiJumpCount += 100L
        telemetry.wfiSkippedTicks += 34_000_000L
        telemetry.sliceCount += 1_000L
        telemetry.sliceExecutedInstructions += 20_000_000L
        repeat(7) { telemetry.recordMmioAccess(0xFFF3C348L, false, 2000L) }
        repeat(3) { telemetry.recordMmioAccess(0xFFFF100CL, true, 4000L) }

        telemetry.currentPc = 0x01234000L
        telemetry.currentPhase = FirmwareTelemetry.PHASE_CPU_NATIVE
        profiler.sampleOnce(now)

        telemetry.currentPc = 0x00040500L
        telemetry.currentPhase = FirmwareTelemetry.PHASE_MMIO_UPCALL
        profiler.sampleOnce(now)

        val report = profiler.reportMap()
        assertEquals(3L, report["totalSamples"])
        assertEquals(1.0, (report["wallSeconds"] as Number).toDouble(), 1e-3)
        assertEquals(54_000_000L, report["virtualTicksDelta"])
        assertEquals(1.0, (report["virtualSeconds"] as Number).toDouble(), 1e-3)
        assertEquals(1.0, (report["realTimeRatio"] as Number).toDouble(), 1e-3)
        assertEquals(20_000_000L, report["attemptsDelta"])
        assertEquals(20.0, (report["avgMips"] as Number).toDouble(), 1e-2)

        val slice = report["sliceMetrics"] as Map<String, Any?>
        assertEquals(1_000L, slice["sliceCount"])
        assertEquals(20_000.0, (slice["avgNativeSliceInstructions"] as Number).toDouble(), 1e-2)
        assertEquals(60.0, (slice["cpuNativePct"] as Number).toDouble(), 1e-2)
        assertEquals(100L, slice["wfiJumpCount"])
        assertEquals(34_000_000L, slice["wfiSkippedTicks"])
        assertEquals(62.96, (slice["wfiRatioPct"] as Number).toDouble(), 0.05)

        val mmio = report["mmioMetrics"] as Map<String, Any?>
        assertEquals(10L, mmio["totalCalls"])
        assertEquals(7L, mmio["reads"])
        assertEquals(3L, mmio["writes"])

        val pages = report["topMmioPages"] as List<Map<String, Any?>>
        assertEquals(2, pages.size)
        assertEquals("0xFFF3C000", pages[0]["pageBaseHex"])
        assertEquals("soc-timer/soc-dma", pages[0]["device"])
        assertEquals(7L, pages[0]["totalHits"])
        assertEquals("0xFFFF1000", pages[1]["pageBaseHex"])
        assertEquals("soc-gic", pages[1]["device"])
        assertEquals(3L, pages[1]["totalHits"])

        val pcs = report["topGuestPcs"] as List<Map<String, Any?>>
        assertEquals("0x01234000", pcs[0]["pcHex"])
        assertEquals(2L, pcs[0]["samples"])
        assertEquals("0x00040500", pcs[1]["pcHex"])
        assertEquals(1L, pcs[1]["samples"])

        val selfFrames = report["topHostSelfFrames"] as List<Map<String, Any?>>
        assertTrue(selfFrames.isNotEmpty(), "Expected host stack self frames to be collected")

        val exported = profiler.exportReport(tempDir)
        assertNotNull(exported)
        assertTrue(Files.isRegularFile(exported))
        val text = Files.readString(exported)
        assertTrue(text.contains("[FUJI-PROFILER SUMMARY]"), text)
        assertTrue(text.contains("Real-Time Ratio (RTR): 1.0000x"), text)
        assertTrue(text.contains("0xFFF3C000"), text)
        assertTrue(text.contains("soc-timer/soc-dma"), text)
        assertEquals(1, logged.size)
        profiler.close()
    }

    @Test
    fun startStopLifecycleCleansUpDaemonThreadWithZeroLeak() {
        val telemetry = FirmwareTelemetry()
        val sampledBy = LinkedBlockingQueue<Thread>()
        val owner = Thread.currentThread()
        AsyncPerfProfiler(telemetry, ownerThreadProvider = {
            sampledBy.offer(Thread.currentThread())
            owner
        }).use { profiler ->
            assertEquals(true, profiler.start(10L)["active"])
            val firstWorker = assertNotNull(sampledBy.poll(2, TimeUnit.SECONDS), "First worker did not sample")
            assertTrue(profiler.isRunning())
            assertTrue(telemetry.perfProfilingEnabled)

            // Restart a live sampler. Its old worker must finish before the new counters are installed.
            profiler.start(15L)
            assertFalse(firstWorker.isAlive, "Restart left the previous sampler alive")
            var secondWorker = sampledBy.poll(2, TimeUnit.SECONDS)
            while (secondWorker === firstWorker) secondWorker = sampledBy.poll(2, TimeUnit.SECONDS)
            assertNotNull(secondWorker, "Restarted worker did not sample")
            val stopped = profiler.stop()
            assertEquals(false, stopped["active"])
            assertFalse(secondWorker.isAlive)
            assertFalse(profiler.isRunning())
            assertFalse(telemetry.perfProfilingEnabled)
            assertTrue((stopped["totalSamples"] as Number).toLong() >= 1L)
        }
    }

    @Test
    fun concurrentStartsAndStopsRetireAllSamplingThreads() {
        val telemetry = FirmwareTelemetry()
        val sampledBy = LinkedBlockingQueue<Thread>()
        val owner = Thread.currentThread()
        AsyncPerfProfiler(telemetry, ownerThreadProvider = {
            sampledBy.offer(Thread.currentThread())
            owner
        }).use { profiler ->
            val ready = CountDownLatch(3)
            val start = CountDownLatch(1)
            Executors.newFixedThreadPool(3).use { pool ->
                val actions = List(3) { id ->
                    pool.submit {
                        ready.countDown()
                        start.await()
                        repeat(3) {
                            profiler.start(5L)
                            if (id == 0) profiler.stop()
                        }
                    }
                }
                val allReady = ready.await(2, TimeUnit.SECONDS)
                start.countDown()
                assertTrue(allReady)
                actions.forEach { it.get(5, TimeUnit.SECONDS) }
            }
            profiler.start(5L)
            val observed = assertNotNull(sampledBy.poll(2, TimeUnit.SECONDS))
            profiler.stop()
            assertFalse(observed.isAlive)
            assertTrue(sampledBy.none { it.isAlive })
            assertFalse(telemetry.perfProfilingEnabled)
        }
    }

    @Test
    fun controlServerExposesProfilerStartStopAndJsonTextReports() {
        val telemetry = FirmwareTelemetry()
        telemetry.markSessionStarted()
        telemetry.currentPc = 0x00040100L
        AsyncPerfProfiler(
            telemetry = telemetry,
            ownerThreadProvider = { Thread.currentThread() },
            defaultSampleIntervalMs = 10L,
        ).use { profiler ->
            ControlServer(StubControlClient(), 0, profiler = profiler).use { server ->
                val base = "http://127.0.0.1:${server.address.port}"
                val http = HttpClient.newHttpClient()

                val startResp = http.send(
                    HttpRequest.newBuilder(URI.create("$base/api/profiler/start?intervalMs=10"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, startResp.statusCode(), startResp.body())
                val startParsed = Formats.json.readValue(startResp.body(), Map::class.java)
                assertEquals(true, startParsed["active"], startResp.body())
                assertEquals(10, (startParsed["sampleIntervalMs"] as Number).toInt())
                assertTrue(telemetry.perfProfilingEnabled)

                Thread.sleep(45L)
                telemetry.attempts = 5_000_000L
                telemetry.virtualTick = 5_000_000L
                telemetry.recordMmioAccess(0xFFF3C348L, false, 1500L)

                val reportJsonResp = http.send(
                    HttpRequest.newBuilder(URI.create("$base/api/profiler/report")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, reportJsonResp.statusCode())
                val parsed = Formats.json.readValue(reportJsonResp.body(), Map::class.java)
                assertEquals(true, parsed["active"])
                assertTrue((parsed["totalSamples"] as Number).toLong() >= 1L)

                val reportTextResp = http.send(
                    HttpRequest.newBuilder(URI.create("$base/api/profiler/report?format=text")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, reportTextResp.statusCode())
                assertTrue(reportTextResp.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"))
                assertTrue(reportTextResp.body().contains("[FUJI-PROFILER SUMMARY]"), reportTextResp.body())

                val stopResp = http.send(
                    HttpRequest.newBuilder(URI.create("$base/api/profiler/stop"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, stopResp.statusCode())
                val stopParsed = Formats.json.readValue(stopResp.body(), Map::class.java)
                assertEquals(false, stopParsed["active"], stopResp.body())
                assertFalse(telemetry.perfProfilingEnabled)
            }
        }
    }
}
