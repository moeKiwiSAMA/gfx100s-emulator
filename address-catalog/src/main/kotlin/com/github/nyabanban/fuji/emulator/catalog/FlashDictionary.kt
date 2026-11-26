package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Files
import java.nio.file.Path

object FlashDictionary {
    private val EVIDENCE_CLASSES = setOf("DAT", "SOURCE", "SIM", "HYPOTHESIS")
    private val FIELD_TYPES = setOf("u8", "u16le", "u32le", "ascii", "bytes", "parity2d")
    private val CHECKSUM_ALGORITHMS = setOf("magic-u16le", "parity2d-row-col-v1")
    private val HEX_REGEX = Regex("[0-9a-f]+")

    fun validate(flash: Map<String, Any?>, firmware: Map<String, Any?>) {
        val expectedKeys = setOf(
            "schema", "id", "firmwareId", "firmwareSha256", "totalSize",
            "eraseBlockBytes", "programPageBytes", "partitions", "domains", "checksums", "fields",
        )
        require(flash.keys == expectedKeys) { "flash dictionary keys differ: ${flash.keys}" }
        require(valueLong(flash["schema"]) == 1L) { "flash dictionary schema must be 1" }
        require(flash.text("id").safeId()) { "invalid flash dictionary id: ${flash["id"]}" }
        require(flash.text("firmwareId") == firmware.text("id")) { "flash dictionary firmwareId mismatch" }
        require(flash.text("firmwareSha256") == firmware.text("sha256")) { "flash dictionary firmwareSha256 mismatch" }

        val totalSize = flash.long("totalSize")
        val eraseBlockBytes = flash.long("eraseBlockBytes")
        val programPageBytes = flash.long("programPageBytes")
        require(totalSize > 0 && eraseBlockBytes > 0 && totalSize % eraseBlockBytes == 0L) {
            "flash totalSize must be a positive multiple of eraseBlockBytes"
        }
        require(programPageBytes in 1..eraseBlockBytes && eraseBlockBytes % programPageBytes == 0L) {
            "flash programPageBytes must divide eraseBlockBytes"
        }

        // Validate partitions
        val partitions = rows(flash["partitions"])
        require(partitions.isNotEmpty()) { "flash partitions must not be empty" }
        val partIds = HashSet<String>()
        val partIntervals = mutableListOf<Interval>()
        partitions.forEachIndexed { idx, p ->
            require(p.long("index") == idx.toLong()) { "flash partition index must be sequential at $idx" }
            val pid = p.text("id")
            require(pid.safeId() && partIds.add(pid)) { "invalid/duplicate flash partition id: $pid" }
            val firstBlock = p.long("firstBlock")
            val blockCount = p.long("blockCount")
            val base = p.long("base")
            val endExclusive = p.long("endExclusive")
            require(firstBlock >= 0 && blockCount >= 0) { "negative partition block coordinates: $pid" }
            require(base == firstBlock * eraseBlockBytes && endExclusive == (firstBlock + blockCount) * eraseBlockBytes) {
                "partition byte span does not match block coordinates: $pid"
            }
            require(base in 0..totalSize && endExclusive in base..totalSize) { "partition outside flash bounds: $pid" }
            require(p.long("flags") >= 0) { "invalid partition flags: $pid" }
            require(p.text("role").isNotBlank() && p.text("evidence").isNotBlank()) { "partition role/evidence required: $pid" }
            require(p.text("confidence") in EVIDENCE_CLASSES) { "invalid partition confidence: ${p["confidence"]} ($pid)" }
            if (blockCount > 0) {
                val span = Interval(base, endExclusive)
                require(partIntervals.none { it.overlaps(span) }) { "overlapping flash partitions: $pid" }
                partIntervals.add(span)
            }
        }
        val sortedParts = partIntervals.sortedBy { it.base }
        require(sortedParts.first().base == 0L && sortedParts.last().endExclusive == totalSize) {
            "flash partitions must span [0, ${hex(totalSize)})"
        }
        sortedParts.zipWithNext().forEach { (a, b) ->
            require(a.endExclusive == b.base) { "flash partition gap between ${hex(a.endExclusive)} and ${hex(b.base)}" }
        }

        // Validate supplemental domains
        val domains = rows(flash["domains"])
        require(domains.isNotEmpty()) { "flash domains must not be empty" }
        val domainMap = LinkedHashMap<String, Map<String, Any?>>()
        val domainIntervals = mutableListOf<Interval>()
        val domainFiles = HashSet<String>()
        domains.forEach { d ->
            val did = d.text("id")
            require(did.safeId() && domainMap.put(did, d) == null) { "invalid/duplicate flash domain id: $did" }
            val base = d.long("base")
            val length = d.long("length")
            require(length > 0 && base >= 0 && Math.addExact(base, length) <= totalSize) { "flash domain out of bounds: $did" }
            val span = Interval(base, base + length)
            require(domainIntervals.none { it.overlaps(span) }) { "overlapping flash domains: $did" }
            domainIntervals.add(span)
            val file = d.text("file")
            require(file.isNotBlank() && !file.contains('/') && !file.contains('\\') && domainFiles.add(file)) {
                "invalid/duplicate flash domain file: $file"
            }
            require(d.long("defaultFillByte") in 0..255) { "invalid defaultFillByte in $did" }
            require(d.text("role").isNotBlank() && d.text("evidence").isNotBlank()) { "domain role/evidence required: $did" }
            require(d.text("confidence") in EVIDENCE_CLASSES) { "invalid domain confidence: $did" }

            d["datSeed"]?.let { rawSeed ->
                val seed = obj(rawSeed)
                val datOffset = seed.long("datOffset")
                val seedLen = seed.long("length")
                require(datOffset >= 0 && seedLen in 1..length) { "invalid datSeed bounds in $did" }
                require(seed["invertBytes"] is Boolean) { "datSeed.invertBytes must be boolean in $did" }
                require(seed.text("confidence") in EVIDENCE_CLASSES && seed.text("evidence").isNotBlank()) {
                    "invalid datSeed provenance in $did"
                }
            }
            d["writableSubrange"]?.let { rawW ->
                val w = obj(rawW)
                val wBase = w.long("base")
                val wLen = w.long("length")
                require(wLen > 0 && wBase >= base && wBase + wLen <= base + length) {
                    "writableSubrange outside domain $did"
                }
                val wFile = w.text("file")
                require(wFile.isNotBlank() && !wFile.contains('/') && !wFile.contains('\\')) {
                    "invalid writableSubrange file in $did"
                }
                require(w.long("programPageBytes") == programPageBytes) { "writableSubrange programPageBytes mismatch in $did" }
                require(w.text("programLaw").isNotBlank()) { "writableSubrange programLaw required in $did" }
            }
        }

        // Validate checksums
        val checksums = rows(flash["checksums"])
        val checksumIds = HashSet<String>()
        checksums.forEach { c ->
            val cid = c.text("id")
            require(cid.safeId() && checksumIds.add(cid)) { "invalid/duplicate flash checksum id: $cid" }
            val domain = domainMap[c.text("domainId")] ?: error("unknown checksum domainId: ${c["domainId"]}")
            val domainLen = domain.long("length")
            val algo = c.text("algorithm")
            require(algo in CHECKSUM_ALGORITHMS) { "unsupported flash checksum algorithm: $algo ($cid)" }
            val pOff = c.long("payloadOffset")
            val pLen = c.long("payloadLength")
            val oOff = c.long("outputOffset")
            val oLen = c.long("outputLength")
            require(pOff >= 0 && pLen > 0 && pOff + pLen <= domainLen) { "checksum payload out of bounds: $cid" }
            require(oOff >= 0 && oLen > 0 && oOff + oLen <= domainLen) { "checksum output out of bounds: $cid" }
            if (algo == "parity2d-row-col-v1") {
                require(pLen % 4 == 0L && oLen == 0x104L) { "parity2d-row-col-v1 requires 4-byte aligned payload and 260B output: $cid" }
                require(!Interval(pOff, pOff + pLen).overlaps(Interval(oOff, oOff + oLen))) {
                    "parity2d payload and output overlap: $cid"
                }
            } else if (algo == "magic-u16le") {
                val expectedHex = c.text("expectedHex")
                require(expectedHex.length == 4 && expectedHex.matches(HEX_REGEX)) { "invalid expectedHex for $cid" }
            }
            require(c.text("confidence") in EVIDENCE_CLASSES && c.text("evidence").isNotBlank() && c.text("description").isNotBlank()) {
                "invalid checksum metadata: $cid"
            }
        }

        // Validate persistent fields (strictly non-overlapping per domain)
        val fields = rows(flash["fields"])
        val fieldIds = HashSet<String>()
        val fieldIntervalsByDomain = HashMap<String, MutableList<Interval>>()
        fields.forEach { f ->
            val fid = f.text("id")
            require(fid.safeId() && fieldIds.add(fid)) { "invalid/duplicate flash field id: $fid" }
            val domainId = f.text("domainId")
            val domain = domainMap[domainId] ?: error("unknown flash field domainId: $domainId ($fid)")
            val domainLen = domain.long("length")
            val relOff = f.long("relativeOffset")
            val len = f.long("length")
            require(relOff >= 0 && len > 0 && relOff + len <= domainLen) { "flash field out of bounds: $fid" }
            val type = f.text("type")
            require(type in FIELD_TYPES) { "invalid flash field type: $type ($fid)" }
            if (type == "u8") require(len == 1L) { "u8 field must have length 1: $fid" }
            if (type == "u16le") require(len == 2L) { "u16le field must have length 2: $fid" }
            if (type == "u32le") require(len == 4L) { "u32le field must have length 4: $fid" }
            require(f.text("confidence") in EVIDENCE_CLASSES) { "invalid flash field confidence: $fid" }
            require(f["applyInFactoryTemplate"] is Boolean) { "applyInFactoryTemplate must be boolean: $fid" }
            val hexVal = f["factoryDefaultHex"]
            if (hexVal != null) {
                require(hexVal is String && hexVal.length.toLong() == len * 2 && hexVal.matches(HEX_REGEX)) {
                    "invalid factoryDefaultHex for $fid (expected ${len * 2} lowercase hex chars)"
                }
            } else {
                require(f["applyInFactoryTemplate"] == false) {
                    "applyInFactoryTemplate=true requires non-null factoryDefaultHex: $fid"
                }
            }
            require(f.text("evidence").isNotBlank() && f.text("description").isNotBlank()) {
                "field evidence/description required: $fid"
            }
            val span = Interval(relOff, relOff + len)
            val list = fieldIntervalsByDomain.getOrPut(domainId) { mutableListOf() }
            require(list.none { it.overlaps(span) }) { "overlapping flash fields in $domainId: $fid" }
            list.add(span)
        }
    }

