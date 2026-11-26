package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.MmioDevice

/**
 * Opt-in Synopsys DWC3 USB 3.0 device controller (`0xFFB0C100..0xFFB0C9FF`) and USB3 PHY
 * (`0xFFB23800..0xFFB23807`, `0xFFB23C00..0xFFB23C03`) register model for GFX100S 2.13.
 *
 * DAT producers/consumers in `firmware-data/gfx100s/2.13/decoded/section5.bin` (`0x021A8E00..0x021AD000`):
 * - `GCTL` (`+0x010` / `0xFFB0C110`), `GUSB2PHYCFG` (`+0x100`), `GUSB3PIPECTL` (`+0x1C0`)
 * - `GEVNTADRLO0/HI0/SIZ0/COUNT0` (`+0x300..+0x30C` / `0xFFB0C400..0xFFB0C40C`)
 * - `DCFG` (`+0x600` / `0xFFB0C700`), `DCTL` (`+0x604` / `0xFFB0C704` — bit 30 `CSFTRST` polled at
 *   `0x021A9C8C` & `0x021AA3EC`; bit 31 `RUN_STOP` drives `DSTS.DEVCTRLHLT` bit 22)
 * - `DEVTEN` (`+0x608`), `DSTS` (`+0x60C` — bits `[21:18]` `USBLNKST` checked at `0x021AA194`;
 *   bits `[2:0]` `CONNECTSPD` decoded at `0x021AA5C8`), `DGCMDPAR` (`+0x610`), `DGCMD` (`+0x614`),
 *   `DALEPENA` (`+0x620`)
 * - `DEPCMDPAR2/1/0` + `DEPCMD` (`+0x700..+0x8FF` / `0xFFB0C800..0xFFB0C9FF`, 32 physical EPs,
 *   stride `0x10` — `DEPCMD` bit 10 `CMDACT` polled in 16 busy-wait loops `0x021A91EC..0x021ACD60`)
 * - USB3 PHY indirect access (`0x021A90B0..0x021A9148`): `0xFFB23800` (bits `[12:8]` reg index,
 *   bit 16 write strobe, bits `[7:0]` data), `0xFFB23804` (PHY misc), `0xFFB23C00` (indirect readback).
 *
 * Immediate command completion (`CSFTRST`/`CMDACT` self-clear), `GSNPSID=0x5533300A`, and zero-reset
 * PHY registers are explicit functional `HYPOTHESIS` laws; physical USB wire packets remain unmodeled.
 */
internal class UsbDwc3Controller(private val vbusPresentProvider: () -> Boolean) {
    private var gctl = 0L
    private var gusb2PhyCfg = 0L
    private var gusb3PipeCtl = 0L
    private var gevntAdrLo0 = 0L
    private var gevntAdrHi0 = 0L
    private var gevntSiz0 = 0L
    private var gevntCount0 = 0L
    private var dcfg = 0L
    private var dctl = 0L
    private var devten = 0L
    private var dgcmdPar = 0L
    private var dgcmd = 0L
    private var dalepena = 0L
    private val depPar2 = LongArray(EP_COUNT)
    private val depPar1 = LongArray(EP_COUNT)
    private val depPar0 = LongArray(EP_COUNT)
    private val depCmd = LongArray(EP_COUNT)

    private var phyCtrl3800 = 0L
    private var phyMisc3804 = 0L
    private val phyRegs = LongArray(PHY_REG_COUNT)

    private var dwc3ReadCount = 0L
    private var dwc3WriteCount = 0L
    private var phyReadCount = 0L
    private var phyWriteCount = 0L

    private fun dstsWord(): Long {
        val runStop = (dctl and DCTL_RUN_STOP) != 0L
        val haltedBit = if (runStop) 0L else DSTS_DEVCTRLHLT
        // USBLNKST (bits [21:18]) = 0 (U0) so 0x021AA194 (cmp r0, #4) proceeds cleanly without retry hang.
        // CONNECTSPD (bits [2:0]): 0 = High-Speed when attached/running; 0 otherwise.
        val connectSpeed = if (vbusPresentProvider() && runStop) 0L else 0L
        return haltedBit or connectSpeed
    }

