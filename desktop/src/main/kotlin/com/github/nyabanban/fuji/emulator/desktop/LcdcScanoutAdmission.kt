package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata

internal object LcdcScanoutAdmission {
    private val declaration = mapOf(
        "setting" to "firstScanout", "profile" to "lcdc1-osd0-main-640x480-rgb565le-v1",
        "confidence" to "HYPOTHESIS", "instance" to 1, "upperOffset" to 64, "lowOffset" to 68,
        "upperMask" to 3, "width" to 640, "height" to 480, "strideBytes" to 1280,
        "format" to "RGB565LE", "memory" to "cpu.memory", "output" to "display.main",
    )
    private val budget = mapOf("setting" to "scanoutFrameLimit", "default" to 1, "maximum" to 256,
        "unit" to "successful frame publications", "exhaustion" to "retain MMIO words and skip pixel reads")
    private val rgba = mapOf(
        "setting" to "firstScanout", "profile" to "lcdc1-osd0-1024x768-rgba-nearest-v1",
        "confidence" to "HYPOTHESIS", "instance" to 1, "upperOffset" to 64, "lowOffset" to 68,
        "commitOffset" to 96, "control" to 0x10400, "origin" to 0, "unitScale" to 0x10001000,
        "width" to 1024, "height" to 768, "strideBytes" to 4096, "format" to "LE-u32-RRGGBBAA",
        "scaling" to "center-nearest-640x480-straight-ARGB", "memory" to "cpu.memory", "output" to "display.main",
    )
    private val composite = mapOf(
        "setting" to "firstScanout", "profile" to "lcdc1-pic-yuv-osd0-nearest-v1",
        "confidence" to "HYPOTHESIS", "instance" to 1, "enableOffset" to 8, "commitOffset" to 1536,
        "osd0" to mapOf("controlOffset" to 48, "upperOffset" to 64, "lowOffset" to 68, "format" to "LE-u32-RRGGBBAA", "strideBytes" to 4096, "width" to 1024, "height" to 768, "unitScale" to 0x10001000),
        "pic1" to mapOf("alphaOffset" to 352, "upperOffset" to 356, "lowOffset" to 360, "strideOffset" to 364, "outExtentOffset" to 368, "positionOffset" to 372, "sizeOffset" to 376, "scaleOffset" to 380, "format" to "YUV422-16bpp-single-plane", "csc" to "BT.601-full-range", "unitScale" to 0x10001000),
        "scaling" to "center-nearest-640x480-straight-ARGB", "memory" to "cpu.memory", "output" to "display.main",
    )

    fun verify(catalog: AddressCatalog, meta: FirmwareMetadata, state: Map<String, Any?>,
               app: ByteArray, kernel: ByteArray): Map<String, Any> {
        require(state["profile"] == VglibInitAdmission.FUNCTIONAL_PROFILE)
        val (key, selected) = when (state["firstScanout"]) {
            declaration["profile"] -> "optionalFirstScanout" to declaration
            rgba["profile"] -> "optionalRgbaScanout" to rgba
            composite["profile"] -> "optionalCompositeScanout" to composite
            else -> throw IllegalArgumentException("Unknown LCD scanout profile")
        }
        val bounded = "scanoutFrameLimit" in state
        val continuous = state["continuousScanout"] == true
        val fresh = mapOf("upper" to null, "publishedAddress" to null, "confidence" to "HYPOTHESIS") +
            (if (selected == rgba) mapOf("pendingSlot" to false) else emptyMap()) +
            (if (continuous) mapOf("outputActive" to false) else emptyMap())
        require(state["scanout"] == if (bounded) fresh + ("publishedFrames" to 0) else fresh) { "LCD scanout requires a fresh cursor" }
        val bank = catalog.windowsFor(mapOf(VglibInitAdmission.ID to VglibInitAdmission.FUNCTIONAL_PROFILE))
            .single { it.portId == VglibInitAdmission.FUNCTIONAL_PORTS[1] }
        val backing = catalog.byId(bank.definitionId)!!.getValue("backing") as Map<*, *>
        require(bank.base == 0xfffa9000L && bank.size == 0x1000L && backing[key] == selected) {
            "LCD scanout bank or hypothesis declaration differs"
        }
        require(meta.sections.single { it.name == "section4" }.address == 0x01021000L &&
            meta.sections.single { it.name == "kernel" }.address == 0x40000L)
        require(app.isNotEmpty())
        verifyVglibWords("LCD scanout helper", 0x40000L, kernel, mapOf(
            0x5af90 to listOf(0xe52db004L, 0xe28db000L, 0xe5801000L, 0xe28bd000L, 0xe49db004L, 0xe12fff1eL),
        ))
        if (!bounded) return selected
        val limit = state.getValue("scanoutFrameLimit")
        require((limit is Int || limit is Long) && (limit as Number).toLong() in 2..256 &&
            backing["optionalScanoutBudget"] == budget) { "LCD scanout frame limit or declaration differs" }
        return selected + ("scanoutFrameLimit" to limit) + ("exhaustion" to budget.getValue("exhaustion"))
    }

