package com.github.nyabanban.fuji.emulator.firmware

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class Method4Test {
    private fun stream(size: Int, vararg body: Int): ByteArray = ByteBuffer.allocate(20 + body.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(size); putInt(20 + body.size); putInt(1); putInt(0x4000); putInt(4); body.forEach { put(it.toByte()) }
    }.array()
    @Test fun overlappingBackReferenceCopiesProducedBytes() {
        assertContentEquals("ABABABAB".toByteArray(), Method4.decode(stream(8, 2, 65, 66, 128, 38)))
    }
    @Test fun malformedStreamsRefuseBeforeUnboundedAllocation() {
        listOf(stream(1, 0), stream(1, 128), stream(1, 128, 17), stream(2, 1, 65), stream(1, 2, 65, 66), stream(Int.MAX_VALUE, 1, 65)).forEach { bytes ->
            assertFailsWith<IllegalArgumentException> { Method4.decode(bytes) }
        }
    }
}
