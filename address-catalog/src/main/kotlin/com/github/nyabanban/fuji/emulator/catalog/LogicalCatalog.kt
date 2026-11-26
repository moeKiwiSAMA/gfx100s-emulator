package com.github.nyabanban.fuji.emulator.catalog

/** A protocol selector is not a CPU byte address. Width is in bits, never bytes. */
data class LogicalRegisterContract(val registerId:String,val selector:Int,val wordBits:Int,val readable:Boolean,val writable:Boolean)
data class LogicalPortBinding(val componentId:String,val portId:String,val profile:String,val registers:List<LogicalRegisterContract>)

data class CatalogLogicalRegister(val id:String,val selector:Int,val wordBits:Int,val fields:List<CatalogField>,val data:Map<String,Any?>) {
    fun access(profile:String?):Map<String,Any?> = if(profile==null) emptyMap() else obj(data["accessByProfile"])[profile]?.let(::obj) ?: emptyMap()
    fun contract(profile:String):LogicalRegisterContract {
        val access=access(profile);require(access.isNotEmpty()) {"logical register profile absent: "+profile}
        return LogicalRegisterContract(id,selector,wordBits,access["readable"]==true,access["writable"]==true)
    }
    fun accessDetails(profile:String?):Map<String,Any?> {
        val access=access(profile)
        return mapOf("componentProfile" to profile,"effectiveReadable" to (access["readable"]==true),
            "effectiveWritable" to (access["writable"]==true),"accessStatus" to (access["status"] ?: "UNBOUND"),
            "effectiveAccessProvenance" to access["provenance"],"readable" to (access["readable"]==true),"writable" to (access["writable"]==true))
    }
}

data class CatalogLogicalSpace(val id:String,val instanceId:String,val deviceId:String,val componentId:String,val portId:String,
    val selectorBits:Int,val wordBits:Int,val profiles:List<String>,val registers:List<CatalogLogicalRegister>,val data:Map<String,Any?>) {
    val selectorLimit:Long get()=1L shl selectorBits
}

internal fun protocolDefinitions(data:Map<String,Any?>):List<Pair<Map<String,Any?>,Map<String,Any?>>> =
    rows(data["devices"]).flatMap {d->rows(d["logicalSpaces"] ?: emptyList<Any>()).map {d to it}}

internal fun logicalSpace(instance:Map<String,Any?>,definition:Pair<Map<String,Any?>,Map<String,Any?>>):CatalogLogicalSpace {
    val (device,space)=definition
    return CatalogLogicalSpace(space.text("id"),instance.text("id"),instance.text("deviceId"),device.text("componentId"),space.text("portId"),
        Math.toIntExact(space.long("selectorBits")),Math.toIntExact(space.long("wordBits")),immutableList((device["profiles"] as List<*>).map {it as String}),
        immutableList(rows(space["registers"]).map {r->CatalogLogicalRegister(r.text("id"),Math.toIntExact(r.long("selector")),Math.toIntExact(r.long("wordBits")),
            immutableList(rows(r["fields"]).map {f->CatalogField(f.text("id"),Math.toIntExact(f.long("bitOffset")),Math.toIntExact(f.long("bitWidth")),f)}),r)}),space)
}