    private fun resetDeviceRegisters() {
        dcfg = 0L
        devten = 0L
        dgcmdPar = 0L
        dgcmd = 0L
        dalepena = 0L
        gevntCount0 = 0L
        depPar2.fill(0L)
        depPar1.fill(0L)
        depPar0.fill(0L)
        depCmd.fill(0L)
    }

    val dwc3Device: MmioDevice = object : MmioDevice {
        override val clockOrder = 72

        private fun checkAccess(offset: Long, size: Int) {
            require(size == 4 && offset >= 0L && offset < DWC3_WINDOW_SIZE && (offset and 3L) == 0L) {
                "soc-system-control.usb-dwc3 requires aligned 32-bit word within [0, 0x${DWC3_WINDOW_SIZE.toString(16)})"
            }
        }

        override fun read(offset: Long, size: Int): Long {
            checkAccess(offset, size)
            val result = when (offset) {
                OFF_GCTL -> gctl
                OFF_GSNPSID -> GSNPSID_VALUE
                OFF_GUSB2PHYCFG -> gusb2PhyCfg
                OFF_GUSB3PIPECTL -> gusb3PipeCtl
                OFF_GEVNTADRLO0 -> gevntAdrLo0
                OFF_GEVNTADRHI0 -> gevntAdrHi0
                OFF_GEVNTSIZ0 -> gevntSiz0
                OFF_GEVNTCOUNT0 -> gevntCount0
                OFF_DCFG -> dcfg
                OFF_DCTL -> dctl
                OFF_DEVTEN -> devten
                OFF_DSTS -> dstsWord()
                OFF_DGCMDPAR -> dgcmdPar
                OFF_DGCMD -> dgcmd
                OFF_DALEPENA -> dalepena
                in OFF_DEPCMD_BASE until OFF_DEPCMD_END -> {
                    val rel = (offset - OFF_DEPCMD_BASE).toInt()
                    val ep = rel ushr 4
                    when (rel and 0xF) {
                        0x0 -> depPar2[ep]
                        0x4 -> depPar1[ep]
                        0x8 -> depPar0[ep]
                        0xC -> depCmd[ep]
                        else -> error("Unreachable DEPCMD lane")
                    }
                }
                else -> error("soc-system-control.usb-dwc3 refused unmodeled read at +0x${offset.toString(16)}")
            }
            if (dwc3ReadCount < Long.MAX_VALUE) dwc3ReadCount++
            return result
        }

        override fun write(offset: Long, size: Int, value: Long) {
            checkAccess(offset, size)
            require(value in 0L..0xffffffffL) { "soc-system-control.usb-dwc3 write value outside u32" }
            when (offset) {
                OFF_GCTL -> gctl = value
                OFF_GSNPSID -> error("soc-system-control.usb-dwc3 GSNPSID (+0x20) is read-only")
                OFF_GUSB2PHYCFG -> gusb2PhyCfg = value
                OFF_GUSB3PIPECTL -> gusb3PipeCtl = value
                OFF_GEVNTADRLO0 -> gevntAdrLo0 = value
                OFF_GEVNTADRHI0 -> gevntAdrHi0 = value
                OFF_GEVNTSIZ0 -> gevntSiz0 = value
                OFF_GEVNTCOUNT0 -> {
                    val ackBytes = value and 0xFFFFL
                    gevntCount0 = (gevntCount0 - ackBytes).coerceAtLeast(0L)
                }
                OFF_DCFG -> dcfg = value
                OFF_DCTL -> {
                    if ((value and DCTL_CSFTRST) != 0L) {
                        resetDeviceRegisters()
                        // CSFTRST (bit 30) self-clears immediately upon completion of soft reset.
                        dctl = value and DCTL_CSFTRST.inv() and DCTL_RUN_STOP.inv()
                    } else {
                        dctl = value
                    }
                }
                OFF_DEVTEN -> devten = value
                OFF_DSTS -> error("soc-system-control.usb-dwc3 DSTS (+0x60C) is read-only")
                OFF_DGCMDPAR -> dgcmdPar = value
                OFF_DGCMD -> {
                    // CMDACT (bit 10) self-clears; CMDSTATUS (bits [15:12]) = 0 (success).
                    dgcmd = value and CMDACT_BIT.inv() and CMDSTATUS_MASK.inv()
                }
                OFF_DALEPENA -> dalepena = value
                in OFF_DEPCMD_BASE until OFF_DEPCMD_END -> {
                    val rel = (offset - OFF_DEPCMD_BASE).toInt()
                    val ep = rel ushr 4
                    when (rel and 0xF) {
                        0x0 -> depPar2[ep] = value
                        0x4 -> depPar1[ep] = value
                        0x8 -> depPar0[ep] = value
                        0xC -> {
                            // CMDACT (bit 10) self-clears; CMDSTATUS (bits [15:12]) = 0 (success).
                            var completed = value and CMDACT_BIT.inv() and CMDSTATUS_MASK.inv()
                            val cmdType = value and 0xFL
                            if (cmdType == DEPCMD_STARTTRANSFER) {
                                // Place deterministic non-zero XferRscIdx (ep+1) in bits [22:16].
                                val rscIdx = ((ep + 1) and 0x7F).toLong() shl 16
                                completed = (completed and XFER_RSC_IDX_MASK.inv()) or rscIdx
                            }
                            depCmd[ep] = completed
                        }
                        else -> error("Unreachable DEPCMD lane")
                    }
                }
                else -> error("soc-system-control.usb-dwc3 refused unmodeled write at +0x${offset.toString(16)}")
            }
            if (dwc3WriteCount < Long.MAX_VALUE) dwc3WriteCount++
        }
    }

