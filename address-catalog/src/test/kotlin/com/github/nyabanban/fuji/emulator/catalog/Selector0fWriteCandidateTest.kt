package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class Selector0fWriteCandidateTest {
    private val root=Path.of(System.getProperty("catalogRoot"))
    private val id="request.power-peer.selector0x0f"
    private val history = HistoricalCatalogFixture(root)
    @Suppress("UNCHECKED_CAST") private fun mutable(c:AddressCatalog)=CatalogIO.json.readValue(CatalogIO.canonical(c.data),MutableMap::class.java) as MutableMap<String,Any?>
    @Suppress("UNCHECKED_CAST") private fun space(data:Map<String,Any?>)=rows(rows(data["devices"]).single {it["componentId"]=="power-peer"}["protocolCandidates"]).single() as MutableMap<String,Any?>
    @Test fun removingOnly15RestoresEntireG10AndAll32HardwareDescriptors() {
        val c=protocolQualificationCatalog(root);val data=mutable(c);val s=space(data)
        assertEquals(listOf(37L,38L,14L,3L,47L,48L,49L,50L,15L,37L,38L),rows(s["requests"]).map {valueLong(it["selector"])})
        s["requests"]=rows(s["requests"]).filter {it["id"] !in setOf(id,"request.power-peer.selector0x25.write","request.power-peer.selector0x26.write")}
        val old=history.catalog("27bc998b56eb1f1190f0945c55d248a9356de365")
        assertTrue(CatalogIO.canonical(old.data).contentEquals(CatalogIO.canonical(data)), "Complete historical catalog differs");assertEquals(32,c.componentIds.size)
        for(component in c.componentIds)assertContentEquals(CatalogIO.canonical(old.componentDescriptor(component)),CatalogIO.canonical(c.componentDescriptor(component)),component)
    }
    @Test fun attemptedFull16WriteKeepsAllPhysicalPropertiesUnknownAndCannotRoute() {
        val c=CatalogIO.readCompiled(CatalogIO.compiledBytes(CatalogCompiler.compile(root)));val r=c.protocolCandidateSpaces.single().requests.single {it.selector==15}
        assertEquals("write",r.data["direction"]);assertEquals(16,r.wordBits);assertEquals(id,r.id);assertEquals(listOf(id+".raw"),r.fields.map {it.id})
        assertEquals(0,r.fields.single().bitOffset);assertEquals(16,r.fields.single().bitWidth);assertEquals("UNKNOWN",r.fields.single().data["access"])
        assertEquals(mapOf("preserve" to 0L,"prohibited" to 0L,"unknown" to 65535L,"writable" to 0L),obj(r.data["masks"]).mapValues {valueLong(it.value)})
        assertNull(obj(r.data["initial"])["value"]);assertNull(r.data["behaviorId"])
        for(key in listOf("unit","initial","access","behavior"))assertEquals("UNKNOWN",obj(obj(r.fields.single().data["provenance"])[key])["confidence"])
        for(entityId in listOf(id,id+".raw")) {val entity=assertNotNull(c.byId(entityId));for(key in listOf("permitted","readable","writable","cpuRoutable"))assertEquals(false,entity[key]);assertNull(entity["physicalAddress"]);assertNull(entity["componentId"])}
        val before=CatalogIO.canonical(c.data)
        for(direction in listOf("read","write")){val q=c.queryLogicalCandidate("gfx100s-power-protocol-requests",15,16,direction);assertEquals("UNASSIGNED_LOGICAL_REQUEST",q["classification"]);assertEquals(false,q["permitted"]);assertEquals(false,q["dynamicReadPerformed"])}
        assertContentEquals(before,CatalogIO.canonical(c.data))
    }
    @Test fun provenancePinsPendingChecksumAndQualifiedSoftwareSourceWithoutPhysicalAck() {
        val c=CatalogCompiler.compile(root);val r=c.protocolCandidateSpaces.single().requests.single {it.selector==15};val p=obj(r.data["provenance"])
        assertEquals("b55c551d565b4a93c4d3a330df3cc9792ba6e50d",obj(obj(p["selector"])["source"])["commit"])
        val observation=obj(p["observation"]);assertEquals("SIM",observation["confidence"])
        val source=obj(observation["source"]);assertEquals("ca6dc8d4658b5c321c2371537a90acbb859663ee",source["commit"])
        val original=obj(obj(CatalogIO.json.readValue(history.bytes(source["commit"] as String,source["path"] as String),Map::class.java))["original"])
        assertEquals("unmodeled power write register15 value49152",original["status"])
        val peer=obj(obj(original["peripherals"])["power-peer"]);assertEquals(3L,valueLong(peer["phase"]));assertFalse(obj(peer["stored"]).containsKey("15"));assertTrue(rows(peer["events"]).none {valueLong(it["register"])==15L})
        assertEquals(176L,valueLong(obj(obj(obj(original["devices"])["soc-spi"])["pending"])["tx"]))
    }
    @Test fun hypothesizedInitialWordOrMaskCannotBecomeCatalogPermission() {
        val c=CatalogCompiler.compile(root)
        for(change in listOf(mapOf("initial" to mapOf("value" to 0,"source" to "UNKNOWN")),mapOf("masks" to mapOf("preserve" to 0,"prohibited" to 0,"unknown" to 0x3fff,"writable" to 0xc000)),mapOf("permitted" to true))) {
            val data=mutable(c);val s=space(data);s["requests"]=rows(s["requests"]).map {if(it["id"]==id)it+change else it};assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(data)}
        }
        val data=mutable(c);val s=space(data);s["requests"]=rows(s["requests"]).map {if(it["id"]==id)it+mapOf("fields" to rows(it["fields"]).map {f->f+mapOf("bitOffset" to 14,"bitWidth" to 2)})else it};assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(data)}
    }
}