internal fun validateProtocolDefinitions(data:Map<String,Any?>,id:(Map<String,Any?>)->Unit) {
    rows(data["devices"]).forEach {if("logicalSpaces" in it)require(it["logicalSpaces"] is List<*>) {"explicit logicalSpaces must be an array, not null"}}
    if("logicalSpaces" in data)require(data["logicalSpaces"] is List<*>) {"explicit logical instances must be an array, not null"}
    val schemaKeys=setOf("id","portId","coordinateKind","selectorBits","wordBits","unit","byteOrder","unknownPolicy","registers","provenance","unknowns","nextValidation")
    protocolDefinitions(data).forEach {(device,space)->
        id(space);require(space.keys==schemaKeys && space["coordinateKind"]=="PROTOCOL_SELECTOR" && space["unit"]=="selector") {"logical space must not contain physical coordinates"}
        require(space.long("selectorBits") in 1..16 && space.long("wordBits") in 1..32 && space["byteOrder"] in setOf("big","little") && space["unknownPolicy"]=="refuse") {"logical dimensions/order/policy invalid"}
        require(space.text("portId").safeId() && rows(space["registers"]).isNotEmpty())
        val profiles=(device["profiles"] as? List<*>)?.map {it as String}?.toSet() ?: error("logical space needs device profiles")
        val selectors=HashSet<Long>();val wordBits=space.long("wordBits");val full=(1L shl wordBits.toInt())-1L
        rows(space["registers"]).forEach {r->
            id(r);require(listOf("selector","wordBits").all {key->r[key] is Byte || r[key] is Short || r[key] is Int || r[key] is Long}) {"logical selector/width requires exact integer"}
            require(r.long("selector") in 0 until (1L shl space.long("selectorBits").toInt()) && selectors.add(r.long("selector"))) {"logical selector outside space or duplicated"}
            require(r.long("wordBits")==wordBits && "offset" !in r && "base" !in r && "size" !in r) {"logical register must use selector and wordBits"}
            val access=obj(r["accessByProfile"]);require(access.keys==profiles) {"logical access must cover exactly owning profiles"}
            access.forEach {(profile,raw)->val a=obj(raw)
                require(a.keys==setOf("readable","writable","status","provenance") && a["readable"] is Boolean && a["writable"] is Boolean && a["status"] in setOf("DISABLED","IMPLEMENTED_FUNCTIONAL_HYPOTHESIS","EXPERIMENTAL_INPUT","NOT_IMPLEMENTED") && a["provenance"] is Map<*,*>) {"logical profile access invalid"}
                if(profile=="disabled")require(a["readable"]==false && a["writable"]==false && a["status"]=="DISABLED") {"disabled logical profile cannot grant access"}
            }
            val masks=obj(r["masks"]);require(masks.keys==setOf("writable","preserve","unknown","prohibited"))
            var occupied=0L;listOf("writable","preserve","unknown","prohibited").forEach {key->val mask=masks.long(key);require(mask>=0 && mask and full.inv()==0L && mask and occupied==0L) {"logical masks overlap/outside word"};occupied=occupied or mask};require(occupied==full) {"logical masks incomplete"}
            var fields=0L;rows(r["fields"]).forEach {f->id(f);val offset=f.long("bitOffset");val width=f.long("bitWidth");require(offset>=0 && width>0 && offset+width<=wordBits) {"logical field outside word"};val mask=((1L shl width.toInt())-1L) shl offset.toInt();require(fields and mask==0L) {"logical fields overlap"};fields=fields or mask;require(f["provenance"] is Map<*,*>)}
            require(fields==full && r["provenance"] is Map<*,*> && r["initial"] is Map<*,*> && r["evidence"] is List<*>) {"logical field coverage/provenance incomplete"}
        }
    }
}

internal fun validateProtocolInstances(data:Map<String,Any?>,id:(Map<String,Any?>)->Unit,instanceIds:Set<String>) {
    val definitions=protocolDefinitions(data).associateBy {it.second.text("id")};val ports=HashSet<String>();val selected=HashSet<String>()
    rows(data["logicalSpaces"] ?: emptyList<Any>()).forEach {instance->
        id(instance);require(instance.keys==setOf("id","space","definitionId","componentId","deviceId","portId")) {"logical instance cannot contain physical mappings"}
        require(rows(data["windows"]).none {it["portId"]==instance["portId"]}) {"logical port cannot alias a CPU MMIO binding"}
        val definition=definitions[instance.text("definitionId")] ?: error("logical definition absent")
        require(instance["space"]==definition.second["id"] && instance["componentId"]==definition.first["componentId"] && instance["portId"]==definition.second["portId"] && instance.text("deviceId") in instanceIds) {"logical instance differs from definition"}
        require(ports.add(instance.text("portId")) && selected.add(instance.text("definitionId"))) {"logical instance/port duplicated"}
    }
    require(selected==definitions.keys) {"every logical definition needs exactly one declared instance"}
}

internal fun logicalDescriptor(space:CatalogLogicalSpace):Map<String,Any?> = mapOf(
    "id" to space.id,"portId" to space.portId,"selectorBits" to space.selectorBits,"wordBits" to space.wordBits,
    "registers" to space.registers.map {r->mapOf("registerId" to r.id,"selector" to r.selector,"wordBits" to r.wordBits,
        "accessByProfile" to obj(r.data["accessByProfile"]).mapValues {(_,v)->obj(v).filterKeys {it in setOf("readable","writable")}})})

