package com.github.nyabanban.fuji.emulator.components.spi

import com.github.nyabanban.fuji.emulator.api.CameraOrientationPort
import com.github.nyabanban.fuji.emulator.api.IbisShiftPort
import com.github.nyabanban.fuji.emulator.api.LensPort
import com.github.nyabanban.fuji.emulator.api.SpiPeer
import java.util.function.LongSupplier
import kotlin.math.roundToInt

/**
 * Genuine 32-bit big-endian framed SPI slave implementing the Fujifilm `biscom` (SPI2, RTOS Task
 * `0x89`) 5-axis IBIS + 16-shot 400MP Pixel Shift sub-MCU protocol (`section4.bin 0x0123060c..0x01233080`):
 * - 1-bit LSB=0 marker, 5-bit checksum `[5:1]`, 9-bit command ID `[14:6]`, 1-bit frame type `[15]`,
 *   16-bit payload/status `[31:16]` with 1-frame full-duplex SPI shift-register pipelining.
 * - Handles runtime IBIS commands (`0x014`, `0x029`, `0x02A`, `0x080`, `0x083`, `0x086`/`0x087`,
 *   `0x0A8`, `0x0A9`, `0x0B1`/`0x0B2`, `0x0C0`, `0x0D0`, `0x181`+`0x122` bank=7 accel fallback &
 *   bank=9, and `0x186`+`0x187` 16-shot 400MP Pixel Shift Hall targets centered at `0x8000`).
 * - Exposes live 5-axis sensor-stage state via [IbisShiftPort] (`soc-spi.biscom.ibis`).
 */
