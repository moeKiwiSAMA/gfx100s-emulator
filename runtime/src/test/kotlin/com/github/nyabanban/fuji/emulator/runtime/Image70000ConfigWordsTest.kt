package com.github.nyabanban.fuji.emulator.runtime

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class Image70000ConfigWordsTest {
    @Test fun matchesAllFourDatGetterGroupsAndStopsAfterOnePass() {
        val reads = Image70000ConfigWords.reads
        val offsets = listOf(0x020, 0x054) + (0x024..0x050 step 4) + 0x058 + (0x09c..0x104 step 4)
        assertEquals(offsets, reads.map { it.offset })
        val callSites = listOf(0x11b9b78, 0x11b9b94, 0x11b9ba8, 0x11b9bc4) +
            List(4) { 0x11b9be8 } + listOf(0x11b9c1c, 0x11b9c30, 0x11b9c54, 0x11b9c78, 0x11b9c9c, 0x11b9cc0,
                0x11bd344) + List(6) { listOf(0x11ba03c, 0x11ba05c, 0x11ba074, 0x11ba08c) }.flatten() +
            listOf(0x11ba228, 0x11ba244, 0x11ba260)
        assertEquals(callSites.map { it + 4L }, reads.map { it.lr })
        assertEquals(42, reads.size)
        assertEquals(42, reads.distinctBy { it.offset }.size)
        assertEquals(0L, Image70000ConfigWords.INITIAL_WORD)
        assertNull(reads.getOrNull(42))
    }
}
