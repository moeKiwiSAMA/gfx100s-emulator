package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Selected-DAT allocation geometry; zero backing and RW/NX remain explicit hypotheses. */
object L2Contracts {
    const val PROFILE="kernel-l2-tables-experimental-v9"
    private val critical=linkedMapOf(
        0x58054L to 0xe1a05000L,
        0x580ccL to 0xe3007231L,
        0x58184L to 0xe1a0e822L,
        0x58188L to 0xe1a0e80eL,
        0x5818cL to 0xe38ee031L,
        0x58190L to 0xe594c00cL,
        0x58194L to 0xe35c0002L,
        0x58198L to 0x38ee004L,
        0x5819cL to 0xa000002L,
        0x581a0L to 0xe35c0001L,
        0x581a4L to 0xe38eeb19L,
        0x581a8L to 0x38ee004L,
        0x581acL to 0xe594c008L,
        0x581b0L to 0xe35c0000L,
        0x581b4L to 0x38ee902L,
        0x581b8L to 0xe1a0c526L,
        0x581bcL to 0xe20ccfffL,
        0x581c0L to 0xe785e00cL,
        0x581c4L to 0xe2922a01L,
        0x581c8L to 0xe2a33000L,
        0x581ccL to 0xe2866a01L,
        0x581d0L to 0xe1530001L,
        0x581d4L to 0x1520000L,
        0x581d8L to 0xaffffc4L,
        0x581dcL to 0xe594e004L,
        0x58204L to 0xe92d4bf0L,
        0x58208L to 0xe28db01cL,
        0x5820cL to 0xe24dd018L,
        0x58210L to 0xe1a04a21L,
        0x58214L to 0xe1a04a04L,
        0x58218L to 0xe7903924L,
        0x5821cL to 0xe2032003L,
        0x58220L to 0xe3520001L,
        0x58224L to 0x3a00000L,
        0x58228L to 0xa000003L,
        0x5822cL to 0xe7f32051L,
        0x58230L to 0xe3520000L,
        0x58234L to 0x3a00000L,
        0x58238L to 0x1a000001L,
        0x5823cL to 0xe24bd01cL,
        0x58240L to 0xe8bd8bf0L,
        0x58244L to 0xe3042b18L,
        0x58248L to 0xe3402008L,
        0x5824cL to 0xe5927000L,
        0x58250L to 0xe3570000L,
        0x58254L to 0xa000019L,
        0x58258L to 0xe3042b18L,
        0x5825cL to 0xe3402008L,
        0x58260L to 0xe597c000L,
        0x58264L to 0xe582c000L,
        0x58268L to 0xe1a05001L,
        0x5826cL to 0xe1a08000L,
        0x58270L to 0xe24b902cL,
        0x58274L to 0xe1a01003L,
        0x58278L to 0xe1a00009L,
        0x5827cL to 0xebffff11L,
        0x58280L to 0xe3a06000L,
        0x58284L to 0xe58d9004L,
        0x58288L to 0xe3a03601L,
        0x5828cL to 0xe58d3000L,
        0x58290L to 0xe1a02004L,
        0x58294L to 0xe1a03006L,
        0x58298L to 0xe1a01004L,
        0x5829cL to 0xe1a00007L,
        0x582a0L to 0xebffff68L,
        0x582a4L to 0xe3c77fffL,
        0x582a8L to 0xe3c77003L,
        0x582acL to 0xe1a01a25L,
        0x582b0L to 0xe3877001L,
        0x582b4L to 0xe7887101L,
        0x582b8L to 0xe1a00006L,
        0x582bcL to 0xeaffffdeL,
        0x582c0L to 0xe3042b1cL,
        0x582c4L to 0xe3402008L,
        0x582c8L to 0xe5922000L,
        0x582ccL to 0xe3520c02L,
        0x582d0L to 0x2a000007L,
        0x582d4L to 0xe3007000L,
        0x582d8L to 0xe340700aL,
        0x582dcL to 0xe0877502L,
        0x582e0L to 0xe304cb1cL,
        0x582e4L to 0xe340c008L,
        0x582e8L to 0xe2822001L,
        0x582ecL to 0xe58c2000L,
        0x582f0L to 0xeaffffdcL,
        0x582f4L to 0xe3a00001L,
        0x582f8L to 0xeaffffcfL)
    val instructionAddresses get()=critical.keys.toList()
    private fun word(kernel: ByteArray,pc: Long): Long {
        require(pc%4==0L&&pc>=0x40000&&pc-0x40000<=kernel.size-4L){"L2 DAT instruction outside initialized kernel"}
        return ByteBuffer.wrap(kernel,(pc-0x40000).toInt(),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()and 0xffffffffL
    }
    private fun imm(w: Long)=Integer.rotateRight((w and 255).toInt(),((w ushr 8 and 15)*2).toInt()).toLong()and 0xffffffffL
    private fun pair(kernel: ByteArray,pc: Long): Long {
        fun half(w: Long)=(w and 0xfff)or(w ushr 4 and 0xf000)
        return half(word(kernel,pc))or(half(word(kernel,pc+4))shl 16)
    }
    fun observe(kernel: ByteArray): Map<String,Any> {
        critical.forEach{(pc,w)->require(word(kernel,pc)==w){"L2 DAT consumer differs at "+pc.toString(16)}}
        val base=pair(kernel,0x582d4);val count=imm(word(kernel,0x582cc));val shift=(word(kernel,0x582dc)ushr 7 and 31).toInt()
        val stride=1L shl shift;val size=Math.multiplyExact(count,stride);val end=Math.addExact(base,size)
        val span=imm(word(kernel,0x58288));val step=imm(word(kernel,0x581cc));val mask=imm(word(kernel,0x581bc))
        val words=span/step
        require(base==0xa0000L&&count==512L&&shift==10&&stride==1024L&&end==0x120000L&&span==0x100000L&&step==4096L&&words==256L&&mask==1020L)
        require(pair(kernel,0x58244)==0x84b18L&&pair(kernel,0x582c0)==0x84b1cL&&pair(kernel,0x582e0)==0x84b1cL)
        return mapOf("profile" to PROFILE,"base" to base,"tableCount" to count,"indexShift" to shift,"tableBytes" to stride,"byteSize" to size,"endExclusive" to end,"lastTableBase" to end-stride,"lastWord" to end-4,
            "freeListAddress" to pair(kernel,0x58244),"allocationCounterAddress" to pair(kernel,0x582c0),"virtualBytesPerTable" to span,"wordCount" to words,"virtualStepBytes" to step,"wordOffsetMask" to mask,
            "allocator" to 0x58204,"builder" to 0x58048,"storeInstruction" to 0x581c0,"publicationInstruction" to 0x582b4,"exhaustedReturn" to 1,
            "criticalWords" to critical.size,"checkedInstructions" to instructionAddresses,"basis" to "DAT guarded index<512, base A0000+index<<10, 256 word stores and L1 coarse publication after return; no physical DRAM/reset/cache/timing claim")
    }
    fun regions(base: List<MemoryRegion>,geometry: Map<String,Any>): List<MemoryRegion> {
        require(geometry["profile"]==PROFILE)
        val address=(geometry.getValue("base")as Number).toLong();val size=(geometry.getValue("byteSize")as Number).toLong()
        require(address==0xa0000L&&size==0x80000L)
        require(base.none{it.address<address+size&&address<it.address+it.size}){"L2 arena overlaps inherited RAM"}
        val sorted=(base+MemoryRegion("kernel-l2-tables",address,size,3)).sortedBy{it.address}
        require(sorted.map{it.id}.distinct().size==sorted.size)
        val merged=ArrayList<MemoryRegion>()
        for(r in sorted){require(r.address>=0&&r.size>0&&r.address<=0x100000000L-r.size&&r.address%4096==0L&&r.size%4096==0L&&r.permissions in 1..7)
            val last=merged.lastOrNull()
            if(last!=null&&r.address<=last.address+last.size){require(r.address==last.address+last.size||r.permissions==last.permissions){"L2 permission overlap"}
                if(r.permissions==last.permissions)merged[merged.lastIndex]=last.copy(size=maxOf(last.address+last.size,r.address+r.size)-last.address)else merged.add(r)
            }else merged.add(r)
        }
        return merged.toList()
    }
    fun verify(image: FirmwareImage,selected: List<MemoryRegion>,v8: SegmentsContracts.VerifiedPlan): SegmentsContracts.VerifiedPlan {
        require(image.metadata.id=="gfx100s-213"&&v8.report["profile"]==PoolContracts.TRACE_PROFILE&&v8.regions==image.metadata.memoryProfiles.getValue(PoolContracts.TRACE_PROFILE).sortedBy{it.address}){"L2 contract requires verified selected v8"}
        val geometry=observe(image.decode("kernel"));val all=regions(v8.regions,geometry)
        require(all==selected.sortedBy{it.address}){"selected L2 RAM differs from DAT arena or inherited permissions"}
        return SegmentsContracts.VerifiedPlan(all,v8.report+mapOf("profile" to PROFILE,"l2Tables" to geometry,"regions" to all.map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)},
            "basis" to "Verified v8 plus exactly A0000..11FFFF from DAT 512*1024 allocator; merge equal-permission adjacent ranges retaining left id. RW/NX/zero native backing remain HYP; other holes and strict v8 control preserved"))
    }
}
