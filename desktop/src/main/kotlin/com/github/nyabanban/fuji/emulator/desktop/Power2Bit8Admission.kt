package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.firmware.PowerMaskedRegisterContracts
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object Power2Bit8Admission {
    const val PROFILE="masked-bit8-retained-experimental-v1"
    const val KEY="powerRegister2Bit8"
    const val CONFIG_KEY="powerRegister2Bit8Profile"
    const val WRITE_MASK=0x0104L
    private const val APP=0x01021000L
    private val precedingSelectors=setOf("15","37","38")
    private val code=listOf(
        0x0120c734L to listOf(0xe3083f74L,0xe3403187L,0xe1d310b4L,0xe3a00002L,0xebfffe56L),
        0x0120c8a4L to listOf(0xe3083f74L,0xe3403187L,0xe5d32005L,0xe3822001L,0xe5c32005L,
            0xe3093014L,0xe3403187L,0xe3a02001L,0xe5c32020L,0xeaffff99L)
    )
    val wordAddresses=code.flatMap { (start,words)->words.indices.map { start+it*4L } }
    data class Result(val legacyInput:Map<*,*>,val contract:Map<String,Any>)

    private fun integer(value:Any?):Long {
        require(value is Byte || value is Short || value is Int || value is Long) {"Power2 bit8 requires exact integers"}
        return (value as Number).toLong()
    }

    fun qualify(section4:()->ByteArray,peer:Map<String,Any?>,maskedInput:Map<*,*>,selected:Boolean,legacyInput:Map<*,*>):Result? {
        val raw=peer["inputs"] as? Map<*,*>
        val masks=maskedInput["write_masks"] as? Map<*,*>
        if(!selected) {
            val liveMask=(raw?.get("write_masks") as? Map<*,*>)?.get("2")
            for(mask in listOfNotNull(masks?.get("2"),liveMask,(legacyInput["write_masks"] as? Map<*,*>)?.get("2")))
                require(integer(mask) and 0x0100L==0L) {"Power2 bit8 requires explicit selection"}
            return null
        }
        require(peer["enabled"]==true && peer["profile"]==PowerFiniteReadAdmission.PROFILE &&
            raw!=null && raw["profile"]==PowerFiniteReadAdmission.PROFILE) {"Power2 bit8 requires the enabled finite peer"}
        require(maskedInput==raw.filterKeys { it!="candidate_reads" }+mapOf("profile" to PowerMaskedRegisterContracts.PROFILE)) {
            "Power2 bit8 requires the complete admitted finite projection"
        }
        val words=maskedInput["inputs"] as? Map<*,*> ?: throw IllegalArgumentException("Power2 bit8 explicit words missing")
        require(masks!=null && integer(masks["2"])==WRITE_MASK) {"Power2 bit8 requires exact mask0104"}
        require(integer(words["2"])==0L) {"Power2 bit8 requires explicit HYP initial0"}
        val retained=maskedInput["retained_registers"] as? List<*> ?: throw IllegalArgumentException("Power2 bit8 retained ownership missing")
        val disabled=maskedInput["disable_writes"] as? List<*> ?: throw IllegalArgumentException("Power2 bit8 disabled ownership missing")
        val candidates=raw["candidate_reads"] as? List<*> ?: throw IllegalArgumentException("Power2 bit8 finite declaration missing")
        require((retained+disabled).none { integer(it)==2L } && candidates.none {
            val candidate=it as? Map<*,*> ?: throw IllegalArgumentException("Power2 bit8 malformed finite declaration")
            integer(candidate["selector"])==2L
        }) {"Power2 bit8 ownership overlaps another declared law"}
        val expectedLegacy=maskedInput+mapOf("inputs" to words.filterKeys { it !in precedingSelectors },
            "write_masks" to masks.filterKeys { it !in precedingSelectors })
        require(legacyInput==expectedLegacy) {"Power2 bit8 requires the exact preceding15/37/38 projection"}
        val app=section4()
        val bytes=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
        code.forEach { (start,pins)->pins.forEachIndexed { index,expected ->
            val address=start+index*4L
            val offset=address-APP
            require(offset>=0 && offset<=app.size.toLong()-4) {"Power2 bit8 DAT truncated at "+address.toString(16)}
            require(bytes.getInt(offset.toInt()).toLong() and 0xffffffffL==expected) {"Power2 bit8 DAT word differs at "+address.toString(16)}
        } }
        val projected=legacyInput+mapOf("write_masks" to (masks.filterKeys { it !in precedingSelectors }+("2" to 4)))
        val contract=linkedMapOf<String,Any>("profile" to PROFILE,"sourceProfile" to PowerFiniteReadAdmission.PROFILE,
            "legacyProfile" to PowerMaskedRegisterContracts.PROFILE,"selector" to 2,"selectedInitialWord" to 0,
            "selectedWriteMask" to WRITE_MASK,"legacyWriteMask" to 4,"addedMask" to 0x0100,
            "confidence" to "HYPOTHESIS","unchangedOutsideSelectedMask" to true,
            "shadowHalfwordVirtual" to 0x01878f78L,"producerByteVirtual" to 0x01878f79L,
            "qualifiedBitSet" to 0x0120c8b0L,"qualifiedWriteCall" to 0x0120c744L,"qualifiedWriter" to 0x0120c0a4L,
            "completePinnedWords" to code.flatMap { (start,pins)->pins.mapIndexed { index,word->mapOf("pc" to start+index*4L,"word" to word) } },
            "pinnedBytes" to 60,"wholeFunctionQualified" to false,
            "legacyProjectionChangedOnly" to mapOf("write_masks" to mapOf("2" to mapOf("from" to WRITE_MASK,"to" to 4))),
            "precedingProjectionSelectors" to precedingSelectors.toList(),"liveInputUnchanged" to true,
            "physicalMask" to "UNKNOWN","physicalReset" to "UNKNOWN","physicalMeaning" to "UNKNOWN",
            "sourceEvidence" to "evidence/refactor/a300-second-input-957/power-write-review.generated.json",
            "modelLaw" to "Explicit HYP initial0 and existing checksum-committed masked retention0104 at selector2. The two complete local DAT windows set shadow high-byte bit0 and submit the halfword; other producer paths and physical effects are not qualified. The preceding15/37/38 admissions must validate the full live input before this exact projection; unchanged register2/block contracts must then verify the returned legacy input. No ACK, ready, IRQ, DMA, display coupling or persistence is supplied.")
        return Result(projected,contract)
    }
}
