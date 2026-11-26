package com.github.nyabanban.fuji.emulator.desktop

internal object VglibModeCase4Dat {
    private val caller = mapOf(
        0x01255734 to listOf(0xe3a00c97L, 0xe34f0ff4L, 0xebb81625L, 0xe1a01000L, 0xe3560004L, 0x979ff106L),
        0x01255760 to listOf(0x0125587cL),
        0x0125587c to listOf(0xe7c9149fL, 0xe7c8141fL, 0xe3043140L, 0xe340318aL, 0xe3a02004L, 0xe5c3200cL, 0xeaffffcdL),
        0x012557d0 to listOf(0xe3a00c97L, 0xe34f0ff4L, 0xebb815ecL),
    )
    private val helpers = mapOf(
        0x0005afd8 to listOf(0xe52db004L, 0xe28db000L, 0xe5900000L, 0xe28bd000L, 0xe49db004L, 0xe12fff1eL),
        0x0005af90 to listOf(0xe52db004L, 0xe28db000L, 0xe5801000L, 0xe28bd000L, 0xe49db004L, 0xe12fff1eL),
    )

    fun verify(appBase:Long, app:ByteArray, kernelBase:Long, kernel:ByteArray) {
        require(appBase == 0x01021000L && kernelBase == 0x40000L) { "Mode word case4 DAT base differs" }
        verifyVglibWords("Mode word case4", appBase, app, caller)
        verifyVglibWords("Mode word case4", kernelBase, kernel, helpers)
    }
}
