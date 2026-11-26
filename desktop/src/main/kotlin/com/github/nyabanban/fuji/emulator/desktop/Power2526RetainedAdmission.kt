package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.firmware.PowerMaskedRegisterContracts
import com.github.nyabanban.fuji.emulator.firmware.PowerRetainedContracts

/** A selected retained-word hypothesis replaces, rather than overlaps, the two finite read inputs. */
internal object Power2526RetainedAdmission {
    const val KEY="power2526Retained"
    data class Admission(val legacyInput:Map<*,*>,val contract:Map<String,Any>)
    private val selectors=listOf(37,38)
    private fun integer(value:Any?):Long {
        require(value is Byte || value is Short || value is Int || value is Long) {"Retained37/38 requires exact integers"}
        return (value as Number).toLong()
    }
    fun qualify(app:()->ByteArray,state:Map<String,Any?>,maskedInput:Map<*,*>,admission15:Power15MaskedWriteAdmission.Admission?):Admission? {
        if(state["profile"]!=PowerFiniteReadAdmission.PROFILE)return null
        val raw=state["inputs"] as? Map<*,*> ?: error("Retained37/38 live input missing")
        require(raw["profile"]==PowerFiniteReadAdmission.PROFILE)
        require(maskedInput==raw.filterKeys {it!="candidate_reads"}+mapOf("profile" to PowerMaskedRegisterContracts.PROFILE)) {"Retained37/38 requires the complete admitted finite projection"}
        val words=maskedInput["inputs"] as? Map<*,*> ?: error("Retained37/38 explicit words missing")
        val masks=maskedInput["write_masks"] as? Map<*,*> ?: error("Retained37/38 explicit masks missing")
        val retained=maskedInput["retained_registers"] as? List<*> ?: error("Retained37/38 retained ownership missing")
        val disabled=maskedInput["disable_writes"] as? List<*> ?: error("Retained37/38 disabled ownership missing")
        val candidates=raw["candidate_reads"] as? List<*> ?: error("Retained37/38 finite declaration missing")
        // The previous finite-only37/38 input retains its old path without loading a new DAT contract.
        if(selectors.none {it.toString() in words || it.toString() in masks || (retained+disabled).any {v->integer(v)==it.toLong()}})return null
        require(selectors.all {integer(words[it.toString()])==0L}) {"Retained37/38 requires explicit HYP initial zeros"}
        require(integer(masks["37"])==65535L && integer(masks["38"])==255L) {"Retained37/38 requires exact HYP masksFFFF/00FF"}
        require((retained+disabled).none {integer(it) in selectors.map(Int::toLong)} && candidates.none {integer((it as Map<*,*>)["selector"]) in selectors.map(Int::toLong)}) {"Retained37/38 ownership overlaps another declared law"}
        val liveBattery=(state["batteryPort"] as? String)?.isNotEmpty()==true
        val mask15=integer(masks["15"])
        require(admission15!=null && integer(words["15"])==0L && (mask15==49152L || (liveBattery && mask15==49153L))) {"Retained37/38 requires the preceding qualified15 hypothesis"}
        val after15=maskedInput+mapOf("inputs" to words.filterKeys {it!="15"},"write_masks" to masks.filterKeys {it!="15"})
        require(admission15.legacyInput==after15) {"Retained37/38 received an unexpected legacy15 projection"}
        PowerRetainedContracts.observe(app())
        val oldWords=after15["inputs"] as Map<*,*>;val oldMasks=after15["write_masks"] as Map<*,*>
        val legacy=after15+mapOf("inputs" to oldWords.filterKeys {it !in setOf("37","38")},"write_masks" to oldMasks.filterKeys {it !in setOf("37","38")})
        val promoted=listOf(mapOf("selector" to 37,"initial" to 0,"writeMask" to 65535,"confidence" to "HYPOTHESIS"),mapOf("selector" to 38,"initial" to 0,"writeMask" to 255,"confidence" to "HYPOTHESIS"))
        val contract=linkedMapOf<String,Any>("sourceProfile" to PowerFiniteReadAdmission.PROFILE,"legacyProfile" to PowerMaskedRegisterContracts.PROFILE,
            "promotedRetainedInputs" to promoted,"finiteReadSelectorsRemoved" to selectors,"finiteReadSemanticsChanged" to true,"confidence" to "HYPOTHESIS",
            "qualifiedConstructor" to 0x124c488L,"qualifiedWrite37Call" to 0x124c640L,
            "legacyProjectionRemovedOnly" to mapOf("inputs" to listOf("37","38"),"write_masks" to listOf("37","38")),
            "preceding15ContractPreserved" to true,"liveInputUnchanged" to true,"physicalMask" to "UNKNOWN","physicalReset" to "UNKNOWN","physicalMeaning" to "UNKNOWN",
            "modelLaw" to "HYP explicit initial zeros with existing checksum-committed masked retention FFFF at37 and00FF at38. Replaces both finite single-zero reads; later reads retain the written model word, not repeat-last. The low8 software argument at38 does not identify physical reserved bits or helper output. No W1C, ACK, ready, IRQ, DMA, display coupling or persistence is established. The qualified15 legacy copy precedes the separate37/38 projection; original masked/block contracts still verify their complete exact input.")
        return Admission(legacy,contract)
    }
}
