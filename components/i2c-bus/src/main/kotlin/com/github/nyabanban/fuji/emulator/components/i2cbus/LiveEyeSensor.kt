package com.github.nyabanban.fuji.emulator.components.i2cbus

internal class LiveEyeSensor(initialNear: Boolean = false) {
    companion object {
        const val KIND = "eye-sensor-live-v1"
        const val ADDRESS7 = 0x39
        const val CHIP_ID = 0x39
        private val CONFIG_WRITE_COMMANDS = setOf(0xa0, 0xa1, 0xa2, 0xa3, 0xac, 0xae, 0xaf, 0xbe)
        private val VALID_COMMANDS = setOf(0xb2, 0xb3, 0xb8, 0xa8, 0xe5) + CONFIG_WRITE_COMMANDS
        private val SNAPSHOT_KEYS = setOf(
            "near", "pendingUpdate", "lowThreshold", "highThreshold",
            "selected", "expectRead", "cmd", "writeCount",
            "a8Bytes", "readBuf", "readLen", "readPos",
        )

        private fun snapInt(raw: Any?, field: String, range: IntRange): Int {
            require(raw is Byte || raw is Short || raw is Int || raw is Long) { "Expected integer for $field" }
            val value = (raw as Number).toLong()
            require(value in range.first.toLong()..range.last.toLong()) { "Out-of-range integer for $field" }
            return value.toInt()
        }

        private fun snapBool(raw: Any?, field: String): Boolean =
            raw as? Boolean ?: throw IllegalArgumentException("Expected boolean for $field")

        private fun snapByteList(raw: Any?, field: String, expectedSize: Int): IntArray {
            val list = raw as? List<*> ?: throw IllegalArgumentException("Expected list for $field")
            require(list.size == expectedSize) { "Unexpected list length for $field" }
            return IntArray(expectedSize) { index -> snapInt(list[index], "$field[$index]", 0..255) }
        }
    }

    internal class ParsedSnapshot(
        val near: Boolean,
        val pendingUpdate: Boolean,
        val lowThreshold: Int,
        val highThreshold: Int,
        val selected: Boolean,
        val expectRead: Boolean,
        val cmd: Int?,
        val writeCount: Int,
        val a8Bytes: IntArray,
        val readBuf: IntArray,
        val readLen: Int,
        val readPos: Int,
    )

    var near: Boolean = initialNear
        private set
    private var pendingUpdate: Boolean = true
    private var lowThreshold: Int = 0xffff
    private var highThreshold: Int = 0x0000

    private var selected: Boolean = false
    private var expectRead: Boolean = false
    private var cmd: Int? = null
    private var writeCount: Int = 0
    private val a8Bytes = IntArray(4)
    private val readBuf = IntArray(2)
    private var readLen: Int = 0
    private var readPos: Int = 0

    fun setNear(nextNear: Boolean) {
        near = nextNear
        pendingUpdate = true
    }

    private fun currentSample(): Int = if (near) 0x03ff else 0x0000

    private fun currentStatusByte(): Int {
        val sample = currentSample()
        val outsideWindow = sample < lowThreshold || sample > highThreshold
        return if (pendingUpdate || outsideWindow) 0x20 else 0x00
    }

    fun address(byte: Int): Boolean {
        require(byte in 0..255)
        if ((byte ushr 1) != ADDRESS7) {
            stop()
            return false
        }
        return if ((byte and 1) == 0) {
            selected = true
            expectRead = false
            cmd = null
            writeCount = 0
            readLen = 0
            readPos = 0
            true
        } else if (selected && readPos < readLen) {
            expectRead = true
            true
        } else {
            stop()
            false
        }
    }

    fun checkWrite(byte: Int) {
        require(byte in 0..255)
        require(selected && !expectRead) { "Eye sensor write outside selected write phase" }
        when (val activeCmd = cmd) {
            null -> require(byte in VALID_COMMANDS) { "Unsupported eye sensor command 0x${byte.toString(16)}" }
            0xa8 -> require(writeCount < 16) { "Eye sensor 0xA8 threshold write overflow" }
            in CONFIG_WRITE_COMMANDS -> require(writeCount < 16) { "Eye sensor config register 0x${activeCmd.toString(16)} write overflow" }
            else -> throw IllegalArgumentException("Unexpected extra write byte for eye sensor command 0x${activeCmd.toString(16)}")
        }
    }

