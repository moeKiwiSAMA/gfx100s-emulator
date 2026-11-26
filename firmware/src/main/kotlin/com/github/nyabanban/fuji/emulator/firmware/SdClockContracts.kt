package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Pinned software clock polling and original delay, not a measured physical clock law. */
object SdClockContracts {
    const val PROFILE = "gfx100s-sd-host-clock-samples-experimental-v3"
    val addedSpans = listOf(
        SdHostContracts.Span(0x11f1270,160,"d4acc92b846960c43f112fa07d0fa5697b11656c8fcf1f8ebea566c275c7ac39",false),
        SdHostContracts.Span(0x12b7148,48,"88917954dc205f77a4fe27b90079b8d4ccddd3a91dae986a40da0621344b5d34",false),
        SdHostContracts.Span(0x12b7178,148,"57ba6915a4df7ccc7fcbad463579a867ee4f4f40fcf51217ba4c0f796715b579",false),
        SdHostContracts.Span(0x5a7bc,72,"a2fa18cb4b995f3edae80af41a15efe0cab1679fbba64643eaba87022c30f83a",true),
        SdHostContracts.Span(0x5a8d8,72,"d1bbf87403ddc3b3f978cee612e451dbc1d537def546565d91c5e59733d0d6d5",true)
    )
    val spans = SdHostContracts.controlSpans + addedSpans
    fun clockWindows() = (0..1).flatMap { slot -> (0..1).map { controller ->
        DeviceWindow("soc-sd-host",0xfffc402cL + slot*0x4000 + controller*0x2200,2,
            "soc-sd-host.clock-$slot-$controller",true)
    } }
    fun windows() = SdHostContracts.controlWindows() + clockWindows()
    private fun hash(bytes:ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    @JvmOverloads
    fun observe(app:ByteArray,kernel:ByteArray,profile:String=PROFILE):Map<String,Any> {
        require(profile in setOf(PROFILE,SdInitContracts.PHY_PROFILE))
        val controls = SdHostContracts.observeControls(app,kernel,if(profile==SdInitContracts.PHY_PROFILE)profile else SdHostContracts.CONTROL_PROFILE)
        val selectedSpans=if(profile==SdInitContracts.PHY_PROFILE)spans+SdInitContracts.PHY_SPAN else spans
        val reports = selectedSpans.map { span ->
            val code = if(span.kernel)kernel else app; val base = if(span.kernel)0x40000L else 0x1021000L
            val at = (span.start-base).toInt(); require(at>=0 && at<=code.size-span.size)
            val bytes = code.copyOfRange(at,at+span.size)
            require(hash(bytes)==span.sha256) { "SD clock/delay DAT span differs" }
            mapOf("address" to span.start,"bytes" to span.size,"sha256" to hash(bytes))
        }
        fun word(pc:Long):Long {val code=if(pc<0x100000L)kernel else app;val base=if(pc<0x100000L)0x40000L else 0x1021000L;return ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN).getInt((pc-base).toInt()).toLong()and 0xffffffffL}
        require(word(0x11f12ac)==0xe3833001L && word(0x11f12e4)==0xe3130002L && word(0x11f12cc)==0xe3a04ffaL && word(0x12b71b4)==0xe0853593L)
        return controls + mapOf("profile" to profile,"criticalWords" to selectedSpans.sumOf{it.size/4},"spans" to reports,
            "clockWindows" to clockWindows().map{mapOf("port" to it.port,"address" to it.address,"size" to it.size)},
            "clockPollMask" to 2,"clockOrMask" to 1,"maxPolls" to 1000,"delayArgument" to 1,
            "timerValueAddress" to 0xfff3c348L,"timerReloadAddress" to 0xfff3c34cL,
            "limitation" to "Software geometry/native-test scope; explicit per-read bit1 samples/full-u16 retention HYP. No physical stable latency, reset, card response or SD protocol qualification.")
    }
    @Suppress("UNCHECKED_CAST")
    @JvmOverloads
    fun verify(image:FirmwareImage,state:Map<String,Any?>,claims:List<String>,active:List<DeviceWindow>,expectedProfile:String=PROFILE):Map<String,Any> = verifyDeclared(image,state,claims,active,expectedProfile,image.metadata.devices.filter{it.id=="soc-sd-host"})
    internal fun verifyDeclared(image:FirmwareImage,state:Map<String,Any?>,claims:List<String>,active:List<DeviceWindow>,expectedProfile:String,declared:List<DeviceWindow>):Map<String,Any> {
        require(image.metadata.id=="gfx100s-213" && image.metadata.sha256==SdHostContracts.FIRMWARE_SHA)
        require(expectedProfile in setOf(PROFILE,SdInitContracts.PHY_PROFILE) && state["profile"]==expectedProfile && state["enabled"]==true && state["closed"]==false && state["cardProtocolQualified"]==false)
        val expected = windows(); val selected = active.filter{it.id=="soc-sd-host"}
        require(claims==expected.map{it.port} && (listOf(expected,SdInitContracts.windows()).any{declared.size==it.size&&declared.toSet()==it.toSet()}) && selected.size==14 && selected.toSet()==expected.toSet())
        val controls = (state["controlState"] as? Map<*, *>)?.takeIf { m -> m.keys.all { it is String } }?.entries?.associate { (k, v) -> k as String to v } ?: error("SD control projection missing")
        SdHostContracts.verifyControls(image,controls,SdHostContracts.controlWindows().map{it.port},active.filter{it.port in SdHostContracts.controlWindows().map{it.port}},if(expectedProfile==SdInitContracts.PHY_PROFILE)expectedProfile else SdHostContracts.CONTROL_PROFILE,declared)
        val clocks = state["clocks"] as? Map<*,*> ?: error("Clock inputs missing")
        require(clocks.keys==setOf("0:0","0:1","1:0","1:1"))
        fun integer(value:Any?):Long {require(value is Byte||value is Short||value is Int||value is Long);return(value as Number).toLong()}
        for(raw in clocks.values) {
            val row=raw as? Map<*,*> ?: error("Clock row missing")
            require(row.keys==setOf("initialWord","storedWord","bit1Samples","consumedSamples"))
            val initial=integer(row["initialWord"]);require(initial in 0..65535 && initial and 2L==0L && integer(row["storedWord"])==initial && integer(row["consumedSamples"])==0L)
            val samples=row["bit1Samples"] as? List<*> ?: error("Clock samples missing");require(samples.size<=4096 && samples.all{it==null||integer(it)in 0..1})
        }
        require(listOf("clockReadCount","clockWriteCount","clockAccessCount").all{integer(state[it])==0L})
        return observe(image.decode("section4"),image.decode("kernel"),expectedProfile) + mapOf("inputIdentityVerified" to true,"inputSha256" to image.metadata.sha256,"clockInputs" to clocks)
    }
}
