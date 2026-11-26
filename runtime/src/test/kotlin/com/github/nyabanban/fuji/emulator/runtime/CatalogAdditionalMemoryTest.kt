package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class CatalogAdditionalMemoryTest {
    private val catalog=AddressCatalog.bundled()
    private val memoryProfile="flashload-request-buffer-experimental-v24"
    private val base=catalog.memoryProfiles.getValue(memoryProfile)
    private val addition=MemoryRegion("method4-output-input",0x03bf3000L,0x0466c000L-0x03bf3000L,3)

    private fun runtime(mode:String="discovery")=CatalogRuntime(catalog,mode,Path.of("."),emptyMap())
    private fun bind(runtime:CatalogRuntime,profile:String=AdditionalRamRegions.PROFILE,regions:List<Map<String,Any?>> = base) {
        runtime.bindPorts(emptyList(),memoryProfile,regions,additionalRamProfile=profile)
    }
    private fun query(runtime:CatalogRuntime,address:Long=addition.address,direction:String="read",width:Int=4)=runtime.query(
        mapOf("address" to address.toString(),"direction" to direction,"width" to width.toString()))

    @Test fun preflightIsPureAndSelectionPublishesOnlyExplicitHypothesisMemoryOnce() {
        val runtime=runtime()
        bind(runtime)
        val frozenData=catalog.data
        val before=query(runtime)
        val original=query(runtime,0)
        val selectionBefore=runtime.additionalMemorySelection()
        assertEquals(true,selectionBefore["enabled"])
        assertEquals(false,selectionBefore["selected"])
        assertEquals(emptyList<Any>(),selectionBefore["regions"])
        assertEquals("UNASSIGNED",before["classification"])
        repeat(2) { runtime.validateAdditionalMemory(listOf(addition)) }
        assertEquals(before,query(runtime))
        assertEquals(selectionBefore,runtime.additionalMemorySelection())
        runtime.selectAdditionalMemory(listOf(addition))
        val selection=runtime.additionalMemorySelection()
        assertEquals(AdditionalRamRegions.PROFILE,selection["profile"])
        assertEquals(true,selection["selected"])
        assertEquals("HYPOTHESIS",selection["confidence"])
        assertEquals(false,selection["staticDictionaryDeclaration"])
        assertEquals(memoryProfile,selection["baseMemoryProfile"])
        val selected=(selection["regions"] as List<*>).single() as Map<*,*>
        assertEquals(addition.id,selected["id"])
        assertEquals(addition.address,selected["base"])
        assertEquals(addition.address+addition.size,selected["endExclusive"])
        assertEquals(3L,selected["permissions"])
        for(direction in listOf("read","write")) {
            val result=query(runtime,direction=direction)
            assertEquals("RAM",result["classification"])
            assertEquals(true,result["permitted"])
            assertEquals("HYPOTHESIS",result["evidenceClass"])
            assertEquals("HYPOTHESIS",result["confidence"])
            assertEquals(false,result["staticDictionaryDeclaration"])
            assertEquals("owner-selected-additional-memory",result["policySource"])
            assertEquals("active-profile-with-additional-ram",result["memoryView"])
            assertEquals(false,result["deviceReadPerformed"])
            assertEquals(false,result["guestStateChanged"])
        }
        assertEquals(false,query(runtime,direction="execute")["permitted"])
        assertEquals("RAM permission denied",query(runtime,direction="execute")["refusalReason"])
        assertEquals("CROSS_BOUNDARY",query(runtime,addition.address-2,width=4)["classification"])
        assertEquals("CROSS_BOUNDARY",query(runtime,addition.address+addition.size-2,width=4)["classification"])
        assertEquals("UNASSIGNED",query(runtime,addition.address+addition.size)["classification"])
        assertEquals(original.filterKeys { it!="memoryView" },query(runtime,0).filterKeys { it!="memoryView" })
        assertEquals("DICTIONARY",query(runtime,0)["evidenceClass"])
        assertEquals("UNKNOWN",runtime.query(mapOf("id" to addition.id))["status"])
        assertSame(frozenData,catalog.data)
        assertEquals(base,catalog.memoryProfiles.getValue(memoryProfile))
        assertFailsWith<IllegalStateException> { runtime.selectAdditionalMemory(listOf(addition.copy(id="second",address=0x10000000L))) }
        assertFailsWith<IllegalStateException> { runtime.validateAdditionalMemory(listOf(addition)) }
        assertEquals(selection,runtime.additionalMemorySelection())
    }

    @Test fun extensionChecksTheWholeUnionBeforePublishingNewCoverage() {
        val runtime = runtime()
        bind(runtime)
        runtime.selectAdditionalMemory(listOf(addition))
        val before = runtime.additionalMemorySelection()
        val slot = MemoryRegion("osd-fb-slot0", 0x059ed000, 0x00300000, 3)
        for (rows in listOf(listOf(slot), listOf(addition), listOf(addition.copy(size = 4096), slot),
            listOf(addition, slot, slot.copy(id = "flash", address = 0xf0000000)))) {
            assertFailsWith<IllegalArgumentException> { runtime.extendAdditionalMemory(rows) { fail("mapped invalid plan") } }
        }
        assertFailsWith<IllegalStateException> { runtime.extendAdditionalMemory(listOf(addition, slot)) { error("map failed") } }
        assertEquals(before, runtime.additionalMemorySelection())
        assertEquals("UNASSIGNED", query(runtime, slot.address)["classification"])
        runtime.extendAdditionalMemory(listOf(slot, addition)) {}
        assertEquals("RAM", query(runtime, addition.address)["classification"])
        assertEquals(true, query(runtime, slot.address, "write")["permitted"])
        assertEquals(false, query(runtime, slot.address, "execute")["permitted"])
        assertEquals("UNASSIGNED", query(runtime, slot.address + slot.size)["classification"])
    }

    @Test fun activeCoverageIncludesAdditionWhileExplicitStaticProfileRemainsFrozen() {
        val runtime=runtime()
        bind(runtime)
        runtime.selectAdditionalMemory(listOf(addition))
        val parameters=mapOf("coverage" to "true","base" to addition.address.toString(),"endExclusive" to (addition.address+addition.size).toString())
        val active=(runtime.query(parameters)["coverage"] as List<*>).single() as Map<*,*>
        assertEquals("RAM",active["classification"])
        assertEquals("HYPOTHESIS",active["evidenceClass"])
        assertEquals(false,active["staticDictionaryDeclaration"])
        assertEquals(addition.id,(active["region"] as Map<*,*>)["id"])
        val static=(runtime.query(parameters+("memoryProfile" to memoryProfile))["coverage"] as List<*>).single() as Map<*,*>
        assertEquals("UNASSIGNED",static["classification"])
        val staticQuery=runtime.query(mapOf("address" to addition.address.toString(),"memoryProfile" to memoryProfile))
        assertEquals("UNASSIGNED",staticQuery["classification"])
        assertEquals("explicit-static-profile",staticQuery["memoryView"])
        assertEquals("DICTIONARY",staticQuery["evidenceClass"])
    }

    @Test fun defaultDisabledAndInvalidProfileOrStrictModeCannotWeakenOriginalBinding() {
        val unbound=runtime()
        assertFailsWith<IllegalStateException> { unbound.validateAdditionalMemory(listOf(addition)) }
        assertFailsWith<IllegalStateException> { unbound.selectAdditionalMemory(listOf(addition)) }
        val omitted=runtime()
        omitted.bindPorts(emptyList(),memoryProfile,base)
        val explicitDefault=runtime()
        bind(explicitDefault,"")
        assertEquals(query(omitted),query(explicitDefault))
        assertEquals(false,omitted.additionalMemorySelection()["enabled"])
        assertFailsWith<IllegalArgumentException> { omitted.validateAdditionalMemory(listOf(addition)) }
        assertFailsWith<IllegalArgumentException> { omitted.selectAdditionalMemory(listOf(addition)) }
        for((mode,profile) in listOf("strict" to AdditionalRamRegions.PROFILE,"discovery" to "unknown","discovery" to " ")) {
            val rejected=runtime(mode)
            assertFailsWith<IllegalArgumentException> { bind(rejected,profile) }
            assertEquals(false,rejected.additionalMemorySelection()["enabled"])
            bind(rejected,"")
            assertEquals("UNASSIGNED",query(rejected)["classification"])
        }
        for(regions in listOf(base.dropLast(1),base.mapIndexed { index,region ->
            if(index==0)region+("permissions" to 3)else region
        },base+mapOf("id" to addition.id,"address" to addition.address,"size" to addition.size,"permissions" to 3))) {
            val rejected=runtime()
            val failure=assertFailsWith<IllegalArgumentException> { bind(rejected,regions=regions) }
            assertEquals("Actual RAM differs from dictionary profile",failure.message)
            bind(rejected)
            assertEquals(false,rejected.additionalMemorySelection()["selected"])
        }
    }

    @Test fun completePreflightRejectsCatalogConflictsAndInvalidRangesWithoutPartialSelection() {
        val window=catalog.windows.first { it.kind!="FLASH_APERTURE" }
        val alternative=catalog.allWindows.first { it !in catalog.windows }
        val candidate=catalog.candidates.first()
        val diagnostic=catalog.singleReadDiagnosticProfiles.first()
        val conflicts=listOf(
            window.base,alternative.base,candidate.base,diagnostic.base,0xfff00000L,0xf0000000L)
        val runtime=runtime()
        bind(runtime)
        val before=runtime.additionalMemorySelection()
        for(address in conflicts) {
            val region=MemoryRegion("catalog-overlap",address and -4096L,4096,3)
            val failure=assertFailsWith<IllegalArgumentException> { runtime.validateAdditionalMemory(listOf(addition,region)) }
            assertTrue(failure.message.orEmpty().startsWith("Additional RAM overlaps catalog region:"),failure.message)
            assertFailsWith<IllegalArgumentException> { runtime.selectAdditionalMemory(listOf(addition,region)) }
            assertEquals(before,runtime.additionalMemorySelection())
            assertEquals("UNASSIGNED",query(runtime)["classification"])
        }
        val invalid=listOf(
            emptyList(),
            listOf(addition.copy(permissions=7)),
            listOf(addition.copy(permissions=1)),
            listOf(addition.copy(address=addition.address+1)),
            listOf(addition.copy(size=4097)),
            listOf(addition.copy(size=0)),
            listOf(addition.copy(address=-4096)),
            listOf(addition.copy(address=0xfffff000L,size=8192)),
            listOf(addition.copy(address=0x10000000L,size=768L*1024*1024+4096)),
            listOf(addition.copy(id="loader")),
            listOf(addition.copy(address=0,size=4096)),
            listOf(addition,addition.copy(id="overlap")),
            listOf(addition,addition.copy(address=0x10000000L)),
            List(41) { index -> MemoryRegion("too-many-$index",0x10000000L+index*4096L,4096,3) })
        for(regions in invalid) {
            assertFailsWith<IllegalArgumentException> { runtime.validateAdditionalMemory(regions) }
            assertFailsWith<IllegalArgumentException> { runtime.selectAdditionalMemory(regions) }
            assertEquals(before,runtime.additionalMemorySelection())
        }
        runtime.selectAdditionalMemory(listOf(addition))
        assertEquals(true,runtime.additionalMemorySelection()["selected"])
    }

    @Test fun onlySmallNonExecutableRangesCanExtendSixteenSelectedRegions() {
        val runtime = runtime()
        bind(runtime, AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        val initial = List(36) { index -> MemoryRegion("existing-$index", 0x10000000L + index * 4096L, 4096, 3) }
        runtime.selectAdditionalMemory(initial)
        val before = runtime.additionalMemorySelection()
        val extra = MemoryRegion("extra", 0x10040000, 8192, 3)
        for (invalid in listOf(extra.copy(permissions = 7), extra.copy(size = 12288))) {
            val failure = assertFailsWith<IllegalArgumentException> {
                runtime.extendAdditionalMemory(listOf(invalid) + initial) { error("premature publication") }
            }
            assertTrue(failure.message.orEmpty().contains("RW/NX pages"))
            assertEquals(before, runtime.additionalMemorySelection())
        }
        val pages = List(4) { index -> extra.copy(id = "extra-$index", address = extra.address + index * 8192L) }
        runtime.extendAdditionalMemory(pages + initial) {}
        assertEquals(40, (runtime.additionalMemorySelection()["regions"] as List<*>).size)
    }

    @Test fun baseAndSelectedInputsAreFrozenAndKnownInactiveRulesStayFirst() {
        val runtime=runtime()
        val mutableBase=base.map { it.toMutableMap() }.toMutableList()
        bind(runtime,regions=mutableBase)
        mutableBase.first()["address"]=addition.address
        mutableBase.clear()
        val requested=mutableListOf(addition.copy(id="later",address=0x10000000L,size=4096),addition)
        val knownAddress=0xfffc6244L
        val knownBefore=query(runtime,knownAddress,"write")
        runtime.selectAdditionalMemory(requested)
        requested.clear()
        val receipt=runtime.additionalMemorySelection()
        val selected=receipt["regions"] as List<*>
        assertEquals(listOf(addition.address,0x10000000L),selected.map { (it as Map<*,*>)["base"] })
        assertFailsWith<UnsupportedOperationException> { (selected as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (selected.first() as MutableMap<*,*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (receipt as MutableMap<*,*>).clear() }
        assertEquals("RAM",query(runtime)["classification"])
        assertEquals("RAM",query(runtime,0x10000000L)["classification"])
        val knownAfter=query(runtime,knownAddress,"write")
        assertEquals(knownBefore.filterKeys { it!="memoryView" },knownAfter.filterKeys { it!="memoryView" })
        assertEquals(false,knownAfter["permitted"])
        assertEquals("DICTIONARY",knownAfter["evidenceClass"])
    }

    @Test fun oldJvmBindSignatureRemainsAvailableForReflectiveCallers() {
        val method=CatalogRuntime::class.java.getMethod("bindPorts",List::class.java,String::class.java,List::class.java,Map::class.java)
        val runtime=runtime()
        method.invoke(runtime,emptyList<Any>(),memoryProfile,base,emptyMap<String,String>())
        assertEquals(false,runtime.additionalMemorySelection()["enabled"])
        assertEquals("UNASSIGNED",query(runtime)["classification"])
    }

    @Test fun execCapableProfileAllowsRwNxAndRwxWhileRejectingOtherPermissionsAndStrictMode() {
        assertFailsWith<IllegalArgumentException> {
            bind(runtime("strict"),AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        }
        val execRuntime=runtime()
        bind(execRuntime,AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        assertEquals(true,execRuntime.additionalMemorySelection()["enabled"])
        assertEquals(AdditionalRamRegions.EXEC_CAPABLE_PROFILE,execRuntime.additionalMemorySelection()["profile"])
        for(badPermissions in listOf(1,2,4,5,6)) {
            assertFailsWith<IllegalArgumentException> {
                execRuntime.validateAdditionalMemory(listOf(addition.copy(permissions=badPermissions)))
            }
        }
        val rwx=addition.copy(permissions=7)
        val rwnx=MemoryRegion("scratch-rw-nx",0x10000000L,4096,3)
        execRuntime.validateAdditionalMemory(listOf(rwx,rwnx))
        execRuntime.selectAdditionalMemory(listOf(rwx,rwnx))
        for(direction in listOf("read","write","execute")) {
            val rwxQuery=query(execRuntime,rwx.address,direction)
            assertEquals("RAM",rwxQuery["classification"])
            assertEquals(true,rwxQuery["permitted"])
            assertEquals("HYPOTHESIS",rwxQuery["evidenceClass"])
            assertEquals(false,rwxQuery["staticDictionaryDeclaration"])
        }
        assertEquals(true,query(execRuntime,rwx.address,"execute")["permitted"])
        assertEquals(false,query(execRuntime,rwnx.address,"execute")["permitted"])
        assertEquals(true,query(execRuntime,rwnx.address,"write")["permitted"])
        assertEquals("UNASSIGNED",execRuntime.query(mapOf("address" to rwx.address.toString(),"memoryProfile" to memoryProfile))["classification"])
    }
}
