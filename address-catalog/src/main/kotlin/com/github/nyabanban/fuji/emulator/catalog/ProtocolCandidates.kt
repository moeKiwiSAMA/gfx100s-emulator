package com.github.nyabanban.fuji.emulator.catalog

/** Evidence about an unassigned protocol request is not an executable endpoint contract. */
data class CatalogProtocolCandidate(val id:String,val selector:Int,val wordBits:Int,val fields:List<CatalogField>,val data:Map<String,Any?>)
data class CatalogProtocolCandidateSpace(val id:String,val transportComponentId:String,val transportPort:String,
    val selectorBits:Int,val wordBits:Int,val requests:List<CatalogProtocolCandidate>,val data:Map<String,Any?>) {
    val selectorLimit:Long get()=1L shl selectorBits
}

internal fun protocolCandidateDefinitions(data:Map<String,Any?>):List<Pair<Map<String,Any?>,Map<String,Any?>>> =
    rows(data["devices"]).flatMap {d->rows(d["protocolCandidates"] ?: emptyList<Any>()).map {d to it}}

internal fun protocolCandidateSpace(definition:Pair<Map<String,Any?>,Map<String,Any?>>):CatalogProtocolCandidateSpace {
    val (device,space)=definition
    return CatalogProtocolCandidateSpace(space.text("id"),device.text("componentId"),space.text("transportPort"),
        Math.toIntExact(space.long("selectorBits")),Math.toIntExact(space.long("wordBits")),
        immutableList(rows(space["requests"]).map {r->CatalogProtocolCandidate(r.text("id"),Math.toIntExact(r.long("selector")),Math.toIntExact(r.long("wordBits")),
            immutableList(rows(r["fields"]).map {f->CatalogField(f.text("id"),Math.toIntExact(f.long("bitOffset")),Math.toIntExact(f.long("bitWidth")),f)}),r)}),space)
}

internal fun validateProtocolCandidates(data:Map<String,Any?>,id:(Map<String,Any?>)->Unit) {
    rows(data["devices"]).forEach {if("protocolCandidates" in it)require(it["protocolCandidates"] is List<*>) {"explicit protocolCandidates must be an array"}}
    val namespaceKeys=setOf("id","coordinateKind","selectorBits","wordBits","unit","byteOrder","transportPort","deviceOwner","implementationStatus","unknownPolicy","applicability","requests","provenance","unknowns","nextValidation")
    val requestKeys=setOf("id","selector","wordBits","name","direction","observationStatus","implementationStatus","initial","masks","behaviorId","sideEffects","fields","provenance","evidence","unknowns","nextValidation")
    val fieldKeys=setOf("id","bitOffset","bitWidth","type","signed","unit","access","provenance","unknowns","nextValidation")
    fun integers(row:Map<String,Any?>,vararg keys:String)=require(keys.all {row[it] is Byte || row[it] is Short || row[it] is Int || row[it] is Long}) {"candidate dimensions/masks require exact integers"}
    fun unknown(row:Map<String,Any?>,key:String)=require(row[key]=="UNKNOWN") {"candidate "+key+" must remain UNKNOWN"}
    protocolCandidateDefinitions(data).forEach {(_,s)->
        id(s);require(s.keys==namespaceKeys && s["coordinateKind"]=="PROTOCOL_SELECTOR" && s["unit"]=="selector") {"candidate protocol namespace cannot contain physical coordinates or bindings"}
        integers(s,"selectorBits","wordBits")
        require(s.long("selectorBits") in 1..16 && s.long("wordBits") in 1..32 && s["byteOrder"] in setOf("big","little") && s["unknownPolicy"]=="refuse")
        require(s.text("transportPort").safeId());unknown(s,"deviceOwner")
        require(s["implementationStatus"]=="NOT_IMPLEMENTED") {"candidate declaration cannot install behavior"}
        val applicable=obj(s["applicability"])
        require(applicable.keys==setOf("body","firmware") && applicable["firmware"]==obj(data["firmware"])["id"] && rows(data["windows"]).any {obj(it["applicability"])["body"]==applicable["body"]}) {"candidate applicability must match selected body/firmware"}
        require(s["provenance"] is Map<*,*> && rows(s["requests"]).isNotEmpty())
        val coordinates=HashSet<Pair<Long,String>>();val bits=s.long("wordBits");val full=(1L shl bits.toInt())-1L
        rows(s["requests"]).forEach {r->
            id(r);require(r.keys==requestKeys) {"candidate request keys/coordinates invalid"}
            integers(r,"selector","wordBits")
            require(r.long("selector") in 0 until (1L shl s.long("selectorBits").toInt()))
            require(r.long("wordBits")==bits && r["direction"] in setOf("read","write") && r["observationStatus"] in setOf("REQUEST_OBSERVED_PENDING","STATIC_CANDIDATE"))
            require(coordinates.add(r.long("selector") to r.text("direction"))) {"duplicate candidate selector/direction"}
            unknown(r,"name");unknown(r,"sideEffects")
            require(r["implementationStatus"]=="NOT_IMPLEMENTED" && r.containsKey("behaviorId") && r["behaviorId"]==null)
            val initial=obj(r["initial"]);require(initial.keys==setOf("value","source") && initial.containsKey("value") && initial["value"]==null && initial["source"]=="UNKNOWN") {"candidate initial value cannot be invented"}
            val masks=obj(r["masks"]);integers(masks,"writable","preserve","unknown","prohibited");require(masks.keys==setOf("writable","preserve","unknown","prohibited") && masks.long("unknown")==full && listOf("writable","preserve","prohibited").all {masks.long(it)==0L})
            var occupied=0L
            rows(r["fields"]).forEach {f->
                id(f);require(f.keys==fieldKeys)
                integers(f,"bitOffset","bitWidth")
                val offset=f.long("bitOffset");val width=f.long("bitWidth");require(offset>=0 && offset<bits && width>0 && width<=bits && offset<=bits-width)
                val mask=((1L shl width.toInt())-1L) shl offset.toInt();require(occupied and mask==0L);occupied=occupied or mask
                require(f["type"]=="raw-bits" && f["signed"]==false && f["access"]=="UNKNOWN" && f["unit"]=="UNKNOWN" && f["provenance"] is Map<*,*>)
            }
            require(occupied==full && r["provenance"] is Map<*,*> && r["evidence"] is List<*>)
            val provenance=obj(r["provenance"])
            require(listOf("selector","width","direction","observation","name","initial","behavior").all {provenance[it] is Map<*,*>})
            for(key in listOf("name","initial","behavior"))require(obj(provenance[key])["confidence"]=="UNKNOWN")
            require(obj(provenance["observation"])["confidence"]==if(r["observationStatus"]=="REQUEST_OBSERVED_PENDING")"SIM" else "DAT")
        }
    }
}

