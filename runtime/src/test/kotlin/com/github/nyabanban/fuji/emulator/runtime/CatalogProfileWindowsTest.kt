package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class CatalogProfileWindowsTest {
    private val catalog=AddressCatalog.bundled()
    private val profiles=mapOf("soc-sd-host" to "gfx100s-sd-host-card-transactions-experimental-v6")
    private val ports=catalog.windowsFor(profiles).filter{it.componentId=="soc-sd-host"}.map{PortBinding(it.componentId,it.portId,it.base,it.size)}
    private fun runtime()=CatalogRuntime(catalog,"strict",Path.of("."),emptyMap())
    private fun bind(r:CatalogRuntime,ports:List<PortBinding> = this.ports,profiles:Map<String,String> = this.profiles)=r.bindPorts(ports,catalog.defaultMemoryProfile,catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile),profiles)
    @Test fun declarationsAndOwnerProfileMustSelectTheSameCompleteLayout() {
        val r=runtime();r.validateFirmware(catalog.firmwareMetadata(profiles));bind(r)
        val q=r.query(mapOf("address" to "0xfffc4030","width" to "4","direction" to "read"))
        assertEquals(true,q["permitted"]);assertEquals("ACTIVE",q["runtimeActivation"])
        assertEquals("soc-sd-host.transaction-0",(q["window"] as Map<*,*>)["portId"])
        for(bad in listOf(ports.dropLast(1),ports.map{if(it.portId.endsWith("transaction-0"))it.copy(size=32)else it}))assertFails {bind(runtime(),bad)}
        val mismatch=runtime();mismatch.validateFirmware(catalog.firmwareMetadata(profiles))
        assertFails {bind(mismatch,profiles=mapOf("soc-sd-host" to "gfx100s-sd-host-phy-word-retention-experimental-v5"))}
        val legacy=runtime();legacy.validateFirmware(catalog.legacyFirmwareMetadata())
        val old=catalog.windows.filter{it.componentId=="soc-sd-host"}.map{PortBinding(it.componentId,it.portId,it.base,it.size)}
        assertFails {bind(legacy,old)}
    }
}
