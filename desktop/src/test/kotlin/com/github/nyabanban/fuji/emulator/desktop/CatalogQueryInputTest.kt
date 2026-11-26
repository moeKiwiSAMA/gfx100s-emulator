package com.github.nyabanban.fuji.emulator.desktop

import org.junit.jupiter.api.Test
import kotlin.test.*

class CatalogQueryInputTest {
    @Test fun sameNumberUsesExplicitDifferentAddressDomainsAndUnits() {
        assertEquals(mapOf("space" to "cpu-physical","address" to "29","width" to "4"),catalogQueryParameters("cpu-physical","29",4))
        for(space in listOf("rtc-power-register","gfx100s-power-protocol-requests"))
            assertEquals(mapOf("space" to space,"selector" to "29","wordBits" to "16"),catalogQueryParameters(space,"29",16))
    }
    @Test fun stableIdsHaveNoInventedAccessOperands() {
        for(space in listOf("cpu-physical","rtc-power-register","gfx100s-power-protocol-requests"))
            assertEquals(mapOf("id" to "reg.rtc.stage-low"),catalogQueryParameters(space," reg.rtc.stage-low ",16))
    }
    @Test fun blankMalformedAndWrongUnitOperandsNeverBecomeZeroOrImplicitMemory() {
        for(value in listOf(""," ","-1","reg.rtc. bad")) assertFailsWith<IllegalArgumentException> {catalogQueryParameters("rtc-power-register",value,16)}
        assertFailsWith<IllegalArgumentException> {catalogQueryParameters("cpu-physical","29",16)}
        assertFailsWith<IllegalArgumentException> {catalogQueryParameters("rtc-power-register","29",0)}
    }
}
