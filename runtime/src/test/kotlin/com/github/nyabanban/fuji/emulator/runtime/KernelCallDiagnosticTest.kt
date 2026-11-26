package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogCompiler
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class KernelCallDiagnosticTest {
    private val q=KernelCallDiagnosticQualification
    private val profile=q.PROFILE;private val combined=q.COMBINED;private val limit=q.LIMIT
    private val address=0xfff4f01cL;private val a300=0xfff4a300L
    private val catalog by lazy{AddressCatalog.bundled()}
    private val context=object:DiscoveryContext {override fun core()=3;override fun tick()=162001107L;override fun pc()=0x5afe0L;override fun lr()=0x12919e0L}
    private fun runtime(id:String=combined,capacity:Int=128)=CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to id,"logCapacity" to capacity)))
    private fun states(bus:DiscoveryBus)=(bus.snapshot()["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).filter{it["id"] in setOf(profile,combined)}
    private fun refusal(bus:DiscoveryBus,reason:String,action:()->Unit) {
        val before=states(bus);assertFails{action()};val row=(bus.snapshot()["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).last()
        assertEquals(reason,row["reason"]);assertEquals(false,row["accepted"]);assertEquals(false,row["completed"]);assertEquals(before,states(bus))
    }

    @Test fun selectedPairBindsCompleteDeclarationsAndRejectsOverrides() {
        val parent=checkNotNull(runtime(profile).createBus(context));val selected=checkNotNull(runtime().createBus(context))
        val ordinary=checkNotNull(CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap()).createBus(context))
        assertEquals(ordinary.regions.map(q::binding),parent.regions.filter{it.id!=profile}.map(q::binding))
        assertEquals(parent.regions.map(q::binding),selected.regions.filter{it.id!=combined}.map(q::binding))
        val declaration=catalog.diagnosticProfiles.single{it.id==profile};val script=catalog.singleReadDiagnosticProfiles.single{it.id==combined}
        val old=catalog.diagnosticProfiles.single{it.id==DispatchFollowupDiagnosticQualification.PROFILE}
        val oldScript=catalog.singleReadDiagnosticProfiles.single{it.id==DispatchFollowupDiagnosticQualification.COMBINED}
        assertTrue(q.same(old.data+mapOf("id" to profile,"successfulReadLimit" to limit,"evidence" to listOf(q.EVIDENCE)),declaration.data))
        assertTrue(q.same(oldScript.data+mapOf("id" to combined,"parentProfile" to profile,"evidence" to listOf(q.EVIDENCE)),script.data))
        val retained=selected.regions.single{it.id==profile};val sample=selected.regions.single{it.id==combined}
        assertEquals(RetainedWriteDiagnostic(1,limit),retained.retainedDiagnostic);assertEquals(address,retained.base);assertEquals(4,retained.length)
        assertEquals(setOf(4),retained.widths);assertNull(retained.initial);assertEquals(listOf(0L),sample.samples);assertEquals(a300,sample.base)
        assertEquals(DiscoveryStrategy.SCRIPT,sample.strategy);assertNull(sample.retainedDiagnostic);assertNull(sample.initial);assertNull(sample.fixedValue)
        assertSharedA300Page(parent,selected)
        refusal(parent,"outside-selected-envelope"){MmioRouter(emptyList(),parent).read(a300,4)}
        for(id in listOf(profile,combined)) {
            assertNull(CatalogRuntime(catalog,"strict",Path.of("."),mapOf("discovery" to mapOf("profile" to id))).createBus(context))
            assertFailsWith<IllegalArgumentException>{CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to id,"successfulReadLimit" to limit+1)))}
            assertFailsWith<IllegalArgumentException>{CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to id,"inputs" to mapOf(id to mapOf("strategy" to "fixed-input","value" to 0))))).createBus(context)}
        }
        for(bound in listOf(0,-1,q.COMPLETION_LIMIT+1))assertFailsWith<IllegalArgumentException>{RetainedWriteDiagnostic(1,bound)}
        assertEquals(limit,RetainedWriteDiagnostic(1,limit).successfulReadLimit)
    }

    @Test fun exactly697344ReadsExhaustOnceWithoutClearOrSampleConsumption() {
        val bus=checkNotNull(runtime(capacity=limit+6).createBus(context));val router=MmioRouter(emptyList(),bus)
        router.write(address,4,1);assertEquals(0L,router.read(a300,4));repeat(limit){assertEquals(1L,router.read(address,4))}
        assertFails{router.read(address,4)};assertFails{router.read(a300,4)};assertFails{router.write(address,4,0)}
        val snapshot=bus.snapshot();val accesses=snapshot["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } };val regions=snapshot["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        val retained=regions.single{it["id"]==profile};val sample=regions.single{it["id"]==combined}
        assertEquals(limit+5,accesses.size);assertEquals(listOf("diagnostic-limit","finite-input-missing-or-exhausted","diagnostic-repeated-write"),accesses.takeLast(3).map{it["reason"]})
        assertEquals(listOf(null,null,0L),accesses.takeLast(3).map{it["value"]});assertTrue(accesses.takeLast(3).all{it["completed"]==false&&it["accepted"]==false})
        assertEquals(limit,retained["successfulReads"]);assertEquals(limit,retained["successfulReadLimit"]);assertEquals(1L,retained["retainedValue"])
        assertEquals(1,sample["sampleIndex"]);assertEquals(false,snapshot["truncated"])
        println("P121 exactly697344 retained1 reads;one limit refusal;single A300 sample;second write0 refused;${accesses.size} journal rows")
    }

    @Test fun illegalRequestsAndClockLeaveBothInputsUnchanged() {
        val bus=checkNotNull(runtime().createBus(context));val router=MmioRouter(emptyList(),bus)
        refusal(bus,"diagnostic-read-before-write"){router.read(address,4)}
        for(width in listOf(1,2,8))for(port in listOf(address,a300))refusal(bus,"unsupported-width"){router.read(port,width)}
        for(port in listOf(address,a300)){refusal(bus,"unaligned"){router.read(port+1,4)};refusal(bus,"outside-selected-envelope"){router.read(port+4,4)}}
        for(value in listOf(0L,2L,0xffffffffL))refusal(bus,"diagnostic-write-value"){router.write(address,4,value)}
        refusal(bus,"policy-refuse-or-read-only"){router.write(a300,4,0)};router.write(address,4,1)
        for(value in listOf(0L,1L,2L))refusal(bus,"diagnostic-repeated-write"){router.write(address,4,value)}
        assertEquals(1L,router.read(address,4));assertEquals(0L,router.read(a300,4));refusal(bus,"finite-input-missing-or-exhausted"){router.read(a300,4)}
        val before=bus.snapshot();router.beforeInstruction(context.tick()+1)
        assertNull(router.nextDeadline(context.tick()+1));assertEquals(before,bus.snapshot());assertEquals(162001107L,context.tick())
    }

    @Test fun strictNewPairProjectionPreservesWholePriorCatalogAndRejectsChangedFields() {
        val baseline=historicalDiagnosticCatalog(q.BASELINE);val data=catalog.atCompletionQualification();val rows=q.rows(data,"diagnosticProfiles");val singles=q.rows(data,"singleReadDiagnosticProfiles")
        assertTrue(q.same(baseline,q.priorData(data,baseline)));assertEquals(baseline,q.dataBeforeKernelCall(baseline))
        assertEquals(rows.filter{it["id"] !in setOf(profile,q.LCD_PROFILE,q.COMPLETION_PROFILE)},q.profilesBeforeKernelCall(rows))
        for((key,value)in listOf("successfulReadLimit" to limit-1,"evidence" to listOf("changed"),"routeBase" to address+4,"routeEndExclusive" to address+8,
            "inventoryId" to "changed","automaticEvents" to true,"unknownKey" to 0)) {
            val changed=rows.map{if(it["id"]==profile)it+(key to value)else it}
            assertEquals("Complete kernel-call retained declaration differs",assertFailsWith<IllegalArgumentException>{q.profilesBeforeKernelCall(changed)}.message)
            assertEquals("Complete kernel-call retained declaration differs",assertFailsWith<IllegalArgumentException>{Type3ParentDiagnosticQualification.profilesBeforeType3Parent(changed)}.message)
        }
        for((key,value)in listOf("parentProfile" to q.oldIds.first(),"samples" to listOf(1),"routeBase" to a300+4,"evidence" to listOf("changed"),"dataEffect" to "ACK","unknownKey" to 0)) {
            val changed=data+("singleReadDiagnosticProfiles" to singles.map{if(it["id"]==combined)it+(key to value)else it})
            assertEquals("Complete kernel-call single-read declaration differs",assertFailsWith<IllegalArgumentException>{q.priorData(changed,baseline)}.message)
        }
        for(changed in listOf(rows+rows.single{it["id"]==profile},rows.filter{it["id"]!=q.oldIds.first()},rows.filter{it["id"]!=profile}))
            assertEquals("Kernel-call declaration set differs",assertFailsWith<IllegalArgumentException>{q.priorData(data+("diagnosticProfiles" to changed),baseline)}.message)
        assertEquals("Kernel-call single-read set differs",assertFailsWith<IllegalArgumentException>{q.priorData(data+("singleReadDiagnosticProfiles" to singles.filter{it["id"]!=combined}),baseline)}.message)
        val coordinated=data+("diagnosticProfiles" to rows.map{if(it["id"] in setOf(profile,q.oldIds.last()))it+("routeBase" to address+4)else it})
        assertEquals("Complete kernel-call retained declaration differs",assertFailsWith<IllegalArgumentException>{q.priorData(coordinated,baseline)}.message)
        val oldChanged=data+("diagnosticProfiles" to rows.map{if(it["id"]==q.oldIds.last())it+("successfulReadLimit" to 696319)else it})
        assertEquals("Existing catalog data differs",assertFailsWith<IllegalArgumentException>{q.priorData(oldChanged,baseline)}.message)
        for((key,value)in listOf("initialValue" to 0,"physicalAddress" to address,"reset" to 0,"dataEffect" to "ACK","repeatWrite" to "allow","successfulReadLimit" to q.COMPLETION_LIMIT+1))
            assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(data+("diagnosticProfiles" to rows.map{if(it["id"]==profile)it+(key to value)else it}))}
    }
}
