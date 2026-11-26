package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exact observed Method-4 runtime destination; CPU translation and business use remain UNKNOWN. */
object Method4OutputAdmission {
    const val PROFILE="method4-runtime-output-experimental-v25"
    const val EXEC_PROFILE="method4-elfloader-exec-experimental-v26"
    val PROFILES:Set<String> = java.util.Collections.unmodifiableSet(linkedSetOf(PROFILE,EXEC_PROFILE))
    const val REGION="method4-runtime-output"
    const val BASE=0x03bf3000L
    const val SIZE=0x00a79000L
    const val SOURCE_LOGICAL=0x00b60000L
    const val OUTPUT_DESCRIPTOR=0x00e201ecL
    const val SUBMODULE_TABLE=0x00e201f8L
    const val SOURCE_ID=7
    val added=MemoryRegion(REGION,BASE,SIZE,3)
    val execAdded=added.copy(permissions=7)
    private const val APP=0x01021000L
    private const val DATA_XOR=0x00140274L
    private const val FLASH_XOR=0x00b60274L
    private val code=listOf(
        0x0129e424L to """
            e92d4ff0 e28db020 e24dd014 e3a00007 eb00ad4e e1a04000 eb00ad6b e50b0028
            e3540000 1a000000 eafffffe e5946014 e1a068a6 e5943018 e30f4fff e3404001
            e0834004 e1a058a4 e30703cc e3400174 ebb6e467 eb00ad54 e3500000 0a00001f
        """,
        0x0129e504L to """
            e3081000 e34011c0 e51b3028 e5930004 ebfd2b01 e3a01002 e3a00802 ebfd2b4b
            eb006b71 e1b04924 0a000014 e1a0a006 e3a09000 e3098300 e340811e e3087000
            e34071c0 ea000003 e2899001 e28aa002 e1590004 0a000009 e3a03001 e58d3000
            e1a03008 e1a02007 e3a01002 e1a0000a ebfffe2d
        """,
        0x012c9380L to "ebff5427 e1a06000",
        0x012c9974L to """
            e52db004 e28db000 e3003000 e34030e2 e5932000 e1d230d0 e3730001 0a00000c
            e1530000 0a000006 e1f232d4 e3730001 0a000002 e1530000 1afffffa ea000000
            e3a02000 e1a00002 e28bd000 e49db004 e12fff1e e3a02000 eafffff9 e52db004
            e28db000 e3003000 e34030e2 e5d30008 e28bd000 e49db004 e12fff1e e52db004
            e28db000 e3003000 e34030e2 e5930004 e28bd000 e49db004 e12fff1e
        """,
        0x011e9120L to "e92d4830 e28db00c e1a05000 e1a04001",
        0x011e919cL to """
            e30432b4 e3403187 e5834000 e3043288 e3403187 e5835000 e304328c e3403187 e5834000
        """,
        0x011e8facL to """
            e30442b4 e3404187 e3043288 e3403187 e5941000 e5930000 eb000135 e304328c
            e3403187 e5933000 e5940000 e593100c e2800014 eb000151
        """,
        0x011e931cL to """
            e30432b4 e3403187 e3044290 e3404187 e3a02014 e5931000 e1a00004 eb03431f
            e304328c e3403187 e5834000
        """,
        0x011e94a0L to """
            e92d4830 e28db00c e1a05000 e1a04001 e3a01000 e3090040 e34f0ff7 ebb9c6b3
            e1a01005 e3090010 e34f0ff7 ebb9c6af e5941000 e3090014 e34f0ff7 ebb9c6ab
            e3a01014 e3090018 e34f0ff7 ebb9c6a7 e5941008 e309001c e34f0ff7 ebb9c6a3
            e3094020 e34f4ff7 e3a01b01 e3481400 e1a00004 ebb9c69d e3a01b05 e3491400
            e1a00004 ebb9c699 e8bd8830
        """,
        0x011e952cL to """
            e92d4830 e28db00c e1a04001 e1a01000 e3090008 e34f0ff7 ebb9c691 e1a01004
            e309000c e34f0ff7 ebb9c68d e3a01001 e3090004 e34f0ff7 ebb9c689 e8bd8830
        """
    ).map{(address,words)->address to words.trim().split(Regex("\\s+")).map{it.toLong(16)}}
    val wordAddresses=code.flatMap{(address,words)->words.indices.map{address+it*4L}}
    private val execCode=listOf(
        0x0129e5c8L to "e51b3028 e5930008 eb00a938 e1a00004 e24bd020 e8bd8ff0",
        0x012c8ab8L to """
            e92d4810 e28db008 e24dd01c e50b0020 e3083178 e340319f e5933000 e1500003
            8a000001 e24bd008 e8bd8810 e3083178 e340319f e5830000 eb0003be e5903000
            e5904004 e51b2020 e0844002 e1d300d0 e3700001 1a000003 eafffff1 e1f301d8
            e3700001 0affffee e5d32010 e3520000 1afffff9 e5932004 e5931008 e0822001
            e1520004 8afffff4 e3011111 ebb63a6c e51b1020 e1a00004 ebb63a69 e30137dc
            e14b31b8 ebffb54c e54b0016 e3e0307b e54b3015 e3a03004 e50b3014 e24b3020
            e50b3010 e24b1018 e3a00084 ebffb844 eaffffd3
        """,
        0x012c9a10L to """
            e52db004 e28db000 e3003000 e34030e2 e5933004 e5932000 e1d230d0 e3730001
            0a00000c e1500003 0a000006 e1f231d8 e3730001 0a000002 e1530000 1afffffa
            ea000000 e3a02000 e1a00002 e28bd000 e49db004 e12fff1e e3a02000 eafffff9
        """,
        0x012c8dccL to """
            eb000307 e1a04000 e5903000 e51b5020 e5902004 e0858002 e5901008 e0822001
            e1d310d0 e3710001 0a000013 e1a00002 e1a01007 e593c004 e593e008 e08ce00e
            e158000e 315c0000 3042100c 31a0000c e1f3c1d8 e37c0001 1afffff5 ebb639b3
            e3a02007 e1a01005 e5940004 ebffc16d e3500000 0a000003 eafffffe e1a00002
            e1a01007 eafffff4 e5944000 e1d460d0 e3760001 1a000028 e3e05000
        """,
        0x012c8f08L to """
            e50b4028 e5d43010 e3530000 1afffff8 e5943004 e5942008 e0833002 e1580003
            3afffff3 e1a00006 eb0002b6 e1a05000 e5d03010 e3530000 1affffe9 e5909004
            e5993000 e3530000 0a000003 e12fff33 e5b93004 e3530000 1afffffb e3a03001
            e5c53010 e5950014 ebffb643
        """,
        0x012b937cL to """
            e2253008 e7e031d3 e58d3010 e7e03155 e58d300c e7e030d5 e58d3008 e2055001
            e58d5004 e59b3004 e58d3000 e1a02006 e1a03007 e1a00008 ebb67d6a
        """,
        0x012b93f4L to """
            e92d4800 e28db004 e24dd010 e3a03001 e58d3008 e58d2004 e58d1000 e1a02000
            e3a03000 ebffffbf e24bd004 e8bd8800
        """
    ).map{(address,words)->address to words.trim().split(Regex("\\s+")).map{it.toLong(16)}}
    val execWordAddresses=execCode.flatMap{(address,words)->words.indices.map{address+it*4L}}
    private val submoduleTableWords=listOf(
        0x00000000L,0x03bf3000L,0x006fd000L,0x00000000L,0x00000000L,0x0000004eL,
        0x00000002L,0x042f0000L,0x00008000L,0x00000000L,0x00000000L,0x00000050L,
        0x00000003L,0x042f8000L,0x00374000L,0x00000000L,0x00000000L,0x00000051L,
        0x000000ffL,0x00000000L,0x00000000L,0x00000000L,0x00000000L,0x00000000L)

