package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class DispatchTailDiagnosticTest {
    private val profile=DispatchTailDiagnosticQualification.PROFILE
    private val combined=DispatchTailDiagnosticQualification.COMBINED
    private val limit=DispatchTailDiagnosticQualification.LIMIT
    private val address=0xfff4f01cL
    private val a300=0xfff4a300L
    private val catalog by lazy{AddressCatalog.bundled()}
    private val context=object:DiscoveryContext {override fun core()=3;override fun tick()=159731281L;override fun pc()=0x5afe0L;override fun lr()=0x12919e0L}
    private fun runtime(id:String=combined,capacity:Int=128)=CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to id,"logCapacity" to capacity)))
    private fun states(bus:DiscoveryBus)=(bus.snapshot()["regions"] as List<*>).filter{(it as Map<*,*>)["id"] in setOf(profile,combined)}
    private fun refusal(bus:DiscoveryBus,reason:String,action:()->Unit) {
        val before=states(bus);assertFails{action()}
        val after=bus.snapshot();val last=(after["accesses"] as List<*>).last() as Map<*,*>
        assertEquals(reason,last["reason"]);assertEquals(false,last["accepted"]);assertEquals(before,states(bus))
    }

    @Test fun selectedPairBindsExactNewAllowanceAndUnchangedSingleZero() {
        val base=catalog.diagnosticProfiles.single{it.id==DispatchTailDiagnosticQualification.oldIds.first()}
        val oldSingle=catalog.singleReadDiagnosticProfiles.single{it.id==DispatchTailDiagnosticQualification.OLD_A300}
        val declaration=catalog.diagnosticProfiles.single{it.id==profile}
        val single=catalog.singleReadDiagnosticProfiles.single{it.id==combined}
        val evidence=listOf(DispatchTailDiagnosticQualification.EVIDENCE)
        assertTrue(DispatchTailDiagnosticQualification.same(base.data+mapOf("id" to profile,"successfulReadLimit" to limit,"evidence" to evidence),declaration.data))
        assertTrue(DispatchTailDiagnosticQualification.same(oldSingle.data+mapOf("id" to combined,"parentProfile" to profile,"evidence" to evidence),single.data))
        assertEquals(limit+1,RetainedWriteDiagnostic(1,limit+1).successfulReadLimit) // Shared ceiling; this old binding remains limit.
        assertFailsWith<IllegalArgumentException>{RetainedWriteDiagnostic(1,KernelCallDiagnosticQualification.COMPLETION_LIMIT+1)}
        assertFailsWith<IllegalArgumentException>{RetainedWriteDiagnostic(1,0)}
        val parent=checkNotNull(runtime(profile).createBus(context));val selected=checkNotNull(runtime().createBus(context))
        assertEquals(parent.snapshot()["regions"],(selected.snapshot()["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).filter{it["id"]!=combined})
        assertSharedA300Page(parent,selected)
        val retained=selected.regions.single{it.id==profile};val script=selected.regions.single{it.id==combined}
        assertEquals(RetainedWriteDiagnostic(1,limit),retained.retainedDiagnostic)
        assertEquals(address,retained.base);assertEquals(4,retained.length);assertEquals(setOf(4),retained.widths);assertNull(retained.initial)
        assertEquals(a300,script.base);assertEquals(4,script.length);assertEquals(listOf(0L),script.samples);assertEquals(DiscoveryStrategy.SCRIPT,script.strategy)
        assertNull(script.retainedDiagnostic);assertNull(script.initial);assertNull(script.fixedValue)
        for(id in listOf(profile,combined))assertNull(CatalogRuntime(catalog,"strict",Path.of("."),mapOf("discovery" to mapOf("profile" to id))).createBus(context))
        refusal(parent,"outside-selected-envelope"){MmioRouter(emptyList(),parent).read(a300,4)}
        for(id in listOf(profile,combined)) {
            assertFails{CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to id,"successfulReadLimit" to limit+1)))}
            assertFails{CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to id,"inputs" to mapOf(id to mapOf("strategy" to "fixed-input","value" to 0))))).createBus(context)}
        }
    }

    @Test fun exactly598016SuccessfulReadsThenRefusesWithoutConsumingEitherInput() {
        val bus=checkNotNull(runtime(capacity=limit+6).createBus(context));val router=MmioRouter(emptyList(),bus)
        assertEquals(0L,router.read(a300,4));router.write(address,4,1)
        repeat(limit){assertEquals(1L,router.read(address,4))}
        repeat(2){assertTrue(assertFails{router.read(address,4)}.message.orEmpty().contains("diagnostic-limit"))}
        assertFails{router.read(a300,4)}
        // Only one full journal materialization after the primitive access loop.
        val snapshot=bus.snapshot();val rows=snapshot["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        val regions=snapshot["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        val retained=regions.single{it["id"]==profile};val script=regions.single{it["id"]==combined}
        assertEquals(limit+5,rows.size);assertEquals(listOf("diagnostic-limit","diagnostic-limit","finite-input-missing-or-exhausted"),rows.takeLast(3).map{it["reason"]})
        assertTrue(rows.takeLast(3).all{it["accepted"]==false&&it["value"]==null&&it["before"]==null})
        assertEquals(limit,retained["successfulReads"]);assertEquals(limit,retained["successfulReadLimit"]);assertEquals(1L,retained["retainedValue"])
        assertEquals(1,script["sampleIndex"]);assertEquals(false,snapshot["truncated"])
        println("P83 exactly598016 retained1 reads then two non-consuming refusals;A300 single zero consumed once;${rows.size} complete journal rows")
    }

    @Test fun illegalAccessAndClockNeverConsumeOrClearTheSelectedPair() {
        val selected=runtime();val bus=checkNotNull(selected.createBus(context));val router=MmioRouter(emptyList(),bus)
        refusal(bus,"diagnostic-read-before-write"){router.read(address,4)}
        for(width in listOf(1,2,8))for(port in listOf(address,a300))refusal(bus,"unsupported-width"){router.read(port,width)}
        for(port in listOf(address,a300)) {
            refusal(bus,"unaligned"){router.read(port+1,4)}
            refusal(bus,"outside-selected-envelope"){router.read(port+4,4)}
        }
        for(value in listOf(0L,2L,0xffffffffL))refusal(bus,"diagnostic-write-value"){router.write(address,4,value)}
        refusal(bus,"policy-refuse-or-read-only"){router.write(a300,4,0)}
        router.write(address,4,1)
        for(value in listOf(0L,1L,2L))refusal(bus,"diagnostic-repeated-write"){router.write(address,4,value)}
        assertEquals(1L,router.read(address,4));assertEquals(0L,router.read(a300,4))
        refusal(bus,"finite-input-missing-or-exhausted"){router.read(a300,4)}
        val before=bus.snapshot();router.beforeInstruction(context.tick()+1)
        assertNull(router.nextDeadline(context.tick()+1));assertEquals(before,bus.snapshot());assertEquals(159731281L,context.tick())
        val accesses=selected.observation(bus)["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertTrue(accesses.filter{it["addressPhysical"]==address}.all{it["diagnosticRouting"]==catalog.diagnosticProfiles.single{d->d.id==profile}.data})
    }

    @Test fun completePairAndHistoricalProjectionRejectCoordinatedChangesAtTheIntendedGate() {
        val baseline=historicalDiagnosticCatalog(DispatchTailDiagnosticQualification.BASELINE)
        val data=DispatchExitDiagnosticQualification.priorData(DispatchFollowupDiagnosticQualification.dataBeforeDispatchFollowup(catalog.atCompletionQualification()),historicalDiagnosticCatalog(DispatchExitDiagnosticQualification.BASELINE))
        val rows=DispatchTailDiagnosticQualification.rows(data,"diagnosticProfiles")
        val singles=DispatchTailDiagnosticQualification.rows(data,"singleReadDiagnosticProfiles")
        assertTrue(DispatchTailDiagnosticQualification.same(baseline,DispatchTailDiagnosticQualification.priorData(data,baseline)))
        assertEquals(rows.filter{it["id"]!=profile},DispatchTailDiagnosticQualification.profilesBeforeDispatchTail(rows))
        assertEquals(DispatchTailDiagnosticQualification.profilesBeforeDispatchTail(rows),DispatchTailDiagnosticQualification.profilesBeforeDispatchTail(rows.filter{it["id"]!=profile}))
        for((key,value) in listOf("successfulReadLimit" to limit-1,"evidence" to listOf("changed"),"routeBase" to address+4,
            "routeEndExclusive" to address+8,"inventoryId" to "changed","automaticEvents" to true,"unknownKey" to 0)) {
            val changed=rows.map{if(it["id"]==profile)it+(key to value)else it}
            assertEquals("Complete dispatch-tail retained declaration differs",assertFailsWith<IllegalArgumentException>{DispatchTailDiagnosticQualification.profilesBeforeDispatchTail(changed)}.message)
            assertEquals("Complete dispatch-tail retained declaration differs",assertFailsWith<IllegalArgumentException>{Type3ParentDiagnosticQualification.profilesBeforeType3Parent(changed)}.message)
        }
        val coordinated=rows.map{if(it["id"] in setOf(profile,DispatchTailDiagnosticQualification.oldIds.first()))it+("routeBase" to address+4)else it}
        assertEquals("Complete dispatch-tail retained declaration differs",assertFailsWith<IllegalArgumentException>{DispatchTailDiagnosticQualification.profilesBeforeDispatchTail(coordinated)}.message)
        for(changed in listOf(rows+rows.single{it["id"]==profile},rows.filter{it["id"]!=DispatchTailDiagnosticQualification.oldIds.first()}))
            assertEquals("Dispatch-tail declaration set differs",assertFailsWith<IllegalArgumentException>{DispatchTailDiagnosticQualification.profilesBeforeDispatchTail(changed)}.message)
        for((key,value) in listOf("parentProfile" to DispatchTailDiagnosticQualification.oldIds.last(),"samples" to listOf(1),"routeBase" to a300+4,
            "evidence" to listOf("changed"),"dataEffect" to "ACK","unknownKey" to 0)) {
            val changed=data+("singleReadDiagnosticProfiles" to singles.map{if(it["id"]==combined)it+(key to value)else it})
            assertEquals("Complete dispatch-tail single-read declaration differs",assertFailsWith<IllegalArgumentException>{DispatchTailDiagnosticQualification.priorData(changed,baseline)}.message)
        }
        val bothRetained=data+("diagnosticProfiles" to rows.map{if(it["id"] in setOf(profile,DispatchTailDiagnosticQualification.oldIds.first()))it+("routeBase" to address+4)else it})
        assertEquals("Complete dispatch-tail retained declaration differs",assertFailsWith<IllegalArgumentException>{DispatchTailDiagnosticQualification.priorData(bothRetained,baseline)}.message)
        val bothScripts=data+("singleReadDiagnosticProfiles" to singles.map{it+("routeBase" to a300+4)})
        assertEquals("Complete dispatch-tail single-read declaration differs",assertFailsWith<IllegalArgumentException>{DispatchTailDiagnosticQualification.priorData(bothScripts,baseline)}.message)
        val oldChanged=data+("diagnosticProfiles" to rows.map{if(it["id"]==DispatchTailDiagnosticQualification.oldIds.last())it+("successfulReadLimit" to 540671)else it})
        assertEquals("Existing catalog data differs",assertFailsWith<IllegalArgumentException>{DispatchTailDiagnosticQualification.priorData(oldChanged,baseline)}.message)
    }
}
