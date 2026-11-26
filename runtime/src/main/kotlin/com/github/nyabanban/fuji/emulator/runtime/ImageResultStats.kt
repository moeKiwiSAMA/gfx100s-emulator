package com.github.nyabanban.fuji.emulator.runtime

/**
 * Explicit read-only zero discovery support for the ISP result stats SRAM windows read by T80
 * (`0x15E2` @ `0251C084..0251C258`, `02362670(0x21..0x24)` -> `0132962C` -> `012BA054` memcpy):
 * - Ping-pong buffer 0 (`0xFFF10000 + 0x6000..0x6FFF`): `[0xFFF16000, 0xFFF17000)` (4 KiB)
 * - Ping-pong buffer 1 (`0xFFF17000 + 0x6000..0x6FFF`): `[0xFFF1D000, 0xFFF1E000)` (4 KiB)
 *
 * Read-only, aligned 4-byte reads return explicit zero words (`HYPOTHESIS`).
 */
internal object ImageResultStats {
    const val KEY = "imageResultStats"
    const val PROFILE = "fff16000-fff1d000-zero-v1"
    const val BANK1_PROFILE = "fff16000-fff1d000-bank1-zero-v1"
    val PROFILES = setOf(PROFILE, BANK1_PROFILE)
    const val REGION_BUF0 = "diagnostic.image-result-stats.fff16000"
    const val REGION_BUF1 = "diagnostic.image-result-stats.fff1d000"
    const val REGION_BANK0_HEAD = "diagnostic.image-result-sram.fff10000"
    const val REGION_BANK0_MID = "diagnostic.image-result-sram.fff129e0"
    const val REGION_BANK1_FULL = "diagnostic.image-result-sram.fff17000"
    const val BASE_BUF0 = 0xfff16000L
    const val BASE_BUF1 = 0xfff1d000L
    const val BASE_BANK0_HEAD = 0xfff10000L
    const val SIZE_BANK0_HEAD = 0x1060
    const val BASE_BANK0_MID = 0xfff129e0L
    const val SIZE_BANK0_MID = 0x3620
    const val BASE_BANK1_FULL = 0xfff17000L
    const val SIZE_BANK1_FULL = 0x6000
    const val SIZE = 0x1000
    val REGION_IDS = setOf(REGION_BUF0, REGION_BUF1, REGION_BANK0_HEAD, REGION_BANK0_MID, REGION_BANK1_FULL)

    fun regions(selection: Any): List<DiscoveryRegion> {
        require(selection in PROFILES) { "Unknown $KEY selection: $selection" }
        val base = listOf(
            DiscoveryRegion(
                id = REGION_BUF0,
                deviceId = "section5-0251c084",
                base = BASE_BUF0,
                length = SIZE,
                strategy = DiscoveryStrategy.FIXED,
                widths = setOf(1, 2, 4),
                alignment = 1,
                littleEndian = true,
                fixedValue = 0L,
                evidence = listOf(
                    "DAT section5 0251C084..0251C258 & 02362670(0x21..0x24): T80 0x15E2 reads 4x0x400 B stats from ping-pong buffer 0 [0xFFF16000, 0xFFF17000)",
                    "HYPOTHESIS $selection: read-only zero stats window",
                ),
            ),
            DiscoveryRegion(
                id = REGION_BUF1,
                deviceId = "section5-0251c084",
                base = BASE_BUF1,
                length = SIZE,
                strategy = DiscoveryStrategy.FIXED,
                widths = setOf(1, 2, 4),
                alignment = 1,
                littleEndian = true,
                fixedValue = 0L,
                evidence = listOf(
                    "DAT section5 0251C084..0251C258 & 02364B64..02364B8C: T80 0x15E2 reads 4x0x400 B stats from ping-pong buffer 1 [0xFFF1D000, 0xFFF1E000)",
                    "HYPOTHESIS $selection: read-only zero stats window",
                ),
            ),
        )
        if (selection == PROFILE) return base
        return base + listOf(
            DiscoveryRegion(
                id = REGION_BANK0_HEAD,
                deviceId = "section5-02371990",
                base = BASE_BANK0_HEAD,
                length = SIZE_BANK0_HEAD,
                strategy = DiscoveryStrategy.FIXED,
                widths = setOf(1, 2, 4),
                alignment = 1,
                littleEndian = true,
                fixedValue = 0L,
                evidence = listOf(
                    "DAT section4 012BA054 memcpy: T75/T80 ISP ping-pong bank 0 stats head [0xFFF10000, 0xFFF11060) (e.g. 0xFFF10400)",
                    "HYPOTHESIS $selection: read-only zero ISP stats SRAM window",
                ),
            ),
            DiscoveryRegion(
                id = REGION_BANK0_MID,
                deviceId = "section5-02371990",
                base = BASE_BANK0_MID,
                length = SIZE_BANK0_MID,
                strategy = DiscoveryStrategy.FIXED,
                widths = setOf(1, 2, 4),
                alignment = 1,
                littleEndian = true,
                fixedValue = 0L,
                evidence = listOf(
                    "DAT section5 0237193C..023719A8: T75/T80 ISP ping-pong bank 0 stats tail [0xFFF129E0, 0xFFF16000)",
                    "HYPOTHESIS $selection: read-only zero ISP stats SRAM window",
                ),
            ),
            DiscoveryRegion(
                id = REGION_BANK1_FULL,
                deviceId = "section5-02371990",
                base = BASE_BANK1_FULL,
                length = SIZE_BANK1_FULL,
                strategy = DiscoveryStrategy.FIXED,
                widths = setOf(1, 2, 4),
                alignment = 1,
                littleEndian = true,
                fixedValue = 0L,
                evidence = listOf(
                    "DAT section5 0237193C..023719A8 (lr 0129D2F0): T75/T80 ISP ping-pong bank 1 result surface & stats [0xFFF17000, 0xFFF1D000) (+0x7000 mirror of bank 0)",
                    "HYPOTHESIS $selection: read-only zero ping-pong bank 1 ISP stats SRAM window",
                ),
            ),
        )
    }
}
