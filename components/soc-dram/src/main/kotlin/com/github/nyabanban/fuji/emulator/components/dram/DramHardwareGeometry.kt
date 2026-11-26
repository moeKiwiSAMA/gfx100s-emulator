package com.github.nyabanban.fuji.emulator.components.dram

/**
 * Decoded physical DRAM hardware geometry and timing metadata for the FUJIFILM GFX100S (SPX4).
 *
 * Provenance summary (BootROM & LPDDR4 clock-tree analysis):
 * - `DAT` / `FILE`: Quad-channel (`UMC0..3` at `0xFF50..0xFF53`, `PHY0..3` at `0xFF60..0xFF63`),
 *   dual-rank (`CS0` + `CS1`), `16-bit/ch` (`64-bit` aggregate), `DRAM_CLASS = 0xB` (LPDDR4),
 *   `2 GiB/channel` = `8.0 GiB` (`8,192 MiB`) total across `PA 0x00_0000_0000..0x01_FFFF_FFFF`,
 *   23-master x 9-window DDR fabric address decoder (`0xFF400000`), power-gate masks (`0xFF70F038`),
 *   reset-gate masks (`0xFFF48334`), and per-channel thermal sensor addresses (`0xFFF47820..0xFFF4782C`).
 * - `HYPOTHESIS`: JEDEC JESD209-4 speed-grade fit `LPDDR4-2400` (`1,200 MHz` CK / `600 MHz` DFI 1:2 /
 *   `2,400 MT/s` / `19.2 GB/s` peak aggregate bandwidth), uniquely satisfying all 8 decoded UMC
 *   timing registers simultaneously (`tRFCab = 168 DFI = 280.00 ns`, `tREFI = 2339 DFI = 3898.33 ns`).
 * - `UNKNOWN`: Physical LPDDR4 DRAM package vendor (MR5/MR6/MR7 not logged in firmware dump).
 */
object DramHardwareGeometry {
    const val STANDARD: String = "LPDDR4"
    const val DRAM_CLASS_CODE: Int = 0xB
    const val SPEED_GRADE_HYPOTHESIS: String = "LPDDR4-2400"
    const val CONTROLLER_IP: String = "Socionext UMC"
    const val PHY_IP: String = "Cadence Denali LPDDR4 PI/PHY"

    const val CHANNEL_COUNT: Int = 4
    const val RANKS_PER_CHANNEL: Int = 2
    const val CHANNEL_DATA_WIDTH_BITS: Int = 16
    const val AGGREGATE_DATA_WIDTH_BITS: Int = CHANNEL_COUNT * CHANNEL_DATA_WIDTH_BITS

    const val BYTES_PER_RANK: Long = 1L shl 30
    const val BYTES_PER_CHANNEL: Long = RANKS_PER_CHANNEL * BYTES_PER_RANK
    const val TOTAL_CAPACITY_BYTES: Long = CHANNEL_COUNT * BYTES_PER_CHANNEL
    const val TOTAL_CAPACITY_MIB: Int = 8192
    const val TOTAL_CAPACITY_GIB: Int = 8

    const val CK_FREQUENCY_MHZ_HYPOTHESIS: Int = 1200
    const val DFI_FREQUENCY_MHZ_HYPOTHESIS: Int = 600
    const val DATA_RATE_MT_S_HYPOTHESIS: Int = 2400
    const val PEAK_BANDWIDTH_GB_S_HYPOTHESIS: Double = 19.2
    const val TRFC_AB_DFI_CYCLES: Int = 168
    const val TRFC_AB_NS_HYPOTHESIS: Double = 280.0
    const val TREFI_DFI_CYCLES: Int = 2339
    const val TREFI_NS_HYPOTHESIS: Double = 3898.33
    val PI_FSP_TRAS_CYCLES: List<Int> = listOf(3, 17, 51)

    const val POWER_GATE_REG: Long = 0xFF70_F038L
    const val RESET_GATE_REG: Long = 0xFFF4_8334L
    const val ARBITER_BASE: Long = 0xFF40_0000L
    const val ARBITER_MASTER_COUNT: Int = 23
    const val ARBITER_WINDOWS_PER_MASTER: Int = 9

    data class ChannelDescriptor(
        val channel: Int,
        val umcBase: Long,
        val phyBase: Long,
        val physicalAddressStart: Long,
        val physicalAddressEnd: Long,
        val capacityBytes: Long,
        val ranks: Int,
        val dataWidthBits: Int,
        val powerGateMask: Long,
        val resetGateMask: Long,
        val thermalSensorReg: Long,
        val bringUpStage: String,
    ) {
        fun toStateMap(): Map<String, Any> = linkedMapOf(
            "channel" to channel,
            "umcBase" to umcBase,
            "phyBase" to phyBase,
            "physicalAddressStart" to physicalAddressStart,
            "physicalAddressEnd" to physicalAddressEnd,
            "capacityBytes" to capacityBytes,
            "ranks" to ranks,
            "dataWidthBits" to dataWidthBits,
            "powerGateMask" to powerGateMask,
            "resetGateMask" to resetGateMask,
            "thermalSensorReg" to thermalSensorReg,
            "bringUpStage" to bringUpStage,
        )
    }

