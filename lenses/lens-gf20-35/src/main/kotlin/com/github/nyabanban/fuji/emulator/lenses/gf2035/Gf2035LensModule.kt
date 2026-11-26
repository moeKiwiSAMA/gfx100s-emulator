package com.github.nyabanban.fuji.emulator.lenses.gf2035

import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.LenscomIdentitySpec
import com.github.nyabanban.fuji.emulator.api.PluginMetadata

class Gf2035LensModule : LensModule {
    override val id: String = "gf20-35"
    override val displayName: String = "GF 20-35mm F4 R WR"
    override val mountType: String = "GF"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Ultra-wide medium-format zoom with Nano GI coating for ultra-low flare, gentle wide-angle distortion, minimal CA, and 0.35 m close focus.",
        descriptionZh = "超广角中画幅变焦镜头 · STM 0.42s · Nano GI 镀膜冷绿抗炫光 · 极低色散与 0.35m 近摄能力。",
        descriptions = mapOf(
            "en" to "Ultra-wide medium-format zoom with Nano GI coating for ultra-low flare, gentle wide-angle distortion, minimal CA, and 0.35 m close focus.",
            "zh_CN" to "超广角中画幅变焦镜头 · STM 0.42s · Nano GI 镀膜冷绿抗炫光 · 极低色散与 0.35m 近摄能力。",
            "ja_JP" to "ナノGIコーティングによる極めて高い逆光耐性を備えた中判超広角ズーム。STM 0.42秒、極小色収差、最短撮影距離0.35m。",
            "de_DE" to "Mittelformat-Ultraweitwinkelzoom mit Nano-GI-Vergütung gegen Streulicht, geringer Farbsäumung und 0,35 m Naheinstellgrenze.",
            "fr_FR" to "Zoom ultra grand-angle moyen format à traitement Nano GI très résistant au flare, faible aberration chromatique et mise au point à 0,35 m.",
            "es_ES" to "Zoom ultra gran angular de formato medio con recubrimiento Nano GI antiflare, mínima aberración cromática y enfoque cercano a 0,35 m.",
        ),
    )

    override val opticalSpec: LensOpticalSpec = LensOpticalSpec(
        id = id,
        displayName = displayName,
        mountType = mountType,
        modelName = "GF20-35mmF4 R WR",
        defaultFocalLengthMm = 20.0,
        referenceFocalLengthMm = 20.0,
        minFocalLengthMm = 20.0,
        maxFocalLengthMm = 35.0,
        defaultAperture = 4.0,
        minAperture = 4.0,
        maxAperture = 22.0,
        minFocusDistanceM = 0.35,
        teleMinFocusDistanceM = 0.35,
        defaultFocusDistanceM = 2.5,
        transmission = 0.90,
        focusBreathing = -0.015,
        distortionK1 = -0.075,
        distortionK2 = 0.012,
        caRedScale = 1.0007,
        caBlueScale = 0.9994,
        longitudinalCaDefocusMm = 0.02,
        wideOpenContrastDrop = 0.04,
        vignettingCos4 = 2.6,
        vignettingMechanical = 0.22,
        imageCircleMm = 56.0,
        electronicContacts = true,
        oisEnabled = false,
        defaultFocusMode = "AF-S",
        focusMotorType = "STM",
        focusSweepSeconds = 0.42,
        flareStrength = 0.10,
        flareTintR = 0.85,
        flareTintG = 1.0,
        flareTintB = 0.92,
        apertureBladeCount = 9,
        apertureBladeRoundness = 0.80,
        bokehCatsEye = 0.22,
        bokehOnionRing = 0.25,
        bokehRimHighlight = 0.18,
        opticalCharacterSummary = "Ultra-wide medium-format zoom with Nano GI coating for ultra-low flare, gentle wide-angle distortion, minimal CA, and 0.35 m close focus.",
    )

    override val controlContract: LensControlContract = LensControlContract.GenericSimulated(
        LenscomIdentitySpec(
            shortId = "GF2035",
            modelAscii = "GF20-35mmF4 R WR",
            serialAscii = "GF02003501",
            capabilityFlags1 = 0x01, // DAT 0x0122C088: hdr[101] bit 0 = LMO/LEQ support (bits 3/4 = 0)
            isZoom = true,
            hasOis = false,
            hasApertureRing = true,
            minFocalMm = 20.0,
            maxFocalMm = 35.0,
            minAperture = 4.0,
            maxAperture = 22.0,
            focusMotorType = "STM",
            focusSweepSeconds = 0.42,
        ),
    )
}
