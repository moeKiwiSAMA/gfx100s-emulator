package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class Selector30CandidateTest {
    private val root=Path.of(System.getProperty("catalogRoot"))
    private val id="request.power-peer.selector0x30"
    private val space="gfx100s-power-protocol-requests"
    private val history = HistoricalCatalogFixture(root)
    @Suppress("UNCHECKED_CAST") private fun mutable(c:AddressCatalog)=CatalogIO.json.readValue(CatalogIO.canonical(c.data),MutableMap::class.java) as MutableMap<String,Any?>
    @Suppress("UNCHECKED_CAST") private fun candidate(data:Map<String,Any?>)=rows(rows(data["devices"]).single {it["componentId"]=="power-peer"}["protocolCandidates"]).single() as MutableMap<String,Any?>
    @Test fun removing48And49RestoresEntireG7AndEveryHardwareDescriptor() {
        val c=protocolQualificationCatalog(root);val data=mutable(c);val s=candidate(data)
        assertEquals(listOf(37L,38L,14L,3L,47L,48L,49L,50L,15L,37L,38L),rows(s["requests"]).map {valueLong(it["selector"])})
        s["requests"]=rows(s["requests"]).filter {it["id"] !in setOf("request.power-peer.selector0x30","request.power-peer.selector0x31","request.power-peer.selector0x32","request.power-peer.selector0x0f","request.power-peer.selector0x25.write","request.power-peer.selector0x26.write")}
        val old=history.catalog("3244a3757cd2a2ca19291ad67934d6e64ee699cb")
        assertTrue(CatalogIO.canonical(old.data).contentEquals(CatalogIO.canonical(data)), "Complete historical catalog differs")
        assertEquals(32,c.componentIds.size)
        for(component in c.componentIds)assertContentEquals(CatalogIO.canonical(old.componentDescriptor(component)),CatalogIO.canonical(c.componentDescriptor(component)),component)
    }
    @Test fun request48IsAnUnknownNonbindingFull16Candidate() {
        val c=CatalogIO.readCompiled(CatalogIO.compiledBytes(CatalogCompiler.compile(root)))
        val r=c.protocolCandidateSpaces.single().requests.single {it.selector==48}
        assertEquals(id,r.id);assertEquals(16,r.wordBits);assertEquals("read",r.data["direction"])
        assertEquals("REQUEST_OBSERVED_PENDING",r.data["observationStatus"])
        assertEquals("UNKNOWN",r.data["name"]);assertNull(r.data["behaviorId"]);assertNull(obj(r.data["initial"])["value"])
        assertEquals(listOf(id+".raw"),r.fields.map {it.id})
        assertEquals(0,r.fields.single().bitOffset);assertEquals(16,r.fields.single().bitWidth)
        for(key in listOf("selector","width","direction"))assertEquals("DAT",obj(obj(r.data["provenance"])[key])["confidence"])
        for(key in listOf("unit","initial","access","behavior"))assertEquals("UNKNOWN",obj(obj(r.fields.single().data["provenance"])[key])["confidence"])
        for(entity in listOf(id,id+".raw").map {assertNotNull(c.byId(it))}) {
            for(key in listOf("permitted","readable","writable","cpuRoutable"))assertEquals(false,entity[key],key)
            assertEquals("NOT_IMPLEMENTED",entity["runtimeActivation"]);assertNull(entity["physicalAddress"]);assertNull(entity["componentId"])
        }
        val before=CatalogIO.canonical(c.data)
        for(direction in listOf("read","write")) {
            val q=c.queryLogicalCandidate(space,48,16,direction)
            assertEquals("UNASSIGNED_LOGICAL_REQUEST",q["classification"]);assertEquals(false,q["permitted"]);assertEquals(false,q["dynamicReadPerformed"])
        }
        assertEquals("UNKNOWN",c.queryLogicalCandidate(space,51)["classification"])
        assertContentEquals(before,CatalogIO.canonical(c.data))
    }
    @Test fun observationPinsActualMissing48AndCompleted47WithoutInventingAReply() {
        val relative="evidence/refactor/power2f-original-fresh-11/study/discovery-cap8-v5-gpio110-power2f-zero/original.generated.json"
        val outer=obj(CatalogIO.json.readValue(history.bytes("862b9e9ae5844ef062faed9f33166d4ffc21acdf",relative),Map::class.java))
        val original=obj(outer["original"]);assertEquals("power peer missing explicit input register48",original["status"])
        assertEquals(false,outer["completeBoot"]);assertEquals(false,outer["menuVerified"])
        val ledger=obj(obj(obj(obj(original["peripherals"])["power-peer"])["scriptedCandidateReads"])["selectors"])
        assertFalse(ledger.containsKey("48"));assertEquals(1L,valueLong(obj(ledger["47"])["consumed"]))
        assertEquals(2L,valueLong(obj(ledger["3"])["consumed"]));assertEquals(4L,valueLong(obj(ledger["3"])["remaining"]))
        val r=CatalogCompiler.compile(root).protocolCandidateSpaces.single().requests.single {it.selector==48}
        val observation=obj(obj(r.data["provenance"])["observation"])
        assertEquals("SIM",observation["confidence"]);assertEquals("862b9e9ae5844ef062faed9f33166d4ffc21acdf",obj(observation["source"])["commit"])
        assertEquals(relative,obj(observation["source"])["path"])
    }
    @Test fun noResetPermissionOrPartialFieldCanBeInsertedInto48() {
        val c=CatalogCompiler.compile(root)
        for(change in listOf(mapOf("wordBits" to 8),mapOf("initial" to mapOf("value" to 0,"source" to "UNKNOWN")),mapOf("permitted" to true))) {
            val d=mutable(c);val s=candidate(d);s["requests"]=rows(s["requests"]).map {if(it["id"]==id)it+change else it}
            assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(d)}
        }
        for(change in listOf(mapOf("bitWidth" to 15),mapOf("bitOffset" to 1),mapOf("access" to "read"))) {
            val d=mutable(c);val s=candidate(d);s["requests"]=rows(s["requests"]).map {if(it["id"]==id)it+mapOf("fields" to rows(it["fields"]).map {f->f+change})else it}
            assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(d)}
        }
    }
}