    val CHANNELS: List<ChannelDescriptor> = listOf(
        ChannelDescriptor(
            channel = 0,
            umcBase = 0xFF50_0000L,
            phyBase = 0xFF60_0000L,
            physicalAddressStart = 0x00_0000_0000L,
            physicalAddressEnd = 0x00_7FFF_FFFFL,
            capacityBytes = BYTES_PER_CHANNEL,
            ranks = RANKS_PER_CHANNEL,
            dataWidthBits = CHANNEL_DATA_WIDTH_BITS,
            powerGateMask = 0x0001FL,
            resetGateMask = 0x01L,
            thermalSensorReg = 0xFFF4_7820L,
            bringUpStage = "stage1-ipl",
        ),
        ChannelDescriptor(
            channel = 1,
            umcBase = 0xFF51_0000L,
            phyBase = 0xFF61_0000L,
            physicalAddressStart = 0x00_8000_0000L,
            physicalAddressEnd = 0x00_FFFF_FFFFL,
            capacityBytes = BYTES_PER_CHANNEL,
            ranks = RANKS_PER_CHANNEL,
            dataWidthBits = CHANNEL_DATA_WIDTH_BITS,
            powerGateMask = 0x003E0L,
            resetGateMask = 0x02L,
            thermalSensorReg = 0xFFF4_7824L,
            bringUpStage = "section4-runtime",
        ),
        ChannelDescriptor(
            channel = 2,
            umcBase = 0xFF52_0000L,
            phyBase = 0xFF62_0000L,
            physicalAddressStart = 0x01_0000_0000L,
            physicalAddressEnd = 0x01_7FFF_FFFFL,
            capacityBytes = BYTES_PER_CHANNEL,
            ranks = RANKS_PER_CHANNEL,
            dataWidthBits = CHANNEL_DATA_WIDTH_BITS,
            powerGateMask = 0x07C00L,
            resetGateMask = 0x44L,
            thermalSensorReg = 0xFFF4_7828L,
            bringUpStage = "section4-runtime",
        ),
        ChannelDescriptor(
            channel = 3,
            umcBase = 0xFF53_0000L,
            phyBase = 0xFF63_0000L,
            physicalAddressStart = 0x01_8000_0000L,
            physicalAddressEnd = 0x01_FFFF_FFFFL,
            capacityBytes = BYTES_PER_CHANNEL,
            ranks = RANKS_PER_CHANNEL,
            dataWidthBits = CHANNEL_DATA_WIDTH_BITS,
            powerGateMask = 0xF8000L,
            resetGateMask = 0x88L,
            thermalSensorReg = 0xFFF4_782CL,
            bringUpStage = "section4-runtime",
        ),
    )

    fun channel(index: Int): ChannelDescriptor {
        require(index in CHANNELS.indices) { "DRAM channel index out of range: $index" }
        return CHANNELS[index]
    }

    fun toStateMap(): Map<String, Any> = linkedMapOf(
        "standard" to STANDARD,
        "dramClassCode" to DRAM_CLASS_CODE,
        "speedGrade" to SPEED_GRADE_HYPOTHESIS,
        "controllerIp" to CONTROLLER_IP,
        "phyIp" to PHY_IP,
        "channels" to CHANNEL_COUNT,
        "ranksPerChannel" to RANKS_PER_CHANNEL,
        "channelDataWidthBits" to CHANNEL_DATA_WIDTH_BITS,
        "aggregateDataWidthBits" to AGGREGATE_DATA_WIDTH_BITS,
        "bytesPerRank" to BYTES_PER_RANK,
        "bytesPerChannel" to BYTES_PER_CHANNEL,
        "totalCapacityBytes" to TOTAL_CAPACITY_BYTES,
        "totalCapacityMiB" to TOTAL_CAPACITY_MIB,
        "totalCapacityGiB" to TOTAL_CAPACITY_GIB,
        "ckFrequencyMhz" to CK_FREQUENCY_MHZ_HYPOTHESIS,
        "dfiFrequencyMhz" to DFI_FREQUENCY_MHZ_HYPOTHESIS,
        "dataRateMtPerSec" to DATA_RATE_MT_S_HYPOTHESIS,
        "peakBandwidthGbPerSec" to PEAK_BANDWIDTH_GB_S_HYPOTHESIS,
        "tRfcAbDfiCycles" to TRFC_AB_DFI_CYCLES,
        "tRfcAbNs" to TRFC_AB_NS_HYPOTHESIS,
        "tRefiDfiCycles" to TREFI_DFI_CYCLES,
        "tRefiNs" to TREFI_NS_HYPOTHESIS,
        "piFspTrasCycles" to PI_FSP_TRAS_CYCLES,
        "powerGateReg" to POWER_GATE_REG,
        "resetGateReg" to RESET_GATE_REG,
        "arbiterBase" to ARBITER_BASE,
        "arbiterMasterCount" to ARBITER_MASTER_COUNT,
        "arbiterWindowsPerMaster" to ARBITER_WINDOWS_PER_MASTER,
        "channelTopology" to CHANNELS.map { it.toStateMap() },
        "geometryConfidence" to "DAT",
        "speedGradeConfidence" to "HYPOTHESIS",
        "packageVendor" to "UNKNOWN",
    )
}
