package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ProtocolCandidateReadContract
import com.github.nyabanban.fuji.emulator.api.DescribedProtocolCandidateInputs
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog

/** Input admission only. This cannot install a device, CPU window or access permission. */
data class ProtocolCandidateInputBinding(val componentId:String,val portId:String,val profile:String,
    val reads:List<ProtocolCandidateReadContract>)

internal fun candidateInputBinding(componentId:String,profile:String?,provider:DescribedProtocolCandidateInputs?):ProtocolCandidateInputBinding? {
    val enabled=profile=="finite-candidate-reads-experimental-v3"
    require((provider!=null)==enabled) {"Candidate input provider export differs from selected profile"}
    if(!enabled)return null
    return ProtocolCandidateInputBinding(componentId,componentId+".candidate-inputs",checkNotNull(profile),checkNotNull(provider).candidateReadContracts().toList())
}

internal fun validateCandidateInputs(catalog:AddressCatalog,bindings:List<ProtocolCandidateInputBinding>):Map<String,Map<String,Any>> {
    require(bindings.map {it.portId}.distinct().size==bindings.size) {"Duplicate candidate input provider"}
    val result=linkedMapOf<String,Map<String,Any>>()
    for(binding in bindings) {
        require(binding.profile=="finite-candidate-reads-experimental-v3" && binding.portId==binding.componentId+".candidate-inputs") {"Unknown candidate input provider profile or port"}
        require(catalog.protocolCandidateSpaces.any {it.transportComponentId==binding.componentId}) {"Candidate provider has no declared transport"}
        require(binding.reads.size<=128)
        val selectors=HashSet<Pair<String,Int>>()
        for(read in binding.reads) {
            val space=catalog.protocolCandidateSpaces.singleOrNull {it.id==read.spaceId && it.transportComponentId==binding.componentId}
                ?: throw IllegalArgumentException("Unknown candidate namespace or transport")
            val request=space.requests.singleOrNull {it.id==read.requestId}
                ?: throw IllegalArgumentException("Unknown candidate request ID")
            require(read.selector==request.selector && read.wordBits==request.wordBits && read.wordBits==space.wordBits &&
                read.direction=="read" && request.data["direction"]==read.direction) {"Candidate input differs from evidenced coordinates or direction"}
            require(read.strategy=="scripted-read" && read.confidence=="HYPOTHESIS") {"Candidate inputs require explicit finite hypothesis"}
            require(selectors.add(read.spaceId to read.selector) && read.requestId !in result) {"Duplicate candidate input selector or ID"}
            require(request.data["implementationStatus"]=="NOT_IMPLEMENTED" && request.data["behaviorId"]==null) {"Known behavior cannot be bypassed by candidate input"}
            val initial=request.data["initial"] as Map<*,*>
            require(initial.containsKey("value") && initial["value"]==null && initial["source"]=="UNKNOWN")
            // Copy primitive metadata; caller containers and endpoint objects never enter the query model.
            result[read.requestId]=mapOf("spaceId" to read.spaceId,"requestId" to read.requestId,"selector" to read.selector,
                "wordBits" to read.wordBits,"direction" to read.direction,"strategy" to read.strategy,"confidence" to read.confidence,
                "transportComponentId" to binding.componentId,"providerPort" to binding.portId,"profile" to binding.profile)
        }
    }
    return java.util.Collections.unmodifiableMap(result.mapValues {java.util.Collections.unmodifiableMap(it.value)})
}
