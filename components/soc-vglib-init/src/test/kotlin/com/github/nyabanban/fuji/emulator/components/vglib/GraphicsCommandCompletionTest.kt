package com.github.nyabanban.fuji.emulator.components.vglib

import kotlin.test.*
import org.junit.jupiter.api.Test

class GraphicsCommandCompletionTest {
    @Test fun oneLevelAfterReadbackAndBothCallbackClearsAcrossRestore() {
        var assertions = 0
        var clears = 0
        val first = GraphicsCommandCompletion(40)
        first.fire(39,null) { assertions++ }
        assertFails { first.fire(40,null) { assertions++ } }
        assertFails { first.fire(40,40) { assertions++ } }
        assertTrue(first.pending)
        first.fire(41,39) { assertions++ }
        assertEquals(0x80000000L,first.access(52,4,false,0,42) { clears++ })
        assertFails { first.access(0x1020,4,false,0,43) { clears++ } }
        assertFails { first.access(0x1800,2,false,0,43) { clears++ } }
        assertEquals(0L,first.access(0x1800,4,false,0,43) { clears++ })
        assertFails { first.access(0x1800,4,true,1,44) { clears++ } }
        assertEquals(0L,first.access(0x1800,4,true,0,44) { clears++ })
        val resumed = GraphicsCommandCompletion(40)
        resumed.restore(resumed.parse(first.snapshot(),true,39,44))
        resumed.fire(45,39) { assertions++ }
        assertEquals(1,assertions)
        assertEquals(0,clears)
        assertEquals(0L,resumed.access(0x1020,4,false,0,45) { clears++ })
        assertEquals(0L,resumed.access(0x1020,4,true,0,46) { clears++ })
        assertEquals(1,clears)
        assertTrue(resumed.inCallback)
        assertEquals(0L,resumed.access(52,4,false,0,47) { clears++ })
        assertFalse(resumed.inCallback)
        assertEquals(0L,resumed.access(88,4,false,0,48) { clears++ })
        assertNull(resumed.access(88,4,false,0,49) { clears++ })
        assertEquals((41L..48L).toList(),resumed.snapshot())
        for (bad in listOf(listOf(40L,39L), listOf(38L), listOf(49L), (40L..48L).toList())) {
            assertFails { resumed.parse(bad,true,39,48) }
        }
        assertFails { resumed.parse(null,false,39,48) }
    }
}
