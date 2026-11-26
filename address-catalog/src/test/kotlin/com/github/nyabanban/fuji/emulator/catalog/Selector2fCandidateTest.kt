package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class Selector2fCandidateTest {
    private val root=Path.of(System.getProperty("catalogRoot"))
    private val id="request.power-peer.selector0x2f"
    private val space="gfx100s-power-protocol-requests"
    private val history = HistoricalCatalogFixture(root)
    private fun frozen() = history.catalog("059e43841a2d8e5ef6e29e4f9f594f7c3550bfb7")
    @Suppress("UNCHECKED_CAST") private fun mutable(c:AddressCatalog)=CatalogIO.json.readValue(CatalogIO.canonical(c.data),MutableMap::class.java) as MutableMap<String,Any?>
    @Suppress("UNCHECKED_CAST") private fun candidate(data:Map<String,Any?>)=rows(rows(data["devices"]).single {it["componentId"]=="power-peer"}["protocolCandidates"]).single() as MutableMap<String,Any?>
    @Test fun request47OnlyAddsAnUnknownRaw16CandidateAndDoesNotBindHardware() {
        val c=CatalogIO.readCompiled(CatalogIO.compiledBytes(CatalogCompiler.compile(root)))
        val r=c.protocolCandidateSpaces.single().requests.single {it.selector==47}
        assertEquals(id,r.id);assertEquals(16,r.wordBits);assertEquals("read",r.data["direction"])
        assertEquals(listOf(id+".raw"),r.fields.map {it.id});assertEquals(0,r.fields.single().bitOffset);assertEquals(16,r.fields.single().bitWidth)
        assertEquals("REQUEST_OBSERVED_PENDING",r.data["observationStatus"])
        assertEquals("UNKNOWN",r.data["name"]);assertNull(r.data["behaviorId"]);assertNull(obj(r.data["initial"])["value"])
        assertEquals(65535L,valueLong(obj(r.data["masks"])["unknown"]))
        for(key in listOf("selector","width","direction"))assertEquals("DAT",obj(obj(r.data["provenance"])[key])["confidence"])
        val observation=obj(obj(r.data["provenance"])["observation"])
        assertEquals("SIM",observation["confidence"]);assertTrue(observation["limitation"].toString().contains("no returned value"))
        val before=CatalogIO.canonical(c.data)
        for(entityId in listOf(id,id+".raw")) {
            val entity=assertNotNull(c.byId(entityId))
            for(key in listOf("permitted","readable","writable","cpuRoutable"))assertEquals(false,entity[key],key)
            assertEquals("NOT_IMPLEMENTED",entity["runtimeActivation"]);assertNull(entity["physicalAddress"]);assertNull(entity["componentId"])
            assertEquals("UNKNOWN",obj(c.property(entityId,"deviceOwner")["provenance"])["confidence"])
        }
        for(key in listOf("unit","initial","access","behavior"))assertEquals("UNKNOWN",obj(obj(r.fields.single().data["provenance"])[key])["confidence"])
        for(direction in listOf("read","write")) {
            val q=c.queryLogicalCandidate(space,47,16,direction)
            assertEquals("UNASSIGNED_LOGICAL_REQUEST",q["classification"]);assertEquals(false,q["permitted"]);assertEquals(false,q["dynamicReadPerformed"])
        }
        assertEquals("UNKNOWN",c.queryLogicalCandidate(space,51)["classification"])
        assertEquals("UNASSIGNED",c.query(space,47,2,"read").classification)
        assertContentEquals(before,CatalogIO.canonical(c.data))
    }
    @Test fun removing47AndLaterCandidateRestoresTheEntireG6ModelIncludingEveryPortDescriptor() {
        val c=protocolQualificationCatalog(root);val data=mutable(c);val s=candidate(data);val requests=rows(s["requests"])
        assertEquals(listOf(37L,38L,14L,3L,47L,48L,49L,50L,15L,37L,38L),requests.map {valueLong(it["selector"])})
        s["requests"]=requests.filter {it["id"] !in setOf("request.power-peer.selector0x2f","request.power-peer.selector0x30","request.power-peer.selector0x31","request.power-peer.selector0x32","request.power-peer.selector0x0f","request.power-peer.selector0x25.write","request.power-peer.selector0x26.write")}
        val old=frozen();assertTrue(CatalogIO.canonical(old.data).contentEquals(CatalogIO.canonical(data)), "Complete historical catalog differs")
        assertEquals(32,c.componentIds.size)
        for(component in c.componentIds)assertContentEquals(CatalogIO.canonical(old.componentDescriptor(component)),CatalogIO.canonical(c.componentDescriptor(component)),component)
    }
    @Test fun original47ObservationIsMissingInputAfterOnlyTwoSelector3Samples() {
        val relative="evidence/refactor/power03-ring-original-fresh-10/study/discovery-cap8-v5-gpio110-power03-ring-six/original.generated.json"
        val bytes = history.bytes("4faef9700713b64d6fc0544fb278104fdbd8b82b", relative)
        val outer=obj(CatalogIO.json.readValue(bytes,Map::class.java));val original=obj(outer["original"])
        assertEquals("power peer missing explicit input register47",original["status"])
        assertEquals(false,outer["completeBoot"]);assertEquals(false,outer["menuVerified"])
        val power=obj(obj(original["peripherals"])["power-peer"])
        assertFalse(rows(obj(power["inputs"])["candidate_reads"]).any {valueLong(it["selector"])==47L})
        val ledger=obj(obj(power["scriptedCandidateReads"])["selectors"])
        assertFalse(ledger.containsKey("47"));assertEquals(2L,valueLong(obj(ledger["3"])["consumed"]));assertEquals(4L,valueLong(obj(ledger["3"])["remaining"]))
        val c=CatalogCompiler.compile(root);val observation=obj(obj(c.protocolCandidateSpaces.single().requests.single {it.selector==47}.data["provenance"])["observation"])
        assertEquals("4faef9700713b64d6fc0544fb278104fdbd8b82b",obj(observation["source"])["commit"]);assertEquals(relative,obj(observation["source"])["path"])
    }
    @Test fun request47CannotInventAResetValuePermissionOrPartialRawFieldCoverage() {
        val c=CatalogCompiler.compile(root)
        for(change in listOf(mapOf("wordBits" to 8),mapOf("initial" to mapOf("value" to 0,"source" to "UNKNOWN")),mapOf("permitted" to true))) {
            val d=mutable(c);val s=candidate(d);s["requests"]=rows(s["requests"]).map {if(it["id"]==id)it+change else it}
            assertFailsWith<IllegalArgumentException> {CatalogCompiler.validate(d)}
        }
        for(change in listOf(mapOf("bitWidth" to 15),mapOf("bitOffset" to 1),mapOf("bitOffset" to Long.MAX_VALUE),mapOf("access" to "read"))) {
            val d=mutable(c);val s=candidate(d);s["requests"]=rows(s["requests"]).map {if(it["id"]==id)it+mapOf("fields" to rows(it["fields"]).map {f->f+change})else it}
            assertFailsWith<IllegalArgumentException> {CatalogCompiler.validate(d)}
        }
    }
}
