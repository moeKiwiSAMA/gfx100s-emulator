package com.github.nyabanban.fuji.emulator.lenses.takumar50f14

import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.PluginMetadata

class Takumar50F14LensModule : LensModule {
    override val id: String = "takumar-50-f14"
    override val legacyTemplateIds: List<String> = listOf("adapter-m42-dumb")
    override val displayName: String = "Asahi Super-Takumar 50mm f/1.4"
    override val mountType: String = "M42"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Vintage mechanical M42 manual prime featuring classic f/1.4 spherical glow, warm amber flare, 6-blade hexagonal highlights, and soap-bubble bokeh rims.",
        descriptionZh = "经典复古 M42 机械手动大光圈 · f/1.4 球差柔光、标志性琥珀暖炫光与 6叶片六边形泡泡亮边焦外。",
        descriptions = mapOf(
            "en" to "Vintage mechanical M42 manual prime featuring classic f/1.4 spherical glow, warm amber flare, 6-blade hexagonal highlights, and soap-bubble bokeh rims.",
            "zh_CN" to "经典复古 M42 机械手动大光圈 · f/1.4 球差柔光、标志性琥珀暖炫光与 6叶片六边形泡泡亮边焦外。",
            "ja_JP" to "開放F1.4の球面収差による柔らかな滲み、象徴的なアンバー色の暖色フレア、6枚羽根六角形・バブル縁取りボケを持つM42クラシック単焦点。",
            "de_DE" to "Klassische mechanische M42-Festbrennweite mit typischem f/1,4-Sphärenleuchten, warmem Bernstein-Flare, 6-Lamellen-Sechsecken und Seifenblasen-Bokeh.",
            "fr_FR" to "Focale fixe vintage mécanique M42 au halo sphérique classique à f/1,4, flare ambré chaleureux, reflets hexagonaux à 6 lamelles et bokeh bulles de savon.",
            "es_ES" to "Objetivo fijo mecánico M42 clásico con resplandor esférico a f/1.4, flare ámbar cálido, reflejos hexagonales de 6 palas y bokeh burbuja.",
        ),
    )

    override val opticalSpec: LensOpticalSpec = LensOpticalSpec(
        id = id,
        displayName = displayName,
        mountType = mountType,
        modelName = "Super-Takumar 50mm F1.4",
        defaultFocalLengthMm = 50.0,
        referenceFocalLengthMm = 50.0,
        minFocalLengthMm = 50.0,
        maxFocalLengthMm = 50.0,
        defaultAperture = 1.4,
        minAperture = 1.4,
        maxAperture = 16.0,
        minFocusDistanceM = 0.45,
        teleMinFocusDistanceM = 0.45,
        defaultFocusDistanceM = 2.5,
        transmission = 0.88,
        focusBreathing = -0.02,
        distortionK1 = -0.035,
        distortionK2 = 0.005,
        caRedScale = 1.0042,
        caBlueScale = 0.9958,
        longitudinalCaDefocusMm = 0.36,
        wideOpenContrastDrop = 0.32,
        vignettingCos4 = 2.8,
        vignettingMechanical = 0.30,
        imageCircleMm = 44.5,
        electronicContacts = false,
        oisEnabled = false,
        defaultFocusMode = "MF",
        focusMotorType = "MANUAL",
        focusSweepSeconds = 0.0,
        flareStrength = 0.48,
        flareTintR = 1.0,
        flareTintG = 0.72,
        flareTintB = 0.38,
        apertureBladeCount = 6,
        apertureBladeRoundness = 0.12,
        bokehCatsEye = 0.48,
        bokehOnionRing = 0.12,
        bokehRimHighlight = 0.38,
        opticalCharacterSummary = "Vintage mechanical M42 manual prime featuring classic f/1.4 spherical glow, warm amber flare, 6-blade hexagonal highlights, and soap-bubble bokeh rims.",
    )

    override val controlContract: LensControlContract = LensControlContract.Manual
}
