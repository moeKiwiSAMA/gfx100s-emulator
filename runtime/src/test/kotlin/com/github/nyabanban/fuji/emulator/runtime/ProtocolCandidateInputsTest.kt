package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ProtocolCandidateReadContract
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProtocolCandidateInputsTest {
    private fun read()=ProtocolCandidateReadContract("gfx100s-power-protocol-requests","request.power-peer.selector0x25",37,16,"read","scripted-read","HYPOTHESIS")
    private fun binding(reads:List<ProtocolCandidateReadContract> = listOf(read()))=ProtocolCandidateInputBinding("power-peer","power-peer.candidate-inputs","finite-candidate-reads-experimental-v3",reads)
    private fun runtime()=CatalogRuntime(AddressCatalog.bundled(),"discovery",Path.of("."),emptyMap())
    private fun query()=mapOf("space" to "gfx100s-power-protocol-requests","selector" to "37","wordBits" to "16","direction" to "read")
    @Test fun candidateInputMetadataRemainsAHypothesisAndNeverGrantsPermission() {
        val r=runtime();r.bindCandidateInputs(listOf(binding()))
        for(parameters in listOf(query(),mapOf("id" to read().requestId),mapOf("id" to read().requestId+".raw"))) {
            val result=r.query(parameters)
            assertEquals(false,result["permitted"]);assertEquals(false,result["cpuRoutable"]);assertNull(result["physicalAddress"])
            assertEquals("NOT_IMPLEMENTED",result["implementationStatus"]);assertEquals("UNKNOWN",result["deviceOwner"])
            assertEquals(false,result["deviceReadPerformed"]);assertEquals(false,result["guestStateChanged"])
            val input=result["experimentalInput"] as Map<*,*>
            assertEquals("HYPOTHESIS",input["confidence"]);assertEquals("scripted-read",input["strategy"])
            assertEquals("CONFIGURED_ONLY",result["experimentalInputActivation"])
        }
        val absent=r.query(query()+("selector" to "38"))
        assertNull(absent["experimentalInput"]);assertEquals("NOT_CONFIGURED",absent["experimentalInputActivation"])
    }
    @Test fun enabledProviderCannotBeOmittedAndLegacyCannotExportAnExperiment() {
        var descriptions=0
        val provider=object:com.github.nyabanban.fuji.emulator.api.DescribedProtocolCandidateInputs {
            override fun candidateReadContracts():List<ProtocolCandidateReadContract> {descriptions++;return emptyList()}
        }
        assertFailsWith<IllegalArgumentException> {candidateInputBinding("power-peer","finite-candidate-reads-experimental-v3",null)}
        assertFailsWith<IllegalArgumentException> {candidateInputBinding("power-peer","masked-retained-registers-experimental-v2",provider)}
        assertEquals(0,descriptions)
        assertNull(candidateInputBinding("power-peer","masked-retained-registers-experimental-v2",null))
        assertEquals(emptyList(),candidateInputBinding("power-peer","finite-candidate-reads-experimental-v3",provider)!!.reads)
        assertEquals(1,descriptions)
    }
    @Test fun emptyControlAndLegacyNoBindingsInventNoInput() {
        for(bindings in listOf(emptyList(),listOf(binding(emptyList())))) {
            val r=runtime();r.bindCandidateInputs(bindings)
            assertNull(r.query(query())["experimentalInput"])
            assertEquals(false,r.query(query())["permitted"])
        }
    }
    @Test fun wrongCoordinatesDirectionProvenanceAndTransportRefuseAtomically() {
        val v=read()
        val bad=listOf(v.copy(spaceId="rtc-power-register"),v.copy(requestId="reg.rtc.stage-low"),v.copy(selector=38),v.copy(wordBits=2),
            v.copy(direction="write"),v.copy(strategy="fixed-input"),v.copy(confidence="DAT"))
        for(item in bad) {
            val r=runtime();assertFailsWith<IllegalArgumentException> {r.bindCandidateInputs(listOf(binding(listOf(v,item))))}
            assertNull(r.query(query())["experimentalInput"])
            r.bindCandidateInputs(listOf(binding()))
        }
        for(item in listOf(binding().copy(componentId="rtc"),binding().copy(portId="power-peer.spi"),binding().copy(profile="masked-retained-registers-experimental-v2"),
            binding(listOf(v,v)))) assertFailsWith<IllegalArgumentException> {runtime().bindCandidateInputs(listOf(item))}
        assertFailsWith<IllegalArgumentException> {runtime().bindCandidateInputs(listOf(binding(),binding()))}
    }
    @Test fun mutableCallerContainersAndQueriesCannotChangeTheAdmittedMetadata() {
        val r=runtime();val values=mutableListOf(read());val bindings=mutableListOf(binding(values))
        r.bindCandidateInputs(bindings);values.clear();bindings.clear()
        val first=r.query(query());repeat(3) {assertEquals(first,r.query(query()))}
        @Suppress("UNCHECKED_CAST")
        val input=first["experimentalInput"] as MutableMap<String,Any>
        assertFailsWith<UnsupportedOperationException> {input["confidence"]="VERIFIED"}
        assertFailsWith<IllegalStateException> {r.bindCandidateInputs(emptyList())}
        assertEquals(first,r.query(query()))
    }
    @Test fun sameSelectorWriteEvidenceCannotCaptureOrChangeReadInputBinding() {
        val r=runtime();r.bindCandidateInputs(listOf(binding()))
        assertEquals(read().requestId,r.query(query())["requestId"])
        assertNotNull(r.query(query())["experimentalInput"])
        val write=r.query(query()+("direction" to "write"))
        assertEquals(read().requestId+".write",write["requestId"]);assertNull(write["experimentalInput"])
        assertEquals(false,write["permitted"]);assertEquals(false,write["deviceReadPerformed"])
        val ambiguous=r.query(query()-"direction")
        assertNull(ambiguous["requestId"]);assertNull(ambiguous["experimentalInput"])
        assertEquals("NOT_CONFIGURED",ambiguous["experimentalInputActivation"])
        for(candidate in listOf(read().copy(requestId=read().requestId+".write"),read().copy(requestId=read().requestId+".write",direction="write"))) {
            val failure=assertFailsWith<IllegalArgumentException> {runtime().bindCandidateInputs(listOf(binding(listOf(candidate))))}
            assertEquals("Candidate input differs from evidenced coordinates or direction",failure.message)
        }
    }

}
