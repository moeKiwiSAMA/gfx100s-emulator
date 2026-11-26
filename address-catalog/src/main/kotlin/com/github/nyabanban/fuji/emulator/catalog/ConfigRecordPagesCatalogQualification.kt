package com.github.nyabanban.fuji.emulator.catalog

import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/** Exact catalog delta and actual restart-only parameter declaration; no guest execution. */
object ConfigRecordPagesCatalogQualification {
    const val BASELINE="e335c3816f49f945ce8852c2ec25147b9631764a"
    const val PARENT="kernel-paired-flash-read-buffer-experimental-v14"
    const val PROFILE="kernel-config-record-pages-experimental-v15"
    const val REGION="config-record-pages"
    private const val FACTORY="components/system-memory/src/main/kotlin/com/github/nyabanban/fuji/emulator/components/memory/MemoryFactory.kt"
    private fun git(root:Path,vararg args:String):ByteArray {
        val p=ProcessBuilder(listOf("git")+args).directory(root.toFile()).start()
        val bytes=p.inputStream.use {it.readAllBytes()};val error=p.errorStream.use {it.readAllBytes()}
        check(p.waitFor()==0) {error.toString(Charsets.UTF_8)}
        return bytes
    }
    fun baseline(root:Path)=CatalogIO.readCompiled(git(root,"show",BASELINE+":catalog/compiled/catalog.json"))
    private fun same(a:Any?,b:Any?)=CatalogIO.canonical(a).contentEquals(CatalogIO.canonical(b))
    @Suppress("UNCHECKED_CAST") private fun mutable(data:Map<String,Any?>)=CatalogIO.json.readValue(CatalogIO.canonical(data),MutableMap::class.java) as MutableMap<String,Any?>
    @Suppress("UNCHECKED_CAST") fun restoreG12(data:Map<String,Any?>):Map<String,Any?> {
        val copy=mutable(data)
        val memory=copy["memoryProfiles"] as MutableMap<String,Any?>;check(memory.containsKey(PROFILE));memory.remove(PROFILE)
        val metadata=copy["legacyFirmwareMetadata"] as MutableMap<String,Any?>
        val legacy=metadata["memoryProfiles"] as MutableMap<String,Any?>;check(legacy.containsKey(PROFILE));legacy.remove(PROFILE)
        return copy
    }
    fun verifyCatalog(old:AddressCatalog,current:AddressCatalog):Map<String,Any?> {
        check(current.defaultMemoryProfile==PARENT && old.defaultMemoryProfile==PARENT)
        check(current.memoryProfiles.keys==old.memoryProfiles.keys+PROFILE)
        for((name,regions) in old.memoryProfiles)check(same(regions,current.memoryProfiles.getValue(name))) {"Existing memory profile differs: $name"}
        val raw=obj(current.legacyFirmwareMetadata()["memoryProfiles"])
        val parent=rows(raw.getValue(PARENT));val selected=rows(raw.getValue(PROFILE))
        check(selected.size==parent.size+1 && same(selected.dropLast(1),parent))
        val added=selected.last()
        check(added.keys==setOf("id","address","size","permissions") && added["id"]==REGION && valueLong(added["address"])==0x4e00000L && valueLong(added["size"])==0x3000L && valueLong(added["permissions"])==3L)
        check(same(restoreG12(current.data),old.data)) {"Deleting only the two new profile declarations must restore all G12 data"}
        check(current.componentIds==old.componentIds && current.componentIds.size==32)
        for(id in current.componentIds)check(same(current.componentDescriptor(id),old.componentDescriptor(id))) {"Narrow component ABI changed: $id"}
        val differences=leafDifferences(old.data,current.data)
        check(differences.isNotEmpty() && differences.all {d->(d["path"] as String).let {it.startsWith("/memoryProfiles/$PROFILE/") || it.startsWith("/legacyFirmwareMetadata/memoryProfiles/$PROFILE/")}})
        return mapOf("baselineArtifactCommit" to BASELINE,"baselinePayloadSha256" to old.artifactSha256,"newPayloadSha256" to current.artifactSha256,
            "defaultProfileUnchanged" to true,"allOldProfilesUnchanged" to true,"newRegion" to added,"deletingOnlyNewProfileRestoresCompleteG12" to true,
            "unchangedComponentDescriptors" to current.componentIds,"unchangedComponentDescriptorCount" to 32,"completeLeafDifferences" to differences,"leafDifferenceCount" to differences.size,
            "scope" to "Only two appearances of the new firmware memory profile change compiled catalog data. Component catalog descriptors contain narrow port ABI, not Kotlin Setting choices, and all32 remain exact.")
    }
    internal fun leafDifferences(old:Any?,current:Any?,path:String=""):List<Map<String,Any?>> {
        if(same(old,current))return emptyList()
        if(old is Map<*,*> && current is Map<*,*>)return (old.keys+current.keys).distinct().flatMap {key->
            val p=path+"/"+key.toString().replace("~","~0").replace("/","~1")
            when { !old.containsKey(key)->newLeaves(current[key],p); !current.containsKey(key)->listOf(mapOf("path" to p,"beforePresent" to true,"beforeValue" to old[key],"afterPresent" to false));else->leafDifferences(old[key],current[key],p) }
        }
        if(old is List<*> && current is List<*> && old.size==current.size)return old.indices.flatMap {leafDifferences(old[it],current[it],"$path/$it")}
        return listOf(mapOf("path" to path,"beforePresent" to true,"beforeValue" to old,"afterPresent" to true,"afterValue" to current))
    }
    private fun newLeaves(value:Any?,path:String):List<Map<String,Any?>> = when {
        value is Map<*,*> && value.isNotEmpty()->value.entries.flatMap {(key,v)->newLeaves(v,path+"/"+key.toString().replace("~","~0").replace("/","~1"))}
        value is List<*> && value.isNotEmpty()->value.indices.flatMap {newLeaves(value[it],"$path/$it")}
        else->listOf(mapOf("path" to path,"beforePresent" to false,"afterPresent" to true,"afterValue" to value))
    }
    fun factoryParameter(root: Path, memoryJar: Path, apiJar: Path, old: AddressCatalog) =
        factoryParameter(Files.readString(root.resolve(FACTORY)), git(root, "show", "$BASELINE:$FACTORY"), memoryJar, apiJar, old)