    val phyDevice: MmioDevice = object : MmioDevice {
        override val clockOrder = 72

        private fun checkAccess(offset: Long, size: Int) {
            require(size == 4 && offset >= 0L && offset < PHY_WINDOW_SIZE && (offset and 3L) == 0L) {
                "soc-system-control.usb3-phy requires aligned 32-bit word within [0, 0x${PHY_WINDOW_SIZE.toString(16)})"
            }
        }

        override fun read(offset: Long, size: Int): Long {
            checkAccess(offset, size)
            val result = when (offset) {
                PHY_OFF_CTRL -> phyCtrl3800
                PHY_OFF_MISC -> phyMisc3804
                PHY_OFF_READBACK -> {
                    val regIdx = ((phyCtrl3800 ushr 8) and 0x1FL).toInt()
                    phyRegs[regIdx]
                }
                else -> error("soc-system-control.usb3-phy refused unmodeled read at +0x${offset.toString(16)}")
            }
            if (phyReadCount < Long.MAX_VALUE) phyReadCount++
            return result
        }

        override fun write(offset: Long, size: Int, value: Long) {
            checkAccess(offset, size)
            require(value in 0L..0xffffffffL) { "soc-system-control.usb3-phy write value outside u32" }
            when (offset) {
                PHY_OFF_CTRL -> {
                    val prevStrobe = (phyCtrl3800 and PHY_WRITE_STROBE_BIT) != 0L
                    val nextStrobe = (value and PHY_WRITE_STROBE_BIT) != 0L
                    phyCtrl3800 = value
                    if (!prevStrobe && nextStrobe) {
                        val regIdx = ((value ushr 8) and 0x1FL).toInt()
                        phyRegs[regIdx] = value and 0xFFL
                    }
                }
                PHY_OFF_MISC -> phyMisc3804 = value
                PHY_OFF_READBACK -> {
                    val regIdx = ((phyCtrl3800 ushr 8) and 0x1FL).toInt()
                    phyRegs[regIdx] = value and 0xFFL
                }
                else -> error("soc-system-control.usb3-phy refused unmodeled write at +0x${offset.toString(16)}")
            }
            if (phyWriteCount < Long.MAX_VALUE) phyWriteCount++
        }
    }

