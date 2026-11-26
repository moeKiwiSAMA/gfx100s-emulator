package com.github.nyabanban.fuji.emulator.components.sdhost

import java.math.BigInteger

internal enum class SdDataDirection { NONE, READ, WRITE }
internal enum class SdResponseKind { NONE, SHORT, LONG }

/** Explicit synthetic identity. No field is inferred from a camera dump. */
internal class SdCardIdentity private constructor(
    val capacityBytes: Long, val highCapacity: Boolean, val readOnly: Boolean,
    val rca: Int, val cidHex: String, val ocrVoltageWindow: Long,
    val supportsHighSpeed: Boolean, internal val readLength: Int,
    internal val capacitySize: Int, internal val capacityMultiplier: Int,
) {
    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "basis" to "SYNTHETIC", "capacityBytes" to capacityBytes, "highCapacity" to highCapacity,
        "readOnly" to readOnly, "rca" to rca, "cidHex" to cidHex,
        "ocrVoltageWindow" to ocrVoltageWindow, "supportsHighSpeed" to supportsHighSpeed,
    )

    fun withMediaProperties(newCapacityBytes: Long, newReadOnly: Boolean): SdCardIdentity {
        if (newCapacityBytes == capacityBytes && newReadOnly == readOnly) return this
        val useHighCapacity = computeHighCapacity(newCapacityBytes, preferHighCapacity = highCapacity)
        val (nextReadLength, nextSize, nextMultiplier) = computeCsdGeometry(newCapacityBytes, useHighCapacity)
        return SdCardIdentity(
            newCapacityBytes, useHighCapacity, newReadOnly, rca, cidHex, ocrVoltageWindow,
            supportsHighSpeed, nextReadLength, nextSize, nextMultiplier,
        )
    }

    companion object {
        private val keys = setOf("basis", "capacityBytes", "highCapacity", "readOnly", "rca", "cidHex", "ocrVoltageWindow", "supportsHighSpeed")

        private fun isLegalCsdV2Capacity(capacity: Long): Boolean {
            if (capacity <= 0L || capacity % (512L * 1024L) != 0L) return false
            val c = capacity / (512L * 1024L) - 1L
            return c in 0x1010L..0xff5fL || c in 0xffffL..0x3ffeffL
        }

        private fun isRepresentableCsdV1Capacity(capacity: Long): Boolean {
            if (capacity <= 0L || capacity % 512L != 0L || capacity > 2L * 1024L * 1024L * 1024L) return false
            for (length in 9..11) for (mult in 0..7) {
                val unit = 1L shl (length + mult + 2)
                val units = capacity / unit
                if (capacity % unit == 0L && units in 1..4096) return true
            }
            return false
        }

        private fun computeHighCapacity(capacity: Long, preferHighCapacity: Boolean): Boolean = when {
            capacity > 2L * 1024L * 1024L * 1024L -> true
            preferHighCapacity && isLegalCsdV2Capacity(capacity) -> true
            isRepresentableCsdV1Capacity(capacity) -> false
            isLegalCsdV2Capacity(capacity) -> true
            else -> preferHighCapacity
        }

        private fun computeCsdGeometry(capacity: Long, hc: Boolean): Triple<Int, Int, Int> {
            require(capacity > 0 && capacity % 512 == 0L)
            var readLength = 9; var size = -1; var multiplier = 0
            if (hc) {
                require(capacity % (512L * 1024) == 0L) { "SDHC/SDXC capacity must be a 512 KiB multiple" }
                val c = capacity / (512L * 1024) - 1
                require(c in 0x1010L..0xff5fL || c in 0xffffL..0x3ffeffL) { "capacity is not a legal CSD v2 SDHC/SDXC geometry" }
                size = c.toInt()
            } else {
                require(capacity <= 2L * 1024 * 1024 * 1024)
                search@ for (length in 9..11) for (mult in 0..7) {
                    val unit = 1L shl (length + mult + 2)
                    val units = capacity / unit
                    if (capacity % unit == 0L && units in 1..4096) {
                        readLength = length; multiplier = mult; size = (units - 1).toInt(); break@search
                    }
                }
                require(size >= 0) { "capacity is not representable by CSD v1" }
            }
            return Triple(readLength, size, multiplier)
        }

        fun syntheticForMedia(slot: Int, capacityBytes: Long, readOnly: Boolean): SdCardIdentity {
            require(slot in 0..1) { "Unsupported SD slot: $slot" }
            val slotDigit = ('1'.code + slot).toByte()
            val prefix = byteArrayOf(
                0xfe.toByte(), 0x54, 0x45, 0x53, 0x54, 0x30, 0x30, slotDigit,
                0x10, 0x01, 0x02, 0x03, 0x04, 0x01, (0xa1 + slot).toByte(),
            )
            val fullCid = prefix + crcEnd(prefix).toByte()
            val cidHex = fullCid.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val hc = computeHighCapacity(capacityBytes, preferHighCapacity = capacityBytes > 2L * 1024L * 1024L * 1024L)
            val (readLength, size, multiplier) = computeCsdGeometry(capacityBytes, hc)
            return SdCardIdentity(
                capacityBytes, hc, readOnly, slot + 1, cidHex, 0x00ff8000L,
                true, readLength, size, multiplier,
            )
        }

        fun parse(input: Map<String, Any?>): SdCardIdentity {
            require(input.keys == keys && input["basis"] == "SYNTHETIC") { "explicit SYNTHETIC card identity required" }
            fun integer(key: String): Long = when (val v = input[key]) {
                is Byte -> v.toLong(); is Short -> v.toLong(); is Int -> v.toLong(); is Long -> v
                else -> error("$key must be an integer")
            }
            fun flag(key: String) = input[key] as? Boolean ?: error("$key must be Boolean")
            val capacity = integer("capacityBytes")
            val hc = flag("highCapacity")
            val rca = integer("rca"); require(rca in 1..65535)
            val ocr = integer("ocrVoltageWindow")
            require(ocr != 0L && ocr and 0xff8000L == ocr) { "OCR requires only supported 2.7–3.6 V window bits" }
            val cid = input["cidHex"] as? String ?: error("cidHex required")
            require(cid.matches(Regex("[0-9a-fA-F]{32}")))
            val bytes = hexBytes(cid)
            require(bytes[15].toInt() and 255 == crcEnd(bytes.copyOf(15))) { "CID CRC7/end bit mismatch" }
            val (readLength, size, multiplier) = computeCsdGeometry(capacity, hc)
            return SdCardIdentity(capacity, hc, flag("readOnly"), rca.toInt(), cid.lowercase(), ocr,
                flag("supportsHighSpeed"), readLength, size, multiplier)
        }
    }
}

