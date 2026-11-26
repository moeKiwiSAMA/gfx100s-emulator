package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class CatalogObservationTest {
    private class Context : DiscoveryContext {
        var event=0L
        override fun core()=(event%4).toInt()
        override fun tick()=100L+event
        override fun pc()=0x8000L+event*4
        override fun lr()=0x9000L+event*4
    }
    private fun append(bus:DiscoveryBus,context:Context,address:Long,width:Int=4,direction:String="read",region:Int=-1,completed:Boolean=false) {
        context.event++
        val slot=bus.journal.begin(context.event,address,width,direction=="write",context.event,region,context,
            direction=="execute",if(direction=="write")20 else if(direction=="execute")21 else 19)
        if(completed)bus.journal.complete(slot,context.event,null,context.event)
        else bus.journal.refuse(slot,DiscoveryJournal.OUTSIDE)
    }
    /** Independent pre-optimization per-event formula, including original-region routing. */
    private fun uncached(runtime:CatalogRuntime,bus:DiscoveryBus,profile:String="gfx100s-sd-finite-v1"):Map<String,Any?> {
        val snapshot=bus.snapshot()
        val single=runtime.catalog.singleReadDiagnosticProfiles.singleOrNull{it.id==profile}
        val diagnostic=runtime.catalog.diagnosticProfiles.singleOrNull{it.id==(single?.parentProfile?:profile)}
        val accesses=(snapshot.getValue("accesses") as List<*>).map { raw->
            @Suppress("UNCHECKED_CAST") val access=raw as Map<String,Any?>
            val q=runtime.query(mapOf("space" to "cpu-physical","address" to access.getValue("addressPhysical").toString(),
                "width" to access.getValue("width").toString(),"direction" to access.getValue("direction").toString()))
            access+mapOf("regionId" to q["regionId"],"deviceId" to q["deviceId"],"registerId" to q["registerId"],
                "fieldIds" to q["fieldIds"],"classification" to q["classification"],"resolutionPath" to q["resolutionPath"],
                "propertyProvenance" to (q["register"] as? Map<*,*>)?.get("provenance"),
                "componentProfile" to q["componentProfile"],"declaredWriteWidths" to q["declaredWriteWidths"],
                "effectiveWriteWidths" to q["effectiveWriteWidths"],"writeWidthsByProfile" to q["writeWidthsByProfile"],
                "runtimeActivation" to q["runtimeActivation"],"catalogPermitted" to q["permitted"],"catalogRefusalReason" to q["refusalReason"])+
                ((diagnostic?.takeIf{access["regionId"]==it.id}?.data ?: single?.takeIf{access["regionId"]==it.id}?.data)
                    ?.let{mapOf<String,Any?>("diagnosticRouting" to it)} ?: emptyMap())
        }
        return snapshot+mapOf("profile" to profile,"accesses" to accesses)
    }
    private fun compare(runtime:CatalogRuntime,bus:DiscoveryBus,profile:String="gfx100s-sd-finite-v1"):Map<String,Any?> {
        val before=bus.snapshot()
        val expected=uncached(runtime,bus,profile)
        val actual=runtime.observation(bus)
        assertEquals(expected,actual)
        assertContentEquals(Formats.jsonBytes(expected),Formats.jsonBytes(actual))
        assertEquals(before,bus.snapshot())
        return actual
    }
    @Test fun staticReuseKeepsEveryEventAndRoutesUsingTheOriginalRegion() {
        val catalog=AddressCatalog.bundled()
        val profile="gfx100s-sd-finite-f01c-retained-diagnostic-v1"
        val runtime=CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to profile)))
        val context=Context();val bus=checkNotNull(runtime.createBus(context))
        val region=bus.regions.indexOfFirst{it.id==profile};assertTrue(region>=0)
        append(bus,context,0xfff4f01cL,region=region,completed=true)
        append(bus,context,0xfff4f01cL,completed=true)
        for(direction in listOf("read","write","execute"))for(width in listOf(1,2,4)) {
            append(bus,context,0xfffc41e0L,width,direction,completed=direction=="read"&&width==4)
            append(bus,context,0xfffc41e0L,width,direction)
        }
        append(bus,context,0xfffc6078L)
        append(bus,context,0x40000000L)
        val actual=compare(runtime,bus,profile)
        val rows=actual["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertNotNull(rows[0]["diagnosticRouting"]);assertFalse(rows[1].containsKey("diagnosticRouting"))
        assertNotEquals(rows[0]["value"],rows[1]["value"])
        assertNotEquals(rows[0]["tick"],rows[1]["tick"])
        assertNotEquals(rows[0]["core"],rows[1]["core"])
        assertTrue(rows.any{it["direction"]=="read"&&it["value"]==null})
        assertTrue(rows.any{it["direction"]=="write"&&it["value"]!=null&&it["completed"]==false})
        assertTrue(rows.any{it["classification"]=="UNASSIGNED"})
        assertTrue(rows.any{it["catalogPermitted"]==false})
    }
    @Test fun capacityFallbackAndLaterObservationsKeepFreshActivationAndEvents() {
        val catalog=AddressCatalog.bundled()
        val runtime=CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap())
        val context=Context();val bus=checkNotNull(runtime.createBus(context))
        append(bus,context,0xfffc6078L)
        repeat(4097){append(bus,context,0x40000000L+it*4)}
        append(bus,context,0xfffc6078L)
        append(bus,context,0x40000000L+4096*4)
        val first=compare(runtime,bus)
        val firstRows=first["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertEquals("NOT_BOUND",firstRows.first()["runtimeActivation"])
        runtime.bindPorts(emptyList(),"loader-low128-experimental-v1",catalog.memoryProfiles.getValue("loader-low128-experimental-v1"))
        append(bus,context,0xfffc6078L)
        val second=compare(runtime,bus)
        val secondRows=second["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertNotSame(first["accesses"],second["accesses"])
        assertEquals(firstRows.size+1,secondRows.size)
        assertEquals("INACTIVE",secondRows.first()["runtimeActivation"])
        assertEquals(false,secondRows.first()["catalogPermitted"])
        assertEquals("NOT_BOUND",firstRows.first()["runtimeActivation"])
        assertEquals(context.event,secondRows.last()["sequence"])
        val separate=CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap())
        compare(separate,bus)
        assertEquals("NOT_BOUND",(separate.observation(bus)["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).first()["runtimeActivation"])
    }
    @Test fun repeatedKeySyntheticTimingKeepsUncachedBytes() {
        val catalog=AddressCatalog.bundled()
        val runtime=CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap())
        val context=Context();val bus=checkNotNull(runtime.createBus(context))
        repeat(1000){append(bus,context,0xfffc41e0L,completed=true)}
        // Warm both formulas on the same finite synthetic record set; no native guest or time assertion.
        compare(runtime,bus)
        repeat(7000){append(bus,context,0xfffc41e0L,completed=true)}
        var expected:Map<String,Any?> = emptyMap();var actual:Map<String,Any?> = emptyMap()
        val uncachedNanos=kotlin.system.measureNanoTime{expected=uncached(runtime,bus)}
        val memoNanos=kotlin.system.measureNanoTime{actual=runtime.observation(bus)}
        assertEquals(expected,actual)
        assertContentEquals(Formats.jsonBytes(expected),Formats.jsonBytes(actual))
        println("Stopped observation synthetic8000 repeated-key comparison: uncachedNanos=$uncachedNanos memoNanos=$memoNanos; host-only single sample, not original or whole-emulator speedup")
    }
}