internal class Frame32SpiPeer(
    private val orientationProvider: () -> CameraOrientationPort? = { null },
    private val lensPortProvider: () -> LensPort? = { null },
    private val powerOnSequence: LongSupplier = LongSupplier { -1L },
) : SpiPeer, IbisShiftPort {
    private var observedPowerOnSequence = -1L
    private var txAcc = 0L
    private var txBytes = 0
    private var rxShift = 0L
    private var frameCount = 0L
    private var pendingCmdId = -1L
    private var pendingTxPayload = 0L
    private var slaveSeq = 0L

    // Live DAT-verified biscom IBIS & Pixel Shift actuator state
    private var ibisModeState: Int = IbisShiftPort.MODE_OFF
    private var explicitModeCommanded: Boolean = false
    private var stageLockedState: Boolean = true
    private var stabilizationStateVal: Int = 0
    private var stabilizationGainVal: Int = 0
    private var focalLengthMmVal: Double = 63.0
    private var exposureParamCh0: Int = 0
    private var exposureParamCh1: Int = 0
    private var sensorBankVal: Int = 0
    private var pixelShiftAxisVal: Int = 0 // 0=X, 1=Y
    private var pixelShiftTargetXVal: Int = IbisShiftPort.HALL_CENTER
    private var pixelShiftTargetYVal: Int = IbisShiftPort.HALL_CENTER
    private var commandCount: Long = 0L

    override val ibisMode: Int get() = ibisModeState
    override val ibisModeName: String
        get() = when (ibisModeState) {
            IbisShiftPort.MODE_CONTINUOUS -> "CONTINUOUS"
            IbisShiftPort.MODE_SHOOTING_ONLY -> "SHOOTING_ONLY"
            IbisShiftPort.MODE_LOCK -> "LOCK"
            else -> "OFF"
        }
    override val stageLocked: Boolean get() = stageLockedState
    override val stabilizationState: Int get() = stabilizationStateVal
    override val stabilizationGain: Int get() = stabilizationGainVal
    override val focalLengthMm: Double
        get() = lensPortProvider()?.parameters()?.focalLengthMm?.takeIf { it.isFinite() && it > 0.0 }
            ?: focalLengthMmVal
    override val pixelShiftTargetX: Int get() = pixelShiftTargetXVal
    override val pixelShiftTargetY: Int get() = pixelShiftTargetYVal

    // GFX100S 16-shot Pixel Shift Hall DAC scale: ~0.01 pixel (~0.0376 µm) per DAC LSB around 0x8000
    override val shiftXUm: Double
        get() = (pixelShiftTargetXVal - IbisShiftPort.HALL_CENTER) * HALL_UM_PER_LSB
    override val shiftYUm: Double
        get() = (pixelShiftTargetYVal - IbisShiftPort.HALL_CENTER) * HALL_UM_PER_LSB
    override val rollDeg: Double get() = 0.0

    override fun sampleShiftPixels(pixelPitchUm: Double, tick: Long): Triple<Double, Double, Double> {
        val pitch = pixelPitchUm.coerceAtLeast(0.5)
        var sxPx = shiftXUm / pitch
        var syPx = shiftYUm / pitch
        var rollRad = 0.0

        val orient = orientationProvider()
        if (orient != null && orient.shakeAmpDeg > 1e-6) {
            val (dRoll, dPitch, dYaw) = orient.handShakeDeltaDeg(tick)
            // Uncompensated handheld image-plane shift proportional to focal length * tan(angle)
            val fMm = focalLengthMm.coerceIn(14.0, 500.0)
            val shakeXPx = (fMm * 1000.0 * Math.toRadians(dYaw)) / pitch
            val shakeYPx = (fMm * 1000.0 * Math.toRadians(dPitch)) / pitch
            val shakeRollRad = Math.toRadians(dRoll)
            // When IBIS is active (CONTINUOUS or SHOOTING_ONLY) & unlocked, 5-axis stage cancels ~90% of tremor
            val residualFactor = when {
                !stageLockedState && (ibisModeState == IbisShiftPort.MODE_CONTINUOUS || ibisModeState == IbisShiftPort.MODE_SHOOTING_ONLY) -> 0.10
                else -> 1.0
            }
            sxPx += shakeXPx * residualFactor
            syPx += shakeYPx * residualFactor
            rollRad += shakeRollRad * residualFactor
        }
        return Triple(sxPx, syPx, rollRad)
    }

    fun isInitial(): Boolean =
        txAcc == 0L && txBytes == 0 && rxShift == 0L && frameCount == 0L &&
            pendingCmdId < 0L && pendingTxPayload == 0L && slaveSeq == 0L &&
            ibisModeState == IbisShiftPort.MODE_OFF && stageLockedState &&
            stabilizationStateVal == 0 && stabilizationGainVal == 0 &&
            pixelShiftAxisVal == 0 &&
            pixelShiftTargetXVal == IbisShiftPort.HALL_CENTER &&
            pixelShiftTargetYVal == IbisShiftPort.HALL_CENTER &&
            sensorBankVal == 0 && commandCount == 0L

    override fun exchange(bits: Int, tx: Int, tick: Long): Int {
        require(bits == 8) { "Frame32SpiPeer expects 8-bit transfers, got $bits" }
        // Preserve the shutdown exchange and start a fresh link on the next power-up.
        // The entire power cycle may occur between SPI transfers.
        val sequence = powerOnSequence.asLong
        if (observedPowerOnSequence >= 0L && sequence >= 0L && sequence != observedPowerOnSequence) {
            txAcc = 0L
            txBytes = 0
            rxShift = 0L
            pendingCmdId = -1L
            pendingTxPayload = 0L
            slaveSeq = 0L
        }
        if (sequence >= 0L) observedPowerOnSequence = sequence
        val outByte = ((rxShift ushr ((3 - txBytes) * 8)) and 0xffL).toInt()
        txAcc = (txAcc shl 8) or (tx.toLong() and 0xffL)
        txBytes++
        if (txBytes == 4) {
            val sentData = rxShift != 0L && (rxShift and 1L) == 0L && ((rxShift ushr 15) and 1L) == 0L
            stepWord(txAcc, sentData, tick)
            txAcc = 0L
            txBytes = 0
            frameCount++
        }
        return outByte
    }

    private fun stepWord(tx: Long, sentData: Boolean, tick: Long) {
        if (tx == 0xA55A3CC3L || tx == 0xC33C5AA5L || (tx and 1L) != 0L) {
            rxShift = 0L; pendingCmdId = -1L; pendingTxPayload = 0L; return
        }
        if (((tx ushr 1) and 0x1fL) != checksum(tx)) {
            rxShift = 0L; pendingCmdId = -1L; pendingTxPayload = 0L; return
        }
        val cmdId = (tx ushr 6) and 0x1ffL
        val frameType = (tx ushr 15) and 1L
        val txPayload16 = (tx ushr 16) and 0xffffL
        if (frameType == 0L && cmdId != 0x3c0L) {
            val seq = slaveSeq and 0x7L
            val nextSeq = if (sentData) ((slaveSeq + 1L) and 0x7L) else seq
            val ackBase = (1L shl 27) or (seq shl 24) or (1L shl 15) or (cmdId shl 6)
            rxShift = ackBase or (checksum(ackBase) shl 1)
            pendingCmdId = cmdId
            pendingTxPayload = txPayload16
            slaveSeq = nextSeq
            return
        }
        if (frameType == 1L && pendingCmdId >= 0L) {
            val reply16 = executeBiscomCommand(pendingCmdId, pendingTxPayload, tick) and 0xffffL
            val dataBase = (reply16 shl 16) or (pendingCmdId shl 6)
            rxShift = dataBase or (checksum(dataBase) shl 1)
            pendingCmdId = -1L
            pendingTxPayload = 0L
            return
        }
        rxShift = 0L; pendingCmdId = -1L; pendingTxPayload = 0L
    }

    private fun executeBiscomCommand(cmdId: Long, txPayload: Long, tick: Long): Long {
        commandCount++
        val b0 = (txPayload and 0xffL).toInt()
        val b1 = ((txPayload ushr 8) and 0xffL).toInt()
        return when (cmdId) {
            0x014L -> 0x0120L // IBIS MCU version / ID (DAT 0x0123060c -> [b3, b2])
            0x029L -> 0x0000L // 16-bit MCU status / zero fault flags (DAT 0x01230740)
            0x02AL -> {
                // Signed 12-bit sensor/temp telemetry {u4[3:0]=ch, s12[15:4]=25C} (DAT 0x012307d0)
                val ch = b0 and 0x03
                ((25 shl 4) or ch).toLong()
            }
            0x080L -> {
                // Primary IBIS Operating Mode: b0[2:0] = 0:OFF, 1:CONTINUOUS, 2:SHOOTING_ONLY, 3:LOCK
                val mode = b0 and 0x07
                explicitModeCommanded = true
                ibisModeState = mode
                stageLockedState = (mode == IbisShiftPort.MODE_OFF || mode == IbisShiftPort.MODE_LOCK)
                0x0000L
            }
            0x083L -> 0x0000L // Init/enable trigger
            0x086L -> {
                stageLockedState = true
                0x0000L
            }
            0x087L -> {
                // Stage unlock/release control (DAT 0x01230c00): b0[1:0] = unlock mode (0/1/2 = unlocked, 3 = lock)
                val unlockMode = b0 and 0x03
                stageLockedState = (unlockMode == 3)
                0x0000L
            }
            0x0A8L -> {
                // 5-Axis IBIS / OIS Stabilization State (1..5, DAT 0x01230d00)
                stabilizationStateVal = b0 and 0x0f
                if (!explicitModeCommanded && stabilizationStateVal > 0 && ibisModeState == IbisShiftPort.MODE_OFF) {
                    ibisModeState = IbisShiftPort.MODE_CONTINUOUS
                    stageLockedState = false
                }
                0x0000L
            }
            0x0A9L -> {
                // IBIS Stabilization Gain/Parameter {b0[3:0], b0[5:4], u10[15:6]} (DAT 0x01230da4)
                stabilizationGainVal = ((txPayload ushr 6) and 0x3ffL).toInt()
                0x0000L
            }
            0x0B1L -> {
                // Optical lens focal-length / flag packet (DAT 0x01230f80): bit15=1 carries 15-bit focal length
                if ((txPayload and 0x8000L) != 0L) {
                    val rawFocal = (txPayload and 0x7fffL).toInt()
                    if (rawFocal in 10..2000) focalLengthMmVal = rawFocal.toDouble()
                }
                0x0000L
            }
            0x0B2L -> {
                val rawFocal = (txPayload and 0xffffL).toInt()
                if (rawFocal in 100..20000) focalLengthMmVal = rawFocal / 10.0
                else if (rawFocal in 10..2000) focalLengthMmVal = rawFocal.toDouble()
                0x0000L
            }
            0x0D0L -> {
                // 2-Channel 14-bit Exposure/Optical Parameter {ch[1:0], val[15:2]}
                val ch = (txPayload and 0x03L).toInt()
                val val14 = ((txPayload ushr 2) and 0x3fffL).toInt()
                if (ch == 0) exposureParamCh0 = val14 else if (ch == 1) exposureParamCh1 = val14
                0x0000L
            }
            0x181L -> {
                // Latch IBIS MCU sensor bank: {opt[0], bank[4:1]} (DAT 0x01231bec)
                sensorBankVal = ((b0 ushr 1) and 0x0f)
                0x0000L
            }
            0x122L -> {
                // Read latched sensor bank (DAT 0x01231948): b0[1:0]=subIdx, b0[5:2]=bank -> u16
                // bank=7: 3-axis accel in 12-bit offset-binary `(raw12 + 2048) << 4`
                // levelsens (DAT 0x01216e90..0x01216f98): subIdx=0 -> Y (+0x66), subIdx=1 -> X (+0x52), subIdx=2 -> Z (+0x7a)
                val subIdx = b0 and 0x03
                val packedBank = (b0 ushr 2) and 0x0f
                val bank = if (packedBank != 0) packedBank else sensorBankVal
                if (bank == 7) {
                    val orient = orientationProvider()
                    val g = orient?.bodyGravityG(tick)
                    val axisG = when (subIdx) {
                        0 -> g?.yG ?: 0.0
                        1 -> g?.xG ?: 1.0
                        2 -> g?.zG ?: 0.0
                        else -> 0.0
                    }
                    val raw12 = (axisG * 1024.0).roundToInt().coerceIn(-2048, 2047)
                    val offsetBin12 = (raw12 + 2048).coerceIn(0, 4095)
                    ((offsetBin12 shl 4) and 0xffff).toLong()
                } else {
                    0x0000L
                }
            }
            0x186L -> {
                // Pixel Shift Multi-Shot axis select: b0[2:1] -> 0=X, 1=Y (DAT 0x01231f50)
                pixelShiftAxisVal = (b0 ushr 1) and 0x03
                0x0000L
            }
            0x187L -> {
                // Pixel Shift Multi-Shot 16-bit unsigned Hall target centered at 0x8000 (DAT 0x01232004)
                val target = (txPayload and 0xffffL).toInt()
                if (pixelShiftAxisVal == 0) {
                    pixelShiftTargetXVal = target
                } else if (pixelShiftAxisVal == 1) {
                    pixelShiftTargetYVal = target
                }
                0x0000L
            }
            else -> 0x0000L
        }
    }

    fun ibisStateMap(): Map<String, Any?> = linkedMapOf(
        "ibisMode" to ibisModeState,
        "ibisModeName" to ibisModeName,
        "stageLocked" to stageLockedState,
        "stabilizationState" to stabilizationStateVal,
        "stabilizationGain" to stabilizationGainVal,
        "focalLengthMm" to focalLengthMm,
        "pixelShiftAxis" to pixelShiftAxisVal,
        "pixelShiftTargetX" to pixelShiftTargetXVal,
        "pixelShiftTargetY" to pixelShiftTargetYVal,
        "shiftXUm" to shiftXUm,
        "shiftYUm" to shiftYUm,
        "sensorBank" to sensorBankVal,
        "commandCount" to commandCount,
    )

    fun snapshot(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "txAcc" to txAcc,
        "txBytes" to txBytes,
        "rxShift" to rxShift,
        "frameCount" to frameCount,
    ) + (if (observedPowerOnSequence >= 0L) mapOf("powerOnSequence" to observedPowerOnSequence) else emptyMap()) +
        (if (pendingCmdId >= 0L) mapOf("pendingCmdId" to pendingCmdId) else emptyMap()) +
        (if (pendingTxPayload != 0L) mapOf("pendingTxPayload" to pendingTxPayload) else emptyMap()) +
        (if (slaveSeq != 0L) mapOf("slaveSeq" to slaveSeq) else emptyMap()) +
        (if (ibisModeState != IbisShiftPort.MODE_OFF) mapOf("ibisMode" to ibisModeState) else emptyMap()) +
        (if (!stageLockedState) mapOf("stageLocked" to stageLockedState) else emptyMap()) +
        (if (stabilizationStateVal != 0) mapOf("stabilizationState" to stabilizationStateVal) else emptyMap()) +
        (if (stabilizationGainVal != 0) mapOf("stabilizationGain" to stabilizationGainVal) else emptyMap()) +
        (if (pixelShiftAxisVal != 0) mapOf("pixelShiftAxis" to pixelShiftAxisVal) else emptyMap()) +
        (if (pixelShiftTargetXVal != IbisShiftPort.HALL_CENTER) mapOf("pixelShiftTargetX" to pixelShiftTargetXVal) else emptyMap()) +
        (if (pixelShiftTargetYVal != IbisShiftPort.HALL_CENTER) mapOf("pixelShiftTargetY" to pixelShiftTargetYVal) else emptyMap()) +
        (if (sensorBankVal != 0) mapOf("sensorBank" to sensorBankVal) else emptyMap()) +
        (if (commandCount != 0L) mapOf("commandCount" to commandCount) else emptyMap())

    data class ParsedState(
        val txAcc: Long,
        val txBytes: Int,
        val rxShift: Long,
        val frameCount: Long,
        val pendingCmdId: Long = -1L,
        val pendingTxPayload: Long = 0L,
        val slaveSeq: Long = 0L,
        val ibisMode: Int = IbisShiftPort.MODE_OFF,
        val stageLocked: Boolean = true,
        val stabilizationState: Int = 0,
        val stabilizationGain: Int = 0,
        val pixelShiftAxis: Int = 0,
        val pixelShiftTargetX: Int = IbisShiftPort.HALL_CENTER,
        val pixelShiftTargetY: Int = IbisShiftPort.HALL_CENTER,
        val sensorBank: Int = 0,
        val commandCount: Long = 0L,
        val powerOnSequence: Long = -1L,
    )

    fun parse(raw: Any?): ParsedState {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid biscom peer snapshot")
        require(map.keys - OPTIONAL_SNAPSHOT_KEYS == setOf("txAcc", "txBytes", "rxShift", "frameCount")) {
            "Unexpected biscom peer snapshot keys"
        }
        val acc = parseIntegralLong(map["txAcc"], "txAcc")
        require(acc in 0L..0xffffffffL) { "Out-of-range biscom txAcc" }
        val bytes = parseIntegralInt(map["txBytes"], "txBytes")
        require(bytes in 0..3) { "Invalid biscom txBytes" }
        val shift = parseIntegralLong(map["rxShift"], "rxShift")
        require(shift in 0L..0xffffffffL) { "Out-of-range biscom rxShift" }
        val frames = parseIntegralLong(map["frameCount"], "frameCount")
        require(frames >= 0L) { "Negative biscom frameCount" }
        val pending = map["pendingCmdId"]?.let {
            parseIntegralLong(it, "pendingCmdId").also { id -> require(id in 0L..0x1ffL) { "Out-of-range biscom pendingCmdId" } }
        } ?: -1L
        val pendingPayload = map["pendingTxPayload"]?.let {
            parseIntegralLong(it, "pendingTxPayload").also { p -> require(p in 0L..0xffffL) { "Out-of-range biscom pendingTxPayload" } }
        } ?: 0L
        val seq = map["slaveSeq"]?.let {
            parseIntegralLong(it, "slaveSeq").also { s -> require(s in 0L..7L) { "Out-of-range biscom slaveSeq" } }
        } ?: 0L
        val mode = map["ibisMode"]?.let { parseIntegralInt(it, "ibisMode").also { m -> require(m in 0..7) } } ?: IbisShiftPort.MODE_OFF
        val locked = map["stageLocked"]?.let { it as? Boolean ?: throw IllegalArgumentException("Invalid biscom stageLocked") } ?: true
        val stabState = map["stabilizationState"]?.let { parseIntegralInt(it, "stabilizationState").also { s -> require(s in 0..15) } } ?: 0
        val stabGain = map["stabilizationGain"]?.let { parseIntegralInt(it, "stabilizationGain").also { g -> require(g in 0..0x3ff) } } ?: 0
        val psAxis = map["pixelShiftAxis"]?.let { parseIntegralInt(it, "pixelShiftAxis").also { a -> require(a in 0..3) } } ?: 0
        val psX = map["pixelShiftTargetX"]?.let { parseIntegralInt(it, "pixelShiftTargetX").also { v -> require(v in 0..0xffff) } } ?: IbisShiftPort.HALL_CENTER
        val psY = map["pixelShiftTargetY"]?.let { parseIntegralInt(it, "pixelShiftTargetY").also { v -> require(v in 0..0xffff) } } ?: IbisShiftPort.HALL_CENTER
        val bank = map["sensorBank"]?.let { parseIntegralInt(it, "sensorBank").also { b -> require(b in 0..15) } } ?: 0
        val cmds = map["commandCount"]?.let { parseIntegralLong(it, "commandCount").also { c -> require(c >= 0L) } } ?: 0L
        // Older snapshots stored a counter for GPIO 12.2. Preserve their in-flight bytes
        // and establish the GPIO 3.4 baseline on the first transfer after loading.
        val powerOnSequence = map["powerOnSequence"]?.let {
            parseIntegralLong(it, "powerOnSequence").also { sequence -> require(sequence >= 0L) { "Negative biscom powerOnSequence" } }
        } ?: -1L
        return ParsedState(acc, bytes, shift, frames, pending, pendingPayload, seq, mode, locked, stabState, stabGain, psAxis, psX, psY, bank, cmds, powerOnSequence)
    }

    fun restore(parsed: ParsedState?) {
        observedPowerOnSequence = parsed?.powerOnSequence ?: -1L
        if (parsed == null) {
            txAcc = 0L; txBytes = 0; rxShift = 0L; frameCount = 0L; pendingCmdId = -1L; pendingTxPayload = 0L; slaveSeq = 0L
            ibisModeState = IbisShiftPort.MODE_OFF; explicitModeCommanded = false; stageLockedState = true
            stabilizationStateVal = 0; stabilizationGainVal = 0
            pixelShiftAxisVal = 0; pixelShiftTargetXVal = IbisShiftPort.HALL_CENTER; pixelShiftTargetYVal = IbisShiftPort.HALL_CENTER
            sensorBankVal = 0; commandCount = 0L
        } else {
            txAcc = parsed.txAcc; txBytes = parsed.txBytes; rxShift = parsed.rxShift; frameCount = parsed.frameCount
            pendingCmdId = parsed.pendingCmdId; pendingTxPayload = parsed.pendingTxPayload; slaveSeq = parsed.slaveSeq
            ibisModeState = parsed.ibisMode; explicitModeCommanded = (parsed.ibisMode != IbisShiftPort.MODE_OFF)
            stageLockedState = parsed.stageLocked
            stabilizationStateVal = parsed.stabilizationState; stabilizationGainVal = parsed.stabilizationGain
            pixelShiftAxisVal = parsed.pixelShiftAxis
            pixelShiftTargetXVal = parsed.pixelShiftTargetX; pixelShiftTargetYVal = parsed.pixelShiftTargetY
            sensorBankVal = parsed.sensorBank; commandCount = parsed.commandCount
        }
    }

    companion object {
        // 16-shot 400MP Pixel Shift nominal Hall scale (~0.0376 µm = 0.01 px per DAC count)
        private const val HALL_UM_PER_LSB = 0.0376
        private val OPTIONAL_SNAPSHOT_KEYS = setOf(
            "pendingCmdId", "pendingTxPayload", "slaveSeq", "powerOnSequence", "resetSequence",
            "ibisMode", "stageLocked", "stabilizationState", "stabilizationGain",
            "pixelShiftAxis", "pixelShiftTargetX", "pixelShiftTargetY",
            "sensorBank", "commandCount",
        )

        fun checksum(word: Long): Long =
            (((word ushr 27) and 0x1fL) + ((word ushr 22) and 0x1fL) + ((word ushr 17) and 0x1fL) +
                ((word ushr 12) and 0x1fL) + ((word ushr 7) and 0x1fL) + ((word ushr 6) and 1L)) and 0x1fL

        fun stepFrame(tx: Long, pendingCmdId: Long, slaveSeq: Long = 0L, sentData: Boolean = false): Triple<Long, Long, Long> {
            if (tx == 0xA55A3CC3L || tx == 0xC33C5AA5L || (tx and 1L) != 0L) return Triple(0L, -1L, slaveSeq)
            if (((tx ushr 1) and 0x1fL) != checksum(tx)) return Triple(0L, -1L, slaveSeq)
            val cmdId = (tx ushr 6) and 0x1ffL
            val frameType = (tx ushr 15) and 1L
            if (frameType == 0L && cmdId != 0x3c0L) {
                val seq = slaveSeq and 0x7L
                val nextSeq = if (sentData) ((slaveSeq + 1L) and 0x7L) else seq
                val ackBase = (1L shl 27) or (seq shl 24) or (1L shl 15) or (cmdId shl 6)
                return Triple(ackBase or (checksum(ackBase) shl 1), cmdId, nextSeq)
            }
            if (frameType == 1L && pendingCmdId >= 0L) {
                val payload = if (pendingCmdId == 0x14L) 0x0120L shl 16 else 0L
                val dataBase = payload or (pendingCmdId shl 6)
                return Triple(dataBase or (checksum(dataBase) shl 1), -1L, slaveSeq)
            }
            return Triple(0L, -1L, slaveSeq)
        }

        fun buildReply(tx: Long): Long = stepFrame(tx, -1L, 0L).first

        private fun parseIntegralLong(value: Any?, field: String): Long = when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            else -> throw IllegalArgumentException("$field requires integer")
        }

        private fun parseIntegralInt(value: Any?, field: String): Int =
            parseIntegralLong(value, field).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    }
}
