package com.github.nyabanban.fuji.emulator.components.lens.filmsim

/**
 * Monochrome optical color filter channel weighting (`section4` `BKUP_FILM_SIM_BW_FILTER_*` /
 * `BKUP_FILM_SIM_ACROS_FILTER_*`).
 */
enum class MonochromeFilter(val code: Int, val label: String) {
    NONE(0, "None"),
    YELLOW(1, "Ye"),
    RED(2, "R"),
    GREEN(3, "G"),
}

/**
 * Complete catalog of FUJIFILM GFX100S 2.13 Film Simulations (19 still modes + 2 video log/HDR modes).
 *
 * Firmware indices are verified (`DAT`) from GFX100S 2.13 static analysis:
 * - `bkupId`: `section4` `BKUP_FILM_SIM_*` enum printer (`0x01068B60..0x01068D60`).
 * - `reccCode`: `section5` `[r0+0x5A]` RECC color-science selector (`0x021A5630`).
 * - `profileId`: `section5` `[r0+0x51]` 15-slot internal color profile (`0x0218FC70`, `0..14`).
 * - `videoLogMode`: `section5` `[r0+0x142]` override (`0 = standard`, `1 = F-Log`, `2 = HLG`).
 */
enum class FilmSimulation(
    val displayName: String,
    val firmwareSymbol: String,
    val bkupId: Int,
    val reccCode: Int,
    val profileId: Int,
    val isMonochrome: Boolean = false,
    val isSepia: Boolean = false,
    val monochromeFilter: MonochromeFilter = MonochromeFilter.NONE,
    val videoLogMode: Int = 0,
) {
    PROVIA(
        displayName = "PROVIA / Standard",
        firmwareSymbol = "BKUP_FILM_SIM_STD",
        bkupId = 0,
        reccCode = 0,
        profileId = 0,
    ),
    VELVIA(
        displayName = "Velvia / Vivid",
        firmwareSymbol = "BKUP_FILM_SIM_F2",
        bkupId = 4,
        reccCode = 4,
        profileId = 1,
    ),
    ASTIA(
        displayName = "ASTIA / Soft",
        firmwareSymbol = "BKUP_FILM_SIM_F1B",
        bkupId = 2,
        reccCode = 5,
        profileId = 2,
    ),
    CLASSIC_CHROME(
        displayName = "CLASSIC CHROME",
        firmwareSymbol = "BKUP_FILM_SIM_CLASSIC_CHROME",
        bkupId = 14,
        reccCode = 8,
        profileId = 5,
    ),
    PRO_NEG_HI(
        displayName = "PRO Neg. Hi",
        firmwareSymbol = "BKUP_FILM_SIM_NEGA_COL_1",
        bkupId = 12,
        reccCode = 6,
        profileId = 3,
    ),
    PRO_NEG_STD(
        displayName = "PRO Neg. Std",
        firmwareSymbol = "BKUP_FILM_SIM_NEGA_COL_2",
        bkupId = 13,
        reccCode = 7,
        profileId = 4,
    ),
    CLASSIC_NEG(
        displayName = "Classic Neg.",
        firmwareSymbol = "BKUP_FILM_SIM_SUPERIA",
        bkupId = 15,
        reccCode = 11,
        profileId = 8,
    ),
    NOSTALGIC_NEG(
        displayName = "Nostalgic Neg.",
        firmwareSymbol = "BKUP_FILM_SIM_AMERICAN_NEW_COLOR",
        bkupId = 16,
        reccCode = 12,
        profileId = 9,
    ),
    ETERNA(
        displayName = "ETERNA / Cinema",
        firmwareSymbol = "BKUP_FILM_SIM_ETERNA",
        bkupId = 17,
        reccCode = 9,
        profileId = 6,
    ),
    ETERNA_BLEACH_BYPASS(
        displayName = "ETERNA Bleach Bypass",
        firmwareSymbol = "BKUP_FILM_SIM_BLEACH_BYPASS",
        bkupId = 18,
        reccCode = 10,
        profileId = 7,
    ),
    ACROS(
        displayName = "ACROS",
        firmwareSymbol = "BKUP_FILM_SIM_ACROS_FILTER_NON",
        bkupId = 20,
        reccCode = 16,
        profileId = 12,
        isMonochrome = true,
        monochromeFilter = MonochromeFilter.NONE,
    ),
    ACROS_YE(
        displayName = "ACROS + Ye Filter",
        firmwareSymbol = "BKUP_FILM_SIM_ACROS_FILTER_Y",
        bkupId = 22,
        reccCode = 17,
        profileId = 12,
        isMonochrome = true,
        monochromeFilter = MonochromeFilter.YELLOW,
    ),
    ACROS_R(
        displayName = "ACROS + R Filter",
        firmwareSymbol = "BKUP_FILM_SIM_ACROS_FILTER_R",
        bkupId = 21,
        reccCode = 18,
        profileId = 12,
        isMonochrome = true,
        monochromeFilter = MonochromeFilter.RED,
    ),
    ACROS_G(
        displayName = "ACROS + G Filter",
        firmwareSymbol = "BKUP_FILM_SIM_ACROS_FILTER_G",
        bkupId = 23,
        reccCode = 19,
        profileId = 12,
        isMonochrome = true,
        monochromeFilter = MonochromeFilter.GREEN,
    ),
    MONOCHROME(
        displayName = "Monochrome",
        firmwareSymbol = "BKUP_FILM_SIM_BW_FILTER_NON",
        bkupId = 8,
        reccCode = 2,
        profileId = 10,
        isMonochrome = true,
        monochromeFilter = MonochromeFilter.NONE,
    ),
    MONOCHROME_YE(
        displayName = "Monochrome + Ye Filter",
        firmwareSymbol = "BKUP_FILM_SIM_BW_FILTER_Y",
        bkupId = 10,
        reccCode = 2,
        profileId = 10,
        isMonochrome = true,
        monochromeFilter = MonochromeFilter.YELLOW,
    ),
    MONOCHROME_R(
        displayName = "Monochrome + R Filter",
        firmwareSymbol = "BKUP_FILM_SIM_BW_FILTER_R",
        bkupId = 9,
        reccCode = 2,
        profileId = 10,
        isMonochrome = true,
        monochromeFilter = MonochromeFilter.RED,
    ),
    MONOCHROME_G(
        displayName = "Monochrome + G Filter",
        firmwareSymbol = "BKUP_FILM_SIM_BW_FILTER_G",
        bkupId = 11,
        reccCode = 2,
        profileId = 10,
        isMonochrome = true,
        monochromeFilter = MonochromeFilter.GREEN,
    ),
    SEPIA(
        displayName = "Sepia",
        firmwareSymbol = "BKUP_FILM_SIM_SEPIA",
        bkupId = 6,
        reccCode = 3,
        profileId = 11,
        isMonochrome = true,
        isSepia = true,
    ),
    F_LOG(
        displayName = "F-Log",
        firmwareSymbol = "BKUP_DOTLCD_POWOFF_FILM_SIM_FLOG",
        bkupId = -1,
        reccCode = 0,
        profileId = 13,
        videoLogMode = 1,
    ),
    HLG(
        displayName = "HLG",
        firmwareSymbol = "BKUP_DOTLCD_POWOFF_FILM_SIM_HLG",
        bkupId = -1,
        reccCode = 0,
        profileId = 14,
        videoLogMode = 2,
    ),
    ;

    companion object {
        /** The 19 user-facing GFX100S still Film Simulation modes. */
        val ALL_STILL_19: List<FilmSimulation> = entries.filter { it.videoLogMode == 0 }

        /** All 21 modes (19 still Film Simulations + F-Log + HLG). */
        val ALL_21: List<FilmSimulation> = entries.toList()
    }
}
