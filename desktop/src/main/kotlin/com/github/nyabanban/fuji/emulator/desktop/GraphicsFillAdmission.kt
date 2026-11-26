package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.GraphicsSuccessors
import com.github.nyabanban.fuji.emulator.api.GraphicsMode2Tile
import com.github.nyabanban.fuji.emulator.api.GraphicsMode2Workspace
import com.github.nyabanban.fuji.emulator.api.GraphicsFillSpans

internal object GraphicsFillAdmission {
    private const val profile = "graphics-fill-retained-experimental-v2"
    private val pins = mapOf(
        0x1401e44 to listOf(0xe5954034L),
        0x14023ec to listOf(0xe3a0200fL,0xe5941004L,0xe3a00010L,0xebffff63L,0xe3a0200fL,0xe5941008L,0xe3a00014L,0xebffff5fL,
            0xe5943000L,0xe3530000L,0x1a000011L,0xe3a0200fL,0xe594100cL,0xe3a00018L,0xebffff58L,0xe5941010L,0xe3510000L,0x1a00001fL),
        0x14024b4 to listOf(0xe3a0200fL,0xe3a0001cL,0xebffff32L),
        0x140218c to listOf(0xe92d4800L,0xe28db004L,0xebfffef5L),
        0x1401d84 to listOf(0xe3120001L,0x151b300cL,0x151bc00cL,0x1183300cL,0x138330ffL,0x150b300cL),
        0x1401d9c to listOf(0xe3120002L,0x151b300cL,0x151bc00cL,0x1183300cL,0x13833cffL,0x150b300cL),
        0x1401db4 to listOf(0xe3120004L,0x151b300cL,0x151bc00cL,0x1183300cL,0x138338ffL,0x150b300cL),
        0x1401dcc to listOf(0xe3120008L,0x151b300cL,0x151b200cL,0x11833002L,0x138334ffL,0x150b300cL),
        0x1401de4 to listOf(0xe3063b7cL,0xe34031d2L,0xe5932000L,0xe7923000L,0xe50b3008L,0xe51bc00cL,0xe51b3008L,0xe1c3300cL,
            0xe50b3008L,0xe51b300cL,0xe51bc008L,0xe0011003L,0xe181100cL,0xe50b1008L,0xe51b3008L,0xe7823000L),
    )

