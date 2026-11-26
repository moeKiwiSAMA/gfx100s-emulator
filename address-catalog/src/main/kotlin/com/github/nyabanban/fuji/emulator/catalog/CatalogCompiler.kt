package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Files
import java.nio.file.Path

object CatalogCompiler {
    fun compile(input:Path):AddressCatalog {
        val root=if(Files.exists(input.resolve("catalog.yaml")))input else input.resolve("catalog")
        val manifest=CatalogIO.readYaml(root.resolve("catalog.yaml"))
        val requiredManifestKeys=setOf("schema","version","architecture","soc","body","firmware","devices","inventory")
        require(manifest.keys.containsAll(requiredManifestKeys)&&manifest.keys.all{it in requiredManifestKeys+"flash"}) {"catalog manifest keys differ"}
        require(valueLong(manifest["schema"])==1L)
        val sourcePaths=mutableListOf<String>()
        fun read(relative:String):Map<String,Any?> {
            val path=root.resolve(relative).normalize();require(path.startsWith(root.normalize())&&path!=root.normalize()){"catalog source escapes root"}
            require(path.toRealPath().startsWith(root.toRealPath())){"catalog source symlink escapes root"}
            sourcePaths.add("catalog/"+relative)
            return CatalogIO.readYaml(path)
        }
        read("catalog.yaml")
        val architecture=read(manifest.text("architecture"));val soc=read(manifest.text("soc"));val body=read(manifest.text("body"));val fw=read(manifest.text("firmware"))
        val devices=(manifest["devices"] as List<*>).map{read(it as String)}
        val inventory=read(manifest.text("inventory"))
        val flash=manifest["flash"]?.let{read(it as String)}
        val schemas=listOf("address-space","device","register","logical-register","protocol-candidate").associateWith{read("schema/"+it+".schema.json")}
        CatalogSchema.validate(architecture,schemas.getValue("address-space"))
        devices.forEach{device->CatalogSchema.validate(device,schemas.getValue("device")); (device["implementationSources"] as List<*>).forEach{source->val file=root.parent.resolve(source as String).normalize();require(file.startsWith(root.parent.normalize())&&Files.isRegularFile(file)){"component implementation source missing: "+source}};rows(device["ports"]).forEach{port->rows(port["registers"]).forEach{CatalogSchema.validate(it,schemas.getValue("register"))}}}
        devices.forEach {d->rows(d["logicalSpaces"] ?: emptyList<Any>()).forEach {s->rows(s["registers"]).forEach {CatalogSchema.validate(it,schemas.getValue("logical-register"))}}}
        devices.forEach {d->rows(d["protocolCandidates"] ?: emptyList<Any>()).forEach {CatalogSchema.validate(it,schemas.getValue("protocol-candidate"))}}
        val ports=devices.flatMap{d->rows(d["ports"]).map{p->p.text("id") to (d to p)}}.toMap()
        fun window(w:Map<String,Any?>):Map<String,Any?> {
            val pair=ports[w.text("definitionId")] ?: error("missing port definition: "+w["definitionId"])
            require(w["componentId"]==pair.first["componentId"])
            return linkedMapOf<String,Any?>("id" to w["id"],"componentId" to w["componentId"],"portId" to w["portId"],"deviceId" to w["deviceId"],
                "definitionId" to w["definitionId"],"space" to "cpu-physical","base" to w.long("base"),"size" to pair.second.long("size"),
                "optional" to w["optional"],"kind" to pair.second["kind"],
                "byteOrder" to "little","backing" to pair.second["backing"],"unknownPolicy" to "refuse","provenance" to w["provenance"],"applicability" to mapOf("body" to body["id"],"firmware" to fw["id"],"sha256" to fw["sha256"]))
        }
        val windows=rows(body["windows"]).map(::window)
        val alternatives=rows(body["profileWindows"] ?: emptyList<Any>()).map { it + mapOf("windows" to rows(it["windows"]).map(::window)) }
        val memory=obj(fw["memoryProfiles"]).mapValues{(_,v)->rows(v).map{r->r+mapOf("base" to r.long("address"),"endExclusive" to Math.addExact(r.long("address"),r.long("size")),"space" to "cpu-physical","backingSpace" to "ram-backing","backingBase" to 0,"backingRegionId" to r["id"],"mappingKind" to "region-relative byte offset; guest virtual mapping remains dynamic","initialValue" to null,"initialSource" to "selected system-memory profile; not a measured reset","provenance" to mapOf("address" to "DAT","permissions" to "HYPOTHESIS","initialValue" to "HYPOTHESIS"))}}
        val legacy=linkedMapOf<String,Any?>("schema" to 1,"id" to fw["id"],"version" to fw["version"],"size" to fw["size"],"sha256" to fw["sha256"],"entry" to fw["entry"],"cpsr" to fw["cpsr"],"loaderMemoryBytes" to fw["loaderMemoryBytes"],"bootMode" to fw["bootMode"],"memoryProfiles" to fw["memoryProfiles"],"sections" to fw["sections"],"devices" to windows.map{w->linkedMapOf<String,Any?>("id" to w["componentId"],"address" to hex(w.long("base")),"size" to hex(w.long("size")),"port" to w["portId"]).apply{if(w["optional"]==true)put("optional",true)}},"evidence" to fw["evidence"])
        val candidates=rows(soc["candidates"]).map { candidate ->
            val port = ports.getValue(candidate.text("definitionId")).second
            require(port["kind"] == "CANDIDATE")
            val region = candidate + mapOf("base" to candidate.long("base"), "endExclusive" to candidate.long("endExclusive"), "registers" to port["registers"])
            if ("wordRuns" in region) compileWordRuns(region, port) else region
        }
        val instances=windows.groupBy{it.text("deviceId")}.map{(id,ws)->mapOf<String,Any?>("id" to id,"componentId" to ws.first()["componentId"],"definitionId" to ("device."+ws.first()["componentId"]),"windowIds" to ws.map{it["id"]},"candidateIds" to candidates.filter{it["deviceId"]==id}.map{it["id"]},"scope" to "logical instance; physical package identity UNKNOWN")}.toMutableList()
        devices.filter{d->windows.none{it["componentId"]==d["componentId"]}}.forEach{d->instances.add(mapOf("id" to ("instance.gfx100s."+d["componentId"]),"componentId" to d["componentId"],"definitionId" to d["id"],"windowIds" to emptyList<String>(),"candidateIds" to emptyList<String>(),"scope" to "host contract; no guest electrical binding qualified"))}
        val logicalInstances=devices.flatMap {d->rows(d["logicalSpaces"] ?: emptyList<Any>()).map {s->mapOf<String,Any?>("id" to ("logical.gfx100s."+s.text("id")),"space" to s["id"],"definitionId" to s["id"],"componentId" to d["componentId"],"deviceId" to instances.single {it["componentId"]==d["componentId"]}["id"],"portId" to s["portId"])}}
        val data=linkedMapOf<String,Any?>("schema" to 1,"version" to manifest["version"],"sourcePaths" to sourcePaths.sorted(),"firmware" to fw.filterKeys{it in setOf("id","version","sha256","size","defaultMemoryProfile")},"spaces" to architecture["spaces"],"logicalSpaces" to logicalInstances,"devices" to devices,"windows" to windows,"instances" to instances,"mirrors" to body["mirrorSets"],"candidates" to candidates,"memoryProfiles" to memory,"mappings" to fw["mappings"],"imageDomains" to fw["imageDomains"],"inventory" to inventory["entries"],"legacyFirmwareMetadata" to legacy,"legacyCatalogs" to fw["legacyCatalogs"])
        if ("diagnosticProfiles" in soc) data["diagnosticProfiles"] = soc["diagnosticProfiles"]
        if ("singleReadDiagnosticProfiles" in soc) data["singleReadDiagnosticProfiles"] = soc["singleReadDiagnosticProfiles"]
        if(alternatives.isNotEmpty()) data["profileWindows"] = alternatives
        if (flash != null) data["flash"] = flash
        validate(data)
        return AddressCatalog(data)
    }
    private fun compileWordRuns(region: Map<String, Any?>, port: Map<String, Any?>): Map<String, Any?> {
        val base = region.long("base")
        val length = region.long("endExclusive") - base
        require(length == port.long("size") && length in 4..1048576 && base % 4 == 0L)
        require(region.ints("widths") == listOf(4) && region.long("alignment") == 4L && region["policy"] == "refuse")
        require(rows(region["prohibited"]).isEmpty())
        val templates = rows(port["registers"]).associateBy { it.text("name") }
        val holes = mutableListOf<Map<String, Long>>()
        var cursor = 0L
        val registers = rows(region["wordRuns"]).map { run ->
            require(run.keys == setOf("first", "last", "template", "access"))
            val first = run.long("first")
            val last = run.long("last")
            require(first in cursor until length && last in first until length && first % 4 == 0L && last % 4 == 0L)
            val template = templates.getValue(run.text("template"))
            require(template.long("size") == 4L && template.long("stride") == 4L && template.long("alignment") == 4L)
            if (run.text("access") != "read") require(template.ints("writeWidths") == listOf(4))
            val reads = when (run.text("access")) {
                "write" -> emptyList()
                "read", "read-write" -> listOf(4).also { require(template.ints("readWidths") == it) }
                else -> error("Unsupported candidate word access")
            }
            if (cursor < first) holes += mapOf("base" to base + cursor, "endExclusive" to base + first)
            cursor = last + 4
            template + mapOf("id" to "${template.text("id")}.${first.toString(16)}", "offset" to first,
                "count" to (last - first) / 4 + 1,
                "readWidths" to reads, "writeWidths" to if (run.text("access") == "read") emptyList<Int>() else listOf(4))
        }
        require(registers.isNotEmpty())
        if (cursor < length) holes += mapOf("base" to base + cursor, "endExclusive" to base + length)
        return region - "wordRuns" + mapOf("prohibited" to holes, "registers" to registers)
    }
    private val SHA256_REGEX = Regex("[a-f0-9]{64}")
    private data class NamedInterval(val base: Long, val endExclusive: Long, val id: Any?)

