package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ControlClient
import com.github.nyabanban.fuji.emulator.catalog.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class LogicalCatalogRuntimeTest {
    private val v1="gfx100s-rtc-functional-experimental-v1"
    private val v2="gfx100s-rtc-scripted-stage-read-experimental-v2"
    private fun runtime()=CatalogRuntime(AddressCatalog.bundled(),"discovery",Path.of("."),emptyMap())
    private fun binding(profile:String)=LogicalPortBinding("rtc","rtc.registers",profile,listOf(
        LogicalRegisterContract("reg.rtc.stage-low",29,16,profile==v2,true),
        LogicalRegisterContract("reg.rtc.stage-high",30,16,profile==v2,true),
        LogicalRegisterContract("reg.rtc.counter-low",35,16,true,false),
        LogicalRegisterContract("reg.rtc.counter-high",36,16,true,false),
        LogicalRegisterContract("reg.rtc.control",39,16,true,true)))
    private fun query(selector:String="29",bits:String="16",direction:String="read")=mapOf("space" to "rtc-power-register","selector" to selector,"wordBits" to bits,"direction" to direction)

    @Test fun staticProfileLookupDoesNotPretendTheEndpointIsBoundOrRead() {
        val r=runtime();val result=r.query(query()+("componentProfile" to v2))
        assertEquals(true,result["declaredPermitted"]);assertEquals(false,result["permitted"])
        assertEquals("NOT_BOUND",result["runtimeActivation"]);assertEquals("explicit-static-selection",result["profileSource"])
        assertEquals(false,result["cpuRoutable"]);assertNull(result["physicalAddress"])
        assertEquals(false,result["deviceReadPerformed"]);assertEquals(false,result["guestStateChanged"])
    }
    @Test fun actualProfileAndLogicalUnitsNeverGrantCpuAccessOrConsumeProbeInput() {
        val r=runtime();r.bindLogicalPorts(listOf(binding(v1)),mapOf("rtc" to v1))
        val context=object:DiscoveryContext {override fun core()=0;override fun tick()=99L;override fun pc()=0L;override fun lr()=0L}
        val bus=checkNotNull(r.createBus(context));val before=bus.snapshot()
        assertEquals(false,r.query(query())["permitted"])
        assertEquals(true,r.query(query(direction="write"))["permitted"])
        assertEquals(true,r.query(query(selector="35"))["permitted"])
        val physical=r.query(mapOf("space" to "cpu-physical","address" to "29","width" to "4"))
        assertEquals("RAM",physical["classification"]);assertNull(physical["registerId"])
        assertEquals(before,bus.snapshot());assertEquals(99L,context.tick())
        assertFailsWith<IllegalArgumentException> {r.query(query()+("componentProfile" to v2))}
        val entity=r.query(mapOf("id" to "reg.rtc.stage-low"))["entity"] as Map<*,*>
        assertEquals(v1,entity["componentProfile"]);assertEquals(false,entity["effectiveReadable"])
    }
    @Test fun contractMismatchCannotPartiallyPublishAndInputContainersAreNotRetained() {
        val r=runtime();val valid=binding(v2)
        assertFailsWith<IllegalArgumentException> {r.bindLogicalPorts(listOf(valid.copy(registers=valid.registers.dropLast(1))),mapOf("rtc" to v2))}
        assertEquals("NOT_BOUND",r.query(query())["runtimeActivation"])
        assertFailsWith<IllegalArgumentException> {r.bindLogicalPorts(emptyList(),mapOf("rtc" to v2))}
        val profiles=mutableMapOf("rtc" to v2);val ports=mutableListOf(valid)
        r.bindLogicalPorts(ports,profiles);profiles["rtc"]="disabled";ports.clear()
        val result=r.query(query());assertEquals(true,result["permitted"]);assertEquals("ACTIVE_LOGICAL_PORT",result["runtimeActivation"])
        assertEquals(v2,result["componentProfile"])
        assertFailsWith<IllegalStateException> {r.bindLogicalPorts(listOf(valid),mapOf("rtc" to v2))}
    }
    @Test fun disabledUnknownSelectorsAndMixedCoordinatesStayRefused() {
        val disabled=runtime();disabled.bindLogicalPorts(emptyList(),mapOf("rtc" to "disabled"))
        assertEquals("INACTIVE_LOGICAL_PORT",disabled.query(query())["runtimeActivation"])
        assertEquals(false,disabled.query(query())["permitted"])
        val r=runtime();r.bindLogicalPorts(listOf(binding(v2)),mapOf("rtc" to v2))
        for(p in listOf(query(selector="127"),query(selector="128"),query(selector="-1"),query(bits="2"),query(direction="execute")))assertEquals(false,r.query(p)["permitted"])
        for(p in listOf(query()+mapOf("address" to "29"),query()+mapOf("width" to "2"),query()-"space",query()+("space" to "cpu-physical"),
            mapOf("id" to "reg.rtc.stage-low","wordBits" to "16"),
            mapOf("id" to "reg.soc-sd-host.word80.o0","space" to "rtc-power-register")))assertFails {r.query(p)}
        val unknown=r.query(mapOf("space" to "rtc-power-register","id" to "reg.rtc.missing"))
        assertEquals("UNKNOWN",unknown["status"]);assertEquals(false,unknown["permitted"])
    }
    @Test fun deviceContainerAndLeafUseTheSameActiveLogicalProfile() {
        val r=runtime();r.bindLogicalPorts(listOf(binding(v2)),mapOf("rtc" to v2))
        val leaf=r.query(mapOf("id" to "reg.rtc.stage-low"))["entity"] as Map<*,*>
        val container=r.query(mapOf("id" to "device.rtc"))
        val device=container["entity"] as Map<*,*>
        val space=(device["logicalSpaces"] as List<*>).single() as Map<*,*>
        val register=(space["registers"] as List<*>).map {it as Map<*,*>}.single {it["id"]=="reg.rtc.stage-low"}
        assertEquals(leaf["componentProfile"],register["componentProfile"])
        assertEquals(leaf["effectiveReadable"],register["effectiveReadable"])
        assertEquals(v2,device["componentProfile"]);assertEquals(true,register["effectiveReadable"])
        assertEquals("ACTIVE_COMPONENT_DEFINITION",container["runtimeActivation"])
    }
    @Test fun httpUsesTheSameOwnerQueryAndRejectsCrossDomainOperands() {
        val owner=OwnerLoop();val r=runtime()
        val control=object:ControlClient {
            override fun snapshot()=CompletableFuture.completedFuture<Map<String,Any?>>(emptyMap())
            override fun patch(id:String,revision:Long,changes:Map<String,Any>)=CompletableFuture.completedFuture<Map<String,Any?>>(emptyMap())
        }
        try {
            owner.submit {r.bindLogicalPorts(listOf(binding(v2)),mapOf("rtc" to v2))}.join()
            ControlServer(control,0,addressQuery={ p -> owner.submit {owner.requireOwner();r.query(p)}.join()}).use {server->
                val host=server.address.address.hostAddress.let {if(it.contains(':'))"["+it+"]" else it}
                val base="http://"+host+":"+server.address.port+"/api/v1/catalog?space=rtc-power-register&selector=29&wordBits=16&direction=read"
                HttpClient.newHttpClient().use {client->
                    val response=client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(),HttpResponse.BodyHandlers.ofString())
                    assertEquals(200,response.statusCode());val body=Formats.json.readValue(response.body(),Map::class.java)
                    assertEquals("reg.rtc.stage-low",body["registerId"]);assertEquals(false,body["deviceReadPerformed"])
                    assertEquals(true,body["permitted"]);assertEquals("ACTIVE_LOGICAL_PORT",body["runtimeActivation"])
                    val rejected=client.send(HttpRequest.newBuilder(URI.create(base+"&address=29")).GET().build(),HttpResponse.BodyHandlers.ofString())
                    assertEquals(400,rejected.statusCode())
                }
            }
        } finally {owner.close()}
    }
}
