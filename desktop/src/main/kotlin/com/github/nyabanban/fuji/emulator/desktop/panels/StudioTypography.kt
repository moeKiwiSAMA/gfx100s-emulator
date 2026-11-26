package com.github.nyabanban.fuji.emulator.desktop.panels

import javafx.scene.text.Font

/** Website alias Fujifilm Sans resolves to Trade Gothic LT Std in its private font.
 * No downloaded vendor font is packaged or loaded. Installed fonts are selected explicitly.
 */
object StudioTypography {
    val family: String by lazy {
        val available = Font.getFamilies().toSet()
        listOf("Trade Gothic LT Std", "Arial Narrow", "Arial", "System").firstOrNull { it in available } ?: Font.getDefault().family
    }
    fun observation(): Map<String, Any> = mapOf("actualFamily" to family,
        "websiteFamily" to "Fujifilm Sans (Trade Gothic LT Std)",
        "exactWebsiteFamilyAvailable" to (family == "Trade Gothic LT Std"),
        "downloadedFontPackagedOrLoaded" to false)
}