internal class SdCardCommand(
    response: LongArray, val responseKind: SdResponseKind, val direction: SdDataDirection,
    val dataBytes: Long, val mediaOffset: Long?, payload: ByteArray?, val busy: Boolean,
    val accept: () -> Unit, val complete: () -> Unit,
    internal val completionStateName: String? = null,
    completionFunctions: IntArray? = null,
) {
    internal val writeProtectViolation: Boolean
        get() = responseKind == SdResponseKind.SHORT && (responseValue[0] and R1_WP_VIOLATION) != 0L

    private val responseValue = response.copyOf()
    private val payloadValue = payload?.copyOf()
    private val completionFunctionsValue = completionFunctions?.copyOf()
    val response: LongArray get() = responseValue.copyOf()
    val payload: ByteArray? get() = payloadValue?.copyOf()
    internal val completionFunctions: IntArray? get() = completionFunctionsValue?.copyOf()
}

private const val R1_WP_VIOLATION = 1L shl 26

/** Bounded SD 2.0 memory-card model. Timing/current and identity are synthetic hypotheses.
 * Physical 9.10 §§4.2, 4.3, 4.10, 5; SDHCI 4.20 §2.2.7 defines R2 packing.
 * Unsupported commands fail closed; advertising the standard CSD is not full SD conformance.
 */
