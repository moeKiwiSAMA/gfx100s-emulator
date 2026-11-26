package com.github.nyabanban.fuji.emulator.firmware.research

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class HardwareDumpResearchTest {
    @TempDir lateinit var temp: Path
    private fun entry(text: String): HardwareDumpResearch.Entry {
        val bytes = text.toByteArray()
        return HardwareDumpResearch.Entry(bytes,HardwareDumpResearch.sha(bytes))
    }
    private fun zip(name: String, bytes: ByteArray = byteArrayOf(1,2,3)): Path {
        val file = temp.resolve("sample.zip")
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
        }
        return file
    }
    @Test fun archiveReadsBoundedBytesWithoutExtraction() {
        val file = zip("files/a.bin")
        val entries = HardwareDumpResearch.readArchive(file)
        assertContentEquals(byteArrayOf(1,2,3),entries.getValue("files/a.bin").bytes)
        assertFalse(Files.exists(temp.resolve("files")))
    }
    @Test fun unsafePathsRejected() {
        listOf("../x","/x","C:/x","a/../x","a/./x","a//x","a"+92.toChar()+"x", "x"+0.toChar()).forEach {
            assertFailsWith<IllegalArgumentException> { HardwareDumpResearch.safeName(it) }
        }
        assertFailsWith<IllegalArgumentException> { HardwareDumpResearch.readArchive(zip("../outside")) }
    }
    @Test fun corruptedCrcRejected() {
        val file = zip("files/a.bin")
        val bytes = Files.readAllBytes(file)
        val signature = byteArrayOf(0x50,0x4b,1,2)
        val offset = (0..bytes.size-4).first { at -> (0..3).all { bytes[at+it] == signature[it] } }
        bytes[offset+16] = (bytes[offset+16].toInt() xor 1).toByte()
        Files.write(file,bytes)
        assertFailsWith<IllegalArgumentException> { HardwareDumpResearch.readArchive(file) }
    }
    @Test fun manifestCoversEveryPayloadExactlyOnce() {
        val a = entry("A")
        val m = entry(a.sha256 + "  a.bin" + 10.toChar())
        val entries = mapOf("files/a.bin" to a, "files/SHA256SUMS" to m)
        assertEquals(1,HardwareDumpResearch.manifest(entries).size)
        assertFailsWith<IllegalArgumentException> { HardwareDumpResearch.manifest(entries + ("files/extra.bin" to entry("X"))) }
        assertFailsWith<IllegalArgumentException> { HardwareDumpResearch.manifest(entries + ("files/a.bin" to entry("B"))) }
        val duplicate = entry(m.bytes.toString(Charsets.UTF_8).repeat(2))
        assertFailsWith<IllegalArgumentException> { HardwareDumpResearch.manifest(entries + ("files/SHA256SUMS" to duplicate)) }
    }
    @Test fun equalAndEmptyComparisonsKeepNullDifference() {
        for (data in listOf(byteArrayOf(),byteArrayOf(0,1,2))) {
            val result = HardwareDumpResearch.difference(data,data)
            assertEquals(true,result["equal"]); assertEquals(0L,result["differentBytes"])
            assertNull(result["firstDifferenceOffset"]); assertNull(result["lastDifferenceOffset"])
            assertEquals(emptyList<Any>(),result["firstDifferences"])
        }
    }
    @Test fun differenceCountsAllBytesEvenWhenExamplesTruncated() {
        val data = ByteArray(131073) { 1 }
        val result = HardwareDumpResearch.difference(ByteArray(data.size),data)
        assertEquals(data.size.toLong(),result["differentBytes"])
        assertEquals(true,result["examplesTruncated"])
        assertEquals(16,(result["firstDifferences"] as List<*>).size)
        assertEquals(3,(result["changedBlocks"] as List<*>).size)
        assertEquals(0,result["firstDifferenceOffset"]); assertEquals(data.lastIndex,result["lastDifferenceOffset"])
    }
    @Test fun comparisonsRefuseOutOfBoundsAndDoNotSilentlyTruncate() {
        assertFailsWith<IllegalArgumentException> { HardwareDumpResearch.difference(ByteArray(4),ByteArray(4),1) }
        assertFailsWith<IllegalArgumentException> { HardwareDumpResearch.difference(ByteArray(4),ByteArray(4),-1) }
        assertFailsWith<IllegalArgumentException> { HardwareDumpResearch.difference(ByteArray(4),ByteArray(4),0,3) }
        assertEquals(true,HardwareDumpResearch.difference(byteArrayOf(9,1,2,9),byteArrayOf(1,2),1)["equal"])
    }
    private fun row(base: Long,size: Long) = mapOf("offset" to base,"size" to size)
    @Test fun coverageKeepsGapsAndRejectsOverlapOrOverflow() {
        assertEquals(listOf(mapOf("base" to 4L,"endExclusive" to 8L)),HardwareDumpResearch.coverage(listOf(row(0,4),row(8,2)),10))
        assertEquals(emptyList<Any>(),HardwareDumpResearch.coverage(listOf(row(0,4),row(4,6)),10))
        assertFailsWith<IllegalArgumentException> { HardwareDumpResearch.coverage(listOf(row(0,4),row(3,3)),10) }
        assertFailsWith<IllegalArgumentException> { HardwareDumpResearch.coverage(listOf(row(1,Long.MAX_VALUE)),Long.MAX_VALUE) }
    }
    @Test fun tableReadsUnsignedWordsWithoutU32Overflow() {
        val offset = (HardwareDumpResearch.TABLE_ADDRESS - 0x1021000L).toInt()
        val data = ByteArray(offset+32*12)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset,-1).putInt(offset+4,-1).putInt(offset+8,7)
        val row = HardwareDumpResearch.table(data).first()
        assertEquals(0xffffffffL*0x20000L,row["size"])
        assertEquals(0xffffffffL*0x20000L,row["offset"])
        assertEquals(7L,row["thirdWordUnknown"])
        assertFailsWith<IllegalArgumentException> { HardwareDumpResearch.table(ByteArray(10)) }
    }
}