    fun state(): Map<String, Any> = linkedMapOf(
        "profile" to PROFILE,
        "gctl" to gctl,
        "gusb2PhyCfg" to gusb2PhyCfg,
        "gusb3PipeCtl" to gusb3PipeCtl,
        "gevntAdrLo0" to gevntAdrLo0,
        "gevntAdrHi0" to gevntAdrHi0,
        "gevntSiz0" to gevntSiz0,
        "gevntCount0" to gevntCount0,
        "dcfg" to dcfg,
        "dctl" to dctl,
        "devten" to devten,
        "dsts" to dstsWord(),
        "dgcmdPar" to dgcmdPar,
        "dgcmd" to dgcmd,
        "dalepena" to dalepena,
        "depCmd" to depCmd.toList(),
        "phyCtrl3800" to phyCtrl3800,
        "phyMisc3804" to phyMisc3804,
        "phyRegs" to phyRegs.toList(),
        "dwc3ReadCount" to dwc3ReadCount,
        "dwc3WriteCount" to dwc3WriteCount,
        "phyReadCount" to phyReadCount,
        "phyWriteCount" to phyWriteCount,
        "basis" to "DAT Synopsys DWC3 (0xFFB0C100..0xFFB0C9FF) and USB3 PHY (0xFFB23800..0xFFB23C03) register offsets & polling loops in section5 0x021A8E00..0x021AD000; immediate CSFTRST/CMDACT self-clear and GSNPSID are HYPOTHESIS",
    )

    fun saveSnapshot(): Map<String, Any> = linkedMapOf(
        "profile" to PROFILE,
        "gctl" to gctl,
        "gusb2PhyCfg" to gusb2PhyCfg,
        "gusb3PipeCtl" to gusb3PipeCtl,
        "gevntAdrLo0" to gevntAdrLo0,
        "gevntAdrHi0" to gevntAdrHi0,
        "gevntSiz0" to gevntSiz0,
        "gevntCount0" to gevntCount0,
        "dcfg" to dcfg,
        "dctl" to dctl,
        "devten" to devten,
        "dgcmdPar" to dgcmdPar,
        "dgcmd" to dgcmd,
        "dalepena" to dalepena,
        "depPar2" to depPar2.toList(),
        "depPar1" to depPar1.toList(),
        "depPar0" to depPar0.toList(),
        "depCmd" to depCmd.toList(),
        "phyCtrl3800" to phyCtrl3800,
        "phyMisc3804" to phyMisc3804,
        "phyRegs" to phyRegs.toList(),
        "dwc3ReadCount" to dwc3ReadCount,
        "dwc3WriteCount" to dwc3WriteCount,
        "phyReadCount" to phyReadCount,
        "phyWriteCount" to phyWriteCount,
    )

    internal class ValidatedSnapshot(
        val gctl: Long,
        val gusb2PhyCfg: Long,
        val gusb3PipeCtl: Long,
        val gevntAdrLo0: Long,
        val gevntAdrHi0: Long,
        val gevntSiz0: Long,
        val gevntCount0: Long,
        val dcfg: Long,
        val dctl: Long,
        val devten: Long,
        val dgcmdPar: Long,
        val dgcmd: Long,
        val dalepena: Long,
        val depPar2: LongArray,
        val depPar1: LongArray,
        val depPar0: LongArray,
        val depCmd: LongArray,
        val phyCtrl3800: Long,
        val phyMisc3804: Long,
        val phyRegs: LongArray,
        val dwc3ReadCount: Long,
        val dwc3WriteCount: Long,
        val phyReadCount: Long,
        val phyWriteCount: Long,
    )

