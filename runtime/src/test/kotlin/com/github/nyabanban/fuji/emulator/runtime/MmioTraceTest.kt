package com.github.nyabanban.fuji.emulator.runtime

import org.junit.jupiter.api.Test
import kotlin.test.*

class MmioTraceTest {
    @Test fun checkpointTailUsesAnObservedCursorAcrossRingWrapAndPreservesLegacyRows() {
        val trace=MmioTrace(3)
        trace.record(false,0x1000,4,1,0x20,1,0)
        val cursor=trace.observed
        repeat(3){i->trace.record(i%2==0,0x2000L+i*4,4,i.toLong(),0x24L+i*4,2L+i,i)}
        val legacy=trace.toList()
        assertEquals(4L,trace.observed)
        assertContentEquals(Formats.jsonBytes(legacy),Formats.jsonBytes(trace.tail(cursor,3)))
        assertEquals(emptyList(),trace.tail(trace.observed))
        assertEquals(legacy,trace.toList())
        assertEquals(4L,trace.observed)
    }
    @Test fun checkpointTailRefusesLostFutureOrOversizedIntervalsWithoutSilentTruncation() {
        val trace=MmioTrace(2)
        repeat(3){trace.record(false,0x1000,4,it.toLong(),0x20,it.toLong(),-1)}
        val before=trace.toList()
        for(cursor in listOf(-1L,0L,4L))assertFailsWith<IllegalArgumentException>{trace.tail(cursor)}
        assertFailsWith<IllegalArgumentException>{trace.tail(1,1)}
        assertEquals(before,trace.toList());assertEquals(3L,trace.observed)
        assertEquals(before,trace.tail(1,2))
    }
    @Test fun parentObservationCapacityIsFiniteAndEmptyUntilRecorded() {
        for (capacity in listOf(1,8192,524288,655360,655361,786432,786433,1179648,1179649,2228224)) {
            val trace=MmioTrace(capacity)
            assertEquals(capacity,trace.capacity)
            assertEquals(0,trace.size)
            assertEquals(emptyList(),trace.toList())
        }
        for (capacity in listOf(Int.MIN_VALUE,-1,0,2228225,Int.MAX_VALUE))
            assertFailsWith<IllegalArgumentException> { MmioTrace(capacity) }
    }

    @Test fun primitiveTracePreservesWireFieldsRingOrderAndOptionalCoreWithoutMutatingOnSnapshot() {
        val trace=MmioTrace(2)
        trace.record(false,0xfffffff0,4,0xffffffff,0x5afe0,3,-1)
        val first=trace.toList();assertEquals(1,trace.size);assertFalse(first.single().containsKey("core"));assertEquals(0xffffffffL,first.single()["value"])
        trace.record(true,0x1000,2,255,0x5af98,4,2)
        trace.record(false,0x1004,1,7,0x5aff8,5,1)
        val actual=trace.toList();assertEquals(listOf(0x1000L,0x1004L),actual.map{it["addressPhysical"]});assertEquals(listOf("write","read"),actual.map{it["kind"]})
        assertEquals(listOf(2,1),actual.map{it["core"]});assertEquals(actual,trace.toList());assertEquals(2,trace.size)
        assertEquals(0xfffffff0L,first.single()["addressPhysical"])
        assertFails{MmioTrace(0)};assertFails{MmioTrace(2228225)}
    }

    @Test fun explicitLargerCapacityRetainsTheEntireOldFrontierAndNewContinuation() {
        val trace=MmioTrace(131072)
        repeat(65967) {i->trace.record(false,0xfffc4000L,4,i.toLong(),0x5afe0L,i.toLong(),0)}
        val saved=trace.toList();assertEquals(65967,saved.size)
        assertEquals(0L,saved.first()["tick"]);assertEquals(65966L,saved.last()["tick"])
        assertEquals(54465L,saved[54465]["value"])
        kotlin.test.assertFails {MmioTrace(2228225)}
    }

