package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.firmware.PowerMaskedRegisterContracts

/** Select the unchanged masked-write sub-contract only after finite-input identity admission. */
internal object PowerFiniteReadAdmission {
    const val PROFILE="finite-candidate-reads-experimental-v3"
    fun maskedInput(state:Map<String,Any?>,boundProfile:String?,admitted:List<Map<String,Any>>):Map<*,*>? {
        val profile=state["profile"]
        if(profile !in setOf(PowerMaskedRegisterContracts.PROFILE,PROFILE))return null
        val input=state["inputs"] as? Map<*,*> ?: throw IllegalArgumentException("Masked power input missing")
        require(input["profile"]==profile) {"Power peer state/input profile differs"}
        if(profile==PowerMaskedRegisterContracts.PROFILE)return input
        require(state["enabled"]==true && boundProfile==PROFILE) {"Finite power input requires validated provider binding"}
        require(input.keys==setOf("profile","chip_select","inputs","retained_registers","disable_writes","write_masks","candidate_reads"))
        val reads=input["candidate_reads"] as? List<*> ?: throw IllegalArgumentException("Finite power input declaration missing")
        require(reads.size==admitted.size && reads.size<=128)
        val expectedKeys=setOf("spaceId","requestId","selector","wordBits","confidence","values")
        val comparable=setOf("spaceId","requestId","selector","wordBits","confidence")
        val declarations=reads.map {raw ->
            val row=raw as? Map<*,*> ?: throw IllegalArgumentException("Invalid finite power declaration")
            require(row.keys==expectedKeys)
            val values=row["values"] as? List<*> ?: throw IllegalArgumentException("Finite samples missing")
            val batteryInput = row["spaceId"] == "gfx100s-power-protocol-requests" &&
                row["requestId"] == "request.power-peer.selector0x03" && row["selector"] == 3
            val limit = if (batteryInput) 320 else 64
            require(values.size in 1..limit) { "candidate sequence must contain 1..$limit raw16 samples" }
            require(values.all {v -> (v is Byte || v is Short || v is Int || v is Long) && (v as Number).toLong() in 0..65535L})
            row.filterKeys {it in comparable}+mapOf("direction" to "read","strategy" to "scripted-read")
        }
        val contracts=admitted.map {it.filterKeys {key->key in comparable || key in setOf("direction","strategy")}}
        require(declarations.distinct().size==declarations.size && declarations.toSet()==contracts.toSet()) {"Finite input declarations differ from admitted candidate contracts"}
        // This projection is passed only to existing DAT masked/block verification. The live v3 device,
        // original configuration, finite sequences and observer report retain their actual identity.
        return input.filterKeys {it!="candidate_reads"}+mapOf("profile" to PowerMaskedRegisterContracts.PROFILE)
    }
}