    /** Cross-check tools/release/factory-flash-template.yaml against the authoritative Flash YAML dictionary. */
    fun verifyReleaseTemplate(flash: Map<String, Any?>, repository: Path) {
        val templatePath = repository.resolve("tools/release/factory-flash-template.yaml")
        if (!Files.isRegularFile(templatePath)) return
        val template = CatalogIO.readYaml(templatePath)
        require(template.text("dictionaryRef") == "catalog/flash/gfx100s-213.yaml") {
            "factory-flash-template.yaml must declare dictionaryRef: catalog/flash/gfx100s-213.yaml"
        }
        val dictDomains = rows(flash["domains"]).associateBy { it.text("id") }
        val tplDomains = rows(template["domains"])
        require(tplDomains.size == dictDomains.size) {
            "factory-flash-template.yaml domain count (${tplDomains.size}) differs from Flash dictionary (${dictDomains.size})"
        }
        tplDomains.forEach { td ->
            val did = td.text("id")
            val dd = dictDomains[did] ?: error("factory-flash-template.yaml references unknown domain: $did")
            require(td.long("offset") == dd.long("base") && td.long("length") == dd.long("length") && td.text("file") == dd.text("file")) {
                "factory-flash-template.yaml domain $did differs from catalog/flash/gfx100s-213.yaml"
            }
            val tplFill = td["defaultFillByte"]?.let(::valueLong) ?: template.long("defaultFillByte")
            require(tplFill == dd.long("defaultFillByte")) {
                "factory-flash-template.yaml defaultFillByte mismatch for $did"
            }
            val dSeed = dd["datSeed"]?.let(::obj)
            val tSeed = td["datSeed"]?.let(::obj)
            if (dSeed == null) {
                require(tSeed == null) { "unexpected datSeed in factory-flash-template.yaml for $did" }
            } else {
                require(tSeed != null && tSeed.long("datOffset") == dSeed.long("datOffset") &&
                    tSeed.long("length") == dSeed.long("length") && tSeed["invertBytes"] == dSeed["invertBytes"]) {
                    "factory-flash-template.yaml datSeed mismatch for $did"
                }
            }
        }
        val writableEntries = rows(obj(template["writableRegions"])["entries"])
        val dictWritables = dictDomains.values.filter { it["writableSubrange"] != null }
        require(writableEntries.size == dictWritables.size) {
            "factory-flash-template.yaml writableRegions count differs from Flash dictionary"
        }
        writableEntries.forEach { we ->
            val did = we.text("regionId")
            val dw = obj(dictDomains.getValue(did)["writableSubrange"])
            require(we.text("file") == dw.text("file") && we.long("writableOffset") == dw.long("base") &&
                we.long("writableLength") == dw.long("length") && we.long("programPageBytes") == dw.long("programPageBytes") &&
                we.text("programLaw") == dw.text("programLaw")) {
                "factory-flash-template.yaml writableRegions mismatch for $did"
            }
        }
        val expectedPatches = rows(flash["fields"]).filter { it["applyInFactoryTemplate"] == true }.associateBy { it.text("id") }
        val tplFields = rows(template["fields"])
        require(tplFields.size == expectedPatches.size) {
            "factory-flash-template.yaml fields count (${tplFields.size}) differs from Flash dictionary applyInFactoryTemplate count (${expectedPatches.size})"
        }
        tplFields.forEach { tf ->
            val fid = tf.text("id")
            val df = expectedPatches[fid] ?: error("factory-flash-template.yaml field $fid not marked applyInFactoryTemplate=true in Flash dictionary")
            require(tf.text("regionId") == df.text("domainId") &&
                tf.long("relativeOffset") == df.long("relativeOffset") &&
                tf.text("hex").lowercase() == df.text("factoryDefaultHex") &&
                tf.text("confidence") == df.text("confidence")) {
                "factory-flash-template.yaml field $fid differs from catalog/flash/gfx100s-213.yaml"
            }
        }
    }

