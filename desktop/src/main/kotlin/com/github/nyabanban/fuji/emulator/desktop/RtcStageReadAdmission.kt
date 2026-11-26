package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.firmware.RtcContracts
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Admission of a finite host hypothesis; the unchanged RTC DAT contract is still required separately. */
internal object RtcStageReadAdmission {
    const val PROFILE="gfx100s-rtc-scripted-stage-read-experimental-v2"
    private const val BASE=0x1021000L
    private val words=linkedMapOf(0x120c278L to 0xe3a0001dL,
        0x120c27cL to (0xeb000000L or (((0x120be60L-0x120c27cL-8)/4) and 0xffffffL)),
        0x120c298L to 0xe3a0001eL,
        0x120c29cL to (0xeb000000L or (((0x120be60L-0x120c29cL-8)/4) and 0xffffffL)))

    fun selected(firmwareId:String,state:Map<String,Any?>,boundProfile:String?):Boolean {
        require(firmwareId==RtcContracts.FIRMWARE_ID && state["profile"] in setOf(RtcContracts.PROFILE,PROFILE)) {
            "RTC profile is not qualified for selected firmware version"}
        if(state["profile"]==RtcContracts.PROFILE)return false
        require(boundProfile==PROFILE) {"RTC scripted profile requires its validated active logical endpoint"}
        val inputs=state["inputs"] as? Map<*,*> ?: error("RTC scripted input state missing")
        val schema=inputs["schema"]
        require((schema is Byte || schema is Short || schema is Int || schema is Long) && (schema as Number).toLong()==2L && inputs["profile"]==PROFILE) {"RTC scripted admission requires schema2 input"}
        val sequences=inputs["stageReadInputs"] as? Map<*,*> ?: throw IllegalArgumentException("RTC stage inputs missing")
        require(sequences.keys==setOf("29","30"))
        for(value in sequences.values) {
            val samples=value as? List<*> ?: throw IllegalArgumentException("RTC stage input requires a finite list")
            require(samples.size in 1..64)
            require(samples.all {sample -> (sample is Byte || sample is Short || sample is Int || sample is Long) && (sample as Number).toLong() in 0..65535L})
        }
        return true
    }
    fun observe(app:ByteArray):Map<String,Any> {
        for((pc,expected) in words) {
            val at=pc-BASE;require(at>=0 && at<=app.size-4L)
            val actual=ByteBuffer.wrap(app,Math.toIntExact(at),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            require(actual==expected) {"RTC staged-read DAT consumer differs at "+pc.toString(16)}
        }
        return mapOf("profile" to PROFILE,"criticalWords" to words.size,"wordAddresses" to words.keys.toList(),
            "confidence" to "HYPOTHESIS","wordBits" to 16,"selectors" to listOf(29,30),"sampleLimitPerSelector" to 64,
            "basis" to "Selected DAT literal selectors and exact calls to120BE60 plus owner-bound logical profile. Finite raw16 scripted replies are experimental inputs; no physical reset/time/calendar/ready qualification. Existing76-word RTC consumers and endpoint claims are checked separately and remain unchanged.")
    }
}
