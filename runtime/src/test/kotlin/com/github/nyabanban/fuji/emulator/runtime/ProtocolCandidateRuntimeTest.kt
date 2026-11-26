package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ControlClient
import com.github.nyabanban.fuji.emulator.catalog.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProtocolCandidateRuntimeTest {
    private val namespace="gfx100s-power-protocol-requests"
    private val request="request.power-peer.selector0x25"
    private fun runtime(mode:String="discovery")=CatalogRuntime(AddressCatalog.bundled(),mode,Path.of("."),emptyMap())
    private fun query()=mapOf("space" to namespace,"selector" to "37","wordBits" to "16","direction" to "read")
    @Test fun readonlyCandidatesStayUnimplementedInBothModesAndAfterActualRtcBinding() {
        for(mode in listOf("discovery","strict")) {
            val r=runtime(mode)
            val rtc=r.catalog.logicalSpaces.single();val profile="gfx100s-rtc-scripted-stage-read-experimental-v2"
            r.bindLogicalPorts(listOf(LogicalPortBinding("rtc","rtc.registers",profile,rtc.registers.map {it.contract(profile)})),mapOf("rtc" to profile))
            val q=r.query(query())
            assertEquals("UNASSIGNED_LOGICAL_REQUEST",q["classification"]);assertEquals(false,q["permitted"])
            assertEquals("NOT_IMPLEMENTED",q["runtimeActivation"]);assertEquals("UNKNOWN",q["deviceOwner"])
            assertEquals(false,q["readable"]);assertEquals(false,q["writable"]);assertEquals(false,q["cpuRoutable"])
            assertTrue(q.containsKey("physicalAddress"));assertNull(q["physicalAddress"])
            assertNull(q["componentProfile"]);assertEquals("power-peer.spi",q["transportPort"])
            val current=r.query(mapOf("space" to "rtc-power-register","selector" to "29","wordBits" to "16","direction" to "read"))
            assertEquals(true,current["permitted"]);assertEquals("ACTIVE_LOGICAL_PORT",current["runtimeActivation"])
        }
    }
    @Test fun idAndPropertyQueriesShareTheCompiledModelWithoutDeviceOwnershipClaims() {
        val r=runtime()
        for(id in listOf(namespace,request,request+".raw")) {
            val q=r.query(mapOf("id" to id));val entity=q["entity"] as Map<*,*>
            assertEquals(namespace,entity["protocolCandidateSpaceId"]);assertEquals(false,q["permitted"])
            assertNotNull(q["refusalReason"]);assertTrue((q["resolutionPath"] as List<*>).isNotEmpty())
            assertEquals(false,entity["permitted"]);assertEquals(false,q["deviceReadPerformed"])
            assertEquals("NOT_IMPLEMENTED",q["runtimeActivation"]);assertEquals("NOT_SAMPLED",q["dynamicAvailability"])
        }
        val p=r.query(mapOf("id" to request,"property" to "initial.value"))
        assertEquals("UNKNOWN",p["status"]);assertTrue(p.containsKey("value"));assertNull(p["value"])
        val word=r.query(mapOf("id" to request,"property" to "wordBits"))
        assertEquals(16,word["value"]);assertEquals("DAT",(word["provenance"] as Map<*,*>)["confidence"])
    }
    @Test fun mixedCoordinatesProfilesAndRelabelledKnownIdsAreRejectedBeforeAnyFallback() {
        val r=runtime()
        val variants=listOf(query()+mapOf("address" to "37"),query()+mapOf("width" to "2"),query()+mapOf("coverage" to "true"),
            query()+mapOf("memoryProfile" to "default"),query()+mapOf("componentProfile" to "enabled"),
            query()+mapOf("id" to request),mapOf("id" to request,"space" to "rtc-power-register"),
            mapOf("id" to "reg.rtc.stage-low","space" to namespace),mapOf("id" to "reg.soc-sd-host.word80.o0","space" to namespace),
            mapOf("id" to request,"space" to "cpu-physical"),mapOf("id" to request,"wordBits" to "16"))
        for(p in variants)assertFailsWith<IllegalArgumentException> {r.query(p)}
        assertEquals("RAM",r.query(mapOf("address" to "37","width" to "2"))["classification"])
        for(p in listOf(query()+("selector" to "39"),query()+("selector" to "128"),query()+("wordBits" to "2"),query()+("direction" to "execute")))assertEquals(false,r.query(p)["permitted"])
    }
    @Test fun missingSelectorAndNumericPropertyOperandsAreExplicitClientErrors() {
        val r=runtime()
        val invalid=listOf(mapOf("space" to namespace),query()+mapOf("property" to "initial.value"))
        val errors=invalid.map {runCatching {r.query(it)}.exceptionOrNull()}
        assertTrue(errors.all {it is IllegalArgumentException},errors.toString())
    }
    @Test fun candidatesNeverEnterDiscoveryRegionsOrChangeTheOwnerClockAndJournal() {
        val r=runtime();val context=object:DiscoveryContext {override fun core()=0;override fun tick()=101L;override fun pc()=0L;override fun lr()=0L}
        val bus=assertNotNull(r.createBus(context));val before=bus.snapshot()
        repeat(100) {r.query(query());r.query(mapOf("id" to request));r.query(mapOf("id" to request,"property" to "provenance"))}
        assertEquals(before,bus.snapshot());assertEquals(101L,context.tick())
        assertEquals(r.catalog.candidates.map { it.id }.toSet(), bus.regions.map { it.id }.toSet())
        assertTrue(bus.regions.none { it.id == namespace || it.id == request || it.id == "$request.raw" })
        assertFails {r.bindLogicalPorts(listOf(LogicalPortBinding("power-peer","power-peer.spi","unknown",emptyList())),mapOf("power-peer" to "unknown"))}
    }
    @Test fun httpReusesTheOwnerQueryAndReturnsNoDynamicReadOrFakePermission() {
        val r=runtime();val owner=OwnerLoop()
        val control=object:ControlClient {
            override fun snapshot():CompletableFuture<Map<String,Any?>> = error("Candidate query must not request device state")
            override fun patch(id:String,revision:Long,changes:Map<String,Any>):CompletableFuture<Map<String,Any?>> = error("Candidate query must not mutate settings")
        }
        try {
            ControlServer(control,0,addressQuery={p->owner.submit {owner.requireOwner();r.query(p)}.join()}).use {server->
                val host=server.address.address.hostAddress.let {if(it.contains(':'))"["+it+"]" else it}
                val base="http://"+host+":"+server.address.port+"/api/v1/catalog?space="+namespace+"&selector=37&wordBits=16&direction=read"
                HttpClient.newHttpClient().use {client->
                    val response=client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(),HttpResponse.BodyHandlers.ofString())
                    assertEquals(200,response.statusCode());val body=Formats.json.readValue(response.body(),Map::class.java)
                    assertEquals(false,body["permitted"]);assertEquals(false,body["deviceReadPerformed"])
                    assertEquals("NOT_IMPLEMENTED",body["runtimeActivation"]);assertNull(body["physicalAddress"])
                    val bad=client.send(HttpRequest.newBuilder(URI.create(base+"&address=37")).GET().build(),HttpResponse.BodyHandlers.ofString())
                    assertEquals(400,bad.statusCode())
                    val malformed=listOf("http://"+host+":"+server.address.port+"/api/v1/catalog?space="+namespace,base+"&property=initial.value")
                    val statuses=malformed.map {url->client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()}
                    assertEquals(listOf(400,400),statuses)
                }
            }
        } finally {owner.close()}
    }
}