internal fun protocolCandidateAnnotations(space:CatalogProtocolCandidateSpace,request:CatalogProtocolCandidate?=null):Map<String,Any?> = mapOf(
    "protocolCandidateSpaceId" to space.id,"coordinateKind" to "PROTOCOL_SELECTOR","transportComponentId" to space.transportComponentId,
    "transportPort" to space.transportPort,"deviceOwner" to "UNKNOWN","cpuRoutable" to false,"physicalAddress" to null,
    "implementationStatus" to "NOT_IMPLEMENTED","behaviorId" to null,"permitted" to false,"readable" to false,"writable" to false,
    "runtimeActivation" to "NOT_IMPLEMENTED","dynamicReadPerformed" to false,"dynamicAvailability" to "NOT_SAMPLED") +
    if(request==null)emptyMap() else mapOf("requestId" to request.id,"selector" to request.selector,"wordBits" to request.wordBits)

internal fun queryProtocolCandidate(spaces:List<CatalogProtocolCandidateSpace>,spaceId:String,selector:Long,wordBits:Int,direction:String?):Map<String,Any?> {
    val space=spaces.singleOrNull {it.id==spaceId}
    val candidates=space?.requests.orEmpty().filter {it.selector.toLong()==selector}
    val request=if(candidates.size==1)candidates.single() else candidates.singleOrNull {it.data["direction"]==direction}
    val reason=when {
        space==null->"unknown protocol candidate namespace"
        selector<0 || selector>=space.selectorLimit->"selector outside declared candidate namespace"
        wordBits!=space.wordBits->"candidate protocol width is bits, not CPU bytes"
        direction!=null && direction !in setOf("read","write")->"candidate direction must be read or write"
        candidates.size>1 && direction==null->"direction required to distinguish evidenced read/write requests"
        request==null->"unknown selector; candidate namespaces have no fallback"
        direction!=null && direction!=request.data["direction"]->"direction differs from the evidenced request"
        else->"request has no qualified device ownership or behavior; declaration grants no access"
    }
    return java.util.Collections.unmodifiableMap(mapOf("classification" to when{space==null->"UNASSIGNED";request==null->"UNKNOWN";else->"UNASSIGNED_LOGICAL_REQUEST"},
        "space" to spaceId,"selector" to selector,"wordBits" to wordBits,"direction" to direction,"unit" to "selector",
        "requestId" to request?.id,"request" to request?.data,"fields" to request?.fields?.map {it.data},
        "refusalReason" to reason,"resolutionPath" to listOf("protocol-candidate-space:"+spaceId)+(request?.let {listOf("unassigned-request:"+it.id)} ?: emptyList()),
        "permitted" to false,"cpuRoutable" to false,"physicalAddress" to null,"deviceOwner" to "UNKNOWN","implementationStatus" to "NOT_IMPLEMENTED",
        "runtimeActivation" to "NOT_IMPLEMENTED","dynamicReadPerformed" to false,"dynamicAvailability" to "NOT_SAMPLED") +
        (space?.let {protocolCandidateAnnotations(it,request)} ?: emptyMap())+mapOf("selector" to selector,"wordBits" to wordBits,"declaredWordBits" to space?.wordBits))
}
