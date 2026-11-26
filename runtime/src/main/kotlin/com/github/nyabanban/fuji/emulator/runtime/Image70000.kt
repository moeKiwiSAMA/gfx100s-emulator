package com.github.nyabanban.fuji.emulator.runtime

internal object Image70000 {
    const val REGION = "diagnostic.image70000.011b811c"
    const val SECOND_DRIVER_GETTERS_PROFILE = "t79-second-driver-getters-v1"
    const val BASE = 0xfff70000L
    const val SIZE = 0x2ec
    private data class Step(val offset: Int, val lr: Long, val word: Long, val write: Boolean = false,
                            val payloadMask: Long = 0, val retainedMask: Long = 0)
    private val setup = listOf(
        Step(0, 0x11b8154, 0),
        Step(0, 0x11b8160, 0, write = true),
        Step(0, 0x11b8170, 1),
        Step(0x2e8, 0x11b8188, 0x10000),
        Step(0x2e4, 0x11b81a0, 0),
        Step(0x2e4, 0x11b81ac, 0, write = true),
        Step(0x0c, 0x11b7e50, 0),
        Step(0x0c, 0x11b7f0c, 0, write = true),
        Step(0x0c, 0x11b7c58, 0),
    )
    private val configured = setup + listOf(
        Step(0x19c, 0x11b8600, 0x10201, true),
        Step(0x1a0, 0x11b8620, 0x02000000, true),
        Step(0x1a4, 0x11b8640, 0x02000000, true),
        Step(0x1a8, 0x11b8654, 4, true),
        Step(0x1fc, 0x11b86a0, 0, true),
        Step(0x1f8, 0x11b86c0, 0x100, true),
        Step(0x208, 0x11b86e0, 0, true),
        Step(0x20c, 0x11b86f4, 0, true),
        Step(0x210, 0x11b8714, 0, true),
        Step(0x214, 0x11b8728, 0, true),
        Step(0x218, 0x11b8748, 0, true),
        Step(0x21c, 0x11b8768, 0, true),
        Step(0x220, 0x11b89b0, 2, true),
        Step(0x01c, 0x11b89e0, 0x66480, true),
        Step(0x198, 0x11b89f4, 0xfff, true),
    )

