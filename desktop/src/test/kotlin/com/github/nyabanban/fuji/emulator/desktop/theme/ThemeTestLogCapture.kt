package com.github.nyabanban.fuji.emulator.desktop.theme

import com.github.nyabanban.fuji.emulator.runtime.FujiLogCapture

/**
 * Captures SLF4J WARN messages emitted by [loggerClass] synchronously via [FujiLogCapture].
 */
internal object ThemeTestLogCapture {
    fun captureWarnings(loggerClass: Class<*>, block: () -> Unit): List<String> =
        FujiLogCapture.captureWarnings(loggerClass, block)
}
