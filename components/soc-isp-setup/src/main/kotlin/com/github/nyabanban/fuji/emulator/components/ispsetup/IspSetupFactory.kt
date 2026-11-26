package com.github.nyabanban.fuji.emulator.components.ispsetup

import com.github.nyabanban.fuji.emulator.api.*

/**
 * Paired dual-channel SoC I2S/PCM controllers (historically named `soc-isp-setup`, stride `0x29000`):
 * - Controller 0 (`I2S0`): [0xFFF4F008, 0xFFF4F01C) & [0xFFF4F020, 0xFFF4F02C)
 *   (strictly disjoint from diagnosticProfiles INV-SYS-F01C-001 at 0xFFF4F01C..0xFFF4F020)
 * - Controller 1 (`I2S1`): [0xFFF78008, 0xFFF7801C), [0xFFF7801C, 0xFFF78020) (POLL_7801C), & [0xFFF78020, 0xFFF7802C)
 * - Optional opt-in `i2sAudioProfile = "dual-i2s-pcm-dma-experimental-v1"` activates dual-channel
 *   PCM FIFO/DMA TX capture ports (`0xFFF4F000` / `0xFFF78000`) and `I2sAudioPort` without altering
 *   legacy `discovery`, `strict`, or existing snapshot layouts when omitted.
 */
class IspSetupFactory : ComponentFactory {
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        Controller(context, config)

    companion object {
        const val PROFILE = "isp-setup-retained-experimental-v1"
        const val POLL_ONCE = "fff7801c-write1-read0-once-v1"
        const val POLL_REPEAT = "fff7801c-setup-repeat-v1"
        const val POLL_UNLIMITED = "fff7801c-setup-repeatable-v2"
        const val I2S_AUDIO_PROFILE = "dual-i2s-pcm-dma-experimental-v1"

        const val PORT_CTRL0_LOW = "soc-isp-setup.ctrl0-low"
        const val PORT_CTRL0_HIGH = "soc-isp-setup.ctrl0-high"
        const val PORT_CTRL1_LOW = "soc-isp-setup.ctrl1-low"
        const val PORT_CTRL1_POLL = "soc-isp-setup.ctrl1-poll"
        const val PORT_CTRL1_HIGH = "soc-isp-setup.ctrl1-high"
        const val PORT_I2S_AUDIO = DualI2sAudioEngine.PORT_AUDIO
        const val PORT_I2S0_DMA_TX = DualI2sAudioEngine.PORT_I2S0_DMA_TX
        const val PORT_I2S1_DMA_TX = DualI2sAudioEngine.PORT_I2S1_DMA_TX
        const val PORT_I2S0_DMA_RX = DualI2sAudioEngine.PORT_I2S0_DMA_RX
        const val PORT_I2S1_DMA_RX = DualI2sAudioEngine.PORT_I2S1_DMA_RX
        const val PORT_I2S0_FIFO = DualI2sAudioEngine.PORT_I2S0_FIFO
        const val PORT_I2S1_FIFO = DualI2sAudioEngine.PORT_I2S1_FIFO
        val ALL_PORTS = listOf(PORT_CTRL0_LOW, PORT_CTRL0_HIGH, PORT_CTRL1_LOW, PORT_CTRL1_POLL, PORT_CTRL1_HIGH)

        const val LOW_SIZE = 20L
        const val HIGH_SIZE = 12L
        const val POLL_SIZE = 4L

        private val DESCRIPTOR = ComponentDescriptor(
            id = "soc-isp-setup",
            version = "0.0.1",
            settings = listOf(
                Setting("profile", ValueKind.TEXT, "disabled", choices = listOf("disabled", PROFILE), live = false),
                Setting("initialFill", ValueKind.INTEGER, 0L, minimum = 0.0, maximum = 255.0, live = false),
                Setting("poll7801cProfile", ValueKind.TEXT, "", choices = listOf("", POLL_ONCE, POLL_REPEAT, POLL_UNLIMITED), live = false),
                Setting("i2sAudioProfile", ValueKind.TEXT, "", choices = listOf("", I2S_AUDIO_PROFILE), live = false),
            ),
        )

        private val BASE_SCALAR_KEYS = setOf("profile", "initialFill", "poll7801cProfile")

        private val SNAPSHOT_KEYS = setOf(
            "schema", "componentId", "scalars", "enabled",
            "ctrl0LowWords", "ctrl0HighWords", "ctrl1LowWords", "ctrl1HighWords", "pollPosition",
        )

        private data class Slot(val index: Int, val canRead: Boolean, val canWrite: Boolean)

        private val LOW_SLOTS = mapOf(
            0x00L to Slot(0, canRead = false, canWrite = true),
            0x04L to Slot(1, canRead = true, canWrite = true),
            0x08L to Slot(2, canRead = false, canWrite = true),
            0x0cL to Slot(3, canRead = false, canWrite = true),
            0x10L to Slot(4, canRead = true, canWrite = true),
        )

        private val HIGH_SLOTS = mapOf(
            0x00L to Slot(0, canRead = false, canWrite = true),
            0x08L to Slot(1, canRead = false, canWrite = true),
        )

        private fun snapshotLong(value: Any?, field: String, min: Long, max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) {
                "Snapshot field $field requires integer"
            }
            val converted = (value as Number).toLong()
            require(converted in min..max) { "Snapshot field $field out of range: $converted" }
            return converted
        }

