package com.github.nyabanban.fuji.emulator.firmware.research

import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Selects bytes for the existing four read domains. It never creates mappings, COW files or RAM. */
object HardwareFlashInputPreparation {
    const val PROFILE="gfx100s-hardware-supplements-20261112-v1"
    const val INPUT_COMMIT="14c2b18645deaa2b87fd048beb7773764104f093"
    const val ARCHIVE="firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip"
    const val ENTRY="files/flash-layout.bin"
    const val BASELINE="evidence/refactor/power25-caller-original-fresh-03/study/discovery-cap8-v5-gpio110-rtc-stage-v2-zero-caller/compoments/flash-image"
    internal data class Domain(val id:String,val offset:Long,val length:Long,val file:String)
    internal val domains=listOf(Domain("blank-ff-experiment",0x3500000,0x840000,"hardware-config.img"),
        Domain("dat-domain490-497-ff",0x3d40000,0x100000,"hardware-domain490-497.img"),
        Domain("dat-range504-ff",0x3f00000,0x40000,"hardware-domain504-505.img"),
        Domain("dat-secondary-read506-507-ff",0x3f40000,0x40000,"hardware-domain506-507.img"))
    private const val FLASH_BYTES=0x4000000
    internal fun exact(value:Any?):Long {
        require(value is Byte || value is Short || value is Int || value is Long) {"Exact integer required"}
        return (value as Number).toLong()
    }
    internal fun verifyBaseline(config:Map<String,Any>,regions:Map<String,Any>,mutation:Map<String,Any>) {
        require(config==mapOf("tailProfile" to "dat-end-block-ff-experimental-v1","regionsProfile" to "explicit-readonly-regions-experimental-v1",
            "regionsFile" to "regions.json","mutationProfile" to "explicit-disjoint-copy-on-write-program-experimental-v3","mutationFile" to "mutable.json"))
        require(regions.keys==setOf("schema","profile","regions") && exact(regions["schema"])==1L && regions["profile"]=="explicit-readonly-regions-experimental-v1")
        val rows=regions["regions"] as? List<*> ?: error("Missing read domains")
        require(rows.size==domains.size)
        for((row,domain) in rows.zip(domains)) {
            val value=row as? Map<*,*> ?: error("Invalid read domain")
            require(value.keys==setOf("id","offset","length","file","sha256") && value["id"]==domain.id && exact(value["offset"])==domain.offset && exact(value["length"])==domain.length)
            require(value["file"] is String && (value["sha256"] as? String)?.matches(Regex("[0-9a-f]{64}"))==true)
        }
        require(mutation.keys==setOf("schema","profile","primaryRegionId","regions") && exact(mutation["schema"])==1L && mutation["profile"]==config["mutationProfile"] && mutation["primaryRegionId"]==domains[1].id)
        val copies=mutation["regions"] as? List<*> ?: error("Missing writable domains")
        require(copies.size==2)
        val wanted=listOf(Triple(domains[1].id,0x3dc0000L,0x80000L),Triple(domains[2].id,0x3f00000L,0x40000L))
        for((raw,domain) in copies.zip(wanted)) {
            val row=raw as? Map<*,*> ?: error("Invalid COW domain")
            require(row.keys==setOf("schema","profile","regionId","file","mode","sha256","writableOffset","writableLength","programLaw","programPageBytes"))
            require(exact(row["schema"])==1L && row["profile"]=="explicit-copy-on-write-program-experimental-v2" && row["regionId"]==domain.first &&
                exact(row["writableOffset"])==domain.second && exact(row["writableLength"])==domain.third && row["mode"]=="create" &&
                row["programLaw"]=="strict-one-to-zero-no-page-wrap-experimental-v1" && exact(row["programPageBytes"])==256L)
            require((row["file"] as? String)?.matches(Regex("[a-z0-9][a-z0-9._-]{0,120}\\.img"))==true && (row["sha256"] as? String)?.matches(Regex("[0-9a-f]{64}"))==true)
        }
    }
    internal fun readFlash(archive:Path):ByteArray = ZipFile(archive.toFile(),Charsets.UTF_8).use {zip ->
        require(Files.size(archive) in 1..128L*1024*1024)
        val entries=zip.entries().toList();require(entries.size in 1..128)
        val names=entries.map {HardwareDumpResearch.safeName(if(it.isDirectory)it.name.dropLast(1)else it.name)}
        require(names.distinct().size==names.size)
        val payload=entries.single {it.name==ENTRY};require(payload.size==FLASH_BYTES.toLong())
        val bytes=zip.getInputStream(payload).use {it.readNBytes(FLASH_BYTES+1)}
        require(bytes.size==FLASH_BYTES && CRC32().apply {update(bytes)}.value==payload.crc)
        val supplied=entries.single {it.name=="files/SHA256SUMS"};require(supplied.size in 1..32768)
        val text=zip.getInputStream(supplied).use {it.readNBytes(32769)}.also {require(it.size.toLong()==supplied.size)}.toString(Charsets.UTF_8)
        val expected=text.lineSequence().mapNotNull {Regex("([0-9a-f]{64})  flash-layout\\.bin").matchEntire(it)?.groupValues?.get(1)}.toList()
        require(expected.size==1 && HardwareDumpResearch.sha(bytes)==expected.single()) {"Supplied Flash identity mismatch"}
        bytes
    }
    internal fun verifyPrefix(image:FirmwareImage,flash:ByteArray):Long {
        val port=image.inputPort();val header=ByteBuffer.allocate(0x274).order(ByteOrder.LITTLE_ENDIAN);port.read(0,header)
        val size=header.getInt(0x24c).toLong() and 0xffffffffL
        require(header.getInt(0)==6 && size==0x2f00020L && size<=port.size-0x274 && size<=flash.size.toLong())
        val bytes=ByteArray(65536);var at=0L
        while(at<size) {
            val count=minOf(bytes.size.toLong(),size-at).toInt();val view=ByteBuffer.wrap(bytes,0,count);port.read(0x274+at,view);check(!view.hasRemaining())
            repeat(count) {require(bytes[it].toInt().inv().toByte()==flash[at.toInt()+it]) {"Installed Flash differs from selected DAT prefix"}}
            at+=count
        }
        return size
    }
    internal fun prepare(config:Map<String,Any>,regions:Map<String,Any>,mutation:Map<String,Any>,flash:ByteArray,output:Path):Map<String,Any?> {
        verifyBaseline(config,regions,mutation)
        require(flash.size==FLASH_BYTES && !Files.exists(output)) {"New output directory and exact retained Flash view required"}
        // Compute and validate the complete selection before creating the first file.
        val bytes=domains.associate {it.id to flash.copyOfRange(it.offset.toInt(),Math.toIntExact(it.offset+it.length))}
        val hashes=bytes.mapValues {HardwareDumpResearch.sha(it.value)}
        val selected=regions+("regions" to domains.map {d->mapOf("id" to d.id,"offset" to d.offset,"length" to d.length,"file" to d.file,"sha256" to hashes.getValue(d.id))})
        val copies=(mutation["regions"] as List<*>).map {raw ->
            val row=(raw as Map<*,*>).entries.associate {it.key.toString() to it.value}
            row+("sha256" to hashes.getValue(row["regionId"] as String))
        }
        val mutable=mutation+("regions" to copies)
        Files.createDirectory(output)
        val component=output.resolve("compoments/flash-image");Files.createDirectories(component)
        for(domain in domains)Files.write(component.resolve(domain.file),bytes.getValue(domain.id),CREATE_NEW)
        Formats.atomicYaml(component.resolve("flash-image.yaml"),config)
        Formats.atomicYaml(component.resolve("regions.json"),selected)
        Formats.atomicYaml(component.resolve("mutable.json"),mutable)
        return mapOf("schema" to 1,"profile" to PROFILE,"status" to "PREPARED_INPUT_NOT_ORIGINAL_FIRMWARE_QUALIFICATION",
            "inputCommit" to INPUT_COMMIT,"archive" to ARCHIVE,"entry" to ENTRY,"coordinateKind" to "FLASH_LOGICAL_OFFSET",
            "domains" to domains.map {d->mapOf("regionId" to d.id,"sourceOffset" to d.offset,"length" to d.length,"endExclusive" to d.offset+d.length,
                "destination" to "compoments/flash-image/"+d.file,"identityReference" to "compoments/flash-image/regions.json regions[id="+d.id+"].sha256")},
            "totalSelectedBytes" to domains.sumOf {it.length},"componentConfiguration" to "compoments/flash-image/flash-image.yaml",
            "writableConfiguration" to "compoments/flash-image/mutable.json","cowFilesCreated" to false,"legacyStableRegionIdsRetained" to true,
            "legacyNameMeaning" to "IDs contain historical -ff naming; selected contents are actual retained file slices, not fill or physical reset claims",
            "permissionsChanged" to false,"datPrefixChanged" to false,"tailProfileChanged" to false,"defaultChanged" to false,
            "guestExecuted" to false,"runtimeRamInjected" to false,"evidenceClass" to listOf("FILE","DAT"),
            "scope" to "Only four existing read domains receive supplied bytes. Original DAT prefix, selected finite HYP tail, holes, separate narrow write domains and COW law remain unchanged. Snapshot contents are not reset state; no physical capacity or accepted firmware request assertion.")
    }
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==8 && args.toList().chunked(2).map {it[0]}.toSet()==setOf("--repo","--inputs","--output","--git"))
        val options=args.toList().chunked(2).associate {it[0] to it[1]}
        val repo=Path.of(options.getValue("--repo")).toRealPath();val inputs=Path.of(options.getValue("--inputs")).toRealPath()
        val output=Path.of(options.getValue("--output")).toAbsolutePath().normalize();require(output.startsWith(repo) && !Files.exists(output))
        fun gitBytes(at:Path,vararg argv:String):ByteArray {
            val p=ProcessBuilder(listOf(options.getValue("--git"),"-c","core.longpaths=true","-C",at.toString())+argv).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val data=p.inputStream.readAllBytes();require(p.waitFor(30,TimeUnit.SECONDS) && p.exitValue()==0);return data
        }
        fun git(at:Path,vararg argv:String)=gitBytes(at,*argv).toString(Charsets.UTF_8).trimEnd()
        val source=git(repo,"rev-parse","HEAD")
        val sourcePaths=listOf("firmware/src/main/kotlin/com/github/nyabanban/fuji/emulator/firmware/research/HardwareFlashInputPreparation.kt",
            "firmware/src/main/kotlin/com/github/nyabanban/fuji/emulator/firmware/research/HardwareDumpResearch.kt",
            "firmware/src/main/kotlin/com/github/nyabanban/fuji/emulator/firmware/Firmware.kt",
            "configurations/jvm/firmware/gfx100s-213.yaml","tools/research/hardware-flash-input.init.gradle.kts")
        require(git(repo,*(listOf("status","--porcelain","--")+sourcePaths).toTypedArray()).isEmpty())
        val baseline=inputs.resolve(BASELINE)
        for(name in listOf("flash-image.yaml","regions.json","mutable.json"))require(gitBytes(inputs,"show",INPUT_COMMIT+":"+BASELINE+"/"+name).contentEquals(Files.readAllBytes(baseline.resolve(name)))) {"Baseline differs from pinned Git record"}
        require(git(inputs,"status","--porcelain","--",ARCHIVE,"firmware-data/gfx100s/2.13/GXUP0004.DAT").isEmpty())
        val metadataPath="configurations/jvm/firmware/gfx100s-213.yaml"
        require(gitBytes(inputs,"show",INPUT_COMMIT+":"+metadataPath).contentEquals(Files.readAllBytes(inputs.resolve(metadataPath)))) {"Firmware metadata differs from pinned input"}
        val flash=readFlash(inputs.resolve(ARCHIVE))
        val metadata=FirmwareMetadata(inputs.resolve("configurations/jvm/firmware/gfx100s-213.yaml"))
        val image=FirmwareImage(metadata,inputs.resolve("firmware-data/gfx100s/2.13/GXUP0004.DAT"))
        val prefix=verifyPrefix(image,flash)
        val config=Formats.read(baseline.resolve("flash-image.yaml"));val regions=Formats.read(baseline.resolve("regions.json"));val mutation=Formats.read(baseline.resolve("mutable.json"))
        Files.createDirectories(output.parent)
        val report=prepare(config,regions,mutation,flash,output)+mapOf("sourceCommit" to source,"sourcePaths" to sourcePaths,"verifiedDatPrefixBytes" to prefix,"arguments" to args.toList())
        require(git(repo,"rev-parse","HEAD")==source && git(repo,*(listOf("status","--porcelain","--")+sourcePaths).toTypedArray()).isEmpty())
        Files.write(output.resolve("manifest.generated.json"),Formats.jsonBytes(report),CREATE_NEW)
        println("Prepared four finite hardware-backed Flash domains: "+domains.sumOf {it.length}+" bytes; no mappings, COW files or guest execution")
    }
}
