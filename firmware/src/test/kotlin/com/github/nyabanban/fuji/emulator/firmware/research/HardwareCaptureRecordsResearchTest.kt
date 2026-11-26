package com.github.nyabanban.fuji.emulator.firmware.research

import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Test
import kotlin.test.*

class HardwareCaptureRecordsResearchTest {
    @Test fun ledgerRejectsGapsDuplicatesShortReadsAndOverrunAtomically() {
        val ledger=HardwareCaptureRecordsResearch.ReadLedger(100,300)
        for ((offset,q,r) in listOf(Triple(101L,256L,256L),Triple(100L,256L,255L),Triple(100L,257L,257L))) {
            assertFailsWith<IllegalArgumentException>{ledger.accept(offset,q,r)}
            assertEquals(0L,ledger.consumed)
        }
        ledger.accept(100,256,256)
        assertFailsWith<IllegalArgumentException>{ledger.accept(100,44,44)}
        assertFailsWith<IllegalArgumentException>{ledger.accept(356,45,45)}
        assertFailsWith<IllegalArgumentException>{ledger.finish()}
        ledger.accept(356,44,44);ledger.finish();assertEquals(2L,ledger.chunks)
    }
    @Test fun ledgerRefusesArithmeticOverflow() {
        assertFailsWith<IllegalArgumentException>{HardwareCaptureRecordsResearch.ReadLedger(Long.MAX_VALUE,2)}
        assertFailsWith<IllegalArgumentException>{HardwareCaptureRecordsResearch.ReadLedger(0,0)}
    }
    @Test fun integerParserKeepsPrecisionAndRejectsMissingNullAndFloat() {
        assertEquals(9007199254740993L,HardwareCaptureRecordsResearch.number(Formats.json.readTree("9007199254740993")))
        for (value in listOf("null","1.0","9223372036854775808","\"0\"")) {
            assertFailsWith<IllegalArgumentException>{HardwareCaptureRecordsResearch.number(Formats.json.readTree(value))}
        }
        assertFailsWith<IllegalArgumentException>{HardwareCaptureRecordsResearch.number(null)}
    }
    @Test fun recordedEqualityCannotConcealDifferentHashesOrChangedRetainedA() {
        val bytes=byteArrayOf(1,2);val sha=HardwareDumpResearch.sha(bytes)
        fun row(equal:Boolean,b:String)=Formats.json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf("name" to "x","size" to 2,"offset" to 0,"view" to "TEST","byte_equal" to equal,
            "passes" to listOf(mapOf("pass" to "A","file" to "a","actual" to 2,"sha256" to sha),mapOf("pass" to "B","file" to "b","actual" to 2,"sha256" to b))))
        val actual=HardwareDumpResearch.Entry(bytes,sha)
        assertEquals(true,HardwareCaptureRecordsResearch.checkPasses(row(true,sha),actual)["retainedAMatches"])
        assertEquals(null,HardwareCaptureRecordsResearch.checkPasses(row(true,sha),null)["retainedAMatches"])
        assertFailsWith<IllegalArgumentException>{HardwareCaptureRecordsResearch.checkPasses(row(true,"0".repeat(64)),actual)}
        assertFailsWith<IllegalArgumentException>{HardwareCaptureRecordsResearch.checkPasses(row(true,sha),HardwareDumpResearch.Entry(bytes,"0".repeat(64)))}
        assertEquals(false,HardwareCaptureRecordsResearch.checkPasses(row(false,"0".repeat(64)),actual)["recordedPassHashesEqual"])
    }
    @Test fun codePinsRequireExactBytesAndBounds() {
        val bytes=byteArrayOf(1,2,3,4)
        fun pin(address:Long,sha:String)=Formats.json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf("address" to address,"size" to 4,"sha256" to sha))
        HardwareCaptureRecordsResearch.verifyCode(pin(100,HardwareDumpResearch.sha(bytes)),listOf(100L to bytes))
        assertFailsWith<IllegalArgumentException>{HardwareCaptureRecordsResearch.verifyCode(pin(100,"0".repeat(64)),listOf(100L to bytes))}
        assertFails{HardwareCaptureRecordsResearch.verifyCode(pin(101,HardwareDumpResearch.sha(bytes)),listOf(100L to bytes))}
    }
}