    @Test fun callbackObservationCapacityRetainsBeyondOldMaximumWithoutWrapping() {
        val trace=MmioTrace(524288)
        repeat(262145) { i->trace.record(false,0xfff4f01cL,4,1L,0x5afe0L,i.toLong(),3) }
        val rows=trace.toList()
        assertEquals(262145,trace.size)
        assertEquals(262145,rows.size)
        assertEquals(0L,rows.first()["tick"])
        assertEquals(262144L,rows.last()["tick"])
        assertTrue(rows.withIndex().all { (i,row)->row["tick"]==i.toLong() && row["core"]==3 && row["value"]==1L })
        assertEquals(262145,trace.size)
    }
    @Test fun retainedSuffixMakesLostRecordsExplicitAndDigestCoversEveryRetainedPrimitive() {
        val trace=MmioTrace(3)
        repeat(5) {i->trace.record(i%2==0,0x2000L+i,4,i.toLong(),0x100L+i,10L+i,i-1)}
        val before=trace.toList()
        val receipt=trace.retainedSuffix(1)
        assertEquals(2L,receipt["ringRetainedStart"]);assertEquals(5L,receipt["ringRetainedEndExclusive"])
        assertEquals(1L,receipt["omittedSuffixCount"]);assertEquals(true,receipt["truncated"])
        assertEquals(4L,receipt["suffixObservedCount"]);assertEquals(before,receipt["tailRecords"])
        val bytes=java.nio.ByteBuffer.allocate(3*49).order(java.nio.ByteOrder.BIG_ENDIAN)
        for(i in 2..4)bytes.putLong(i.toLong()).putLong(0x2000L+i).putLong(i.toLong()).putLong(0x100L+i).putLong(10L+i).putInt(4).putInt(i-1).put(if(i%2==0)1.toByte()else 0.toByte())
        val expected=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.array()))
        assertEquals(expected,receipt["retainedSuffixSha256"])
        assertEquals(receipt,trace.retainedSuffix(1));assertEquals(before,trace.toList());assertEquals(5L,trace.observed)
        val retained=trace.retainedSuffix(3)
        assertEquals(0L,retained["omittedSuffixCount"]);assertEquals(false,retained["truncated"])
        assertEquals(before.takeLast(2),retained["tailRecords"])
        assertNotEquals(receipt["retainedSuffixSha256"],retained["retainedSuffixSha256"])
        val empty=trace.retainedSuffix(5)
        assertEquals(emptyList<Any>(),empty["tailRecords"]);assertEquals(0L,empty["suffixObservedCount"])
        assertFailsWith<IllegalArgumentException>{trace.retainedSuffix(-1)}
        assertFailsWith<IllegalArgumentException>{trace.retainedSuffix(6)}
        // The original complete-tail API retains its explicit refusal law.
        assertFailsWith<IllegalArgumentException>{trace.tail(1)}
    }
    @Test fun retainedSuffixHashesBeyondBoundedDiagnosticRowsWithoutChangingRecording() {
        val a=MmioTrace(5000);val b=MmioTrace(5000)
        repeat(5000) {i->
            a.record(false,0x1000,4,i.toLong(),0x20,i.toLong(),-1)
            b.record(false,0x1000,4,if(i==0)99L else i.toLong(),0x20,i.toLong(),-1)
        }
        val receipt=a.retainedSuffix(0);val changed=b.retainedSuffix(0)
        assertEquals(false,receipt["truncated"]);assertEquals(0L,receipt["omittedSuffixCount"])
        assertEquals(904L,receipt["tailRecordsOmitted"]);assertEquals(904L,receipt["tailRecordsStart"])
        assertEquals(4096,(receipt["tailRecords"] as List<*>).size)
        assertEquals(receipt["tailRecords"],changed["tailRecords"])
        assertNotEquals(receipt["retainedSuffixSha256"],changed["retainedSuffixSha256"])
        assertFailsWith<IllegalArgumentException>{a.tail(0)}
    }
}
