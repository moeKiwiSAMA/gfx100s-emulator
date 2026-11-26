package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.LogicalPortBinding
import com.github.nyabanban.fuji.emulator.catalog.LogicalRegisterContract
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

/** A known physical entity must not be relabelled as a protocol selector. */
class LogicalCatalogReviewTest {
    @Test fun physicalRegisterIdCannotBeQueriedInsideLogicalSelectorSpace() {
        val runtime=CatalogRuntime(AddressCatalog.bundled(),"discovery",Path.of("."),emptyMap())
        assertFailsWith<IllegalArgumentException> {
            runtime.query(mapOf("space" to "rtc-power-register","id" to "reg.soc-sd-host.word80.o0"))
        }
    }
    @Test fun deviceAndLeafViewsUseTheSameBoundLogicalProfile() {
        val profile="gfx100s-rtc-scripted-stage-read-experimental-v2"
        val runtime=CatalogRuntime(AddressCatalog.bundled(),"discovery",Path.of("."),emptyMap())
        runtime.bindLogicalPorts(listOf(LogicalPortBinding("rtc","rtc.registers",profile,listOf(
            LogicalRegisterContract("reg.rtc.stage-low",29,16,true,true),
            LogicalRegisterContract("reg.rtc.stage-high",30,16,true,true),
            LogicalRegisterContract("reg.rtc.counter-low",35,16,true,false),
            LogicalRegisterContract("reg.rtc.counter-high",36,16,true,false),
            LogicalRegisterContract("reg.rtc.control",39,16,true,true)))),mapOf("rtc" to profile))
        val leaf=runtime.query(mapOf("id" to "reg.rtc.stage-low"))["entity"] as Map<*,*>
        val device=runtime.query(mapOf("id" to "device.rtc"))["entity"] as Map<*,*>
        val space=(device["logicalSpaces"] as List<*>).single() as Map<*,*>
        val child=(space["registers"] as List<*>).map {it as Map<*,*>}.single {it["id"]=="reg.rtc.stage-low"}
        assertEquals(profile,leaf["componentProfile"])
        assertEquals(leaf["componentProfile"],child["componentProfile"])
        assertEquals(leaf["effectiveReadable"],child["effectiveReadable"])
    }
}
