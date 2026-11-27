package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.IspStillReadout
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SensorPort
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class StillReadoutLatchTest {
    @Test
    fun stillDevelopmentUsesItsOwnProgrammedWhiteBalanceAndTheSameBayerBuffer() {
        val companion = ImagingCompanionEngines({ 0L }, { null })
        val bank = companion.createIsp85000Device { false }
        val samples = ByteBuffer.wrap(byteArrayOf(10, 20))
        val shot = IspStillReadout(13, 2, 1, 603, 604, 603, samples)
        assertSame(shot, companion.colorReadout(shot))

        // GFX100S 2.13, M ISO100 f/2.8 1/160: daylight (Q8 461:257:487 -> unity ~1024), then incandescent.
        bank.write(0xd4, 4, 0x01cd0101)
        assertSame(shot, companion.colorReadout(shot))
        bank.write(0xd8, 4, 0x01e7)
        val daylight = companion.colorReadout(shot)
        assertEquals(listOf(1027, 1028, 1026), listOf(daylight.gainR1024, daylight.gainG1024, daylight.gainB1024))
        bank.write(0xd4, 4, 0x01250101)
        bank.write(0xd8, 4, 0x02c1)
        val incandescent = companion.colorReadout(shot)
        assertEquals(listOf(652, 1028, 1485), listOf(incandescent.gainR1024, incandescent.gainG1024, incandescent.gainB1024))
        assertEquals(shot.tick, incandescent.tick)
        samples.put(0, 42)
        assertEquals(42, incandescent.bayerSamples().get(0).toInt())
    }

    @Test
    fun continuousReadoutKeepsProcessingItsOwnFrameAcrossTheNextCcdArmAndSnapshot() {
        val latch = StillReadoutLatch()
        val lens = LensParameters("test", 45.0, 5.6, 1.0, 1.0, 0.0)
        val exposures = mutableListOf<Exposure>()
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int = error("Asynchronous exposure")

            override fun exposeStill(exposure: Exposure, destination: ByteBuffer): Int {
                exposures += exposure
                destination.put(0, exposures.size.toByte())
                return 1024 * 576
            }
        }
        val integrated = LiveViewDmaEngine.ExposureParams(1700, 100, 1110, 1111, 1112)
        latch.observeIntegration(0xB1, integrated)
        latch.observeIntegration(0x40, LiveViewDmaEngine.DEFAULT_EXPOSURE_PARAMS)
        latch.capture(51, sensor, lens)
        latch.capture(52, sensor, lens)
        assertNull(latch.processing)
        latch.beginProcessing()
        latch.beginProcessing()
        latch.capture(130, sensor, lens)
        assertEquals(listOf(51L, 130L), exposures.map { it.startTick })
        assertEquals(listOf(1700L, 1700L), exposures.map { it.durationNanos })
        assertEquals(1112, assertNotNull(latch.processing).gainB1024)
        val resumed = StillReadoutLatch.restore(latch.save())
        val first = assertNotNull(resumed.processing)
        assertEquals(51L, first.tick)
        assertEquals(1, first.bayerSamples().get(0).toInt())
        resumed.beginProcessing()
        val second = assertNotNull(resumed.processing)
        assertEquals(130L, second.tick)
        assertEquals(2, second.bayerSamples().get(0).toInt())
        resumed.capture(200, null, lens)
        resumed.beginProcessing()
        assertNull(resumed.processing)
        resumed.endReadout()
        assertNull(resumed.processing)
        assertNull(resumed.save())
    }

    @Test
    fun overrideExposureOverridesLiveViewAeAndPersistsAcrossSnapshot() {
        val latch = StillReadoutLatch()
        val lens = LensParameters("test", 45.0, 2.8, 1.0, 1.0, 0.0)
        val captured = mutableListOf<Exposure>()
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int = error("unused")
            override fun exposeStill(exposure: Exposure, destination: ByteBuffer): Int {
                captured += exposure
                destination.put(0, captured.size.toByte())
                return 1024 * 576
            }
        }
        // LiveView AE ran at 1/250 s ISO 100 (Exposure Preview OFF)
        latch.observeIntegration(0xB1, LiveViewDmaEngine.ExposureParams(1000L, 100, 1050, 1024, 980))

        // M-mode still capture overrides with real firmware shutter 1/40 s (6250 ns) ISO 400
        val overrideExp = LiveViewDmaEngine.ExposureParams(6250L, 400, 1050, 1024, 980)
        latch.capture(500L, sensor, lens, overrideExposure = overrideExp)
        assertEquals(listOf(6250L), captured.map { it.durationNanos })
        assertEquals(listOf(400), captured.map { it.iso })

        // Snapshot/restore works even if observeIntegration was never called prior to override capture
        val freshLatch = StillReadoutLatch()
        freshLatch.capture(600L, sensor, lens, overrideExposure = LiveViewDmaEngine.ExposureParams(391L, 100))
        val restored = StillReadoutLatch.restore(freshLatch.save())
        restored.beginProcessing()
        assertEquals(600L, assertNotNull(restored.processing).tick)
        assertEquals(391L, captured.last().durationNanos)
    }
}
