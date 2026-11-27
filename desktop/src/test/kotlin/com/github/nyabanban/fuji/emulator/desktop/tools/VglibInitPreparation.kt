package com.github.nyabanban.fuji.emulator.desktop.tools

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.desktop.VglibInitAdmission
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.file.Files
import java.nio.file.Path

/** Selected extra component inputs come from the central SOURCE declaration, never a new execution plan. */
object VglibInitPreparation {
    val additions=listOf("compoments/soc-vglib-init-0.3.1.jar","compoments/soc-vglib-init/soc-vglib-init.yaml","compoments/soc-vglib-init/sequence.yaml")
    const val MODE_INPUT="compoments/soc-vglib-init/mode-word.yaml"
    const val SNAPSHOT_INPUT="compoments/soc-vglib-init/lcdc-snapshot.yaml"
    fun additions(modeProfile:String?,snapshotProfile:String?=null)=additions+(if(modeProfile==null)emptyList()else listOf(MODE_INPUT))+(if(snapshotProfile==null)emptyList()else listOf(SNAPSHOT_INPUT))
    fun snapshotInput(root:Path,samples:List<Long> = List(140){0L},profile:String=VglibInitAdmission.SNAPSHOT_PROFILE):Map<String,Any> {
        require(profile in setOf(VglibInitAdmission.SNAPSHOT_PROFILE,VglibInitAdmission.UNREADY_PROFILE))
        require(samples.size==140&&samples.all{it in 0..0xffffffffL})
        val port=checkNotNull(AddressCatalog.load(root.resolve("catalog/compiled/catalog.json")).byId(if(profile==VglibInitAdmission.UNREADY_PROFILE)"definition.soc-vglib-init.lcdc-unready" else "definition.soc-vglib-init.lcdc-snapshot"))
        val backing=Formats.map(port["backing"]);require(backing["sequenceProfile"]==profile)
        val order=backing["sourceReadOffsets"] as List<*>;require(order.size==140)
        return mapOf("schema" to 1,"profile" to profile,"readConfidence" to "HYPOTHESIS","reads" to order.mapIndexed{i,offset->mapOf("offset" to offset,"value" to samples[i])})+(if(profile==VglibInitAdmission.UNREADY_PROFILE)mapOf("controlReadLimit" to VglibInitAdmission.CONTROL_READ_LIMIT,"controlInitialValue" to 0,"controlWriteValue" to 0,"controlReadValue" to 0)else emptyMap())
    }
    fun modeInput(root:Path,sample:Long=0L):Map<String,Any> {
        require(sample in 0..0xffffffffL)
        val port=checkNotNull(AddressCatalog.load(root.resolve("catalog/compiled/catalog.json")).byId("definition.soc-vglib-init.mode-word"))
        val backing=Formats.map(port["backing"])
        require(backing["sequenceProfile"]==VglibInitAdmission.MODE_PROFILE)
        return mapOf("schema" to 1,"profile" to VglibInitAdmission.MODE_PROFILE,"readConfidence" to "HYPOTHESIS","readSample" to sample,"sourcePreserveMask" to backing.getValue("sourcePreserveMask"))
    }
    fun configuration(before:Map<String,Any>,profile:String?):Map<String,Any> {
        if(profile==null)return before
        require(profile==VglibInitAdmission.PROFILE) {"unknown finite vglib profile"}
        val devices=before.getValue("enabledDevices") as List<*>
        require(VglibInitAdmission.ID !in devices)
        return before+mapOf("enabledDevices" to (devices+VglibInitAdmission.ID))
    }
    fun input(root:Path,readSamples:List<Long> = List(18){0L}):Map<String,Any> {
        val port=checkNotNull(AddressCatalog.load(root.resolve("catalog/compiled/catalog.json")).byId("definition.soc-vglib-init.registers"))
        val backing=Formats.map(port["backing"])
        require(backing["sequenceProfile"]==VglibInitAdmission.PROFILE&&readSamples.size==18&&readSamples.all{it in 0..0xffffffffL})
        val sequence=(backing["finiteFullLaneRmw"] as List<*>).map{Formats.map(it)}
        require(sequence.size==18)
        return mapOf("schema" to 1,"profile" to VglibInitAdmission.PROFILE,"readConfidence" to "HYPOTHESIS","transactions" to sequence.mapIndexed{i,row->row+mapOf("readValue" to readSamples[i])})
    }
    fun materialize(root:Path,home:Path,metadata:String,modeProfile:String?=null,snapshotProfile:String?=null) {
        require(modeProfile==null||modeProfile==VglibInitAdmission.MODE_PROFILE)
        require(snapshotProfile==null||snapshotProfile in setOf(VglibInitAdmission.SNAPSHOT_PROFILE,VglibInitAdmission.UNREADY_PROFILE))
        additions.forEach{Files.createDirectories(home.resolve(it).parent)}
        Files.copy(root.resolve("build/emulator/"+additions[0]),home.resolve(additions[0]))
        Formats.atomicYaml(home.resolve(additions[1]),mapOf("profile" to (if(snapshotProfile==VglibInitAdmission.UNREADY_PROFILE)VglibInitAdmission.UNREADY_PROFILE else VglibInitAdmission.PROFILE),"inputFile" to "sequence.yaml")+(if(modeProfile==null)emptyMap()else mapOf("controlWordInputFile" to "mode-word.yaml"))+(if(snapshotProfile==null)emptyMap()else mapOf("lcdcSnapshotInputFile" to "lcdc-snapshot.yaml")))
        Formats.atomicYaml(home.resolve(additions[2]),input(root))
        if(modeProfile!=null)Formats.atomicYaml(home.resolve(MODE_INPUT),modeInput(root))
        if(snapshotProfile!=null)Formats.atomicYaml(home.resolve(SNAPSHOT_INPUT),snapshotInput(root,profile=snapshotProfile))
        val raw=Formats.read(home.resolve(metadata));val devices=raw["devices"] as List<*>
        require(devices.none{Formats.map(it)["id"]==VglibInitAdmission.ID})
        val declared=AddressCatalog.load(root.resolve("catalog/compiled/catalog.json")).firmwareMetadata(if(snapshotProfile==VglibInitAdmission.UNREADY_PROFILE)mapOf(VglibInitAdmission.ID to VglibInitAdmission.UNREADY_PROFILE)else emptyMap())["devices"] as List<*>
        val window=declared.map{Formats.map(it)}.single{it["id"]==VglibInitAdmission.ID&&it["port"]=="soc-vglib-init.registers"}
        Formats.atomicYaml(home.resolve(metadata),raw+mapOf("devices" to (devices+window+(if(modeProfile==null)emptyList()else listOf(declared.map{Formats.map(it)}.single{it["id"]==VglibInitAdmission.ID&&it["port"]==VglibInitAdmission.MODE_PORT}))+(if(snapshotProfile==null)emptyList()else listOf(declared.map{Formats.map(it)}.single{it["id"]==VglibInitAdmission.ID&&it["port"]==(if(snapshotProfile==VglibInitAdmission.UNREADY_PROFILE)VglibInitAdmission.UNREADY_PORT else VglibInitAdmission.SNAPSHOT_PORT)})))))
    }
}