    private fun ensureDisjointSorted(intervals: List<Interval>, message: () -> String) {
        for (i in 1 until intervals.size) {
            require(intervals[i - 1].endExclusive <= intervals[i].base, message)
        }
    }

    private fun ensureDisjointBetweenSorted(a: List<Interval>, b: List<Interval>, message: () -> String) {
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val left = a[i]
            val right = b[j]
            when {
                left.endExclusive <= right.base -> i++
                right.endExclusive <= left.base -> j++
                else -> throw IllegalArgumentException(message())
            }
        }
    }

    fun validate(data:Map<String,Any?>) = validate(data, validateStaticDefinitions = true)

    internal fun validate(data:Map<String,Any?>, validateStaticDefinitions:Boolean) {
        require(valueLong(data["schema"])==1L&&data.text("version").isNotBlank())
        val sourcePaths=data["sourcePaths"] as? List<*> ?: error("source paths required")
        require(sourcePaths.isNotEmpty()&&sourcePaths.distinct().size==sourcePaths.size&&sourcePaths.all {it is String&&it.startsWith("catalog/")&&!it.split('/').contains("..")}) {"invalid source paths"}
        val firmware=obj(data["firmware"]);require(firmware.text("sha256").matches(SHA256_REGEX))
        val ids=HashSet<String>()
        fun id(row:Map<String,Any?>){val name=row.text("id");require(name.safeId()&&ids.add(name)){"invalid/duplicate ID: "+name}}
        val spaces=rows(data["spaces"])
        val spaceIds=HashSet<String>(spaces.size * 2)
        if (validateStaticDefinitions) {
            spaces.forEach{ id(it);require(it.long("base")==0L&&it.long("endExclusive") in 1..ARM32_LIMIT);spaceIds.add(it.text("id")) }
        } else {
            spaces.forEach{ spaceIds.add(it.text("id")) }
        }
        val portIds=HashSet<String>()
        val componentIds=HashSet<String>()
        val deviceRows=rows(data["devices"])
        val definitions=HashMap<String,Pair<Map<String,Any?>,Map<String,Any?>>>()
        if (validateStaticDefinitions) {
            deviceRows.forEach{d->
                id(d);require(componentIds.add(d.text("componentId"))){"duplicate component definition"}
                val profiles = if ("profiles" in d) {
                    val declared = d["profiles"]
                    require(declared is List<*>) { "device profiles must be an array: "+d["id"] }
                    require(declared.isNotEmpty() && declared.size <= 64 && declared.all { it is String && it.safeId() }) { "device profiles must contain 1..64 non-empty valid IDs: "+d["id"] }
                    require(declared.distinct().size == declared.size) { "duplicate device profile: "+d["id"] }
                    declared.map { it as String }.toSet()
                } else emptySet()
                rows(d["ports"]).forEach{p->
                    id(p);val portId=p.text("id");require(portIds.add(portId))
                    definitions[portId] = d to p
                    val extent=p.long("size");require(extent in 1..ARM32_LIMIT)
                    val regRows=rows(p["registers"])
                    val intervals=ArrayList<NamedInterval>(regRows.size)
                    regRows.forEach{r->
                        id(r);val offset=r.long("offset");val size=r.long("size");val count=r.long("count");val stride=r.long("stride")
                        require(size in 1..4&&count in 1..65536&&stride>=size&&offset>=0&&r.long("alignment") in listOf(1L,2L,4L)) {"register dimensions invalid"}
                        require(Math.addExact(offset,Math.addExact(Math.multiplyExact(count-1,stride),size))<=extent){"register exceeds window"}
                        require((r.ints("readWidths")+r.ints("writeWidths")).all{it in listOf(1,2,4)&&it<=size}){"invalid register width"}
                        if ("writeWidthsByProfile" in r) {
                            val raw = r["writeWidthsByProfile"]
                            require(raw is Map<*, *> && raw.isNotEmpty() && raw.size <= 64 && raw.keys.all { it is String && it.safeId() }) { "writeWidthsByProfile must be a non-empty string-keyed object of at most 64 profiles: "+r["id"] }
                            require(p["kind"] != "CANDIDATE") { "writeWidthsByProfile requires a known component port: "+r["id"] }
                            raw.forEach { (profile, value) ->
                                require(profile in profiles) { "writeWidthsByProfile references unknown owning-device profile: "+profile+" register="+r["id"] }
                                require(value is List<*>) { "writeWidthsByProfile widths must be an array: "+r["id"]+" profile="+profile }
                                val widths = value
                                require(widths.isNotEmpty() && widths.size <= 3 && widths.all { it is Byte || it is Short || it is Int || it is Long }) { "writeWidthsByProfile requires 1..3 integer widths: "+r["id"]+" profile="+profile }
                                val parsed = widths.map(::valueLong)
                                require(parsed.distinct().size == parsed.size) { "duplicate writeWidthsByProfile width: "+r["id"]+" profile="+profile }
                                require(parsed.all { it in listOf(1L,2L,4L) && it <= size }) { "invalid writeWidthsByProfile width: "+r["id"]+" profile="+profile }
                                require(offset % r.long("alignment") == 0L && (count == 1L || stride % r.long("alignment") == 0L) && parsed.all { offset % it == 0L && (count == 1L || stride % it == 0L) }) { "writeWidthsByProfile register alignment invalid: "+r["id"] }
                            }
                        }
                        for (direction in listOf("read", "write")) if ("${direction}OffsetsByWidth" in r) {
                            val lanes=r["${direction}OffsetsByWidth"] as? Map<*,*> ?: error("${direction}OffsetsByWidth must be an object")
                            require(lanes.keys == r.ints("${direction}Widths").map {it.toString()}.toSet()) {"Lanes must cover exactly declared $direction widths"}
                            if (direction == "write") (r["writeWidthsByProfile"] as? Map<*,*>)?.values?.forEach { widths ->
                                require((widths as List<*>).all { valueLong(it).toString() in lanes }) {"Write profile width has no declared lanes"}
                            }
                            lanes.forEach {(key,value) ->
                                val width=(key as String).toInt();val offsets=value as? List<*> ?: error("Offsets must be an array")
                                require(offsets.isNotEmpty() && offsets.size<=4 && offsets.distinct().size==offsets.size && offsets.all {it is Int || it is Long})
                                require(offsets.map(::valueLong).all {lane -> lane>=0 && lane+width<=size && (offset+lane)%width==0L && (offset+lane)%r.long("alignment")==0L && (count==1L || (stride%width==0L && stride%r.long("alignment")==0L))}) {"Invalid $direction offset/width"}
                            }
                        }
                        if ("writeOffsets" in r) {
                            val lanes=r["writeOffsets"] as? List<*> ?: error("writeOffsets must be an array")
                            require(lanes.isNotEmpty() && lanes.size<=4 && lanes.all {it is Int || it is Long} && lanes.distinct().size==lanes.size)
                            require(r.ints("writeWidths").isNotEmpty() && lanes.map(::valueLong).all {lane -> lane>=0 && r.ints("writeWidths").all {width -> lane+width<=size && (offset+lane)%width==0L && (offset+lane)%r.long("alignment")==0L}}) {"Invalid write offset/width"}
                        }
                        if (stride == size) {
                            intervals.add(NamedInterval(offset, offset + count * size, r["id"]))
                        } else {
                            repeat(count.toInt()) { i ->
                                intervals.add(NamedInterval(offset + i * stride, offset + i * stride + size, r["id"]))
                            }
                        }
                        val full=(1L shl(size.toInt()*8))-1
                        val masks=obj(r["masks"]);val maskNames=listOf("writable","preserve","unknown","prohibited")
                        val bits=maskNames.map{masks.long(it)};require(bits.all{it>=0&&it and full.inv()==0L}){"mask outside width"}
                        var occupied=0L;bits.forEach{mask->require(occupied and mask==0L){"overlapping masks"};occupied=occupied or mask};require(occupied==full){"masks do not classify every bit"}
                        var fieldBits=0L
                        rows(r["fields"]).forEach{f->id(f);val start=f.long("bitOffset");val width=f.long("bitWidth");require(start>=0&&width>0&&start+width<=size*8){"field outside register"};val mask=((1L shl width.toInt())-1)shl start.toInt();require(fieldBits and mask==0L){"overlapping fields"};fieldBits=fieldBits or mask;require(f["provenance"] is Map<*,*>)}
                        require(fieldBits==full){"field coverage incomplete: "+r["id"]}
                        require(r["provenance"] is Map<*,*>&&r["initial"] is Map<*,*>&&r["evidence"] is List<*>)
                    }
                    intervals.sortBy { it.base }
                    for (i in 1 until intervals.size) {
                        require(intervals[i - 1].endExclusive <= intervals[i].base) { "register overlap: " + intervals[i].id }
                    }
                }
            }
            validateProtocolDefinitions(data,::id)
            validateProtocolCandidates(data,::id)
            validateDeviceDependencies(deviceRows)
        } else {
            deviceRows.forEach { d ->
                componentIds.add(d.text("componentId"))
                rows(d["ports"]).forEach { p ->
                    val portId = p.text("id")
                    portIds.add(portId)
                    definitions[portId] = d to p
                }
            }
        }
        val instances=rows(data["instances"]?:emptyList<Any>())
        val instanceIds=HashSet<String>(instances.size * 2)
        if (validateStaticDefinitions) {
            instances.forEach{instance->id(instance);require(instance.text("componentId") in componentIds);instanceIds.add(instance.text("id"))}
            validateProtocolInstances(data,::id,instanceIds)
        } else {
            instances.forEach{ instanceIds.add(it.text("id")) }
        }
        val windows=rows(data["windows"]);val intervals=ArrayList<Interval>(windows.size);val bound=HashSet<String>(windows.size * 2)
        windows.forEach{w->id(w);require("registers" !in w){"window must reference the sole generic register definition"};val definition=definitions[w.text("definitionId")] ?: error("window definition absent");require(w["componentId"]==definition.first["componentId"]&&w.long("size")==definition.second.long("size")&&w["kind"]==definition.second["kind"]){"window differs from generic definition"};require(w.text("space") in spaceIds&&w.text("definitionId") in portIds&&w.text("componentId") in componentIds&&w.text("deviceId") in instanceIds);require(bound.add(w.text("portId"))){"duplicate instance port"};intervals.add(Interval(w.long("base"),Math.addExact(w.long("base"),w.long("size"))))}
        intervals.sortBy { it.base }
        ensureDisjointSorted(intervals) { "overlapping windows require explicit alias mapping" }
        windows.forEach { w ->
            rows(definitions.getValue(w.text("definitionId")).second["registers"]).filter { "writeWidthsByProfile" in it }.forEach { r ->
                val address = Math.addExact(w.long("base"), r.long("offset"))
                val widths = obj(r["writeWidthsByProfile"]).values.flatMap { value -> (value as List<*>).map(::valueLong) }
                require(address % r.long("alignment") == 0L && widths.all { address % it == 0L }) { "writeWidthsByProfile window alignment invalid: "+w["id"]+" register="+r["id"] }
            }
        }
        rows(data["mirrors"]?:emptyList<Any>()).forEach{m->id(m);val names=m["windowIds"] as? List<*> ?: error("mirror window references required");val count=m.long("count");val stride=m.long("stride");require(count==names.size.toLong()&&count>=2&&stride>0&&names.distinct().size==names.size){"mirror count/stride invalid"};val selected=names.map{name->windows.singleOrNull{it["id"]==name}?:error("mirror window absent")};val first=selected.first();selected.forEachIndexed{i,w->require(w["componentId"]==first["componentId"]&&w["definitionId"]==first["definitionId"]&&w.long("size")==first.long("size")&&w.long("base")==Math.addExact(first.long("base"),Math.multiplyExact(i.toLong(),stride))){"mirror stride/definition mismatch"}}}
        obj(data["memoryProfiles"]).forEach{(name,value)->
            require(name.safeId())
            val regionRows=rows(value)
            val ram=ArrayList<Interval>(regionRows.size)
            val names=HashSet<String>(regionRows.size * 2)
            regionRows.forEach{r->
                require(names.add(r.text("id")))
                require(r.long("permissions") in 1..7){"RAM overlap/permission invalid"}
                ram.add(Interval(r.long("base"),r.long("endExclusive")))
            }
            ram.sortBy { it.base }
            ensureDisjointSorted(ram) { "RAM overlap/permission invalid" }
            ensureDisjointBetweenSorted(ram, intervals) { "RAM overlap/permission invalid" }
        }
        require(firmware.text("defaultMemoryProfile") in obj(data["memoryProfiles"]))
        val candidateRows=rows(data["candidates"])
        val cs=ArrayList<Interval>(candidateRows.size)
        candidateRows.forEach{c->
            id(c);require(c.text("space")=="cpu-physical"&&c.text("deviceId") in instanceIds)
            val span=Interval(c.long("base"),c.long("endExclusive"))
            require(span.size<=1024*1024){"candidate ownership overlap"}
            cs.add(span)
            require(c.text("policy") in setOf("refuse","image-backed","fixed-input","retained-bytes","scripted-read"))
            require(c.text("byteOrder") in setOf("little","big")&&c.long("alignment") in listOf(1L,2L,4L)&&c.ints("widths").all{it in listOf(1,2,4)})
            c["initialFill"]?.let{require(valueLong(it) in 0..255)}
            if(c["policy"]=="retained-bytes")require(c["initialFill"]!=null){"retained bytes require an explicit fill"}
            windows.filter{Interval(it.long("base"),it.long("base")+it.long("size")).overlaps(span)}.forEach{w->require(w.text("componentId")=="soc-sd-host"&&span.contains(w.long("base"),w.long("size").toInt())){"candidate crosses another owner"}}
            val prohibitedRows=rows(c["prohibited"])
            val holes=ArrayList<Interval>(prohibitedRows.size)
            prohibitedRows.forEach{p->
                val hole=Interval(p.long("base"),p.long("endExclusive"))
                require(hole.base>=span.base&&hole.endExclusive<=span.endExclusive){"invalid prohibited hole"}
                holes.add(hole)
            }
            holes.sortBy { it.base }
            ensureDisjointSorted(holes) { "invalid prohibited hole" }
            ensureDisjointBetweenSorted(holes, intervals) { "invalid prohibited hole" }
            rows(c["groups"]).forEach{g->id(g);require(g.long("base")>=span.base&&g.long("endExclusive")<=span.endExclusive){"candidate subgroup outside owner"}}
        }
        cs.sortBy { it.base }
        ensureDisjointSorted(cs) { "candidate ownership overlap" }
        if (validateStaticDefinitions) {
            rows(data["mappings"]).forEach{m->id(m);require(m.text("sourceSpace") in spaceIds&&m.text("targetSpace") in spaceIds);if(m["sourceBase"]!=null){val span=Interval(m.long("sourceBase"),m.long("sourceEndExclusive"));if(m["kind"]=="linear")require(m.long("targetBase")>=0&&m.long("targetBase")<=ARM32_LIMIT-span.size){"mapping overflow"}}}
            val domainRows=rows(data["imageDomains"]?:emptyList<Any>())
            val images=ArrayList<Interval>(domainRows.size)
            domainRows.forEach{d->
                id(d);require(d.text("space")=="flash-logical")
                val extent=Interval(d.long("base"),d.long("endExclusive"))
                images.add(extent)
                val writableRows=rows(d["writable"])
                val writable=ArrayList<Interval>(writableRows.size)
                writableRows.forEach{w->
                    val region=Interval(w.long("base"),w.long("endExclusive"))
                    require(region.base>=extent.base&&region.endExclusive<=extent.endExclusive){"writable interval outside one image domain"}
                    writable.add(region)
                }
                writable.sortBy { it.base }
                ensureDisjointSorted(writable) { "writable interval outside one image domain" }
            }
            images.sortBy { it.base }
            ensureDisjointSorted(images) { "image domains overlap" }
            rows(data["inventory"]).forEach(::id)
        }
        validateRetainedDiagnostics(data, ::id)
        validateSingleReadDiagnostics(data, ::id)
        if (validateStaticDefinitions) {
            validateProfileWindows(data, ::id)
            data["flash"]?.let { FlashDictionary.validate(obj(it), firmware) }
        }
    }
    fun generate(repository:Path,resources:Path) {
        generate(compile(repository),repository,resources)
    }
    private fun generate(catalog:AddressCatalog,repository:Path,resources:Path) {
        val legacyCatalogs=obj(catalog.data["legacyCatalogs"])
        CatalogIO.writeJson(repository.resolve("catalog/soc/xp4/soc.json"),legacyCatalogs["soc"])
        CatalogIO.writeJson(repository.resolve("catalog/bodies/gfx100s/body.json"),legacyCatalogs["body"])
        CatalogIO.writeJson(repository.resolve("catalog/firmware/gfx100s/2.13/firmware.json"),legacyCatalogs["firmware"])
        val bytes=CatalogIO.compiledBytes(catalog)
        val compiled=repository.resolve("catalog/compiled")
        Files.createDirectories(compiled);Files.write(compiled.resolve("catalog.json"),bytes)
        Files.createDirectories(resources.resolve("catalog"));Files.write(resources.resolve("catalog/compiled.json"),bytes)
        catalog.componentIds.forEach{id->CatalogIO.writeJson(compiled.resolve("components/"+id+".json"),catalog.componentDescriptor(id))}
        generateGpioButtons(catalog, compiled)
        generatePowerButton(catalog, compiled)
        generateMenuAdc(catalog, compiled)
        generateDirectionInput(catalog, compiled)
        CatalogIO.writeJson(compiled.resolve("firmware-metadata.json"),catalog.legacyFirmwareMetadata())
        profileWindowRows(catalog.data).forEach { variant -> CatalogIO.writeJson(compiled.resolve("firmware-profiles/"+variant.text("profile")+".json"),catalog.firmwareMetadata(mapOf(variant.text("componentId") to variant.text("profile")))) }
        CatalogIO.writeJson(repository.resolve("configurations/jvm/firmware/gfx100s-213.yaml"),catalog.legacyFirmwareMetadata())
        CatalogIO.writeJson(compiled.resolve("manifest.json"),mapOf("schema" to 1,"version" to catalog.version,"artifactSha256" to catalog.artifactSha256,"sourcePaths" to catalog.data["sourcePaths"]))
        val doc=StringBuilder("# Generated address dictionary\n\nGenerated from catalog/catalog.yaml by :address-catalog:compileCatalog. Do not edit.\n\nDictionary "+catalog.version+" / SHA256 "+catalog.artifactSha256+". Firmware "+catalog.firmwareId+" / "+catalog.firmwareSha256+".\n\nAll intervals are half open. Candidate envelope lengths and fill values are HYPOTHESIS, not measured hardware spans or reset values. Component behavior and lifecycle checks remain active.\n\n| Window | Component / port | Physical interval | Kind | Register definitions |\n| --- | --- | --- | --- | ---: |\n")
        catalog.windows.forEach{w->doc.append("| "+w.id+" | "+w.portId+" | ["+hex(w.base)+", "+hex(w.endExclusive)+") | "+w.kind+" | "+w.registers.size+" |\n")}
        if(profileWindowRows(catalog.data).isNotEmpty()) {
            doc.append("\n## Explicit profile layouts\n\nGenerated from body.profileWindows; default windows above remain unchanged. Each layout atomically replaces its listed ports and must pass ordinary non-overlap validation.\n\n")
            profileWindowRows(catalog.data).forEach {v->doc.append("- "+v.text("componentId")+" / "+v.text("profile")+": replace "+(v["replacePortIds"] as List<*>).size+" ports with "+rows(v["windows"]).size+" optional windows. Metadata: catalog/compiled/firmware-profiles/"+v.text("profile")+".json\n")}
        }
        doc.append("\n## Finite discovery ownership\n\n| ID | Interval | Policy | Explicit fill | Prohibited holes |\n| --- | --- | --- | --- | --- |\n")
        catalog.candidates.forEach{c->doc.append("| "+c.id+" | ["+hex(c.base)+", "+hex(c.endExclusive)+") | "+c.policy+" | "+c.initialFill+" (HYPOTHESIS) | "+c.prohibited.size+" |\n")}
        doc.append("\nControl group B is a labelled subgroup inside each PHY candidate owner; it never installs a second overlapping fallback. Unknown gaps outside these owners refuse. Flash aperture/controller/protocol and logical-image permissions remain separate.\n\n")
        if (catalog.diagnosticProfiles.isNotEmpty()) {
            doc.append("## Explicit retained-write diagnostic profiles\n\nThese opt-in routes use observed callback coordinates under HYPOTHESIS. They do not establish a physical device or change ordinary catalog address queries, RAM, Flash or passive inventory. No initial value, completion, automatic clear or events are supplied.\n\n| Profile | Observed route | Width | First write | Successful read limit | Repeated write |\n| --- | --- | ---: | ---: | ---: | --- |\n")
            catalog.diagnosticProfiles.forEach { p -> doc.append("| "+p.id+" | ["+hex(p.base)+", "+hex(p.endExclusive)+") | "+p.width+" | "+p.writeValue+" | "+p.successfulReadLimit+" | refuse |\n") }
            doc.append("\n")
        }
        if (catalog.singleReadDiagnosticProfiles.isNotEmpty()) {
            doc.append("## Explicit single-read diagnostic profiles\n\nHYPOTHESIS only: one zero sample followed by refusal, on top of the named retained parent. No physical identity, reset, hardware function, completion or automatic event is asserted. Ordinary address queries and passive inventory remain unchanged.\n\n| Profile | Parent | Observed callback route | Width | Samples | Exhaustion |\n| --- | --- | --- | ---: | --- | --- |\n")
            catalog.singleReadDiagnosticProfiles.forEach { p -> doc.append("| "+p.id+" | "+p.parentProfile+" | ["+hex(p.base)+", "+hex(p.endExclusive)+") | "+p.width+" | "+p.samples+" | refuse |\n") }
            doc.append("\n")
        }
        doc.append("## Flash logical domains\n\nThese independent domains describe supplied inputs. Listing does not activate backing or grant direct aperture writes. Adjacent reads never merge erase/program permissions.\n\n| Domain | Logical interval | Writable subrange | Activation |\n| --- | --- | --- | --- |\n")
        rows(catalog.data["imageDomains"]).forEach{d->doc.append("| "+d["id"]+" | ["+hex(d.long("base"))+", "+hex(d.long("endExclusive"))+") | "+rows(d["writable"]).joinToString{"["+hex(it.long("base"))+", "+hex(it.long("endExclusive"))+")"}+" | "+d["activation"]+" |\n")}
        doc.append("\n## Memory profile regions\n\n")
        catalog.memoryProfiles.forEach{(name,regions)->doc.append("### "+name+"\n\n| Region | Physical interval | Permission bits |\n| --- | --- | --- |\n");regions.forEach{r->doc.append("| "+r["id"]+" | ["+hex(r.long("base"))+", "+hex(r.long("endExclusive"))+") | "+r["permissions"]+" |\n")};doc.append("\n")}
        doc.append("\n## Logical protocol spaces\n\nSelectors use their own namespace and bit-width. They are not CPU byte addresses, do not allocate backing, and cannot grant MMIO access. Unknown selectors refuse.\n\n| Logical space | Component / port | Selector bits / word bits | CPU physical base |\n| --- | --- | --- | --- |\n")
        catalog.logicalSpaces.forEach {s->doc.append("| "+s.id+" | "+s.componentId+" / "+s.portId+" | "+s.selectorBits+" / "+s.wordBits+" | not assigned / not CPU-routable |\n")}
        doc.append("\n## Unassigned protocol request candidates\n\nThese namespaces record firmware requests only. Transport identity is not physical device ownership. They install no endpoint, CPU mapping or fallback and always refuse access.\n\n| Namespace | Transport | Selector bits / raw word bits | Qualified owner / behavior |\n| --- | --- | --- | --- |\n")
        catalog.protocolCandidateSpaces.forEach {s->doc.append("| "+s.id+" | "+s.transportComponentId+" / "+s.transportPort+" | "+s.selectorBits+" / "+s.wordBits+" | UNKNOWN / NOT_IMPLEMENTED |\n")}
        CatalogIO.writeText(repository.resolve("docs/generated/address-space.md"),doc.toString())
        val fields=StringBuilder("# Generated device and field dictionary\n\nGenerated from catalog/devices/*.yaml by :address-catalog:compileCatalog. Do not edit. Property-level evidence is queryable through the same compiled model.\n\nwriteWidths is the base declaration for unbound and other profiles. Optional writeWidthsByProfile selects an exact list only from the actual owner-bound component profile; queries report componentProfile, declaredWriteWidths and effectiveWriteWidths separately. Profile permissions remain subject to component state, lifecycle and whole-access validation. No ready, IRQ or DMA behavior is implied.\n\n")
        rows(catalog.data["devices"]).forEach{d->fields.append("## "+d["componentId"]+"\n\nScope: "+d["implementationStatus"]+"; physical identity "+d["physicalIdentity"]+". Implementation paths identify sources; build and run records carry their Git revision.\n\nSources: "+(d["implementationSources"] as List<*>).joinToString(", "){path->"["+path.toString().substringAfterLast('/')+"](../../"+path+")"}+"\n\n");rows(d["ports"]).forEach{p->fields.append("### "+p["id"]+"\n\n| Register ID | Offset / count / stride | Read / write widths | Status | Fields / definition confidence | Behavior | Property provenance | Evidence |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n");rows(p["registers"]).forEach{r->fields.append("| "+r["id"]+" | "+hex(r.long("offset"))+" / "+r["count"]+" / "+r["stride"]+" | "+r["readWidths"]+" / "+r["writeWidths"]+(r["writeWidthsByProfile"]?.let { " (owner-bound profile overrides: "+it+")" } ?: "")+" | "+r["status"]+" | "+rows(r["fields"]).joinToString("; "){it["id"].toString()+"["+it["bitOffset"]+":"+it["bitWidth"]+"] "+obj(obj(it["provenance"])["definition"])["confidence"]}+" | "+r["behaviorId"]+" | "+obj(r["provenance"]).entries.joinToString("; "){it.key+"="+obj(it.value)["confidence"]}+" | "+(r["evidence"] as List<*>).filter{it !in (d["implementationSources"] as List<*>)}.joinToString("; "){path->"[raw](../../"+path+")"}.ifEmpty{"implementation source index above; no physical qualification"}+" |\n")};fields.append("\n")}}
        catalog.logicalSpaces.forEach {s->
            fields.append("\n## Logical protocol: "+s.id+"\n\nComponent "+s.componentId+", port "+s.portId+". Selector unit; "+s.selectorBits+" selector bits / "+s.wordBits+" word bits, wire order "+s.data["byteOrder"]+". No CPU physical base or device read is implied. Access is selected only by the actual owner profile; stateful/transaction checks remain in Kotlin. Initial/reset values are UNKNOWN.\n\n| Register ID | Selector | Profile read / write / status | Fields | Evidence |\n| --- | --- | --- | --- | --- |\n")
            s.registers.forEach {r->fields.append("| "+r.id+" | "+hex(r.selector.toLong())+" | "+obj(r.data["accessByProfile"]).entries.joinToString("; "){(p,a)->val access=obj(a);p+": "+access["readable"]+" / "+access["writable"]+" / "+access["status"]}+" | "+r.fields.joinToString("; "){it.id+"["+it.bitOffset+":"+it.bitWidth+"]"}+" | "+(r.data["evidence"] as List<*>).joinToString("; "){"[source](../../"+it+")"}+" |\n")}
        }
        catalog.protocolCandidateSpaces.forEach {s->
            fields.append("\n## Unassigned protocol requests: "+s.id+"\n\nApplicable body/firmware: "+s.data["applicability"]+". Host transport "+s.transportComponentId+" / "+s.transportPort+"; physical owner UNKNOWN. No endpoint binding, CPU address, initial value or behavior is provided. A read request is evidence of direction, never permission. All queries refuse.\n\n| Request ID | Selector | Observation | Definition / initial / behavior | Unknown raw fields | Evidence |\n| --- | --- | --- | --- | --- | --- |\n")
            s.requests.forEach {r->fields.append("| "+r.id+" | "+hex(r.selector.toLong())+" | "+r.data["observationStatus"]+" | raw"+r.wordBits+" "+obj(obj(r.data["provenance"])["width"])["confidence"]+" / "+obj(obj(r.data["provenance"])["initial"])["confidence"]+" / "+r.data["implementationStatus"]+" | "+r.fields.joinToString("; "){it.id+"["+it.bitOffset+":"+it.bitWidth+"]"}+" | "+(r.data["evidence"] as List<*>).joinToString("; "){"[source](../../"+it+")"}+" |\n")}
        }
        CatalogIO.writeText(repository.resolve("docs/generated/devices.md"),fields.toString())
        catalog.data["flash"]?.let {
            CatalogIO.writeText(repository.resolve("docs/generated/flash-map.md"), FlashDictionary.renderMarkdown(obj(it), catalog))
        }
    }
    internal fun generateGpioButtons(catalog: AddressCatalog, compiled: Path) {
        val device = rows(catalog.data["devices"]).singleOrNull { it["componentId"] == "soc-gpio" }
        val profiles = (device?.get("profiles") as? List<*>).orEmpty()
        val v1 = "gfx100s-2.13-gpio-buttons-v1"
        val v2 = "gfx100s-2.13-gpio-buttons-v2"
        val v3 = "gfx100s-2.13-gpio-buttons-v3"
        val v4 = "gfx100s-2.13-gpio-buttons-v4"
        val v5 = "gfx100s-2.13-gpio-buttons-v5"
        val v6 = "gfx100s-2.13-gpio-buttons-v6"
        val selected = v1 in profiles || v2 in profiles || v3 in profiles || v4 in profiles || v5 in profiles || v6 in profiles
        val bindings = if(selected) {
            val input = rows(rows(device!!.get("ports")).single { it["id"] == "definition.soc-gpio.main" }["registers"]).single { it["id"] == "reg.soc-gpio.main.o14" }
            rows(input["connections"]).also { connections ->
                require(connections.all { it["profile"] in setOf(v1,v2,v3,v4,v5,v6,"gfx100s-2.13-direction-input-v1") }) { "unknown GPIO button connection profile" }
            }.filter { it["profile"] != "gfx100s-2.13-direction-input-v1" }
        } else emptyList()
        require(v6 !in profiles || v5 in profiles) { "GPIO v6 requires the v5 DRIVE/AF-ON/Focus Mode profile" }
        require(v5 !in profiles || v4 in profiles) { "GPIO v5 requires the v4 STILL/MOVIE profile" }
        require(v4 !in profiles || v3 in profiles) { "GPIO v4 requires the v3 shortcut profile" }
        require(v3 !in profiles || v2 in profiles) { "GPIO v3 requires the PLAY profile" }
        require(v2 !in profiles || v1 in profiles) { "GPIO v2 requires the original three-switch profile" }
        val base = bindings.filter { it["profile"] == v1 }
        fun export(profile: String, switches: List<Map<String, Any?>>, keys: Set<String>): String {
            require(switches.size == keys.size && switches.map { it["buttonKey"] }.toSet() == keys) { "GPIO button wiring must define exactly the selected switches" }
            val coordinates = HashSet<Pair<Long, Long>>()
            val text = StringBuilder("profile=$profile\n")
            switches.sortedBy { it.text("buttonKey") }.forEach { binding ->
                val bank = binding.long("bank"); val pin = binding.long("pin")
                require(bank in 0..15 && pin in 0..15 && coordinates.add(bank to pin) && binding["activeLow"] is Boolean && binding["confidence"] == "SOURCE") { "invalid/duplicate GPIO button wiring" }
                text.append(binding.text("buttonKey")).append('=').append(bank).append(',').append(pin).append(',').append(binding["activeLow"]).append('\n')
            }
            return text.toString()
        }
        val keys = setOf("back", "shutterHalf", "shutterFull")
        val v2Bindings = base + bindings.filter { it["profile"] == v2 }
        val v3Bindings = v2Bindings + bindings.filter { it["profile"] == v3 }
        val v4Bindings = v3Bindings + bindings.filter { it["profile"] == v4 }
        val v5Bindings = v4Bindings + bindings.filter { it["profile"] == v5 }
        val v6Bindings = v5Bindings + bindings.filter { it["profile"] == v6 }
        val original = if(v1 in profiles) export(v1, base, keys) else null
        val expanded = if(v2 in profiles) export(v2, v2Bindings, keys + "play") else null
        val withShortcutKeys = if(v3 in profiles) export(v3, v3Bindings, keys + setOf("play", "delete", "rearDialPress")) else null
        val withStillMovie = if(v4 in profiles) export(v4, v4Bindings, keys + setOf("play", "delete", "rearDialPress", "movieMode")) else null
        val withDriveAfFocus = if(v5 in profiles) export(v5, v5Bindings, keys + setOf("play", "delete", "rearDialPress", "movieMode", "drive", "afOn", "focusMode")) else null
        val withFrontDialFn4Stick = if(v6 in profiles) export(v6, v6Bindings, keys + setOf("play", "delete", "rearDialPress", "movieMode", "drive", "afOn", "focusMode", "frontDialPress", "fn4", "focusStickPress")) else null
        for((name, content) in listOf("gpio-buttons.properties" to original, "gpio-buttons-v2.properties" to expanded, "gpio-buttons-v3.properties" to withShortcutKeys, "gpio-buttons-v4.properties" to withStillMovie, "gpio-buttons-v5.properties" to withDriveAfFocus, "gpio-buttons-v6.properties" to withFrontDialFn4Stick)) {
            val target = compiled.resolve(name)
            if(content == null) Files.deleteIfExists(target) else CatalogIO.writeText(target, content)
        }
    }
    internal fun generatePowerButton(catalog: AddressCatalog, compiled: Path) {
        val target=compiled.resolve("power-button.properties")
        val device=rows(catalog.data["devices"]).singleOrNull { it["componentId"]=="power-peer" }
        val raw=device?.get("powerButtonBinding")
        if(raw==null) {Files.deleteIfExists(target);return}
        val binding=obj(raw)
        val profile=binding.text("profile");val selector=binding.long("selector");val bit=binding.long("bit")
        require(profile=="gfx100s-2.13-power-switch-v1" && binding["buttonKey"]=="power" && binding["confidence"]=="SOURCE")
        require(selector in 0..127 && bit in 0..15 && binding["activeLow"] is Boolean)
        CatalogIO.writeText(target,"profile=$profile\npower=$selector,$bit,${binding["activeLow"]}\n")
    }
    internal fun generateMenuAdc(catalog: AddressCatalog,compiled: Path) {
        val target=compiled.resolve("menu-adc.properties")
        val device=rows(catalog.data["devices"]).singleOrNull {it["componentId"]=="soc-adc"}
        val bindings=device?.let{rows(it["ports"]).flatMap{port->rows(port["registers"]).flatMap{r->rows(r["connections"]).filter{b->b["profile"]=="gfx100s-2.13-menu-adc-v1"}.map{b->r to b}}}}.orEmpty()
        if(bindings.isEmpty()){Files.deleteIfExists(target);return}
        val (register,binding)=bindings.single();val channel=binding.long("channel")
        require(channel in 0..11 && register.long("offset")==0x10+4*channel && binding["buttonKey"]=="menu" && binding["confidence"]=="SOURCE")
        CatalogIO.writeText(target,"profile=gfx100s-2.13-menu-adc-v1\nmenu=$channel\n")
    }
    internal fun generateDirectionInput(catalog: AddressCatalog, compiled: Path) {
        val profile="gfx100s-2.13-direction-input-v1"
        val target=compiled.resolve("direction-input.properties")
        fun bindings(component:String)=rows(catalog.data["devices"]).filter { it["componentId"]==component }.flatMap { d ->
            rows(d["ports"]).flatMap { p -> rows(p["registers"]).flatMap { r ->
                rows(r["connections"]).filter { it["profile"]==profile }.map { r to it }
            } }
        }
        val adc=bindings("soc-adc");val gpio=bindings("soc-gpio")
        if(adc.isEmpty() && gpio.isEmpty()) {Files.deleteIfExists(target);return}
        require(adc.size==1 && gpio.size==1) { "Direction input requires one ADC binding and one GPIO gate" }
        val (result,a)=adc.single();val (input,g)=gpio.single()
        val keys=listOf("up","right","down","left")
        require(a["confidence"]=="SOURCE" && g["confidence"]=="SOURCE" && a["buttonKeys"]==keys && g["buttonKeys"]==keys)
        val channel=a.long("channel");val bank=g.long("bank");val pin=g.long("pin")
        require(channel in 0..11 && result.long("offset")==0x10+4*channel && result["writeWidths"]==emptyList<Any>())
        require(input.long("offset")==0x14L && input.long("stride")==64L && bank>=0 && bank<input.long("count") && bank<=15 && pin in 0..15 && g["activeLow"] is Boolean)
        val expected=linkedMapOf("up" to 1L,"upRight" to 3L,"right" to 2L,"downRight" to 6L,"down" to 4L,"downLeft" to 12L,"left" to 8L,"upLeft" to 9L)
        val directions=rows(a["directions"])
        require(directions.size==8 && directions.map { it["key"] }.toSet()==expected.keys)
        directions.forEach { d -> require(d.long("mask")==expected[d.text("key")] && d.long("minimum") in 0..4095 && d.long("maximum") in d.long("minimum")..4095) }
        directions.sortedBy { it.long("minimum") }.zipWithNext().forEach { (left,right) -> require(left.long("maximum")<right.long("minimum")) { "Direction intervals overlap" } }
        val text=buildString {
            append("profile=$profile\nadcChannel=$channel\ngpioBank=$bank\ngpioPin=$pin\ngpioActiveLow=${g["activeLow"]}\nbuttonKeys=${keys.joinToString(",")}\n")
            expected.keys.forEach { key -> val d=directions.single { it["key"]==key };append("$key=${d.long("mask")},${d.long("minimum")},${d.long("maximum")}\n") }
        }
        CatalogIO.writeText(target,text)
    }
    /** Recompute expected outputs without touching source or installed generated artifacts. */
    fun verify(repository:Path,generatedRoot:Path=repository) {
        val catalog=compile(repository)
        catalog.data["flash"]?.let { FlashDictionary.verifyReleaseTemplate(obj(it), repository) }
        val temporary=Files.createTempDirectory("fuji-catalog-verify-")
        try {
            val resources=temporary.resolve("resources")
            generate(catalog,temporary,resources)
            Files.walk(temporary).use {files->files.filter {Files.isRegularFile(it)&&!it.startsWith(resources)}.forEach {expected->
                val relative=temporary.relativize(expected);val actual=generatedRoot.resolve(relative)
                require(Files.isRegularFile(actual)&&Files.mismatch(expected,actual)==-1L) {"Generated catalog artifact differs: "+relative}
            }}
        } finally {Files.walk(temporary).use {files->files.sorted(java.util.Comparator.reverseOrder()).forEach {Files.delete(it)}}}
    }
    private fun hex(value:Long)="0x"+value.toString(16)
}
fun main(args:Array<String>) {
    if(args.size>=3 && args[0]=="--export-firmware-metadata") {
        val catalog=AddressCatalog.load(Path.of(args[1]))
        val out=Path.of(args[2])
        val rest=args.drop(3)
        val bootRomMode=rest.contains("--boot-rom")
        val profiles=rest.filterNot { it == "--boot-rom" }.associate { entry ->
            val parts=entry.split('=',limit=2)
            require(parts.size==2&&parts[0].isNotBlank()&&parts[1].isNotBlank()){"Invalid profile pair: "+entry}
            parts[0] to parts[1]
        }
        val legacy=catalog.legacyFirmwareMetadata()
        val selected=catalog.firmwareMetadata(profiles)
        val merged=if(profiles.isEmpty()) legacy else {
            val kept=rows(legacy["devices"]).filterNot { it["id"] in profiles.keys }
            val replaced=rows(selected["devices"]).filter { it["id"] in profiles.keys }
            legacy+("devices" to (kept+replaced))
        }
        if(bootRomMode) {
            val bootRomRegions=listOf(
                linkedMapOf<String,Any?>("id" to "boot-rom","address" to "0xfff00000","size" to "0x8000","permissions" to 5),
                linkedMapOf<String,Any?>("id" to "boot-rom-stage1-sram","address" to "0xfff10000","size" to "0x2000","permissions" to 7),
                linkedMapOf<String,Any?>("id" to "boot-rom-sram","address" to "0xfff2e000","size" to "0x2000","permissions" to 3),
            )
            val legacyProfiles=obj(merged["memoryProfiles"])
            val coldStartV1=listOf(
                linkedMapOf<String,Any?>("id" to "loader","address" to "0x0","size" to "0x20000","permissions" to 7),
            )+bootRomRegions
            val segmentsV2=rows(legacyProfiles.getValue("loader-segments-experimental-v4"))+bootRomRegions
            val fullBootV1=rows(legacyProfiles.getValue("flashload-request-buffer-experimental-v24"))+bootRomRegions
            val devices=rows(merged["devices"]).let { existing ->
                if(existing.any { it["port"] == "soc-system-control.boot-strap" }) existing
                else existing+linkedMapOf<String,Any?>(
                    "id" to "soc-system-control",
                    "address" to "0xfff4a000",
                    "size" to "0x4",
                    "port" to "soc-system-control.boot-strap",
                    "optional" to true,
                )
            }
            val bootRomMeta=linkedMapOf<String,Any?>().apply {
                putAll(merged)
                put("entry","0xfff00000")
                put("bootMode","boot-rom-experiment")
                put("bootRom",linkedMapOf<String,Any?>(
                    "address" to "0xfff00000",
                    "size" to "0x8000",
                    "sha256" to "2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4",
                ))
                put("memoryProfiles",linkedMapOf<String,Any?>(
                    "boot-rom-cold-start-experimental-v1" to coldStartV1,
                    "boot-rom-cold-start-segments-experimental-v2" to segmentsV2,
                    "boot-rom-full-boot-v1" to fullBootV1,
                ))
                put("devices",devices)
            }
            catalog.validateBootRomExperiment(bootRomMeta)
            CatalogIO.writeJson(out,bootRomMeta)
            return
        }
        catalog.verifyStockDeclaration(merged)
        CatalogIO.writeJson(out,merged)
        return
    }
    require(args.size==2){"usage: CatalogCompiler <repository> <generated-resources|--verify>"}
    if(args[1]=="--verify") {
        CatalogCompiler.verify(Path.of(args[0]))
        println("Catalog metadata and generated artifacts match YAML sources")
    } else CatalogCompiler.generate(Path.of(args[0]),Path.of(args[1]))
}
