package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Optional SOURCE SD transaction models. Identity, latency and address-extension behavior remain HYPOTHESIS. */
object SdTransactionContracts {
    const val PROFILE="gfx100s-sd-host-card-transactions-experimental-v6"
    const val ADDRESS_PROFILE="gfx100s-sd-host-address-extension-experimental-v7"
    const val VENDOR_PHY_PROFILE="gfx100s-sd-host-vendor-phy-experimental-v8"
    const val UHS_PHY_PROFILE="gfx100s-sd-host-uhs-phy-experimental-v9"
    val VENDOR_SPAN=SdHostContracts.Span(0x11f1c54,132,"1a8e200dc0a96547ca106a1d7d03a917bc191ae955d99018e859949d4a2451be",false)
    val UHS_PHY_SPANS=listOf(
        SdHostContracts.Span(0x11f08ec,224,"219886182083f57d4d45a1fe9d1c687f0a66f3d573155a37615c81bb18395678",false),
        SdHostContracts.Span(0x11f0a68,116,"5ea678825b4ef58454dd28c906af3ca9d729b126b8259255121a615fc00a3b98",false),
        SdHostContracts.Span(0x11f2534,72,"903b6f0d6b8c0d86cf0237b6b2560166a5846d1b615eca08dddb24727d65e431",false),
        SdHostContracts.Span(0x11f26bc,116,"117b502c0670590175ac825777ed24a41b9a1ab1e1e66df1a82f515404ef0897",false),
        SdHostContracts.Span(0x11f2784,32,"cd2d833b98bb9bf7c3bf1ffcf2fa4fd7c2924fddd9e68ceb656b1e23a369659b",false),
        SdHostContracts.Span(0x11f27a4,36,"c775567015aca5666c380172c76fa5b45f977295c248330d88a56537652da424",false),
        SdHostContracts.Span(0x11f27c8,84,"90ca3865265e8e2437a8f9915c8f4ae844ea026b2b9ec4fef144ec52687ab3be",false),
        SdHostContracts.Span(0x11f281c,72,"fe28b51ac6a9181af3a5974cfe4d05ca35ed716bae3e97e069296304cacef229",false),
        SdHostContracts.Span(0x11f2864,76,"066bacaf04495fca52793f5c63f4b91e148127aed1b8fc6946b714e04f971fa9",false),
        SdHostContracts.Span(0x11f05c8,76,"c808118b2e04e4028aa15e38b01265bb8b6b705a5a835a47e7f31e8b5d886cdf",false),
        SdHostContracts.Span(0x11f0630,84,"c78f3e5c67859e6178aa8998f94c56a2cbbd4e73480a5cf6ca82605a4b789406",false),
        SdHostContracts.Span(0x11f0d74,116,"926f1838ebd1d2f338f7c6990eb6a6d04b074935932270fbc6e74c790d216291",false),
        SdHostContracts.Span(0x11f1330,64,"64a87eb22f93b05ddff1b433df10b9b0d3bb797f7f55dc8cdf6ff1494fddb783",false),
        SdHostContracts.Span(0x11f1818,124,"67f384c09496f6fc0c50fb9be90f079e947665ba7c9d28f1e2cacf4e05f8997d",false),
        SdHostContracts.Span(0x11f1894,148,"0efd415cf75e29eb0e853d2ad4576b00b0ef28d8033e819c322f6f2aeef44d9d",false),
        SdHostContracts.Span(0x11f1a80,100,"5aec7b9120d3a57e3ec9e364d94f247b86c62b4c65e07d6e31a60a167e003c15",false),
        SdHostContracts.Span(0x11f1ae4,28,"127051ee69263bc4f1576067c191e2a171d998c5a12b4360864cf964d5cd268b",false),
        SdHostContracts.Span(0x11f1b00,84,"856f8090ba2f044660dca99f1d66c677ec4627ef3814ec36bb7b1aec6d046a0a",false),
        SdHostContracts.Span(0x11f2434,80,"368cd0003adfd56db846201f40cc442b92f37959a1d6ebdbede634b5f8dc029c",false),
    )
    val VENDOR_PHY_FIELDS=listOf("vendor41e0","vendor41e4","phy6060","phy607c","phy6094")
    val UHS_PHY_U16_FIELDS=listOf("uhs4100","uhs4108","uhs410a","uhs410c","uhs410e","uhs2_6280","uhs2_62c0","uhs2_62c2")
    val UHS_PHY_U32_FIELDS=listOf("uhs4114","uhs4124","phy6020","phy6024","uhs2_6258","uhs2_625c","uhs2_6284","uhs2_6288","uhs2_628c","uhs2_6290","uhs2_629c","uhs2_62c4","uhs2_62c8","uhs2_62cc")
    val UHS_PHY_FIELDS=UHS_PHY_U16_FIELDS+UHS_PHY_U32_FIELDS
    private val replaced=(0..1).flatMap { slot -> listOf("block4006-$slot","present4024-$slot","host4028-$slot","clock-$slot-0","control403e-$slot").map {"soc-sd-host.$it"} }.toSet()
    @JvmOverloads
    fun windows(profile:String=PROFILE):List<DeviceWindow> {
        require(profile in setOf(PROFILE,ADDRESS_PROFILE,VENDOR_PHY_PROFILE,UHS_PHY_PROFILE))
        val extended=profile in setOf(ADDRESS_PROFILE,VENDOR_PHY_PROFILE,UHS_PHY_PROFILE)
        val vendorPhy=profile in setOf(VENDOR_PHY_PROFILE,UHS_PHY_PROFILE)
        val uhsPhy=profile==UHS_PHY_PROFILE
        val txName=when(profile) { UHS_PHY_PROFILE->"transaction-v9"; VENDOR_PHY_PROFILE->"transaction-v8"; ADDRESS_PROFILE->"transaction-address"; else->"transaction" }
        val highName=when(profile) { UHS_PHY_PROFILE->"address-high-v9"; VENDOR_PHY_PROFILE->"address-high-v8"; else->"address-high" }
        val vpSuffix=if(uhsPhy) "-v9" else ""
        return SdInitContracts.windows().filter {it.port !in replaced} + (0..1).map {slot ->
            DeviceWindow("soc-sd-host",0xfffc4000L+slot*0x4000,64,"soc-sd-host.$txName-$slot",true)
        } + (if(extended)(0..1).map {slot -> DeviceWindow("soc-sd-host",0xfff4a888L+slot*12,4,"soc-sd-host.$highName-$slot",true)} else emptyList()) +
            (if(vendorPhy)(0..1).flatMap {slot -> listOf(
                DeviceWindow("soc-sd-host",0xfffc41e0L+slot*0x4000,8,"soc-sd-host.vendor-a$vpSuffix-$slot",true),
                DeviceWindow("soc-sd-host",0xfffc6060L+slot*0x4000,4,"soc-sd-host.phy6060$vpSuffix-$slot",true),
                DeviceWindow("soc-sd-host",0xfffc607cL+slot*0x4000,4,"soc-sd-host.phy607c$vpSuffix-$slot",true),
                DeviceWindow("soc-sd-host",0xfffc6094L+slot*0x4000,4,"soc-sd-host.phy6094$vpSuffix-$slot",true),
            )} else emptyList()) +
            (if(uhsPhy)(0..1).flatMap {slot -> listOf(
                DeviceWindow("soc-sd-host",0xfffc4100L+slot*0x4000,2,"soc-sd-host.uhs4100-$slot",true),
                DeviceWindow("soc-sd-host",0xfffc4108L+slot*0x4000,8,"soc-sd-host.uhs4108-$slot",true),
                DeviceWindow("soc-sd-host",0xfffc4114L+slot*0x4000,4,"soc-sd-host.uhs4114-$slot",true),
                DeviceWindow("soc-sd-host",0xfffc4124L+slot*0x4000,4,"soc-sd-host.uhs4124-$slot",true),
                DeviceWindow("soc-sd-host",0xfffc6020L+slot*0x4000,8,"soc-sd-host.phy-trim-$slot",true),
                DeviceWindow("soc-sd-host",0xfffc6258L+slot*0x4000,8,"soc-sd-host.uhs2-dma-$slot",true),
                DeviceWindow("soc-sd-host",0xfffc6280L+slot*0x4000,2,"soc-sd-host.uhs2-6280-$slot",true),
                DeviceWindow("soc-sd-host",0xfffc6284L+slot*0x4000,16,"soc-sd-host.uhs2-cmd-$slot",true),
                DeviceWindow("soc-sd-host",0xfffc629cL+slot*0x4000,4,"soc-sd-host.uhs2-629c-$slot",true),
                DeviceWindow("soc-sd-host",0xfffc62c0L+slot*0x4000,16,"soc-sd-host.uhs2-err-$slot",true),
            )} else emptyList())
    }
    /** Pinned DAT UHS-I tuning, PHY analog trim, and UHS-II Bank B routines (19 functions, 1732B). */
    fun observeUhsPhy(app:ByteArray):Map<String,Any> {
        val md=java.security.MessageDigest.getInstance("SHA-256")
        val hex=java.util.HexFormat.of()
        val spans=UHS_PHY_SPANS.map { s ->
            val at=(s.start-0x1021000L).toInt()
            require(at>=0 && at<=app.size-s.size) {"SD UHS/PHY DAT span missing"}
            val digest=hex.formatHex(md.digest(app.copyOfRange(at,at+s.size)))
            require(digest==s.sha256) {"SD UHS/PHY DAT span differs at 0x${s.start.toString(16)}"}
            mapOf("address" to s.start,"bytes" to s.size,"sha256" to digest)
        }
        return mapOf(
            "criticalWords" to UHS_PHY_SPANS.sumOf { it.size / 4 },
            "spans" to spans,
            "uhs1Addresses" to listOf(0xfffc4100L,0xfffc4108L,0xfffc410aL,0xfffc410cL,0xfffc410eL,0xfffc4114L,0xfffc4124L),
            "phyTrimAddresses" to listOf(0xfffc6020L,0xfffc6024L),
            "uhs2BankBAddresses" to listOf(0xfffc6258L,0xfffc625cL,0xfffc6280L,0xfffc6284L,0xfffc6288L,0xfffc628cL,0xfffc6290L,0xfffc629cL,0xfffc62c0L,0xfffc62c2L,0xfffc62c4L,0xfffc62c8L,0xfffc62ccL),
            "scope" to "DAT software access geometry for UHS-I tuning/1.8V, PHY analog trim, and UHS-II Bank B registers under opt-in v9. Explicit initial words, full-width retention, and reset persistence remain HYPOTHESIS; unmodeled gap addresses inside the 4 candidate windows stay strictly refused.",
        )
    }
    /** Pinned DAT vendor41e0/vendor41e4 (0x11F1C54, 132B) and PHY init (0x11F1CD8, 312B) routines. */
    fun observeVendorPhy(app:ByteArray):Map<String,Any> {
        val md=java.security.MessageDigest.getInstance("SHA-256")
        val hex=java.util.HexFormat.of()
        val spans=listOf(VENDOR_SPAN,SdInitContracts.PHY_SPAN).map { s ->
            val at=(s.start-0x1021000L).toInt()
            require(at>=0 && at<=app.size-s.size) {"SD vendor/PHY DAT span missing"}
            val digest=hex.formatHex(md.digest(app.copyOfRange(at,at+s.size)))
            require(digest==s.sha256) {"SD vendor/PHY DAT span differs at 0x${s.start.toString(16)}"}
            mapOf("address" to s.start,"bytes" to s.size,"sha256" to digest)
        }
        return mapOf("criticalWords" to (VENDOR_SPAN.size+SdInitContracts.PHY_SPAN.size)/4,"spans" to spans,
            "vendorAddresses" to listOf(0xfffc41e0L,0xfffc41e4L,0xfffc81e0L,0xfffc81e4L),
            "phyAddresses" to listOf(0xfffc6060L,0xfffc607cL,0xfffc6094L,0xfffca060L,0xfffca07cL,0xfffca094L),
            "scope" to "DAT software access geometry for boot-exercised vendor41e0/vendor41e4 and phy6060/phy607c/phy6094. Explicit initial words and full-u32 retention remain HYPOTHESIS; unexercised UHS-I tuning and UHS-II bank B registers remain outside v8.")
    }
    /** Complete original writer and low-address setter, including condition codes and full BL words. */
    fun observeAddressExtension(app:ByteArray):Map<String,Any> {
        val writer=longArrayOf(0xe92d48f0L,0xe28db014L,0xe1a04002L,0xe3520001L,0x93510001L,0x83a03001L,0x93a03000L,0xe3500001L,0x83833001L,0xe3530000L,0x03a05000L,0x13e0501dL,0xe3510001L,0x0a000007L,0xe30a3888L,0xe34f3ff4L,0xe1a01002L,0xe3a0200cL,0xe0203092L,0xebb9a1a3L,0xe1a00005L,0xe8bd88f0L,0xe3a0600cL,0xe0060096L,0xe1a01002L,0xe2460ab5L,0xe2400d1eL,0xebb9a19bL,0xe1a01004L,0xe30a0884L,0xe34f0ff4L,0xe0860000L,0xebb9a196L,0xeafffff1L)
        val low=longArrayOf(0xe92d4800L,0xe28db004L,0xe30f3ff1L,0xe3403003L,0xe0803003L,0xe1a00703L,0xebb9a718L,0xe8bd8800L)
        val buffer=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
        for((start,words) in listOf(0x11f28b0L to writer,0x11f1310L to low)) {
            val offset=start-0x1021000L
            require(offset>=0 && offset+words.size*4<=app.size) {"SD address-extension DAT span missing"}
            words.forEachIndexed {i,word->require((buffer.getInt(offset.toInt()+i*4).toLong() and 0xffffffffL)==word) {"SD address-extension DAT word differs at 0x${(start+i*4).toString(16)}"}}
        }
        return mapOf("criticalWords" to (writer.size+low.size),"writer" to 0x11f28b0L,"writerBytes" to 136,
            "lowAddressSetter" to 0x11f1310L,"lowAddressSetterBytes" to 32,"highWordAddresses" to listOf(0xfff4a888L,0xfff4a894L),
            "scope" to "DAT software write geometry only. The v7 bit32 association, initial latch, reset persistence and no-cross-low32 rule remain HYPOTHESIS; mode1 words are not mapped.")
    }
    @Suppress("UNCHECKED_CAST")
    fun verify(image:FirmwareImage,state:Map<String,Any?>,claims:List<String>,active:List<DeviceWindow>):Map<String,Any> {
        require(image.metadata.id=="gfx100s-213" && image.metadata.sha256==SdHostContracts.FIRMWARE_SHA)
        val profile=state["profile"] as? String ?: error("SD transaction profile missing")
        require(profile in setOf(PROFILE,ADDRESS_PROFILE,VENDOR_PHY_PROFILE,UHS_PHY_PROFILE) && state["enabled"]==true && state["closed"]==false && state["started"]==true && state["cardProtocolQualified"]==false)
        val expected=windows(profile);val declared=image.metadata.devices.filter {it.id=="soc-sd-host"};val selected=active.filter {it.id=="soc-sd-host"}
        require(claims==expected.map {it.port} && declared.size==expected.size && declared.toSet()==expected.toSet() && selected.size==expected.size && selected.toSet()==expected.toSet()) {"SD $profile requires the complete exclusive ${expected.size}-port layout"}
        require(listOf("commandCount","completedCommands","transferCount","transferredBytes","mediaBytes","failedTransactions").all {(state[it] as? Number)?.toLong()==0L}) {"SD transaction admission requires untouched transactions"}
        require((state["initialSlots"] as? List<*>)?.size==2 && state["initialSlots"]==state["slots"]) {"SD transaction state differs from initial state"}
        val extended=profile in setOf(ADDRESS_PROFILE,VENDOR_PHY_PROFILE,UHS_PHY_PROFILE)
        val vendorPhy=profile in setOf(VENDOR_PHY_PROFILE,UHS_PHY_PROFILE)
        val uhsPhy=profile==UHS_PHY_PROFILE
        if(extended) {
            val initial=state["initialAddressHighBits"] as? Map<*,*> ?: error("SD initial address bits missing")
            require(initial.keys==setOf("0","1") && initial.values.all { it is Byte || it is Short || it is Int || it is Long })
            require(initial.values.all {(it as Number).toLong() in 0..1} && state["addressHighBits"]==initial) {"SD address bits differ from explicit initial state"}
            val slots=state["slots"] as List<Map<String,Any?>>
            for(slot in 0..1)require((slots[slot]["addressHighBit"] as? Number)?.toLong()==(initial[slot.toString()] as Number).toLong()) {"SD slot address bit differs"}
        }
        if(vendorPhy) {
            val initial=state["initialVendorPhyWords"] as? Map<*,*> ?: error("SD initial vendor/PHY words missing")
            require(initial.keys==setOf("0","1") && state["vendorPhyWords"]==initial) {"SD vendor/PHY words differ from explicit initial state"}
            for(slot in 0..1) {
                val row=initial[slot.toString()] as? Map<*,*> ?: error("SD vendor/PHY slot missing")
                require(row.keys==VENDOR_PHY_FIELDS.toSet()) {"SD vendor/PHY fields differ"}
                for(v in row.values) { require(v is Byte || v is Short || v is Int || v is Long); require((v as Number).toLong() in 0L..0xffffffffL) }
            }
        }
        if(uhsPhy) {
            val initial=state["initialUhsPhyWords"] as? Map<*,*> ?: error("SD initial UHS/PHY words missing")
            require(initial.keys==setOf("0","1") && state["uhsPhyWords"]==initial) {"SD UHS/PHY words differ from explicit initial state"}
            for(slot in 0..1) {
                val row=initial[slot.toString()] as? Map<*,*> ?: error("SD UHS/PHY slot missing")
                require(row.keys==UHS_PHY_FIELDS.toSet()) {"SD UHS/PHY fields differ"}
                for((k,v) in row) {
                    require(v is Byte || v is Short || v is Int || v is Long)
                    val maxVal=if(k in UHS_PHY_U16_FIELDS) 0xffffL else 0xffffffffL
                    require((v as Number).toLong() in 0L..maxVal)
                }
            }
        }
        for(key in listOf("commandTicks","blockTicks","resetTicks")) {val v=state[key];require(v is Int || v is Long);require((v as Number).toLong() in 1..1000000)}
        val legacy=state["legacyState"] as? Map<String,Any?> ?: error("SD transaction legacy projection missing")
        require(legacy["profile"]==SdInitContracts.PHY_PROFILE)
        // Verify the complete actual declaration first, then reuse unchanged legacy DAT/state checks.
        val old=SdInitContracts.windows()
        val report=SdInitContracts.verifyDeclared(image,legacy,old.map {it.port},old,old)
        val app=image.decode("section4")
        return report+mapOf("profile" to profile,"legacyProfile" to SdInitContracts.PHY_PROFILE,"transactionWindows" to expected.map {mapOf("port" to it.port,"address" to it.address,"size" to it.size)},"cardIdentityBasis" to "SYNTHETIC","physicalQualification" to false)+
            (if(extended)mapOf("addressExtension" to observeAddressExtension(app)) else emptyMap())+
            (if(vendorPhy)mapOf("vendorPhy" to observeVendorPhy(app)) else emptyMap())+
            (if(uhsPhy)mapOf("uhsPhy" to observeUhsPhy(app)) else emptyMap())
    }
}
