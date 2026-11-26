package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.PortBinding
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class CatalogComponentProfilesTest {
    private val v4="gfx100s-sd-host-init-registers-experimental-v4"
    private val v5="gfx100s-sd-host-phy-word-retention-experimental-v5"
    private val catalog=AddressCatalog.bundled()
    private val windows=catalog.windows.filter { it.portId.startsWith("soc-sd-host.mixed6078-") }
    private val memory=catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile)
    private val context=object:DiscoveryContext { override fun core()=0;override fun tick()=17L;override fun pc()=0x5af98L;override fun lr()=0x11f1d88L }
    private fun runtime()=CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap())
    private fun bindings()=windows.map { PortBinding(it.componentId,it.portId,it.base,it.size) }
    private fun address(runtime:CatalogRuntime,address:Long,width:Int=4)=runtime.query(mapOf("address" to address.toString(),"width" to width.toString(),"direction" to "write"))

    @Test fun ownerBoundProfilesResolveAddressIdPropertyAndJournalWithoutConsumingInput() {
        for(profile in listOf(v4,v5)) {
            val runtime=runtime();val profiles=mutableMapOf("soc-sd-host" to profile)
            runtime.bindPorts(bindings(),catalog.defaultMemoryProfile,memory,profiles)
            profiles["soc-sd-host"]="changed-after-binding"
            val bus=checkNotNull(runtime.createBus(context));val before=bus.snapshot()
            for(window in windows) {
                val q=address(runtime,window.base)
                val widths=if(profile==v5)listOf(2,4) else listOf(2)
                assertEquals(profile,q["componentProfile"]);assertEquals(listOf(2),q["declaredWriteWidths"])
                assertEquals(widths,q["effectiveWriteWidths"]);assertEquals(widths,q["writeWidths"])
                assertEquals(profile==v5,q["permitted"]);assertEquals("ACTIVE",q["runtimeActivation"])
                val id=q.getValue("registerId").toString()
                val entity=runtime.query(mapOf("id" to id))["entity"] as Map<*,*>
                assertEquals(profile,entity["componentProfile"]);assertEquals(listOf(2),entity["writeWidths"])
                assertEquals(widths,entity["effectiveWriteWidths"])
                val property=runtime.query(mapOf("id" to id,"property" to "effectiveWriteWidths"))
                assertEquals(widths,property["value"]);assertEquals(profile,property["componentProfile"])
                assertEquals(listOf(2),runtime.query(mapOf("id" to id,"property" to "writeWidths"))["value"])
                val fieldId=window.registers.single().fields.first().id
                val field=runtime.query(mapOf("id" to fieldId))["entity"] as Map<*,*>
                assertEquals(id,field["registerId"]);assertEquals(profile,field["componentProfile"])
                assertEquals(widths,field["effectiveWriteWidths"])
                val fieldProperty=runtime.query(mapOf("id" to fieldId,"property" to "effectiveWriteWidths"))
                assertEquals(widths,fieldProperty["value"]);assertEquals(id,fieldProperty["provenanceEntityId"])
                assertEquals(false,q["deviceReadPerformed"]);assertEquals(false,q["guestStateChanged"])
                @Suppress("UNCHECKED_CAST") val exposed=q["activeComponentProfiles"] as MutableMap<String,String>
                assertFails { exposed["soc-sd-host"]="forged" }
            }
            assertEquals(before,bus.snapshot());assertEquals(17L,context.tick())
            val router=MmioRouter(emptyList(),bus)
            router.refusedNative(20,windows.first().base,4,0)
            val row=(runtime.observation(bus)["accesses"] as List<*>).single() as Map<*,*>
            val q=address(runtime,windows.first().base)
            for(key in listOf("componentProfile","declaredWriteWidths","effectiveWriteWidths","writeWidthsByProfile","runtimeActivation"))assertEquals(q[key],row[key],key)
            assertEquals(q["permitted"],row["catalogPermitted"])
            assertEquals(q["refusalReason"],row["catalogRefusalReason"])
            assertEquals("native-unmapped-or-protected",row["reason"]);assertEquals(false,row["completed"])
        }
    }

    @Test fun staticInactiveAndPartialBindingsCannotSelectV5ThroughQueryParameters() {
        val unbound=runtime();val q=address(unbound,windows.first().base)
        assertNull(q["componentProfile"]);assertEquals(listOf(2),q["effectiveWriteWidths"]);assertEquals(false,q["permitted"])
        for(key in listOf("profile","componentProfile","componentProfiles"))assertFails {unbound.query(mapOf("address" to windows.first().base.toString(),key to v5))}
        val partial=runtime();partial.bindPorts(bindings().take(1),catalog.defaultMemoryProfile,memory,mapOf("soc-sd-host" to v5))
        assertEquals(true,address(partial,windows.first().base)["permitted"])
        val inactive=address(partial,windows.last().base)
        assertEquals("INACTIVE",inactive["runtimeActivation"]);assertNull(inactive["componentProfile"])
        assertEquals(listOf(2),inactive["effectiveWriteWidths"]);assertEquals(false,inactive["permitted"])
        val entity=partial.query(mapOf("id" to windows.last().id))["entity"] as Map<*,*>
        assertNull(entity["componentProfile"])
        val inactiveRegister=(entity["registers"] as List<*>).single() as Map<*,*>
        val inactiveField=(inactiveRegister["fields"] as List<*>).first() as Map<*,*>
        assertNull(inactiveField["componentProfile"]);assertEquals(listOf(2),inactiveField["effectiveWriteWidths"])
        val fieldId=windows.first().registers.single().fields.first().id
        val generic=partial.query(mapOf("id" to fieldId))
        assertEquals("ACTIVE_COMPONENT_DEFINITION",generic["runtimeActivation"])
        assertEquals(listOf(windows.first().id),generic["activeWindowIds"])
        assertEquals(listOf(windows.last().id),generic["inactiveWindowIds"])
        val whollyInactive=runtime();whollyInactive.bindPorts(emptyList(),catalog.defaultMemoryProfile,memory)
        val id=q.getValue("registerId").toString()
        assertNull((whollyInactive.query(mapOf("id" to id))["entity"] as Map<*,*>)["componentProfile"])
        assertFails {whollyInactive.query(mapOf("id" to id,"componentProfile" to v5))}
        val disabledField=whollyInactive.query(mapOf("id" to fieldId,"property" to "effectiveWriteWidths"))
        assertNull(disabledField["componentProfile"]);assertEquals(listOf(2),disabledField["value"])
        assertEquals("INACTIVE_COMPONENT_DEFINITION",disabledField["runtimeActivation"])
    }

    @Test fun invalidPortMemoryAndProfileBindingsLeaveAllActiveStateUnchanged() {
        val runtime=runtime();val before=address(runtime,windows.first().base)
        val attempts=listOf<()->Unit>(
            {runtime.bindPorts(bindings(),catalog.defaultMemoryProfile,memory,mapOf("soc-sd-host" to "unimplemented-v99"))},
            {runtime.bindPorts(bindings(),catalog.defaultMemoryProfile,memory,mapOf("unknown" to v5))},
            {runtime.bindPorts(emptyList(),catalog.defaultMemoryProfile,memory,mapOf("soc-sd-host" to v5))},
            {runtime.bindPorts(bindings().map { it.copy(base=it.base+4) },catalog.defaultMemoryProfile,memory,mapOf("soc-sd-host" to v5))},
            {runtime.bindPorts(bindings(),catalog.defaultMemoryProfile,memory.mapIndexed { i,r->if(i==0)r+mapOf("permissions" to 0)else r },mapOf("soc-sd-host" to v5))})
        for(attempt in attempts) {assertFails {attempt()};assertEquals(before,address(runtime,windows.first().base))}
        runtime.bindPorts(bindings(),catalog.defaultMemoryProfile,memory,mapOf("soc-sd-host" to v5))
        assertEquals(true,address(runtime,windows.first().base)["permitted"])
        val bound=address(runtime,windows.first().base)
        assertFails {runtime.bindPorts(emptyList(),catalog.defaultMemoryProfile,memory)}
        assertEquals(bound,address(runtime,windows.first().base))
    }

    @Test fun v5DoesNotGrantOtherWidthsCrossFieldsHolesOrInactiveFallback() {
        val runtime=runtime();runtime.bindPorts(bindings(),catalog.defaultMemoryProfile,memory,mapOf("soc-sd-host" to v5))
        for(width in listOf(1,8))assertEquals(false,address(runtime,windows.first().base,width)["permitted"])
        assertEquals(false,address(runtime,windows.first().base+2,4)["permitted"])
        val bus=checkNotNull(runtime.createBus(context));val before=bus.snapshot()["regions"]
        assertFails {bus.access(1,windows.first().base,4,true,0)}
        assertFails {bus.access(2,windows.first().base+2,4,true,0)}
        assertEquals(before,bus.snapshot()["regions"])
    }
    @Test fun finitePublicTestLayoutUsesRelocatedBoundSdProfileWithoutVendorIdentityWhitelist() {
        val regions=listOf(mapOf<String,Any?>("id" to "loader","address" to 0,"size" to 0x20000,"permissions" to 7))
        val ports=bindings().mapIndexed { i,port -> port.copy(base=0x100000L+i*0x1000L) }
        val raw=mapOf<String,Any?>("schema" to 1,"id" to "own-sd-fixture","version" to "TEST","size" to 0x20284,"sha256" to "a".repeat(64),"entry" to 0,"cpsr" to 0xd3,"loaderMemoryBytes" to 0x20000,"bootMode" to "public-synthetic-guest",
            "sections" to listOf(mapOf("name" to "boot","address" to 0,"size" to 16,"offset" to 0x20274,"encoding" to "raw","sha256" to "b".repeat(64))),
            "devices" to ports.map {mapOf("id" to it.componentId,"port" to it.portId,"address" to it.base,"size" to it.size)},
            "memoryProfiles" to mapOf("own-memory" to regions),"evidence" to listOf("TEST"))
        val runtime=CatalogRuntime(catalog,"strict",Path.of("."),emptyMap())
        runtime.validateFirmware(raw)
        runtime.bindPorts(ports,"own-memory",regions,mapOf("soc-sd-host" to v5))
        for(port in ports) {
            val query=address(runtime,port.base)
            assertEquals("TEST",query["evidenceClass"]);assertEquals("own-sd-fixture",query["firmwareId"])
            assertEquals(v5,query["componentProfile"]);assertEquals(listOf(2),query["declaredWriteWidths"])
            assertEquals(listOf(2,4),query["effectiveWriteWidths"]);assertEquals(true,query["permitted"])
        }
        assertEquals("UNASSIGNED",address(runtime,windows.first().base)["classification"])
        assertNull(runtime.createBus(context))
    }

}
