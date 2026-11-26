package com.github.nyabanban.fuji.emulator.runtime

import java.util.Arrays

/**
 * Explicit opt-in behavioral discovery support for the GFX100S 2.13 Boot ROM cold-start
 * register frontier leading through real NOR Flash Stage-1 IPL (`0xFFF10000`) and Stage-2 XIP
 * loader (`0xF0007360`) into `boot.bin` (`0x00000000`).
 *
 * Profile `bootrom-stage1-handoff-v1` (5 regions) covers the Boot ROM 30-entry init table and
 * handoff status mailbox (`boot-rom.bin` `0xFFF0041C..0xFFF00488`, `0xFFF00250..0xFFF0025C`):
 * - `0xFFF41500` (4 B): `INV-GPIO-GLOBAL-FFF41500` (16-bit masked write).
 * - `0xFFF48180` (4 B): retained 32-bit system-control word (`bank-00[0x20]`).
 * - `0xFF505200..0xFF50525C` (24 x 4 B = 96 B): DRAM-0 QoS/pre-init words (`bank-00[0x38..0xF8)`).
 * - `0xFF505384` (4 B): DRAM-0 pre-init commit trigger (`WRITE4 1` then `POLL_EQ == 0`).
 * - `0xFFF4A100` (4 B): boot status mailbox (`0x900D` = normal handoff, `0x0BAD` = halt).
 *
 * Profile `bootrom-stage1-ddr-init-v2` (10 regions) extends `v1` with the bounded Stage-1 IPL
 * DRAM PHY0/CTL0/fabric register set verified by static disassembly of `bank-00_00000000.bin`:
 * - `0xFB000000` (4 B): Stage-1 pre-DDR flag (`0xFFF11A0C` writes `0x1`).
 * - `0xFF507000..0xFF507C44`: 31 exact DRAM CTL0 offsets (`0xFFF10A04`/`0xFFF10B2C`).
 * - `0xFF602000..0xFF60475C` (hole `0xFF60235C..0xFF604000`): 686 exact DRAM PHY0 offsets
 *   (`0xF0002E5C` table, `0xFFF10A04`/`0xFFF10B2C`/`0xFFF10778`).
 * - `0xFF400000..0xFF402C94`: 392 exact DDR bus-arbiter offsets (`0xFF400010` + 23 blocks x 17 words).
 * - `0xFF480000..0xFF483008`: 8 exact DDR bus-bridge offsets (`0xFF48{0,1,2,3}00{0,4}`).
 *
 * All unlisted offsets within those regions fail closed. Behavioral responses are explicit
 * `HYPOTHESIS` models and are never active in `strict` mode or by default in `discovery` mode.
 */