    private val updateWrites = configured + buildList {
        fun word(offset: Int, lr: Long, value: Long) { add(Step(offset, lr, value, write = true)) }
        word(0x134, 0x11ba364, 0x80ff1)
        word(0x138, 0x11ba394, 0x950f2b)
        word(0x13c, 0x11ba3b4, 0)
        for (pair in 0 until 10) {
            word(0x140 + pair * 8, 0x11ba604, if (pair == 0) 0x7070121 else 0x10000)
            word(0x144 + pair * 8, 0x11ba628, if (pair == 0) 0x1c4005f else 0x10000)
        }
        word(0x05c, 0x11b8d40, 0x80007)
        word(0x060, 0x11b8d68, 0x20007)
        word(0x064, 0x11b8d88, 0)
        word(0x068, 0x11b90b0, 0x80007)
        word(0x06c, 0x11b90d8, 0x20007)
        word(0x070, 0x11b90f8, 0)
        word(0x190, 0x11bd778, 0x80007)
        word(0x194, 0x11bd79c, 0x20007)
        for (pair in 0 until 5) {
            word(0x074 + pair * 8, 0x11b93c8, 0x80000)
            word(0x078 + pair * 8, 0x11b93e4, 0x20001)
        }
        word(0x124, 0x11ba8a0, 0x80007)
        word(0x128, 0x11ba8cc, 0x20007)
        word(0x12c, 0x11ba8ec, 0)
        word(0x1b0, 0x11bad1c, 0x210f1d)
        word(0x1ac, 0x11bad40, 0xa0003)
        word(0x1b8, 0x11baee8, 0x210f1d)
        word(0x1b4, 0x11baf0c, 0x40005)
        word(0x1c0, 0x11bb0b4, 0x10001)
        word(0x1bc, 0x11bb0d8, 0x10000)
        word(0x1c4, 0x11bd958, 0x10101)
        word(0x1c8, 0x11bd97c, 0x10000)
        word(0x1cc, 0x11bdb5c, 0x10101)
        word(0x1d0, 0x11bdb80, 0x10000)
        word(0x1d4, 0x11bdd54, 0x10001)
        word(0x1d8, 0x11bdd84, 0x10000)
        word(0x1dc, 0x11bdd94, 0)
        word(0x1e0, 0x11bdf78, 0x10001)
        word(0x1e4, 0x11bdfa8, 0x10000)
        word(0x1e8, 0x11bdfb8, 0)
        word(0x1ec, 0x11be1b4, 0x10001)
        word(0x1f0, 0x11be1dc, 1)
        word(0x1f4, 0x11be1fc, 1)
        word(0x224, 0x11bc3e0, 1)
        word(0x228, 0x11bc404, 0)
    }
    private val updated = updateWrites + listOf(
        Step(0x134, 0x11ba500, 0x80ff1), Step(0x138, 0x11ba52c, 0x950f2b), Step(0x13c, 0x11ba558, 0))
    val driverStart get() = updateWrites.size
    private const val CONTROL = 0x2e4
    private val driver = updateWrites + buildList {
        fun address(offset: Int, lr: Long, lowMask: Long = 0xffffffe0) {
            add(Step(offset, lr, 0, true, payloadMask = lowMask))
            add(Step(offset + 4, lr + 0x10, 0, true, payloadMask = 3))
        }
        address(0x254, 0x11bb4ac)
        address(0x22c, 0x11bb244)
        address(0x244, 0x11bb714)
        address(0x24c, 0x11bb848)
        address(0x274, 0x11bb97c)
        address(0x27c, 0x11bbab0)
        address(0x284, 0x11bbbe4)
        address(0x28c, 0x11bbd18)
        address(0x294, 0x11bbe4c)
        address(0x29c, 0x11bbf80)
        address(0x2a4, 0x11bc0b4)
        address(0x2ac, 0x11bc1e8)
        address(0x25c, 0x11bb5e0)
        address(0x234, 0x11bb378)
        address(0x2b4, 0x11bc5a8)
        address(0x2bc, 0x11bc6dc)
        address(0x2c4, 0x11bc810)
        address(0x2cc, 0x11bc944)
        address(0x264, 0x11be6b8)
        address(0x26c, 0x11be7ec)
        address(0x23c, 0x11be584)
        address(0x014, 0x11bd11c, lowMask = 0xffffffff)
        add(Step(0x010, 0x11bd000, 0, true, payloadMask = 0x10001))
        add(Step(CONTROL, 0x11bc318, 0))
        add(Step(CONTROL, 0x11bc32c, 0, true, payloadMask = 0xffff0000))
        add(Step(CONTROL, 0x11bcdd0, 0, retainedMask = 0xffff0000))
        add(Step(CONTROL, 0x11bcde8, 0x20, true, retainedMask = 0xffff0000))
        add(Step(CONTROL, 0x11bce18, 0x20, retainedMask = 0xffff0000))
        add(Step(CONTROL, 0x11bce24, 0x120, true, retainedMask = 0xffff0000))
        add(Step(4, 0x11b8238, 0))
        add(Step(4, 0x11b82c0, 0x03ff03ff, true))
        add(Step(8, 0x11b82cc, 0))
        add(Step(8, 0x11b8354, 0x03df03df, true))
    }

