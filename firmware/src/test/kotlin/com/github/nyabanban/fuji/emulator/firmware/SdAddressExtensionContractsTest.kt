package com.github.nyabanban.fuji.emulator.firmware

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SdAddressExtensionContractsTest {
    @Test fun selectedLayoutPreservesV6AndAddsOnlyTwoWriteWords() {
        val old=SdTransactionContracts.windows()
        val selected=SdTransactionContracts.windows(SdTransactionContracts.ADDRESS_PROFILE)
        assertEquals(20,old.size);assertEquals(22,selected.size)
        assertEquals(old.take(18),selected.take(18))
        assertEquals(listOf("soc-sd-host.transaction-address-0","soc-sd-host.transaction-address-1","soc-sd-host.address-high-0","soc-sd-host.address-high-1"),selected.drop(18).map{it.port})
        assertEquals(listOf(0xfffc4000L,0xfffc8000L,0xfff4a888L,0xfff4a894L),selected.drop(18).map{it.address})
        assertEquals(listOf(64L,64L,4L,4L),selected.drop(18).map{it.size})
        assertFailsWith<IllegalArgumentException>{SdTransactionContracts.windows("unknown")}
    }
    @Test fun originalCompleteWritersQualifyAndInstructionOrConditionChangesRefuse() {
        val source=Path.of(System.getProperty("sdAddressDat","../firmware-data/gfx100s/2.13/decoded/section4.bin"))
        val app=Files.readAllBytes(source)
        val report=SdTransactionContracts.observeAddressExtension(app)
        assertEquals(42,report["criticalWords"])
        assertEquals(listOf(0xfff4a888L,0xfff4a894L),report["highWordAddresses"])
        // Change the conditional branch without changing its target, and each full BL/address word.
        for(pc in listOf(0x11f28e4,0x11f28e8,0x11f28ec,0x11f28f4,0x11f28fc,0x11f291c,0x11f2930,0x11f1318,0x11f1328)) {
            val index=pc-0x1021000+3
            val before=app[index];app[index]=(before.toInt() xor 0x10).toByte()
            assertContains(assertFailsWith<IllegalArgumentException>{SdTransactionContracts.observeAddressExtension(app)}.message.orEmpty(),"DAT word differs")
            app[index]=before
        }
        assertFailsWith<IllegalArgumentException>{SdTransactionContracts.observeAddressExtension(app.copyOf(32))}
    }
    @Test fun vendorPhyV8LayoutPinsDatRoutinesAndExposes30ExclusivePorts() {
        val v8 = SdTransactionContracts.windows(SdTransactionContracts.VENDOR_PHY_PROFILE)
        assertEquals(30, v8.size)
        assertEquals(SdTransactionContracts.windows().take(18), v8.take(18))
        val added = v8.drop(18)
        assertEquals(
            listOf(
                "soc-sd-host.transaction-v8-0", "soc-sd-host.transaction-v8-1",
                "soc-sd-host.address-high-v8-0", "soc-sd-host.address-high-v8-1",
                "soc-sd-host.vendor-a-0", "soc-sd-host.phy6060-0", "soc-sd-host.phy607c-0", "soc-sd-host.phy6094-0",
                "soc-sd-host.vendor-a-1", "soc-sd-host.phy6060-1", "soc-sd-host.phy607c-1", "soc-sd-host.phy6094-1",
            ),
            added.map { it.port },
        )
        assertEquals(
            listOf(
                0xfffc4000L, 0xfffc8000L, 0xfff4a888L, 0xfff4a894L,
                0xfffc41e0L, 0xfffc6060L, 0xfffc607cL, 0xfffc6094L,
                0xfffc81e0L, 0xfffca060L, 0xfffca07cL, 0xfffca094L,
            ),
            added.map { it.address },
        )
        val source = Path.of(System.getProperty("sdAddressDat", "../firmware-data/gfx100s/2.13/decoded/section4.bin"))
        val app = Files.readAllBytes(source)
        val report = SdTransactionContracts.observeVendorPhy(app)
        assertEquals((132 + 312) / 4, report["criticalWords"])
        val mutated = app.copyOf()
        mutated[0x11f1c54 - 0x1021000] = (mutated[0x11f1c54 - 0x1021000].toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> { SdTransactionContracts.observeVendorPhy(mutated) }
    }

    @Test fun uhsPhyV9LayoutPinsAll19DatRoutinesAndExposes50ExclusivePorts() {
        val v9 = SdTransactionContracts.windows(SdTransactionContracts.UHS_PHY_PROFILE)
        assertEquals(50, v9.size)
        assertEquals(SdTransactionContracts.windows().take(18), v9.take(18))
        val added = v9.drop(18)
        assertEquals(32, added.size)
        assertEquals("soc-sd-host.transaction-v9-0", added[0].port)
        assertEquals("soc-sd-host.address-high-v9-0", added[2].port)
        assertEquals("soc-sd-host.uhs4100-0", added[12].port)
        assertEquals(0xfffc4100L, added[12].address)
        assertEquals("soc-sd-host.uhs2-err-1", added.last().port)
        assertEquals(0xfffca2c0L, added.last().address)

        val source = Path.of(System.getProperty("sdAddressDat", "../firmware-data/gfx100s/2.13/decoded/section4.bin"))
        val app = Files.readAllBytes(source)
        val report = SdTransactionContracts.observeUhsPhy(app)
        assertEquals(SdTransactionContracts.UHS_PHY_SPANS.sumOf { it.size / 4 }, report["criticalWords"])
        val mutated = app.copyOf()
        mutated[0x11f281c - 0x1021000] = (mutated[0x11f281c - 0x1021000].toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> { SdTransactionContracts.observeUhsPhy(mutated) }
    }
}
