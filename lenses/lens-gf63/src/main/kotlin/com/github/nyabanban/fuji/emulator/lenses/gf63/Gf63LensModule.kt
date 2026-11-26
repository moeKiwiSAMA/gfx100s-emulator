package com.github.nyabanban.fuji.emulator.lenses.gf63

import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.LenscomIdentitySpec
import com.github.nyabanban.fuji.emulator.api.PluginMetadata

class Gf63LensModule : LensModule {
    override val id: String = "gf63"
    override val displayName: String = "GF 63mm F2.8 R WR"
    override val mountType: String = "GF"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Compact standard prime with unit-focusing DC coreless motor (0.78 s), high micro-contrast, gentle f/2.8 longitudinal CA, and clean bokeh.",
        descriptionZh = "标准定焦镜头 · 全组伸出空心杯直流马达 0.78s · 极高微反差、f/2.8 温和轴向色散与洁净焦外。",
        descriptions = mapOf(
            "en" to "Compact standard prime with unit-focusing DC coreless motor (0.78 s), high micro-contrast, gentle f/2.8 longitudinal CA, and clean bokeh.",
            "zh_CN" to "标准定焦镜头 · 全组伸出空心杯直流马达 0.78s · 极高微反差、f/2.8 温和轴向色散与洁净焦外。",
            "ja_JP" to "全群繰り出しDCコアレスモーター(0.78秒)採用の標準単焦点。高いマイクロコントラスト、F2.8の穏やかな軸上色収差、素直なボケ味。",
            "de_DE" to "Kompakte Standardfestbrennweite mit DC-Glockenankermotor (0,78 s), hohem Mikrokontrast, sanfter f/2,8-Längs-CA und sauberem Bokeh.",
            "fr_FR" to "Focale fixe standard compacte à moteur DC sans noyau (0,78 s), micro-contraste élevé, légère AC longitudinale à f/2,8 et bokeh propre.",
            "es_ES" to "Objetivo fijo estándar compacto con motor DC sin núcleo (0,78 s), alto microcontraste, suave AC axial a f/2.8 y bokeh limpio.",
        ),
    )

    override val opticalSpec: LensOpticalSpec = LensOpticalSpec(
        id = id,
        displayName = displayName,
        mountType = mountType,
        modelName = "GF63mmF2.8 R WR",
        defaultFocalLengthMm = 63.0,
        referenceFocalLengthMm = 63.0,
        minFocalLengthMm = 63.0,
        maxFocalLengthMm = 63.0,
        defaultAperture = 2.8,
        minAperture = 2.8,
        maxAperture = 32.0,
        minFocusDistanceM = 0.50,
        teleMinFocusDistanceM = 0.50,
        defaultFocusDistanceM = 2.5,
        transmission = 0.94,
        focusBreathing = -0.01,
        distortionK1 = -0.012,
        distortionK2 = 0.002,
        caRedScale = 1.0011,
        caBlueScale = 0.9990,
        longitudinalCaDefocusMm = 0.09,
        wideOpenContrastDrop = 0.08,
        vignettingCos4 = 1.5,
        vignettingMechanical = 0.10,
        imageCircleMm = 56.0,
        electronicContacts = true,
        oisEnabled = false,
        defaultFocusMode = "AF-S",
        focusMotorType = "DC_CORELESS",
        focusSweepSeconds = 0.78,
        flareStrength = 0.11,
        flareTintR = 0.95,
        flareTintG = 0.97,
        flareTintB = 1.0,
        apertureBladeCount = 9,
        apertureBladeRoundness = 0.78,
        bokehCatsEye = 0.15,
        bokehOnionRing = 0.05,
        bokehRimHighlight = 0.05,
        opticalCharacterSummary = "Compact standard prime with unit-focusing DC coreless motor (0.78 s), high micro-contrast, gentle f/2.8 longitudinal CA, and clean bokeh.",
    )

    override val controlContract: LensControlContract = LensControlContract.GenericSimulated(
        LenscomIdentitySpec(
            shortId = "GF63F28",
            modelAscii = "GF63mmF2.8 R WR",
            serialAscii = "GF06302801",
            capabilityFlags1 = 0x01, // DAT 0x0122C088: hdr[101] bit 0 = LMO/LEQ support (bits 3/4 = 0)
            isZoom = false,
            hasOis = false,
            hasApertureRing = true,
            minFocalMm = 63.0,
            maxFocalMm = 63.0,
            minAperture = 2.8,
            maxAperture = 32.0,
            focusMotorType = "DC_CORELESS",
            focusSweepSeconds = 0.78,
        ),
    )
}