    private val getterPass = buildList {
        fun read(offset: Int, vararg callers: Long) {
            for ((index, caller) in callers.withIndex()) {
                val address = offset + index * 4
                val written = driver.last { it.write && it.offset == address }
                check(written.payloadMask == 0L && written.retainedMask == 0L)
                add(Step(address, caller, written.word))
            }
        }
        read(0x134, 0x11ba500, 0x11ba52c, 0x11ba558)
        repeat(10) { read(0x140 + 8 * it, 0x11ba74c, 0x11ba768) }
        read(0x05c, 0x11b8f14, 0x11b8f40, 0x11b8f6c)
        read(0x068, 0x11b9284, 0x11b92b0, 0x11b92dc)
        read(0x190, 0x11bd878, 0x11bd898)
        repeat(5) { read(0x074 + 8 * it, 0x11b9558, 0x11b9574) }
        read(0x124, 0x11baa38, 0x11baa64, 0x11baa90)
        read(0x1b0, 0x11bae1c)
        read(0x1ac, 0x11bae3c)
        read(0x1b8, 0x11bafe8)
        read(0x1b4, 0x11bb008)
        read(0x1c0, 0x11bb1b4)
        read(0x1bc, 0x11bb1d4)
        read(0x1c4, 0x11bda74, 0x11bda9c)
        read(0x1cc, 0x11bdc78, 0x11bdca0)
        read(0x1d4, 0x11bde8c, 0x11bdeac, 0x11bdebc)
        read(0x1e0, 0x11be0b0, 0x11be0d0, 0x11be0e0)
        read(0x1ec, 0x11be310, 0x11be32c, 0x11be350)
        read(0x224, 0x11bc518, 0x11bc538)
        add(Step(0x200, 0x11be4f4, 0))
        add(Step(0x204, 0x11be510, 0))
    }
    private val getters = driver + getterPass + getterPass
    private val teardown = getters + buildList {
        fun clear(offset: Int, readLr: Long, writeLr: Long, lowClear: Long, set: Long = 0) {
            val previous = driver.last { it.write && it.offset == offset }
            check(previous.payloadMask == 0L)
            add(previous.copy(lr = readLr, write = false))
            add(previous.copy(lr = writeLr, word = (previous.word and lowClear.inv()) or set))
        }
        clear(4, 0x11b8238, 0x11b82c0, 0x3ff, 0x03ff0000)
        clear(8, 0x11b82cc, 0x11b8354, 0x3ff, 0x03ff0000)
        clear(CONTROL, 0x11bcec8, 0x11bced4, 0x100)
        add(Step(0x2e8, 0x11bcee4, 0x10000))
    }
    private val secondSetup = teardown + setup.map { step ->
        when {
            step == setup.first() -> step.copy(word = setup.last { it.offset == 0 }.word)
            step.offset == CONTROL -> teardown.last { it.offset == CONTROL }.copy(lr = step.lr, write = step.write)
            else -> step
        }
    }
    private val reinitialized = secondSetup + configured.drop(setup.size).map { step ->
        val mask = when (step.offset) {
            0x19c -> 0x10301L
            0x1a0, 0x1a4, 0x218, 0x21c -> 0xffffffffL
            0x1a8 -> 0xfL
            0x1fc, 0x208, 0x210 -> 0x3fff3fffL
            0x1f8 -> 0x3f0101L
            0x20c, 0x214 -> 0x3fffL
            0x220 -> 3L
            0x01c -> 0L
            0x198 -> 0xfffL
            else -> error("Unknown configuration word at ${step.offset}")
        }
        step.copy(word = step.word and mask.inv(), payloadMask = mask)
    }

    private val configurationReads = reinitialized + Image70000ConfigWords.reads.map { read ->
        check(reinitialized.none { it.offset == read.offset && it.write })
        Step(read.offset, read.lr, Image70000ConfigWords.INITIAL_WORD)
    }

