package com.github.nyabanban.fuji.emulator.desktop.panels

import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority

/** Three absolute detents sharing the labels printed in the theme artwork underneath. */
internal class FocusModeSegments(initialMode: String, private val choose: (String) -> Unit) : HBox() {
    private val modes = ShootingControlsState.FOCUS_MODES
    private val group = ToggleGroup()
    private val buttons = modes.mapIndexed { index, mode ->
        ToggleButton().apply {
            id = "focus-mode-segment-${mode.lowercase()}"
            accessibleText = mode
            styleClass += "studio-focus-segment"
            toggleGroup = group
            minWidth = 0.0
            prefWidth = 0.0
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            setHgrow(this, Priority.ALWAYS)
            setOnAction {
                selectMode(mode)
                choose(mode)
            }
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                val next = when (event.code) {
                    KeyCode.LEFT -> (index - 1).coerceAtLeast(0)
                    KeyCode.RIGHT -> (index + 1).coerceAtMost(modes.lastIndex)
                    KeyCode.HOME -> 0
                    KeyCode.END -> modes.lastIndex
                    else -> return@addEventFilter
                }
                activate(next)
                event.consume()
            }
        }
    }
    private var tracking = false

    init {
        id = "button-focusMode"
        children.setAll(buttons)
        selectMode(initialMode)
        addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (event.button == MouseButton.PRIMARY) {
                tracking = true
                event.consume()
            }
        }
        addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            if (tracking && event.button == MouseButton.PRIMARY) {
                tracking = false
                val point = sceneToLocal(event.sceneX, event.sceneY)
                if (width > 0.0 && contains(point)) {
                    activate((point.x / width * modes.size).toInt().coerceIn(modes.indices))
                }
                event.consume()
            }
        }
    }

    fun selectMode(mode: String) {
        val index = modes.indexOf(mode)
        if (index >= 0) group.selectToggle(buttons[index])
    }

    private fun activate(index: Int) {
        buttons[index].requestFocus()
        buttons[index].fire()
    }
}