    fun sourceGeometry(app:ByteArray,dat:ByteArray):Map<String,Any> {
        val bytes=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
        code.forEach{(address,words)->words.forEachIndexed{index,expected->
            val pc=address+index*4L
            val offset=pc-APP
            require(offset>=0&&offset<=app.size.toLong()-4) {"Method-4 source truncated at "+pc.toString(16)}
            require(bytes.getInt(offset.toInt()).toLong() and 0xffffffffL==expected) {"Method-4 source word differs at "+pc.toString(16)}
        }}
        fun inverted(offset:Int,size:Int):ByteArray {
            require(offset>=0&&offset<=dat.size-size) {"Method-4 DAT window outside file"}
            return ByteArray(size){index->(dat[offset+index].toInt() xor 255).toByte()}
        }
        val metadata=ByteBuffer.wrap(inverted(DATA_XOR.toInt(),16)).order(ByteOrder.LITTLE_ENDIAN)
        val table=metadata.getInt(0)
        val descriptorPointer=metadata.getInt(4)
        require(table==0x00e20040&&descriptorPointer==OUTPUT_DESCRIPTOR.toInt()&&metadata.get(8).toInt()==0) {"Method-4 metadata precedence differs"}
        val sourceRow=inverted(DATA_XOR.toInt()+0x160,36)
        val row=ByteBuffer.wrap(sourceRow).order(ByteOrder.LITTLE_ENDIAN)
        require(row.getInt(0)==SOURCE_ID&&row.getInt(4)==0&&row.getInt(20)==SOURCE_LOGICAL.toInt()&&row.getInt(24)==0x004c0000) {"Method-4 source ID7 row differs"}
        val descriptor=inverted(DATA_XOR.toInt()+0x1ec,12)
        val descriptorWords=ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN)
        require(descriptorWords.getInt(0)==0x00e201f8&&descriptorWords.getInt(4)==BASE.toInt()&&
            descriptorWords.getInt(8).toLong() and 0xffffffffL==SIZE) {"Method-4 output descriptor differs"}
        val header=inverted(FLASH_XOR.toInt(),20)
        val headerWords=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        require(headerWords.getInt(0).toLong() and 0xffffffffL==SIZE&&headerWords.getInt(4)==0x004bc485&&
            headerWords.getInt(8)==0x130&&headerWords.getInt(12)==0x4000&&headerWords.getInt(16)==4) {"Method-4 input header differs"}
        return mapOf("producerStart" to 0x0129e424L,"callsite" to 0x012c9380L,"returnAddress" to 0x012c9384L,
            "criticalWords" to wordAddresses.size,"metadataAddress" to 0x00e20000L,"sourceId" to SOURCE_ID,
            "sourceLogical" to SOURCE_LOGICAL,"outputDescriptor" to OUTPUT_DESCRIPTOR,"outputLength" to SIZE,
            "outputPhysical" to BASE,"headerDatStart" to FLASH_XOR,"headerDatEndExclusive" to FLASH_XOR+20,
            "sourceDatStart" to FLASH_XOR,"packedLength" to 0x004bc485L,"pages" to 0x130L,"pageSize" to 0x4000L,
            "method" to 4)
    }

    fun execGeometry(app:ByteArray,dat:ByteArray):Map<String,Any> {
        sourceGeometry(app,dat)
        val bytes=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
        execCode.forEach{(address,words)->words.forEachIndexed{index,expected->
            val pc=address+index*4L
            val offset=pc-APP
            require(offset>=0&&offset<=app.size.toLong()-4) {"Method-4 elfloader source truncated at "+pc.toString(16)}
            require(bytes.getInt(offset.toInt()).toLong() and 0xffffffffL==expected) {"Method-4 elfloader word differs at "+pc.toString(16)}
        }}
        val tableOffset=DATA_XOR.toInt()+0x1f8
        require(tableOffset>=0&&tableOffset<=dat.size-96) {"Method-4 submodule table outside file"}
        val invertedTable=ByteArray(96){index->(dat[tableOffset+index].toInt() xor 255).toByte()}
        val tableBuffer=ByteBuffer.wrap(invertedTable).order(ByteOrder.LITTLE_ENDIAN)
        submoduleTableWords.forEachIndexed{index,expected->
            require(tableBuffer.getInt(index*4).toLong() and 0xffffffffL==expected) {
                "Method-4 submodule table word differs at "+(SUBMODULE_TABLE+index*4L).toString(16)
            }
        }
        val submodules=listOf(
            mapOf("subId" to 0,"address" to 0x03bf3000L,"size" to 0x006fd000L,"semaphore" to 0x4e),
            mapOf("subId" to 2,"address" to 0x042f0000L,"size" to 0x00008000L,"semaphore" to 0x50),
            mapOf("subId" to 3,"address" to 0x042f8000L,"size" to 0x00374000L,"semaphore" to 0x51))
        var cursor=BASE
        submodules.forEach{row->
            require(row["address"]==cursor) {"Method-4 submodule range is not contiguous"}
            cursor+=row.getValue("size")
        }
        require(cursor==BASE+SIZE) {"Method-4 submodule table does not cover the exact output range"}
        return mapOf("notifyCallsite" to 0x0129e5d0L,"notifyEntry" to 0x012c8ab8L,"taskId" to 132,
            "remapCallsite" to 0x012c8e38L,"remapTarget" to 0x012b93f4L,"remapPermissions" to 7,
            "constructorLoop" to 0x012c8f54L,"submoduleTable" to SUBMODULE_TABLE,
            "submoduleTableDatStart" to DATA_XOR+0x1f8L,"submoduleTableDatEndExclusive" to DATA_XOR+0x258L,
            "submodules" to submodules,"criticalWords" to execWordAddresses.size,
            "totalCriticalWords" to wordAddresses.size+execWordAddresses.size)
    }

    @JvmOverloads
    fun row26(profile:String=PROFILE):MemoryRegion = when(profile) {
        PROFILE -> added
        EXEC_PROFILE -> execAdded
        else -> throw IllegalArgumentException("Unknown Method-4 output profile")
    }
    @JvmOverloads
    fun finiteProfileRegions(inherited:List<MemoryRegion>,profile:String=PROFILE):List<MemoryRegion> {
        val output=row26(profile)
        require(inherited.isNotEmpty()&&inherited.none{it.id==REGION||it.address<BASE+SIZE&&BASE<it.address+it.size}) {"Method-4 output overlaps the v24 baseline"}
        return (inherited+output).sortedBy{it.address}
    }
    @JvmOverloads
    fun finiteProfile(inherited:List<MemoryRegion>,profile:String=PROFILE):Map<String,Any> {
        val output=row26(profile)
        return mapOf(
            "profile" to profile,"ancestorProfile" to FlashloadBufferAdmission.PROFILE,
            "regions" to finiteProfileRegions(inherited,profile).map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)},
            "addedRegion" to mapOf("id" to REGION,"address" to BASE,"size" to SIZE,"permissions" to output.permissions),
            "initialContent" to "HYPOTHESIS: explicit zero backing; original Method-4 writes only after complete checks",
            "permissions" to if(output.permissions==7) "HYPOTHESIS: exact RWX physical request" else "HYPOTHESIS: exact RW/NX physical request",
            "cpuMapping" to if(output.permissions==7) "Guest-owned CPU page tables; physical RWX backing only, no CPU page-table changes supplied" else "UNKNOWN: current same-value VA descriptors are faults",
            "physicalGeometry" to "UNKNOWN","objectPurpose" to "UNKNOWN",
            "scope" to "Full declared 26-region finite profile selected before cold start; no neighboring gaps, CPU page table, codec completion or IRQ change")
    }

    fun additionalRam(selected:AdditionalRamPlan,catalog:AddressCatalog,baseRegions:List<MemoryRegion>,dat:ByteArray,app:ByteArray):Map<String,Any> {
        val region=selected.additionalRegions.singleOrNull{it.id==REGION}
            ?: throw IllegalArgumentException("Method-4 plan lacks the declared output region")
        val (declaredProfile,expected)=when {
            selected.sourceProfile==PROFILE -> PROFILE to added
            selected.sourceProfile==EXEC_PROFILE -> EXEC_PROFILE to execAdded
            region==added -> PROFILE to added
            region==execAdded -> EXEC_PROFILE to execAdded
            else -> throw IllegalArgumentException("Unknown Method-4 output source profile")
        }
        require(region==expected) {"Method-4 output region differs from catalog declaration"}
        val profiles=FirmwareMetadata(Formats.map(catalog.firmwareMetadata(emptyMap()))).memoryProfiles
        val declared=profiles[declaredProfile]?.sortedBy{it.address} ?: error("Bundled catalog lacks Method-4 output declaration: "+declaredProfile)
        val isBootRomFullBoot = selected.baseProfile == "boot-rom-full-boot-v1"
        val effectiveBaseRegions = if (isBootRomFullBoot) {
            selected.baseRegions.filterNot { it.id.startsWith("boot-rom") }
        } else {
            selected.baseRegions
        }
        require((selected.baseProfile==FlashloadBufferAdmission.PROFILE || isBootRomFullBoot)&&selected.baseRegions.sortedBy{it.address}==baseRegions.sortedBy{it.address}
            && declared==finiteProfileRegions(effectiveBaseRegions,declaredProfile)) {"Method-4 plan differs from saved base and catalog declaration"}
        if(selected.sourceProfile in PROFILES)require(selected.additionalRegions==listOf(expected)) {"Method-4 plan must contain only the declared output with exact permissions"}
        if(expected.permissions==7)require(selected.profile==AdditionalRamRegions.EXEC_CAPABLE_PROFILE) {"Executable Method-4 output requires the exec-capable RAM profile"}
        AdditionalRamRegions.validate(selected.baseRegions,selected.additionalRegions,selected.profile)
        val geometry=sourceGeometry(app,dat)
        val elfloader=if(declaredProfile==EXEC_PROFILE)execGeometry(app,dat)else null
        return mapOf("profile" to selected.profile,"plan" to selected.receipt(),"declaredProfile" to declaredProfile,
            "geometry" to geometry,"sourceId" to SOURCE_ID,"sourceLogical" to SOURCE_LOGICAL,"outputDescriptor" to OUTPUT_DESCRIPTOR,
            "headerDatStart" to FLASH_XOR,"headerDatEndExclusive" to FLASH_XOR+20,
            "partialExactHeaderReproduction" to "20B header only; no whole-container decode or output generation supplied",
            "initialContent" to "HYPOTHESIS: explicit zero; original guest codec writes",
            "permissions" to if(expected.permissions==7) "HYPOTHESIS RWX" else "HYPOTHESIS RW/NX",
            "physicalGeometry" to "UNKNOWN","cpuMapping" to if(expected.permissions==7)
                "Guest-owned CPU page tables; physical RWX backing only, no CPU page-table changes supplied"
            else "UNKNOWN: no CPU page-table or execute permission supplied",
            "transfersInjected" to 0,"decodedBytesInjected" to 0)+(elfloader?.let{mapOf("elfloaderExec" to it)} ?: emptyMap())
    }
}