    private val secondDriverSteps = driver.drop(updateWrites.size).map { step ->
        when {
            step.offset == CONTROL && step.lr == 0x11bc318L ->
                step.copy(word = 0x20, retainedMask = 0xffff0000)
            step.offset == CONTROL && step.lr == 0x11bc32cL ->
                step.copy(word = 0x20)
            step.offset == CONTROL && step.lr == 0x11bcdd0L ->
                step.copy(word = 0x20)
            step.offset == 4 && !step.write ->
                step.copy(word = 0x03ff0000)
            step.offset == 8 && !step.write ->
                step.copy(word = 0x03ff0000)
            step.offset == 8 && step.write ->
                step.copy(word = 0x03ff03df)
            else -> step
        }
    }
    private val secondGettersSteps = getterPass + getterPass
    val secondDriverStart = configurationReads.size + T79ImageConfigSetter.accessLimit + T79ImageSecondUpdate.accessLimit
    val secondDriverControlPosition = secondDriverStart + secondDriverSteps.indexOfFirst { it.offset == CONTROL && it.payloadMask != 0L }
    val secondGettersStart = secondDriverStart + secondDriverSteps.size
    val statefulStart = secondGettersStart + secondGettersSteps.size
    val statefulAllowedOffsets: Set<Int> = (
        setup.map { it.offset } +
            configured.map { it.offset } +
            updateWrites.map { it.offset } +
            driver.map { it.offset } +
            getterPass.map { it.offset } +
            T79ImageConfigSetter.offsets +
            listOf((ImageIrq106Status.ADDRESS - BASE).toInt())
        ).toSet()

    init {
        check(secondDriverSteps.size == 55 && getterPass.size == 67 && secondGettersSteps.size == 134)
        check(getterPass.map { it.offset }.filter { it != 0x200 && it != 0x204 }.toSet() == T79ImageSecondUpdate.offsets.toSet())
    }

    fun includesSecondSetup(strategy: DiscoveryStrategy?) =
        strategy == DiscoveryStrategy.IMAGE_70000_REINIT || strategy == DiscoveryStrategy.IMAGE_70000_CONFIG_READS

    fun inSecondSetup(strategy: DiscoveryStrategy, position: Int) =
        includesSecondSetup(strategy) && position in teardown.size until secondSetup.size

    fun inStatefulBank(strategy: DiscoveryStrategy, position: Int) =
        strategy == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK && position >= statefulStart

    fun includesDriver(strategy: DiscoveryStrategy?) =
        strategy == DiscoveryStrategy.IMAGE_70000_DRIVER || strategy == DiscoveryStrategy.IMAGE_70000_GETTERS ||
            strategy == DiscoveryStrategy.IMAGE_70000_TEARDOWN || includesSecondSetup(strategy) ||
            strategy == DiscoveryStrategy.IMAGE_70000_SETTER ||
            strategy == DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE ||
            strategy == DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS ||
            strategy == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK

    private fun steps(strategy: DiscoveryStrategy) = when (strategy) {
        DiscoveryStrategy.IMAGE_70000 -> setup
        DiscoveryStrategy.IMAGE_70000_CONFIG -> configured
        DiscoveryStrategy.IMAGE_70000_UPDATE -> updated
        DiscoveryStrategy.IMAGE_70000_DRIVER -> driver
        DiscoveryStrategy.IMAGE_70000_GETTERS -> getters
        DiscoveryStrategy.IMAGE_70000_TEARDOWN -> teardown
        DiscoveryStrategy.IMAGE_70000_REINIT -> reinitialized
        DiscoveryStrategy.IMAGE_70000_CONFIG_READS -> configurationReads
        else -> error("Unsupported image70000 strategy")
    }

    internal fun migrationSteps(strategy: DiscoveryStrategy): List<Pair<Int, Long?>> =
        if (strategy in setOf(
                DiscoveryStrategy.IMAGE_70000_SETTER,
                DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE,
                DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS,
                DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK,
            )
        ) {
            configurationReads.map { it.offset to (if (it.write) it.word else null) }
        } else {
            steps(strategy).map { it.offset to (if (it.write) it.word else null) }
        }