internal class SdCardProtocol(
    val identity: SdCardIdentity,
    private val liveReadOnly: () -> Boolean = { identity.readOnly },
) {
    private fun effectiveReadOnly(): Boolean = liveReadOnly()

    fun resetToIdle() {
        state = State.IDLE
        app = false
        interfaceAccepted = false
        busWidth = 1
        functions = IntArray(6)
        pending = null
    }
    private enum class State { IDLE, READY, IDENT, STANDBY, TRANSFER, SENDING, RECEIVE, PROGRAM, DISCONNECT }
    internal data class ParsedSnapshot(
        val stateName: String,
        val appCommandPending: Boolean,
        val interfaceConditionAccepted: Boolean,
        val busWidth: Int,
        val selectedFunctions: IntArray,
        val pendingActive: Boolean,
        val acceptedCommands: Long,
        val completedCommands: Long,
        val revision: Long,
    )
    private var state = State.IDLE
    private var app = false
    private var interfaceAccepted = false
    private var busWidth = 1
    private var functions = IntArray(6)
    private var revision = 0L
    private var pending: Any? = null
    private var acceptedCount = 0L
    private var completedCount = 0L

    fun status(): Long = (state.ordinal.toLong() shl 9) or
        (if (state == State.TRANSFER || state == State.STANDBY) 0x100L else 0L) or (if (app) 0x20L else 0L)

    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "identity" to effectiveIdentity().snapshot(), "state" to state.name, "stateCode" to state.ordinal,
        "appCommandPending" to app, "interfaceConditionAccepted" to interfaceAccepted,
        "busWidth" to busWidth, "selectedFunctions" to functions.toList(),
        "pendingDataOrBusy" to (pending != null), "acceptedCommands" to acceptedCount,
        "completedCommands" to completedCount, "revision" to revision,
    )

    fun prepare(index: Int, argument: Long, blockSize: Int, blocks: Int, autoStop: Boolean): SdCardCommand {
        require(index in 0..63 && argument in 0..0xffffffffL)
        require(blockSize >= 0 && blocks >= 0)
        val expectedRevision = revision
        // Undefined ACMD indices fall back to ordinary commands (§4.3.9.1); defined but
        // unimplemented ACMDs must never accidentally execute an ordinary command.
        val acmd = app && index in setOf(6, 13, 22, 23, 41, 42, 51)
        var response = longArrayOf(status() and 0x20L.inv(), 0, 0, 0)
        var kind = SdResponseKind.SHORT
        var direction = SdDataDirection.NONE
        var payload: ByteArray? = null
        var offset: Long? = null
        var bytes = 0L
        var busy = false
        var onAccept: () -> Unit = {}
        var onComplete: () -> Unit = {}
        var hasPending = false
        var rejectedWrite = false
        var completionStateName: String? = null
        var completionFunctions: IntArray? = null
        fun inState(vararg allowed: State) = require(state in allowed) { "${if (acmd) "ACMD" else "CMD"}$index not supported in $state" }
        fun zero() = require(argument == 0L)
        fun addressed() = require(argument == identity.rca.toLong() shl 16) { "RCA/address bits mismatch" }
        fun small(data: ByteArray, completed: () -> Unit = {}) {
            require(blockSize == data.size && blocks == 1 && !autoStop) { "small register transfer geometry mismatch" }
            direction = SdDataDirection.READ; bytes = data.size.toLong(); payload = data; hasPending = true
            completionStateName = State.TRANSFER.name
            onAccept = { state = State.SENDING }
            onComplete = { completed(); state = State.TRANSFER }
        }
        if (acmd) {
            response[0] = response[0] or 0x20
            when (index) {
                41 -> {
                    inState(State.IDLE)
                    require(argument and 0x51ff8000L.inv() == 0L) { "unsupported ACMD41 capability/voltage bits" }
                    val voltage = argument and 0xff8000L
                    val query = voltage == 0L
                    // Physical9.10 §4.2.3.1: OCR queries ignore the supported high request bits.
                    // XPC does not select SDSC/SDHC power (Table3-6). SDXC starts at C_SIZE
                    // FFFFh (§5.3.3); its XPC power/Speed Class behavior is not modeled.
                    require(query || argument and 0x10000000L == 0L || !identity.highCapacity || identity.capacitySize < 0xffff) {
                        "SDXC XPC power and Speed Class selection is outside this card model"
                    }
                    require(query || voltage and identity.ocrVoltageWindow != 0L) { "incompatible ACMD41 voltage is outside this card model" }
                    val ready = voltage and identity.ocrVoltageWindow != 0L &&
                        (!identity.highCapacity || (argument and 0x40000000 != 0L && interfaceAccepted))
                    // Recognize S18R without accepting a voltage switch: this non-UHS model
                    // keeps S18A=0 (§4.2.4.1), independently of 3.3V High Speed support.
                    response[0] = identity.ocrVoltageWindow or (if (ready) 0x80000000L else 0L) or
                        (if (ready && identity.highCapacity) 0x40000000L else 0L)
                    onAccept = { if (ready) state = State.READY }
                }
                6 -> {
                    inState(State.TRANSFER); require(argument == 0L || argument == 2L)
                    onAccept = { busWidth = if (argument == 0L) 1 else 4 }
                }
                51 -> { inState(State.TRANSFER); zero(); small(scr()) }
                13 -> { inState(State.TRANSFER); zero(); small(sdStatus()) }
                else -> error("unsupported ACMD$index")
            }
        } else when (index) {
            0 -> {
                zero(); kind = SdResponseKind.NONE; response = LongArray(4)
                onAccept = { state = State.IDLE; app = false; interfaceAccepted = false; busWidth = 1; functions = IntArray(6); pending = null }
            }
            8 -> {
                inState(State.IDLE); require(argument and 0xfffff000L == 0L && argument and 0xf00L == 0x100L)
                response[0] = argument; onAccept = { interfaceAccepted = true }
            }
            55 -> {
                inState(State.IDLE, State.STANDBY, State.TRANSFER)
                if (state == State.IDLE) zero() else addressed()
                response[0] = response[0] or 0x20; onAccept = { app = true }
            }
            2 -> { inState(State.READY); zero(); kind = SdResponseKind.LONG; response = packed(hexBytes(identity.cidHex)); onAccept = { state = State.IDENT } }
            3 -> { inState(State.IDENT, State.STANDBY); zero(); response[0] = (identity.rca.toLong() shl 16) or (response[0] and 0x1fff); onAccept = { state = State.STANDBY } }
            9 -> { inState(State.STANDBY); addressed(); kind = SdResponseKind.LONG; response = packed(csd()) }
            7 -> {
                inState(State.STANDBY, State.TRANSFER)
                if (argument == 0L) { kind = SdResponseKind.NONE; response = LongArray(4); onAccept = { state = State.STANDBY } }
                // Table4-35: selection enters TRAN; host R1b busy is not a PRG state.
                else { inState(State.STANDBY); addressed(); busy = true; hasPending = true; onAccept = { state = State.TRANSFER } }
            }
            13 -> { inState(State.STANDBY, State.TRANSFER, State.SENDING, State.RECEIVE, State.PROGRAM); addressed() }
            16 -> { inState(State.TRANSFER); require(argument == 512L) { "only 512-byte media blocks supported" } }
            6 -> {
                inState(State.TRANSFER); require(argument and 0x7f000000L == 0L)
                val requested = IntArray(6) { ((argument ushr (it * 4)) and 15).toInt() }
                val valid = requested.indices.all { supported(it, requested[it]) }
                val selected = IntArray(6) { if (!supported(it, requested[it])) 15 else if (requested[it] == 15) functions[it] else requested[it] }
                val switch = argument and 0x80000000L != 0L
                if (switch && valid) completionFunctions = selected.copyOf()
                small(switchStatus(selected, valid)) {
                    if (switch && valid) functions = selected.copyOf()
                }
            }
            17, 18, 24, 25 -> {
                inState(State.TRANSFER)
                require(blockSize == 512 && blocks > 0)
                val multi = index == 18 || index == 25
                require(multi || (blocks == 1 && !autoStop))
                val write = index == 24 || index == 25
                val mediaAddress = if (identity.highCapacity) Math.multiplyExact(argument, 512L) else argument
                require(mediaAddress % 512 == 0L) { "unaligned SDSC byte address" }
                val length = Math.multiplyExact(blocks.toLong(), 512L)
                require(mediaAddress <= identity.capacityBytes && length <= identity.capacityBytes - mediaAddress) { "media range exceeds CSD capacity" }
                if (write && effectiveReadOnly()) {
                    // The card stays in TRAN and returns the write-protect error in R1.
                    rejectedWrite = true
                    response[0] = response[0] or R1_WP_VIOLATION
                } else {
                    offset = mediaAddress
                    bytes = length
                    direction = if (write) SdDataDirection.WRITE else SdDataDirection.READ
                    busy = write; hasPending = true
                    if (!multi || autoStop) completionStateName = State.TRANSFER.name
                    onAccept = { state = if (write) State.RECEIVE else State.SENDING }
                    onComplete = { if (!multi || autoStop) state = State.TRANSFER }
                }
            }
            12 -> {
                // Physical §4.20.6/Table4-77: CMD12 in TRAN is illegal for <=2TB cards;
                // the SDUC exception does not apply to this CSD v1/v2 model.
                zero(); inState(State.SENDING, State.RECEIVE)
                val nextState = if (state == State.RECEIVE) State.PROGRAM else State.TRANSFER
                busy = true; hasPending = true; onAccept = { state = nextState }; onComplete = { state = State.TRANSFER }
                completionStateName = State.TRANSFER.name
            }
            else -> error("unsupported CMD$index")
        }
        if (direction == SdDataDirection.NONE && !rejectedWrite) require(!autoStop) { "Auto CMD12 requires a multi-block data command" }
        val token = Any()
        var accepted = false; var completed = false
        val accept = {
            check(!accepted && revision == expectedRevision) { "stale or already accepted command" }
            accepted = true; acceptedCount++; revision++
            app = false
            if (hasPending) pending = token
            onAccept()
        }
        val complete = {
            check(accepted && !completed) { "command must be accepted once before completion" }
            if (hasPending) check(pending === token) { "data/busy command was superseded or cancelled" }
            completed = true; completedCount++; revision++
            onComplete()
            if (hasPending) pending = null
        }
        return SdCardCommand(
            response, kind, direction, bytes, offset, payload, busy,
            accept, complete, completionStateName, completionFunctions,
        )
    }

    private fun effectiveIdentity(): SdCardIdentity {
        val ro = effectiveReadOnly()
        return if (ro == identity.readOnly) identity else identity.withMediaProperties(identity.capacityBytes, ro)
    }

    internal fun saveStateSnapshot(): Map<String, Any?> = linkedMapOf(
        "identity" to effectiveIdentity().snapshot(),
        "state" to state.name,
        "appCommandPending" to app,
        "interfaceConditionAccepted" to interfaceAccepted,
        "busWidth" to busWidth,
        "selectedFunctions" to functions.toList(),
        "pendingDataOrBusy" to (pending != null),
        "acceptedCommands" to acceptedCount,
        "completedCommands" to completedCount,
        "revision" to revision,
    )

    internal fun parseStateSnapshot(raw: Any?): ParsedSnapshot {
        val cardMap = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD card protocol snapshot")
        val expectedKeys = setOf(
            "identity", "state", "appCommandPending", "interfaceConditionAccepted", "busWidth",
            "selectedFunctions", "pendingDataOrBusy", "acceptedCommands", "completedCommands", "revision",
        )
        require(cardMap.keys == expectedKeys) { "Unexpected SD card protocol snapshot keys" }
        val rawIdentity = cardMap["identity"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD card identity in snapshot")
        require(rawIdentity.keys.all { key -> key is String }) { "SD card identity keys must be String" }
        val parsedIdentity = SdCardIdentity.parse(rawIdentity.entries.associate { entry -> entry.key as String to entry.value })
        require(parsedIdentity.snapshot() == effectiveIdentity().snapshot()) { "SD card identity differs from snapshot" }
        val stateName = cardMap["state"] as? String ?: throw IllegalArgumentException("Invalid SD card state")
        val targetState = State.entries.firstOrNull { candidate -> candidate.name == stateName }
            ?: throw IllegalArgumentException("Unknown SD card state $stateName")
        val nextApp = cardMap["appCommandPending"] as? Boolean ?: throw IllegalArgumentException("Invalid appCommandPending")
        if (nextApp) require(targetState in setOf(State.IDLE, State.STANDBY, State.TRANSFER)) { "appCommandPending invalid in $targetState" }
        val nextInterfaceAccepted = cardMap["interfaceConditionAccepted"] as? Boolean
            ?: throw IllegalArgumentException("Invalid interfaceConditionAccepted")
        if (identity.highCapacity && targetState != State.IDLE) {
            require(nextInterfaceAccepted) { "High-capacity card past IDLE requires interfaceConditionAccepted" }
        }
        val nextBusWidth = SdHostSnapshotSupport.parseIntegralInt(cardMap["busWidth"], "card.busWidth")
        require(nextBusWidth == 1 || nextBusWidth == 4) { "Invalid SD card busWidth" }
        if (targetState == State.IDLE) require(nextBusWidth == 1) { "IDLE SD card must use 1-bit busWidth" }
        val rawFunctions = cardMap["selectedFunctions"] as? List<*>
            ?: throw IllegalArgumentException("Invalid selectedFunctions in SD card snapshot")
        require(rawFunctions.size == 6) { "SD card requires 6 function groups" }
        val nextFunctions = IntArray(6) { groupIndex ->
            val fnValue = SdHostSnapshotSupport.parseIntegralInt(rawFunctions[groupIndex], "card.selectedFunctions.$groupIndex")
            require(fnValue != 15 && supported(groupIndex, fnValue)) { "Unsupported SD card function $fnValue in group $groupIndex" }
            if (targetState == State.IDLE) require(fnValue == 0) { "IDLE SD card must have default functions" }
            fnValue
        }
        val nextPending = cardMap["pendingDataOrBusy"] as? Boolean ?: throw IllegalArgumentException("Invalid pendingDataOrBusy")
        if (targetState in setOf(State.SENDING, State.RECEIVE, State.PROGRAM)) {
            require(nextPending) { "Active SD card transfer/program state requires pendingDataOrBusy" }
        }
        if (targetState == State.IDLE) require(!nextPending) { "IDLE SD card cannot have pendingDataOrBusy" }
        val nextAccepted = SdHostSnapshotSupport.parseIntegralLong(cardMap["acceptedCommands"], "card.acceptedCommands")
        val nextCompleted = SdHostSnapshotSupport.parseIntegralLong(cardMap["completedCommands"], "card.completedCommands")
        val nextRevision = SdHostSnapshotSupport.parseIntegralLong(cardMap["revision"], "card.revision")
        require(nextAccepted >= 0L && nextCompleted in 0L..nextAccepted) { "Invalid SD card command counts" }
        val expectedRevision = try { Math.addExact(nextAccepted, nextCompleted) } catch (_: ArithmeticException) {
            throw IllegalArgumentException("SD card revision overflow")
        }
        require(expectedRevision == nextRevision) { "SD card revision disagrees with acceptedCommands + completedCommands" }
        return ParsedSnapshot(
            stateName, nextApp, nextInterfaceAccepted, nextBusWidth,
            nextFunctions, nextPending, nextAccepted, nextCompleted, nextRevision,
        )
    }

    internal fun validateRestoredCommandCompletion(completionStateName: String?, completionFunctions: IntArray?) {
        if (completionStateName != null) {
            require(completionStateName == State.TRANSFER.name) { "Unsupported SD command completion state $completionStateName" }
        }
        if (completionFunctions != null) {
            require(completionFunctions.size == 6) { "SD command completion functions must have size 6" }
            for (groupIndex in 0..5) {
                val fnValue = completionFunctions[groupIndex]
                require(fnValue != 15 && supported(groupIndex, fnValue)) { "Invalid SD command completion function" }
            }
        }
    }

    internal fun applyStateSnapshot(parsed: ParsedSnapshot) {
        state = State.valueOf(parsed.stateName)
        app = parsed.appCommandPending
        interfaceAccepted = parsed.interfaceConditionAccepted
        busWidth = parsed.busWidth
        functions = parsed.selectedFunctions.copyOf()
        pending = if (parsed.pendingActive) Any() else null
        acceptedCount = parsed.acceptedCommands
        completedCount = parsed.completedCommands
        revision = parsed.revision
    }

    internal fun restoreActiveCommand(
        response: LongArray,
        responseKind: SdResponseKind,
        direction: SdDataDirection,
        dataBytes: Long,
        mediaOffset: Long?,
        payload: ByteArray?,
        busy: Boolean,
        completionStateName: String?,
        completionFunctions: IntArray?,
    ): SdCardCommand {
        val hasPending = direction != SdDataDirection.NONE || busy
        val activeToken = if (hasPending) checkNotNull(pending) { "Active SD data/busy command requires card token" } else null
        val targetState = completionStateName?.let(State::valueOf)
        val nextFunctions = completionFunctions?.copyOf()
        var completed = false
        val rejectAccept: () -> Unit = { check(false) { "stale or already accepted command" } }
        val completeAction: () -> Unit = {
            check(!completed) { "command must be accepted once before completion" }
            if (hasPending) check(pending === activeToken) { "data/busy command was superseded or cancelled" }
            completed = true
            completedCount++
            revision++
            if (nextFunctions != null) functions = nextFunctions.copyOf()
            if (targetState != null) state = targetState
            if (hasPending) pending = null
        }
        return SdCardCommand(
            response, responseKind, direction, dataBytes, mediaOffset, payload, busy,
            rejectAccept, completeAction, completionStateName, nextFunctions,
        )
    }

    private fun supported(group: Int, function: Int) = function == 0 || function == 15 || (group == 0 && function == 1 && identity.supportsHighSpeed)
    private fun scr() = ByteArray(8).also { bits(it, 59, 56, 2); bits(it, 51, 48, 5) }
    private fun sdStatus() = ByteArray(64).also { bits(it, 511, 510, if (busWidth == 4) 2 else 0) }
    private fun switchStatus(selected: IntArray, valid: Boolean) = ByteArray(64).also { data ->
        // HYP: 100 mA maximum card consumption, no function-busy interval after data.
        bits(data, 511, 496, if (valid) 100 else 0)
        for (group in 0..5) {
            bits(data, 415 + group * 16, 400 + group * 16, if (group == 0 && identity.supportsHighSpeed) 0x8003 else 0x8001)
            bits(data, 379 + group * 4, 376 + group * 4, selected[group])
        }
        bits(data, 375, 368, 1)
    }
    private fun csd() = ByteArray(16).also { data ->
        bits(data, 127, 126, if (identity.highCapacity) 1 else 0)
        bits(data, 119, 112, 0x0e); bits(data, 103, 96, if (functions[0] == 1) 0x5a else 0x32)
        bits(data, 95, 84, 0x535); bits(data, 83, 80, identity.readLength)
        // CSD v1 always allows partial reads; WRITE_BL_LEN must equal READ_BL_LEN.
        bits(data, 79, 79, if (identity.highCapacity) 0 else 1)
        if (identity.highCapacity) bits(data, 69, 48, identity.capacitySize)
        else { bits(data, 73, 62, identity.capacitySize); bits(data, 49, 47, identity.capacityMultiplier) }
        bits(data, 46, 46, 1); bits(data, 45, 39, 127); bits(data, 28, 26, 2); bits(data, 25, 22, identity.readLength)
        bits(data, 13, 13, if (effectiveReadOnly()) 1 else 0)
        data[15] = crcEnd(data.copyOf(15)).toByte()
    }
}

private fun hexBytes(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun crcEnd(bytes: ByteArray): Int {
    var crc = 0
    for (byte in bytes) for (bit in 7 downTo 0) {
        val feedback = ((byte.toInt() ushr bit) and 1) xor ((crc ushr 6) and 1)
        crc = (crc shl 1) and 0x7f
        if (feedback != 0) crc = crc xor 9
    }
    return (crc shl 1) or 1
}
private fun bits(bytes: ByteArray, high: Int, low: Int, value: Int) {
    for (bit in low..high) if ((value ushr (bit - low)) and 1 != 0) {
        val index = bytes.lastIndex - bit / 8
        bytes[index] = (bytes[index].toInt() or (1 shl (bit % 8))).toByte()
    }
}
private fun packed(bytes: ByteArray): LongArray {
    val value = BigInteger(1, bytes).shiftRight(8)
    val mask = BigInteger("ffffffff", 16)
    return LongArray(4) { value.shiftRight(it * 32).and(mask).toLong() }
}
