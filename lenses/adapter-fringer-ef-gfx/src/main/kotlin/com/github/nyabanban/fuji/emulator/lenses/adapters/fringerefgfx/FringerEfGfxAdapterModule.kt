package com.github.nyabanban.fuji.emulator.lenses.adapters.fringerefgfx

import com.github.nyabanban.fuji.emulator.api.LensCommunicationMode
import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.LenscomIdentitySpec
import com.github.nyabanban.fuji.emulator.api.MountAdapterModule
import com.github.nyabanban.fuji.emulator.api.PluginMetadata

class FringerEfGfxAdapterModule : MountAdapterModule {
    override val id: String = "adapter-fringer-ef-gfx"
    override val legacyTemplateIds: List<String> = listOf("adapter-ef-gfx")
    override val displayName: String = "Fringer EF->GFX Pro Electronic Adapter"
    override val bodyMount: String = "GF"
    override val lensMount: String = "EF"
    override val communicationMode: LensCommunicationMode = LensCommunicationMode.GENERIC_SIMULATED
    override val electronicContacts: Boolean = true
    override val defaultLensId: String = "canon-ef85-f14l"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "2.0.0",
        author = "Built-in",
        description = "Smart electronic EF-to-GF mount adapter with active AF, IS, and aperture LENSCOM translation plus EXIF pass-through.",
        descriptionZh = "智能电子 EF 转 GF 卡口转接环，支持主动自动对焦、防抖、光圈 LENSCOM 协议转换与 EXIF 透传。",
        names = mapOf(
            "en" to "Fringer EF-GFX Pro (Electronic)",
            "zh_CN" to "Fringer EF-GFX Pro（电子转接环）",
            "ja_JP" to "Fringer EF-GFX Pro（電子マウントアダプター）",
            "de_DE" to "Fringer EF-GFX Pro (Elektronischer Adapter)",
            "fr_FR" to "Fringer EF-GFX Pro (Bague électronique)",
            "es_ES" to "Fringer EF-GFX Pro (Adaptador electrónico)",
        ),
        descriptions = mapOf(
            "en" to "Smart electronic EF-to-GF mount adapter with active AF, IS, and aperture LENSCOM translation plus EXIF pass-through.",
            "zh_CN" to "智能电子 EF 转 GF 卡口转接环，支持主动自动对焦、防抖、光圈 LENSCOM 协议转换与 EXIF 透传。",
            "ja_JP" to "AF・手ブレ補正・絞り制御をLENSCOMプロトコルへ変換し、EXIF情報も透過する電子式EF-GFマウントアダプター。",
            "de_DE" to "Elektronischer EF-auf-GF-Adapter mit aktiver AF-, IS- und Blenden-LENSCOM-Übersetzung sowie EXIF-Übertragung.",
            "fr_FR" to "Bague électronique EF vers GF traduisant l'AF, la stabilisation IS et l'ouverture via LENSCOM avec transmission EXIF.",
            "es_ES" to "Adaptador electrónico EF a GF con traducción activa LENSCOM de AF, estabilizador IS y apertura, más datos EXIF.",
        ),
    )

    override fun bridgeOpticalSpec(lens: LensModule): LensOpticalSpec {
        require(lens.mountType == lensMount) {
            "Fringer EF->GFX adapter requires mountType='$lensMount', got '${lens.mountType}' (${lens.id})"
        }
        val spec = lens.opticalSpec
        return spec.copy(
            id = "adapter-ef-gfx",
            displayName = "Fringer EF->GFX Pro + ${spec.displayName}",
            mountType = "EF->GFX",
            modelName = "Fringer EF-GFX (${spec.modelName})",
            electronicContacts = true,
        )
    }

    override fun bridgeControlContract(lens: LensModule): LensControlContract {
        require(lens.mountType == lensMount) {
            "Fringer EF->GFX adapter requires mountType='$lensMount', got '${lens.mountType}' (${lens.id})"
        }
        val spec = lens.opticalSpec
        val baseId = (lens.controlContract as? LensControlContract.GenericSimulated)?.identitySpec
        val bridgedIdentity = (baseId ?: LenscomIdentitySpec(
            shortId = "FREFGFX",
            modelAscii = "Fringer EF-GFX (${spec.modelName})".take(63),
            isZoom = spec.maxFocalLengthMm > spec.minFocalLengthMm + 0.5,
            hasOis = spec.oisEnabled,
            hasApertureRing = true,
            minFocalMm = spec.minFocalLengthMm,
            maxFocalMm = spec.maxFocalLengthMm,
            minAperture = spec.minAperture,
            maxAperture = spec.maxAperture,
            focusMotorType = spec.focusMotorType,
            focusSweepSeconds = spec.focusSweepSeconds,
        )).copy(
            modelAscii = "Fringer EF-GFX (${spec.modelName})".take(63),
            isAdapter = true,
            hasApertureRing = true,
        )
        return LensControlContract.GenericSimulated(bridgedIdentity)
    }
}
