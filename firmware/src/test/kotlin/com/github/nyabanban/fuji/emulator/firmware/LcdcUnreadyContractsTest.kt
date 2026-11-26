package com.github.nyabanban.fuji.emulator.firmware

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LcdcUnreadyContractsTest {
    @Test fun existingDatAndChangedConditionAreDistinguished() {
        val root=Path.of(System.getProperty("firmwareProjectRoot",".."))
        val application=Files.readAllBytes(root.resolve("firmware-data/gfx100s/2.13/decoded/section4.bin"))
        val kernel=Files.readAllBytes(root.resolve("firmware-data/gfx100s/2.13/decoded/kernel.bin"))
        assertEquals(28,LcdcUnreadyContracts.observe(application,kernel)["checkedWords"])
        val mutated=application.copyOf();val condition=(0x12645f8-0x1021000)+3
        mutated[condition]=(mutated[condition].toInt() xor 0x10).toByte()
        assertFailsWith<IllegalArgumentException> {LcdcUnreadyContracts.observe(mutated,kernel)}
        assertFailsWith<IllegalArgumentException> {LcdcUnreadyContracts.observe(application,kernel.copyOf(32))}
    }
}
