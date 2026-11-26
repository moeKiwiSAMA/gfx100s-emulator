package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class StatusA200CandidateTest {
    private companion object {
        // Last qualified P951 delta before Linux memory profiles and new peripherals were added.
        const val PROMOTED = "d1b23b547fdc8a55e47cb6ce5a043a1c04af1da2"
    }

    private val root = Path.of(System.getProperty("catalogRoot"))
    private val windowId = "gfx100s.soc-system-control.status-a200"
    private val definition = "definition.soc-system-control.status-a200"
    private val dmaPromotedWindowIds = listOf(
        "gfx100s.soc-dma.ch7-enable",
        "gfx100s.soc-dma.ch4-control",
        "gfx100s.soc-dma.ch7-control",
        "gfx100s.soc-dma.ch11-control",
        "gfx100s.soc-dma.ch4-params",
        "gfx100s.soc-dma.ch7-params",
        "gfx100s.soc-dma.ch11-params",
    )
    private val promotedWindowIds = setOf(
        windowId,
        "gfx100s.soc-system-control.fff49530-pair",
        "gfx100s.soc-system-control.fff49500-tail",
        "gfx100s.soc-hdmi",
        "gfx100s.soc-panel",
        "gfx100s.soc-uart.uart-bank",
        "gfx100s.soc-uart.uart-config",
        "gfx100s.soc-uart.uart-tail",
        "gfx100s.soc-isp-cmd.c000-words",
        "gfx100s.soc-isp-cmd.c204-control",
        "gfx100s.soc-isp-cmd.c20c-config",
        "gfx100s.soc-isp-cmd.reply",
        "gfx100s.soc-isp-cmd.config-bank-0",
        "gfx100s.soc-isp-cmd.config-bank-1",
        "gfx100s.soc-isp-cmd.config-bank-2",
        "gfx100s.soc-isp-cmd.config-bank-3",
        "gfx100s.soc-isp-cmd.config-bank-4",
        "gfx100s.soc-isp-cmd.config-bank-5",
        "gfx100s.soc-isp-cmd.config-bank-6",
        "gfx100s.soc-isp-setup.ctrl0-low",
        "gfx100s.soc-isp-setup.ctrl0-high",
        "gfx100s.soc-isp-setup.ctrl1-low",
        "gfx100s.soc-isp-setup.ctrl1-poll",
        "gfx100s.soc-isp-setup.ctrl1-high",
        "gfx100s.soc-isp.core",
        "gfx100s.soc-isp.bank-50000",
        "gfx100s.soc-isp.lut-51000",
        "gfx100s.soc-isp.lut-55000",
        "gfx100s.soc-isp.lut-59000",
        "gfx100s.soc-isp.bank-68000",
        "gfx100s.soc-isp.config-69000",
        "gfx100s.soc-isp.config-69100",
        "gfx100s.soc-isp.config-6b000",
        "gfx100s.soc-isp.lut-6d000",
        "gfx100s.soc-isp.bank-6e000",
        "gfx100s.soc-isp.result-surface",
        "gfx100s.soc-isp.stats-0",
        "gfx100s.soc-isp.stats-1",
        "gfx100s.soc-isp.live-view-0",
        "gfx100s.soc-isp.live-view-1",
        "gfx100s.soc-isp.live-view-2",
    ) + dmaPromotedWindowIds

    @Test fun qualifiedPhysicalDataAndAllDescriptorsPreserveP951ExceptForTheDeclaredAdditions() {
        val history = HistoricalCatalogFixture(root)
        val catalog = history.catalog(PROMOTED)
        val baseline = "8f7c5c6bd4026fa1ca15b8f8943bfc0523b8e04f"
        fun git(path: String, revision: String = baseline) = history.bytes(revision, path)
        val old = CatalogIO.readCompiled(git("catalog/compiled/catalog.json"))
        val published = CatalogIO.readCompiled(git("catalog/compiled/catalog.json", "834406aae7c846644b8a06297bb753d10bed1f38"))
        for (key in listOf("logicalSpaces", "mappings", "imageDomains", "legacyCatalogs")) {
            assertContentEquals(CatalogIO.canonical(old.data.getValue(key)),
                CatalogIO.canonical(catalog.data.getValue(key)), key)
        }
        val memoryAdditions = listOf("xlog-first-buffer-experimental-v22", "xlog-all-buffers-experimental-v23",
            "flashload-request-buffer-experimental-v24", "method4-runtime-output-experimental-v25",
            "method4-elfloader-exec-experimental-v26")
        fun expectedProfiles(previous: Any?, released: Any?) =
            obj(previous) + memoryAdditions.associateWith { obj(released).getValue(it) }
        val profiles = expectedProfiles(old.data["memoryProfiles"], published.data["memoryProfiles"])
        assertEquals(profiles, catalog.data["memoryProfiles"])
        val previousFirmware = old.legacyFirmwareMetadata()
        val firmwareProfiles = expectedProfiles(previousFirmware["memoryProfiles"], published.legacyFirmwareMetadata()["memoryProfiles"])
        val actualFirmware = catalog.legacyFirmwareMetadata()
        @Suppress("UNCHECKED_CAST")
        val actualDevices = actualFirmware.getValue("devices") as List<Map<String, Any?>>
        val jpegEntry = actualDevices.single { it["id"] == "soc-jpeg" }
        assertEquals(mapOf("id" to "soc-jpeg", "address" to "0xfffa1000", "size" to "0x12c", "port" to "soc-jpeg.registers", "optional" to true), jpegEntry)
        assertEquals(
            previousFirmware + ("memoryProfiles" to firmwareProfiles) + ("devices" to actualDevices),
            actualFirmware,
        )

        val registersId = "gfx100s.soc-vglib-init.registers"
        val modeWordId = "gfx100s.soc-vglib-init.mode-word"
        fun publishedBacking(revision: String, window: String): Map<String, Any?> {
            val release = CatalogIO.readCompiled(git("catalog/compiled/catalog.json", revision))
            return obj(rows(release.data["windows"]).single { it["id"] == window }["backing"])
        }
        val irqSuccessors = obj(publishedBacking("1eee848b202dc4b344820940736ca5480b51ce15", registersId).getValue("irq92Successors")) + ("capacity" to 128)
        val modeWord = publishedBacking("de81139b8c4cd498af7dd05e70b5e55bd0e34146", modeWordId)
        val additionalPairs = listOf("additionalPairsProfile", "additionalPairsSource", "maxAdditionalPairs")
            .associateWith(modeWord::getValue)
        fun lcdBackings(revision: String): Map<String, Map<String, Any?>> {
            val release = CatalogIO.readCompiled(git("catalog/compiled/catalog.json", revision))
            val variant = rows(release.data["profileWindows"]).single { it["profile"] == "lcdc-functional-experimental-v1" }
            return rows(variant["windows"]).associate { it.text("id") to obj(it["backing"]) }
        }
        val clockBackings = lcdBackings("0cd36e3414e149001e7c2fc1a380905258a9f447")
        val m4LcdcBackings = lcdBackings("e6792ba148ac435ec3f02048d16a455a039085b2").mapValues { (_, backing) ->
            // cb53f8527a extended only the frame-sync followup queue on both controllers.
            val edge = obj(backing.getValue("optionalEdge"))
            val followups = obj(edge.getValue("frameSyncFollowups"))
            assertEquals(32L, valueLong(followups.getValue("maxAdditionalEdges")))
            backing + ("optionalEdge" to (edge + ("frameSyncFollowups" to (followups + ("maxAdditionalEdges" to 256)))))
        }
        val registersBacking = obj(rows(catalog.data["windows"]).single { it["id"] == registersId }["backing"])
        assertEquals(execution, obj(registersBacking["graphicsFillTail"])["execution"])
        val m4RegistersBacking = publishedBacking("e6792ba148ac435ec3f02048d16a455a039085b2", registersId)
        val releasedFields = mapOf(
            registersId to listOf("graphicsFillTail"),
            "gfx100s.soc-vglib-init.mode-word" to listOf("secondPairProfile", "secondPairLimit", "secondPairSource"),
            "gfx100s.soc-vglib-init.lcdc0-functional" to listOf("optionalScanoutBudget", "optionalFirstScanout", "optionalEdge"),
            "gfx100s.soc-vglib-init.lcdc1-functional" to listOf("optionalScanoutBudget", "optionalFirstScanout", "optionalEdge"),
        )
        val m4EvfBackings = mapOf(
            "optionalEvfScanoutBudget" to mapOf(
                "setting" to "evfScanoutFrameLimit", "default" to 1, "maximum" to 256,
                "unit" to "successful EVF frame publications", "exhaustion" to "retain MMIO words and skip pixel reads",
            ),
            "optionalEvfFirstScanout" to mapOf(
                "setting" to "evfScanout", "profile" to "lcdc1-osd0-main-640x480-rgb565le-v1",
                "confidence" to "HYPOTHESIS", "instance" to 0, "upperOffset" to 64, "lowOffset" to 68, "upperMask" to 3,
                "width" to 640, "height" to 480, "strideBytes" to 1280, "format" to "RGB565LE",
                "memory" to "cpu.memory", "output" to "display.evf",
            ),
            "optionalEvfRgbaScanout" to mapOf(
                "setting" to "evfScanout", "profile" to "lcdc1-osd0-1024x768-rgba-nearest-v1",
                "confidence" to "HYPOTHESIS", "instance" to 0, "upperOffset" to 64, "lowOffset" to 68,
                "commitOffset" to 96, "control" to 66560, "origin" to 0, "unitScale" to 268439552,
                "width" to 1024, "height" to 768, "strideBytes" to 4096,
                "format" to "LE-u32-RRGGBBAA", "scaling" to "center-nearest-640x480-straight-ARGB",
                "memory" to "cpu.memory", "output" to "display.evf",
            ),
            "optionalEvfCompositeScanout" to mapOf(
                "setting" to "evfScanout", "profile" to "lcdc1-pic-yuv-osd0-nearest-v1",
                "confidence" to "HYPOTHESIS", "instance" to 0, "enableOffset" to 8, "commitOffset" to 1536,
                "osd0" to mapOf("controlOffset" to 48, "upperOffset" to 64, "lowOffset" to 68, "format" to "LE-u32-RRGGBBAA", "strideBytes" to 4096, "width" to 1024, "height" to 768, "unitScale" to 268439552),
                "pic1" to mapOf("alphaOffset" to 352, "upperOffset" to 356, "lowOffset" to 360, "strideOffset" to 364, "outExtentOffset" to 368, "positionOffset" to 372, "sizeOffset" to 376, "scaleOffset" to 380, "format" to "YUV422-16bpp-single-plane", "csc" to "BT.601-full-range", "unitScale" to 268439552),
                "scaling" to "center-nearest-640x480-straight-ARGB", "memory" to "cpu.memory", "output" to "display.evf",
            ),
        )
        fun expectedWindows(previous: Any?, released: Any?): List<Map<String, Any?>> {
            val releasedById = rows(released).associateBy { it.text("id") }
            return rows(previous).map { window ->
                val windowId = window.text("id")
                val fields = releasedFields[windowId] ?: return@map window
                val backing = obj(window["backing"])
                assertTrue(fields.none { it in backing })
                val publishedBacking = obj(releasedById.getValue(windowId)["backing"])
                val expected = backing + fields.associateWith(publishedBacking::getValue)
                assertEquals(expected, publishedBacking, "$windowId published backing")
                val current = if (windowId == registersId) m4RegistersBacking
                else if (windowId == modeWordId) expected + additionalPairs
                else if (windowId in clockBackings) m4LcdcBackings.getValue(windowId) + m4EvfBackings
                else expected
                window + ("backing" to current)
            }
        }
        assertEquals(
            expectedWindows(old.data["windows"], published.data["windows"]),
            rows(catalog.data["windows"]).filterNot { it["id"] in promotedWindowIds || it["id"] == "gfx100s.soc-jpeg" },
        )
        assertEquals(promotedWindowIds, catalog.windows.filter { it.id in promotedWindowIds }.map { it.id }.toSet())
        assertTrue(catalog.windows.filter { it.id in promotedWindowIds }.all { it.optional })
        val jpegWindow = rows(catalog.data["windows"]).single { it["id"] == "gfx100s.soc-jpeg" }
        assertEquals(0xFFFA1000L, (jpegWindow["base"] as Number).toLong())
        assertEquals(0x12CL, (jpegWindow["size"] as Number).toLong())
        assertEquals(true, jpegWindow["optional"])
        val releasedVariants = rows(published.data["profileWindows"]).associateBy { it.text("profile") }
        val variants = rows(old.data["profileWindows"]).map { variant ->
            val released = releasedVariants.getValue(variant.text("profile"))
            variant + ("windows" to expectedWindows(variant["windows"], released["windows"]))
        }
        assertEquals(
            variants,
            rows(catalog.data["profileWindows"]).filter { variant ->
                variant["profile"] in variants.map { it["profile"] }
            },
        )
        assertTrue(old.candidates.none { it.id == "gfx100s.system-control.status-a200.candidate" })
        val additions = emptyList<String>()
        assertEquals(additions, catalog.candidates.filter { candidate ->
            old.candidates.none { it.id == candidate.id }
        }.map { it.id })
        assertContentEquals(CatalogIO.canonical(old.data.getValue("candidates")),
            CatalogIO.canonical(rows(catalog.data["candidates"]).filterNot { it["id"] in additions }), "existing candidates")
        val owner = "instance.gfx100s.soc-system-control"
        val dmaInstance = "instance.gfx100s.soc-dma"
        val previousInstances = rows(old.data["instances"])
        assertEquals(1, previousInstances.count { it["id"] == owner })
        val expectedSysCtrlWindowIds = (previousInstances.single { it["id"] == owner }.getValue("windowIds") as List<*>) +
            listOf(windowId, "gfx100s.soc-system-control.fff49530-pair", "gfx100s.soc-system-control.fff49500-tail")
        val expectedInstances = previousInstances.map { instance ->
            when (instance["id"]) {
                owner -> instance + mapOf(
                    "windowIds" to expectedSysCtrlWindowIds,
                    "candidateIds" to ((instance.getValue("candidateIds") as List<*>) + additions),
                )
                dmaInstance -> instance + mapOf(
                    "windowIds" to ((instance.getValue("windowIds") as List<*>) + dmaPromotedWindowIds),
                )
                else -> instance
            }
        }
        assertContentEquals(
            CatalogIO.canonical(expectedInstances),
            CatalogIO.canonical(rows(catalog.data.getValue("instances")).filter {
                it["id"] !in setOf(
                    "instance.gfx100s.soc-hdmi", "instance.gfx100s.soc-panel", "instance.gfx100s.soc-uart",
                    "instance.gfx100s.soc-jpeg", "instance.gfx100s.soc-isp-cmd", "instance.gfx100s.soc-isp-setup",
                    "instance.gfx100s.soc-isp",
                )
            }),
            "new candidate owner relations",
        )
        assertEquals(185, catalog.windows.size)
        assertEquals(4, catalog.candidates.size)
        assertEquals(40, catalog.componentIds.size)
        assertEquals((old.componentIds + listOf("soc-hdmi", "soc-panel", "soc-uart", "soc-jpeg", "soc-isp-cmd", "soc-isp-setup", "soc-isp")).toSet(), catalog.componentIds.toSet())
        for (component in old.componentIds.filter { it !in setOf("soc-system-control", "soc-sd-host", "soc-vglib-init", "soc-dma") }) {
            val previous = obj(CatalogIO.json.readValue(git("catalog/compiled/components/$component.json"), Map::class.java))
            val expected = when (component) {
                "soc-flash" -> previous + ("depends" to listOf("flash-image"))
                "soc-gpio" -> previous + ("profiles" to
                    (previous.getValue("profiles") as List<*>) + listOf("gfx100s-2.13-gpio-buttons-v3", "gfx100s-2.13-gpio-buttons-v4"))
                else -> previous
            }
            assertContentEquals(CatalogIO.canonical(expected), CatalogIO.canonical(catalog.componentDescriptor(component)),
                "$component P951 binding baseline")
        }
    }

    private val execution = mapOf(
        "profile" to "q0-register-shared-ram-fill-experimental-v2", "opcode" to 4, "q0" to 0,
        "control" to 252707329, "bytesPerUnit" to 4, "maxBytes" to 3145728, "submissionLimitMaximum" to 513,
        "completionEvents" to "NONE", "confidence" to "HYPOTHESIS",
        "source" to "surface-audit-01, P743/P748 qualify the software opcode4/Q0 path and geometry. The device uses the submitted registers for a bounded little-endian u32 fill into selected shared RAM.",
    )
    private val fillIrq92 = mapOf(
        "setting" to "graphicsFillIrq92Tick", "sink" to "soc-gic.lines", "line" to 92, "trigger" to "level",
        "capacity" to 1, "statusReads" to listOf(67108864, 0), "confidence" to "HYPOTHESIS",
        "source" to "Registered graphics callback 01401E30, bit26 clear via 01401EDC and wake at 01401F08. Guest distributor configuration selects core0 level IRQ92. Physical assertion and timing unknown.",
    )
    private val copyRegisters = mapOf(
        "offsets" to listOf(3072,3076,3080,3084,3088,3096,3104,3108,3112), "submitOffset" to 3096, "submitValue" to 1,
        "copyIrq92" to mapOf("setting" to "graphicsCopyIrq92Tick", "sink" to "soc-gic.lines", "line" to 92,
            "trigger" to "level", "capacity" to 1, "statusReads" to listOf(1073741824, 0), "control" to 3096,
            "clearValue" to 0, "confidence" to "HYPOTHESIS",
            "source" to "P668 callback 01401EC4 tests bit30 and branches to 01401E88 to clear C18. The selected second level requires a completed copy readback."),
        "execution" to mapOf("profile" to "same-geometry-shared-ram-copy-experimental-v1", "geometryWord" to 50332672,
            "surfaceBytes" to 3145728, "control" to 1, "submissionLimitMaximum" to 513, "completionEvents" to "NONE",
            "mode2Morton8x8" to mapOf("control" to 2, "geometryWord" to 2097392, "sourceStrideBytes" to 960, "tileEdge" to 8, "confidence" to "HYPOTHESIS"),
            "mode2Workspace" to mapOf("maxBytes" to 10485760, "maxWidth" to 240, "maxHeight" to 32, "bytesPerWord" to 4, "tileEdge" to 8, "confidence" to "HYPOTHESIS"),
            "areaPageWorkspaceTile" to mapOf("control" to 2, "geometryWord" to 2621584, "sourceStrideBytes" to 576, "tileEdge" to 8, "confidence" to "HYPOTHESIS"),
            "confidence" to "HYPOTHESIS"),
        "confidence" to "HYPOTHESIS",
        "source" to "INV-GRAPHICS-TRANSFER-FFFD0C00 and DAT writer 014024C4. Optional retained u32 words with explicit initial values. The guest's final C18 write records the programmed registers.",
    )

    @Test fun promotedOptionalMmioWindowPreservesReadOnlyRegisterShapeAndNeighborRefusals() {
        val catalog = CatalogCompiler.compile(root)
        assertTrue(catalog.candidates.none { it.id == "gfx100s.system-control.status-a200.candidate" })
        val published = HistoricalCatalogFixture(root).catalog(PROMOTED)
        fun windowData(model: AddressCatalog) = rows(model.data["windows"]).single { it["id"] == windowId }
        fun definitionData(model: AddressCatalog) = rows(model.componentDescriptor("soc-system-control")["definitions"])
            .single { it["id"] == definition }
        assertContentEquals(CatalogIO.canonical(windowData(published)), CatalogIO.canonical(windowData(catalog)))
        assertContentEquals(CatalogIO.canonical(definitionData(published)), CatalogIO.canonical(definitionData(catalog)))
        val window = catalog.windows.single { it.id == windowId }
        assertEquals(0xfff4a200L, window.base)
        assertEquals(0xfff4a204L, window.endExclusive)
        assertEquals("MMIO", window.kind)
        assertTrue(window.optional)
        assertEquals(definition, window.definitionId)

        val query = catalog.query("cpu-physical", window.base, 4, "read")
        assertEquals("REGISTER", query.classification)
        assertTrue(query.permitted)
        val register = assertNotNull(query.register)
        assertEquals(listOf(4), register.readWidths)
        assertTrue(register.writeWidths.isEmpty())
        assertNull(obj(register.data["initial"])["value"])
        val fields = rows(register.data["fields"])
        assertEquals(listOf(0L to 1L, 1L to 31L), fields.map { it.long("bitOffset") to it.long("bitWidth") })
        assertTrue((register.data["evidence"] as List<*>).contains(
            "evidence/refactor/lcd-current-path-and-controls-941-945/lcd-startup-dependencies-943-report.md"))
        assertTrue(rows(catalog.componentDescriptor("soc-system-control")["definitions"]).any { it["id"] == definition })
    }

    @Test fun promotedWindowRefusesWritesNarrowWidthsAndNeighborWords() {
        val catalog = CatalogCompiler.compile(root)
        assertTrue(catalog.query("cpu-physical", 0xfff4a200L, 4, "read").permitted)
        for (direction in listOf("write", "execute"))
            assertFalse(catalog.query("cpu-physical", 0xfff4a200L, 4, direction).permitted)
        for (width in listOf(1, 2))
            assertFalse(catalog.query("cpu-physical", 0xfff4a200L, width, "read").permitted)
        for (address in listOf(0xfff4a1fcL, 0xfff4a1feL, 0xfff4a201L, 0xfff4a204L))
            assertFalse(catalog.query("cpu-physical", address, 4, "read").permitted)
    }
}
