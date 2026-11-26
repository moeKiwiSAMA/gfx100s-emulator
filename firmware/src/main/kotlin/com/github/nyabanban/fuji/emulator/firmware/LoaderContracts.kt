package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object LoaderContracts {
    /** FW-011: packaging boundaries, actual byte/iteration units and DAT-owned footer bytes. */
    fun tail(image: FirmwareImage, loader: ByteArray, flash: com.github.nyabanban.fuji.emulator.api.ReadOnlyImagePort, apertureBase: Long, regions: List<com.github.nyabanban.fuji.emulator.api.MemoryRegion>): Map<String, Any> {
        val input = image.inputPort(); val header = ByteBuffer.allocate(0x274).order(ByteOrder.LITTLE_ENDIAN); input.read(0,header)
        fun field(at: Int) = header.getInt(at).toLong() and 0xffffffffL
        val code = ByteBuffer.wrap(loader).order(ByteOrder.LITTLE_ENDIAN)
        fun word(at: Int) = code.getInt(at).toLong() and 0xffffffffL
        fun mov(at: Int, reg: Int): Long {
            val w = word(at); require(w and 0xfffff000L == (0xe3a00000L or (reg.toLong() shl 12)))
            return Integer.rotateRight((w and 255).toInt(),((w ushr 8) and 15).toInt() * 2).toLong() and 0xffffffffL
        }
        fun pair(at: Int, reg: Int): Long {
            val lo = word(at); val hi = word(at + 4)
            require((lo and 0xfff0f000L) == (0xe3000000L or (reg.toLong() shl 12)) && (hi and 0xfff0f000L) == (0xe3400000L or (reg.toLong() shl 12)))
            fun imm(w: Long) = (w and 0xfff) or ((w ushr 4) and 0xf000)
            return imm(lo) or (imm(hi) shl 16)
        }
        val length = field(0x24c); require(length == flash.length)
        val requested = mov(0x728,2); val bounceBytes = mov(0x85fc,0)
        val shiftWord = word(0x6ed0); require(shiftWord and 0xfffff07fL == 0xe1b02022L)
        val shift = ((shiftWord ushr 7) and 31).toInt(); val iterations = bounceBytes ushr shift
        require(requested == 32L && bounceBytes == 4096L && shift == 3 && iterations == 512L)
        val footerOffset = pair(0x718,0); val pointer = pair(0x70c,3); val destination = word(Math.toIntExact(pointer))
        require(footerOffset == length - requested && pointer == 0xb3dcL && destination == 0x2a69c000L)
        val footerRegion = regions.find { destination >= it.address && requested <= it.address + it.size - destination }
        require(footerRegion == null || footerRegion.permissions == 3) { "declared footer page must be RW/NX" }
        val footer = ByteBuffer.allocate(requested.toInt()).order(ByteOrder.LITTLE_ENDIAN); input.read(0x274 + footerOffset,footer)
        repeat(footer.capacity()) { footer.put(it,footer.get(it).toInt().inv().toByte()) }
        val marker = footer.getShort(30).toInt() and 0xffff; require(marker == 0xaaaa)
        val tail = checkNotNull(flash.experimentalTail)
        val expectedFill = mapOf("dat-end-block-zero-experimental-v1" to 0,"dat-end-block-ff-experimental-v1" to 255)
        require(tail.offset == length && tail.size == (-length and 4095) && expectedFill[tail.profile] == tail.fill)
        val bounce = ByteArray(bounceBytes.toInt()) { tail.fill.toByte() }; footer.array().copyInto(bounce)
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun region(name: String, offset: Long, size: Long, encoding: String): Map<String, Any> {
            require(offset >= 0 && size > 0 && size <= input.size - offset)
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var done = 0L
            while (done < size) { val count = minOf(buffer.size.toLong(),size - done).toInt(); input.read(offset + done,ByteBuffer.wrap(buffer,0,count)); digest.update(buffer,0,count); done += count }
            return mapOf("name" to name,"datOffset" to offset,"size" to size,"encoding" to encoding,"storedSha256" to digest.digest().joinToString("") { "%02x".format(it) })
        }
        val metadata = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN); input.read(0x140274,metadata); repeat(1024) { metadata.put(it,metadata.get(it).toInt().inv().toByte()) }
        val rows = ArrayList<Map<String, Any>>()
        for (offset in 0x40..(1024 - 36) step 36) {
            val words = List(9) { metadata.getInt(offset + it * 4).toLong() and 0xffffffffL }
            rows.add(mapOf("address" to 0xe20000L + offset,"id" to words[0],"words" to words)); if (words[0] == 255L) break
        }
        require(rows.last()["id"] == 255L && rows.single { it["id"] == 0L }["address"] == 0xe20040L && rows.single { it["id"] == 5L }["address"] == 0xe20118L)
        return mapOf("matches" to true,"requestedBytes" to requested,"bounceBytes" to bounceBytes,"ldrdIterations" to iterations,"bytesPerIteration" to (1 shl shift),
            "knownFooterOffset" to footerOffset,"knownFooterAddress" to apertureBase + footerOffset,"footerSha256" to hash(footer.array()),"marker" to marker,"destinationPointer" to pointer,"destination" to destination,
            "destinationMapped" to (footerRegion != null),
            "tailProfile" to tail.profile,"tailAddress" to apertureBase + tail.offset,"tailSize" to tail.size,"fill" to tail.fill,"expectedBounceSha256" to hash(bounce),"metadataRows" to rows,
            "containerRegions" to listOf(region("image",0x274,length,"xor-ff"),region("table",0x274 + length,field(0x250),"xor-ff"),region("segment-a",0x274 + field(0x25c),field(0x260),"stored"),region("segment-b",0x274 + field(0x268),field(0x26c),"stored")))
    }
    /** FW-010: decode the actual MOVW/MOVT/rotated-immediate descriptor producers and consumer. */
    fun workspace(image: FirmwareImage, loader: ByteArray, apertureBase: Long, regions: List<com.github.nyabanban.fuji.emulator.api.MemoryRegion>, segmentPlan: SegmentsContracts.VerifiedPlan? = null): Map<String, Any> {
        val code = ByteBuffer.wrap(loader).order(ByteOrder.LITTLE_ENDIAN)
        fun word(at: Int) = code.getInt(at).toLong() and 0xffffffffL
        fun pair(at: Int, reg: Int): Long {
            val lo = word(at); val hi = word(at + 4)
            require((lo and 0xfff0f000L) == (0xe3000000L or (reg.toLong() shl 12)) && (hi and 0xfff0f000L) == (0xe3400000L or (reg.toLong() shl 12))) { "DAT MOVW/MOVT producer changed" }
            fun immediate(w: Long) = ((w ushr 4) and 0xf000) or (w and 0xfff)
            return immediate(lo) or (immediate(hi) shl 16)
        }
        val offset = pair(0x6d4,0); val destination = pair(0x6ec,0); val consumer = pair(0x135c,3)
        val countWord = word(0x6e4); require(countWord and 0xfffff000L == 0xe3a02000L)
        val count = Integer.rotateRight((countWord and 255).toInt(),((countWord ushr 8) and 15).toInt() * 2).toLong() and 0xffffffffL
        require(offset == 0x140000L && destination == 0xe20000L && consumer == destination && count == 0x400L)
        require(segmentPlan == null || segmentPlan.regions == regions.sortedBy { it.address })
        require(regions.any { it.address <= destination && count <= it.address + it.size - destination && it.permissions == (if(segmentPlan == null) 3 else 7) }) { "workspace needs declared RW/NX RAM or DAT-verified merged segment profile" }
        val bytes = ByteBuffer.allocate(count.toInt()).order(ByteOrder.LITTLE_ENDIAN); image.inputPort().read(0x274 + offset,bytes)
        repeat(bytes.capacity()) { bytes.put(it,bytes.get(it).toInt().inv().toByte()) }
        val words = List(8) { bytes.getInt(it * 4).toLong() and 0xffffffffL }
        require(words == listOf(destination + 0x40,destination + 0x1ec,0L,destination + 0x1cc,0L,0L,0L,0L)) { "DAT metadata pointers changed" }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
        return mapOf("flashOffset" to offset,"flashAddress" to apertureBase + offset,"datOffset" to 0x274 + offset,"destination" to destination,"size" to count,"consumer" to consumer,"headerWords" to words,"sha256" to sha,"matches" to true)
    }
    /** FW-008: selected DAT fields, independently decoded sections and real DMA-source bytes. */
    fun storage(image: FirmwareImage, flash: com.github.nyabanban.fuji.emulator.api.ReadOnlyImagePort): Map<String, Any> {
        val input = image.inputPort(); val header = ByteBuffer.allocate(0x250).order(ByteOrder.LITTLE_ENDIAN)
        input.read(0,header)
        require(header.getInt(0) == 6)
        val size = header.getInt(0x24c).toLong() and 0xffffffffL
        require(size > 0 && size <= input.size - 0x274 && flash.length == size)
        val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536); var at = 0L
        while (at < size) {
            val count = minOf(bytes.size.toLong(),size - at).toInt()
            input.read(0x274 + at,ByteBuffer.wrap(bytes,0,count)); repeat(count) { bytes[it] = bytes[it].toInt().inv().toByte() }
            digest.update(bytes,0,count); at += count
        }
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val prefixHash = digest.digest().joinToString("") { "%02x".format(it) }
        require(flash.sha256 == prefixHash) { "flash image does not match selected DAT prefix" }
        val sections = listOf("boot" to 0x20000L,"kernel" to 0x60000L).map { (name,offset) ->
            val decoded = image.decode(name); val actual = ByteBuffer.allocate(decoded.size); flash.read(offset,actual)
            require(!actual.hasRemaining() && decoded.contentEquals(actual.array())) { "raw flash $name differs from independent decode" }
            mapOf("name" to name,"flashOffset" to offset,"size" to decoded.size,"sha256" to hash(decoded),"matches" to true)
        }
        val source = image.decode("boot").copyOfRange(0xa440,0xa4c0)
        return mapOf("type" to 6,"datOffset" to 0x274,"sizeFieldOffset" to 0x24c,"prefixLength" to size,"prefixSha256" to prefixHash,
            "sections" to sections,"checksumReadSource" to 0xf002a440L,"checksumReadSize" to 128,"checksumReadSha256" to hash(source),"matches" to true)
    }
    /** FW-007: checks the selected original bytes, all 52 fields and the two excluded rows. */
    fun configuration(loader: ByteArray, groups: List<DeviceWindow>, counts: List<Int>): Map<String, Any> {
        require(groups.size == 20 && counts.size == 20 && counts.sum() == 52)
        val at = 0xa9f0; require(loader.size >= at + 54 * 16)
        val table = ByteBuffer.wrap(loader).order(ByteOrder.LITTLE_ENDIAN)
        var row = 0
        fun word(offset: Int) = table.getInt(offset).toLong() and 0xffffffffL
        groups.forEachIndexed { group, window ->
            require(window.port == "soc-configuration.group$group" && window.size == 0x84L)
            repeat(counts[group]) { member ->
                val offset = at + row * 16
                require(word(offset) == window.address && word(offset + 4) == window.address + 0x40 + member * 4 && word(offset + 8) == 0L && word(offset + 12) == 0x1ffL) { "DAT configuration row $row differs from selected layout" }
                row++
            }
        }
        require(word(at + 52 * 16) == 0xffffffffL && word(at + 52 * 16 + 4) == 0xfff4860cL && word(at + 52 * 16 + 8) == 2L && word(at + 52 * 16 + 12) == 255L)
        require(word(at + 53 * 16) == 0xffffffffL && word(at + 53 * 16 + 4) == 0xfff48604L && word(at + 53 * 16 + 8) == 0L && word(at + 53 * 16 + 12) == 16L)
        val digest = MessageDigest.getInstance("SHA-256").digest(loader.copyOfRange(at, at + 54 * 16)).joinToString("") { "%02x".format(it) }
        return mapOf("rowsChecked" to row, "groupsChecked" to groups.size, "excludedSentinelRows" to 2, "tableSha256" to digest, "matches" to true)
    }
}
