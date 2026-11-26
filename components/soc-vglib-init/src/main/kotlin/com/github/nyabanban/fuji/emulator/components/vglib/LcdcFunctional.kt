package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.logging.Logger

/**
 * Behavioural LCD-controller bank selected explicitly for display bring-up.
 * Words are retained; after the firmware's WRITE4(0) to word0 its bit0 poll reads 1.
 * That completion law comes from the consumer loop at 12645C0 and is HYPOTHESIS, not measured silicon.
 *
 * Optional hardware power/clock-gating (used for EVF `LCDC0` at `0xFFF7A000..0xFFF7AFFF`):
 * when `initiallyPowered = false`, accesses before `LCDC0` is powered/ungated (either via firmware
 * reset/enable writes `+0x000 = 0` / `+0x004 bit0 = 1` or via [LcdcPowerGatePort]) increment
 * `unclockedAccessCount` and enforce `unclockedAccessPolicy` (`"warn"` | `"refuse"` | `"abort"`).
 */
internal class LcdcFunctional(
    private val context: ComponentContext,
    val instance: Int,
    private val scanout: LcdcFirstScanout? = null,
    private val initiallyPowered: Boolean = true,
    private val unclockedAccessPolicy: String = POLICY_WARN,
) : MmioDevice {
    override val clockOrder = 90
    override val clockIndex = instance
    val port get() = port(instance)
    private val words = LongArray(WORDS)
    private val readCounts = LongArray(WORDS)
    private val writeCounts = LongArray(WORDS)
    private var resetWrites = 0L
    private var resetPolls = 0L
    private var resetCompleted = false
    private var powered: Boolean = initiallyPowered
    private var clocked: Boolean = initiallyPowered
    private var unclockedAccessCount = 0L
    private var lastUnclockedOffset: Long? = null
    private var lastUnclockedWrite: Boolean? = null
    private var warnedUnclocked = false

    // First accesses only; counts above stay complete. No per-access allocation.
    private val logOffsets = IntArray(LOG)
    private val logValues = LongArray(LOG)
    private val logTicks = LongArray(LOG)
    private val logPcs = LongArray(LOG) { -1 }
    private val logCores = IntArray(LOG) { -1 }
    private val logWrites = BooleanArray(LOG)
    private var logged = 0
    private var observed = 0L

    init {
        require(instance in 0..1) { "LCD controller instance outside 0..1" }
        require(unclockedAccessPolicy in VALID_POLICIES) { "Unsupported LCDC unclockedAccessPolicy: $unclockedAccessPolicy" }
    }

    internal fun setPowerClock(nextPowered: Boolean, nextClocked: Boolean = nextPowered) {
        powered = nextPowered
        clocked = nextClocked
    }

    internal fun layerOutputState(): LcdcLayerOutputState {
        val reg008 = words[0x008 / 4]
        val reg030 = words[0x030 / 4]
        return LcdcLayerOutputState(
            reg008 = reg008,
            reg030 = reg030,
            activeOutputGate = (reg008 and LcdcFirstScanout.LAYER_EN_ACTIVE_GATE) != 0L,
            osd0Enabled = (reg008 and LcdcFirstScanout.LAYER_EN_OSD0) != 0L,
            pic0Enabled = (reg008 and LcdcFirstScanout.LAYER_EN_PIC0) != 0L,
            pic1Enabled = (reg008 and LcdcFirstScanout.LAYER_EN_PIC1) != 0L,
            osd0Mode = LcdcFirstScanout.classifyOsd0Mode(reg030),
            outputActive = scanout?.outputActive ?: false,
            blankReason = scanout?.blankReason,
            activeGateBlankCount = scanout?.activeGateBlankCount ?: 0L,
        )
    }

    internal fun powerGateState(): LcdcPowerGateState = LcdcPowerGateState(
        instance = instance,
        powered = powered,
        clocked = clocked,
        policy = unclockedAccessPolicy,
        unclockedAccessCount = unclockedAccessCount,
        lastUnclockedOffset = lastUnclockedOffset,
        lastUnclockedWrite = lastUnclockedWrite,
    )

    private fun isGatedOff(): Boolean = !powered || !clocked

    private fun enforcePowerClockGate(offset: Long, write: Boolean) {
        if (!isGatedOff()) return
        if (unclockedAccessCount < Long.MAX_VALUE) unclockedAccessCount++
        lastUnclockedOffset = offset
        lastUnclockedWrite = write
        val baseHex = if (instance == 0) "0xfff7a000" else "0xfffa9000"
        val dir = if (write) "write" else "read"
        when (unclockedAccessPolicy) {
            POLICY_ABORT -> throw PeripheralBusAbortException(
                peripheralId = "soc-vglib-init.lcdc$instance",
                offset = offset,
                write = write,
                dfsr = if (write) 0x808L else 0x008L,
                message = "AXI External Data Abort: LCDC$instance ($baseHex) power/clock-gated $dir at +0x${offset.toString(16)}",
            )
            POLICY_REFUSE -> throw IllegalStateException(
                "LCDC$instance ($baseHex) power/clock-gated $dir refused at +0x${offset.toString(16)}",
            )
            else -> {
                if (!warnedUnclocked) {
                    warnedUnclocked = true
                    LOGGER.warning("[LCDC$instance/POWER-GATE] Unclocked $dir at $baseHex+0x${offset.toString(16)} (powered=$powered, clocked=$clocked)")
                }
            }
        }
    }

    private fun index(offset: Long, size: Int): Int {
        require(size == 4 && offset >= 0 && offset % 4 == 0L && offset < SIZE) { "LCD functional bank requires an exact aligned word" }
        return (offset / 4).toInt()
    }

    // Validation and observation happen before any mutation, so a refused access leaves the bank unchanged.
    private fun observe(index: Int, write: Boolean, value: Long) {
        val record = logged < LOG
        val tick = if (record) context.clock.ticks() else 0L
        require(tick >= 0) { "invalid LCD functional clock" }
        val location = if (record) context.ports.find("cpu.execution-location", ExecutionLocationSource::class.java)?.current() else null
        if (write) scanout?.write(index * 4L, value, words, writeCounts)
        observed++
        if (!record) return
        logOffsets[logged] = index * 4
        logValues[logged] = value
        logTicks[logged] = tick
        logWrites[logged] = write
        logCores[logged] = location?.core ?: -1
        logPcs[logged] = location?.pcVirtual ?: -1
        logged++
    }

    override fun read(offset: Long, size: Int): Long {
        val i = index(offset, size)
        enforcePowerClockGate(offset, write = false)
        val poll = i == 0 && resetCompleted
        val value = if (poll) words[0] or 1L else words[i]
        // The completion poll may repeat; only its first observation needs a coordinate.
        if (!poll || resetPolls == 0L) observe(i, false, value) else observed++
        if (poll) resetPolls++
        readCounts[i]++
        return value
    }

    override fun write(offset: Long, size: Int, value: Long) {
        val i = index(offset, size)
        require(value in 0..0xffffffffL) { "LCD functional word exceeds u32" }
        // Firmware EVF/LCDC initialization (word0 reset write 0 or +0x004 Run enable bit 0) ungates LCDC0.
        if (isGatedOff() && ((i == 0 && value == 0L) || (i == 1 && (value and 1L) != 0L))) {
            powered = true
            clocked = true
        } else {
            enforcePowerClockGate(offset, write = true)
        }
        observe(i, true, value)
        if (i == 0 && value == 0L) {
            resetWrites++
            resetCompleted = true
        }
        words[i] = value
        writeCounts[i]++
    }

    fun state(): Map<String, Any?> = mapOf(
        "profile" to PROFILE,
        "instance" to instance,
        "port" to port,
        "confidence" to "HYPOTHESIS",
        "sizeBytes" to SIZE,
        "powered" to powered,
        "clocked" to clocked,
        "unclockedAccessPolicy" to unclockedAccessPolicy,
        "unclockedAccessCount" to unclockedAccessCount,
        "resetWrites" to resetWrites,
        "resetPolls" to resetPolls,
        "resetCompleted" to resetCompleted,
        "observedAccesses" to observed,
        "transactionCapacity" to LOG,
        "transactionCount" to logged,
        "eventLogTruncated" to (observed > logged),
        "touchedWords" to (0 until WORDS).filter { readCounts[it] != 0L || writeCounts[it] != 0L }.map {
            mapOf("offset" to it * 4, "value" to words[it], "reads" to readCounts[it], "writes" to writeCounts[it])
        },
        "transactions" to (0 until logged).map {
            mapOf(
                "offset" to logOffsets[it],
                "direction" to if (logWrites[it]) "write" else "read",
                "size" to 4,
                "value" to logValues[it],
                "tick" to logTicks[it],
                "core" to logCores[it].takeIf { c -> c >= 0 },
                "pcVirtual" to logPcs[it].takeIf { p -> p >= 0 },
            )
        },
        "scope" to "Retained words with zero initial content and word0 bit0 reading 1 after a guest WRITE4(0). Initial values, retention, power/clock gate policy, and completion bit are HYPOTHESIS from consumer code and real-silicon observations.",
    )

    internal fun saveSnapshotState(): Map<String, Any> {
        val touchedIndices = (0 until WORDS).filter { wordIndex ->
            words[wordIndex] != 0L || readCounts[wordIndex] != 0L || writeCounts[wordIndex] != 0L
        }
        val touchedBuffer = ByteBuffer.allocate(touchedIndices.size * 24).order(ByteOrder.LITTLE_ENDIAN)
        for (wordIndex in touchedIndices) {
            touchedBuffer.putInt(wordIndex)
            touchedBuffer.putInt(words[wordIndex].toInt())
            touchedBuffer.putLong(readCounts[wordIndex])
            touchedBuffer.putLong(writeCounts[wordIndex])
        }
        val logBuffer = ByteBuffer.allocate(logged * 29).order(ByteOrder.LITTLE_ENDIAN)
        for (entryIndex in 0 until logged) {
            logBuffer.putInt(logOffsets[entryIndex])
            logBuffer.put(if (logWrites[entryIndex]) 1.toByte() else 0.toByte())
            logBuffer.putInt(logValues[entryIndex].toInt())
            logBuffer.putLong(logTicks[entryIndex])
            logBuffer.putInt(logCores[entryIndex])
            logBuffer.putLong(logPcs[entryIndex])
        }
        val out = linkedMapOf<String, Any>(
            "instance" to instance,
            "resetWrites" to resetWrites,
            "resetPolls" to resetPolls,
            "resetCompleted" to resetCompleted,
            "observedAccesses" to observed,
            "loggedCount" to logged,
            "touchedWordsBase64" to Base64.getEncoder().encodeToString(touchedBuffer.array()),
            "loggedBase64" to Base64.getEncoder().encodeToString(logBuffer.array()),
        )
        if (!initiallyPowered || unclockedAccessPolicy != POLICY_WARN || !powered || !clocked || unclockedAccessCount > 0L) {
            val gate = linkedMapOf<String, Any>(
                "powered" to powered,
                "clocked" to clocked,
                "unclockedAccessCount" to unclockedAccessCount,
            )
            lastUnclockedOffset?.let { gate["lastUnclockedOffset"] = it }
            lastUnclockedWrite?.let { gate["lastUnclockedWrite"] = it }
            out["powerGate"] = gate
        }
        return out
    }

    internal fun parseSnapshotState(raw: Any?): VglibSnapshotCodec.ParsedLcdcFunctionalState {
        val map = VglibSnapshotCodec.requireMap(raw, "lcdcFunctional[$instance]")
        val baseKeys = setOf(
            "instance", "resetWrites", "resetPolls", "resetCompleted",
            "observedAccesses", "loggedCount", "touchedWordsBase64", "loggedBase64",
        )
        require(map.keys - "powerGate" == baseKeys) { "Unexpected keys in lcdcFunctional[$instance] snapshot" }
        require(VglibSnapshotCodec.requireInt(map["instance"], "instance", 0..1) == instance) {
            "LCD functional bank instance mismatch"
        }
        val nextResetWrites = VglibSnapshotCodec.requireLong(map["resetWrites"], "resetWrites")
        require(nextResetWrites >= 0L) { "Negative resetWrites in LCD functional bank" }
        val nextResetPolls = VglibSnapshotCodec.requireLong(map["resetPolls"], "resetPolls")
        require(nextResetPolls >= 0L) { "Negative resetPolls in LCD functional bank" }
        val nextResetCompleted = VglibSnapshotCodec.requireBoolean(map["resetCompleted"], "resetCompleted")
        require(nextResetCompleted == (nextResetWrites > 0L)) { "LCD resetCompleted disagrees with resetWrites" }
        if (!nextResetCompleted) {
            require(nextResetPolls == 0L) { "LCD resetPolls cannot be non-zero before reset completion" }
        }
        val nextObserved = VglibSnapshotCodec.requireLong(map["observedAccesses"], "observedAccesses")
        require(nextObserved >= 0L) { "Negative observedAccesses in LCD functional bank" }
        val nextLogged = VglibSnapshotCodec.requireInt(map["loggedCount"], "loggedCount", 0..LOG)
        require(nextLogged.toLong() <= nextObserved) { "LCD loggedCount exceeds observedAccesses" }

        val touchedBytes = Base64.getDecoder().decode(VglibSnapshotCodec.requireString(map["touchedWordsBase64"], "touchedWordsBase64"))
        require(touchedBytes.size % 24 == 0 && touchedBytes.size / 24 <= WORDS) { "Invalid touchedWordsBase64 byte length" }
        val touchedBuffer = ByteBuffer.wrap(touchedBytes).order(ByteOrder.LITTLE_ENDIAN)
        val nextWords = LongArray(WORDS)
        val nextReadCounts = LongArray(WORDS)
        val nextWriteCounts = LongArray(WORDS)
        var previousWordIndex = -1
        var totalAccesses = 0L
        repeat(touchedBytes.size / 24) {
            val wordIndex = touchedBuffer.getInt()
            require(wordIndex in 0 until WORDS && wordIndex > previousWordIndex) {
                "Out-of-order or out-of-range wordIndex in touchedWordsBase64"
            }
            previousWordIndex = wordIndex
            val wordValue = touchedBuffer.getInt().toLong() and 0xffffffffL
            val reads = touchedBuffer.getLong()
            val writes = touchedBuffer.getLong()
            require(reads >= 0L && writes >= 0L && (reads > 0L || writes > 0L)) {
                "Invalid read/write counts in touchedWordsBase64"
            }
            if (writes == 0L) {
                require(wordValue == 0L) { "Untouched LCD word cannot carry non-zero retained value" }
            }
            nextWords[wordIndex] = wordValue
            nextReadCounts[wordIndex] = reads
            nextWriteCounts[wordIndex] = writes
            totalAccesses = try {
                Math.addExact(totalAccesses, Math.addExact(reads, writes))
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("Overflow summing LCD word access counts", overflow)
            }
        }
        require(totalAccesses == nextObserved) { "LCD observedAccesses disagrees with per-word read/write totals" }
        require(nextResetWrites <= nextWriteCounts[0] && nextResetPolls <= nextReadCounts[0]) {
            "LCD word0 reset counters exceed word0 read/write totals"
        }

        val logBytes = Base64.getDecoder().decode(VglibSnapshotCodec.requireString(map["loggedBase64"], "loggedBase64"))
        require(logBytes.size == nextLogged * 29) { "Invalid loggedBase64 byte length" }
        val logBuffer = ByteBuffer.wrap(logBytes).order(ByteOrder.LITTLE_ENDIAN)
        val nextLogOffsets = IntArray(LOG)
        val nextLogWrites = BooleanArray(LOG)
        val nextLogValues = LongArray(LOG)
        val nextLogTicks = LongArray(LOG)
        val nextLogCores = IntArray(LOG) { -1 }
        val nextLogPcs = LongArray(LOG) { -1L }
        for (entryIndex in 0 until nextLogged) {
            val offset = logBuffer.getInt()
            require(offset in 0 until SIZE.toInt() && offset % 4 == 0) { "Invalid offset in LCD log" }
            val directionFlag = logBuffer.get().toInt()
            require(directionFlag == 0 || directionFlag == 1) { "Invalid direction byte in LCD log" }
            val wordValue = logBuffer.getInt().toLong() and 0xffffffffL
            val tick = logBuffer.getLong()
            val core = logBuffer.getInt()
            val pcVirtual = logBuffer.getLong()
            require(tick >= 0L && core >= -1 && pcVirtual >= -1L) { "Invalid coordinate in LCD log" }
            nextLogOffsets[entryIndex] = offset
            nextLogWrites[entryIndex] = directionFlag == 1
            nextLogValues[entryIndex] = wordValue
            nextLogTicks[entryIndex] = tick
            nextLogCores[entryIndex] = core
            nextLogPcs[entryIndex] = pcVirtual
        }

        var nextPowered: Boolean? = null
        var nextClocked: Boolean? = null
        var nextUnclockedCount = 0L
        var nextLastOff: Long? = null
        var nextLastWrite: Boolean? = null
        if ("powerGate" in map) {
            val pg = VglibSnapshotCodec.requireMap(map["powerGate"], "lcdcFunctional[$instance].powerGate")
            require(pg.keys - setOf("lastUnclockedOffset", "lastUnclockedWrite") == setOf("powered", "clocked", "unclockedAccessCount")) {
                "Unexpected keys in LCDC powerGate snapshot"
            }
            nextPowered = VglibSnapshotCodec.requireBoolean(pg["powered"], "powerGate.powered")
            nextClocked = VglibSnapshotCodec.requireBoolean(pg["clocked"], "powerGate.clocked")
            nextUnclockedCount = VglibSnapshotCodec.requireLong(pg["unclockedAccessCount"], "powerGate.unclockedAccessCount")
            require(nextUnclockedCount >= 0L) { "Negative unclockedAccessCount in LCDC powerGate" }
            pg["lastUnclockedOffset"]?.let {
                val off = VglibSnapshotCodec.requireLong(it, "powerGate.lastUnclockedOffset")
                require(off in 0L until SIZE && off % 4L == 0L) { "Invalid lastUnclockedOffset in LCDC powerGate" }
                nextLastOff = off
            }
            pg["lastUnclockedWrite"]?.let {
                nextLastWrite = VglibSnapshotCodec.requireBoolean(it, "powerGate.lastUnclockedWrite")
            }
        }
        return VglibSnapshotCodec.ParsedLcdcFunctionalState(
            instance = instance,
            words = nextWords,
            readCounts = nextReadCounts,
            writeCounts = nextWriteCounts,
            resetWrites = nextResetWrites,
            resetPolls = nextResetPolls,
            resetCompleted = nextResetCompleted,
            observed = nextObserved,
            logged = nextLogged,
            logOffsets = nextLogOffsets,
            logWrites = nextLogWrites,
            logValues = nextLogValues,
            logTicks = nextLogTicks,
            logCores = nextLogCores,
            logPcs = nextLogPcs,
            powered = nextPowered,
            clocked = nextClocked,
            unclockedAccessCount = nextUnclockedCount,
            lastUnclockedOffset = nextLastOff,
            lastUnclockedWrite = nextLastWrite,
        )
    }

    internal fun applySnapshotState(parsed: VglibSnapshotCodec.ParsedLcdcFunctionalState) {
        parsed.words.copyInto(words)
        parsed.readCounts.copyInto(readCounts)
        parsed.writeCounts.copyInto(writeCounts)
        resetWrites = parsed.resetWrites
        resetPolls = parsed.resetPolls
        resetCompleted = parsed.resetCompleted
        observed = parsed.observed
        logged = parsed.logged
        parsed.logOffsets.copyInto(logOffsets)
        parsed.logWrites.copyInto(logWrites)
        parsed.logValues.copyInto(logValues)
        parsed.logTicks.copyInto(logTicks)
        parsed.logCores.copyInto(logCores)
        parsed.logPcs.copyInto(logPcs)
        powered = parsed.powered ?: (initiallyPowered || parsed.resetCompleted)
        clocked = parsed.clocked ?: (initiallyPowered || parsed.resetCompleted)
        unclockedAccessCount = parsed.unclockedAccessCount
        lastUnclockedOffset = parsed.lastUnclockedOffset
        lastUnclockedWrite = parsed.lastUnclockedWrite
        warnedUnclocked = false
    }

    internal fun resetState() {
        words.fill(0L)
        readCounts.fill(0L)
        writeCounts.fill(0L)
        resetWrites = 0L
        resetPolls = 0L
        resetCompleted = false
        observed = 0L
        logged = 0
        logOffsets.fill(0)
        logWrites.fill(false)
        logValues.fill(0L)
        logTicks.fill(0L)
        logCores.fill(-1)
        logPcs.fill(-1L)
        powered = initiallyPowered
        clocked = initiallyPowered
        unclockedAccessCount = 0L
        lastUnclockedOffset = null
        lastUnclockedWrite = null
        warnedUnclocked = false
    }

    companion object {
        const val PROFILE = "lcdc-functional-experimental-v1"
        const val POLICY_WARN = "warn"
        const val POLICY_REFUSE = "refuse"
        const val POLICY_ABORT = "abort"
        val VALID_POLICIES = setOf(POLICY_WARN, POLICY_REFUSE, POLICY_ABORT)
        const val LCDC0_POWER_PORT = "soc-vglib-init.lcdc0-power"
        const val LCDC1_POWER_PORT = "soc-vglib-init.lcdc1-power"
        const val LCDC1_LAYERS_PORT = "soc-vglib-init.lcdc1-layers"
        const val SIZE = 0x1000L
        const val WORDS = 0x400
        const val LOG = 1024
        private val LOGGER = Logger.getLogger(LcdcFunctional::class.java.name)
        fun port(instance: Int) = "soc-vglib-init.lcdc$instance-functional"
    }
}
