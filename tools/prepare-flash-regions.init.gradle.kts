import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.net.URLClassLoader
import java.security.MessageDigest

// Explicit research input generation only. Does not change runtime settings, metadata, guest RAM or PC.
gradle.projectsEvaluated {
 rootProject.tasks.register("prepareGfxBlankFlashRegion") {
  dependsOn("stageRuntime")
  doLast {
   val root=rootProject.projectDir.toPath().toRealPath()
   val producer="tools/prepare-flash-regions.init.gradle.kts"
   fun gitRead(vararg arguments:String):String {
    val git=rootProject.findProperty("gitExecutable")?.toString() ?: "git"
    val process=ProcessBuilder(listOf(git,"-c","core.longpaths=true","-C",root.toString())+arguments).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val result=process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
    require(process.waitFor()==0) { "Git provenance unavailable" }; return result
   }
   val sourceCommit=gitRead("rev-parse","HEAD");require(sourceCommit.matches(Regex("[0-9a-f]{40,64}")))
   val sourcePaths=listOf(producer,"firmware","runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/Formats.kt")
   val sourceDirty=gitRead("status","--porcelain","--untracked-files=all","--",*sourcePaths.toTypedArray()).isNotEmpty()
   val firmware=root.resolve(rootProject.providers.gradleProperty("flashRegionFirmwareFile").get()).normalize().toRealPath()
   val output=root.resolve(rootProject.providers.gradleProperty("flashRegionOutput").get()).normalize()
   require(output.startsWith(root.resolve("local")) && output!=root.resolve("local")) { "new output must be inside this project's ignored local directory" }
   require(!Files.exists(output)) { "never overwrite a prior experiment" }
   val urls=Files.list(root.resolve("build/emulator/lib")).use{paths->paths.filter{it.toString().endsWith(".jar")}.map{it.toUri().toURL()}.toList().toTypedArray()}
   URLClassLoader(urls,ClassLoader.getPlatformClassLoader()).use { loader ->
    val metadataType=loader.loadClass("com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata")
    val metadata=metadataType.getConstructor(Path::class.java).newInstance(root.resolve("configurations/jvm/firmware/gfx100s-213.yaml"))
    val imageType=loader.loadClass("com.github.nyabanban.fuji.emulator.firmware.FirmwareImage")
    val image=imageType.getConstructor(metadataType,Path::class.java).newInstance(metadata,firmware)
    val app=imageType.getMethod("decode",String::class.java).invoke(image,"section4") as ByteArray
    val contractType=loader.loadClass("com.github.nyabanban.fuji.emulator.firmware.FlashBufferContracts")
    val contract=contractType.getMethod("observe",ByteArray::class.java).invoke(contractType.getField("INSTANCE").get(null),app) as Map<*,*>
    val offset=(contract["flashOffset"] as Number).toLong()
    val size=(contract["declaredTableBytes"] as Number).toLong()
    require(offset==0x3500000L && size==0x840000L)
    val formats=loader.loadClass("com.github.nyabanban.fuji.emulator.runtime.Formats")
    val mapper=formats.getMethod("getJson").invoke(formats.getField("INSTANCE").get(null))
    fun json(value:Any?)=mapper.javaClass.getMethod("writeValueAsString",Any::class.java).invoke(mapper,value).toString()+"\n"
    fun hash(path:Path):String {
     val digest=MessageDigest.getInstance("SHA-256")
     Files.newInputStream(path).use { input->val block=ByteArray(65536);while(true){val n=input.read(block);if(n<0)break;digest.update(block,0,n)} }
     return java.util.HexFormat.of().formatHex(digest.digest())
    }
    Files.createDirectories(output)
    val file=output.resolve("blank-ff-experiment.img")
    Files.newOutputStream(file,StandardOpenOption.CREATE_NEW).use { stream->
     val block=ByteArray(65536){0xff.toByte()};var remaining=size
     while(remaining>0){val n=minOf(block.size.toLong(),remaining).toInt();stream.write(block,0,n);remaining-=n}
    }
    require(Files.size(file)==size)
    val identity=mapOf("id" to "blank-ff-experiment","offset" to offset,"length" to size,"file" to file.fileName.toString(),"sha256" to hash(file))
    val specification=mapOf("schema" to 1,"profile" to "explicit-readonly-regions-experimental-v1","regions" to listOf(identity))
    Files.writeString(output.resolve("regions.json"),json(specification),StandardOpenOption.CREATE_NEW)
    Files.writeString(output.resolve("input-identity.generated.json"),json(mapOf("schema" to 2,"evidenceClass" to "DAT / TEST / HYPOTHESIS / UNKNOWN",
      "selectedFirmwareSha256" to metadataType.getMethod("getSha256").invoke(metadata),"contract" to contract,"region" to identity,
      "fill" to 255,"basis" to "EXPLICIT HYPOTHESIS: finite all-FF blank medium; no camera dump exists at this offset. Software table geometry determines extent, not physical capacity or erase size.",
      "regionsFileSha256" to hash(output.resolve("regions.json")),"producer" to producer,"sourceCommit" to sourceCommit,"sourcePaths" to sourcePaths,"sourceDirty" to sourceDirty,
      "selectedRuntimeChanged" to false,"unknownHolesSupplied" to false,"completeBoot" to false,"menuVerified" to false)),StandardOpenOption.CREATE_NEW)
    println("Prepared explicit finite HYP input at "+root.relativize(output)+"; no runtime selected")
   }
  }
 }
}