internal fun queryLogicalCatalog(spaces:List<CatalogLogicalSpace>,spaceId:String,selector:Long,wordBits:Int,direction:String?,profiles:Map<String,String>):Map<String,Any?> {
    val space=spaces.singleOrNull {it.id==spaceId}
    val path=mutableListOf("logical-space:"+spaceId)
    val register=space?.registers?.singleOrNull {it.selector.toLong()==selector}
    val profile=space?.let {profiles[it.componentId]}
    val access=register?.access(profile).orEmpty()
    val reason=when {
        space==null->"unknown logical protocol space"
        selector<0 || selector>=space.selectorLimit->"selector outside declared logical space"
        wordBits!=space.wordBits->"logical word width differs; width is bits, not CPU bytes"
        direction!=null && direction !in setOf("read","write")->"logical direction must be read or write"
        register==null->"unknown selector; no logical fallback"
        profile==null->"logical component profile is not owner-bound"
        profile !in space.profiles->"unknown logical component profile"
        profile=="disabled"->"logical component is disabled"
        direction=="read" && access["readable"]!=true->"logical register read direction prohibited by profile"
        direction=="write" && access["writable"]!=true->"logical register write direction prohibited by profile"
        direction==null && access["readable"]!=true && access["writable"]!=true->"logical register has no enabled direction"
        else->null
    }
    if(space!=null)path.add("logical-instance:"+space.instanceId)
    if(register!=null)path.add("logical-register:"+register.id)
    return java.util.Collections.unmodifiableMap(linkedMapOf<String,Any?>("classification" to when{space==null->"UNASSIGNED";register==null->"UNKNOWN";else->"LOGICAL_REGISTER"},
        "space" to spaceId,"logicalSpaceId" to space?.id,"logicalInstanceId" to space?.instanceId,"deviceId" to space?.deviceId,
        "componentId" to space?.componentId,"portId" to space?.portId,"componentProfile" to profile,
        "coordinateKind" to "PROTOCOL_SELECTOR","unit" to "selector","selector" to selector,"selectorBits" to space?.selectorBits,
        "wordBits" to wordBits,"requestedWordBits" to wordBits,"declaredWordBits" to space?.wordBits,"direction" to direction,"byteOrder" to space?.data?.get("byteOrder"),"cpuRoutable" to false,"physicalAddress" to null,"registerId" to register?.id,
        "fieldIds" to register?.fields?.map {it.id}.orEmpty(),"register" to register?.data,"knownCoverage" to (register!=null),
        "unknownCoverage" to (space!=null && register==null),"permitted" to (reason==null),"refusalReason" to reason,
        "dynamicReadPerformed" to false,"statefulValidation" to "owner-thread component and protocol transaction; queries never read hardware",
        "resolutionPath" to java.util.Collections.unmodifiableList(path),"provenance" to register?.data?.get("provenance"),
        "effectiveReadable" to (access["readable"]==true),"effectiveWritable" to (access["writable"]==true),
        "accessStatus" to access["status"],"effectiveAccessProvenance" to access["provenance"]))
}

internal fun verifyProtocolContracts(spaces:List<CatalogLogicalSpace>,bindings:List<LogicalPortBinding>) {
    require(bindings.map {it.portId}.distinct().size==bindings.size) {"duplicate logical port binding"}
    bindings.forEach {binding->
        val space=spaces.singleOrNull {it.portId==binding.portId} ?: error("logical port absent from dictionary: "+binding.portId)
        require(binding.componentId==space.componentId) {"logical port component differs: "+binding.portId}
        require(binding.profile in space.profiles && binding.profile!="disabled") {"logical binding profile disabled or unknown: "+binding.profile}
        require(binding.registers.map {it.registerId}.distinct().size==binding.registers.size && binding.registers.map {it.selector}.distinct().size==binding.registers.size) {"duplicate logical register ID/selector"}
        val expected=space.registers.map {it.contract(binding.profile)}.associateBy {it.registerId}
        val actual=binding.registers.associateBy {it.registerId}
        require(actual==expected) {"logical contract differs: port="+binding.portId+" expected="+expected+" actual="+actual}
    }
}
