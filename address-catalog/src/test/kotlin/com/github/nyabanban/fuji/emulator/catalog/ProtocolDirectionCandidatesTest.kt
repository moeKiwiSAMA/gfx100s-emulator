package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProtocolDirectionCandidatesTest {
    private val root=Path.of(System.getProperty("catalogRoot"))
    private val namespace="gfx100s-power-protocol-requests"
    private val newIds=setOf("request.power-peer.selector0x25.write","request.power-peer.selector0x26.write")
    @Suppress("UNCHECKED_CAST") private fun mutable(c:AddressCatalog)=CatalogIO.json.readValue(CatalogIO.canonical(c.data),MutableMap::class.java) as MutableMap<String,Any?>
    @Suppress("UNCHECKED_CAST") private fun space(data:Map<String,Any?>)=rows(rows(data["devices"]).single {it["componentId"]=="power-peer"}["protocolCandidates"]).single() as MutableMap<String,Any?>
    @Test fun bothDirectionsResolveOnlyTheirStableIdsAndNoDirectionRefusesAmbiguity() {
        val c=CatalogIO.readCompiled(CatalogIO.compiledBytes(CatalogCompiler.compile(root)))
        for((selector,hex) in listOf(37L to "25",38L to "26")) {
            for(direction in listOf("read","write")) {
                val q=c.queryLogicalCandidate(namespace,selector,16,direction)
                assertEquals("request.power-peer.selector0x"+hex+if(direction=="write")".write" else "",q["requestId"])
                assertEquals("UNASSIGNED_LOGICAL_REQUEST",q["classification"])
                for(key in listOf("permitted","readable","writable","cpuRoutable","dynamicReadPerformed"))assertEquals(false,q[key],key)
                assertEquals("NOT_IMPLEMENTED",q["runtimeActivation"]);assertEquals("UNKNOWN",q["deviceOwner"]);assertNull(q["physicalAddress"])
            }
            val ambiguous=c.queryLogicalCandidate(namespace,selector)
            assertNull(ambiguous["requestId"]);assertNull(ambiguous["request"]);assertNull(ambiguous["fields"])
            assertEquals("UNKNOWN",ambiguous["classification"])
            assertEquals("direction required to distinguish evidenced read/write requests",ambiguous["refusalReason"])
            assertEquals(false,ambiguous["permitted"])
            assertEquals("candidate direction must be read or write",c.queryLogicalCandidate(namespace,selector,16,"read-write")["refusalReason"])
        }
        val unique=c.queryLogicalCandidate(namespace,15,16,"read")
        assertEquals("request.power-peer.selector0x0f",unique["requestId"])
        assertEquals("direction differs from the evidenced request",unique["refusalReason"])
    }
    @Test fun sameDirectionDuplicateAndGlobalIdCollisionStillRefuse() {
        val c=CatalogCompiler.compile(root)
        for(direction in listOf("read","write")) {
            val d=mutable(c);val s=space(d);val request=rows(s["requests"]).single {valueLong(it["selector"])==37L && it["direction"]==direction}
            val copy=request+mapOf("id" to "request.duplicate."+direction,"fields" to rows(request["fields"]).map {it+mapOf("id" to "field.duplicate."+direction)})
            s["requests"]=rows(s["requests"])+copy
            assertEquals("duplicate candidate selector/direction",assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(d)}.message)
        }
        val d=mutable(c);val s=space(d);val first=rows(s["requests"]).first()
        s["requests"]=rows(s["requests"])+(first+mapOf("selector" to 36,"direction" to "write","fields" to rows(first["fields"]).map {it+mapOf("id" to "field.global-id-control")}))
        assertEquals("invalid/duplicate ID: "+first["id"],assertFailsWith<IllegalArgumentException>{CatalogCompiler.validate(d)}.message)
    }
    @Test fun deletingExactlyTwoWriteIdsRestoresEntireG11AndEveryHardwareDescriptor() {
        val history = HistoricalCatalogFixture(root)
        val c = history.catalog("e335c3816f49f945ce8852c2ec25147b9631764a")
        val d=mutable(c);val s=space(d)
        assertEquals(11,rows(s["requests"]).size)
        assertEquals(newIds,rows(s["requests"]).takeLast(2).map {it["id"]}.toSet())
        s["requests"]=rows(s["requests"]).filter {it["id"] !in newIds}
        val old = history.catalog("3d205d356ced081768a189b3f856e77aeb2abd61")
        assertContentEquals(CatalogIO.canonical(old.data),CatalogIO.canonical(d))
        assertEquals(32,c.componentIds.size)
        for(id in c.componentIds)assertContentEquals(CatalogIO.canonical(old.componentDescriptor(id)),CatalogIO.canonical(c.componentDescriptor(id)),id)
    }
    @Test fun newWriteEvidenceKeepsAllPhysicalBitsAndBehaviorUnknown() {
        val c=CatalogCompiler.compile(root)
        for(id in newIds) {
            val r=c.protocolCandidateSpaces.single().requests.single {it.id==id}
            assertEquals("write",r.data["direction"]);assertEquals(16,r.wordBits)
            assertEquals(listOf(0 to 16),r.fields.map {it.bitOffset to it.bitWidth})
            assertEquals(mapOf("writable" to 0L,"preserve" to 0L,"unknown" to 65535L,"prohibited" to 0L),obj(r.data["masks"]).mapValues {valueLong(it.value)})
            assertNull(obj(r.data["initial"])["value"]);assertEquals("UNKNOWN",obj(r.data["initial"])["source"])
            assertEquals("UNKNOWN",r.data["name"]);assertEquals("UNKNOWN",r.data["sideEffects"]);assertNull(r.data["behaviorId"])
            assertEquals("NOT_IMPLEMENTED",r.data["implementationStatus"])
            val expected=if(r.selector==37)"REQUEST_OBSERVED_PENDING" else "STATIC_CANDIDATE"
            assertEquals(expected,r.data["observationStatus"])
            assertEquals(if(r.selector==37)"SIM" else "DAT",obj(obj(r.data["provenance"])["observation"])["confidence"])
            for(key in listOf("access","unit"))assertEquals("UNKNOWN",r.fields.single().data[key])
            for(key in listOf("permitted","readable","writable","cpuRoutable"))assertEquals(false,c.byId(id)!![key])
        }
    }
    @Test fun dynamic37RefusalAndTruncatedEntryDoNotBecomeACommitOrDynamic38() {
        val path="evidence/refactor/power0f-mask-original-fresh-15/study/discovery-cap8-v5-gpio110-power0f-maskc000-lcdinit/original.generated.json"
        val commit="a803a15625ac525c9c712749d65f5226af3da6a6"
        val original=obj(obj(CatalogIO.json.readValue(HistoricalCatalogFixture(root).bytes(commit, path), Map::class.java))["original"])
        assertEquals("candidate read input cannot accept write register37",original["status"])
        val peer=obj(obj(original["peripherals"])["power-peer"])
        assertEquals(0L,valueLong(peer["phase"]))
        assertEquals(false,peer["eventLogTruncated"]);assertEquals(false,peer["wordLogTruncated"])
        assertFalse(rows(peer["events"]).any {valueLong(it["register"]) in setOf(37L,38L) && it["write"]==true})
        assertTrue(listOf("37","38").none {it in obj(peer["stored"])})
        assertEquals(165L,valueLong(obj(obj(obj(original["devices"])["soc-spi"])["pending"])["tx"]))
        val ledger=rows(obj(original["diagnosticTraceSampling"])["observed"]).single {valueLong(it["address"])==0x120c0a4L && valueLong(it["core"])==0L}
        assertEquals(9L,valueLong(ledger["count"]));assertEquals(8L,valueLong(ledger["retained"]))
        assertFalse(rows(original["checkpoints"]).any {valueLong(it["address"])==0x120c0a4L && valueLong(it["r0"])==37L})
        val c=CatalogCompiler.compile(root)
        val r=c.protocolCandidateSpaces.single().requests.single {it.id=="request.power-peer.selector0x25.write"}
        assertEquals(mapOf("commit" to commit,"path" to path),obj(obj(obj(r.data["provenance"])["observation"])["source"]))
        val static=c.protocolCandidateSpaces.single().requests.single {it.id=="request.power-peer.selector0x26.write"}
        assertEquals("STATIC_CANDIDATE",static.data["observationStatus"])
        assertEquals("7468fa07aa1150aebdf6bf728d32021490a3ae1e",obj(obj(obj(static.data["provenance"])["observation"])["source"])["commit"])
    }

}
