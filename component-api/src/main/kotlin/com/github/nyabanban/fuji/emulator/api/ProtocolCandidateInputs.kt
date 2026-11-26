package com.github.nyabanban.fuji.emulator.api

/** Pure experimental-input identity. It does not grant a device binding or CPU address permission. */
data class ProtocolCandidateReadContract(
    val spaceId: String,
    val requestId: String,
    val selector: Int,
    val wordBits: Int,
    val direction: String,
    val strategy: String,
    val confidence: String,
)

/** Owner-thread startup metadata. Describing these inputs never samples or consumes one. */
interface DescribedProtocolCandidateInputs {
    fun candidateReadContracts(): List<ProtocolCandidateReadContract>
}