    fun verifyEvf(catalog: AddressCatalog, meta: FirmwareMetadata, state: Map<String, Any?>,
                  app: ByteArray, kernel: ByteArray): Map<String, Any> {
        require(state["profile"] == VglibInitAdmission.FUNCTIONAL_PROFILE)
        val (key, selected) = when (state["evfScanout"]) {
            declaration["profile"] -> "optionalEvfFirstScanout" to (declaration + mapOf("setting" to "evfScanout", "instance" to 0, "output" to "display.evf"))
            rgba["profile"] -> "optionalEvfRgbaScanout" to (rgba + mapOf("setting" to "evfScanout", "instance" to 0, "output" to "display.evf"))
            composite["profile"] -> "optionalEvfCompositeScanout" to (composite + mapOf("setting" to "evfScanout", "instance" to 0, "output" to "display.evf"))
            else -> throw IllegalArgumentException("Unknown EVF LCD scanout profile")
        }
        val bounded = "evfScanoutFrameLimit" in state
        val continuous = state["continuousScanout"] == true
        val fresh = mapOf("upper" to null, "publishedAddress" to null, "confidence" to "HYPOTHESIS") +
            (if (selected["profile"] == rgba["profile"]) mapOf("pendingSlot" to false) else emptyMap()) +
            (if (continuous) mapOf("outputActive" to false) else emptyMap())
        require(state["evfScanoutState"] == if (bounded) fresh + ("publishedFrames" to 0) else fresh) { "EVF LCD scanout requires a fresh cursor" }
        val bank = catalog.windowsFor(mapOf(VglibInitAdmission.ID to VglibInitAdmission.FUNCTIONAL_PROFILE))
            .single { it.portId == VglibInitAdmission.FUNCTIONAL_PORTS[0] }
        val backing = catalog.byId(bank.definitionId)!!.getValue("backing") as Map<*, *>
        require(bank.base == 0xfff7a000L && bank.size == 0x1000L && backing[key] == selected) {
            "EVF LCD scanout bank or hypothesis declaration differs"
        }
        require(meta.sections.single { it.name == "section4" }.address == 0x01021000L &&
            meta.sections.single { it.name == "kernel" }.address == 0x40000L)
        require(app.isNotEmpty())
        verifyVglibWords("LCD scanout helper", 0x40000L, kernel, mapOf(
            0x5af90 to listOf(0xe52db004L, 0xe28db000L, 0xe5801000L, 0xe28bd000L, 0xe49db004L, 0xe12fff1eL),
        ))
        if (!bounded) return selected
        val limit = state.getValue("evfScanoutFrameLimit")
        val evfBudget = budget + mapOf("setting" to "evfScanoutFrameLimit", "unit" to "successful EVF frame publications")
        require((limit is Int || limit is Long) && (limit as Number).toLong() in 2..256 &&
            backing["optionalEvfScanoutBudget"] == evfBudget) { "EVF LCD scanout frame limit or declaration differs" }
        return selected + ("evfScanoutFrameLimit" to limit) + ("exhaustion" to evfBudget.getValue("exhaustion"))
    }
}
