package com.github.nyabanban.fuji.emulator.components.spi

import com.github.nyabanban.fuji.emulator.api.CameraGravityVector
import com.github.nyabanban.fuji.emulator.api.CameraOrientationPort
import com.github.nyabanban.fuji.emulator.api.IbisShiftPort
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.LensPort
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.*

class BiscomIbisAndPixelShiftTest {
    private fun frame(cmdId: Long, frameType: Long, payload16: Long = 0L): Long {
        val base = ((payload16 and 0xffffL) shl 16) or ((frameType and 1L) shl 15) or ((cmdId and 0x1ffL) shl 6)
        return base or (Frame32SpiPeer.checksum(base) shl 1)
    }

    private fun sendWord(peer: Frame32SpiPeer, word: Long, tick: Long = 0L): Long {
        var rx = 0L
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val out = peer.exchange(8, ((word ushr shift) and 0xffL).toInt(), tick)
            rx = (rx shl 8) or (out.toLong() and 0xffL)
        }
        return rx
    }

    private fun runCommand(peer: Frame32SpiPeer, cmdId: Long, payload16: Long = 0L, tick: Long = 0L): Long {
        sendWord(peer, frame(cmdId, frameType = 0L, payload16 = payload16), tick)
        sendWord(peer, frame(cmdId, frameType = 1L, payload16 = 0x0800L), tick)
        // Clock out the queued DATA frame with a sync/idle word
        val dataWord = sendWord(peer, 0xA55A3CC3L, tick)
        assertEquals(cmdId, (dataWord ushr 6) and 0x1ffL)
        assertEquals(0L, (dataWord ushr 15) and 1L)
        return (dataWord ushr 16) and 0xffffL
    }

    @Test
    fun efcsShutterValueIsSignedSingleUseAndSurvivesSnapshot() {
        var powerCycle = 1L
        val peer = Frame32SpiPeer(powerOnSequence = java.util.function.LongSupplier { powerCycle })
        runCommand(peer, 0x0a9, 0x24)
        assertNull(peer.takeEfcsShutterTv16())
        runCommand(peer, 0x0a9, 0x2553)
        runCommand(peer, 0x0a9, 0x24)
        val saved = peer.snapshot()
        assertEquals(149, peer.takeEfcsShutterTv16())
        assertNull(peer.takeEfcsShutterTv16())
        peer.restore(peer.parse(saved))
        assertEquals(149, peer.takeEfcsShutterTv16())
        peer.restore(peer.parse(saved - "efcsShutterTv16"))
        assertNull(peer.takeEfcsShutterTv16())
        runCommand(peer, 0x0a9, 0xfc13)
        assertEquals(-16, peer.takeEfcsShutterTv16())
        runCommand(peer, 0x0a9, 0x1d53)
        runCommand(peer, 0x0a9, 0x1d63) // electronic supersedes EFCS
        assertNull(peer.takeEfcsShutterTv16())
        runCommand(peer, 0x0a9, 0x1d53)
        runCommand(peer, 0x0a9, 0x1d43) // mechanical supersedes EFCS
        assertNull(peer.takeEfcsShutterTv16())
        runCommand(peer, 0x0a9, 0x1d53)
        powerCycle++
        assertNull(peer.takeEfcsShutterTv16()) // No SPI exchange after the power edge yet.
        sendWord(peer, 0xa55a3cc3)
        assertNull(peer.takeEfcsShutterTv16())
    }

    @Test
    fun mechanicalShutterCommandBelongsToOneExposureAndMode() {
        var powerCycle = 1L
        val peer = Frame32SpiPeer(powerOnSequence = java.util.function.LongSupplier { powerCycle })
        assertNull(peer.takeMechanicalShutterTv16())
        runCommand(peer, 0x0a9, 0x1943)
        runCommand(peer, 0x0a9, 0x24) // Non-capture gain update before readout.
        val saved = peer.snapshot()
        assertEquals(101, peer.takeMechanicalShutterTv16())
        assertNull(peer.takeMechanicalShutterTv16())
        peer.restore(peer.parse(saved))
        assertEquals(101, peer.takeMechanicalShutterTv16())
        peer.restore(peer.parse(saved - "mechanicalShutterTv16"))
        assertNull(peer.takeMechanicalShutterTv16())
        runCommand(peer, 0x0a9, 0x1943)
        runCommand(peer, 0x0a9, 0x1143)
        assertEquals(69, peer.takeMechanicalShutterTv16())
        runCommand(peer, 0x0a9, 0x1943)
        runCommand(peer, 0x0a9, 0x1d53)
        assertNull(peer.takeMechanicalShutterTv16())
        assertEquals(117, peer.takeEfcsShutterTv16())
        runCommand(peer, 0x0a9, 0x1943)
        runCommand(peer, 0x0a9, 0x2563)
        assertNull(peer.takeMechanicalShutterTv16())
        runCommand(peer, 0x0a9, 0x1943)
        powerCycle++
        assertNull(peer.takeMechanicalShutterTv16())
    }

    @Test
    fun powerCycleBetweenTransfersDropsStaleDataAndRestartsAcknowledgementSequence() {
        var powerEdges = 4L
        var powered = true
        val powerOnSequence = java.util.function.LongSupplier { if (powered) powerEdges else -1L }
        val peer = Frame32SpiPeer(powerOnSequence = powerOnSequence)
        val temperature = frame(0x2A, 0)
        val status = frame(0x29, 0)
        val poll = frame(0, 1, 0x0810)
        sendWord(peer, temperature)
        sendWord(peer, poll)
        assertEquals(0x0190L, sendWord(peer, temperature) ushr 16)
        sendWord(peer, poll)
        peer.exchange(8, 0, 0L)
        val snapshot = peer.snapshot()

        val oldSnapshot = snapshot - "powerOnSequence" + ("resetSequence" to 4L)
        val legacy = Frame32SpiPeer(powerOnSequence = java.util.function.LongSupplier { 1L })
        legacy.restore(legacy.parse(oldSnapshot))
        assertEquals(0x90, legacy.exchange(8, 0, 0L))

        val resumed = Frame32SpiPeer(powerOnSequence = powerOnSequence)
        resumed.restore(resumed.parse(snapshot))
        powered = false
        powerEdges++
        // The response already in the shift register must survive the shutdown handshake.
        assertEquals(0x90, resumed.exchange(8, 0, 0L))
        powered = true
        powerEdges++
        assertEquals(0L, sendWord(resumed, status))
        val firstAck = sendWord(resumed, poll)
        assertEquals(0x29L, (firstAck ushr 6) and 0x1ffL)
        assertEquals(0L, (firstAck ushr 24) and 7L)
        assertEquals(status, sendWord(resumed, temperature))
    }

    @Test
    fun biscomHandlesIbisModesPixelShiftHallTargetsAccelBank7AndShakeCompensation() {
        var shakeAmp = 0.0
        var gravity = CameraGravityVector(0.0, -1.0, 0.0) // Portrait CW
        val orientation = object : CameraOrientationPort {
            override val accelEnabled: Boolean = true
            override val orientationPreset: String = "portrait-cw"
            override val rollDeg: Double = 90.0
            override val pitchDeg: Double = 0.0
            override val shakeAmpDeg: Double get() = shakeAmp
            override val lcdTiltDeg: Double = 0.0
            override fun bodyGravityG(tick: Long): CameraGravityVector = gravity
            override fun lcdGravityG(tick: Long): CameraGravityVector = gravity
            override fun handShakeDeltaDeg(tick: Long): Triple<Double, Double, Double> =
                if (shakeAmp > 0.0) Triple(shakeAmp * 0.5, shakeAmp, -shakeAmp * 0.8) else Triple(0.0, 0.0, 0.0)
        }

        val peer = Frame32SpiPeer(orientationProvider = { orientation })
        assertTrue(peer.isInitial())

        // 1) Boot version query 0x014 -> 0x0120, status 0x029 -> 0x0000
        assertEquals(0x0120L, runCommand(peer, 0x014L))
        assertEquals(0x0000L, runCommand(peer, 0x029L))

        // 2) IBIS mode 0x080: OFF -> CONTINUOUS -> SHOOTING_ONLY -> LOCK
        runCommand(peer, 0x080L, 1L) // CONTINUOUS
        assertEquals(IbisShiftPort.MODE_CONTINUOUS, peer.ibisMode)
        assertEquals("CONTINUOUS", peer.ibisModeName)
        assertFalse(peer.stageLocked)

        runCommand(peer, 0x0A8L, 3L) // 5-axis stabilization state 3
        runCommand(peer, 0x0A9L, 128L shl 6) // gain = 128
        runCommand(peer, 0x0B1L, 0x0100L) // bit15=0 flag packet must NOT overwrite focalLengthMm
        runCommand(peer, 0x0B2L, 630L) // 63.0 mm
        runCommand(peer, 0x087L, 0L) // unlock mode 0 -> unlocked
        assertFalse(peer.stageLocked)
        assertEquals(3, peer.stabilizationState)
        assertEquals(128, peer.stabilizationGain)
        assertEquals(63.0, peer.focalLengthMm, 1e-4)

        // 3) 16-shot 400MP Pixel Shift commands 0x186 (axis) + 0x187 (u16 Hall centered at 0x8000)
        // Shot offset (+1.0 px = +100 DAC counts, -2.5 px = -250 DAC counts)
        runCommand(peer, 0x186L, 0L shl 1) // axis = 0 (X)
        runCommand(peer, 0x187L, (0x8000 + 100).toLong())
        runCommand(peer, 0x186L, 1L shl 1) // axis = 1 (Y)
        runCommand(peer, 0x187L, (0x8000 - 250).toLong())
        assertEquals(0x8064, peer.pixelShiftTargetX)
        assertEquals(0x7F06, peer.pixelShiftTargetY)
        val (shiftX, shiftY, _) = peer.sampleShiftPixels(pixelPitchUm = 3.76, tick = 0L)
        assertEquals(1.0, shiftX, 1e-3)
        assertEquals(-2.5, shiftY, 1e-3)

        // 4) Accel bank=7 via 0x181 + 0x122 (DAT 0x01231948: b0[5:2]=bank, b0[1:0]=subIdx; levelsens 0x01216f88: 0->Y, 1->X, 2->Z)
        runCommand(peer, 0x181L, 7L shl 1)
        val sub0Y = runCommand(peer, 0x122L, (7L shl 2) or 0L)
        val sub1X = runCommand(peer, 0x122L, (7L shl 2) or 1L)
        val sub2Z = runCommand(peer, 0x122L, (7L shl 2) or 2L)
        assertEquals(-1024, ((sub0Y ushr 4).toInt() - 2048)) // subIdx=0 -> Y (-1g)
        assertEquals(0, ((sub1X ushr 4).toInt() - 2048)) // subIdx=1 -> X (0g)
        assertEquals(0, ((sub2Z ushr 4).toInt() - 2048)) // subIdx=2 -> Z (0g)

        // 5) Active IBIS attenuates handheld micro-shake by 90% vs OFF/LOCKED, and cmd 0x0A8 must NOT clobber 0x080(0) OFF
        runCommand(peer, 0x186L, 0L shl 1); runCommand(peer, 0x187L, 0x8000L)
        runCommand(peer, 0x186L, 1L shl 1); runCommand(peer, 0x187L, 0x8000L)
        shakeAmp = 0.25
        val (_, ibisActiveY, _) = peer.sampleShiftPixels(3.76, 1000L)
        runCommand(peer, 0x080L, 0L) // IBIS OFF
        assertEquals(IbisShiftPort.MODE_OFF, peer.ibisMode)
        assertTrue(peer.stageLocked)
        runCommand(peer, 0x0A8L, 2L) // Subsequent 0x0A8 stabilization state must NOT re-enable CONTINUOUS!
        assertEquals(IbisShiftPort.MODE_OFF, peer.ibisMode, "cmd 0x0A8 must not clobber explicit 0x080(0) MODE_OFF")
        assertTrue(peer.stageLocked, "cmd 0x0A8 must not unlock stage while explicit MODE_OFF is active")
        assertEquals(2, peer.stabilizationState)
        val (_, ibisOffY, _) = peer.sampleShiftPixels(3.76, 1000L)
        assertTrue(abs(ibisOffY) > abs(ibisActiveY) * 8.0, "Active IBIS must cancel ~90% of hand-shake (off=$ibisOffY vs active=$ibisActiveY)")

        // 5b) Active mounted LensPort focal length overrides stale/ambiguous 0x0B2 values dynamically
        var activeLensFocal = 45.0
        val liveLensPort = object : LensPort {
            override fun parameters(): LensParameters = LensParameters("gf45-100", activeLensFocal, 4.0, 2.0, 0.9, 0.0)
        }
        val lensLinkedPeer = Frame32SpiPeer(orientationProvider = { orientation }, lensPortProvider = { liveLensPort })
        runCommand(lensLinkedPeer, 0x0B2L, 245L)
        assertEquals(45.0, lensLinkedPeer.focalLengthMm, 1e-4)
        assertEquals(45.0, lensLinkedPeer.ibisStateMap()["focalLengthMm"] as Double, 1e-4)
        activeLensFocal = 110.0
        assertEquals(110.0, lensLinkedPeer.focalLengthMm, 1e-4)
        activeLensFocal = 20.0
        assertEquals(20.0, lensLinkedPeer.focalLengthMm, 1e-4)

        // 6) Snapshot round-trip preserves IBIS mode & Pixel Shift coordinates
        runCommand(peer, 0x080L, 2L) // SHOOTING_ONLY
        runCommand(peer, 0x186L, 0L shl 1); runCommand(peer, 0x187L, 0x8123L)
        val snap = peer.snapshot()
        val peer2 = Frame32SpiPeer(orientationProvider = { orientation })
        peer2.restore(peer2.parse(snap))
        assertEquals(IbisShiftPort.MODE_SHOOTING_ONLY, peer2.ibisMode)
        assertEquals(0x8123, peer2.pixelShiftTargetX)
        assertEquals(snap, peer2.snapshot())
    }
}