    fun limit(strategy: DiscoveryStrategy) = when (strategy) {
        DiscoveryStrategy.IMAGE_70000_SETTER -> configurationReads.size + T79ImageConfigSetter.accessLimit
        DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE -> secondDriverStart
        DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS -> secondGettersStart + secondGettersSteps.size
        else -> steps(strategy).size
    }

    fun region(selection: Any): DiscoveryRegion {
        if (selection == T79ImageConfigSetter.PROFILE) {
            val predecessor = region(DiscoveryStrategy.IMAGE_70000_CONFIG_READS.id)
            val offsets = T79ImageConfigSetter.offsets + (ImageIrq106Status.ADDRESS - BASE).toInt()
            val prohibited = (listOf(-4) + offsets.sorted() + SIZE).zipWithNext().mapNotNull { (left, right) ->
                if (left + 4 < right) AddressInterval(BASE + left + 4, BASE + right) else null
            }
            return predecessor.copy(strategy = DiscoveryStrategy.IMAGE_70000_SETTER, prohibited = prohibited,
                evidence = predecessor.evidence + listOf(
                    "on-t79-setter-near/pre-read: 42 getter reads complete, core1 READ4 FFF70020 LR 011B9720",
                    "HYPOTHESIS: retain the preceding zero seed for the enable RMW and the guest's 43 masked writes through 011BAB7C"))
        }
        if (selection == T79ImageSecondUpdate.PROFILE) {
            val predecessor = region(T79ImageConfigSetter.PROFILE)
            val offsets = (T79ImageConfigSetter.offsets + T79ImageSecondUpdate.offsets +
                (ImageIrq106Status.ADDRESS - BASE).toInt()).distinct().sorted()
            val prohibited = (listOf(-4) + offsets + SIZE).zipWithNext().mapNotNull { (left, right) ->
                if (left + 4 < right) AddressInterval(BASE + left + 4, BASE + right) else null
            }
            return predecessor.copy(strategy = DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE, prohibited = prohibited,
                evidence = predecessor.evidence + listOf(
                    "on-6e-second/post-6e and photo-on/t79-6e-second/post-6e: core1 WRITE4 FFF70134=00170FEF LR 011BA364 after cursor 395",
                    "HYPOTHESIS: accept the 65 ordered second-round 021F3630..021F371C update writes qualified by section4 bfi masks and field bounds"))
        }
        if (selection == SECOND_DRIVER_GETTERS_PROFILE) {
            val predecessor = region(T79ImageSecondUpdate.PROFILE)
            val offsets = (T79ImageConfigSetter.offsets + T79ImageSecondUpdate.offsets +
                secondDriverSteps.map { it.offset } + getterPass.map { it.offset } +
                (ImageIrq106Status.ADDRESS - BASE).toInt()).distinct().sorted()
            val prohibited = (listOf(-4) + offsets + SIZE).zipWithNext().mapNotNull { (left, right) ->
                if (left + 4 < right) AddressInterval(BASE + left + 4, BASE + right) else null
            }
            return predecessor.copy(strategy = DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS, prohibited = prohibited,
                evidence = predecessor.evidence + listOf(
                    "section4 011B81E4..011B8354, 011BC314..011BC32C, 011BCDCC..011BCE24; section5 0237C380..0237C424",
                    "HYPOTHESIS: round-2 55-access driver inherits teardown 004=03FF0000, 008=03FF0000 (writes 03FF03DF), 2E4 bit5=1 with retained high half; 2x67 getters read back retained round-2 update words and zero for 200/204"))
        }
        if (selection == T79StatefulIspBank.PROFILE) {
            val predecessor = region(SECOND_DRIVER_GETTERS_PROFILE)
            val offsets = statefulAllowedOffsets.sorted()
            val prohibited = (listOf(-4) + offsets + SIZE).zipWithNext().mapNotNull { (left, right) ->
                if (left + 4 < right) AddressInterval(BASE + left + 4, BASE + right) else null
            }
            return predecessor.copy(
                strategy = DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK,
                prohibited = prohibited,
                evidence = predecessor.evidence + listOf(
                    "on-round2-driver/post-driver cursor 649: core1 024D9634 -> 02362650 -> 0237146C -> 023713FC -> 021F3258 -> 011B95B8 READ4 FFF70020 LR 011B9720; T80 movdc (01749A80[79]=1, core1) also reprograms ISP bank",
                    "HYPOTHESIS: repeatable stateful 32-bit register bank over the exact union of known Image70000 offsets (preserving prohibited holes); 000 reads value|1, 2E8 reads 0x10000 read-only, quiet 00C reads 0 and acks 0/1 from 011B7F0C",
                ),
            )
        }
        val strategy = listOf(DiscoveryStrategy.IMAGE_70000, DiscoveryStrategy.IMAGE_70000_CONFIG,
            DiscoveryStrategy.IMAGE_70000_UPDATE, DiscoveryStrategy.IMAGE_70000_DRIVER,
            DiscoveryStrategy.IMAGE_70000_GETTERS, DiscoveryStrategy.IMAGE_70000_TEARDOWN,
            DiscoveryStrategy.IMAGE_70000_REINIT, DiscoveryStrategy.IMAGE_70000_CONFIG_READS).single { it.id == selection }
        val holes = steps(strategy).map { it.offset }.distinct().sorted().zipWithNext().mapNotNull { (left, right) ->
            if (left + 4 < right) AddressInterval(BASE + left + 4, BASE + right) else null
        }
        return DiscoveryRegion(REGION, "section4-011b811c", BASE, SIZE, strategy,
            setOf(4), 4, true, prohibited = holes,
            evidence = listOf("ON core1 011B8150 BL 0005AFD8, 011B8154 BIC #1, 011B815C BL 0005AF90",
                "011B8170/8188 poll bits 0/16, 011B81A0 clears bit 8, 011B7E3C and 011B7C40 consume offset 0xC",
                "HYPOTHESIS: zero seed words and both poll bits set on their first read, one sequence") +
                (if (strategy != DiscoveryStrategy.IMAGE_70000) listOf(
                    "011B84A4 config writer through 011B89F8, on-70000 tick 832801797 R4=01AA5F6C, 44-byte argument",
                    "HYPOTHESIS: accept the fifteen derived configuration writes once") else emptyList()) +
                when (strategy) {
                    DiscoveryStrategy.IMAGE_70000_UPDATE -> listOf(
                        "on-6e000 tick 832809753, 021F3630 to 021F352C with mask 001C7FEC and argument base 0328ACB0",
                        "HYPOTHESIS: accept 65 configuration writes followed by one 011BA4B4 readback of offsets 134/138/13C")
                    DiscoveryStrategy.IMAGE_70000_DRIVER, DiscoveryStrategy.IMAGE_70000_GETTERS,
                    DiscoveryStrategy.IMAGE_70000_TEARDOWN, DiscoveryStrategy.IMAGE_70000_REINIT,
                    DiscoveryStrategy.IMAGE_70000_CONFIG_READS -> listOf(
                        "on-6e000 021F3630 update, 65 writes through 011BC404. on-502b0 branches at cursor 89",
                        "on-502b0 tick 833296144, 021F2114 mask 001F00FF, configuration 02D93280, 02D932AC=02364DA0",
                        "021F17A4 dispatches 21 aligned 34-bit addresses. 011BD0D0 accepts an unaligned low word for 014/018",
                        "011BCDF8 stores the callback at 01A3C19C. Selector 88 row 015A8F2C registers IRQ106 handler 011B7F14",
                        "HYPOTHESIS: retain 2E4 through three field updates, seed 004/008 with zero on 011B81E4 enable, 55 driver accesses") +
                        if (strategy != DiscoveryStrategy.IMAGE_70000_DRIVER) listOf(
                            "on-70254 core1 tick 833298586 READ4 FFF70134 LR 011BA500 after cursor 144",
                            "02386C7C(1)/(2) via 021F3930 mask 003C7F80: 65 fixed configuration writes in on-70134 read back per pass",
                            "HYPOTHESIS: configuration words retain the accepted writes; unwritten 200/204 each read zero in 011BE4A8, stop after two passes")
                        else emptyList()
                    else -> emptyList()
                } + (if (strategy == DiscoveryStrategy.IMAGE_70000_TEARDOWN || includesSecondSetup(strategy)) listOf(
                    "T79 024BC954/960/9CC, 0237B2AC clears request and 0237B15C enters cleanup phase 4",
                    "021F2800 converts 003F00FF to 003FF3FF. 011B81E4 clears ten enables in each word; high half selects all ten",
                    "011BCEC8 clears 2E4 bit 8, 011BCEE4 tests 2E8 bit 16 before guest unregisters",
                    "HYPOTHESIS: once in T79 cleanup the first and only 2E8 poll sees bit 16 set") else emptyList()) +
                (if (includesSecondSetup(strategy)) listOf(
                    "T79 1540: on-t79-control2 and photo-on/t79-50000-twice stop in 011B8150 after cursor 285",
                    "HYPOTHESIS: bit 0 stays ready until cleared, first polls ready; 2E4 retains the teardown word; 00C stays quiet",
                    "0237B8BC / 021F3D4C call the 011B84A4 writer; only its fifteen ordered packed words follow") else emptyList()) +
                if (strategy == DiscoveryStrategy.IMAGE_70000_CONFIG_READS) listOf(
                    "on-t79-reinit tick 860702343 READ4 FFF70020 LR 011B9B7C; 021F32A8 dispatches four getters",
                    "HYPOTHESIS: each previously unwritten configuration word reads zero in this single 42-read pass")
                else emptyList())
    }