    fun validateSnapshot(raw: Any?): ValidatedSnapshot {
        val m = raw as? Map<*, *> ?: throw IllegalArgumentException("Snapshot usbDwc3State requires map")
        require(m.keys == SNAPSHOT_KEYS) { "Unexpected usbDwc3State snapshot keys" }
        require(m["profile"] == PROFILE) { "Unsupported usbDwc3State snapshot profile" }
        fun u32(key: String): Long {
            val v = m[key]
            require(v is Byte || v is Short || v is Int || v is Long) { "Snapshot usbDwc3State.$key requires integer" }
            val n = (v as Number).toLong()
            require(n in 0L..0xffffffffL) { "Snapshot usbDwc3State.$key out of u32 range" }
            return n
        }
        fun cnt(key: String): Long {
            val v = m[key]
            require(v is Byte || v is Short || v is Int || v is Long) { "Snapshot usbDwc3State.$key requires integer" }
            val n = (v as Number).toLong()
            require(n in 0L..Long.MAX_VALUE) { "Snapshot usbDwc3State.$key out of range" }
            return n
        }
        fun u32Array(key: String, size: Int, maxVal: Long = 0xffffffffL): LongArray {
            val list = m[key] as? List<*> ?: throw IllegalArgumentException("Snapshot usbDwc3State.$key requires list")
            require(list.size == size) { "Snapshot usbDwc3State.$key requires $size entries" }
            return LongArray(size) { idx ->
                val v = list[idx]
                require(v is Byte || v is Short || v is Int || v is Long) { "Snapshot usbDwc3State.$key[$idx] requires integer" }
                val n = (v as Number).toLong()
                require(n in 0L..maxVal) { "Snapshot usbDwc3State.$key[$idx] out of range" }
                n
            }
        }
        val nextDctl = u32("dctl")
        require((nextDctl and DCTL_CSFTRST) == 0L) { "Snapshot usbDwc3State.dctl cannot retain pending CSFTRST" }
        val nextDgcmd = u32("dgcmd")
        require((nextDgcmd and CMDACT_BIT) == 0L) { "Snapshot usbDwc3State.dgcmd cannot retain pending CMDACT" }
        val nextDepCmd = u32Array("depCmd", EP_COUNT)
        for (ep in 0 until EP_COUNT) {
            require((nextDepCmd[ep] and CMDACT_BIT) == 0L) { "Snapshot usbDwc3State.depCmd[$ep] cannot retain pending CMDACT" }
        }
        return ValidatedSnapshot(
            gctl = u32("gctl"),
            gusb2PhyCfg = u32("gusb2PhyCfg"),
            gusb3PipeCtl = u32("gusb3PipeCtl"),
            gevntAdrLo0 = u32("gevntAdrLo0"),
            gevntAdrHi0 = u32("gevntAdrHi0"),
            gevntSiz0 = u32("gevntSiz0"),
            gevntCount0 = u32("gevntCount0"),
            dcfg = u32("dcfg"),
            dctl = nextDctl,
            devten = u32("devten"),
            dgcmdPar = u32("dgcmdPar"),
            dgcmd = nextDgcmd,
            dalepena = u32("dalepena"),
            depPar2 = u32Array("depPar2", EP_COUNT),
            depPar1 = u32Array("depPar1", EP_COUNT),
            depPar0 = u32Array("depPar0", EP_COUNT),
            depCmd = nextDepCmd,
            phyCtrl3800 = u32("phyCtrl3800"),
            phyMisc3804 = u32("phyMisc3804"),
            phyRegs = u32Array("phyRegs", PHY_REG_COUNT, 0xFFL),
            dwc3ReadCount = cnt("dwc3ReadCount"),
            dwc3WriteCount = cnt("dwc3WriteCount"),
            phyReadCount = cnt("phyReadCount"),
            phyWriteCount = cnt("phyWriteCount"),
        )
    }

    fun applySnapshot(s: ValidatedSnapshot) {
        gctl = s.gctl
        gusb2PhyCfg = s.gusb2PhyCfg
        gusb3PipeCtl = s.gusb3PipeCtl
        gevntAdrLo0 = s.gevntAdrLo0
        gevntAdrHi0 = s.gevntAdrHi0
        gevntSiz0 = s.gevntSiz0
        gevntCount0 = s.gevntCount0
        dcfg = s.dcfg
        dctl = s.dctl
        devten = s.devten
        dgcmdPar = s.dgcmdPar
        dgcmd = s.dgcmd
        dalepena = s.dalepena
        s.depPar2.copyInto(depPar2)
        s.depPar1.copyInto(depPar1)
        s.depPar0.copyInto(depPar0)
        s.depCmd.copyInto(depCmd)
        phyCtrl3800 = s.phyCtrl3800
        phyMisc3804 = s.phyMisc3804
        s.phyRegs.copyInto(phyRegs)
        dwc3ReadCount = s.dwc3ReadCount
        dwc3WriteCount = s.dwc3WriteCount
        phyReadCount = s.phyReadCount
        phyWriteCount = s.phyWriteCount
    }

