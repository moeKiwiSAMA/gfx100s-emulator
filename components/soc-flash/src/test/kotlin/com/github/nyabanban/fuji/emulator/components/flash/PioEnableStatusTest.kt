package com.github.nyabanban.fuji.emulator.components.flash

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class PioEnableStatusTest {
    private class Context(media: Boolean = true) : ComponentContext {
        var tick = 0L
        var imageReads = 0
        val registrations = linkedMapOf<String, Any>()
        val lines = mutableListOf<Pair<Int, Boolean>>()
        override val directory = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) { check(registrations.putIfAbsent(name, port) == null) }
            override fun <T : Any> find(name: String, type: Class<T>): T? = registrations[name]?.let { type.cast(it) }
            override fun removeOwner(owner: String) {}
        }
        override fun dataPath(relative: String): Path = error("no data path expected")
        init {
            if (media) registrations["test.image"] = object : ReadOnlyImagePort {
                override val length = 1048576L
                override val sha256 = "0".repeat(64)
                override fun check(offset: Long, size: Long) { require(offset >= 0 && size > 0 && offset + size <= length) }
                override fun read(offset: Long, destination: ByteBuffer) { imageReads++; error("PIO latch must not read array") }
            }
            registrations["test.irq"] = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { lines.add(irq to high) } }
        }
    }
    private fun config(profile: String = FlashFactory.ENABLE_PROFILE, irq: String = "test.irq", jedec: Any = FlashFactory.ENABLE_JEDEC) =
        mapOf("profile" to profile, "jedec" to jedec, "imagePort" to "test.image", "interruptPort" to irq, "irq" to 37L)
    private inner class Fixture(media: Boolean = true, profile: String = FlashFactory.ENABLE_PROFILE, irq: String = "test.irq") {
        val context = Context(media)
        val hardware = FlashFactory().create(context, config(profile, irq))
        val port = context.ports.find("soc-flash.registers", MmioDevice::class.java)!!
        val scheduled get() = port as ScheduledMmioDevice
        fun write(at: Int, value: Long) = port.write(at.toLong(), 4, value)
        fun read(at: Int) = port.read(at.toLong(), 4)
        fun advance(tick: Long) { context.tick = tick; port.beforeInstruction(tick) }
        fun latch(): List<*> = (hardware.state()["pioEnableStatus"] as Map<*,*>)["writeEnabledBySelector"] as List<*>
    }
    @Test fun completionOnlyLatchRetainsTxRxAndIndependentSelectorsWithExactAndLateDeadline() {
        val f = Fixture()
        f.context.tick = 10; f.write(0x90, 0x9f000001); f.advance(18); assertEquals(0x20L, f.read(0xa0))
        f.context.tick = 20; f.write(0x90, 0x06000001); assertEquals(28L, f.scheduled.nextDeadline()); assertEquals(listOf(false,false), f.latch())
        f.advance(27); assertEquals(listOf(false,false), f.latch()); assertEquals(2L, f.read(0x90) and 2); assertEquals(0x20L, f.read(0xa0))
        val pending = f.hardware.state()
        assertFails { f.write(0, 1L shl 10) }; assertFails { f.write(0, 1L shl 30) }; assertFails { f.write(0x90, 0x05800001) }; assertEquals(pending, f.hardware.state())
        f.advance(28); assertNull(f.scheduled.nextDeadline()); assertEquals(listOf(true,false), f.latch()); assertEquals(0x20L, f.read(0xa0))
        f.context.tick = 30; f.write(0x90, 0x05800001); f.advance(38); assertEquals(2L, f.read(0xa0))
        f.context.tick = 40; f.write(0x90, 0x05800001); f.advance(55); assertEquals(2L, f.read(0xa0))
        f.write(0, 1L shl 10); f.context.tick = 60; f.write(0x90, 0x05800001); f.advance(68); assertEquals(0L, f.read(0xa0)); assertEquals(listOf(true,false), f.latch())
        f.context.tick = 70; f.write(0x90, 0x06000001); f.advance(78); assertEquals(listOf(true,true), f.latch()); assertEquals(0, f.context.imageReads)
    }
    @Test fun oldReadonlyAndMissingMediaRefuseEnableWithoutBusyOrLatchMutation() {
        for (f in listOf(Fixture(profile="dat-readonly-experimental-v1"), Fixture(media=false))) {
            f.write(0x90, 0x06000000); val before = f.hardware.state()
            assertFails { f.write(0x90, 0x06000001) }; assertEquals(before, f.hardware.state()); assertNull(f.scheduled.nextDeadline())
            if (before["profile"] == "dat-readonly-experimental-v1") assertFalse("pioEnableStatus" in before)
        }
    }
    @Test fun malformedCommandsPackedModeAndSelectorsRefuseBeforeMutation() {
        for (word in listOf(0x06800001L,0x06001001L,0x06000003L,0x05000001L,0x05900001L,0x04000001L)) {
            val f=Fixture();val before=f.hardware.state();assertFails { f.write(0x90,word) };assertEquals(before,f.hardware.state())
        }
        for (control in listOf(1L shl 30,2L shl 10,15L shl 10)) {
            val f=Fixture();f.write(0,control);val before=f.hardware.state();assertFails { f.write(0x90,0x06000001) };assertEquals(before,f.hardware.state())
        }
    }
    @Test fun deadlineOverflowHappensBeforeStatusOrControllerMutation() {
        val f=Fixture();f.context.tick=Long.MAX_VALUE-7;val before=f.hardware.state()
        assertFails { f.write(0x90,0x06000001) };assertEquals(before,f.hardware.state());assertNull(f.scheduled.nextDeadline())
    }
    @Test fun closingPendingOperationCannotCompleteOrServeStalePioAccess() {
        val f=Fixture();f.write(0x90,0x06000001);f.hardware.close()
        assertNull(f.scheduled.nextDeadline());assertEquals(listOf(false,false),f.latch())
        assertFails { f.advance(100) };assertFails { f.read(0x90) };assertFails { f.write(0x90,0x06000001) }
        assertEquals(true,(f.hardware.state()["pioEnableStatus"]as Map<*,*>)["closed"])
    }
    @Test fun missingIrqRouteKeepsCompletedLatchAndPendingStatusRatherThanInventingDelivery() {
        val f=Fixture(irq="");f.write(0x44,0x4000);f.write(0x90,0x06000001)
        assertFails { f.advance(8) };assertEquals(listOf(true,false),f.latch());assertEquals(true,f.hardware.state()["irqPending"]);assertEquals(0x4000L,f.read(0x40));assertTrue(f.context.lines.isEmpty())
    }
    @Test fun unsupportedIdentityAndProfileFailBeforePublishingHardwarePorts() {
        for (input in listOf(-1L,0L,0x332221L,3351072.0,"3351072")) {
            val c=Context();val before=c.registrations.keys.toSet()
            assertFails { FlashFactory().create(c,config(jedec=input)) };assertEquals(before,c.registrations.keys)
        }
        val c=Context();assertFails { FlashFactory().create(c,config(profile="unknown")) };assertEquals(setOf("test.image","test.irq"),c.registrations.keys)
    }
    @Test fun enabledLatchStillCannotProgramEraseOrWriteMutationRegisters() {
        val f=Fixture();f.write(0x90,0x06000001);f.advance(8);assertEquals(listOf(true,false),f.latch())
        for (at in listOf(0x70,0x78,0x7c)) { val before=f.hardware.state();assertFails { f.write(at,1) };assertEquals(before,f.hardware.state()) }
        for (command in listOf(0xdc0b0001L,0x210b0001L,0x120b0001L)) { val before=f.hardware.state();assertFails { f.write(0x90,command) };assertEquals(before,f.hardware.state()) }
        assertEquals(false,f.hardware.state()["mutations"]);assertEquals(0,f.context.imageReads)
    }

    @Test fun bootQspiProfileSupportsMicronAndMacronixJedecWrenRdsrWrsrAnd4BytePioRead() {
        val imageData = ByteArray(4096) { i -> ((i * 31 + 7) and 0xff).toByte() }
        fun makeContext(): Context = Context(media = false).apply {
            registrations["test.image"] = object : ReadOnlyImagePort {
                override val length = imageData.size.toLong()
                override val sha256 = "0".repeat(64)
                override fun check(offset: Long, size: Long) { require(offset >= 0 && size > 0 && offset + size <= length) }
                override fun read(offset: Long, destination: ByteBuffer) {
                    imageReads++
                    destination.put(imageData, offset.toInt(), destination.remaining())
                }
            }
        }

        // 1. Macronix 0xC2 25 3A (0x3A25C2L): 1-byte & 3-byte RDID, WREN -> RDSR (WEL=1) -> WRSR (0xC740) -> RDSR (QE=0x40, WEL=0) -> 4-byte 0x13/0xEC PIO read
        val mxCtx = makeContext()
        val mxFlash = FlashFactory().create(mxCtx, config(profile = FlashFactory.BOOT_QSPI_PROFILE, jedec = FlashFactory.MACRONIX_JEDEC)) as StatefulComponent
        val mxPort = mxCtx.ports.find("soc-flash.registers", MmioDevice::class.java)!!
        mxCtx.tick = 10; mxPort.write(0x90, 4, 0x9f800001); mxCtx.tick = 18; mxPort.beforeInstruction(18)
        assertEquals(0xc2L, mxPort.read(0xa0, 4))
        mxCtx.tick = 20; mxPort.write(0x90, 4, 0x9fa00001); mxCtx.tick = 28; mxPort.beforeInstruction(28)
        assertEquals(FlashFactory.MACRONIX_JEDEC, mxPort.read(0xa0, 4))

        // WRSR without prior WREN must refuse fail-closed
        mxPort.write(0xa8, 4, 0xc740L)
        assertFails { mxPort.write(0x90, 4, 0x01009001L) }

        // Macronix boot.bin sequence (Table 1 @ 0xBCF8): WREN (0x06) -> RDSR (0x05, 0x058B0001, bit 1 == 1) -> WRSR (0x01, +0xA8 = 0xC740) -> RDSR (0x05, SR=0x40, WEL=0, WIP=0)
        mxCtx.tick = 30; mxPort.write(0x90, 4, 0x06000001L); mxCtx.tick = 38; mxPort.beforeInstruction(38)
        mxCtx.tick = 40; mxPort.write(0x90, 4, 0x058b0001L); mxCtx.tick = 48; mxPort.beforeInstruction(48)
        assertEquals(0x02L, mxPort.read(0xa0, 4))
        mxPort.write(0xa8, 4, 0xc740L)
        mxCtx.tick = 50; mxPort.write(0x90, 4, 0x01009001L); mxCtx.tick = 58; mxPort.beforeInstruction(58)
        mxCtx.tick = 60; mxPort.write(0x90, 4, 0x058b0001L); mxCtx.tick = 68; mxPort.beforeInstruction(68)
        assertEquals(0x40L, mxPort.read(0xa0, 4))

        // Snapshot round-trip preserves SR=0x40, CR=0xC7
        val saved = mxFlash.saveSnapshot()
        val restoredCtx = makeContext()
        val restoredFlash = FlashFactory().create(restoredCtx, config(profile = FlashFactory.BOOT_QSPI_PROFILE, jedec = FlashFactory.MACRONIX_JEDEC)) as StatefulComponent
        restoredFlash.restoreSnapshot(saved)
        val pioState = restoredFlash.state()["pioEnableStatus"] as Map<*, *>
        assertEquals(listOf(0x40L, 0L), pioState["statusRegisterBySelector"])
        assertEquals(listOf(0xc7L, 0L), pioState["configRegisterBySelector"])

        // 4-byte PIO read (0x13 and 0xEC): bit23=1 (RX), count=4 (bits22:20=3), bit19=1 (addr), addrBytes=4 (bits17:16=3)
        mxPort.write(0x94, 4, 0x100L)
        mxCtx.tick = 70; mxPort.write(0x90, 4, 0x13bb0001L); mxCtx.tick = 78; mxPort.beforeInstruction(78)
        val expectedWord = ByteBuffer.wrap(imageData, 0x100, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        assertEquals(expectedWord, mxPort.read(0xa0, 4))
        mxPort.write(0x94, 4, 0x200L)
        mxCtx.tick = 80; mxPort.write(0x90, 4, 0xecbb0001L); mxCtx.tick = 88; mxPort.beforeInstruction(88)
        val expectedWord2 = ByteBuffer.wrap(imageData, 0x200, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        assertEquals(expectedWord2, mxPort.read(0xa0, 4))

        // 2. Micron 0x20 BB 20 (0x20BB20L): 1-byte and 3-byte RDID; -1L still refused
        val miCtx = makeContext()
        FlashFactory().create(miCtx, config(profile = FlashFactory.BOOT_QSPI_PROFILE, jedec = FlashFactory.MICRON_JEDEC))
        val miPort = miCtx.ports.find("soc-flash.registers", MmioDevice::class.java)!!
        miCtx.tick = 10; miPort.write(0x90, 4, 0x9fa00001L); miCtx.tick = 18; miPort.beforeInstruction(18)
        assertEquals(FlashFactory.MICRON_JEDEC, miPort.read(0xa0, 4))
        assertFails { FlashFactory().create(makeContext(), config(profile = FlashFactory.BOOT_QSPI_PROFILE, jedec = -1L)) }
    }
}