    val driverControlPosition = driver.indexOfFirst { it.offset == CONTROL && it.payloadMask != 0L }

    fun access(strategy: DiscoveryStrategy, position: Int, offset: Int, write: Boolean, value: Long, context: DiscoveryContext,
               retainedControl: Long): Long? {
        val step = steps(strategy).getOrNull(position) ?: return null
        val expected = step.word or (retainedControl and step.retainedMask)
        if (offset != step.offset || write != step.write || (write && value and step.payloadMask.inv() != expected)) return null
        val helperPc = if (write) 0x5af98L else 0x5afe0L
        if (context.core() != 1 || context.pc() != helperPc || context.lr() != step.lr) return null
        if (position in getters.size until teardown.size && !context.inT79ImageTeardown()) return null
        if (position >= secondSetup.size && when (offset) {
                0x19c -> value and 0x300 == 0x300L
                0x1a8 -> value > 8
                0x1f8 -> (value ushr 16 and 7) > 5 || (value ushr 19 and 7) > 5
                0x220 -> value > 2
                else -> false
            }) return null
        return if (write) value else expected
    }

    fun secondDriverOrGetterAccess(
        position: Int,
        offset: Int,
        write: Boolean,
        value: Long,
        context: DiscoveryContext,
        retainedControl: Long,
        secondUpdate: T79ImageSecondUpdate,
    ): Long? {
        if (!secondUpdate.complete || position < secondDriverStart) return null
        val helperPc = if (write) 0x5af98L else 0x5afe0L
        if (context.core() != 1 || context.pc() != helperPc) return null
        if (position < secondGettersStart) {
            val step = secondDriverSteps[position - secondDriverStart]
            val expected = step.word or (retainedControl and step.retainedMask)
            if (offset != step.offset || write != step.write || context.lr() != step.lr) return null
            if (write && value and step.payloadMask.inv() != expected) return null
            return if (write) value else expected
        }
        val step = secondGettersSteps.getOrNull(position - secondGettersStart) ?: return null
        if (write || offset != step.offset || context.lr() != step.lr) return null
        return when (offset) {
            0x200, 0x204 -> 0L
            else -> secondUpdate.retainedWord(offset)
        }
    }
}