    fun write(byte: Int) {
        checkWrite(byte)
        if (cmd == null) {
            cmd = byte
            writeCount = 0
            when (byte) {
                0xb2 -> {
                    readBuf[0] = CHIP_ID
                    readLen = 1
                    readPos = 0
                }
                0xb3 -> {
                    readBuf[0] = currentStatusByte()
                    readLen = 1
                    readPos = 0
                }
                0xb8 -> {
                    val sample = currentSample()
                    readBuf[0] = sample and 0xff
                    readBuf[1] = (sample ushr 8) and 0xff
                    readLen = 2
                    readPos = 0
                }
                0xa8, in CONFIG_WRITE_COMMANDS -> {
                    readLen = 0
                    readPos = 0
                }
                0xe5 -> {
                    pendingUpdate = false
                    readLen = 0
                    readPos = 0
                }
            }
        } else if (cmd == 0xa8) {
            if (writeCount < 4) {
                a8Bytes[writeCount] = byte
            }
            writeCount++
            if (writeCount == 4) {
                lowThreshold = a8Bytes[0] or (a8Bytes[1] shl 8)
                highThreshold = a8Bytes[2] or (a8Bytes[3] shl 8)
            }
        } else {
            writeCount++
        }
    }

    fun peekRead(): Int {
        require(selected && expectRead && readPos < readLen) { "Eye sensor read outside available read buffer" }
        return readBuf[readPos]
    }

    fun consumeRead(expected: Int) {
        require(peekRead() == expected) { "Eye sensor read byte changed before completion" }
        readPos++
    }

    fun stop() {
        selected = false
        expectRead = false
        cmd = null
        writeCount = 0
        readLen = 0
        readPos = 0
    }

    fun state(): Map<String, Any?> = mapOf(
        "address7" to ADDRESS7,
        "near" to near,
        "pendingUpdate" to pendingUpdate,
        "lowThreshold" to lowThreshold,
        "highThreshold" to highThreshold,
        "selected" to selected,
        "confidence" to "HYPOTHESIS",
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "near" to near,
        "pendingUpdate" to pendingUpdate,
        "lowThreshold" to lowThreshold,
        "highThreshold" to highThreshold,
        "selected" to selected,
        "expectRead" to expectRead,
        "cmd" to cmd,
        "writeCount" to writeCount,
        "a8Bytes" to a8Bytes.toList(),
        "readBuf" to readBuf.toList(),
        "readLen" to readLen,
        "readPos" to readPos,
    )

    fun parseSnapshot(raw: Any?): ParsedSnapshot {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid liveEye snapshot")
        require(map.keys == SNAPSHOT_KEYS) { "Invalid keys in liveEye snapshot" }
        val nextNear = snapBool(map["near"], "liveEye.near")
        val nextPending = snapBool(map["pendingUpdate"], "liveEye.pendingUpdate")
        val nextLow = snapInt(map["lowThreshold"], "liveEye.lowThreshold", 0..0xffff)
        val nextHigh = snapInt(map["highThreshold"], "liveEye.highThreshold", 0..0xffff)
        val nextSelected = snapBool(map["selected"], "liveEye.selected")
        val nextExpectRead = snapBool(map["expectRead"], "liveEye.expectRead")
        val nextCmd = map["cmd"]?.let { value ->
            snapInt(value, "liveEye.cmd", 0..255).also { require(it in VALID_COMMANDS) { "Invalid liveEye.cmd" } }
        }
        val nextWriteCount = snapInt(map["writeCount"], "liveEye.writeCount", 0..16)
        val nextA8 = snapByteList(map["a8Bytes"], "liveEye.a8Bytes", 4)
        val nextReadBuf = snapByteList(map["readBuf"], "liveEye.readBuf", 2)
        val nextReadLen = snapInt(map["readLen"], "liveEye.readLen", 0..2)
        val nextReadPos = snapInt(map["readPos"], "liveEye.readPos", 0..nextReadLen)
        require(!nextExpectRead || nextSelected) { "liveEye expectRead requires selected" }
        return ParsedSnapshot(
            near = nextNear,
            pendingUpdate = nextPending,
            lowThreshold = nextLow,
            highThreshold = nextHigh,
            selected = nextSelected,
            expectRead = nextExpectRead,
            cmd = nextCmd,
            writeCount = nextWriteCount,
            a8Bytes = nextA8,
            readBuf = nextReadBuf,
            readLen = nextReadLen,
            readPos = nextReadPos,
        )
    }

    fun applySnapshot(parsed: ParsedSnapshot) {
        near = parsed.near
        pendingUpdate = parsed.pendingUpdate
        lowThreshold = parsed.lowThreshold
        highThreshold = parsed.highThreshold
        selected = parsed.selected
        expectRead = parsed.expectRead
        cmd = parsed.cmd
        writeCount = parsed.writeCount
        parsed.a8Bytes.copyInto(a8Bytes)
        parsed.readBuf.copyInto(readBuf)
        readLen = parsed.readLen
        readPos = parsed.readPos
    }
}
