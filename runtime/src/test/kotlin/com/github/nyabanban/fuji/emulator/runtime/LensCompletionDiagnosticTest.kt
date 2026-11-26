package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class LensCompletionDiagnosticTest {
    private val q=KernelCallDiagnosticQualification
    private val catalog by lazy { AddressCatalog.bundled() }
    private val context=object:DiscoveryContext {override fun core()=3;override fun tick()=162021587L;override fun pc()=0x5afe0L;override fun lr()=0x12919e0L}
    private fun bus(profile:String=q.COMPLETION_COMBINED,capacity:Int=16)=checkNotNull(CatalogRuntime(catalog,"discovery",Path.of("."),
        mapOf("discovery" to mapOf("profile" to profile,"logCapacity" to capacity))).createBus(context))
    private val port=0xfff4f01cL
    private val a300=0xfff4a300L

    @Test fun selectedPairUsesExistingRoutesAndPreservesOldLimit() {
        val old=bus(q.LCD_COMBINED);val current=bus()
        assertEquals(q.LCD_LIMIT,old.regions.single{it.id==q.LCD_PROFILE}.retainedDiagnostic!!.successfulReadLimit)
        assertEquals(q.COMPLETION_LIMIT,current.regions.single{it.id==q.COMPLETION_PROFILE}.retainedDiagnostic!!.successfulReadLimit)
        assertEquals(old.pageRanges(),current.pageRanges())
        val oldRows=old.regions.filter{it.id !in setOf(q.LCD_PROFILE,q.LCD_COMBINED)}.map(q::binding)
        assertEquals(oldRows,current.regions.filter{it.id !in setOf(q.COMPLETION_PROFILE,q.COMPLETION_COMBINED)}.map(q::binding))
        assertNull(CatalogRuntime(catalog,"strict",Path.of("."),mapOf("discovery" to mapOf("profile" to q.COMPLETION_COMBINED))).createBus(context))
        val source=q.dataBeforeCompletion(catalog.data)
        assertEquals(q.rows(catalog.data,"diagnosticProfiles").filter{it["id"]!=q.COMPLETION_PROFILE},source["diagnosticProfiles"])
        val rows=q.rows(catalog.data,"diagnosticProfiles")
        val changed=rows.map{if(it["id"]==q.COMPLETION_PROFILE)it+mapOf("successfulReadLimit" to q.COMPLETION_LIMIT-1) else it}
        assertFailsWith<IllegalArgumentException>{q.profilesBeforeCompletion(changed)}
        assertFailsWith<IllegalArgumentException>{q.dataBeforeCompletion(catalog.data+mapOf("singleReadDiagnosticProfiles" to q.rows(catalog.data,"singleReadDiagnosticProfiles").filter{it["id"]!=q.COMPLETION_COMBINED}))}
    }

    @Test fun extendedHorizonExhaustsWithoutClearOrExtraA300Sample() {
        val selected=bus(capacity=q.COMPLETION_LIMIT+6);val router=MmioRouter(emptyList(),selected)
        router.write(port,4,1);assertEquals(0L,router.read(a300,4))
        repeat(q.COMPLETION_LIMIT){assertEquals(1L,router.read(port,4))}
        assertFails{router.read(port,4)};assertFails{router.read(a300,4)};assertFails{router.write(port,4,0)}
        val snapshot=selected.snapshot()
        val accesses=snapshot["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertEquals(listOf("diagnostic-limit","finite-input-missing-or-exhausted","diagnostic-repeated-write"),accesses.takeLast(3).map{it["reason"]})
        assertTrue(accesses.takeLast(3).all{it["completed"]==false&&it["accepted"]==false})
        val retained=(snapshot["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).single{it["id"]==q.COMPLETION_PROFILE}
        assertEquals(q.COMPLETION_LIMIT,retained["successfulReads"]);assertEquals(1L,retained["retainedValue"])
        val sample=(snapshot["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).single{it["id"]==q.COMPLETION_COMBINED}
        assertEquals(1,sample["sampleIndex"]);assertEquals(false,snapshot["truncated"])
    }

    @Test fun illegalWidthsWritesAndTimeDoNotMakeProgress() {
        val selected=bus();val router=MmioRouter(emptyList(),selected)
        assertFails{router.read(port,4)};assertFails{router.write(port,4,0)}
        for(width in listOf(1,2,8))assertFails{router.write(port,width,1)}
        router.write(port,4,1)
        for(value in listOf(0L,1L,2L))assertFails{router.write(port,4,value)}
        val before=selected.snapshot();router.beforeInstruction(context.tick()+1000000)
        assertEquals(before,selected.snapshot());assertNull(router.nextDeadline(context.tick()))
        assertEquals(1L,router.read(port,4));assertEquals(0L,router.read(a300,4))
    }
}