    fun resetToDefault() {
        gctl = 0L
        gusb2PhyCfg = 0L
        gusb3PipeCtl = 0L
        gevntAdrLo0 = 0L
        gevntAdrHi0 = 0L
        gevntSiz0 = 0L
        gevntCount0 = 0L
        dctl = 0L
        resetDeviceRegisters()
        phyCtrl3800 = 0L
        phyMisc3804 = 0L
        phyRegs.fill(0L)
        dwc3ReadCount = 0L
        dwc3WriteCount = 0L
        phyReadCount = 0L
        phyWriteCount = 0L
    }

    companion object {
        const val PROFILE = "retained-banks-usb-dwc3-experimental-v2"
        const val STATUS_A200_V3_PORT = "soc-system-control.status-a200-v3"
        const val USB_VBUS_V2_PORT = "soc-system-control.usb-vbus-a300-v2"
        const val DWC3_PORT = "soc-system-control.usb-dwc3"
        const val PHY_PORT = "soc-system-control.usb3-phy"

        const val DWC3_WINDOW_SIZE = 0x900L // 0xFFB0C100..0xFFB0C9FF
        const val PHY_WINDOW_SIZE = 0x404L // 0xFFB23800..0xFFB23C03
        const val EP_COUNT = 32
        const val PHY_REG_COUNT = 32

        const val OFF_GCTL = 0x010L
        const val OFF_GSNPSID = 0x020L
        const val OFF_GUSB2PHYCFG = 0x100L
        const val OFF_GUSB3PIPECTL = 0x1C0L
        const val OFF_GEVNTADRLO0 = 0x300L
        const val OFF_GEVNTADRHI0 = 0x304L
        const val OFF_GEVNTSIZ0 = 0x308L
        const val OFF_GEVNTCOUNT0 = 0x30CL
        const val OFF_DCFG = 0x600L
        const val OFF_DCTL = 0x604L
        const val OFF_DEVTEN = 0x608L
        const val OFF_DSTS = 0x60CL
        const val OFF_DGCMDPAR = 0x610L
        const val OFF_DGCMD = 0x614L
        const val OFF_DALEPENA = 0x620L
        const val OFF_DEPCMD_BASE = 0x700L
        const val OFF_DEPCMD_END = 0x900L

        const val PHY_OFF_CTRL = 0x000L
        const val PHY_OFF_MISC = 0x004L
        const val PHY_OFF_READBACK = 0x400L

        const val GSNPSID_VALUE = 0x5533300AL
        const val DCTL_CSFTRST = 1L shl 30
        const val DCTL_RUN_STOP = 1L shl 31
        const val DSTS_DEVCTRLHLT = 1L shl 22
        const val CMDACT_BIT = 1L shl 10
        const val CMDSTATUS_MASK = 0xFL shl 12
        const val XFER_RSC_IDX_MASK = 0x7FL shl 16
        const val DEPCMD_STARTTRANSFER = 6L
        const val PHY_WRITE_STROBE_BIT = 1L shl 16

        private val SNAPSHOT_KEYS = setOf(
            "profile", "gctl", "gusb2PhyCfg", "gusb3PipeCtl",
            "gevntAdrLo0", "gevntAdrHi0", "gevntSiz0", "gevntCount0",
            "dcfg", "dctl", "devten", "dgcmdPar", "dgcmd", "dalepena",
            "depPar2", "depPar1", "depPar0", "depCmd",
            "phyCtrl3800", "phyMisc3804", "phyRegs",
            "dwc3ReadCount", "dwc3WriteCount", "phyReadCount", "phyWriteCount",
        )
    }
}