    /** Exact GFX100S 2.13 BlockA/BlockB 2D bit-parity ECC law (`section4 @ 0x012A2D34`). */
    fun computeParity2d(block: ByteArray, payloadLength: Int = 0x1EFC): Pair<ByteArray, ByteArray> {
        require(payloadLength > 0 && payloadLength % 4 == 0 && block.size >= payloadLength) {
            "parity2d payload must be positive, 4-byte aligned, and within block"
        }
        val rowCount = payloadLength / 4
        require((rowCount + 7) / 8 <= 256) { "parity2d row parity exceeds 256 bytes" }
        val rowParity = ByteArray(256)
        val colParity = ByteArray(4)
        for (row in 0 until rowCount) {
            val rowBase = row * 4
            val rowBit = 1 shl (row and 7)
            val rowByteIdx = row ushr 3
            for (bit in 0 until 32) {
                val colByteIdx = bit ushr 3
                val colBit = 1 shl (bit and 7)
                if ((block[rowBase + colByteIdx].toInt() and colBit) != 0) {
                    rowParity[rowByteIdx] = (rowParity[rowByteIdx].toInt() xor rowBit).toByte()
                    colParity[colByteIdx] = (colParity[colByteIdx].toInt() xor colBit).toByte()
                }
            }
        }
        return rowParity to colParity
    }

