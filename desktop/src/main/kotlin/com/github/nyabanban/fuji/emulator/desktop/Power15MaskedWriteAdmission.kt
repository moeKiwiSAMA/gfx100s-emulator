package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.firmware.PowerMaskedRegisterContracts
import com.github.nyabanban.fuji.emulator.firmware.PowerRetainedContracts

/** Project only the separately qualified15 hypothesis out of the unchanged old masked/block input. */
internal object Power15MaskedWriteAdmission {
    const val KEY="power15MaskedWrite"
    data class Admission(val legacyInput:Map<*,*>,val contract:Map<String,Any>)
    private fun integer(value:Any?):Long {
        require(value is Byte || value is Short || value is Int || value is Long) {"Masked15 requires exact integers"}
        return (value as Number).toLong()
    }
    fun qualify(app:()->ByteArray,state:Map<String,Any?>,maskedInput:Map<*,*>):Admission? {
        // Older profiles retain their original exact DAT sub-contract and refusal path.
        if(state["profile"]!=PowerFiniteReadAdmission.PROFILE)return null
        val raw=state["inputs"] as? Map<*,*> ?: error("Masked15 live input missing")
        require(raw["profile"]==PowerFiniteReadAdmission.PROFILE)
        require(maskedInput==raw.filterKeys {it!="candidate_reads"}+mapOf("profile" to PowerMaskedRegisterContracts.PROFILE)) {"Masked15 requires the complete admitted finite input projection"}
        val words=maskedInput["inputs"] as? Map<*,*> ?: error("Masked15 explicit words missing")
        val masks=maskedInput["write_masks"] as? Map<*,*> ?: error("Masked15 explicit masks missing")
        val retained=maskedInput["retained_registers"] as? List<*> ?: error("Masked15 retained ownership missing")
        val disabled=maskedInput["disable_writes"] as? List<*> ?: error("Masked15 disabled ownership missing")
        val candidates=raw["candidate_reads"] as? List<*> ?: error("Masked15 finite candidates missing")
        val owns15=words.containsKey("15") || masks.containsKey("15") || (retained+disabled).any {integer(it)==15L} || candidates.any {integer((it as Map<*,*>)["selector"])==15L}
        if(!owns15)return null
        require(integer(words["15"])==0L) {"Masked15 requires explicit HYP initial0"}
        val mask15=integer(masks["15"])
        val liveBattery=(state["batteryPort"] as? String)?.isNotEmpty()==true
        require(mask15==0xc000L || (liveBattery && mask15==0xc001L)) {"Masked15 requires exact HYP maskC000"}
        require((retained+disabled).none {integer(it)==15L} && candidates.none {integer((it as Map<*,*>)["selector"])==15L}) {"Masked15 ownership overlaps another declared law"}
        PowerRetainedContracts.observe(app())
        val legacy=maskedInput+mapOf("inputs" to words.filterKeys {it!="15"},"write_masks" to masks.filterKeys {it!="15"})
        val contract=linkedMapOf<String,Any>("sourceProfile" to PowerFiniteReadAdmission.PROFILE,"legacyProfile" to PowerMaskedRegisterContracts.PROFILE,
            "selector" to 15,"selectedInitialWord" to 0,"selectedWriteMask" to mask15.toInt(),"unchangedOutsideSelectedMask" to true,"confidence" to "HYPOTHESIS",
            "qualifiedCaller" to 0x12edb0cL,"qualifiedWriter" to 0x120c0a4L,"qualifiedFormatter" to 0x120bfe8L,
            "controlledArgumentBits" to listOf(0,14,15),"selectedMaskArgumentBits" to if(mask15==0xc001L)listOf(0,14,15)else listOf(14,15),
            "legacyProjectionRemovedOnly" to mapOf("inputs" to listOf("15"),"write_masks" to listOf("15")),
            "liveInputUnchanged" to true,"physicalMask" to "UNKNOWN","physicalReset" to "UNKNOWN","physicalMeaning" to "UNKNOWN",
            "modelLaw" to "HYP explicit initial0 with checksum-committed masked retention C000 only; no new ACK, ready, IRQ, DMA, display coupling or persistence. Full existing v3 binding precedes this qualification; the unchanged register2/block contracts independently validate only their exact legacy input copy.")
        return Admission(legacy,contract)
    }
}
