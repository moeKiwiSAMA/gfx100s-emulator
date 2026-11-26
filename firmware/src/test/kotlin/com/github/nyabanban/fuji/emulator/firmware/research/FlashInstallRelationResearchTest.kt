package com.github.nyabanban.fuji.emulator.firmware.research

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test
import kotlin.test.*

class FlashInstallRelationResearchTest {
    private fun packed(): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(3).putInt(24).putInt(1).putInt(0x4000).putInt(4).put(3).put(65).put(66).put(67).array()
    @Test fun datLogicalAndApertureAddressesRemainSeparate() {
        val mapping = FlashInstallRelationResearch.mapping(0x20000,0x20000)
        assertEquals(0x20274L,mapping["datFileOffset"])
        assertEquals(0x20000L,mapping["flashLogicalOffset"])
        assertEquals(0xf0020000L,mapping["configuredApertureStart"])
        assertEquals(false,mapping["physicalCpuMappingProved"])
    }
    @Test fun checkedHalfOpenMappingsCannotWrapOrIncludeOutsideAperture() {
        assertEquals(0xf8000000L,FlashInstallRelationResearch.mapping(0x7ffffff,1)["configuredApertureEndExclusive"])
        for ((offset,size) in listOf(-1L to 1L,0L to 0L,0x8000000L to 1L,1L to Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { FlashInstallRelationResearch.mapping(offset,size) }
        }
    }
    @Test fun sliceRefusesWholeInvalidExtentBeforeCopy() {
        val bytes = byteArrayOf(0,1,2,3)
        assertContentEquals(byteArrayOf(1,2),FlashInstallRelationResearch.slice(bytes,1,2))
        for ((offset,size) in listOf(-1L to 1L,0L to -1L,3L to 2L,Long.MAX_VALUE to 1L,1L to Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { FlashInstallRelationResearch.slice(bytes,offset,size) }
        }
    }
    @Test fun decodedPayloadAndInitializedReservationAreDifferentLengths() {
        val result = FlashInstallRelationResearch.initializedReservation(8389100,8392704)
        assertEquals("MATCHES_4K_ROUND_UP",result["relation"])
        assertEquals(3604L,result["trailingReservationBytes"])
        assertEquals(false,result["trailingRuntimeContentsProved"])
        assertEquals("EXACT",FlashInstallRelationResearch.initializedReservation(4096,4096)["relation"])
        assertFailsWith<IllegalArgumentException> { FlashInstallRelationResearch.initializedReservation(4097,4096) }
        assertFailsWith<IllegalArgumentException> { FlashInstallRelationResearch.initializedReservation(4097,8193) }
        assertFailsWith<IllegalArgumentException> { FlashInstallRelationResearch.initializedReservation(0,0) }
    }
    @Test fun method4PackedPayloadExcludesAllocationPadding() {
        val source = ByteArray(40) { -1 }.apply { packed().copyInto(this,8) }
        assertEquals(24,FlashInstallRelationResearch.packedLength(source,8,32))
        assertContentEquals(byteArrayOf(65,66,67),FlashInstallRelationResearch.unpacked(source,8,32))
    }
    @Test fun method4RejectsBadHeadersRatherThanTreatingBytesAsRaw() {
        val source = packed()
        for ((offset,value) in listOf(0 to -1,4 to 19,4 to 25,8 to 2,12 to 256,16 to 3)) {
            val changed = source.copyOf(); ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(offset,value)
            assertFailsWith<IllegalArgumentException> { FlashInstallRelationResearch.packedLength(changed,0,24) }
        }
        assertFailsWith<IllegalArgumentException> { FlashInstallRelationResearch.packedLength(source,0,23) }
        assertFailsWith<IllegalArgumentException> { FlashInstallRelationResearch.packedLength(source,1,24) }
    }
    @Test fun compressedDecodeFailureIsNotByteEquality() {
        val changed = packed().apply { this[20] = 0 }
        assertEquals(24,FlashInstallRelationResearch.packedLength(changed,0,24))
        assertFailsWith<IllegalArgumentException> { FlashInstallRelationResearch.unpacked(changed,0,24) }
    }
    @Test fun compareCountsAllDifferencesWithExactOffsetAndBounds() {
        val result = FlashInstallRelationResearch.compare(byteArrayOf(9,1,7,3,9),1,byteArrayOf(1,2,3))
        assertEquals(false,result["equal"]); assertEquals(1L,result["differentBytes"])
        assertEquals(1,result["firstDifferenceOffset"])
        assertFailsWith<IllegalArgumentException> { FlashInstallRelationResearch.compare(ByteArray(4),2,ByteArray(3)) }
    }
    @Test fun equalPayloadCannotHideDifferentAllocationPadding() {
        val expected = packed() + byteArrayOf(-1,-1)
        val actual = expected.copyOf().apply { this[lastIndex] = 0 }
        assertEquals(true,FlashInstallRelationResearch.compare(actual,0,packed())["equal"])
        assertEquals(false,FlashInstallRelationResearch.compare(actual,0,expected)["equal"])
        assertEquals(1L,FlashInstallRelationResearch.compare(actual,0,expected)["differentBytes"])
    }
}