    fun observe(state:Map<String,Any?>, backing:Map<*,*>, base:Long, section:ByteArray):Map<String,Any?> {
        val declared = backing["graphicsFillTail"] as Map<*,*>
        val tail = state["graphicsFill"] as Map<*,*>
        require(base == 0x1021000L && state["graphicsFillTail"] == profile && tail["profile"] == profile &&
            declared["profile"] == profile && declared["laneMask"] == 15 && declared["readConfidence"] == "HYPOTHESIS")
        require(declared["offsets"] == listOf(16,20,24,28) && tail["offsets"] == listOf(16L,20L,24L,28L))
        require(declared["statusOffset"] == 52 && tail["statusOffset"] == 52L && declared["submitMask"] == 1 &&
            declared["accessLimitMaximum"] == GraphicsSuccessors.ACCESS_LIMIT && (tail["accessLimit"] as? Int) in 1..GraphicsSuccessors.ACCESS_LIMIT)
        val initial = tail["initialWords"] as List<*>
        val statuses = tail["statusReads"] as List<*>
        require(initial.size == 4 && statuses.size <= 512 && (initial + statuses).all { it is Long && it in 0..0xffffffffL } &&
            tail["retainedWords"] == initial && tail["readConfidence"] == declared["readConfidence"])
        require(tail["transactionCount"] == 0 && tail["statusCursor"] == 0 && tail["submissions"] == emptyList<Any>() && tail["accesses"] == emptyList<Any>())
        verifyVglibWords("Graphics fill",base,section,pins)
        val command = if ("commandInitialWords" in tail) {
            val registers = listOf(0x1214,0x1298,0x10d4,0x10dc,0x10d0,0x10e4,0x10ec,0x10e0)
            val contract = declared["commandRegisters"] as Map<*, *>
            require(contract == mapOf("setting" to "commandInitialWords", "offsets" to registers, "startOffsets" to listOf(0x10d0,0x10e0),
                "startValue" to 1, "laneMask" to 15, "completionEvents" to "NONE", "confidence" to "HYPOTHESIS"))
            val words = tail["commandInitialWords"]
            require(words is List<*> && words.size == registers.size && words.all { it is Long && it in 0..0xffffffffL } &&
                tail["commandOffsets"] == registers.map(Int::toLong) && tail["commandRetainedWords"] == words && tail["commandStarts"] == emptyList<Any>())
            verifyVglibWords("Graphics command registers",base,section,mapOf(
                0x1402fec to listOf(0xe2551002L,0x13a01001L,0xe24b004cL,0xebfffcb0L),
                0x14022d8 to listOf(0xe3550000L,0xe3a0200fL,0xe30f1fffL,0x13401100L,0xe3010214L,0xebffffa6L,0xe3a0200fL,0xe3a01000L,
                    0xe3010298L,0xebffffa2L,0xe5943004L,0xe3530000L,0x1a000010L,0xe594300cL,0xe3530000L,0x08bd8830L,
                    0xe3a0200fL,0xe5941008L,0xe30100e4L,0xebffff98L,0xe594100cL,0xe3a0200fL,0xe1a010a1L,0xe30100ecL,
                    0xebffff93L,0xe3a0200fL,0xe3a01001L,0xe30100e0L,0xebffff8fL,0xe8bd8830L,0xe3a0200fL,0xe5941000L,
                    0xe30100d4L,0xebffff8aL,0xe5941004L,0xe3a0200fL,0xe1a010a1L,0xe30100dcL,0xebffff85L,0xe3a0200fL,
                    0xe3a01001L,0xe30100d0L,0xebffff81L,0xeaffffe0L)))
            mapOf("commandRegisters" to contract, "commandInitialWords" to words)
        } else emptyMap()
        val copy = if ("copyInitialWords" in tail) {
            val contract = declared["copyRegisters"] as Map<*, *>
            val registers = listOf(0xc00,0xc04,0xc08,0xc0c,0xc10,0xc18,0xc20,0xc24,0xc28)
            val initialWords = tail["copyInitialWords"] as List<*>
            require(contract["offsets"] == registers && contract["submitOffset"] == 0xc18 && contract["submitValue"] == 1 &&
                contract["confidence"] == "HYPOTHESIS" && tail["copyOffsets"] == registers.map(Int::toLong))
            require(initialWords.size == registers.size && initialWords.all { it is Long && it in 0..0xffffffffL } &&
                tail["copyRetainedWords"] == initialWords && tail["copySubmissions"] == emptyList<Any>())
            verifyVglibWords("Graphics copy registers",base,section,mapOf(
                0x14024e8 to listOf(0xe3a0200fL,0xe5941000L,0xe3a00b03L,0xebffff24L,0xe3a0200fL,0xe5941004L,0xe3000c04L,0xebffff20L,
                    0xe5941014L,0xe3110008L,0x0a000015L,0xe3a0200fL,0xe3a00ec1L,0xebffff1aL,0xe3a0200fL,0xe594102cL,
                    0xe3a00ec2L,0xebffff16L,0xe3a0200fL,0xe5941030L,0xe3000c24L,0xebffff12L,0xe3a0200fL,0xe5941034L,
                    0xe3000c28L,0xebffff0eL,0xe5941028L,0xe3510000L,0xe3a0200fL,0x03a01001L,0xe3000c18L,0xebffff08L,0xe8bd8bf0L,
                    0xe3a0200fL,0xe1871808L,0xe3000c08L,0xebffff03L,0xe3a0200fL,0xe1851806L,0xe3000c0cL,0xebfffeffL,
                    0xe3a0200fL,0xe5941014L,0xe3a00ec1L,0xebfffefbL,0xeaffffebL)))
            val selected = if ("copyExecution" in tail) {
                val config = tail["copyExecution"] as Map<*, *>
                val model = contract["execution"] as Map<*, *>
                require(model["profile"] == "same-geometry-shared-ram-copy-experimental-v1" && config["profile"] == model["profile"] &&
                    model["geometryWord"] == 0x03000400 && model["surfaceBytes"] == 3145728 && model["control"] == 1 &&
                    model["submissionLimitMaximum"] == GraphicsSuccessors.SUBMISSION_LIMIT && model["completionEvents"] == "NONE" && model["confidence"] == "HYPOTHESIS")
                val source = config["sourceBase"] as Long
                require(config.keys - setOf("additionalDestinationBases", GraphicsMode2Tile.SETTING, GraphicsMode2Workspace.SETTING, "mode2TileProfile") == setOf("profile","sourceBase","destinationBase","surfaceBytes","submissionLimit"))
                val tile = if (GraphicsMode2Tile.SETTING in config) GraphicsMode2Tile.parse(config[GraphicsMode2Tile.SETTING]) else null
                val workspace = if (GraphicsMode2Workspace.SETTING in config) GraphicsMode2Workspace.parse(config[GraphicsMode2Workspace.SETTING]) else null
                if ("mode2TileProfile" in config) {
                    require(config["mode2TileProfile"] == "aligned-8x8-1024x768-v1" && workspace != null)
                    require(model["mode2AlignedTiles"] == mapOf("setting" to "mode2TileProfile",
                        "profile" to "aligned-8x8-1024x768-v1", "minWidth" to 8, "maxWidth" to 1024,
                        "minHeight" to 8, "maxHeight" to 768, "tileEdge" to 8, "bytesPerWord" to 4,
                        "confidence" to "HYPOTHESIS"))
                }
                if (workspace != null) {
                    require(tile == null || workspace.covers(tile))
                    require(model[GraphicsMode2Workspace.SETTING] == mapOf("maxBytes" to GraphicsMode2Workspace.MAX_BYTES,
                        "maxWidth" to GraphicsMode2Tile.WIDTH, "maxHeight" to GraphicsMode2Tile.HEIGHT,
                        "bytesPerWord" to 4, "tileEdge" to 8, "confidence" to "HYPOTHESIS"))
                }
                if (tile != null || workspace != null) {
                    require(model[GraphicsMode2Tile.SETTING] == mapOf("control" to 2, "geometryWord" to GraphicsMode2Tile.GEOMETRY,
                        "sourceStrideBytes" to GraphicsMode2Tile.WIDTH * 4, "tileEdge" to 8, "confidence" to "HYPOTHESIS"))
                    verifyVglibWords("Graphics mode 2 producer and software tile layout", base, section, mapOf(
                        0x137d1e4 to listOf(0xe3550000L,0x03a03002L,0x1a000081L),
                        0x137d230 to listOf(0xe58d3044L,0xe58d5064L,0xe58d4074L,0xe58dc048L,0xeb00b524L),
                        0x13aa734 to listOf(0xe3a03003L),
                        0x14024d0 to listOf(0xe590801cL,0xe5907018L,0xe5906024L,0xe5905020L),
                        0x13af5b8 to listOf(0xe3110004L,0xe2012001L,0x03a08000L,0x13a08002L,0xe2013002L,0xe0888004L,0xe0882002L,0xe083300cL),
                        0x13af70c to listOf(0xe59da070L,0xe18320daL,0xe14020f4L,0xeaffffa2L)))
                }
                val additional = if ("additionalDestinationBases" in config) {
                    val bases = config["additionalDestinationBases"]
                    require(bases is List<*> && bases.size in 1..15)
                    bases.map { require(it is Long); it }
                } else emptyList()
                val size = config["surfaceBytes"] as Long
                require(size == 3145728L && (config["submissionLimit"] as Int) in 1..GraphicsSuccessors.SUBMISSION_LIMIT && tail["copyReadbacks"] == emptyList<Any>())
                val surfaces = (additional + listOf(source, config["destinationBase"] as Long)).sorted()
                require(surfaces.all { it in 0..0x100000000L-size && it and 3L == 0L })
                require(surfaces.zipWithNext().all { (left, right) -> left + size <= right })
                mapOf("copyExecution" to config)
            } else emptyMap()
            mapOf("copyRegisters" to contract, "copyInitialWords" to initialWords) + selected
        } else emptyMap()
        val execution = if ("execution" in tail) {
            val contract = declared["execution"] as Map<*, *>
            val config = tail["execution"] as Map<*, *>
            require(contract["profile"] == "q0-register-shared-ram-fill-experimental-v2" && contract["profile"] == config["profile"] &&
                contract["opcode"] == 4 && contract["q0"] == 0 && contract["control"] == 0x0f100201 &&
                contract["bytesPerUnit"] == 4 && contract["maxBytes"] == 0x300000 && contract["submissionLimitMaximum"] == GraphicsSuccessors.SUBMISSION_LIMIT &&
                contract["completionEvents"] == "NONE" && contract["confidence"] == "HYPOTHESIS")
            require(config.keys - "additionalSurfaces" - GraphicsFillSpans.SETTING == setOf("profile","submissionLimit","surfaceBase","surfaceBytes") &&
                tail["readbacks"] == emptyList<Any>() && (config["submissionLimit"] as? Int) in 1..GraphicsSuccessors.SUBMISSION_LIMIT)
            val surfaces = buildList {
                add(config)
                if ("additionalSurfaces" in config) {
                    val extra = config["additionalSurfaces"]
                    require(extra is List<*> && extra.size in 1..15)
                    extra.forEach { row ->
                        require(row is Map<*, *> && row.keys == setOf("surfaceBase", "surfaceBytes"))
                        add(row)
                    }
                }
            }.map { surface ->
                val start = surface["surfaceBase"]
                val bytes = surface["surfaceBytes"]
                require(start is Long && bytes is Long && bytes in 4..0x300000L &&
                    start in 0..0x100000000L-bytes && (start or bytes) and 3L == 0L)
                start until start + bytes
            }.sortedBy { it.first }
            require(surfaces.zipWithNext().all { (left, right) -> left.last < right.first })
            if (GraphicsFillSpans.SETTING in config) GraphicsFillSpans.parse(config[GraphicsFillSpans.SETTING],
                surfaces.filter { it.first != config["surfaceBase"] })
            verifyVglibWords("Graphics opcode4 producer",base,section,mapOf(
                0x13aa52c to listOf(0xe3a02004L), 0x13aa53c to listOf(0xe284002cL),
                0x13aa54c to listOf(0xe5c42001L,0xe3a02028L,0xe12fff33L,0xe5973010L),
                0x13aa564 to listOf(0xe38336f1L,0xe584303cL,0xeb015c54L)))
            mapOf("execution" to (contract + config.entries.associate { it.key to it.value }))
        } else {
            require("readbacks" !in tail)
            emptyMap()
        }
        val renderer = if ("commandExecution" in tail) {
            val config = copy["copyExecution"] as Map<*,*>
            val workspace = GraphicsMode2Workspace.parse(config["mode2Workspace"])
            val target = config["sourceBase"] as Long
            require(target + 0x300000 <= workspace.base || workspace.base + workspace.bytes <= target)
            val selected = tail["commandExecution"]
            val contract = when (selected) {
                "axis-rect-morton-rgba-low-alpha-over-v1" -> declared["commandExecution"]
                "axis-rect-morton-opaque-gray-v1" -> declared["solidCommandExecution"]
                "axis-rect-morton-rgba-solid-alpha-v1" -> declared["rgbaSolidCommandExecution"]
                else -> error("Unknown graphics command execution profile")
            }
            val expected = when (selected) {
                "axis-rect-morton-opaque-gray-v1" -> mapOf("profile" to selected, "pairs" to 1, "maxDraws" to 11, "maxSolidRects" to 45, "confidence" to "HYPOTHESIS")
                "axis-rect-morton-rgba-solid-alpha-v1" -> mapOf("profile" to selected, "pairs" to 1, "maxDraws" to 256, "maxSolidRects" to 512, "maxGroups" to 1024, "confidence" to "HYPOTHESIS")
                else -> mapOf("profile" to selected, "pairs" to 1, "maxDraws" to 11, "confidence" to "HYPOTHESIS")
            }
            require(command.isNotEmpty() && tail["commandExecution"] == selected && tail["commandReadbacks"] == emptyList<Any>() &&
                contract == expected)
            verifyVglibWords("Graphics command producer",base,section,mapOf(
                0x13761a4 to listOf(0xe5962014L,0xe30fc046L,0xe5960008L,0xe30e3a18L),
                0x1377660 to listOf(0xe3000702L,0xe3400709L,0xe1a06003L,0xe58d0044L),
                0x1377064 to listOf(0xe5962040L,0xe30fc004L,0xe5960034L,0xe30e3a18L),
                0x1376354 to listOf(0xe5952040L,0xe30fc004L,0xe5950034L,0xe30e3a18L)))
            mapOf("commandExecution" to contract)
        } else emptyMap()
        val completion = if ("commandIrq92Tick" in tail) {
            val tick = tail["commandIrq92Tick"]
            require(renderer.isNotEmpty() && "graphicsIrq92Sequence" in state && tick is Long && tick in 1..0xffffffffL &&
                tail["commandIrq92History"] == emptyList<Any>())
            require(declared["commandCompletion"] == mapOf("setting" to "commandIrq92Tick","line" to 92,"statusBit" to 31,
                "clearOffsets" to listOf(6144,4128),"postStatusOffset" to 88,"sample" to 0,"capacity" to 1,"confidence" to "HYPOTHESIS"))
            verifyVglibWords("Command IRQ92",base,section,mapOf(
                0x1401e60 to listOf(0xea000015L,0xe1a02007L,0xe1a01006L,0xe1a00008L,0xebffffbeL,0xe1a02007L,0xe1a01006L,0xe3010020L,0xebffffbaL),
                0x1401ebc to listOf(0xe3540000L,0xbaffffe7L),0x1401f28 to listOf(0xe3530000L,0xba000006L),
                0x1401f4c to listOf(0xe3a00001L,0xe8bd8800L),
                0x14025ec to listOf(0xe3100001L,0x08bd8830L,0xe3a0100fL,0xe3a00058L,0xebfffedeL,0xe3100301L,0x1a000001L,0xebfffeefL)))
            mapOf("commandIrq92Tick" to tick,"commandCompletion" to declared["commandCompletion"])
        } else emptyMap()
        val pairs = if ("commandPairLimit" in tail) {
            val limit = tail["commandPairLimit"]
            require(renderer.isNotEmpty() && limit is Int && limit in 1..64)
            require(declared["commandPairSequence"] == mapOf("limitSetting" to "commandPairLimit","maxPairs" to 64,
                "irqSetting" to "commandIrq92Followups","confidence" to "HYPOTHESIS"))
            if ("commandIrq92Followups" in tail) {
                val deadlines = tail["commandIrq92Followups"]
                require(completion.isNotEmpty() && deadlines is List<*> && deadlines.size in 1 until limit)
                val ticks = listOf(tail["commandIrq92Tick"] as Long) + deadlines.map { it as Long }
                require(ticks.zipWithNext().all { (first,next) -> next in first+1..0xffffffffL })
                require(tail["commandIrq92FollowupHistories"] == deadlines.map { emptyList<Any>() })
            }
            mapOf("commandPairLimit" to limit) + (tail["commandIrq92Followups"]?.let { mapOf("commandIrq92Followups" to it) } ?: emptyMap())
        } else emptyMap()
        return mapOf("profile" to profile, "offsets" to tail["offsets"], "initialWords" to initial,
            "statusReads" to statuses, "accessLimit" to tail["accessLimit"], "submitMask" to declared["submitMask"],
            "confidence" to "HYPOTHESIS", "source" to declared["source"]) + execution + copy + command + renderer + completion + pairs
    }
}
