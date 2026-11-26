package com.github.nyabanban.fuji.emulator.components.mount

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.math.ln
import kotlin.math.roundToInt

class MountFactory : ComponentFactory {
    override val descriptor = ComponentDescriptor("mount", "0.0.1", settings = listOf(
        Setting("attached", ValueKind.BOOLEAN, true), Setting("lensPort", ValueKind.TEXT, "lens", live = false),
        Setting("protocolProfile", ValueKind.TEXT, "", choices = listOf("", LENSCOM_FRAME32_PROFILE), live = false),
        Setting("eepromFile", ValueKind.TEXT, "", live = false),
    ))
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = object : ScalarComponent("mount", context, config), MountPort, ContactBus, StatefulComponent {
        private val lenscomPipelined = (config["protocolProfile"] as? String).orEmpty() == LENSCOM_FRAME32_PROFILE
        private val eepromFile = (config["eepromFile"] as? String).orEmpty()
        private val customEepromImage: ByteArray? = if (eepromFile.isNotBlank()) loadSafeEepromBin(context.directory, eepromFile) else null
        private fun resolveOpticalEepromImage(
            lens: LensParameters,
            elec: ElectronicLensPort?,
            modular: ModularLensPort?,
        ): ByteArray {
            if (customEepromImage != null) return customEepromImage
            val moduleEeprom = modular?.customEepromBytes
            if (moduleEeprom != null) {
                require(moduleEeprom.size == LENS_EEPROM_BYTES) {
                    "Modular lens customEepromBytes must be exactly $LENS_EEPROM_BYTES bytes (128 KiB), got ${moduleEeprom.size}"
                }
                return moduleEeprom
            }
            return buildLensOpticalEeprom128K(lens, elec, modular)
        }
        private val spiBridge = Frame32Bridge(lenscomPipelined)
        private var lastLensPowerSeq = -1L
        private var lastLensPowerOn = true
        private var lastLensDetHigh: Boolean? = null
        private var detectedLensId: String? = null
        private var detectedAdapterId: String? = null
        private var detectSwitch: LensMountDetectPort? = null
        private fun loadSafeEepromBin(rootDir: Path, relative: String): ByteArray {
            val rel = Path.of(relative)
            require(!rel.isAbsolute && rel == rel.normalize() && rel.none { it.toString() in setOf(".", "..") }) {
                "Invalid mount eepromFile relative path: $relative"
            }
            val base = rootDir.toAbsolutePath().normalize()
            val resolved = base.resolve(rel).normalize()
            require(resolved.startsWith(base) && resolved != base) { "mount eepromFile escapes component directory: $relative" }
            var cur: Path? = resolved
            while (cur != null && cur != base) {
                require(!Files.isSymbolicLink(cur)) { "mount eepromFile cannot traverse symlink: $cur" }
                cur = cur.parent
            }
            require(Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(resolved) && Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                "mount eepromFile must be an existing regular non-symlink file: $relative"
            }
            val size = Files.size(resolved)
            require(size == LENS_EEPROM_BYTES.toLong()) {
                "mount eepromFile must be exactly $LENS_EEPROM_BYTES bytes (128 KiB), got $size"
            }
            return Files.readAllBytes(resolved)
        }
        private fun syncLensDetectGpio(force: Boolean = false) {
            val gpio = context.ports.find("soc-gpio.inputs", GpioInputPort::class.java) ?: return
            val timed = gpio as? LensMountDetectPort
            detectSwitch = timed
            // DAT S4 0x018127C4 / 0x0122AA90 (BUG-V3-3-1): KEY_LENS_DET = GPIO(0, 11), Active-LOW.
            // LOW (false) = electronic GF lens attached; HIGH (true) = detached or dumb mechanical adapter.
            val attached = values["attached"] == true
            val port = if (attached) {
                context.ports.find(values.getValue("lensPort") as String, LensPort::class.java)
            } else null
            val hasContacts = if (port == null) {
                false
            } else {
                val modular = port as? ModularLensPort
                if (modular != null && (modular.communicationMode == LensCommunicationMode.MANUAL || modular.controlContract is LensControlContract.Manual)) {
                    false
                } else {
                    (port as? ElectronicLensPort)?.electronicContacts ?: true
                }
            }
            val high = !hasContacts
            val module = port as? ModularLensPort
            val lensId = module?.activeLensId ?: port?.parameters()?.model
            val adapterId = module?.activeAdapterId
            val replacement = !force && !high && lastLensDetHigh == false &&
                (lensId != detectedLensId || adapterId != detectedAdapterId)
            if (replacement) {
                if (timed != null) timed.breakForLensExchange()
                else gpio.setInputLevel(LENS_DET_GPIO_BANK, LENS_DET_GPIO_PIN, true)
                spiBridge.resetForPowerCycle()
            }
            if (force || replacement || lastLensDetHigh != high) {
                if (high || timed?.replacingLens != true) gpio.setInputLevel(LENS_DET_GPIO_BANK, LENS_DET_GPIO_PIN, high)
                if (!force && lastLensDetHigh != null && lastLensDetHigh != high) {
                    spiBridge.resetForPowerCycle()
                }
                lastLensDetHigh = high
            }
            detectedLensId = lensId
            detectedAdapterId = adapterId
        }
        private fun syncLensPowerRail() {
            if (!lenscomPipelined) return
            val gpio = context.ports.find("soc-gpio.outputs", GpioOutputPort::class.java) ?: return
            val seq = gpio.outputSequence(LENS_PWR_GPIO_BANK, LENS_PWR_GPIO_PIN)
            val on = gpio.outputLevel(LENS_PWR_GPIO_BANK, LENS_PWR_GPIO_PIN)
            if (lastLensPowerSeq < 0L) {
                lastLensPowerSeq = seq
                lastLensPowerOn = on
                return
            }
            if (seq != lastLensPowerSeq) {
                // DAT section4 0x01227f20 / 0x0122ac64: GPIO(1,13) controls GF mount lens power rail.
                // Powering off the lens (e.g. STILL -> PLAY) resets the lens MCU Frame32/Bulk state so
                // re-powering on (PLAY -> STILL UI_EXE_SYS_LENS_POWER(1)) starts cleanly at slaveSeq=0.
                if (!on || !lastLensPowerOn || seq - lastLensPowerSeq >= 2L) {
                    spiBridge.resetForPowerCycle()
                }
                lastLensPowerSeq = seq
                lastLensPowerOn = on
            }
        }
        init {
            context.ports.register(id, "mount", MountPort::class.java, this)
            context.ports.register(id, "mount.contacts", ContactBus::class.java, this)
            context.ports.register(id, "mount.spi", SpiPeer::class.java, object : SpiPeer {
                override fun exchange(bits: Int, tx: Int, tick: Long): Int {
                    if (!hasElectronicContacts()) return 0
                    syncLensPowerRail()
                    return spiBridge.exchange(bits, tx)
                }
            })
        }
        private fun attachedLensPort(): LensPort? = if (values["attached"] == true) {
            context.ports.find(values.getValue("lensPort") as String, LensPort::class.java)
                ?: error("attached lens port missing")
        } else null
        private fun attachedModularPort(): ModularLensPort? = attachedLensPort() as? ModularLensPort
        private fun hasElectronicContacts(): Boolean {
            if (detectSwitch?.replacingLens == true) return false
            val port = attachedLensPort() ?: return false
            val modular = port as? ModularLensPort
            if (modular != null && (modular.communicationMode == LensCommunicationMode.MANUAL || modular.controlContract is LensControlContract.Manual)) {
                return false
            }
            return (port as? ElectronicLensPort)?.electronicContacts ?: true
        }
        override fun start() {
            syncLensDetectGpio(force = true)
        }
        override fun applySettings(settings: Map<String, Any>) {
            super.applySettings(settings)
            syncLensDetectGpio()
        }
        override fun syncHardwareDetect() {
            syncLensDetectGpio()
        }
        override fun restoreHardwareDetect() {
            syncLensDetectGpio(force = true)
        }
        override fun attachedLens(): LensParameters? = attachedLensPort()?.parameters()
        override fun exchange(request: java.nio.ByteBuffer, response: java.nio.ByteBuffer): Int {
            val port = attachedLensPort() ?: return 0
            if (!hasElectronicContacts()) return 0
            val lens = port.parameters()
            val elec = port as? ElectronicLensPort
            val modular = port as? ModularLensPort
            require(request.remaining() == 4 && response.remaining() >= 4) {
                "G-mount contact exchange requires 4-byte framed request and >=4-byte response capacity"
            }
            val txWord = ((request.get().toLong() and 0xffL) shl 24) or
                ((request.get().toLong() and 0xffL) shl 16) or
                ((request.get().toLong() and 0xffL) shl 8) or
                (request.get().toLong() and 0xffL)
            val reply = if (lenscomPipelined) {
                spiBridge.stepContactWord(txWord, lens, elec, modular)
            } else {
                replyForLensFrame(txWord, lens)
            }
            response.put(((reply ushr 24) and 0xffL).toByte())
            response.put(((reply ushr 16) and 0xffL).toByte())
            response.put(((reply ushr 8) and 0xffL).toByte())
            response.put((reply and 0xffL).toByte())
            return 4
        }
        override fun state(): Map<String, Any?> {
            val port = attachedLensPort()
            val modular = port as? ModularLensPort
            val contacts = hasElectronicContacts()
            val modeName = when {
                port == null -> "DETACHED"
                modular != null -> modular.communicationMode.name
                contacts -> LensCommunicationMode.GENERIC_SIMULATED.name
                else -> LensCommunicationMode.MANUAL.name
            }
            val baseState = super.state()
                .let { if (lenscomPipelined) it else it - "protocolProfile" }
                .let { if (eepromFile.isNotEmpty()) it else it - "eepromFile" }
            return baseState + mapOf(
                "lens" to attachedLens(),
                "electronicContacts" to contacts,
                "communicationMode" to modeName,
                "contactProtocol" to when {
                    port != null && !contacts -> "mechanical-no-contacts"
                    lenscomPipelined -> LENSCOM_FRAME32_PROFILE
                    else -> "g-mount-frame32-v1"
                },
                "spiBridge" to spiBridge.snapshot(),
            ) + (if (modular != null) {
                mapOf(
                    "activeLensId" to modular.activeLensId,
                    "activeAdapterId" to (modular.activeAdapterId ?: ""),
                )
            } else {
                emptyMap()
            })
        }
        override fun saveSnapshot(): Map<String, Any?> {
            val scalars = saveScalarValues()
                .let { if (lenscomPipelined) it else it - "protocolProfile" }
                .let { if (eepromFile.isNotEmpty()) it else it - "eepromFile" }
            return linkedMapOf<String, Any?>(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalarValues" to scalars,
            ) + (if (spiBridge.isInitial()) emptyMap() else mapOf("spiBridge" to spiBridge.snapshot()))
        }
        override fun restoreSnapshot(data: Map<String, Any?>) {
            require(exactInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            require(data.keys - "spiBridge" == BASE_SNAPSHOT_KEYS) { "Unexpected key in snapshot for $id" }
            val nextBridge = data["spiBridge"]?.let(::parseBridge)
            val rawScalars = data["scalarValues"] as? Map<*, *> ?: throw IllegalArgumentException("Missing scalarValues for $id")
            val savedProfile = rawScalars["protocolProfile"] as? String ?: ""
            require(savedProfile == "" || savedProfile == LENSCOM_FRAME32_PROFILE) {
                "Unsupported mount protocolProfile in snapshot: $savedProfile"
            }
            val currentProfile = if (lenscomPipelined) LENSCOM_FRAME32_PROFILE else ""
            val compatibleKeys = mutableSetOf<String>()
            if (savedProfile != currentProfile) {
                require(savedProfile.isEmpty() && lenscomPipelined && (nextBridge == null || nextBridge.frameCount == 0L)) {
                    "Incompatible mount protocolProfile transition: $savedProfile -> $currentProfile"
                }
                compatibleKeys += "protocolProfile"
            }
            val savedEepromFile = rawScalars["eepromFile"] as? String ?: ""
            if (savedEepromFile != eepromFile) {
                require(savedEepromFile.isEmpty() && eepromFile.isNotEmpty() && (nextBridge == null || nextBridge.frameCount == 0L)) {
                    "Incompatible mount eepromFile transition: $savedEepromFile -> $eepromFile"
                }
                compatibleKeys += "eepromFile"
            }
            val normalizedScalars = linkedMapOf<String, Any?>()
            rawScalars.forEach { (k, v) -> normalizedScalars[k.toString()] = v }
            restoreScalarValues(normalizedScalars, descriptor, compatibleKeys)
            if (compatibleKeys.isNotEmpty()) {
                values = values + ("protocolProfile" to currentProfile) + ("eepromFile" to eepromFile)
            }
            spiBridge.restore(nextBridge)
            syncLensDetectGpio(force = true)
        }

        private inner class Frame32Bridge(private val pipelined: Boolean) {
            // DAT 0x01221D7C..0x01222028 & 0x0122250C: cold-boot first frame is cmd 0xFF (S03) which expects rx=0
            // (preloading 0xC33CA55A triggers S03 sync-error 9). S12 sync probe 0xA55A3CC3 still replies 0xC33CA55A.
            private val initialRxShift = 0L
            private var txAcc = 0L
            private var txBytes = 0
            private var rxShift = initialRxShift
            private var frameCount = 0L
            private var pendingCmdId = -1L
            private var slaveSeq = 0L

            // Bulk-mode state (S17 -> S14 -> S15/S16, verified against section4.bin 0x01220ec8..0x012225e4)
            private var bulkBlockLen = DEFAULT_BULK_BLOCK_LEN
            private var pendingBulkCmd = -1L
            private var pendingCmdPayload = 0L
            private var bulkMode = BULK_MODE_NONE
            private var bulkPass = 0
            private var bulkPos = 0
            private var bulkRxFirstByte = 0
            private var bulkTxBuf: ByteArray = ByteArray(0)
            private var bulkEepromImage: ByteArray = ByteArray(0)
            private var lastFocusCtrl = 0
            private var lastFocusSpeed = 0
            private var lastReportedRingMode: String? = null
            private var lastReportedRingStep: Int = -1
            private var lastReportedZoomStep: Int = -1
            private var lastReportedFocusEnc: Int = -1
            private var ringDirty: Boolean = false
            private var zoomDirty: Boolean = false
            private var focusDirty: Boolean = false

            fun isInitial() = txAcc == 0L && txBytes == 0 && rxShift == initialRxShift && frameCount == 0L &&
                pendingCmdId < 0L && slaveSeq == 0L &&
                bulkMode == BULK_MODE_NONE && pendingBulkCmd < 0L && bulkBlockLen == DEFAULT_BULK_BLOCK_LEN

            fun resetForPowerCycle() {
                txAcc = 0L
                txBytes = 0
                rxShift = initialRxShift
                pendingCmdId = -1L
                slaveSeq = 0L
                bulkBlockLen = DEFAULT_BULK_BLOCK_LEN
                pendingBulkCmd = -1L
                pendingCmdPayload = 0L
                bulkMode = BULK_MODE_NONE
                bulkPass = 0
                bulkPos = 0
                bulkRxFirstByte = 0
                bulkTxBuf = ByteArray(0)
                bulkEepromImage = ByteArray(0)
                lastFocusCtrl = 0
                lastFocusSpeed = 0
                lastReportedRingMode = null
                lastReportedRingStep = -1
                lastReportedZoomStep = -1
                lastReportedFocusEnc = -1
                ringDirty = false
                zoomDirty = false
                focusDirty = false
            }

            fun stepContactWord(
                txWord: Long,
                lens: LensParameters,
                elec: ElectronicLensPort?,
                modular: ModularLensPort? = null,
            ): Long {
                val clockedOut = rxShift
                val sentData = isDataFrame(clockedOut)
                if (txWord == SYNC_RESET_SENTINEL) {
                    lastReportedRingMode = null
                    lastReportedRingStep = -1
                    lastReportedZoomStep = -1
                    lastReportedFocusEnc = -1
                    ringDirty = false
                    zoomDirty = false
                    focusDirty = false
                }
                val isPhase2 = txWord != 0L &&
                    txWord != SYNC_MASTER_PROBE &&
                    txWord != SYNC_SLAVE_READY &&
                    txWord != SYNC_RESET_SENTINEL &&
                    (txWord and 1L) == 0L &&
                    ((txWord ushr 15) and 1L) == 1L &&
                    ((txWord ushr 1) and 0x1fL) == checksum32(txWord)
                val ringMode = modular?.apertureRingMode ?: "A"
                val ringStep = modular?.apertureRingStepIndex ?: computeIrisStepIndex(lens, elec, modular)
                val zoomStep = computeZoomStepIndex(lens, elec, modular)
                val focusEnc = modular?.focusEncoderPosition
                    ?: (lens.focusDistanceM * 100.0).roundToInt().coerceIn(0, 0x7fff)
                if (isPhase2 && pendingCmdId == 0x020L) {
                    if (lastReportedRingMode == null) {
                        if (ringMode == "C") {
                            lastReportedRingMode = "C"
                            ringDirty = false
                        } else {
                            // Pulse bit 4 on first cmd 0x020 poll so body reads initial A/MANUAL ring via cmd 0x030.
                            ringDirty = true
                        }
                    } else if (ringMode == "C") {
                        lastReportedRingMode = "C"
                        ringDirty = false
                    } else if (ringMode != lastReportedRingMode || (ringMode == "MANUAL" && ringStep != lastReportedRingStep)) {
                        ringDirty = true
                    }
                    if (lastReportedZoomStep >= 0 && zoomStep != lastReportedZoomStep) {
                        zoomDirty = true
                    }
                    if (lastReportedFocusEnc >= 0 && modular?.focusMotorBusy != true && focusEnc != lastReportedFocusEnc) {
                        focusDirty = true
                    }
                }
                val answeredCmd = if (isPhase2) pendingCmdId else -1L
                val step = stepLenscomFrame(
                    tx = txWord,
                    lens = lens,
                    elec = elec,
                    pendingCmdId = pendingCmdId,
                    slaveSeq = slaveSeq,
                    clockedOut = clockedOut,
                    sentData = sentData,
                    pendingBulkCmd = pendingBulkCmd,
                    bulkBlockLen = bulkBlockLen,
                    pendingCmdPayload = pendingCmdPayload,
                    modular = modular,
                    lastFocusCtrl = lastFocusCtrl,
                    lastFocusSpeed = lastFocusSpeed,
                    ringChanged = ringDirty,
                    zoomChanged = zoomDirty,
                    focusChanged = focusDirty,
                )
                when (answeredCmd) {
                    0x030L -> {
                        lastReportedRingMode = ringMode
                        lastReportedRingStep = ringStep
                        ringDirty = false
                    }
                    0x024L -> {
                        lastReportedZoomStep = zoomStep
                        zoomDirty = false
                    }
                    0x021L, 0x023L -> {
                        lastReportedFocusEnc = focusEnc
                        focusDirty = false
                    }
                }
                pendingCmdId = step.nextPendingCmdId
                slaveSeq = step.nextSlaveSeq
                bulkBlockLen = step.nextBulkBlockLen
                pendingBulkCmd = step.nextPendingBulkCmd
                pendingCmdPayload = step.nextPendingCmdPayload
                lastFocusCtrl = step.nextFocusCtrl
                lastFocusSpeed = step.nextFocusSpeed
                if (step.enterBulkCmd >= 0L) {
                    enterBulkMode(step.enterBulkCmd, step.enterBulkArg, lens, elec, modular)
                }
                return step.nextRx
            }

            private fun eepromBlockPacket(mode: Int, pass: Int): ByteArray {
                val total = eepromTotalBlocks(mode)
                if (pass >= total) return buildBulkReadEndPacket(EEPROM_BULK_BLOCK_LEN)
                val offset = eepromBlockOffset(mode, pass)
                val slice = ByteArray(EEPROM_BULK_BLOCK_LEN)
                if (offset < bulkEepromImage.size) {
                    val copyLen = minOf(EEPROM_BULK_BLOCK_LEN, bulkEepromImage.size - offset)
                    bulkEepromImage.copyInto(slice, 0, offset, offset + copyLen)
                }
                return buildBulkReadDataPacket(slice, EEPROM_BULK_BLOCK_LEN, pass)
            }

            private fun enterBulkMode(
                cmdId: Long,
                cmdArg: Int,
                lens: LensParameters,
                elec: ElectronicLensPort?,
                modular: ModularLensPort? = null,
            ) {
                bulkPos = 0
                bulkPass = 0
                bulkRxFirstByte = 0
                bulkEepromImage = ByteArray(0)
                when (cmdId) {
                    CMD_BULK_READ_ID -> {
                        bulkMode = BULK_MODE_READ_ID
                        bulkTxBuf = buildBulkReadDataPacket(buildLensIdentityHeader128(lens, elec, modular), bulkBlockLen, 0)
                    }
                    CMD_BULK_WRITE_BODY -> {
                        bulkMode = BULK_MODE_WRITE
                        bulkTxBuf = buildBulkWriteAckPacket(bulkBlockLen)
                    }
                    CMD_BULK_READ_EEPROM -> {
                        bulkMode = when (cmdArg and 0xf) {
                            0 -> BULK_MODE_READ_EEPROM_0
                            1 -> BULK_MODE_READ_EEPROM_1
                            else -> BULK_MODE_READ_EEPROM_6
                        }
                        bulkEepromImage = resolveOpticalEepromImage(lens, elec, modular)
                        bulkTxBuf = eepromBlockPacket(bulkMode, 0)
                    }
                    else -> {
                        bulkMode = BULK_MODE_GENERIC
                        bulkTxBuf = buildBulkReadEndPacket(bulkBlockLen)
                    }
                }
            }

            private fun stepBulkByte(tx: Int): Int {
                val txByte = tx and 0xff
                if (bulkPos == 0) {
                    bulkRxFirstByte = txByte
                    // For generic bulk commands, dynamically distinguish write (tx[0]!=0xFF) vs read-end (tx[0]==0xFF)
                    if (bulkMode == BULK_MODE_GENERIC && txByte != 0xff) {
                        bulkTxBuf = buildBulkWriteAckPacket(bulkBlockLen)
                    }
                }
                val outByte = if (bulkPos < bulkTxBuf.size) (bulkTxBuf[bulkPos].toInt() and 0xff) else 0
                bulkPos++
                if (bulkPos >= bulkTxBuf.size) {
                    frameCount++
                    if (bulkMode == BULK_MODE_READ_ID && bulkPass == 0) {
                        // Xfer 1 delivered 128B Lens Identity Header; Xfer 2 sends 0xFFFF END so master exits Bulk Mode
                        bulkPass = 1
                        bulkPos = 0
                        bulkTxBuf = buildBulkReadEndPacket(bulkBlockLen)
                    } else if (isEepromBulkMode(bulkMode) && bulkPass < eepromTotalBlocks(bulkMode)) {
                        // Advance to next 2048B EEPROM block, or emit final 0xFFFF END transfer when bulkPass == totalBlocks
                        bulkPass++
                        bulkPos = 0
                        bulkTxBuf = eepromBlockPacket(bulkMode, bulkPass)
                    } else {
                        // Completed Bulk Write or Bulk Read END -> return cleanly to Frame32 S06
                        bulkMode = BULK_MODE_NONE
                        bulkPass = 0
                        bulkPos = 0
                        bulkTxBuf = ByteArray(0)
                        bulkEepromImage = ByteArray(0)
                        rxShift = 0L
                        txAcc = 0L
                        txBytes = 0
                        pendingCmdId = -1L
                        pendingBulkCmd = -1L
                        pendingCmdPayload = 0L
                    }
                }
                return outByte
            }

            fun exchange(bits: Int, tx: Int): Int {
                require(bits == 8) { "G-mount SPI exchange expects 8-bit transfers, got $bits" }
                if (pipelined && bulkMode != BULK_MODE_NONE) {
                    return stepBulkByte(tx)
                }
                val outByte = ((rxShift ushr ((3 - txBytes) * 8)) and 0xffL).toInt()
                txAcc = (txAcc shl 8) or (tx.toLong() and 0xffL)
                txBytes++
                if (txBytes == 4) {
                    val req = java.nio.ByteBuffer.allocate(4).apply {
                        put(((txAcc ushr 24) and 0xffL).toByte())
                        put(((txAcc ushr 16) and 0xffL).toByte())
                        put(((txAcc ushr 8) and 0xffL).toByte())
                        put((txAcc and 0xffL).toByte())
                        flip()
                    }
                    val resp = java.nio.ByteBuffer.allocate(4)
                    val written = exchange(req, resp)
                    rxShift = if (written == 4) {
                        resp.flip()
                        ((resp.get().toLong() and 0xffL) shl 24) or
                            ((resp.get().toLong() and 0xffL) shl 16) or
                            ((resp.get().toLong() and 0xffL) shl 8) or
                            (resp.get().toLong() and 0xffL)
                    } else 0L
                    txAcc = 0L
                    txBytes = 0
                    frameCount++
                }
                return outByte
            }

            fun snapshot(): Map<String, Any?> = linkedMapOf<String, Any?>(
                "txAcc" to txAcc,
                "txBytes" to txBytes,
                "rxShift" to rxShift,
                "frameCount" to frameCount,
            ) + (if (pendingCmdId >= 0L) mapOf("pendingCmdId" to pendingCmdId) else emptyMap()) +
                (if (slaveSeq != 0L) mapOf("slaveSeq" to slaveSeq) else emptyMap()) +
                (if (bulkBlockLen != DEFAULT_BULK_BLOCK_LEN) mapOf("bulkBlockLen" to bulkBlockLen) else emptyMap()) +
                (if (pendingBulkCmd >= 0L) mapOf("pendingBulkCmd" to pendingBulkCmd) else emptyMap()) +
                (if (bulkMode != BULK_MODE_NONE) mapOf("bulkMode" to bulkMode, "bulkPass" to bulkPass, "bulkPos" to bulkPos) else emptyMap())

            fun restore(parsed: ParsedBridge?) {
                if (parsed == null) {
                    txAcc = 0L
                    txBytes = 0
                    rxShift = initialRxShift
                    frameCount = 0L
                    pendingCmdId = -1L
                    slaveSeq = 0L
                    bulkBlockLen = DEFAULT_BULK_BLOCK_LEN
                    pendingBulkCmd = -1L
                    pendingCmdPayload = 0L
                    bulkMode = BULK_MODE_NONE
                    bulkPass = 0
                    bulkPos = 0
                    bulkRxFirstByte = 0
                    bulkTxBuf = ByteArray(0)
                    bulkEepromImage = ByteArray(0)
                    lastFocusCtrl = 0
                    lastFocusSpeed = 0
                    lastReportedRingMode = null
                    lastReportedRingStep = -1
                    lastReportedZoomStep = -1
                    lastReportedFocusEnc = -1
                    ringDirty = false
                    zoomDirty = false
                    focusDirty = false
                } else {
                    txAcc = parsed.txAcc
                    txBytes = parsed.txBytes
                    rxShift = if (pipelined && parsed.frameCount == 0L && parsed.rxShift == 0L) initialRxShift else parsed.rxShift
                    frameCount = parsed.frameCount
                    pendingCmdId = parsed.pendingCmdId
                    slaveSeq = parsed.slaveSeq
                    bulkBlockLen = parsed.bulkBlockLen
                    pendingBulkCmd = parsed.pendingBulkCmd
                    pendingCmdPayload = 0L
                    bulkMode = parsed.bulkMode
                    bulkPass = parsed.bulkPass
                    bulkPos = parsed.bulkPos
                    bulkRxFirstByte = 0
                    bulkEepromImage = ByteArray(0)
                    lastReportedRingMode = null
                    lastReportedRingStep = -1
                    lastReportedZoomStep = -1
                    lastReportedFocusEnc = -1
                    ringDirty = false
                    zoomDirty = false
                    focusDirty = false
                    bulkTxBuf = if (bulkMode != BULK_MODE_NONE) {
                        val lens = attachedLens() ?: LensParameters("GF", 63.0, 2.8, 2.5, 0.9, 0.0)
                        val elec = attachedLensPort() as? ElectronicLensPort
                        val modular = attachedModularPort()
                        when {
                            bulkMode == BULK_MODE_READ_ID && bulkPass == 0 ->
                                buildBulkReadDataPacket(buildLensIdentityHeader128(lens, elec, modular), bulkBlockLen, 0)
                            bulkMode == BULK_MODE_WRITE ->
                                buildBulkWriteAckPacket(bulkBlockLen)
                            isEepromBulkMode(bulkMode) -> {
                                bulkEepromImage = resolveOpticalEepromImage(lens, elec, modular)
                                eepromBlockPacket(bulkMode, bulkPass)
                            }
                            else ->
                                buildBulkReadEndPacket(bulkBlockLen)
                        }
                    } else {
                        ByteArray(0)
                    }
                }
            }
        }
    }

    internal data class LenscomStepResult(
        val nextRx: Long,
        val nextPendingCmdId: Long,
        val nextSlaveSeq: Long,
        val nextBulkBlockLen: Int,
        val nextPendingBulkCmd: Long,
        val nextPendingCmdPayload: Long = 0L,
        val enterBulkCmd: Long = -1L,
        val enterBulkArg: Int = 0,
        val nextFocusCtrl: Int = 0,
        val nextFocusSpeed: Int = 0,
    )

    private data class ParsedBridge(
        val txAcc: Long,
        val txBytes: Int,
        val rxShift: Long,
        val frameCount: Long,
        val pendingCmdId: Long = -1L,
        val slaveSeq: Long = 0L,
        val bulkBlockLen: Int = DEFAULT_BULK_BLOCK_LEN,
        val pendingBulkCmd: Long = -1L,
        val bulkMode: Int = BULK_MODE_NONE,
        val bulkPass: Int = 0,
        val bulkPos: Int = 0,
    )

    companion object {
        const val LENSCOM_FRAME32_PROFILE = "gf-lenscom-frame32-experimental-v1"
        const val LENS_DET_GPIO_BANK = 0
        const val LENS_DET_GPIO_PIN = 11
        const val LENS_PWR_GPIO_BANK = 1
        const val LENS_PWR_GPIO_PIN = 13
        const val SYNC_MASTER_PROBE = 0xA55A3CC3L
        const val SYNC_SLAVE_READY = 0xC33CA55AL
        const val SYNC_RESET_SENTINEL = 0xC33C5AA5L

        private const val CMD_BULK_READ_ID = 0x014L
        private const val CMD_BULK_WRITE_BODY = 0x015L
        private const val CMD_BULK_CONFIG = 0x018L
        private const val CMD_BULK_READ_EEPROM = 0x0C0L

        private const val DEFAULT_BULK_BLOCK_LEN = 128
        private const val EEPROM_BULK_BLOCK_LEN = 2048
        const val LENS_EEPROM_BYTES = 131_072
        private const val BULK_MODE_NONE = 0
        private const val BULK_MODE_READ_ID = 1
        private const val BULK_MODE_WRITE = 2
        private const val BULK_MODE_GENERIC = 3
        private const val BULK_MODE_READ_EEPROM_0 = 4
        private const val BULK_MODE_READ_EEPROM_1 = 5
        private const val BULK_MODE_READ_EEPROM_6 = 6

        private fun isEepromBulkMode(mode: Int): Boolean =
            mode == BULK_MODE_READ_EEPROM_0 || mode == BULK_MODE_READ_EEPROM_1 || mode == BULK_MODE_READ_EEPROM_6

        private fun eepromTotalBlocks(mode: Int): Int = when (mode) {
            BULK_MODE_READ_EEPROM_0 -> 1
            BULK_MODE_READ_EEPROM_1 -> 68
            BULK_MODE_READ_EEPROM_6 -> 64
            else -> 0
        }

        private fun eepromBlockOffset(mode: Int, blkIdx: Int): Int = when (mode) {
            BULK_MODE_READ_EEPROM_0 -> 0x00000
            BULK_MODE_READ_EEPROM_1 -> when {
                blkIdx < 60 -> 0x02000 + blkIdx * 2048
                blkIdx < 64 -> 0x00000 + (blkIdx - 60) * 2048
                else -> 0x00000 + (blkIdx - 64) * 2048
            }
            else -> if (blkIdx < 60) 0x02000 + blkIdx * 2048 else 0x00000 + (blkIdx - 60) * 2048
        }

        private val STANDARD_FNO_X100 = intArrayOf(
            100, 110, 120, 140, 160, 180, 200, 220, 250, 280, 320, 350,
            400, 450, 500, 560, 640, 710, 800, 900, 1000, 1100, 1300, 1400,
            1600, 1800, 2000, 2200, 2500, 2900, 3200,
        )

        private fun fnoTableFor(
            lens: LensParameters,
            elec: ElectronicLensPort?,
            modular: ModularLensPort?,
        ): List<Int> {
            val id = modular?.lenscomIdentity
            val minF = (elec?.minAperture ?: id?.minAperture ?: lens.aperture).coerceIn(1.0, 32.0)
            val maxF = (elec?.maxAperture ?: id?.maxAperture ?: maxOf(22.0, minF)).coerceIn(minF, 32.0)
            val minF100 = (minF * 100.0).roundToInt()
            val maxF100 = (maxF * 100.0).roundToInt()
            return STANDARD_FNO_X100.filter { it in (minF100 - 2)..(maxF100 + 2) }
                .ifEmpty { listOf(minF100.coerceIn(100, 3200)) }
                .take(31)
        }

        private fun computeIrisStepIndex(
            lens: LensParameters,
            elec: ElectronicLensPort?,
            modular: ModularLensPort?,
        ): Int {
            val table = fnoTableFor(lens, elec, modular)
            val target100 = (lens.aperture * 100.0).roundToInt()
            var bestIdx = 0
            var bestDiff = Int.MAX_VALUE
            for (i in table.indices) {
                val diff = kotlin.math.abs(table[i] - target100)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestIdx = i
                }
            }
            return bestIdx
        }

        private fun computeZoomStepIndex(
            lens: LensParameters,
            elec: ElectronicLensPort?,
            modular: ModularLensPort?,
        ): Int {
            val minF = elec?.minFocalLengthMm ?: modular?.lenscomIdentity?.minFocalMm ?: lens.focalLengthMm
            val maxF = elec?.maxFocalLengthMm ?: modular?.lenscomIdentity?.maxFocalMm ?: lens.focalLengthMm
            return if (maxF > minF + 0.5) {
                val norm = ((lens.focalLengthMm - minF) / (maxF - minF)).coerceIn(0.0, 1.0)
                (norm * 63.0).roundToInt().coerceIn(0, 63)
            } else {
                0
            }
        }

        private val BULK_COMMAND_IDS = setOf(
            0x014L, 0x015L,
            0x091L, 0x092L, 0x094L, 0x095L, 0x096L,
            0x0C0L, 0x0C9L,
            0x111L, 0x112L, 0x113L, 0x114L, 0x115L, 0x125L,
        )

        private val BASE_SNAPSHOT_KEYS = setOf("schema", "componentId", "scalarValues")
        private val OPTIONAL_BRIDGE_KEYS = setOf("pendingCmdId", "slaveSeq", "bulkBlockLen", "pendingBulkCmd", "bulkMode", "bulkPass", "bulkPos")

        internal fun checksum32(word: Long): Long =
            (((word ushr 27) and 0x1fL) + ((word ushr 22) and 0x1fL) + ((word ushr 17) and 0x1fL) +
                ((word ushr 12) and 0x1fL) + ((word ushr 7) and 0x1fL) + ((word ushr 6) and 1L)) and 0x1fL

        internal fun isDataFrame(word: Long): Boolean =
            word != 0L &&
                word != SYNC_MASTER_PROBE &&
                word != SYNC_SLAVE_READY &&
                word != SYNC_RESET_SENTINEL &&
                (word and 1L) == 0L &&
                ((word ushr 15) and 1L) == 0L

        internal fun buildAckFrame(cmdId: Long, seq: Long): Long {
            val base = (1L shl 27) or ((seq and 0x7L) shl 24) or (1L shl 15) or ((cmdId and 0x1ffL) shl 6)
            return base or (checksum32(base) shl 1)
        }

        internal fun buildDataFrame(cmdId: Long, payload16: Long): Long {
            val base = ((payload16 and 0xffffL) shl 16) or ((cmdId and 0x1ffL) shl 6)
            return base or (checksum32(base) shl 1)
        }

        private fun replyForLensFrame(tx: Long, lens: LensParameters): Long {
            if (lens.model.isEmpty()) return 0L
            if (tx == 0L || tx == SYNC_MASTER_PROBE || tx == SYNC_RESET_SENTINEL || (tx and 1L) != 0L) return 0L
            if (((tx ushr 1) and 0x1fL) != checksum32(tx)) return 0L
            val cmdId = (tx ushr 6) and 0x1ffL
            val frameType = (tx ushr 15) and 1L
            if (frameType != 0L || cmdId == 0x3c0L) return 0L
            val seq = (tx ushr 24) and 0x7L
            return buildAckFrame(cmdId, seq)
        }

        /**
         * GFX100S 2.13 `DAT`-verified GF-mount Frame32 + Bulk-mode responder (`section4.bin`
         * `0x01220ec8..0x012225e4` / `0x01223438..0x012236bc`):
         * - `S12` Sync: master sends `0xA55A3CC3`, clocks out preloaded `0xC33CA55A`; lens then
         *   loads `0xA55A3CC3` for the first `S03` command (`S03 ev4` skips RX callback and keeps
         *   `expected_seq = 0`), resetting `slaveSeq = 0` and `pendingCmdId = -1`.
         * - Phase 1 (`S03`, master `frameType=0`, `cmdId != 0x3c0`): latch `pendingCmdId`, reply
         *   `ACK(cmdId, slaveSeq)`, incrementing `slaveSeq` iff the word clocked out was a DATA frame.
         * - Burst-flush (`S03`, `tx == 0x00000000` clocking out `DATA(C_last)`): reply
         *   `ACK(cmdId=0, slaveSeq)`, increment `slaveSeq`, clear `pendingCmdId = -1`.
         * - Phase 2 (`S05`, master `frameType=1`): if `pendingCmdId >= 0`, reply `DATA(pendingCmdId, payload)`;
         *   if `pendingCmdId < 0` after burst-flush of a Bulk command (`0x014`, `0x015`, etc.), enter Bulk Mode!
         */
        internal fun stepLenscomFrame(
            tx: Long,
            lens: LensParameters,
            elec: ElectronicLensPort? = null,
            pendingCmdId: Long,
            slaveSeq: Long,
            clockedOut: Long,
            sentData: Boolean,
            pendingBulkCmd: Long = -1L,
            bulkBlockLen: Int = DEFAULT_BULK_BLOCK_LEN,
            pendingCmdPayload: Long = 0L,
            modular: ModularLensPort? = null,
            lastFocusCtrl: Int = 0,
            lastFocusSpeed: Int = 0,
            ringChanged: Boolean = false,
            zoomChanged: Boolean = false,
            focusChanged: Boolean = false,
        ): LenscomStepResult {
            if (lens.model.isEmpty()) {
                return LenscomStepResult(0L, -1L, slaveSeq, bulkBlockLen, -1L, nextFocusCtrl = lastFocusCtrl, nextFocusSpeed = lastFocusSpeed)
            }
            // S12 Sync probe (master sends 0xA55A3CC3 expecting 0xC33CA55A, then expects 0xA55A3CC3 on C_0)
            if (tx == SYNC_MASTER_PROBE) {
                val nextRx = if (clockedOut == SYNC_SLAVE_READY) SYNC_MASTER_PROBE else SYNC_SLAVE_READY
                return LenscomStepResult(nextRx, -1L, 0L, bulkBlockLen, -1L, nextFocusCtrl = lastFocusCtrl, nextFocusSpeed = lastFocusSpeed)
            }
            if (tx == SYNC_SLAVE_READY) {
                return LenscomStepResult(SYNC_SLAVE_READY, -1L, 0L, bulkBlockLen, -1L, nextFocusCtrl = lastFocusCtrl, nextFocusSpeed = lastFocusSpeed)
            }
            if (tx == SYNC_RESET_SENTINEL) {
                return LenscomStepResult(SYNC_SLAVE_READY, -1L, 0L, DEFAULT_BULK_BLOCK_LEN, -1L, nextFocusCtrl = 0, nextFocusSpeed = 0)
            }
            // Burst-flush word tx == 0x00000000 clocking out DATA(C_last) -> ACK(cmdId=0, seq=slaveSeq)
            if (tx == 0L) {
                if (sentData) {
                    val seq = slaveSeq and 0x7L
                    val nextSeq = (slaveSeq + 1L) and 0x7L
                    return LenscomStepResult(
                        nextRx = buildAckFrame(0L, seq),
                        nextPendingCmdId = -1L,
                        nextSlaveSeq = nextSeq,
                        nextBulkBlockLen = bulkBlockLen,
                        nextPendingBulkCmd = pendingBulkCmd,
                        nextPendingCmdPayload = pendingCmdPayload,
                        nextFocusCtrl = lastFocusCtrl,
                        nextFocusSpeed = lastFocusSpeed,
                    )
                }
                return LenscomStepResult(0L, -1L, slaveSeq, bulkBlockLen, -1L, nextFocusCtrl = lastFocusCtrl, nextFocusSpeed = lastFocusSpeed)
            }
            if ((tx and 1L) != 0L || ((tx ushr 1) and 0x1fL) != checksum32(tx)) {
                return LenscomStepResult(0L, -1L, slaveSeq, bulkBlockLen, -1L, nextFocusCtrl = lastFocusCtrl, nextFocusSpeed = lastFocusSpeed)
            }

            val cmdId = (tx ushr 6) and 0x1ffL
            val frameType = (tx ushr 15) and 1L
            val txPayload = (tx ushr 16) and 0xffffL

            if (frameType == 0L && cmdId != 0x3c0L) {
                val seq = slaveSeq and 0x7L
                val nextSeq = if (sentData) ((slaveSeq + 1L) and 0x7L) else seq
                val nextLen = if (cmdId == CMD_BULK_CONFIG) {
                    val lenShift = ((txPayload ushr 11) and 0xfL).toInt()
                    if (lenShift in 2..11) (1 shl lenShift) else DEFAULT_BULK_BLOCK_LEN
                } else {
                    bulkBlockLen
                }
                return LenscomStepResult(
                    nextRx = buildAckFrame(cmdId, seq),
                    nextPendingCmdId = cmdId,
                    nextSlaveSeq = nextSeq,
                    nextBulkBlockLen = nextLen,
                    nextPendingBulkCmd = if (cmdId in BULK_COMMAND_IDS) cmdId else -1L,
                    nextPendingCmdPayload = txPayload,
                    nextFocusCtrl = lastFocusCtrl,
                    nextFocusSpeed = lastFocusSpeed,
                )
            }
            if (frameType == 1L) {
                if (pendingCmdId >= 0L) {
                    val nextCtrl = if (pendingCmdId == 0x055L) (pendingCmdPayload and 0xffffL).toInt() else lastFocusCtrl
                    val nextSpeed = if (pendingCmdId == 0x054L || pendingCmdId == 0x151L) {
                        (pendingCmdPayload and 0x7fffL).toInt()
                    } else {
                        lastFocusSpeed
                    }
                    val payload = payloadForLensCommand(
                        cmdId = pendingCmdId,
                        lens = lens,
                        elec = elec,
                        txPayload = pendingCmdPayload,
                        modular = modular,
                        lastFocusCtrl = lastFocusCtrl,
                        lastFocusSpeed = lastFocusSpeed,
                        ringChanged = ringChanged,
                        zoomChanged = zoomChanged,
                        focusChanged = focusChanged,
                    )
                    return LenscomStepResult(
                        nextRx = buildDataFrame(pendingCmdId, payload),
                        nextPendingCmdId = -1L,
                        nextSlaveSeq = slaveSeq,
                        nextBulkBlockLen = bulkBlockLen,
                        nextPendingBulkCmd = pendingBulkCmd,
                        nextPendingCmdPayload = pendingCmdPayload,
                        nextFocusCtrl = nextCtrl,
                        nextFocusSpeed = nextSpeed,
                    )
                }
                // Final S05 ACK after burst-flush (tx==0L) of a Bulk command -> enter S17/S14 Bulk Mode
                if (pendingBulkCmd >= 0L) {
                    return LenscomStepResult(
                        nextRx = 0L,
                        nextPendingCmdId = -1L,
                        nextSlaveSeq = slaveSeq,
                        nextBulkBlockLen = bulkBlockLen,
                        nextPendingBulkCmd = -1L,
                        nextPendingCmdPayload = 0L,
                        enterBulkCmd = pendingBulkCmd,
                        enterBulkArg = (pendingCmdPayload and 0xffffL).toInt(),
                        nextFocusCtrl = lastFocusCtrl,
                        nextFocusSpeed = lastFocusSpeed,
                    )
                }
                return LenscomStepResult(0L, -1L, slaveSeq, bulkBlockLen, -1L, nextFocusCtrl = lastFocusCtrl, nextFocusSpeed = lastFocusSpeed)
            }
            return LenscomStepResult(0L, -1L, slaveSeq, bulkBlockLen, -1L, nextFocusCtrl = lastFocusCtrl, nextFocusSpeed = lastFocusSpeed)
        }

        internal fun payloadForLensCommand(
            cmdId: Long,
            lens: LensParameters,
            elec: ElectronicLensPort? = null,
            txPayload: Long = 0L,
            modular: ModularLensPort? = null,
            lastFocusCtrl: Int = 0,
            lastFocusSpeed: Int = 0,
            ringChanged: Boolean = false,
            zoomChanged: Boolean = false,
            focusChanged: Boolean = false,
        ): Long = when (cmdId) {
            0x010L, 0x011L -> 0x0100L // handshake / protocol version 1.0 (status=0)
            0x012L, 0x013L -> {
                val id = modular?.lenscomIdentity
                if (id != null) {
                    (((id.firmwareMajorBcd and 0xff) shl 8) or (id.firmwareMinorBcd and 0xff)).toLong()
                } else {
                    0x0110L
                }
            }
            0x014L, 0x015L, 0x016L, 0x018L -> 0x0000L // bulk cmd ACK / init complete (lensMgrState -> 2)
            // DAT 0x0121D434 / 0x012206AC: cmd 0x0C0 Optical EEPROM block count (bit15=0 valid, bits[14:0]=blocks)
            CMD_BULK_READ_EEPROM -> when ((txPayload and 0xfL).toInt()) {
                0 -> 0x0001L
                1 -> 0x0044L // 68 blocks
                else -> 0x0040L // mode 6: 64 blocks (60 main + 4 header)
            }
            // DAT S4 0x01220384 / 0x01228860..0x01229A00 / 0x0123F69C..0x0123F944:
            //   bit 0 (0x0001): focus motor busy / encoder changed -> triggers slot 1 cmd 0x021
            //   bit 1 (0x0002): iris motor busy (0=idle -> triggers slot 2 cmd 0x022 readback)
            //   bit 4 (0x0010): aperture ring moved -> triggers slot 5 cmd 0x030 (KEY_M_IRIS_A / KEY_M_IRIS_0..30)
            //   bit 5 (0x0020): zoom ring moved -> triggers slot 6 cmd 0x024 (focal length HUD & EXIF)
            //   bit 6 (0x0040): OIS barrel switch ON -> triggers slot 7 KEY_OIS (0x0049 / 0x0849) when hdr[11]=='G' & hdr[13]&3!=0
            //   bits [11:10] (0x0C00): aperture ring mode (0=A/Manual click-stop via cmd 0x030, 2=C Command Dial -> KEY_M_IRIS_C 0x4020)
            0x020L -> {
                var flags = 0L
                if (modular?.focusMotorBusy == true || focusChanged) flags = flags or 0x0001L
                if (ringChanged) flags = flags or 0x0010L
                if (zoomChanged) flags = flags or 0x0020L
                val hasOis = modular?.lenscomIdentity?.hasOis ?: (elec?.oisEnabled == true)
                if (hasOis && elec?.oisEnabled == true) flags = flags or 0x0040L
                if (modular?.apertureRingMode == "C") flags = flags or 0x0800L
                flags
            }
            // DAT S4 0x0121BEC8 / 0x012204A0 / 0x0132AD1C / 0x013629B4: Live signed 16-bit focus encoder (0x8000 = invalid)
            0x021L -> {
                val encoded = modular?.focusEncoderPosition
                    ?: (lens.focusDistanceM * 100.0).roundToInt().coerceIn(0, 0x7fff)
                (encoded and 0x7fff).toLong()
            }
            0x022L -> {
                // DAT S4 0x0121BF84 / 0x012204BC / 0x013621CC / 0x0132D3C8: Live iris blade position
                // (bit15=0 valid, bits[13:8] = 0-based iris step index 0..nIris-1 into EEPROM fnoTable).
                val irisStep = modular?.irisStepIndex ?: computeIrisStepIndex(lens, elec, modular)
                ((irisStep.coerceIn(0, 30) and 0x3f) shl 8).toLong()
            }
            0x023L -> {
                // Focus/distance signed 15-bit value (bit15=0 valid); live modular motor encoder when attached
                val encoded = modular?.focusEncoderPosition
                    ?: (lens.focusDistanceM * 100.0).roundToInt().coerceIn(0, 0x7fff)
                (encoded and 0x7fff).toLong()
            }
            0x024L -> {
                // DAT S4 0x0121C0FC / 0x0122052C / 0x0122DAB0 / 0x0132A8E4: Zoom encoder state (bit15=0 valid,
                // bits[14:8] = u7 0-based zoom step index 0..zSteps-1 matching eeprom[0x00210], bits[7:0] = sub-step).
                val zoomStep = computeZoomStepIndex(lens, elec, modular)
                ((zoomStep and 0x7f) shl 8).toLong()
            }
            0x026L -> 0x0019L // Lens temperature 25 C (bit15=0 valid)
            // DAT S4 0x0121C37C / 0x012205D8 / 0x0123F868 / 0x015DF92C:
            // Absolute click-stop aperture ring position (12-bit):
            //   0 = KEY_M_IRIS_A (0x4000, Auto)
            //   1..31 = KEY_M_IRIS_0..30 (0x4001..0x401F, 0-based fnoTable step index = value - 1)
            // (In "C" mode, cmd 0x020 bits[11:10]=2 posts KEY_M_IRIS_C 0x4020 and firmware ignores cmd 0x030.)
            0x030L -> when (modular?.apertureRingMode ?: "A") {
                "MANUAL" -> {
                    val step = modular?.apertureRingStepIndex ?: computeIrisStepIndex(lens, elec, modular)
                    (step.coerceIn(0, 30) + 1).toLong()
                }
                else -> 0x0000L
            }
            0x031L, 0x032L, 0x034L, 0x03FL, 0x0E0L, 0x0EFL, 0x0FFL -> 0x0000L // status / zero error flags / wake
            // DAT S4 0x0121C83C / 0x0132B7BC / 0x0132B848 / 0x01362C68 / 0x01362E18:
            // Focus drive burst sends [0x054=speed(u15), 0x055=mode/dir, 0x056=targetPos(s16)]:
            //   - 0x054: drive speed (0x7FFF = max/continuous sweep speed, 1..0x7FFE = controlled AF speed in pulses/sec)
            //   - 0x055: bit0=directional enable, bit1=dir (0=positive/near/MOD, 1=negative/far/INF, S4 0x0135C260),
            //            bit9=target-position mode (0 in contDrive 0x01361DC4, 1 in targetDrive 0x01361E94)
            //   - 0x056: signed 16-bit absolute target focus encoder (0..0x7FFF); continuous sweep drives to
            //            near/MOD (0x7A00) or far/INF (0x0000) when contDrive sets 0x054=0x7FFF & 0x055(bit0=1, bit9=0).
            0x054L, 0x055L -> 0x0000L
            0x056L -> {
                val rawPos = txPayload.toShort().toInt()
                val isContinuousSweep = lastFocusSpeed == 0x7fff &&
                    (lastFocusCtrl and 0x01) != 0 &&
                    (lastFocusCtrl and 0x0200) == 0 &&
                    (rawPos <= 0 || rawPos >= 0x7fff)
                val targetEnc = if (isContinuousSweep) {
                    val towardFar = (lastFocusCtrl and 0x02) != 0
                    if (towardFar) 0x0000 else 0x7a00
                } else {
                    rawPos.coerceIn(0, 0x7fff)
                }
                val speedPps = if (lastFocusSpeed in 1..0x7ffe) lastFocusSpeed else 0
                modular?.commandFocusEncoder(targetEnc, speedPps)
                0x0000L
            }
            // DAT S4 0x0121CA04 / 0x0132B91C: Focus motor STOP/brake -> halt immediately at current encoder position
            0x058L -> {
                if (modular != null) {
                    modular.commandFocusEncoder(modular.focusEncoderPosition, 0)
                }
                0x0000L
            }
            // DAT S4 0x0121CC2C / 0x0132B98C / 0x01361FA0 / 0x01365158:
            // Live iris drive burst sends [0x061=irisSpeed/delay(u15), 0x062=flags(b15:12) | (stepIdx << 6)].
            0x061L -> 0x0000L
            0x062L -> {
                val stepIdx = ((txPayload and 0x0fffL) ushr 6).toInt()
                if (stepIdx in 0..30) {
                    modular?.commandApertureThirdStopIndex(stepIdx)
                }
                0x0000L
            }
            // DAT S4 0x0121E1FC: Relative step focus drive (0x151 = speed, 0x152 = dir bit1 + steps bits[15:3])
            0x151L -> 0x0000L
            0x152L -> {
                if (modular != null) {
                    val steps = ((txPayload ushr 3) and 0x1fffL).toInt()
                    val dirNear = ((txPayload ushr 1) and 1L) != 0L
                    val cur = modular.focusEncoderPosition
                    val next = (if (dirNear) cur + steps else cur - steps).coerceIn(0, 0x7fff)
                    val speedPps = if (lastFocusSpeed in 1..0x7ffe) lastFocusSpeed else 0
                    modular.commandFocusEncoder(next, speedPps)
                }
                0x0000L
            }
            // DAT S4 0x0121D220 / 0x01225DD8 / 0x01365138: Iris motor power/mode enable (1/4/16/32), not target F-stop.
            0x0A0L -> 0x0000L
            // Auxiliary AF focus, OIS & iris commands
            0x040L, 0x050L,
            0x070L, 0x072L, 0x075L, 0x078L,
            0x080L, 0x0A1L, 0x0A8L, 0x0A9L, -> 0x0000L
            else -> 0x0000L
        }

        // The focus selector changes how the body drives the lens, not the motor or EEPROM fitted to it.
        // Legacy ports do not expose a separate hardware identity.
        private fun supportsAutofocus(id: LenscomIdentitySpec?, legacy: ElectronicLensPort?): Boolean =
            if (id == null) legacy?.focusMode != "MF"
            else id.focusMotorType != "MANUAL" && (id.capabilityFlags0 and 0x02) == 0

        /**
         * Builds a `DAT`-compliant 128-byte GF Lens Identity Header (`0x01b1cdcc`, validated at
         * `0x0122bd70..0x0122c508`) dynamically from the active [LensParameters], [ElectronicLensPort],
         * and optional [ModularLensPort] (preserving unique 10-byte `hdr[80..89]` serial numbers so
         * `part-12`/`part-13` EEPROM cache `0x01225764` never reuses stale data across lens swaps).
         */
        internal fun buildLensIdentityHeader128(
            lens: LensParameters,
            elec: ElectronicLensPort? = null,
            modular: ModularLensPort? = null,
        ): ByteArray {
            val hdr = ByteArray(128)
            val id = modular?.lenscomIdentity
            val shortId = id?.shortId ?: buildString {
                for (ch in lens.model.uppercase()) {
                    if (ch in 'A'..'Z' || ch in '0'..'9') append(ch)
                    if (length >= 7) break
                }
                if (isEmpty()) append("GF63F28")
            }
            val idBytes = shortId.toByteArray(Charsets.US_ASCII)
            idBytes.copyInto(hdr, 0, 0, minOf(7, idBytes.size))
            val mountCode = id?.mountCode ?: "GL"
            hdr[8] = mountCode[0].code.toByte()
            hdr[9] = mountCode[1].code.toByte()
            val isZoom = id?.isZoom ?: (elec != null && elec.maxFocalLengthMm > elec.minFocalLengthMm + 0.5)
            hdr[10] = (if (isZoom) 'Z' else 'S').code.toByte()
            // DAT S4 0x0122C01C..0x0122C188: hdr[11]=='G' + (hdr[13]&3)!=0 registers physical lens OIS switch
            // on cmd 0x020 bit 6 -> 0x0123F724 -> KEY_OIS (0x0049/0x0849).
            val hasOis = id?.hasOis ?: (elec?.oisEnabled == true)
            hdr[11] = (if (hasOis) 'G' else 'N').code.toByte()
            val hasAutofocus = supportsAutofocus(id, elec)
            hdr[12] = (if (hasAutofocus) 'A' else 'M').code.toByte()
            hdr[13] = (if (hasOis) 0x01 else 0x00).toByte()

            val displayName = (id?.modelAscii ?: lens.model).ifEmpty { "GF Lens" }.take(63).toByteArray(Charsets.US_ASCII)
            displayName.copyInto(hdr, 16)

            val serialStr = (id?.serialAscii ?: "0000000001").padEnd(10, '0').take(10)
            val serial = serialStr.toByteArray(Charsets.US_ASCII)
            serial.copyInto(hdr, 80, 0, minOf(10, serial.size))

            // 2B Optical EEPROM revision (`0x5A..0x5B`) checked alongside hdr[80..89] in 0x01225764 cache match
            if (id != null) {
                hdr[90] = (id.firmwareMajorBcd and 0xff).toByte()
                hdr[91] = (id.firmwareMinorBcd and 0xff).toByte()
            }

            // Valid 4-digit BCD firmware/hardware versions & protocol version 1.30 (0x01, 0x30)
            hdr[94] = (id?.firmwareMajorBcd ?: 0x01).toByte()
            hdr[95] = (id?.firmwareMinorBcd ?: 0x00).toByte()
            hdr[96] = (id?.hardwareMajorBcd ?: 0x01).toByte()
            hdr[97] = (id?.hardwareMinorBcd ?: 0x00).toByte()
            hdr[98] = (id?.protocolMajorBcd ?: 0x01).toByte()
            hdr[99] = (id?.protocolMinorBcd ?: 0x30).toByte()
            // Capability flags:
            // [100] bit1=0 (AF enabled), bits[5:4]=0x30 (aperture ring mode 3),
            //       bit7=0x80 (On-Sensor PDAF support, DAT S4 0x0122EF34 / 0x0132C8B0 -> S5 0x02554220);
            // [101] bit0=LMO/LEQ support (DAT 0x0122C088 -> boLensSupportLeq), bit1=0 (1-block header), bits[4:3]=0
            val baseCap0 = id?.capabilityFlags0 ?: 0x30
            hdr[100] = (if (hasAutofocus) (baseCap0 or 0x80) else ((baseCap0 or 0x02) and 0x80.inv())).toByte()
            hdr[101] = (id?.capabilityFlags1 ?: 0x00).toByte()
            return hdr
        }

        /**
         * Builds a `DAT`-compliant 128 KiB (`0x720000..0x73FFFF`) GF Lens Optical EEPROM downloaded via
         * `LENSCOM cmd 0x0C0` (`section4.bin 0x012247B4 / 0x012D3AA0 / 0x0132D5F4`). All `u16`/`s16` fields
         * are little-endian (`0x0129D578`).
         */
        internal fun buildLensOpticalEeprom128K(
            lens: LensParameters,
            elec: ElectronicLensPort? = null,
            modular: ModularLensPort? = null,
        ): ByteArray {
            val eeprom = ByteArray(0x20000)
            fun putU16Le(off: Int, value: Int) {
                eeprom[off] = (value and 0xff).toByte()
                eeprom[off + 1] = ((value ushr 8) and 0xff).toByte()
            }
            fun putU32Le(off: Int, value: Int) {
                eeprom[off] = (value and 0xff).toByte()
                eeprom[off + 1] = ((value ushr 8) and 0xff).toByte()
                eeprom[off + 2] = ((value ushr 16) and 0xff).toByte()
                eeprom[off + 3] = ((value ushr 24) and 0xff).toByte()
            }

            val id = modular?.lenscomIdentity
            val fnoTable = fnoTableFor(lens, elec, modular)
            val nIris = fnoTable.size

            val minFocal = (elec?.minFocalLengthMm ?: id?.minFocalMm ?: lens.focalLengthMm).coerceAtLeast(1.0)
            val maxFocal = (elec?.maxFocalLengthMm ?: id?.maxFocalMm ?: lens.focalLengthMm).coerceAtLeast(minFocal)
            val mfdMm = ((elec?.minFocusDistanceM ?: 0.65) * 1000.0).coerceAtLeast(100.0)
            val zSteps = if (maxFocal > minFocal + 0.5) 64 else 1

            // Header (0x720000..0x721FFF)
            // 64-byte (512-bit) supported LENSCOM command bitmap (DAT S4 0x0121AE48..0x0121AEB4):
            // Body ANDs its own supported-command table with EEPROM[0x0007B..0x000BA]; leaving 0x00
            // disables all post-init SPI0 commands (0x021/0x023/0x054..0x058/0x0A0/0x151/0x152).
            eeprom.fill(0xff.toByte(), 0x0007B, 0x000BB)
            putU16Le(0x000BE, 0xAA55) // mandatory magic checked at 0x01224DA0
            // 0x000C2: 0 = native GF lens (uses 0x738000 AV*1000 table at 0x0132D6FC and enables On-Sensor PDAF + CDAF at 0x01331DBC / 0x023FA07C);
            //          1 = H-mount adapter / non-PDAF legacy lens path
            eeprom[0x000C2] = 0x00
            eeprom[0x00210] = zSteps.toByte()
            eeprom[0x00212] = nIris.toByte()
            // Focus stroke zone percentage thresholds (DAT S4 0x01335230: 4 zones -> 5 buckets across [FAR..NEAR])
            eeprom[0x0105E] = 20
            eeprom[0x0105F] = 40
            eeprom[0x01060] = 60
            eeprom[0x01061] = 80
            // 4x5 CDAF focus-step delta table in encoder pulses (DAT S4 0x013352C8: 0x721853 + 5*mode + zone)
            val cdafStepByMode = intArrayOf(240, 160, 96, 48)
            for (m in 0 until 4) {
                for (zone in 0 until 5) {
                    eeprom[0x01853 + m * 5 + zone] = cdafStepByMode[m].toByte()
                }
            }
            eeprom[0x0186B] = zSteps.toByte() // Zoom-count gate for 0x0067E/0x00747 AF focus travel span (DAT S4 0x0132E8D8 / S5 0x02490840)
            eeprom[0x0186C] = zSteps.toByte() // Focus zoom table step count (DAT S5 0x024965E8)
            putU16Le(0x02019, 0x0100) // CDAF drive speed max (DAT S5 0x024891B0: u16[0x722019] > u16[0x72201B])
            putU16Le(0x0201B, 0x0010) // CDAF drive speed min
            eeprom[0x05122] = 0x00 // native GF AF motor (DAT S4 0x01331DD0 / 0x0132CC4C: 0=AF enabled)

            val openF = fnoTable.first() / 100.0
            val closeF = fnoTable.last() / 100.0
            val openAv100 = (200.0 * (ln(openF) / ln(2.0))).roundToInt().coerceIn(0, 65535)
            val closeAv100 = (200.0 * (ln(closeF) / ln(2.0))).roundToInt().coerceIn(openAv100, 65535)
            val avDelta100 = (closeAv100 - openAv100).coerceIn(0, 65535)
            // Primary focus stroke delta (MOD - INF) in signed 15-bit encoder units (DAT S4 0x0132E850):
            // Maps [INF=openAv100 .. MOD=openAv100+focusStrokeDelta] across the lens motor's 0..0x7FFF stroke.
            val focusStrokeDelta = (0x7A00 - openAv100).coerceIn(1024, 0x7800)

            // Main F-number x 100 table (0x72230E = base + 0x0230E)
            for (i in 0 until nIris) {
                putU16Le(0x0230E + i * 2, fnoTable[i])
            }

            // Per-zoom tables
            for (z in 0 until zSteps) {
                val focalMm = if (zSteps > 1) {
                    minFocal + (maxFocal - minFocal) * z.toDouble() / (zSteps - 1).toDouble()
                } else {
                    lens.focalLengthMm.coerceAtLeast(1.0)
                }
                val focal10 = (focalMm * 10.0).roundToInt().coerceIn(1, 65535)
                putU16Le(0x0035A + z * 2, openAv100)
                putU16Le(0x00423 + z * 2, openAv100)
                putU16Le(0x004EC + z * 2, avDelta100)
                putU16Le(0x005B5 + z * 2, 0x3A00) // Mid-range/macro focus boundary < 0x0067E (S4 0x0132EC60 / 0x0176857C)
                putU16Le(0x0067E + z * 2, 0x7400) // Far-to-near AF focus travel span (S4 0x0132E87C / 0x0176857C)
                putU16Le(0x00747 + z * 2, 0x7400) // Alternate AF focus travel span (S4 0x0132E86C)
                // Mechanical focus stop limits [INF_MIN .. MOD_MAX] (DAT S4 0x01330DDC / 0x01330E30 -> S5 0x02484870):
                // Used by CDAF scan planner (0x02489BF4) to clamp [scanStartPos, scanEndPos]; leaving 0x009A2=0 clamps all CDAF scans to delta=0.
                val mechMaxPos = (openAv100 + 0x7400 + 0x200).coerceAtMost(0x7B00)
                putU16Le(0x009A2 + z * 2, mechMaxPos)
                putU16Le(0x00A6A + z * 2, 0)
                putU16Le(0x0217D + z * 2, focal10)
                putU16Le(0x02246 + z * 2, focal10)
                putU16Le(0x02603 + z * 2, 1500) // Focus sensitivity knot half-spacing (S4 0x0132E6DC)
                // Per-zoom focus-shift-with-aperture interpolation knots (S4 0x0133088C / 0x0133092C):
                // 6 monotonically increasing s16 offsets from baseInf at 0x731C00 + z*12 spanning 0..0x7400
                // so aperture transitions (e.g. AE f/6.4 -> AF f/5.0) preserve full AF focus range [INF..MOD].
                for (k in 1..6) {
                    putU16Le(0x11C00 + z * 12 + (k - 1) * 2, (0x7400 * k) / 6)
                }
                // Thin-lens focus travel pitch in nm/pulse: deltaZ_max = f^2 / (MFD - f) over 32767 encoder pulses.
                // S4 0x01332D40 computes c0 = (eeprom[0x17700 + z*22 + k*2] * eeprom[0x02600] + 50) / 100 (nm/pulse).
                val denomMm = (mfdMm - focalMm).coerceAtLeast(focalMm)
                val nmPerPulse = (((focalMm * focalMm) / denomMm) * 1_000_000.0 / 32767.0).roundToInt().coerceIn(50, 2000)
                for (k in 0..10) {
                    putU16Le(0x17700 + z * 22 + k * 2, nmPerPulse)
                }
                eeprom[0x02411 + z] = 0
                eeprom[0x02476 + z] = (nIris - 1).toByte()
                for (s in 0 until 31) {
                    eeprom[0x1C400 + z * 31 + s] = if (s < nIris) s.toByte() else 0xff.toByte()
                    if (s < nIris) {
                        val av1000 = (2000.0 * (ln(fnoTable[s] / 100.0) / ln(2.0))).roundToInt().coerceIn(-32768, 32767)
                        putU16Le(0x18000 + (z * 31 + s) * 2, av1000 and 0xffff)
                    }
                }
            }
            // On-Sensor PDAF & focus-drive lens optical EEPROM calibration fields (0x720000 + offset):
            // - 0x02600: optical focus sensitivity scale percent = 100 (S4 0x01332C40)
            // - 0x0127D/0x0127F/0x01281: focus motor shift, min/max speed & pulse limits (S4 0x0132ED58 / 0x0132ED84)
            // - 0x010BE..0x013C3: AF focus motor drive speed/ramp profiles (S5 0x0255AB60..0x0255AD80)
            // - 0x04AB8: lens PDAF mode bitmask (S5 0x02554EB8)
            // - 0x04AD0 + z*2: per-zoom PDAF min F-number * 10 (0 = open, S5 0x0255466C)
            // - 0x04AD1 + z*2: per-zoom PDAF max F-number * 10 (0xFF = up to body max F-number, S5 0x0255437C / 0x0255482C)
            // - 0x050AF..0x050BA: per-mode PDAF focus-drive ceiling u16 (S5 0x02554C80 / 0x02554DD8 / 0x02554E34)
            if (supportsAutofocus(id, elec)) {
                putU16Le(0x02600, 100) // 100% scale factor applied to 0x17700 nm/pulse table (S4 0x01332C40 / 0x01332D40)
                eeprom[0x0127D] = 0
                putU16Le(0x0127F, 200)
                putU16Le(0x01281, 0x7A00)
                for (off32 in listOf(0x010BE, 0x010DE, 0x0136E, 0x01372, 0x01395, 0x013C3)) {
                    putU32Le(off32, 16_000)
                }
                for (off16 in listOf(0x010C2, 0x010E2, 0x0137A, 0x0139D)) {
                    putU16Le(off16, 16_000)
                }
                eeprom[0x04AB8] = 0xFF.toByte()
                for (z in 0 until zSteps) {
                    eeprom[0x04AD0 + z * 2] = 0x00
                    eeprom[0x04AD1 + z * 2] = 0xFF.toByte()
                }
                for (off in listOf(0x050AF, 0x050B1, 0x050B3, 0x050B7, 0x050B9)) {
                    putU16Le(off, 0x7A00)
                }
            }
            return eeprom
        }

        internal fun buildBulkReadDataPacket(payload: ByteArray, blockLen: Int, blkIdx: Int = 0): ByteArray {
            val pkt = ByteArray(blockLen + 3)
            pkt[0] = ((blkIdx ushr 8) and 0xff).toByte()
            pkt[1] = (blkIdx and 0xff).toByte()
            payload.copyInto(pkt, 2, 0, minOf(payload.size, blockLen))
            var sum = 0
            for (i in 0 until blockLen + 2) sum = (sum + (pkt[i].toInt() and 0xff)) and 0xff
            pkt[blockLen + 2] = sum.toByte()
            return pkt
        }

        internal fun buildBulkReadEndPacket(blockLen: Int): ByteArray {
            val pkt = ByteArray(blockLen + 3)
            pkt[0] = 0xff.toByte()
            pkt[1] = 0xff.toByte()
            pkt[blockLen + 2] = 0xfe.toByte() // (0xFF + 0xFF) & 0xFF
            return pkt
        }

        internal fun buildBulkWriteAckPacket(blockLen: Int): ByteArray {
            val pkt = ByteArray(blockLen + 3)
            pkt[0] = 0xff.toByte()
            pkt[1] = 0x00
            pkt[blockLen + 2] = 0x5a.toByte() // ACK byte checked at 0x01221914
            return pkt
        }

        private fun parseBridge(raw: Any?): ParsedBridge {
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid mount spiBridge snapshot")
            require(map.keys - OPTIONAL_BRIDGE_KEYS == setOf("txAcc", "txBytes", "rxShift", "frameCount")) {
                "Unexpected mount spiBridge keys"
            }
            val txAcc = (map["txAcc"] as? Number)?.toLong() ?: throw IllegalArgumentException("Invalid mount txAcc")
            require(txAcc in 0L..0xffffffffL) { "Out-of-range mount txAcc" }
            val txBytes = exactInt(map["txBytes"], "txBytes")
            require(txBytes in 0..3) { "Invalid mount txBytes $txBytes" }
            val rxShift = (map["rxShift"] as? Number)?.toLong() ?: throw IllegalArgumentException("Invalid mount rxShift")
            require(rxShift in 0L..0xffffffffL) { "Out-of-range mount rxShift" }
            val frameCount = (map["frameCount"] as? Number)?.toLong() ?: throw IllegalArgumentException("Invalid mount frameCount")
            require(frameCount >= 0L) { "Negative mount frameCount" }
            val pending = map["pendingCmdId"]?.let { rawPending ->
                val id = (rawPending as? Number)?.toLong() ?: throw IllegalArgumentException("Invalid mount pendingCmdId")
                require(id in 0L..0x1ffL) { "Out-of-range mount pendingCmdId" }
                id
            } ?: -1L
            val seq = map["slaveSeq"]?.let { rawSeq ->
                val s = (rawSeq as? Number)?.toLong() ?: throw IllegalArgumentException("Invalid mount slaveSeq")
                require(s in 0L..7L) { "Out-of-range mount slaveSeq" }
                s
            } ?: 0L
            val bulkLen = map["bulkBlockLen"]?.let { exactInt(it, "bulkBlockLen") } ?: DEFAULT_BULK_BLOCK_LEN
            require(bulkLen in 4..4096) { "Out-of-range mount bulkBlockLen $bulkLen" }
            val pendingBulk = map["pendingBulkCmd"]?.let { rawB ->
                val id = (rawB as? Number)?.toLong() ?: throw IllegalArgumentException("Invalid mount pendingBulkCmd")
                require(id in 0L..0x1ffL) { "Out-of-range mount pendingBulkCmd" }
                id
            } ?: -1L
            val bulkMode = map["bulkMode"]?.let { exactInt(it, "bulkMode") } ?: BULK_MODE_NONE
            require(bulkMode in BULK_MODE_NONE..BULK_MODE_READ_EEPROM_6) { "Invalid mount bulkMode $bulkMode" }
            val bulkPass = map["bulkPass"]?.let { exactInt(it, "bulkPass") } ?: 0
            val bulkPos = map["bulkPos"]?.let { exactInt(it, "bulkPos") } ?: 0
            require(bulkPass >= 0 && bulkPos >= 0) { "Negative mount bulk state" }
            return ParsedBridge(txAcc, txBytes, rxShift, frameCount, pending, seq, bulkLen, pendingBulk, bulkMode, bulkPass, bulkPos)
        }

        fun exactInt(value: Any?, field: String): Int {
            val number: Long = when (value) {
                is Byte, is Short, is Int, is Long -> (value as Number).toLong()
                is java.math.BigInteger -> {
                    require(value.bitLength() <= 63) { "$field out of Long range" }
                    value.toLong()
                }
                is java.math.BigDecimal -> try {
                    value.longValueExact()
                } catch (exception: ArithmeticException) {
                    throw IllegalArgumentException("$field requires exact integer", exception)
                }
                is Number -> {
                    val decimal = value.toDouble()
                    require(
                        decimal.isFinite() &&
                            decimal % 1.0 == 0.0 &&
                            decimal >= Long.MIN_VALUE.toDouble() &&
                            decimal < 9223372036854775808.0
                    ) { "$field requires exact integer" }
                    decimal.toLong()
                }
                else -> throw IllegalArgumentException("$field requires integer")
            }
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$field out of Int range" }
            return number.toInt()
        }
    }
}