    fun renderMarkdown(flash: Map<String, Any?>, catalog: AddressCatalog): String {
        val sb = StringBuilder()
        sb.append("# Generated GFX100S 2.13 Flash layout and field dictionary\n\n")
        sb.append("Generated from `catalog/flash/gfx100s-213.yaml` by `:address-catalog:compileCatalog`. Do not edit.\n\n")
        sb.append("Dictionary `").append(flash.text("id")).append("` / Catalog `").append(catalog.version)
            .append("` / SHA256 `").append(catalog.artifactSha256).append("`. Firmware `")
            .append(flash.text("firmwareId")).append("` / `").append(flash.text("firmwareSha256")).append("`.\n\n")
        sb.append("- **Total NOR Flash size**: `").append(hex(flash.long("totalSize"))).append("` (")
            .append(flash.long("totalSize") / (1024 * 1024)).append(" MiB)\n")
        sb.append("- **Erase block size**: `").append(hex(flash.long("eraseBlockBytes"))).append("` (")
            .append(flash.long("eraseBlockBytes") / 1024).append(" KiB)\n")
        sb.append("- **Program page size**: ").append(flash.long("programPageBytes")).append(" bytes\n")
        sb.append("- **Privacy boundary**: Contains only public `GXUP0004.DAT` firmware constants and synthetic `HYPOTHESIS` factory defaults — zero private personal camera data.\n\n")

        sb.append("## NOR Flash partition table (32 entries, `section4 @ 0x017476E8`)\n\n")
        sb.append("| Index | Partition ID | Blocks | Logical interval | Flags | Role | Confidence | Evidence |\n")
        sb.append("| ---: | --- | --- | --- | ---: | --- | --- | --- |\n")
        rows(flash["partitions"]).forEach { p ->
            val first = p.long("firstBlock")
            val count = p.long("blockCount")
            val blocksText = if (count == 0L) "0 (@$first)" else "$count ($first..${first + count - 1})"
            sb.append("| ").append(p.long("index"))
                .append(" | `").append(p.text("id")).append("`")
                .append(" | ").append(blocksText)
                .append(" | `[").append(hex(p.long("base"))).append(", ").append(hex(p.long("endExclusive"))).append(")`")
                .append(" | ").append(p.long("flags"))
                .append(" | ").append(p.text("role"))
                .append(" | `").append(p.text("confidence")).append("`")
                .append(" | ").append(p.text("evidence")).append(" |\n")
        }

        sb.append("\n## Supplemental and writable COW Flash domains\n\n")
        sb.append("| Domain ID | Logical interval | File | Fill | DAT seed | Writable COW subrange | Confidence |\n")
        sb.append("| --- | --- | --- | --- | --- | --- | --- |\n")
        rows(flash["domains"]).forEach { d ->
            val base = d.long("base")
            val len = d.long("length")
            val seedText = d["datSeed"]?.let {
                val s = obj(it)
                "`GXUP0004.DAT+${hex(s.long("datOffset"))}` (${hex(s.long("length"))}B, inv=${s["invertBytes"]}, `${s.text("confidence")}`)"
            } ?: "none"
            val writableText = d["writableSubrange"]?.let {
                val w = obj(it)
                "`[${hex(w.long("base"))}, ${hex(w.long("base") + w.long("length"))})` -> `${w.text("file")}`"
            } ?: "read-only"
            sb.append("| `").append(d.text("id")).append("`")
                .append(" | `[").append(hex(base)).append(", ").append(hex(base + len)).append(")`")
                .append(" | `").append(d.text("file")).append("`")
                .append(" | `0x").append(d.long("defaultFillByte").toString(16).padStart(2, '0')).append("`")
                .append(" | ").append(seedText)
                .append(" | ").append(writableText)
                .append(" | `").append(d.text("confidence")).append("` |\n")
        }

        sb.append("\n## Header magic and checksum / ECC laws\n\n")
        sb.append("| Checksum ID | Domain | Algorithm | Payload range | Output range | Confidence | Description |\n")
        sb.append("| --- | --- | --- | --- | --- | --- | --- |\n")
        rows(flash["checksums"]).forEach { c ->
            val pOff = c.long("payloadOffset")
            val pLen = c.long("payloadLength")
            val oOff = c.long("outputOffset")
            val oLen = c.long("outputLength")
            sb.append("| `").append(c.text("id")).append("`")
                .append(" | `").append(c.text("domainId")).append("`")
                .append(" | `").append(c.text("algorithm")).append("`")
                .append(" | `[+${hex(pOff)}, +${hex(pOff + pLen)})`")
                .append(" | `[+${hex(oOff)}, +${hex(oOff + oLen)})`")
                .append(" | `").append(c.text("confidence")).append("`")
                .append(" | ").append(c.text("description")).append(" |\n")
        }

        sb.append("\n## Researched persistent configuration fields\n\n")
        sb.append("| Field ID | Domain | Relative offset | Length | Type | Confidence | Factory template patch | Default hex | Description |\n")
        sb.append("| --- | --- | --- | ---: | --- | --- | --- | --- | --- |\n")
        rows(flash["fields"]).forEach { f ->
            val hexVal = f["factoryDefaultHex"]?.let { "`$it`" } ?: "runtime/DAT"
            sb.append("| `").append(f.text("id")).append("`")
                .append(" | `").append(f.text("domainId")).append("`")
                .append(" | `+").append(hex(f.long("relativeOffset"))).append("`")
                .append(" | ").append(f.long("length"))
                .append(" | `").append(f.text("type")).append("`")
                .append(" | `").append(f.text("confidence")).append("`")
                .append(" | ").append(if (f["applyInFactoryTemplate"] == true) "yes" else "no")
                .append(" | ").append(hexVal)
                .append(" | ").append(f.text("description")).append(" |\n")
        }
        return sb.toString()
    }

    private fun hex(value: Long) = "0x" + value.toString(16)
}
