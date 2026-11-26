package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.ImageRegionIdentity
import com.github.nyabanban.fuji.emulator.api.WritableImageState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Software command/address geometry only. FF erase, storage timing and private-copy mapping remain HYP. */
object FlashEraseContracts {
    const val PROFILE="gfx100s-pio-erase-functional-experimental-v3"
    private const val BASE=0x1021000L
    private data class Span(val start:Long,val size:Int,val sha:String)
    private val spans=listOf(
        Span(0x11df724,320,"a999a496a6e1868ed6e290799ba737340e16cd2247f1180a95194883e5915ca7"),
        Span(0x11ddd2c,324,"8dd8d2e2029db84aaac580a82e0c8123d96ca74ad726b1c0828860760849cf5a"),
        Span(0x11dea40,304,"cc30ed0e8a0f2c59c66007d6188fdd03df50640c7a3905d9f52fe18ebd60b8ea"),
        Span(0x129df0c,84,"8d505bd6aaa2cf0a6f03753b2a65922f6fa070db1baea1f49f1a2b098884f1f7"),
        Span(0x11dfa78,180,"5cb682e736ce4e2026ec3cc1bdea0144f2c1b75de81553129963380348ece640"),
        Span(0x11dff6c,76,"03c2d3d5e49bd0780d7d9ff144711c5aedd3709b8e5c46195c142cf357036b4e"))
    val wordAddresses:List<Long> get()=spans.flatMap{(it.start until it.start+it.size step 4).toList()}
    private fun hash(b:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
    fun observe(app:ByteArray):Map<String,Any> {
        for(s in spans){require(s.start>=BASE&&s.start-BASE+s.size<=app.size){"erase DAT span outside initialized image"}
            require(hash(app.copyOfRange((s.start-BASE).toInt(),(s.start-BASE+s.size).toInt()))==s.sha){"erase DAT span differs at "+s.start.toString(16)}}
        fun word(pc:Long)=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN).getInt((pc-BASE).toInt()).toLong()and 0xffffffffL
        fun imm(w:Long)=Integer.rotateRight((w and 255).toInt(),((w ushr 8 and 15)*2).toInt()).toLong()and 0xffffffffL
        fun half(w:Long)=(w and 4095)or(w ushr 4 and 0xf000)
        fun pointer(pc:Long)=half(word(pc))or(half(word(pc+4))shl 16)
        val geometryGlobal=pointer(0x11df7d4)
        val structure=pointer(0x11ddd44)
        val small=1L shl(32-((word(0x11df738)ushr 7)and 31).toInt())
        val selected=imm(word(0x11dffb0))
        val blockShift=(word(0x129df38)ushr 7)and 31
        val apertureBase=(0x1_0000_0000L-imm(word(0x11df7c8)))and 0xffffffffL
        val lastOffset=0x1_0000_0000L-imm(word(0x11df7cc))
        val smallOp=imm(word(0x11df78c));val largeOp=imm(word(0x11df830))
        require(geometryGlobal==0x1870d10L&&structure==0x1870cd0L&&small==4096L&&selected==65536L&&blockShift==17L)
        require(apertureBase==0xf0000000L&&lastOffset==0x7ffffffL&&smallOp==9L&&largeOp==10L)
        require(word(0x11df7e0)==0xe732f013L&&word(0x11df7e4)==0xe0623290L)
        require((word(0x11df81c)and 4095)==largeOp*28+8&&(word(0x11df778)and 4095)==smallOp*28+8)
        return linkedMapOf("profile" to PROFILE,"criticalWords" to wordAddresses.size,"geometryGlobal" to geometryGlobal,"structure" to structure,
            "smallGeometryAddress" to(structure+(word(0x11ddd4c)and 4095)),"largeGeometryAddress" to(structure+(word(0x11ddd50)and 4095)),
            "smallGranule" to small,"selectedLargeGranule" to selected,"logicalBlockShift" to blockShift,"apertureBase" to apertureBase,"apertureLastOffset" to lastOffset,
            "smallOperationIndex" to smallOp,"largeOperationIndex" to largeOp,
            "scope" to "DAT software granules/address bounds/UDIV-MLS alignment, selected3351072 table. Physical chip capacity/address lines/erase law/timing unknown. Functional8/4096 tick FF erase only in explicit writable copies; no program or power-loss durability claim.")
    }
    fun verify(image:FirmwareImage,flash:Map<String,Any?>,copy:WritableImageState,regions:List<ImageRegionIdentity>,tailProfile:String):Map<String,Any> {
        val meta=image.metadata
        require(meta.id=="gfx100s-213"&&meta.sha256=="ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12"&&flash["profile"]==PROFILE&&(flash["jedec"]as? Number)?.toLong()==FlashPioEnableContracts.JEDEC_INPUT){"erase requires selected firmware/profile/explicit3351072"}
        val windows=meta.devices.filter{it.id=="soc-flash"}
        require(windows.size==2&&windows.single{it.port=="soc-flash.registers"}.let{it.address==0xfffd8000L&&it.size==4096L&&!it.optional}&&
            windows.single{it.port=="soc-flash.aperture"}.let{it.address==0xf0000000L&&it.size==0x8000000L&&!it.optional}){"erase exact flash metadata windows differ"}
        val input=image.inputPort();val header=ByteBuffer.allocate(0x274).order(ByteOrder.LITTLE_ENDIAN);input.read(0,header);check(!header.hasRemaining())
        val extent=header.getInt(0x24c).toLong()and 0xffffffffL
        require(header.getInt(0)==6&&extent in 1..0x8000000L&&extent<=input.size-0x274)
        require(tailProfile in listOf("strict","dat-end-block-zero-experimental-v1","dat-end-block-ff-experimental-v1"))
        val end=extent+if(tailProfile=="strict")0L else(-extent and 4095L)
        val seed=regions.singleOrNull{it.id==copy.regionId}?:error("erase explicit seed identity missing")
        require(regions.all{it.offset>=end}&&seed.offset==copy.offset&&seed.length==copy.length&&seed.sha256==copy.seedSha256&&seed.relativeFile!=copy.relativeFile&&
            copy.offset>=end&&copy.length>0&&copy.offset<=0x8000000L-copy.length&&copy.writableOffset>=copy.offset&&copy.writableLength>0&&copy.writableOffset<=copy.offset+copy.length-copy.writableLength&&
            !copy.pending&&!copy.closed&&copy.failure==null){"erase writable copy identity/range/lifecycle differs"}
        val app=image.decode("section4")
        return observe(app)+mapOf("pio" to FlashPioEnableContracts.observe(app),"datProvidedExtent" to extent,"selectedPrefixEnd" to end,"writableInput" to copy)
    }
}
