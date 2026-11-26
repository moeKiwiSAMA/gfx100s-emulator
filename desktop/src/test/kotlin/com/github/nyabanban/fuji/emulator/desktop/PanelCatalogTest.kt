package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.panels.*
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.RadialGradient
import org.junit.jupiter.api.Test
import kotlin.test.*

class PanelCatalogTest {
    private fun tree() = Formats.json.readTree(PanelResources.bytes("catalog.json")) as ObjectNode
    private fun changed(block: (ObjectNode) -> Unit) {
        val value = tree(); block(value)
        assertFails { PanelCatalog.parse(Formats.json.writeValueAsBytes(value)) }
    }
    @Test fun sixDirectionsRetainEveryPhysicalIdAndExcludeSeparateDisplaysAndWorkbench() {
        val panels = PanelCatalog.load()
        assertEquals(PanelCatalog.viewIds, panels.map { it.id }.toSet())
        assertEquals(68, panels.sumOf { it.controls.size })
        panels.forEach { PanelCatalog.validateArtwork(it) }
        val controls = panels.flatMap { it.controls }
        assertEquals(68, controls.map { it.id }.distinct().size)
        assertEquals(19, controls.count { it.buttonKey != null })
        assertEquals(PanelCatalog.buttonKeys, controls.mapNotNull { it.buttonKey }.toSet())
        assertTrue(controls.all { it.firmwareBinding == "UNKNOWN" })
        assertFalse(tree().has("windows"))
    }
    @Test fun sameFacingDisplaysAndLensAreIntegratedWithoutInventingEvfPortOrUnwiredButtons() {
        val controls = PanelCatalog.load().flatMap { it.controls }.associateBy { it.id }
        assertEquals("display.main", controls.getValue("rear-lcd").displayPort)
        assertEquals("rear", controls.getValue("rear-lcd").viewId)
        assertEquals("display.evf", controls.getValue("rear-evf").displayPort)
        assertEquals("rear", controls.getValue("rear-evf").viewId)
        assertEquals("i2c-bus", controls.getValue("rear-eye-sensor").componentId)
        assertEquals("eyeSensorNear", controls.getValue("rear-eye-sensor").settingKey)
        assertEquals("host-state-only", controls.getValue("rear-eye-sensor").bindingStatus)
        assertEquals("latching", controls.getValue("rear-eye-sensor").interaction)
        assertEquals(setOf("power", "movieMode", "focusMode"), PanelCatalog.latchingButtonKeys)
        assertEquals("movieMode", controls.getValue("top-still-movie").buttonKey)
        assertEquals("latching", controls.getValue("top-still-movie").interaction)
        assertEquals("drive", controls.getValue("rear-drive").buttonKey)
        assertEquals("momentary", controls.getValue("rear-drive").interaction)
        assertEquals("afOn", controls.getValue("rear-af-on").buttonKey)
        assertEquals("momentary", controls.getValue("rear-af-on").interaction)
        assertEquals("focusMode", controls.getValue("rear-focus-selector").buttonKey)
        assertEquals("latching", controls.getValue("rear-focus-selector").interaction)
        assertEquals(listOf("rear-eye-sensor"), PanelCatalog.latchingSettingControls(PanelCatalog.load()).map { it.id })
        assertEquals("display.shoulder", controls.getValue("top-shoulder").displayPort)
        assertEquals("top", controls.getValue("top-shoulder").viewId)
        assertEquals("front", controls.getValue("lens-parameters").viewId)
        assertEquals("lens", controls.getValue("lens-parameters").componentId)
        assertEquals("battery", controls.getValue("bottom-charge").componentId)
        assertNull(controls.getValue("rear-q").buttonKey)
        assertEquals("soc-adc", controls.getValue("rear-q").componentId)
        assertEquals("qButton", controls.getValue("rear-q").settingKey)
        assertEquals("momentary", controls.getValue("rear-q").interaction)
        assertEquals("soc-adc", controls.getValue("rear-ael").componentId)
        assertEquals("aelButton", controls.getValue("rear-ael").settingKey)
        assertEquals("momentary", controls.getValue("rear-ael").interaction)
        assertEquals("soc-adc", controls.getValue("top-fn1").componentId)
        assertEquals("fn1Button", controls.getValue("top-fn1").settingKey)
        assertEquals("soc-adc", controls.getValue("top-fn2").componentId)
        assertEquals("fn2Button", controls.getValue("top-fn2").settingKey)
        assertEquals("soc-adc", controls.getValue("top-fn3").componentId)
        assertEquals("fn3Button", controls.getValue("top-fn3").settingKey)
        assertEquals("soc-adc", controls.getValue("top-mode-dial").componentId)
        assertEquals("modeDial", controls.getValue("top-mode-dial").settingKey)
        assertEquals("delete", controls.getValue("rear-delete").buttonKey)
        assertEquals("rearDialPress", controls.getValue("rear-command-dial").buttonKey)
        assertEquals("focusStickPress", controls.getValue("rear-stick-press").buttonKey)
        assertEquals("momentary", controls.getValue("rear-stick-press").interaction)
        assertEquals("frontDialPress", controls.getValue("front-command-dial").buttonKey)
        assertEquals("momentary", controls.getValue("front-command-dial").interaction)
        assertEquals("fn4", controls.getValue("front-fn4").buttonKey)
        assertEquals("momentary", controls.getValue("front-fn4").interaction)
        assertNull(controls.getValue("front-af-lamp").componentId)
        val layer = PanelCatalog.load().single { it.id == "front" }.layers.single()
        assertEquals(LayerCondition("lens-attached-art", "mount", "attached", true), layer)
    }
    @Test fun sourceLayoutKeepsSlotNumbersAndPasmAndFnPositionsWithoutClaimingMeasuredGeometry() {
        val c = PanelCatalog.load().flatMap { it.controls }.associateBy { it.id }
        assertTrue(c.getValue("right-slot-2").y < c.getValue("right-slot-1").y)
        assertTrue(kotlin.math.abs(c.getValue("top-fn2").x - c.getValue("top-fn3").x) < 10)
        assertTrue(c.getValue("top-fn2").y < c.getValue("top-fn3").y)
        assertTrue(c.getValue("top-still-movie").y < c.getValue("top-mode-dial").y)
        tree().path("views").forEach { v -> v.path("controls").forEach { assertEquals("HYPOTHESIS", it.path("geometryClass").asText()) } }
    }

