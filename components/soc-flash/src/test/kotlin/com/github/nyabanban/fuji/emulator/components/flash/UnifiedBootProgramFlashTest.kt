package com.github.nyabanban.fuji.emulator.components.flash

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.*

class UnifiedBootProgramFlashTest {
    private fun hash(b: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))

    private inner class UnifiedContext : ComponentContext {
        var tick = 0L
        // 512 KiB sparse-backed in-memory map for fast unit testing across full 64 MiB address space
        val pages = hashMapOf<Long, ByteArray>()
        var generation = 0L
        var pending = false
        var erases = 0
        var programs = 0
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = error("No filesystem in controller unit test")

        fun readByte(off: Long): Byte {
            val page = off and -4096L
            val arr = pages[page] ?: return 0xFF.toByte()
            return arr[(off - page).toInt()]
        }

        fun writeByte(off: Long, v: Byte) {
            val page = off and -4096L
            val arr = pages.getOrPut(page) { ByteArray(4096) { 0xFF.toByte() } }
            arr[(off - page).toInt()] = v
        }

        init {
            // Seed recognizable boot pattern at 0x20000 and calibration pattern at 0x03DC0000
            for (i in 0 until 16) {
                writeByte(0x20000L + i, (0x10 + i).toByte())
                writeByte(0x03DC0000L + i, (0x80 + i).toByte())
            }
            val imageLength = 64L * 1024L * 1024L
            val dummySha = "a".repeat(64)
            claims["board.layout"] = object : AddressLayout {
                override fun window(port: String) = AddressWindow(port, 0xf0000000, 0x8000000)
            }
            // Note: Pure ReadOnlyImagePort (NOT SupplementalImagePort) + unified WritableImagePort + ProgrammableImagePort!
            claims["test.image"] = object : ReadOnlyImagePort {
                override val length: Long = imageLength
                override val sha256: String = dummySha
                override fun check(offset: Long, size: Long) {
                    require(offset >= 0L && size > 0L && offset <= length - size)
                }
                override fun read(offset: Long, destination: ByteBuffer) {
                    check(offset, destination.remaining().toLong())
                    var cur = offset
                    while (destination.hasRemaining()) {
                        destination.put(readByte(cur++))
                    }
                }
            }
            claims["test.image.mutable"] = object : WritableImagePort {
                override fun snapshot() = WritableImageState(
                    regionId = "unified-nor-flash",
                    offset = 0L,
                    length = imageLength,
                    relativeFile = "nor-flash.bin",
                    writableOffset = 0L,
                    writableLength = imageLength,
                    seedSha256 = dummySha,
                    currentSha256 = dummySha,
                    generation = generation,
                    pending = pending,
                    closed = false,
                    failure = null,
                )
                override fun prepareErase(offset: Long, size: Long): ImageEraseTransaction {
                    check(!pending)
                    require(size == 4096L || size == 65536L)
                    pending = true
                    return object : ImageEraseTransaction {
                        override val offset: Long = offset
                        override val size: Long = size
                        private var done = false
                        override fun commit(): ImageEraseReceipt {
                            check(!done)
                            done = true
                            pending = false
                            var changed = 0L
                            for (i in 0L until size) {
                                if (readByte(offset + i) != 0xFF.toByte()) {
                                    writeByte(offset + i, 0xFF.toByte())
                                    changed++
                                }
                            }
                            generation++
                            erases++
                            return ImageEraseReceipt(offset, size, changed, dummySha, dummySha, generation)
                        }
                        override fun close() {
                            if (!done) {
                                done = true
                                pending = false
                            }
                        }
                    }
                }
            }
            claims["test.image.program"] = object : ProgrammableImagePort {
                override fun prepareProgram(offset: Long, payload: ByteBuffer): ImageProgramTransaction {
                    check(!pending)
                    val frozen = ByteArray(payload.remaining())
                    payload.duplicate().get(frozen)
                    pending = true
                    return object : ImageProgramTransaction {
                        override val offset: Long = offset
                        override val size: Int = frozen.size
                        private var done = false
                        override fun commit(): ImageProgramReceipt {
                            check(!done)
                            done = true
                            pending = false
                            var changed = 0L
                            for (i in frozen.indices) {
                                val cur = readByte(offset + i).toInt() and 0xFF
                                val nxt = frozen[i].toInt() and 0xFF
                                check((cur and nxt) == nxt) {
                                    "NOR 1->0 violation"
                                }
                                if (cur != nxt) {
                                    writeByte(offset + i, frozen[i])
                                    changed++
                                }
                            }
                            generation++
                            programs++
                            return ImageProgramReceipt(offset, size, changed, hash(frozen), dummySha, dummySha, generation)
                        }
                        override fun close() {
                            if (!done) {
                                done = true
                                pending = false
                            }
                        }
                    }
                }
            }
            claims["test.irq"] = object : InterruptSink {
                override fun setLine(irq: Int, high: Boolean) {}
            }
        }
    }

    @Test
    fun unifiedV6CombinesBootQspiPioAndFull64MiBEraseAndDmaProgramWithRealMicronJedec() {
        val ctx = UnifiedContext()
        val micronJedec = 0x20BB20L // 2145056: Micron MT25QU512ABB
        val comp = FlashFactory().create(
            ctx,
            mapOf(
                "profile" to FlashFactory.UNIFIED_BOOT_PROGRAM_PROFILE,
                "jedec" to micronJedec,
                "imagePort" to "test.image",
                "interruptPort" to "test.irq",
                "irq" to 37L,
            ),
        )
        comp.use {
            val mmio = ctx.ports.find("soc-flash.registers", MmioDevice::class.java) as ScheduledMmioDevice
            val txPeer = checkNotNull(ctx.ports.find("soc-flash.tx", DmaDestinationPeer::class.java))

            fun wr(o: Int, v: Long) = mmio.write(o.toLong(), 4, v)
            fun rd(o: Int): Long = mmio.read(o.toLong(), 4)
            fun adv(t: Long) {
                ctx.tick = t
                mmio.beforeInstruction(t)
            }

            // 1. BootROM RDID (0x9F) returns real Micron MT25QU512ABB JEDEC 0x20BB20 (1-byte & 3-byte)
            wr(0x90, 0x9F800001L)
            adv(ctx.tick + 8)
            assertEquals(0x20L, rd(0xA0))
            wr(0x90, 0x9FA00001L)
            adv(ctx.tick + 8)
            assertEquals(micronJedec, rd(0xA0) and 0xFFFFFFL)

            // 2. BootROM WREN (0x06) + WRSR (0x01 via +0xA8) + RDSR (0x05) works in v6
            wr(0x90, 0x06000001L)
            adv(ctx.tick + 8)
            wr(0xA8, 0xC740L) // SR=0x40 (QE), CR=0xC7
            wr(0x90, 0x01009001L)
            adv(ctx.tick + 8)
            wr(0x90, 0x058B0001L)
            adv(ctx.tick + 8)
            assertEquals(0x40L, rd(0xA0) and 0xFFL)

            // 3. BootROM 4-byte PIO read (0x13) at firmware 0x00020000 returns seeded bytes
            wr(0x94, 0x00020000L)
            wr(0x90, 0x13BB0001L)
            adv(ctx.tick + 8)
            assertEquals(0x13121110L, rd(0xA0))

            // 4. Runtime 4 KiB sector erase (0x21) at 0x00020000 (mapped 0xF0020000 in firmware area!) + verify erased to 0xFFFFFFFF
            wr(0x90, 0x06000001L) // WREN
            adv(ctx.tick + 8)
            wr(0x94, 0xF0020000L)
            wr(0x90, 0x210B0001L) // 4 KiB erase (4-byte addr)
            adv(ctx.tick + 8)
            val eraseDoneTick = checkNotNull(mmio.nextDeadline())
            adv(eraseDoneTick)
            assertEquals(1, ctx.erases)
            wr(0x94, 0x00020000L)
            wr(0x90, 0x13BB0001L)
            adv(ctx.tick + 8)
            assertEquals(0xFFFFFFFFL, rd(0xA0))

            // 5. Runtime DMA page program (0x12) into erased firmware page 0x00020000
            wr(0x90, 0x06000001L) // WREN
            adv(ctx.tick + 8)
            wr(0, rd(0) or 0x8000L)
            listOf(
                8 to 0x12L,
                0x74 to 0xFFFFFFFFL,
                0x78 to 0xF0020000L,
                0x80 to 15L,
                0x20 to 0x202L,
                0x7C to 256L,
                0x1C to 0xF0020000L,
            ).forEach { (o, v) -> wr(o, v) }
            wr(0x70, 1L)
            adv(ctx.tick + 8)
            assertEquals(0x20L, rd(0x70))
            wr(0x70, 0x20L)
            assertNotNull(txPeer.claim(0, 0xF0020000L, 256, 4, 43))
            val pageData = ByteArray(256) { (0xC0 + (it and 0x1F)).toByte() }
            txPeer.write(0, 0xF0020000L, 0, ByteBuffer.wrap(pageData))
            ctx.tick += 20
            txPeer.finish(0, ctx.tick, ctx.tick)
            val programDoneTick = checkNotNull(mmio.nextDeadline())
            adv(programDoneTick)
            assertEquals(1, ctx.programs)

            // Verify programmed bytes read back via 4-byte PIO 0x13
            wr(0x94, 0x00020000L)
            wr(0x90, 0x13BB0001L)
            adv(ctx.tick + 8)
            assertEquals(0xC3C2C1C0L, rd(0xA0))
        }
    }
}
