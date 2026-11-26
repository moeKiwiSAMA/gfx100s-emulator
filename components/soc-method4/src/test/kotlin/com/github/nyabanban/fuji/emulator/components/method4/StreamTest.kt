package com.github.nyabanban.fuji.emulator.components.method4

import org.junit.jupiter.api.Test
import kotlin.test.*

class StreamTest {
    @Test fun literalsAndOverlappingMatchTokensMayCrossSubmissions() {
        val literal=Stream(header=listOf(5,26,1,0x4000,4))
        assertContentEquals("ab".toByteArray(),literal.feed(byteArrayOf(5,97,98))); assertEquals(3,literal.literal)
        assertContentEquals("cde".toByteArray(),literal.feed("cde".toByteArray())); assertEquals(26,literal.consumed)
        val match=Stream(header=listOf(7,26,1,0x4000,4))
        assertContentEquals("abc".toByteArray(),match.feed(byteArrayOf(3,97,98,99,0x80.toByte())))
        val clone=match.copy()
        assertContentEquals("abca".toByteArray(),clone.feed(byteArrayOf(0x34))) // distance3 length4, overlapping
        assertEquals(3,match.produced); assertEquals(0x80,match.matchFirst); assertEquals(7,clone.produced)
    }
    @Test fun historyWrapAndMalformedTruncatedOrOverlongInputRefuse() {
        val size=2048; val input=ArrayList<Byte>(); val expected=ByteArray(size+15) { (it%251).toByte() }
        for(start in 0..<size step 127) { val count=minOf(127,size-start); input.add(count.toByte()); repeat(count) { input.add(expected[start+it]) } }
        input.add(0xff.toByte()); input.add(0xff.toByte()) // distance2047/length15 uses wrapped history
        repeat(15) { expected[size+it]=expected[size+it-2047] }
        val stream=Stream(header=listOf(expected.size,input.size+20,1,0x4000,4))
        assertContentEquals(expected,stream.feed(input.toByteArray()))
        listOf(byteArrayOf(0),byteArrayOf(0x80.toByte(),0x11),byteArrayOf(1,65,0x80.toByte(),0x10),byteArrayOf(1,65,0x80.toByte(),0x02),byteArrayOf(2,65),byteArrayOf(0x80.toByte())).forEach { bad ->
            assertFails { Stream(header=listOf(2,bad.size+20,1,0x4000,4)).feed(bad) }
        }
        assertFails { Stream(header=listOf(1,22,1,0x4000,4)).feed(byteArrayOf(2,65,66)) }
    }
}