    internal fun factoryParameter(
        source: String, baselineSource: ByteArray, memoryJar: Path, apiJar: Path, old: AddressCatalog,
    ): Map<String, Any?> {
        val token=",\"$PROFILE\"";check(source.split(token).size==2)
        check(source.replace(token,"").toByteArray().contentEquals(baselineSource)) {"MemoryFactory changes beyond the one profile choice"}
        val expected=obj(old.legacyFirmwareMetadata()["memoryProfiles"]).keys.toList()+PROFILE
        return URLClassLoader(arrayOf(memoryJar.toUri().toURL(),apiJar.toUri().toURL()),javaClass.classLoader).use {loader->
            val factory=loader.loadClass("com.github.nyabanban.fuji.emulator.components.memory.MemoryFactory").getConstructor().newInstance()
            val descriptor=factory.javaClass.getMethod("getDescriptor").invoke(factory)
            fun get(o:Any,name:String)=o.javaClass.getMethod("get"+name).invoke(o)
            check(get(descriptor,"Id")=="system-memory" && get(descriptor,"Version")=="0.0.1" && get(descriptor,"Abi")==1)
            val settings=get(descriptor,"Settings") as List<*>;check(settings.size==1)
            val setting=checkNotNull(settings.single())
            check(get(setting,"Key")=="profile" && get(setting,"Kind").toString()=="TEXT" && get(setting,"Default")=="loader-low128-experimental-v1")
            check(get(setting,"Minimum")==null && get(setting,"Maximum")==null && get(setting,"Live")==false && get(setting,"Description")=="")
            check(get(setting,"Choices")==expected) {"Actual factory choice declaration differs"}
            val validate=setting.javaClass.getMethod("validate",Any::class.java)
            for(profile in expected)check(validate.invoke(setting,profile)==profile)
            val invalid="kernel-config-record-pages-experimental-v16"
            var refused=false
            try {validate.invoke(setting,invalid)}catch(e:InvocationTargetException){check(e.cause is IllegalArgumentException && e.cause?.message=="profile invalid choice");refused=true}
            check(refused)
            mapOf("id" to get(descriptor,"Id"),"version" to get(descriptor,"Version"),"abi" to get(descriptor,"Abi"),"key" to get(setting,"Key"),"kind" to "TEXT","default" to get(setting,"Default"),"choices" to expected,"live" to false,
                "allOldChoicesUnchanged" to true,"onlyNewChoice" to PROFILE,"unknownChoiceRejected" to true,"onlyFactorySourceChangeIsNewChoiceLiteral" to true,
                "memoryJarSha256" to CatalogIO.sha256(Files.readAllBytes(memoryJar)),"apiJarSha256" to CatalogIO.sha256(Files.readAllBytes(apiJar)),
                "scope" to "Actual Gradle-built MemoryFactory parameter declaration and validation only. Complete32-JAR assembly and DAT-backed v15 initialization are independently qualified by preparation.")
        }
    }
    fun queries(catalog:AddressCatalog):List<Map<String,Any?>> {
        val result=ArrayList<Map<String,Any?>>()
        for(address in listOf(0x4e00000L,0x4e00140L,0x4e00800L,0x4e01400L,0x4e02000L,0x4e02004L,0x4e02ffcL))for(direction in listOf("read","write","execute")) {
            val q=catalog.query("cpu-physical",address,4,direction,PROFILE,"strict")
            check(q.classification=="RAM" && q.permitted==(direction!="execute"))
            check(catalog.query("cpu-physical",address,4,direction,PARENT,"strict").classification=="UNASSIGNED")
            result+=mapOf("address" to address,"width" to 4,"direction" to direction,"classification" to q.classification,"permitted" to q.permitted)
        }
        for(address in listOf(0x4dffffcL,0x4e03000L,0x4e03ffcL))check(catalog.query("cpu-physical",address,4,"write",PROFILE,"strict").classification=="UNASSIGNED")
        check(catalog.query("cpu-physical",0x4e02ffeL,4,"write",PROFILE,"strict").classification=="CROSS_BOUNDARY")
        return result
    }
    @JvmStatic fun main(args:Array<String>) {
        check(args.size==10 && args.size%2==0);val o=args.toList().chunked(2).associate {it[0] to it[1]}
        check(o.keys==setOf("--repo","--output","--memory-jar","--api-jar","--source-commit"))
        val root=Path.of(o.getValue("--repo")).toRealPath();val source=o.getValue("--source-commit")
        check(source.matches(Regex("[a-f0-9]{40}")) && git(root,"rev-parse","HEAD").toString(Charsets.UTF_8).trim()==source)
        val old=baseline(root);val current=CatalogIO.readCompiled(Files.readAllBytes(root.resolve("catalog/compiled/catalog.json")))
        check(same(CatalogCompiler.compile(root).data,current.data))
        val report=mapOf("schema" to 1,"sourceCommit" to source,"catalog" to verifyCatalog(old,current),"actualFactoryParameter" to factoryParameter(root,Path.of(o.getValue("--memory-jar")),Path.of(o.getValue("--api-jar")),old),"queries" to queries(current),
            "evidenceScope" to mapOf("pageEnvelope" to "HYPOTHESIS native backing envelope supported by selected DAT consumers; not every mapped byte has a proven write","permissionsAndInitialZero" to "HYPOTHESIS","paddingRanges" to listOf(listOf(0x4e00140L,0x4e00800L),listOf(0x4e01400L,0x4e02000L)),"headerFirstFourBytes" to "UNKNOWN selected write/meaning; backing remains explicit HYP zero","guestFfFill" to "Guest must execute the DAT consumer; no FF prefill or ready/completion injection","physicalRamCapacityAndReset" to "UNKNOWN"),
            "guestExecuted" to false,"nativeExecuted" to false,"firstOriginalMainFrame" to false)
        val output=root.resolve(o.getValue("--output")).normalize();check(output.startsWith(root) && !Files.exists(output));CatalogIO.writeJson(output,report)
        println("Qualified exact G13 profile-only catalog delta, unchanged32 narrow ABI descriptors and one actual MemoryFactory choice")
    }
}
