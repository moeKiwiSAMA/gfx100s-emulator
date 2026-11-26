package com.github.nyabanban.fuji.emulator.desktop.panels

import com.fasterxml.jackson.databind.JsonNode
import javafx.geometry.Rectangle2D
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.layout.Region

/** Mechanical cover and the controls behind it. Opening a door does not change card or battery presence. */
data class PanelCover(
    val id: String,
    val latch: String?,
    val inside: List<String>,
    val openHitbox: Rectangle2D,
) {
    fun attach(artwork: Node, controls: Map<String, Region>, openDoors: MutableSet<String>) {
        val closedArt = requireNotNull(artwork.lookup("#svg-$id-closed"))
        val openArt = requireNotNull(artwork.lookup("#svg-$id-open"))
        val handle = controls.getValue(id) as Button
        val release = latch?.let { controls.getValue(it) as Button }
        handle.styleClass += "studio-mechanical-control"
        release?.styleClass?.add("studio-mechanical-control")
        val closedHitbox = Rectangle2D(handle.layoutX, handle.layoutY, handle.prefWidth, handle.prefHeight)
        fun update() {
            val open = id in openDoors
            openArt.isVisible = open
            closedArt.isVisible = !open
            release?.isVisible = !open
            inside.forEach { controls.getValue(it).isVisible = open }
            val bounds = if (open) openHitbox else closedHitbox
            handle.setMinSize(bounds.width, bounds.height)
            handle.setPrefSize(bounds.width, bounds.height)
            handle.setMaxSize(bounds.width, bounds.height)
            handle.resizeRelocate(bounds.minX, bounds.minY, bounds.width, bounds.height)
        }
        handle.setOnAction {
            if (!openDoors.add(id)) openDoors.remove(id)
            update()
        }
        release?.setOnAction {
            openDoors.add(id)
            update()
        }
        release?.toFront()
        update()
    }

    companion object {
        fun parse(view: JsonNode, controls: List<PanelControl>, width: Double, height: Double): List<PanelCover> {
            val covers = view.get("covers") ?: return emptyList()
            require(covers.isArray && covers.size() <= 8) { "invalid mechanical covers" }
            val byId = controls.associateBy { it.id }
            val usedControls = mutableSetOf<String>()
            fun claimHandle(id: String, kind: String) {
                val control = byId[id]
                require(control?.kind == kind && control.buttonKey == null && control.settingKey == null &&
                    control.role == null && control.interaction == "cover-toggle" && usedControls.add(id)) {
                    "invalid mechanical $kind: $id"
                }
            }
            return covers.map { entry ->
                val id = entry.required("id").asText()
                claimHandle(id, "cover")
                val latch = entry.get("latch")?.asText()
                if (latch != null) claimHandle(latch, "latch")
                val inside = entry.required("inside")
                require(inside.isArray && inside.all { it.isTextual && it.asText() in byId && usedControls.add(it.asText()) }) {
                    "invalid controls behind cover: $id"
                }
                val hitbox = entry.required("openHitbox")
                fun field(name: String) = hitbox.required(name).let {
                    require(it.isNumber && it.asDouble().isFinite()) { "invalid cover hitbox: $name" }
                    it.asDouble()
                }
                val bounds = Rectangle2D(field("x"), field("y"), field("width"), field("height"))
                require(bounds.width > 0 && bounds.height > 0 && bounds.minX >= 0 && bounds.minY >= 0 &&
                    bounds.maxX <= width && bounds.maxY <= height) { "open cover hitbox outside view: $id" }
                PanelCover(id, latch, inside.map { it.asText() }, bounds)
            }
        }
    }
}
