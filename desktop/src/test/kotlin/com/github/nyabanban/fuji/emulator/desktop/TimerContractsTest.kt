package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.firmware.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.*

class TimerContractsTest {
    /** Bounded public word fixture, not a DAT image or execution result. Real DAT is checked separately. */
    private val words = linkedMapOf(
        0x58e50 to 0xe3a01001L,
        0x58e54 to 0xe3a00040L,
        0x58e58 to 0xeb00079cL,
        0x58e5c to 0xe3a01010L,
        0x58e60 to 0xe3a00040L,
        0x58e64 to 0xeb00073fL,
        0x58e68 to 0xe3a01003L,
        0x58e6c to 0xe3a00040L,
        0x58e70 to 0xeb000753L,
        0x58e74 to 0xe3a00040L,
        0x58e78 to 0xeb000774L,
        0x58e80 to 0xe3a00007L,
        0x58e84 to 0xeb00064cL,
        0x58e98 to 0xe3a00007L,
        0x58e9c to 0xeb000713L,
        0x58ea0 to 0xe3a02000L,
        0x58ea4 to 0xe3a0100aL,
        0x58ea8 to 0xe3a00007L,
        0x58eac to 0xeb000713L,
        0x5a7cc to 0xe3500007L,
        0x5a7d4 to 0xe3073862L,
        0x5a7d8 to 0xe3413ffeL,
        0x5a7dc to 0xe0803003L,
        0x5a7e0 to 0xe1a00183L,
        0x5ab34 to 0xe1a03002L,
        0x5ab38 to 0xe1a04000L,
        0x5ab3c to 0xe24b2010L,
        0x5ab40 to 0xe1a00003L,
        0x5ab44 to 0xebffff75L,
        0x5ab50 to 0xe51b1010L,
        0x5ab54 to 0xe1a00004L,
        0x5ab58 to 0xebffff3eL,
        0x5a934 to 0x979ff100L,
        0x5a93c to 0x5a960L,
        0x5a968 to 0xe3a03ffaL,
        0x5a9b0 to 0xe1a02003L,
        0x5a9b4 to 0xe3a03000L,
        0x5a9b8 to 0xe30f0980L,
        0x5a9bc to 0xe3400337L,
        0x5a9c0 to 0xe1a06002L,
        0x5a9c4 to 0xe1a07003L,
        0x5a9c8 to 0xe0a76190L,
        0x5a9cc to 0xe2560001L,
        0x5a9d0 to 0xe2c71000L,
        0x5a9d4 to 0xeb000231L,
        0x5a868 to 0xe1a05001L,
        0x5a86c to 0xe3074862L,
        0x5a870 to 0xe3414ffeL,
        0x5a874 to 0xe0804004L,
        0x5a878 to 0xe1a04184L,
        0x5a87c to 0xe1a00004L,
        0x5a880 to 0xeb0001c2L,
        0x5a884 to 0xe1a01005L,
        0x5a888 to 0xe1a00004L,
        0x5a88c to 0xeb0001bfL,
        0x5a890 to 0xe1a01005L,
        0x5a894 to 0xe1a00004L,
        0x5a898 to 0xeb0001bcL,
        0x5a89c to 0xe2844004L,
        0x5a8a0 to 0xe2455001L,
        0x5a8a4 to 0xe1a01005L,
        0x5a8a8 to 0xe1a00004L,
        0x5a8ac to 0xeb0001b7L,
        0x5a8b0 to 0xe1a01005L,
        0x5a8b4 to 0xe1a00004L,
        0x5a8b8 to 0xeb0001b4L,
        0x5a8bc to 0xe1a01005L,
        0x5a8c0 to 0xe1a00004L,
        0x5a8c4 to 0xeb0001b1L,
        0x58d98 to 0xeaffffceL,
        0x58cd8 to 0xeaff9d81L,
        0x58d18 to 0xe3a00040L,
        0x58d1c to 0xe1540000L,
        0x58d24 to 0xebff9df4L,
        0x4051c to 0xe59f10acL,
        0x40524 to 0xe2800001L,
        0x40528 to 0xe5810000L,
        0x40530 to 0xe59f109cL,
        0x40538 to 0xe2800001L,
        0x4053c to 0xe5810000L,
        0x405ac to 0xe59f101cL,
        0x405b4 to 0xe2400001L,
        0x405b8 to 0xe5810000L,
        0x45e98 to 0xe30c3104L,
        0x45e9c to 0xe3403005L,
        0x45ea0 to 0xe5834000L,
        0x56b20 to 0xe1fc8f9fL,
        0x56b2c to 0xe1ec9f91L,
        0x405d0 to 0x5bc04L,
        0x405d4 to 0x5c104L
    )
    private val appWords = linkedMapOf(0x11f7d58 to 0xe3a06c86L, 0x11f7d5c to 0xe34f6ff4L, 0x11f7d60 to 0xe1a00006L, 0x11f7d64 to 0xebb98c9bL)
    private fun fixture(): Pair<ByteArray, ByteArray> {
        val kernel = ByteArray(0x1b660)
        words.forEach { (pc, value) -> put(kernel, 0x40000, pc, value) }
        val app = ByteArray(0x11f7d68 - TimerContracts.SECTION4_BASE.toInt())
        appWords.forEach { (pc, value) -> put(app, TimerContracts.SECTION4_BASE.toInt(), pc, value) }
        return kernel to app
    }
    private fun put(bytes: ByteArray, base: Int, pc: Int, value: Long) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(pc - base, value.toInt())
    }
    private fun changed(bytes: ByteArray, base: Int, pc: Int, value: Long) = bytes.copyOf().also { put(it, base, pc, value) }
    @Suppress("UNCHECKED_CAST")
    private fun map(report: Map<String, Any>, field: String) = report.getValue(field) as Map<String, Any>

    @Test fun boundedFixtureDerivesCounterAddressesArithmeticAndHypothesisWithoutMutation() {
        val (kernel, app) = fixture(); val originalKernel = kernel.copyOf(); val originalApp = app.copyOf()
        val report = TimerContracts.observe(kernel, app)
        assertEquals(true, report["matches"])
        assertEquals(false, report["inputIdentityVerified"])
        assertEquals(false, report["runtimeInjected"])
        val address = map(report, "addressProducer")
        assertEquals(0x1ffe7862L, address["base"]); assertEquals(7L, address["channel"])
        assertEquals(0xfff3c348L, address["computedWordAddress"])
        val count = map(report, "countConversion")
        assertEquals(54_000_000L, count["nominalConstant"]); assertEquals(10L, count["argument"]); assertEquals(1000L, count["divisor"])
        assertEquals(540_000L, count["ceilingCount"]); assertEquals(540_000L, count["load"]); assertEquals(539_999L, count["reload"])
        assertEquals(0xfff3c34cL, count["reloadAddress"])
        assertEquals(mapOf("activity" to 0x5bc04L, "ticks" to 0x5c104L), report["tickCounterPointers"])
        val gic = report["gicInitializers"] as List<*>
        assertEquals(listOf(1L, 16L, 3L), gic.map { (it as Map<*, *>)["r1"] })
        assertTrue(gic.all { (it as Map<*, *>)["r0"] == 64L })
        val route = map(report, "routeCandidate")
        assertEquals("HYPOTHESIS", route["evidenceClass"]); assertEquals(false, route["physicalWiringMeasured"]); assertEquals(false, route["enabled"])
        val irq = map(report, "irqPath")
        assertEquals(0x58cd8L, irq["vectorTarget"]); assertEquals(0x402e4L, irq["dispatcher"]); assertEquals(0x404fcL, irq["tickTarget"])
        val half = map(report, "halfwordConsumers")
        assertEquals(2L, half["bytes"]); assertEquals(12L, half["baseRegister"]); assertEquals(8L, half["loadTargetRegister"])
        assertEquals(9L, half["storeStatusRegister"]); assertEquals(1L, half["storeValueRegister"])
        assertEquals(0xfff48600L, map(report, "nextApplicationMmio")["computedAddress"])
        assertEquals(0x5afd8L, map(report, "nextApplicationMmio")["readHelper"])
        assertContentEquals(originalKernel, kernel); assertContentEquals(originalApp, app)
    }
    @Test fun everyAdmittedInstructionRejectsBitMutationIncludingFull32BitOpcode() {
        val (kernel, app) = fixture()
        val report = TimerContracts.observe(kernel, app)
        val addresses = report["checkedInstructions"] as List<*>
        assertTrue(addresses.size >= 80)
        addresses.forEach { address ->
            val pc = (address as Number).toInt()
            val application = pc >= TimerContracts.SECTION4_BASE
            val source = if (application) app else kernel
            val base = if (application) TimerContracts.SECTION4_BASE.toInt() else 0x40000
            val original = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).getInt(pc - base).toLong() and 0xffffffffL
            for (mask in listOf(1L, 0x10000000L)) {
                val modified = changed(source, base, pc, original xor mask)
                assertFailsWith<IllegalArgumentException>("word "+pc.toString(16)+" mask "+mask.toString(16)) {
                    if (application) TimerContracts.observe(kernel, modified) else TimerContracts.observe(modified, app)
                }
            }
        }
    }
    @Test fun counterLiteralContentAndAddressingChangesRefuseEvenWhenOtherWordsMatch() {
        val (kernel, app) = fixture()
        for ((pc, value) in listOf(0x405d0 to 0x5bc08L, 0x405d4 to 0x5c108L, 0x4051c to 0xe59f10a8L, 0x405ac to 0xe59f1018L, 0x45e98 to 0xe30c3108L)) {
            assertFailsWith<IllegalArgumentException> { TimerContracts.observe(changed(kernel, 0x40000, pc, value), app) }
        }
    }
    @Test fun callBranchesJumpTableChannelAndConversionArgumentsRefuseChangedFields() {
        val (kernel, app) = fixture()
        val changedWords = listOf(0x58d98 to 0xeaffffcfL, 0x58d24 to 0xebff9df5L, 0x5a93c to 0x5a998L,
            0x58e80 to 0xe3a00006L, 0x58e54 to 0xe3a00041L, 0x58ea0 to 0xe3a02001L,
            0x58ea4 to 0xe3a0100bL, 0x5a968 to 0xe3a03ff9L, 0x5a9b8 to 0xe30f0981L,
            0x5a9bc to 0xe3400336L, 0x5a8a0 to 0xe2455002L, 0x5a89c to 0xe2844008L)
        for ((pc, value) in changedWords) assertFailsWith<IllegalArgumentException> { TimerContracts.observe(changed(kernel, 0x40000, pc, value), app) }
    }
    @Test fun applicationAndHalfwordOperandsAndInitializedBoundsStayStrict() {
        val (kernel, app) = fixture()
        for ((pc, value) in listOf(0x56b20 to 0xe1fc9f9fL, 0x56b2c to 0xe1ec9f92L)) {
            assertFailsWith<IllegalArgumentException> { TimerContracts.observe(changed(kernel, 0x40000, pc, value), app) }
        }
        for ((pc, value) in listOf(0x11f7d58 to 0xe3a06c87L, 0x11f7d5c to 0xe34f6ff5L, 0x11f7d64 to 0xebb98c9cL)) {
            assertFailsWith<IllegalArgumentException> { TimerContracts.observe(kernel, changed(app, TimerContracts.SECTION4_BASE.toInt(), pc, value)) }
        }
        assertFailsWith<IllegalArgumentException> { TimerContracts.observe(ByteArray(3), app) }
        assertFailsWith<IllegalArgumentException> { TimerContracts.observe(kernel, ByteArray(3)) }
        assertFailsWith<IllegalArgumentException> { TimerContracts.observe(kernel, app, TimerContracts.SECTION4_BASE + 4) }
    }
    @Test fun ceilingAndRegisterRangeArithmeticDoesNotRoundDownOrOverflow() {
        assertEquals(540_000L, TimerContracts.ceilingCount(54_000_000, 10, 1000))
        assertEquals(540_001L, TimerContracts.ceilingCount(54_000_001, 10, 1000))
        assertEquals(1L, TimerContracts.ceilingCount(1, 1, 1000))
        assertEquals(0xffffffffL, TimerContracts.ceilingCount(0xffffffffL, 1, 1))
        for ((frequency, argument, divisor) in listOf(Triple(0L, 1L, 1L), Triple(1L, 0L, 1L), Triple(1L, 1L, 0L),
            Triple(-1L, 1L, 1L), Triple(0x100000000L, 1L, 1L), Triple(0xffffffffL, 2L, 1L), Triple(0xffffffffL, 0xffffffffL, 1L))) {
            assertFailsWith<IllegalArgumentException> { TimerContracts.ceilingCount(frequency, argument, divisor) }
        }
    }
    @Test fun selectedOriginalDatRecalculatesAllContractsWithoutNativeOrCapstone() {
        val input = System.getenv("FUJI_FIRMWARE_FILE")
        assumeTrue(!input.isNullOrBlank(), "DAT not selected; fixture checks do not qualify original firmware")
        val root = Path.of(System.getProperty("componentProjectRoot"))
        val image = FirmwareImage(FirmwareMetadata(root.resolve("configurations/jvm/firmware/gfx100s-213.yaml")), Path.of(checkNotNull(input)))
        val report = TimerContracts.observe(image)
        assertEquals(true, report["matches"]); assertEquals(true, report["inputIdentityVerified"])
        assertEquals(image.metadata.sha256, report["inputSha256"])
        assertEquals(0xfff3c348L, map(report, "addressProducer")["computedWordAddress"])
        assertEquals(0xfff48600L, map(report, "nextApplicationMmio")["computedAddress"])
        assertEquals(false, report["runtimeInjected"])
    }
}