    @Test fun lensAndBatteryInspectorsCanBePickedWithinTheirStudioWindows() {
        val panels = PanelCatalog.load().associateBy { it.id }
        val inspectors = mapOf(
            "front" to listOf("front-contacts", "lens-contacts", "lens-aperture-ring", "lens-parameters"),
            "battery-bottom" to listOf("bottom-charge"),
        )
        for ((viewId, ids) in inspectors) {
            val controls = panels.getValue(viewId).controls
            val crop = StudioLayout.faces.getValue(viewId).crop
            for (id in ids) {
                val control = controls.single { it.id == id }
                val x = control.x + control.width / 2
                val y = control.y + control.height / 2
                assertTrue(x in crop.x..(crop.x + crop.width) && y in crop.y..(crop.y + crop.height), id)
                val topmost = controls.last { x in it.x..(it.x + it.width) && y in it.y..(it.y + it.height) }
                assertEquals(id, topmost.id, "Topmost control at the center of $id")
            }
        }
    }

    @Test fun stillMovieDetentsAndDialIndexMatchThePhysicalTopPlate() {
        val top = PanelCatalog.load().single { it.id == "top" }
        val lever = top.controls.single { it.id == "top-still-movie" }
        val dial = top.controls.single { it.id == "top-mode-dial" }
        assertTrue(lever.width / dial.width in 0.32..0.37)
        assertTrue(lever.height / dial.height in 0.24..0.29)
        assertTrue(lever.y + lever.height < dial.y)
        val detents = top.dynamicStates.filter { it.buttonKey == "movieMode" }.associateBy { it.id }
        val still = detents.getValue("gfx-movie-selector-still")
        val movie = detents.getValue("gfx-movie-selector-movie")
        assertEquals(1.0 to 0.0, still.inactiveOpacity to still.activeOpacity)
        assertEquals(0.0 to 1.0, movie.inactiveOpacity to movie.activeOpacity)
        val wheel = top.dynamicStates.single { it.settingKey == "modeDial" }
        assertEquals(dial.x + dial.width / 2, wheel.pivotX)
        assertEquals(dial.y + dial.height / 2, wheel.pivotY)
        assertEquals(108.0, wheel.angleMap["M"])
    }
    @Test fun schemaModelDuplicateViewsGlobalControlIdsBoundsAndInventedBindingsRefuse() {
        changed { it.put("schema", 1) }; changed { it.put("model", "GFX100S II") }; changed { it.put("modelCode", "OTHER") }
        changed { it.set<JsonNode>("windows", it.path("views") as ArrayNode) }
        changed { (it.path("views")[1] as ObjectNode).put("id", "rear") }
        changed { val controls = it.path("views")[0].path("controls") as ArrayNode; controls.add(controls[0].deepCopy<JsonNode>()) }
        fun edit(key: String, value: Any) = changed {
            val c = it.path("views")[0].path("controls")[0] as ObjectNode
            when (value) { is String -> c.put(key, value); is Int -> c.put(key, value) }
        }
        edit("width", 0); edit("x", -1); edit("height", 10000); edit("viewId", "other")
        edit("buttonKey", "launchMenu"); edit("displayPort", "display.main-clone"); edit("firmwareBinding", "VERIFIED")
        changed { (it.path("views")[0] as ObjectNode).put("svg", "../external.svg") }
        changed { (it.path("views")[0].path("controls")[0].path("hitbox") as ObjectNode).put("x", 99) }
        changed { (it.path("views")[2].path("layers")[0].path("visibleWhen") as ObjectNode).put("equals", "true") }
    }
    @Test fun boundedGradientPaintServersResolveLocallyAndPreserveColourOpacityAndFractions() {
        val svg = PanelResources.document("""<svg xmlns="http://www.w3.org/2000/svg"><defs><linearGradient id="metal" x1="0%" y1="0%" x2="100%" y2="100%"><stop offset="0" stop-color="#ffffff" stop-opacity="0.25"/><stop offset="100%" stop-color="#111111"/></linearGradient><radialGradient id="glass" cx="40%" cy="30%" r="70%"><stop offset="0" stop-color="#123456"/><stop offset="1" stop-color="#000000"/></radialGradient></defs><rect fill="url(#metal)"/></svg>""".toByteArray())
        val paints = SvgArtwork.gradients(svg)
        val metal = assertIs<LinearGradient>(paints.getValue("metal"))
        assertTrue(metal.isProportional); assertEquals(1.0, metal.endX); assertEquals(1.0, metal.endY)
        assertEquals(0.25, metal.stops.first().color.opacity)
        val glass = assertIs<RadialGradient>(paints.getValue("glass"))
        assertEquals(0.4, glass.centerX); assertEquals(0.3, glass.centerY); assertEquals(0.7, glass.radius)
        PanelCatalog.load().forEach { assertTrue(SvgArtwork.gradients(PanelCatalog.validateArtwork(it)).isNotEmpty()) }
    }
    @Test fun svgActiveContentExternalEntitiesNamespacesAndNonlocalPaintCannotExecute() {
        fun fail(content: String) = assertFails { PanelResources.document(content.toByteArray()) }
        fail("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///private'>]><svg xmlns='http://www.w3.org/2000/svg'>&x;</svg>")
        fail("<svg xmlns='http://www.w3.org/2000/svg'><script>bad</script></svg>")
        fail("<svg xmlns='http://www.w3.org/2000/svg'><image href='https://example.com/x'/></svg>")
        fail("<svg xmlns='http://www.w3.org/2000/svg' onload='bad'/>")
        fail("<svg xmlns='http://www.w3.org/2000/svg'><rect fill='url(https://example.com/paint)'/></svg>")
        fail("<svg xmlns='http://www.w3.org/2000/svg'><rect fill='url(#missing)'/></svg>")
        fail("<svg xmlns='http://www.w3.org/2000/svg'><g id='not-paint'/><rect fill='url(#not-paint)'/></svg>")
        fail("<svg xmlns='http://www.w3.org/2000/svg'><rect style='fill:red'/></svg>")
        fail("<svg xmlns='https://other.example/svg'/>")
        fail("<svg xmlns='http://www.w3.org/2000/svg'><g xmlns='https://other.example'/></svg>")
        fail("<svg xmlns='http://www.w3.org/2000/svg'>" + "<g/>".repeat(4097) + "</svg>")
        assertFails { PanelResources.document(ByteArray(262145)) }; assertFails { PanelResources.bytes("../outside.svg") }
    }
    @Test fun cyclicHrefGradientTransformsBadFractionsOffsetsAndDuplicateIdsRefuse() {
        fun document(extra: String, stops: String = "<stop offset='0' stop-color='#fff'/><stop offset='1' stop-color='#000'/>") = "<svg xmlns='http://www.w3.org/2000/svg'><defs><linearGradient id='paint' $extra>$stops</linearGradient></defs><rect fill='url(#paint)'/></svg>"
        listOf("href='#paint'", "gradientTransform='scale(2)'", "gradientUnits='userSpaceOnUse'", "x1='NaN'", "x2='101%'", "x1='-1'").forEach { assertFails(it) { PanelResources.document(document(it).toByteArray()) } }
        assertFails { PanelResources.document(document("", "<stop offset='1' stop-color='#fff'/><stop offset='0' stop-color='#000'/>").toByteArray()) }
        assertFails { PanelResources.document(document("", "<stop offset='0' stop-color='#fff' stop-opacity='2'/><stop offset='1' stop-color='#000'/>").toByteArray()) }
        assertFails { PanelResources.document("<svg xmlns='http://www.w3.org/2000/svg'><g id='x'/><g id='x'/></svg>".toByteArray()) }
    }
    @Test fun topEvfProjectsBehindThinBodyAndEveryNonfrontLensUsesShallowCapLayers() {
        val panels = PanelCatalog.load()
        val projectionViews = setOf("front", "top", "ports-left", "storage-right", "battery-bottom")
        assertEquals(projectionViews, panels.filter { it.layers.isNotEmpty() }.map { it.id }.toSet())
        panels.filter { it.id in projectionViews }.forEach { panel ->
            assertEquals(1, panel.layers.size)
            val layer = panel.layers.single()
            assertEquals("mount", layer.componentId); assertEquals("attached", layer.settingKey); assertTrue(layer.expected)
            if (panel.id != "front") {
                val root = PanelCatalog.validateArtwork(panel)
                val all = root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "g")
                val art = (0 until all.length).map { all.item(it) as org.w3c.dom.Element }.single { it.getAttribute("id") == layer.id }
                assertEquals("shallow-cap", art.getAttribute("data-projection"))
                assertEquals(0, art.getElementsByTagNameNS("http://www.w3.org/2000/svg", "ellipse").length)
            }
        }
        val root = PanelCatalog.validateArtwork(panels.single { it.id == "top" })
        val groups = root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "g")
        val elements = (0 until groups.length).map { groups.item(it) as org.w3c.dom.Element }.associateBy { it.getAttribute("id") }
        val body = elements.getValue("top-main-body"); val evf = elements.getValue("top-evf-housing")
        assertTrue(body.getAttribute("data-max-core-depth").toInt() <= 280)
        assertTrue(evf.getAttribute("data-back").toInt() > body.getAttribute("data-back-edge").toInt())
        assertEquals("true", evf.getAttribute("data-projects-behind-body"))
    }
    @Test fun staleArtworkMissingResourcesOrNonmatchingTransparentHotspotsNeverProduceFalseControls() {
        val rear = PanelCatalog.load().single { it.id == "rear" }
        assertFails { PanelCatalog.validateArtwork(rear.copy(width = rear.width + 1)) }
        assertFails { PanelCatalog.validateArtwork(rear.copy(controls = rear.controls.drop(1))) }
        assertFails { PanelCatalog.validateArtwork(rear.copy(controls = rear.controls.mapIndexed { i, c -> if (i == 0) c.copy(x = c.x + 1) else c })) }
        assertFails { PanelCatalog.validateArtwork(rear.copy(svg = "missing.svg")) }
        assertFails { PanelCatalog.parse(ByteArray(262145)) }
    }
    @Test fun officialFocusLabelsRunTopToBottomAndDiopterActuallyTouchesEvfHousing() {
        fun elements(panel: String): Map<String, org.w3c.dom.Element> {
            val root = PanelCatalog.validateArtwork(PanelCatalog.load().single { it.id == panel })
            val all = root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "*")
            return (0 until all.length).map { all.item(it) as org.w3c.dom.Element }.filter { it.hasAttribute("id") }.associateBy { it.getAttribute("id") }
        }
        val rear = elements("rear")
        assertEquals("S,C,M", rear.getValue("focus-mode-labels").getAttribute("data-rear-top-to-bottom"))
        val labels = listOf("s","c","m").map { rear.getValue("focus-mode-" + it) }
        assertEquals(listOf("S","C","M"), labels.map { it.textContent })
        assertTrue(labels.map { it.getAttribute("y").toDouble() }.zipWithNext().all { (a,b) -> a < b })
        val top = elements("top").getValue("top-diopter-physical")
        assertEquals("translate(26 0)", top.getAttribute("transform"))
        assertTrue(top.getAttribute("data-right").toDouble() >= top.getAttribute("data-evf-left").toDouble())
        val hit = PanelCatalog.load().single { it.id == "top" }.controls.single { it.id == "top-diopter" }
        assertTrue(hit.x <= top.getAttribute("data-left").toDouble() && hit.x + hit.width >= top.getAttribute("data-right").toDouble())
        assertNull(hit.buttonKey)
    }

    @Test fun separateConnectorRecessesAndThinCardEdgesRemainIndependentlyInspectable() {
        val panels=PanelCatalog.load().associateBy { it.id }
        val left=PanelCatalog.validateArtwork(panels.getValue("ports-left"))
        val groups=left.getElementsByTagNameNS("http://www.w3.org/2000/svg","g")
        val ids=(0 until groups.length).map { (groups.item(it) as org.w3c.dom.Element).getAttribute("id") }.toSet()
        assertTrue(ids.containsAll(setOf("left-upper-connector-recess","left-lower-connector-recess","left-cover-1","left-cover-2")))
        val covers=panels.getValue("ports-left").controls.filter { it.id in setOf("left-cover-1","left-cover-2") }.sortedBy { it.y }
        assertEquals(2,covers.size);assertTrue(covers[0].y+covers[0].height<covers[1].y)
        val right=PanelCatalog.validateArtwork(panels.getValue("storage-right"))
        val rects=right.getElementsByTagNameNS("http://www.w3.org/2000/svg","rect")
        val cardEdges=(0 until rects.length).map { rects.item(it) as org.w3c.dom.Element }.filter { it.getAttribute("width")=="4" && it.getAttribute("height")=="94" }
        assertEquals(2,cardEdges.size)
        panels.getValue("storage-right").controls.filter { it.kind=="storage-slot" }.forEach { assertTrue(it.height/it.width>3) }
    }
    @Test fun paintedAndRubberBroadSurfacesKeepStableBaseColoursWhileBevelsCarryLight() {
        fun parseHexRgb(hex: String): Triple<Int, Int, Int> {
            val clean = hex.removePrefix("#")
            require(clean.length == 6) { "Expected #RRGGBB stop-color, got $hex" }
            return Triple(
                clean.substring(0, 2).toInt(16),
                clean.substring(2, 4).toInt(16),
                clean.substring(4, 6).toInt(16),
            )
        }
        PanelCatalog.load().forEach { panel ->
            val root = PanelCatalog.validateArtwork(panel)
            val gradients = root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "linearGradient")
            val byId = (0 until gradients.length).map { gradients.item(it) as org.w3c.dom.Element }
                .associateBy { it.getAttribute("id") }
            for ((id, maxLuma) in listOf("body" to 0x38, "grip" to 0x24)) {
                val g = byId.getValue(id)
                val stops = g.getElementsByTagNameNS("http://www.w3.org/2000/svg", "stop")
                assertTrue(stops.length >= 1, "${panel.id} #$id must have gradient stops")
                val colors = (0 until stops.length).map {
                    parseHexRgb((stops.item(it) as org.w3c.dom.Element).getAttribute("stop-color"))
                }
                colors.forEach { (r, gCh, b) ->
                    assertEquals(r, gCh, "${panel.id} #$id must stay neutral achromatic")
                    assertEquals(gCh, b, "${panel.id} #$id must stay neutral achromatic")
                    assertTrue(r <= maxLuma, "${panel.id} #$id stop luma $r exceeds matte dark ceiling $maxLuma")
                }
            }
            val edge = byId.getValue("edge")
            val edgeStops = edge.getElementsByTagNameNS("http://www.w3.org/2000/svg", "stop")
            val maxEdgeLuma = (0 until edgeStops.length).maxOf {
                parseHexRgb((edgeStops.item(it) as org.w3c.dom.Element).getAttribute("stop-color")).first
            }
            assertTrue(maxEdgeLuma >= 0x70, "${panel.id} #edge bevel should carry specular highlight (max=$maxEdgeLuma)")
        }
    }
}