internal class BootRomColdStart private constructor(
    val profile: String,
    private var gpioGlobal: Long,
    private var sysCtrl8180: Long,
    private val dram0PreInit: LongArray,
    private var dram0CommitPending: Boolean,
    private var dram0CommitCount: Long,
    private var bootStatus: Long,
    private val secondaryBootStatus: LongArray,
    private var stage1FlagFb000000: Long,
    private var dramInitStage: Int,
    private var dramCtl0AckPending: Boolean,
    private val dramCtl0Words: LongArray,
    private val dramPhy0Words: LongArray,
    private val ddrArbiterWords: LongArray,
    private val ddrBridgeWords: LongArray,
) {
    constructor(profile: String = PROFILE) : this(
        profile = profile.also { require(it in PROFILES) { "Unknown $KEY selection: $it" } },
        gpioGlobal = 0L,
        sysCtrl8180 = 0L,
        dram0PreInit = LongArray(DRAM0_PREINIT_WORDS),
        dram0CommitPending = false,
        dram0CommitCount = 0L,
        bootStatus = 0L,
        secondaryBootStatus = LongArray(SECONDARY_BOOT_STATUS_WORDS),
        stage1FlagFb000000 = 0L,
        dramInitStage = 0,
        dramCtl0AckPending = false,
        dramCtl0Words = LongArray(DRAM0_CTL0_ADDRESSES.size),
        dramPhy0Words = LongArray(DRAM0_PHY0_WORD_COUNT),
        ddrArbiterWords = LongArray(DDR_ARBITER_ADDRESSES.size),
        ddrBridgeWords = LongArray(DDR_BRIDGE_ADDRESSES.size),
    )

    fun access(address: Long, write: Boolean, value: Long): Triple<Long, Long, Long>? {
        return when {
            address == GPIO_GLOBAL_BASE -> {
                val prior = gpioGlobal
                if (write) {
                    val mask = (value ushr 16) and 0xffffL
                    val next = (prior and mask.inv()) or (value and mask)
                    gpioGlobal = next
                    Triple(value, prior, next)
                } else {
                    Triple(prior, prior, prior)
                }
            }
            address == SYSCTRL_8180_BASE -> {
                val prior = sysCtrl8180
                if (write) {
                    sysCtrl8180 = value
                    Triple(value, prior, value)
                } else {
                    Triple(prior, prior, prior)
                }
            }
            address in DRAM0_PREINIT_BASE until (DRAM0_PREINIT_BASE + DRAM0_PREINIT_SIZE) -> {
                if (profile == RELEASE_COLDSTART_PROFILE) return null
                val slot = ((address - DRAM0_PREINIT_BASE) / 4L).toInt()
                val prior = dram0PreInit[slot]
                if (write) {
                    dram0PreInit[slot] = value
                    Triple(value, prior, value)
                } else {
                    Triple(prior, prior, prior)
                }
            }
            address == DRAM0_COMMIT_BASE -> {
                if (profile == RELEASE_COLDSTART_PROFILE) return null
                val prior = if (dram0CommitPending) 1L else 0L
                if (write) {
                    if (value != 0L && value != 1L) return null
                    dram0CommitPending = (value == 1L)
                    Triple(value, prior, value)
                } else {
                    if (dram0CommitPending) {
                        dram0CommitPending = false
                        dram0CommitCount++
                    }
                    Triple(0L, prior, 0L)
                }
            }
            address == BOOT_STATUS_BASE -> {
                val prior = bootStatus
                if (write) {
                    bootStatus = value
                    Triple(value, prior, value)
                } else {
                    Triple(prior, prior, prior)
                }
            }
            profile in SECONDARY_MAILBOX_PROFILES &&
                address in SECONDARY_BOOT_STATUS_BASE until (SECONDARY_BOOT_STATUS_BASE + SECONDARY_BOOT_STATUS_SIZE) -> {
                val slot = ((address - SECONDARY_BOOT_STATUS_BASE) / 4L).toInt()
                val prior = secondaryBootStatus[slot]
                if (write) {
                    secondaryBootStatus[slot] = value
                    Triple(value, prior, value)
                } else {
                    Triple(prior, prior, prior)
                }
            }
            profile !in STAGE1_DDR_PROFILES -> null
            address == STAGE1_FLAG_BASE -> {
                val prior = stage1FlagFb000000
                if (write) {
                    stage1FlagFb000000 = value
                    Triple(value, prior, value)
                } else {
                    Triple(prior, prior, prior)
                }
            }
            address in DRAM0_CTL0_BASE until (DRAM0_CTL0_BASE + DRAM0_CTL0_SIZE) ->
                if (profile == RELEASE_COLDSTART_PROFILE) null else accessCtl0(address, write, value)
            address in DRAM0_PHY0_BASE until (DRAM0_PHY0_BASE + DRAM0_PHY0_SIZE) ->
                if (profile == RELEASE_COLDSTART_PROFILE) null else accessPhy0(address, write, value)
            address in DDR_ARBITER_BASE until (DDR_ARBITER_BASE + DDR_ARBITER_SIZE) -> {
                val slot = Arrays.binarySearch(DDR_ARBITER_ADDRESSES, address)
                if (slot < 0) return null
                val prior = ddrArbiterWords[slot]
                if (write) {
                    ddrArbiterWords[slot] = value
                    Triple(value, prior, value)
                } else {
                    Triple(prior, prior, prior)
                }
            }
            address in DDR_BRIDGE_BASE until (DDR_BRIDGE_BASE + DDR_BRIDGE_SIZE) -> {
                val slot = Arrays.binarySearch(DDR_BRIDGE_ADDRESSES, address)
                if (slot < 0) return null
                val prior = ddrBridgeWords[slot]
                if (write) {
                    ddrBridgeWords[slot] = value
                    Triple(value, prior, value)
                } else {
                    Triple(prior, prior, prior)
                }
            }
            else -> null
        }
    }

    private fun observableCtl0Word(address: Long, slot: Int): Long = when (address) {
        0xff507c18L -> if (dramInitStage >= 1) 1L else dramCtl0Words[slot]
        0xff507044L -> 0L
        0xff507364L -> if (dramCtl0AckPending) 1L else 0L
        else -> dramCtl0Words[slot]
    }

    private fun accessCtl0(address: Long, write: Boolean, value: Long): Triple<Long, Long, Long>? {
        val slot = Arrays.binarySearch(DRAM0_CTL0_ADDRESSES, address)
        if (slot < 0) return null
        val prior = observableCtl0Word(address, slot)
        if (write) {
            dramCtl0Words[slot] = value
            when (address) {
                0xff507c18L -> if ((value and 0x40000000L) != 0L && dramInitStage < 1) dramInitStage = 1
                0xff507040L -> if (dramInitStage >= 1 && (value == 0x3L || value == 0x10L) && dramInitStage < 2) {
                    dramInitStage = 2
                }
                0xff507360L -> if (dramInitStage >= 3 && value == 0xff00c00dL) {
                    dramInitStage = 4
                    dramCtl0AckPending = true
                }
                0xff507364L -> if ((value and 1L) != 0L) {
                    dramCtl0AckPending = false
                }
            }
            return Triple(value, prior, observableCtl0Word(address, slot))
        }
        return Triple(prior, prior, prior)
    }

    private fun phy0Slot(address: Long): Int = when {
        address in DRAM0_PHY0_LO_BASE until (DRAM0_PHY0_LO_BASE + DRAM0_PHY0_LO_WORDS * 4L) ->
            ((address - DRAM0_PHY0_LO_BASE) / 4L).toInt()
        address in DRAM0_PHY0_HI_BASE until (DRAM0_PHY0_HI_BASE + DRAM0_PHY0_HI_WORDS * 4L) ->
            DRAM0_PHY0_LO_WORDS + ((address - DRAM0_PHY0_HI_BASE) / 4L).toInt()
        else -> -1
    }

    private fun observablePhy0Word(address: Long, slot: Int): Long {
        val stored = dramPhy0Words[slot]
        return when (address) {
            0xff604670L -> if (dramInitStage >= 1) stored or 0x00030000L else stored
            0xff604674L, 0xff604678L -> if (dramInitStage >= 1) stored or 0x00030003L else stored
            0xff602110L -> if (dramInitStage >= 3) stored or 0x00000001L else stored
            0xff60444cL, 0xff604620L -> if (dramInitStage >= 3) stored or 0x00010001L else stored
            0xff6040dcL, 0xff6042dcL -> if (dramInitStage >= 3) (stored and 0x1000L.inv()) or 0x00000300L else stored
            else -> stored
        }
    }

    private fun accessPhy0(address: Long, write: Boolean, value: Long): Triple<Long, Long, Long>? {
        val slot = phy0Slot(address)
        if (slot < 0) return null
        val prior = observablePhy0Word(address, slot)
        if (write) {
            dramPhy0Words[slot] = value
            if (address == 0xff602000L && dramInitStage >= 2 && (value and 1L) != 0L && dramInitStage < 3) {
                dramInitStage = 3
            }
            return Triple(value, prior, observablePhy0Word(address, slot))
        }
        return Triple(prior, prior, prior)
    }

    fun receipt(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "profile" to profile,
        "confidence" to "HYPOTHESIS",
        "gpioGlobalFff41500" to gpioGlobal,
        "sysCtrlFff48180" to sysCtrl8180,
        "dram0PreInitNonZeroCount" to dram0PreInit.count { it != 0L },
        "dram0CommitPending" to dram0CommitPending,
        "dram0CommitCount" to dram0CommitCount,
        "bootStatusFff4a100" to bootStatus,
    ).apply {
        if (profile in STAGE1_DDR_PROFILES) {
            put("stage1FlagFb000000", stage1FlagFb000000)
            put("dramInitStage", dramInitStage)
            put("dramCtl0AckPending", dramCtl0AckPending)
            put("dramCtl0NonZeroCount", dramCtl0Words.count { it != 0L })
            put("dramPhy0NonZeroCount", dramPhy0Words.count { it != 0L })
            put("ddrArbiterNonZeroCount", ddrArbiterWords.count { it != 0L })
            put("ddrBridgeNonZeroCount", ddrBridgeWords.count { it != 0L })
        }
        if (profile in SECONDARY_MAILBOX_PROFILES) {
            put("secondaryBootStatusFff4a104", secondaryBootStatus.toList())
        }
    }

    fun snapshot(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "profile" to profile,
        "confidence" to "HYPOTHESIS",
        "gpioGlobalFff41500" to gpioGlobal,
        "sysCtrlFff48180" to sysCtrl8180,
        "dram0PreInitWords" to dram0PreInit.toList(),
        "dram0CommitPending" to dram0CommitPending,
        "dram0CommitCount" to dram0CommitCount,
        "bootStatusFff4a100" to bootStatus,
    ).apply {
        if (profile in STAGE1_DDR_PROFILES) {
            put("stage1FlagFb000000", stage1FlagFb000000)
            put("dramInitStage", dramInitStage)
            put("dramCtl0AckPending", dramCtl0AckPending)
            put("dramCtl0Words", dramCtl0Words.toList())
            put("dramPhy0Words", dramPhy0Words.toList())
            put("ddrArbiterWords", ddrArbiterWords.toList())
            put("ddrBridgeWords", ddrBridgeWords.toList())
        }
        if (profile in SECONDARY_MAILBOX_PROFILES) {
            put("secondaryBootStatusWords", secondaryBootStatus.toList())
        }
    }

    companion object {
        const val KEY = "bootRomColdStart"
        const val PROFILE = "bootrom-stage1-handoff-v1"
        const val STAGE1_DDR_PROFILE = "bootrom-stage1-ddr-init-v2"
        const val QSPI_KERNEL_PROFILE = "bootrom-qspi-kernel-v3"
        /**
         * Release-compatible Boot ROM cold-start profile: exposes ONLY the 7 regions outside
         * `soc-dram` bank 0 (`0xFF500000`/`0xFF600000`) so `soc-dram` (`coldStartChannel0=true`)
         * can own UMC0/PHY0 cleanly across both Stage-1 IPL and runtime `section4.bin`.
         */
        const val RELEASE_COLDSTART_PROFILE = "bootrom-release-coldstart-v4"
        val STAGE1_DDR_PROFILES = setOf(STAGE1_DDR_PROFILE, QSPI_KERNEL_PROFILE, RELEASE_COLDSTART_PROFILE)
        val SECONDARY_MAILBOX_PROFILES = setOf(QSPI_KERNEL_PROFILE, RELEASE_COLDSTART_PROFILE)
        val PROFILES = setOf(PROFILE, STAGE1_DDR_PROFILE, QSPI_KERNEL_PROFILE, RELEASE_COLDSTART_PROFILE)

        const val GPIO_GLOBAL_BASE = 0xfff41500L
        const val SYSCTRL_8180_BASE = 0xfff48180L
        const val DRAM0_PREINIT_BASE = 0xff505200L
        const val DRAM0_PREINIT_WORDS = 24
        const val DRAM0_PREINIT_SIZE = DRAM0_PREINIT_WORDS * 4
        const val DRAM0_COMMIT_BASE = 0xff505384L
        const val BOOT_STATUS_BASE = 0xfff4a100L
        const val SECONDARY_BOOT_STATUS_BASE = 0xfff4a104L
        const val SECONDARY_BOOT_STATUS_WORDS = 3
        const val SECONDARY_BOOT_STATUS_SIZE = SECONDARY_BOOT_STATUS_WORDS * 4

        const val STAGE1_FLAG_BASE = 0xfb000000L
        const val DRAM0_CTL0_BASE = 0xff507000L
        const val DRAM0_CTL0_SIZE = 0xc44
        const val DRAM0_PHY0_BASE = 0xff602000L
        const val DRAM0_PHY0_SIZE = 0x275c
        const val DRAM0_PHY0_LO_BASE = 0xff602000L
        const val DRAM0_PHY0_LO_WORDS = 215
        const val DRAM0_PHY0_HI_BASE = 0xff604000L
        const val DRAM0_PHY0_HI_WORDS = 471
        const val DRAM0_PHY0_WORD_COUNT = DRAM0_PHY0_LO_WORDS + DRAM0_PHY0_HI_WORDS
        const val DDR_ARBITER_BASE = 0xff400000L
        const val DDR_ARBITER_SIZE = 0x2c94
        const val DDR_BRIDGE_BASE = 0xff480000L
        const val DDR_BRIDGE_SIZE = 0x3008

        const val STAGE2_XIP_BASE = 0xf0007000L
        const val STAGE2_XIP_SIZE = 0x1000L

        const val REGION_GPIO_GLOBAL = "diagnostic.bootrom.gpio-global-fff41500"
        const val REGION_SYSCTRL_8180 = "diagnostic.bootrom.sysctrl-fff48180"
        const val REGION_DRAM0_PREINIT = "diagnostic.bootrom.dram0-preinit-ff505200"
        const val REGION_DRAM0_COMMIT = "diagnostic.bootrom.dram0-commit-ff505384"
        const val REGION_BOOT_STATUS = "diagnostic.bootrom.status-fff4a100"

        const val REGION_STAGE1_FLAG = "diagnostic.bootrom.stage1-flag-fb000000"
        const val REGION_DRAM0_CTL0 = "diagnostic.bootrom.dram0-ctl0-ff507000"
        const val REGION_DRAM0_PHY0 = "diagnostic.bootrom.dram0-phy0-ff602000"
        const val REGION_DDR_ARBITER = "diagnostic.bootrom.ddr-arbiter-ff400000"
        const val REGION_DDR_BRIDGE = "diagnostic.bootrom.ddr-bridge-ff480000"
        const val REGION_SECONDARY_BOOT_STATUS = "diagnostic.bootrom.secondary-status-fff4a104"

        const val PRIMARY_REGION = REGION_GPIO_GLOBAL

        val REGION_IDS = setOf(
            REGION_GPIO_GLOBAL,
            REGION_SYSCTRL_8180,
            REGION_DRAM0_PREINIT,
            REGION_DRAM0_COMMIT,
            REGION_BOOT_STATUS,
        )

        val STAGE1_DDR_EXTRA_REGION_IDS = setOf(
            REGION_STAGE1_FLAG,
            REGION_DRAM0_CTL0,
            REGION_DRAM0_PHY0,
            REGION_DDR_ARBITER,
            REGION_DDR_BRIDGE,
        )

        val STAGE1_DDR_REGION_IDS = REGION_IDS + STAGE1_DDR_EXTRA_REGION_IDS
        val QSPI_KERNEL_EXTRA_REGION_IDS = setOf(REGION_SECONDARY_BOOT_STATUS)
        val QSPI_KERNEL_REGION_IDS = STAGE1_DDR_REGION_IDS + QSPI_KERNEL_EXTRA_REGION_IDS
        val RELEASE_COLDSTART_REGION_IDS = setOf(
            REGION_GPIO_GLOBAL,
            REGION_SYSCTRL_8180,
            REGION_BOOT_STATUS,
            REGION_SECONDARY_BOOT_STATUS,
            REGION_STAGE1_FLAG,
            REGION_DDR_ARBITER,
            REGION_DDR_BRIDGE,
        )
        val ALL_REGION_IDS = QSPI_KERNEL_REGION_IDS

        fun regionIdsFor(profile: String): Set<String> = when (profile) {
            PROFILE -> REGION_IDS
            STAGE1_DDR_PROFILE -> STAGE1_DDR_REGION_IDS
            QSPI_KERNEL_PROFILE -> QSPI_KERNEL_REGION_IDS
            RELEASE_COLDSTART_PROFILE -> RELEASE_COLDSTART_REGION_IDS
            else -> throw IllegalArgumentException("Unknown $KEY selection: $profile")
        }

        private val DRAM0_PHY0_PROHIBITED = listOf(AddressInterval(0xff60235cL, 0xff604000L))

        private val DRAM0_CTL0_ADDRESSES: LongArray = longArrayOf(
            0x7000, 0x7004, 0x7008, 0x700c, 0x7030, 0x7040, 0x7044, 0x7050,
            0x7058, 0x7064, 0x7068, 0x7084, 0x7100, 0x7104, 0x7108, 0x7200,
            0x7204, 0x7210, 0x7214, 0x7240, 0x7360, 0x7364, 0x7600, 0x7604,
            0x7624, 0x7690, 0x7698, 0x7c18, 0x7c20, 0x7c30, 0x7c40,
        ).map { 0xff500000L + it }.toLongArray()

        private val DDR_ARBITER_BLOCK_OFFSETS: IntArray = intArrayOf(
            0x04, 0x20, 0x40, 0x44, 0x48, 0x4c, 0x50,
            0x60, 0x64, 0x68, 0x6c, 0x70, 0x80, 0x84, 0x88, 0x8c, 0x90,
        )

        private val DDR_ARBITER_ADDRESSES: LongArray = buildList {
            add(DDR_ARBITER_BASE + 0x10L)
            for (block in 0 until 23) {
                val blockBase = DDR_ARBITER_BASE + block * 0x200L
                for (off in DDR_ARBITER_BLOCK_OFFSETS) {
                    add(blockBase + off)
                }
            }
        }.sorted().toLongArray()

        private val DDR_BRIDGE_ADDRESSES: LongArray = longArrayOf(
            0xff480000L, 0xff480004L, 0xff481000L, 0xff481004L,
            0xff482000L, 0xff482004L, 0xff483000L, 0xff483004L,
        )

        private data class RegionSpec(
            val id: String,
            val base: Long,
            val size: Int,
            val prohibited: List<AddressInterval> = emptyList(),
        )

        private val SPECS = listOf(
            RegionSpec(REGION_STAGE1_FLAG, STAGE1_FLAG_BASE, 4),
            RegionSpec(REGION_DDR_ARBITER, DDR_ARBITER_BASE, DDR_ARBITER_SIZE),
            RegionSpec(REGION_DDR_BRIDGE, DDR_BRIDGE_BASE, DDR_BRIDGE_SIZE),
            RegionSpec(REGION_DRAM0_PREINIT, DRAM0_PREINIT_BASE, DRAM0_PREINIT_SIZE),
            RegionSpec(REGION_DRAM0_COMMIT, DRAM0_COMMIT_BASE, 4),
            RegionSpec(REGION_DRAM0_CTL0, DRAM0_CTL0_BASE, DRAM0_CTL0_SIZE),
            RegionSpec(REGION_DRAM0_PHY0, DRAM0_PHY0_BASE, DRAM0_PHY0_SIZE, DRAM0_PHY0_PROHIBITED),
            RegionSpec(REGION_GPIO_GLOBAL, GPIO_GLOBAL_BASE, 4),
            RegionSpec(REGION_SYSCTRL_8180, SYSCTRL_8180_BASE, 4),
            RegionSpec(REGION_BOOT_STATUS, BOOT_STATUS_BASE, 4),
            RegionSpec(REGION_SECONDARY_BOOT_STATUS, SECONDARY_BOOT_STATUS_BASE, SECONDARY_BOOT_STATUS_SIZE),
        )

        fun regions(selection: Any): List<DiscoveryRegion> {
            val profile = selection as? String
            require(profile in PROFILES) { "Unknown $KEY selection: $selection" }
            val baseRegions = listOf(
                DiscoveryRegion(
                    id = REGION_DRAM0_PREINIT,
                    deviceId = "bootrom-0xfff0041c",
                    base = DRAM0_PREINIT_BASE,
                    length = DRAM0_PREINIT_SIZE,
                    strategy = DiscoveryStrategy.BOOTROM_COLD_START,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    evidence = listOf(
                        "FILE/DAT bank-00_00000000.bin[0x38..0xF8) entries 4..27 & boot-rom.bin 0xFFF00464: 24 aligned 32-bit writes to 0xFF505200..0xFF50525C",
                        "HYPOTHESIS $profile: retained 24x32-bit DRAM-0 pre-init words",
                    ),
                ),
                DiscoveryRegion(
                    id = REGION_DRAM0_COMMIT,
                    deviceId = "bootrom-0xfff0041c",
                    base = DRAM0_COMMIT_BASE,
                    length = 4,
                    strategy = DiscoveryStrategy.BOOTROM_COLD_START,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    evidence = listOf(
                        "FILE/DAT bank-00_00000000.bin[0xF8..0x108) entries 28..29 & boot-rom.bin 0xFFF00464/0xFFF00474: WRITE4 0xFF505384=1 then POLL_EQ 0xFF505384==0",
                        "HYPOTHESIS $profile: write 0/1 accepted; poll read after write 1 returns 0 and clears pending state",
                    ),
                ),
                DiscoveryRegion(
                    id = REGION_GPIO_GLOBAL,
                    deviceId = "INV-GPIO-GLOBAL-FFF41500",
                    base = GPIO_GLOBAL_BASE,
                    length = 4,
                    strategy = DiscoveryStrategy.BOOTROM_COLD_START,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    evidence = listOf(
                        "FILE/DAT bank-00_00000000.bin[0x18] entry 0 (WRITE4 0xFFF41500=0xE3F4E3F4) & SOURCE INV-GPIO-GLOBAL-FFF41500 (0x011DA708/0x011DA73C)",
                        "HYPOTHESIS $profile: 16-bit masked-write register (upper half write mask, lower half data)",
                    ),
                ),
                DiscoveryRegion(
                    id = REGION_SYSCTRL_8180,
                    deviceId = "bootrom-0xfff0041c",
                    base = SYSCTRL_8180_BASE,
                    length = 4,
                    strategy = DiscoveryStrategy.BOOTROM_COLD_START,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    evidence = listOf(
                        "FILE/DAT bank-00_00000000.bin[0x20] entry 1 & boot-rom.bin 0xFFF00464: WRITE4 0xFFF48180=0x00000005",
                        "HYPOTHESIS $profile: retained 32-bit system-control word",
                    ),
                ),
                DiscoveryRegion(
                    id = REGION_BOOT_STATUS,
                    deviceId = "bootrom-0xfff00250",
                    base = BOOT_STATUS_BASE,
                    length = 4,
                    strategy = DiscoveryStrategy.BOOTROM_COLD_START,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    evidence = listOf(
                        "FILE boot-rom.bin 0xFFF00250..0xFFF0025C (WRITE4 0xFFF4A100=0x900D before LDR PC,=0xFFF10000) & 0xFFF00318..0xFFF00320 (0x0BAD halt)",
                        "HYPOTHESIS $profile: retained 32-bit boot status mailbox word",
                    ),
                ),
            )
            if (profile == PROFILE) return baseRegions
            val combined = baseRegions + listOf(
                DiscoveryRegion(
                    id = REGION_STAGE1_FLAG,
                    deviceId = "stage1-0xfff11a04",
                    base = STAGE1_FLAG_BASE,
                    length = 4,
                    strategy = DiscoveryStrategy.BOOTROM_COLD_START,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    evidence = listOf(
                        "FILE/DAT bank-00_00000000.bin Stage-1 IPL 0xFFF100AC->0xFFF11A0C: WRITE4 0xFB000000=0x1 before DDR init",
                        "HYPOTHESIS $profile: retained 32-bit Stage-1 pre-DDR flag word",
                    ),
                ),
                DiscoveryRegion(
                    id = REGION_DDR_ARBITER,
                    deviceId = "stage1-0xfff10f10",
                    base = DDR_ARBITER_BASE,
                    length = DDR_ARBITER_SIZE,
                    strategy = DiscoveryStrategy.BOOTROM_COLD_START,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    evidence = listOf(
                        "FILE/DAT bank-00_00000000.bin Stage-1 IPL 0xFFF10F10 & 0xFFF10138 (tables 0xF0002D10 x 0xF0002C84): 0xFF400010 + 23 blocks x 17 arbiter words",
                        "HYPOTHESIS $profile: retained 32-bit writes on exact 392 Stage-1 arbiter offsets; unlisted offsets refuse",
                    ),
                ),
                DiscoveryRegion(
                    id = REGION_DDR_BRIDGE,
                    deviceId = "stage1-0xfff10f10",
                    base = DDR_BRIDGE_BASE,
                    length = DDR_BRIDGE_SIZE,
                    strategy = DiscoveryStrategy.BOOTROM_COLD_START,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    evidence = listOf(
                        "FILE/DAT bank-00_00000000.bin Stage-1 IPL 0xFFF10F10 (table 0xF0002DF0): 8 DDR bridge words 0xFF48{0,1,2,3}00{0,4}",
                        "HYPOTHESIS $profile: retained 32-bit writes on exact 8 bridge offsets; unlisted offsets refuse",
                    ),
                ),
                DiscoveryRegion(
                    id = REGION_DRAM0_CTL0,
                    deviceId = "stage1-0xfff10b2c",
                    base = DRAM0_CTL0_BASE,
                    length = DRAM0_CTL0_SIZE,
                    strategy = DiscoveryStrategy.BOOTROM_COLD_START,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    evidence = listOf(
                        "FILE/DAT bank-00_00000000.bin Stage-1 IPL 0xFFF10A04/0xFFF10B2C (tables 0xF000724C/0xF00071C0/0xF0007184/0xF00072A8/0xF0007288): 31 DRAM CTL0 words",
                        "HYPOTHESIS $profile: 5-stage CTL0 init state machine (0x7C18 ready, 0x7040/0x7044 mode commands, 0x7360/0x7364 ACK); unlisted offsets refuse",
                    ),
                ),
                DiscoveryRegion(
                    id = REGION_DRAM0_PHY0,
                    deviceId = "stage1-0xfff10a04",
                    base = DRAM0_PHY0_BASE,
                    length = DRAM0_PHY0_SIZE,
                    strategy = DiscoveryStrategy.BOOTROM_COLD_START,
                    widths = setOf(4),
                    alignment = 4,
                    littleEndian = true,
                    prohibited = DRAM0_PHY0_PROHIBITED,
                    evidence = listOf(
                        "FILE/DAT bank-00_00000000.bin Stage-1 IPL 0xFFF10A04/0xFFF10B2C/0xFFF10778 (table 0xF0002E5C): 686 DRAM PHY0 words (0x2000..0x2358, 0x4000..0x4758)",
                        "HYPOTHESIS $profile: retained PHY0 words + PLL lock (0x4670..0x4678) after 0x7C18=0x40000000 + training/calibration status (0x2110/0x444C/0x4620/0x40DC/0x42DC) after 0x2000 bit 0",
                    ),
                ),
            ) + if (profile in SECONDARY_MAILBOX_PROFILES) {
                listOf(
                    DiscoveryRegion(
                        id = REGION_SECONDARY_BOOT_STATUS,
                        deviceId = "bootrom-0xfff002bc",
                        base = SECONDARY_BOOT_STATUS_BASE,
                        length = SECONDARY_BOOT_STATUS_SIZE,
                        strategy = DiscoveryStrategy.BOOTROM_COLD_START,
                        widths = setOf(4),
                        alignment = 4,
                        littleEndian = true,
                        evidence = listOf(
                            "FILE boot-rom.bin 0xFFF002BC..0xFFF002E4: CPU1..CPU3 write 0x1000900D/0x2000900D/0x3000900D to 0xFFF4A104/0xFFF4A108/0xFFF4A10C after SGI 0 WFI wakeup before LDR PC,=0x0",
                            "HYPOTHESIS $profile: retained 3x32-bit secondary-core boot status mailbox words",
                        ),
                    ),
                )
            } else {
                emptyList()
            }
            return if (profile == RELEASE_COLDSTART_PROFILE) {
                combined.filter { it.id in RELEASE_COLDSTART_REGION_IDS }
            } else {
                combined
            }
        }

        fun validRegion(region: DiscoveryRegion): Boolean =
            SPECS.any { spec ->
                region.id == spec.id && region.base == spec.base && region.length == spec.size &&
                    region.widths == setOf(4) && region.alignment == 4 && region.littleEndian &&
                    region.prohibited == spec.prohibited && region.initial == null &&
                    region.samples == null && region.fixedValue == null && region.retainedDiagnostic == null
            }

        fun restore(
            raw: Any?,
            targetProfile: String = PROFILE,
            allowPromotion: Boolean = false,
        ): BootRomColdStart {
            require(targetProfile in PROFILES) { "Unknown $KEY target profile: $targetProfile" }
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Missing $KEY snapshot state")
            val savedProfile = map["profile"] as? String ?: throw IllegalArgumentException("Invalid $KEY profile")
            require(savedProfile in PROFILES && map["confidence"] == "HYPOTHESIS") {
                "Invalid $KEY snapshot header"
            }
            require(
                savedProfile == targetProfile ||
                    (allowPromotion && (
                        (savedProfile == PROFILE && targetProfile in STAGE1_DDR_PROFILES) ||
                            (savedProfile == STAGE1_DDR_PROFILE && targetProfile == QSPI_KERNEL_PROFILE)
                        )),
            ) {
                "Incompatible $KEY profile: saved=$savedProfile target=$targetProfile"
            }
            val baseKeys = setOf(
                "profile", "confidence", "gpioGlobalFff41500", "sysCtrlFff48180",
                "dram0PreInitWords", "dram0CommitPending", "dram0CommitCount", "bootStatusFff4a100",
            )
            val v2Keys = baseKeys + setOf(
                "stage1FlagFb000000", "dramInitStage", "dramCtl0AckPending",
                "dramCtl0Words", "dramPhy0Words", "ddrArbiterWords", "ddrBridgeWords",
            )
            val v3Keys = v2Keys + setOf("secondaryBootStatusWords")
            val expectedKeys = when (savedProfile) {
                QSPI_KERNEL_PROFILE, RELEASE_COLDSTART_PROFILE -> v3Keys
                STAGE1_DDR_PROFILE -> v2Keys
                else -> baseKeys
            }
            require(map.keys == expectedKeys) { "Invalid $KEY snapshot keys" }

            fun u32(field: String, max: Long = 0xffffffffL): Long {
                val v = map[field]
                require(v is Byte || v is Short || v is Int || v is Long) { "Invalid $KEY field $field" }
                val n = (v as Number).toLong()
                require(n in 0L..max) { "Out-of-range $KEY field $field" }
                return n
            }

            fun u32Array(field: String, expectedSize: Int): LongArray {
                val rawWords = map[field] as? List<*>
                    ?: throw IllegalArgumentException("Invalid $KEY $field")
                require(rawWords.size == expectedSize) { "Invalid $KEY $field length" }
                return LongArray(expectedSize) { idx ->
                    val v = rawWords[idx]
                    require(v is Byte || v is Short || v is Int || v is Long) { "Invalid $KEY $field[$idx]" }
                    (v as Number).toLong().also {
                        require(it in 0L..0xffffffffL) { "Out-of-range $KEY $field[$idx]" }
                    }
                }
            }

            val gpio = u32("gpioGlobalFff41500", 0xffffL)
            val sys8180 = u32("sysCtrlFff48180")
            val preInitWords = u32Array("dram0PreInitWords", DRAM0_PREINIT_WORDS)
            val pending = map["dram0CommitPending"] as? Boolean
                ?: throw IllegalArgumentException("Invalid $KEY dram0CommitPending")
            val commits = u32("dram0CommitCount", Long.MAX_VALUE)
            val status = u32("bootStatusFff4a100")

            val stage1Flag: Long
            val initStage: Int
            val ctl0Ack: Boolean
            val ctl0Words: LongArray
            val phy0Words: LongArray
            val arbiterWords: LongArray
            val bridgeWords: LongArray
            val secondaryStatus: LongArray
            if (savedProfile in STAGE1_DDR_PROFILES) {
                stage1Flag = u32("stage1FlagFb000000")
                initStage = u32("dramInitStage", 4L).toInt()
                ctl0Ack = map["dramCtl0AckPending"] as? Boolean
                    ?: throw IllegalArgumentException("Invalid $KEY dramCtl0AckPending")
                ctl0Words = u32Array("dramCtl0Words", DRAM0_CTL0_ADDRESSES.size)
                phy0Words = u32Array("dramPhy0Words", DRAM0_PHY0_WORD_COUNT)
                arbiterWords = u32Array("ddrArbiterWords", DDR_ARBITER_ADDRESSES.size)
                bridgeWords = u32Array("ddrBridgeWords", DDR_BRIDGE_ADDRESSES.size)
                secondaryStatus = if (savedProfile in SECONDARY_MAILBOX_PROFILES) {
                    u32Array("secondaryBootStatusWords", SECONDARY_BOOT_STATUS_WORDS)
                } else {
                    LongArray(SECONDARY_BOOT_STATUS_WORDS)
                }
            } else {
                stage1Flag = 0L
                initStage = 0
                ctl0Ack = false
                ctl0Words = LongArray(DRAM0_CTL0_ADDRESSES.size)
                phy0Words = LongArray(DRAM0_PHY0_WORD_COUNT)
                arbiterWords = LongArray(DDR_ARBITER_ADDRESSES.size)
                bridgeWords = LongArray(DDR_BRIDGE_ADDRESSES.size)
                secondaryStatus = LongArray(SECONDARY_BOOT_STATUS_WORDS)
            }
            return BootRomColdStart(
                profile = targetProfile,
                gpioGlobal = gpio,
                sysCtrl8180 = sys8180,
                dram0PreInit = preInitWords,
                dram0CommitPending = pending,
                dram0CommitCount = commits,
                bootStatus = status,
                secondaryBootStatus = secondaryStatus,
                stage1FlagFb000000 = stage1Flag,
                dramInitStage = initStage,
                dramCtl0AckPending = ctl0Ack,
                dramCtl0Words = ctl0Words,
                dramPhy0Words = phy0Words,
                ddrArbiterWords = arbiterWords,
                ddrBridgeWords = bridgeWords,
            )
        }
    }
}