        private fun snapshotList(value: Any?, field: String): List<*> =
            value as? List<*> ?: throw IllegalArgumentException("Snapshot field $field requires list")

        private fun snapshotMap(value: Any?, field: String): Map<*, *> {
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all { it is String }) { "Snapshot field $field requires string keys" }
            return map
        }

        private fun snapshotBoolean(value: Any?, field: String): Boolean =
            value as? Boolean ?: throw IllegalArgumentException("Snapshot field $field requires boolean")
    }

    private class Controller(
        context: ComponentContext,
        config: Map<String, Any>,
    ) : ScalarComponent("soc-isp-setup", context, config), StatefulComponent {
        private var closed = false
        private val profile = config.getValue("profile") as String
        private val active = profile == PROFILE
        private val fillByte = (config.getValue("initialFill") as Number).toLong()
        private val pollProfile = config.getValue("poll7801cProfile") as String
        private val i2sAudioProfile = (config["i2sAudioProfile"] as? String).orEmpty()

        private val ctrl0LowWords = LongArray(5)
        private val ctrl0HighWords = LongArray(2)
        private val ctrl1LowWords = LongArray(5)
        private val ctrl1HighWords = LongArray(2)
        private var pollPosition = 0
        private val i2sAudio: DualI2sAudioEngine? =
            if (active && i2sAudioProfile == I2S_AUDIO_PROFILE) {
                DualI2sAudioEngine({ context.clock.ticks() }, movieExposureTick = {
                    context.ports.find(CcdFrameClockPort.PORT_ID, CcdFrameClockPort::class.java)?.latestMovieFrameSyncTick()
                })
            } else {
                null
            }

        init {
            require(profile in listOf("disabled", PROFILE)) { "Unsupported soc-isp-setup profile: $profile" }
            require(fillByte in 0L..255L) { "soc-isp-setup initialFill outside 0..255" }
            require(i2sAudioProfile in listOf("", I2S_AUDIO_PROFILE)) {
                "Unsupported soc-isp-setup i2sAudioProfile: $i2sAudioProfile"
            }
            if (!active) {
                require(fillByte == 0L && pollProfile.isEmpty() && i2sAudioProfile.isEmpty()) {
                    "Disabled soc-isp-setup cannot carry initialFill, poll7801cProfile, or i2sAudioProfile"
                }
            } else {
                val fillWord = fillByte or (fillByte shl 8) or (fillByte shl 16) or (fillByte shl 24)
                ctrl0LowWords.fill(fillWord)
                ctrl0HighWords.fill(fillWord)
                ctrl1LowWords.fill(fillWord)
                ctrl1HighWords.fill(fillWord)
                registerLowPort(PORT_CTRL0_LOW, 0, ctrl0LowWords)
                registerHighPort(PORT_CTRL0_HIGH, 0, ctrl0HighWords)
                registerLowPort(PORT_CTRL1_LOW, 1, ctrl1LowWords)
                registerHighPort(PORT_CTRL1_HIGH, 1, ctrl1HighWords)
                registerPollPort()
                i2sAudio?.let { engine ->
                    context.ports.register(id, PORT_I2S_AUDIO, I2sAudioPort::class.java, engine)
                    context.ports.register(id, PORT_I2S0_DMA_TX, DmaDestinationPeer::class.java, engine.createDmaTxPeer(0))
                    context.ports.register(id, PORT_I2S1_DMA_TX, DmaDestinationPeer::class.java, engine.createDmaTxPeer(1))
                    context.ports.register(id, PORT_I2S0_DMA_RX, DmaSourcePeer::class.java, engine.createDmaRxPeer(0))
                    context.ports.register(id, PORT_I2S1_DMA_RX, DmaSourcePeer::class.java, engine.createDmaRxPeer(1))
                    context.ports.register(id, PORT_I2S0_FIFO, MmioDevice::class.java, engine.createFifoMmioDevice(0))
                    context.ports.register(id, PORT_I2S1_FIFO, MmioDevice::class.java, engine.createFifoMmioDevice(1))
                }
            }
        }

        private fun registerLowPort(name: String, channelIndex: Int, words: LongArray) {
            context.ports.register(id, name, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 78
                private fun resolve(offset: Long, size: Int, write: Boolean): Slot {
                    check(!closed) { "soc-isp-setup closed" }
                    require(size == 4 && offset >= 0L && offset < LOW_SIZE && offset % 4L == 0L) {
                        "$name requires aligned 32-bit word within [0, $LOW_SIZE)"
                    }
                    val slot = LOW_SLOTS[offset] ?: error("$name refused offset 0x${offset.toString(16)}")
                    require(if (write) slot.canWrite else slot.canRead) {
                        "$name refused ${if (write) "write" else "read"} at offset 0x${offset.toString(16)}"
                    }
                    return slot
                }

                override fun read(offset: Long, size: Int): Long = words[resolve(offset, size, write = false).index]

                override fun write(offset: Long, size: Int, value: Long) {
                    val slot = resolve(offset, size, write = true)
                    require(value in 0L..0xffffffffL) { "$name write value outside u32" }
                    words[slot.index] = value
                    i2sAudio?.onLowRegisterWrite(channelIndex, offset, value)
                }
            })
        }

        private fun registerHighPort(name: String, channelIndex: Int, words: LongArray) {
            context.ports.register(id, name, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 78
                private fun resolve(offset: Long, size: Int, write: Boolean): Slot {
                    check(!closed) { "soc-isp-setup closed" }
                    require(size == 4 && offset >= 0L && offset < HIGH_SIZE && offset % 4L == 0L) {
                        "$name requires aligned 32-bit word within [0, $HIGH_SIZE)"
                    }
                    val slot = HIGH_SLOTS[offset] ?: error("$name refused hole at offset 0x${offset.toString(16)}")
                    require(if (write) slot.canWrite else slot.canRead) {
                        "$name refused ${if (write) "write" else "read"} at offset 0x${offset.toString(16)}"
                    }
                    return slot
                }

                override fun read(offset: Long, size: Int): Long = words[resolve(offset, size, write = false).index]

                override fun write(offset: Long, size: Int, value: Long) {
                    val slot = resolve(offset, size, write = true)
                    require(value in 0L..0xffffffffL) { "$name write value outside u32" }
                    words[slot.index] = value
                    i2sAudio?.onHighRegisterWrite(channelIndex, offset, value)
                }
            })
        }

        private fun registerPollPort() {
            context.ports.register(id, PORT_CTRL1_POLL, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 78
                override fun read(offset: Long, size: Int): Long {
                    check(!closed) { "soc-isp-setup closed" }
                    require(pollProfile.isNotEmpty()) { "$PORT_CTRL1_POLL is disabled" }
                    require(size == 4 && offset == 0L) { "$PORT_CTRL1_POLL requires aligned READ4 at offset 0" }
                    if (pollProfile == POLL_UNLIMITED) {
                        if (pollPosition % 2 == 1) {
                            pollPosition++
                        }
                        return 0L
                    }
                    val limit = if (pollProfile == POLL_REPEAT) 4 else 2
                    require(pollPosition < limit && (pollPosition == 1 || pollPosition == 3)) {
                        "$PORT_CTRL1_POLL unexpected read at position=$pollPosition"
                    }
                    pollPosition++
                    return 0L
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    check(!closed) { "soc-isp-setup closed" }
                    require(pollProfile.isNotEmpty()) { "$PORT_CTRL1_POLL is disabled" }
                    require(size == 4 && offset == 0L) { "$PORT_CTRL1_POLL requires aligned WRITE4 at offset 0" }
                    if (pollProfile == POLL_UNLIMITED) {
                        require(value == 0L || value == 1L) { "$PORT_CTRL1_POLL write requires value 0 or 1, got $value" }
                        if (value == 1L) {
                            if (pollPosition % 2 == 0) {
                                pollPosition++
                            }
                            i2sAudio?.onSoftReset(1)
                        }
                        return
                    }
                    val limit = if (pollProfile == POLL_REPEAT) 4 else 2
                    require(pollPosition < limit && (pollPosition == 0 || pollPosition == 2)) {
                        "$PORT_CTRL1_POLL unexpected write at position=$pollPosition"
                    }
                    require(value == 1L) { "$PORT_CTRL1_POLL write requires value=1, got $value" }
                    pollPosition++
                    i2sAudio?.onSoftReset(1)
                }
            })
        }

        override fun start() {
            check(!closed) { "soc-isp-setup closed" }
        }

        override fun close() {
            closed = true
        }

        override fun additionalMmioPorts(): List<String> = if (active) ALL_PORTS else emptyList()

        override fun state(): Map<String, Any?> = super.state() + mapOf(
            "enabled" to active,
            "ports" to additionalMmioPorts(),
            "ctrl0LowWords" to if (active) ctrl0LowWords.toList() else emptyList<Long>(),
            "ctrl0HighWords" to if (active) ctrl0HighWords.toList() else emptyList<Long>(),
            "ctrl1LowWords" to if (active) ctrl1LowWords.toList() else emptyList<Long>(),
            "ctrl1HighWords" to if (active) ctrl1HighWords.toList() else emptyList<Long>(),
            "pollPosition" to pollPosition,
            "basis" to "DAT section4/kernel aligned 32-bit offsets & stride 0x29000 (excluding 0xFFF4F01C INV-SYS-F01C-001); retained fills & POLL_7801C are HYPOTHESIS; optional dual-I2S PCM/DMA capture is opt-in",
        ) + (i2sAudio?.let { mapOf("i2sAudio" to it.state()) } ?: emptyMap())

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "soc-isp-setup closed" }
            val scalars = saveScalarValues().let { saved ->
                if (i2sAudioProfile.isEmpty()) saved - "i2sAudioProfile" else saved
            }
            val result = linkedMapOf<String, Any?>(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to scalars,
                "enabled" to active,
                "ctrl0LowWords" to if (active) ctrl0LowWords.toList() else emptyList<Long>(),
                "ctrl0HighWords" to if (active) ctrl0HighWords.toList() else emptyList<Long>(),
                "ctrl1LowWords" to if (active) ctrl1LowWords.toList() else emptyList<Long>(),
                "ctrl1HighWords" to if (active) ctrl1HighWords.toList() else emptyList<Long>(),
                "pollPosition" to pollPosition,
            )
            i2sAudio?.let { result["i2sAudio"] = it.saveSnapshot() }
            return result
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "soc-isp-setup closed" }
            require(data.keys - "i2sAudio" == SNAPSHOT_KEYS) { "Unexpected snapshot keys for $id" }
            require(snapshotLong(data["schema"], "schema", 1L, Int.MAX_VALUE.toLong()).toInt() == snapshotSchemaVersion) {
                "Unsupported snapshot schema for $id"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = snapshotMap(data["scalars"], "scalars")
            val expectedScalars = DESCRIPTOR.settings.map { it.key }.toSet()
            require(scalarsRaw.keys == expectedScalars || scalarsRaw.keys == BASE_SCALAR_KEYS) {
                "Scalar setting keys differ in snapshot for $id"
            }
            val normalizedScalars = linkedMapOf<String, Any?>()
            for ((k, v) in scalarsRaw) normalizedScalars[k as String] = v
            val savedI2sProfile = if (normalizedScalars.containsKey("i2sAudioProfile")) {
                normalizedScalars["i2sAudioProfile"] as? String
                    ?: throw IllegalArgumentException("i2sAudioProfile in snapshot must be text")
            } else {
                ""
            }
            val parsedI2s = if ("i2sAudio" in data) {
                requireNotNull(i2sAudio) { "Snapshot contains i2sAudio state without active $I2S_AUDIO_PROFILE" }
                    .parseSnapshot(data["i2sAudio"])
            } else {
                null
            }
            val previousValues = values
            val next0Low = LongArray(5)
            val next0High = LongArray(2)
            val next1Low = LongArray(5)
            val next1High = LongArray(2)
            val nextPollPos: Int
            try {
                require(snapshotBoolean(data["enabled"], "enabled") == active) {
                    "Snapshot enabled flag differs for $id"
                }
                val savedPollPos = snapshotLong(data["pollPosition"], "pollPosition", 0L, Int.MAX_VALUE.toLong()).toInt()
                if (!active) {
                    require(savedI2sProfile.isEmpty() && parsedI2s == null) {
                        "Disabled soc-isp-setup snapshot cannot carry i2sAudioProfile"
                    }
                    val disabledCompat = if (savedI2sProfile.isEmpty() && !values.containsKey("i2sAudioProfile")) {
                        setOf("i2sAudioProfile")
                    } else {
                        emptySet()
                    }
                    restoreScalarValues(normalizedScalars, DESCRIPTOR, disabledCompat)
                    require(
                        snapshotList(data["ctrl0LowWords"], "ctrl0LowWords").isEmpty() &&
                            snapshotList(data["ctrl0HighWords"], "ctrl0HighWords").isEmpty() &&
                            snapshotList(data["ctrl1LowWords"], "ctrl1LowWords").isEmpty() &&
                            snapshotList(data["ctrl1HighWords"], "ctrl1HighWords").isEmpty() &&
                            savedPollPos == 0,
                    ) { "Disabled soc-isp-setup snapshot cannot carry active state" }
                    nextPollPos = 0
                } else {
                    val savedPollProfile = normalizedScalars["poll7801cProfile"] as? String ?: ""
                    val compatible = mutableSetOf<String>()
                    if (savedPollProfile != pollProfile) {
                        val validPromo = (savedPollProfile == "" && pollProfile in listOf(POLL_ONCE, POLL_REPEAT, POLL_UNLIMITED) && savedPollPos == 0) ||
                            (savedPollProfile == POLL_ONCE && pollProfile in listOf(POLL_REPEAT, POLL_UNLIMITED) && savedPollPos <= 2) ||
                            (savedPollProfile == POLL_REPEAT && pollProfile == POLL_UNLIMITED && savedPollPos <= 4)
                        require(validPromo) {
                            "Incompatible poll7801cProfile transition: $savedPollProfile -> $pollProfile at pos=$savedPollPos"
                        }
                        compatible += "poll7801cProfile"
                    } else {
                        val maxPos = when (savedPollProfile) {
                            POLL_UNLIMITED -> Int.MAX_VALUE
                            POLL_REPEAT -> 4
                            POLL_ONCE -> 2
                            else -> 0
                        }
                        require(savedPollPos in 0..maxPos) {
                            "pollPosition ($savedPollPos) exceeds limit for $savedPollProfile"
                        }
                    }
                    if (savedI2sProfile != i2sAudioProfile) {
                        val validAudioPromo = savedI2sProfile.isEmpty() && i2sAudioProfile == I2S_AUDIO_PROFILE && parsedI2s == null
                        require(validAudioPromo) {
                            "Incompatible i2sAudioProfile transition: $savedI2sProfile -> $i2sAudioProfile"
                        }
                        compatible += "i2sAudioProfile"
                    } else if (savedI2sProfile.isEmpty() && !values.containsKey("i2sAudioProfile")) {
                        compatible += "i2sAudioProfile"
                    }
                    restoreScalarValues(normalizedScalars, DESCRIPTOR, compatible)

                    fun readWords(key: String, target: LongArray) {
                        val list = snapshotList(data[key], key)
                        require(list.size == target.size) { "$key size mismatch" }
                        for (i in target.indices) {
                            target[i] = snapshotLong(list[i], "$key[$i]", 0L, 0xffffffffL)
                        }
                    }
                    readWords("ctrl0LowWords", next0Low)
                    readWords("ctrl0HighWords", next0High)
                    readWords("ctrl1LowWords", next1Low)
                    readWords("ctrl1HighWords", next1High)
                    nextPollPos = savedPollPos
                }
            } catch (failure: Throwable) {
                values = previousValues
                throw failure
            }
            next0Low.copyInto(ctrl0LowWords)
            next0High.copyInto(ctrl0HighWords)
            next1Low.copyInto(ctrl1LowWords)
            next1High.copyInto(ctrl1HighWords)
            pollPosition = nextPollPos
            i2sAudio?.applySnapshot(parsedI2s)
        }
    }
}
