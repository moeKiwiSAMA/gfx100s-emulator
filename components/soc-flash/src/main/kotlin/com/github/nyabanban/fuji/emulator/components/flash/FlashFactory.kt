package com.github.nyabanban.fuji.emulator.components.flash

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

class FlashFactory : ComponentFactory {
    companion object {
        const val ENABLE_PROFILE = "gfx100s-pio-enable-status-functional-experimental-v2"
        const val ENABLE_JEDEC = 0x332220L
        const val MICRON_JEDEC = 0x20BB20L
        const val MACRONIX_JEDEC = 0x3A25C2L
        const val ERASE_PROFILE = "gfx100s-pio-erase-functional-experimental-v3"
        const val PROGRAM_PROFILE = "gfx100s-program-dma-functional-experimental-v4"
        const val BOOT_QSPI_PROFILE = "gfx100s-boot-qspi-jedec-functional-experimental-v5"
        const val UNIFIED_BOOT_PROGRAM_PROFILE = "gfx100s-unified-boot-program-v6"
        private val DESCRIPTOR = ComponentDescriptor("soc-flash", "0.0.1", settings = listOf(
            Setting("profile", ValueKind.TEXT, "dat-readonly-experimental-v1", choices = listOf("dat-readonly-experimental-v1", ENABLE_PROFILE, ERASE_PROFILE, PROGRAM_PROFILE, BOOT_QSPI_PROFILE, UNIFIED_BOOT_PROGRAM_PROFILE), live = false),
            Setting("jedec", ValueKind.INTEGER, -1L, -1.0, 16777215.0, live = false, description = "-1 refuses ID; other values are explicit unmeasured inputs"),
            Setting("imagePort", ValueKind.TEXT, "flash.image", live = false), Setting("interruptPort", ValueKind.TEXT, "", live = false),
            Setting("irq", ValueKind.INTEGER, 37L, 32.0, 511.0, live = false),
        ))
        private val SNAPSHOT_KEYS = setOf(
            "schema", "componentId", "scalars", "registers", "irqPending", "eraseCount",
            "due", "request", "pioEnableStatus", "programEngine", "eventCount", "events",
        )
        private val DUE_KEYS = setOf("tick", "kind", "enableOperation")
        private val ENABLE_OPERATION_KEYS = setOf("opcode", "selector")
        private val REQUEST_KEYS = setOf("address", "size", "acceptedAt", "owner")
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")
        private fun requireLong(value: Any?, field: String): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for $field" }
            return (value as? Number)?.toLong() ?: error("Unreachable integer conversion for $field")
        }
        private fun requireInt(value: Any?, field: String): Int {
            val number = requireLong(value, field)
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Out-of-range int for $field" }
            return number.toInt()
        }
        private fun requireBoolean(value: Any?, field: String): Boolean {
            require(value is Boolean) { "Expected boolean for $field" }
            return value
        }
        private fun requireSha256(value: Any?, field: String): String {
            require(value is String && HEX_SHA256.matches(value)) { "Expected SHA-256 hex for $field" }
            return value
        }
    }
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = Flash(context, config)
    private class Flash(context: ComponentContext, config: Map<String, Any>) : ScalarComponent("soc-flash", context, config), DmaSourcePeer, StatefulComponent {
        override fun interruptRoutes(): List<InterruptRoute> {
            val sink = values.getValue("interruptPort") as String; val line = (values.getValue("irq") as Number).toInt()
            return if (sink.isEmpty()) emptyList() else listOf(InterruptRoute(sink, line..line))
        }
        private val offsets = setOf(0,4,8,0xc,0x10,0x14,0x18,0x1c,0x20,0x38,0x40,0x44,0x60,0x64,0x68,0x6c,0x70,0x74,0x78,0x7c,0x80,0x90,0x94,0x98,0x9c,0xa0,0xa4,0xa8,0xb4,0xb8,0xbc,0xe0,0xf0)
        private val validOffsetTable = BooleanArray(64).apply {
            for (o in offsets) this[o / 4] = true
        }
        private val r = LongArray(64)
        private var closed = false
        private data class Due(val tick: Long, val kind: String, val enableOperation: PioEnableStatus.Operation? = null)
        private data class Request(val address: Long, val size: Long, var acceptedAt: Long? = null, var owner: Int? = null)
        private val bootQspiMode = config["profile"] in listOf(BOOT_QSPI_PROFILE, UNIFIED_BOOT_PROGRAM_PROFILE)
        private val programMode = config["profile"] in listOf(PROGRAM_PROFILE, UNIFIED_BOOT_PROGRAM_PROFILE)
        private val eraseMode = config["profile"] in listOf(ERASE_PROFILE, PROGRAM_PROFILE, UNIFIED_BOOT_PROGRAM_PROFILE)
        private val enableStatus = if (config["profile"] in listOf(ENABLE_PROFILE, ERASE_PROFILE, PROGRAM_PROFILE, BOOT_QSPI_PROFILE, UNIFIED_BOOT_PROGRAM_PROFILE)) PioEnableStatus(supportWrsr = bootQspiMode) else null
        private data class Erase(val transaction:ImageEraseTransaction,val rawAddress:Long,val deadline:Long,val nextCount:Long,var commandAccepted:Boolean=false)
        private var erase:Erase?=null
        private var eraseCount=0L
        private var eraseFailure:String?=null
        private var due: Due? = null
        private var request: Request? = null
        private var irqPending = false
        private val events = ArrayDeque<Map<String, Any>>()
        private var eventCount = 0L
        private val programEngine=if(programMode)FlashProgramEngine(context,r,{aperture()},{image()},{mutablePeer()},{context.ports.find(values.getValue("imagePort").toString()+".program",ProgrammableImagePort::class.java)?:error("explicit programmable flash peer missing")},checkNotNull(enableStatus),{erase!=null||due!=null||request!=null},{check(eventCount<Long.MAX_VALUE){"flash event counter overflow"}},{event->record(event)})else null
        private val apertureRead = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        private fun image() = context.ports.find(values.getValue("imagePort") as String, ReadOnlyImagePort::class.java) ?: error("flash image peer missing")
        private fun aperture() = (context.ports.find("board.layout", AddressLayout::class.java) ?: error("board address layout missing")).window("soc-flash.aperture")
        private fun record(event: Map<String, Any>) { if (events.size == 512) events.removeFirst(); events.addLast(event); eventCount++ }
        private fun deadline(): Long { val now = context.clock.ticks(); require(now >= 0); return Math.addExact(now, 8) }
        private val irqPortName = values.getValue("interruptPort") as String
        private val irqLine = (values.getValue("irq") as Number).toInt()
        private val irqSink by lazy {
            if (irqPortName.isEmpty()) null else context.ports.find(irqPortName, InterruptSink::class.java) ?: error("flash IRQ sink missing")
        }
        private var syncedIrq: Boolean? = null
        private fun sync() {
            if (syncedIrq == irqPending) return
            irqSink?.setLine(irqLine, irqPending)
            syncedIrq = irqPending
        }
        init {
            require(config["profile"] in listOf("dat-readonly-experimental-v1", ENABLE_PROFILE, ERASE_PROFILE, PROGRAM_PROFILE, BOOT_QSPI_PROFILE, UNIFIED_BOOT_PROGRAM_PROFILE)) { "unknown flash profile" }
            if (bootQspiMode) {
                val input = config["jedec"]
                require((input is Byte || input is Short || input is Int || input is Long) && (input as Number).toLong() in 0L..0xffffffL) {
                    "boot QSPI profile requires explicit 24-bit JEDEC ID"
                }
            } else if (enableStatus != null) {
                val input=config["jedec"];require((input is Byte || input is Short || input is Int || input is Long) && (input as Number).toLong()==ENABLE_JEDEC) { "PIO enable requires explicit qualified JEDEC input332220" }
            }
            context.ports.register(id, "$id.dma", DmaSourcePeer::class.java, this)
            programEngine?.let{context.ports.register(id,"soc-flash.tx",DmaDestinationPeer::class.java,it)}
            context.ports.register(id, "$id.registers", MmioDevice::class.java, object : TickAwareMmioDevice {
                override val clockOrder = 7
                override fun nextDeadline():Long? {val command=due?.tick;val array=erase?.deadline;val a=if(command==null)array else if(array==null)command else minOf(command,array);val p=programEngine?.nextDeadline();return if(a==null)p else if(p==null)a else minOf(a,p)}
                override fun nextInterestedTick(): Long {
                    if (syncedIrq != irqPending || eraseFailure != null) return 0L
                    return nextDeadline() ?: Long.MAX_VALUE
                }
                override fun readMayMutateSchedule(): Boolean = false
                override fun read(offset: Long, size: Int) = access(offset, size, false, 0)
                override fun write(offset: Long, size: Int, value: Long) { access(offset, size, true, value) }
                override fun beforeInstruction(tick: Long) {
                    enableStatus?.live();programEngine?.ensureLive();check(eraseFailure==null) { "flash erase failed: "+eraseFailure }; advance(tick); sync()
                    check(!irqPending || irqPortName.isNotEmpty()) { "flash IRQ pending: GIC route missing" }
                }
            })
            context.ports.register(id, "$id.aperture", MmioDevice::class.java, object : MmioDevice {
                override fun read(offset: Long, size: Int): Long {
                    if(eraseMode){enableStatus?.live();check(erase==null&&programEngine?.isBusy()!=true){"flash aperture read during array operation unqualified"}}
                    require(size == 1 || size == 2 || size == 4 || size == 8)
                    val bytes = apertureRead; bytes.clear(); bytes.putLong(0,0); bytes.limit(size); image().read(offset, bytes); bytes.limit(8); return bytes.getLong(0)
                }
                override fun write(offset: Long, size: Int, value: Long) { error("read-only flash aperture store refused") }
            })
        }
        private fun access(offset: Long, size: Int, write: Boolean, value: Long): Long {
            val o = offset.toInt()
            val index = o ushr 2
            require(size == 4 && offset >= 0L && offset <= 0xf0L && (o and 3) == 0 && validOffsetTable[index]) { "unmodeled serial-flash register/width" }
            enableStatus?.live();programEngine?.ensureLive()
            if (!write) return r[index]
            require(value in 0..0xffffffffL)
            require(o != 0x40 && o != 0xa0 && o != 0xbc) { "write to flash receive/status register" }
            require(programMode || (o != 0x70 && o != 0x78 && o != 0x7c)) { "flash mutation path absent" }
            programEngine?.checkRegisterWrite(o,value)
            require((due == null && request == null) || (o != 0x20 && o != 0x68 && o != 0x6c && o != 0x90 && o != 0x94)) { "flash reconfiguration while busy" }
            if (enableStatus != null && (due?.enableOperation != null || erase!=null || programEngine?.isBusy()==true) && o == 0) require((value xor r[0]) and 0x40003c00L == 0L) { "PIO enable/status selector or packing reconfiguration while busy" }
            when {
                o == 0x70 && programEngine!=null -> {programEngine.writeControl(value);return 0}
                o == 0 -> r[0] = (value and 0x80000000L.inv()) or (r[0] and 0x80000000)
                o == 0x90 && value and 1 != 0L -> {
                    val command = value ushr 24; val count = ((value ushr 20) and 7) + 1
                    if(eraseMode && (command == 0x21L || command == 0xdcL)){submitErase(value);return 0}
                    if(eraseMode&&(erase!=null||programEngine?.isBusy()==true))require(command==5L){"only status PIO qualified during erase"}
                    val isBootRead4 = bootQspiMode && (command == 0x13L || command == 0xecL)
                    if (isBootRead4) {
                        val hasRx = (value and 0x800000L) != 0L
                        val hasAddr = (value and 0x80000L) != 0L
                        val addrBytes = ((value ushr 16) and 3L) + 1L
                        require(hasRx && hasAddr && addrBytes == 4L && count in 1L..4L) { "unsupported 4-byte PIO read shape" }
                        image().check(r[37], count)
                    }
                    require(command == 0x9fL || command == 5L || (enableStatus != null && command == 6L) || (bootQspiMode && command == 1L) || isBootRead4) { "unsupported or mutating flash PIO command" }
                    val enableOperation = if (enableStatus != null && (command == 5L || command == 6L || (bootQspiMode && command == 1L))) { image(); enableStatus.prepare(r[0], value, r[42]) } else null
                    require(command != 0x9fL || ((values.getValue("jedec") as Number).toLong() >= 0 && count <= 3)) { "flash identity missing or exceeds three bytes" }
                    val at = deadline(); r[index] = value or 2; r[0] = r[0] and 0x80000000L.inv(); r[16] = 0; irqPending = false; due = Due(at, "pio", enableOperation)
                    record(mapOf("operation" to "pio-submit", "tick" to context.clock.ticks(), "deadline" to at, "command" to command))
                }
                o == 0x60 -> when (value) {
                    0x20L -> r[index] = r[index] and 0x20L.inv()
                    1L -> {
                        require(erase==null&&programEngine?.isBusy()!=true&&due == null && request == null && (r[8] == 0x606L || r[8] == 0x707L)) { "unsupported or busy mapped flash read" }
                        check(r[26], r[27]); val at = deadline(); request = Request(r[26], r[27]); due = Due(at, "mapped-read"); r[index] = 1
                        record(mapOf("operation" to "read-submit", "tick" to context.clock.ticks(), "deadline" to at, "address" to r[26], "size" to r[27]))
                    }
                    else -> error("unsupported mapped flash read control")
                }
                o == 0xb4 -> {
                    require(erase==null&&programEngine?.isBusy()!=true&&request == null && (due == null || due!!.kind == "init" || due!!.kind == "configure")) { "initialization during flash transaction" }
                    val cfgSel = (value ushr 16) and 127
                    val configuration = value and 0x007f007fL.inv() == 0xc0000000L && (cfgSel == 0x1fL || cfgSel == 0x3fL)
                    when {
                        value == 0L -> { due = null; r[47] = r[47] and 0x8001L.inv() }
                        value == 0x80200020L && due == null -> {}
                        value == 0x40000000L && due == null -> { val at = deadline(); r[47] = r[47] and 0x8001L.inv(); due = Due(at, "init") }
                        configuration && due == null && r[47] and 0x8000 != 0L -> { val at = deadline(); r[47] = r[47] and 1L.inv(); due = Due(at, "configure") }
                        else -> error("unsupported flash initialization transition")
                    }
                    r[index] = value
                }
                else -> r[index] = value
            }
            if (r[17] and 0x4000 == 0L || r[16] and 0x4000 == 0L) irqPending = false
            // Source changes become GIC inputs at the next pre-instruction boundary (stage-6 clock law).
            return 0
        }
        private fun mutablePeer():WritableImagePort {
            val port=context.ports.find(values.getValue("imagePort").toString()+".mutable",WritableImagePort::class.java) ?: error("explicit mutable flash image peer missing")
            if (port !is WritableImageSetPort) {
                val st = port.snapshot()
                if (st.regionId == "unified-nor-flash") {
                    val readonly = image()
                    require(
                        st.offset == 0L &&
                            st.length == readonly.length &&
                            st.writableOffset == 0L &&
                            st.writableLength == readonly.length &&
                            !st.closed &&
                            st.failure == null,
                    ) { "unified NOR mutable flash identity/lifecycle mismatch" }
                    return port
                }
            }
            val states=(port as? WritableImageSetPort)?.regionSnapshots()?:listOf(port.snapshot());require(states.isNotEmpty()&&states.size<=8&&states.map{it.regionId}.distinct().size==states.size){"mutable flash region-set identity mismatch"}
            val readonly=image() as? SupplementalImagePort ?: error("mutable flash requires matching supplemental image identity")
            for(state in states){val region=readonly.supplementalRegions.singleOrNull{it.id==state.regionId} ?: error("mutable flash seed region missing")
                require(region.offset==state.offset&&region.length==state.length&&region.sha256==state.seedSha256&&region.relativeFile!=state.relativeFile&&!state.closed&&state.failure==null) { "mutable flash seed identity/lifecycle mismatch" }}

            return port
        }
        private fun submitErase(value:Long) {
            require(erase==null&&programEngine?.isBusy()!=true&&due==null&&request==null) { "flash erase while busy" }
            require(value in listOf(0x210b0001L,0xdc0b0001L)&&r[0]and 0x40003c00L==0L&&(bootQspiMode||r[42]==0L)&&r[56]and 255==0L&&r[60]==0L) { "flash erase command shape/selector/packing unqualified" }
            require(checkNotNull(enableStatus).isEnabled(0)) { "flash erase requires completed write enable" }
            val size=if(value ushr 24==0xdcL)65536L else 4096L;val a=aperture();val raw=r[37]
            require(raw>=a.base&&raw<=a.base+a.size-size&&raw%size==0L) { "flash erase address/alignment unqualified" }
            val commandAt=deadline();val arrayAt=Math.addExact(context.clock.ticks(),4096L);val next=Math.addExact(eraseCount,1)
            val transaction=mutablePeer().prepareErase(raw-a.base,size)
            erase=Erase(transaction,raw,arrayAt,next);due=Due(commandAt,"erase-command")
            r[36]=value or 2;r[0]=r[0]and 0x80000000L.inv();r[16]=0;irqPending=false
            record(mapOf("operation" to "erase-submit","tick" to context.clock.ticks(),"commandDeadline" to commandAt,"arrayDeadline" to arrayAt,"rawAddress" to raw,"logicalOffset" to transaction.offset,"size" to size))
        }
        private fun advance(tick: Long) {
            while(true) {
                val item=due;val array=erase
                val programAt=programEngine?.nextDeadline()
                if(programAt!=null&&programAt<=tick&&(item==null||programAt<=item.tick)&&(array==null||programAt<=array.deadline)){programEngine.advance(programAt,tick);continue}
                // Declared tie policy: storage commit before status sampling at an equal deadline.
                if(array!=null&&array.deadline<=tick&&(item==null||array.deadline<=item.tick)) {
                    check(array.commandAccepted) { "erase array completion before command acceptance" }
                    val receipt=try{array.transaction.commit()}catch(error:Throwable){eraseFailure=error.toString();throw error}
                    erase=null;eraseCount=array.nextCount
                    record(mapOf("operation" to "erase-array-complete","deadline" to array.deadline,"observedAt" to tick,"offset" to receipt.offset,"size" to receipt.size,"changedBytes" to receipt.changedBytes,"beforeSha256" to receipt.beforeSha256,"afterSha256" to receipt.afterSha256,"generation" to receipt.generation))
                    continue
                }
                if(item==null||tick<item.tick)return
                when (item.kind) {
                    "init" -> r[47] = r[47] or 0x8000
                    "configure" -> r[47] = r[47] or 1
                    "erase-command" -> {
                        val pending=checkNotNull(erase);checkNotNull(enableStatus).consumeEnable(0);pending.commandAccepted=true
                        r[36]=r[36]and 3L.inv();r[0]=r[0]or 0x80000000;r[16]=r[16]or 0x4000;irqPending=r[17]and 0x4000!=0L
                    }
                    "pio" -> {
                        val command = r[36] ushr 24; val count = ((r[36] ushr 20) and 7).toInt() + 1
                        val receive = if (item.enableOperation != null) {
                            checkNotNull(enableStatus).complete(item.enableOperation, erase != null || programEngine?.isBusy() == true)
                        } else if (command == 0x9fL) {
                            (values.getValue("jedec") as Number).toLong() and ((1L shl (8 * count)) - 1)
                        } else if (bootQspiMode && command in listOf(0x13L, 0xecL)) {
                            val buf = apertureRead; buf.clear(); buf.putLong(0, 0); buf.limit(count)
                            image().read(r[37], buf); buf.limit(8); buf.getLong(0) and 0xffffffffL
                        } else {
                            0L
                        }
                        if (receive != null) r[40] = receive
                        r[36] = r[36] and 3L.inv(); r[0] = r[0] or 0x80000000; r[16] = r[16] or 0x4000; irqPending = r[17] and 0x4000 != 0L
                    }
                    "mapped-read" -> { checkNotNull(request).acceptedAt = item.tick; r[24] = (r[24] and 1L.inv()) or 0x20 }
                }
                due = null; record(mapOf("operation" to "${item.kind}-complete", "deadline" to item.tick, "observedAt" to tick))
            }
        }
        override fun overlaps(address: Long, size: Long): Boolean { val a = aperture(); return size > 0 && address < a.base + a.size && a.base < address + size }
        override fun check(address: Long, size: Long) { val a = aperture(); require(address >= a.base && size > 0 && address <= a.base + a.size && size <= a.base + a.size - address); image().check(address - a.base, size) }
        override fun acceptedAt() = request?.acceptedAt
        override fun claim(channel: Int, address: Long, size: Long): Long? {
            val item = request ?: return null; val at = item.acceptedAt ?: return null
            require(item.address == address && item.size == size) { "DMA descriptor differs from flash request" }
            require(item.owner == null || item.owner == channel) { "flash request already owned" }; item.owner = channel; return at
        }
        override fun release(channel: Int) { request?.let { if (it.owner == channel) it.owner = null } }
        override fun read(channel: Int, address: Long, destination: ByteBuffer) {
            val item = request ?: error("no flash request")
            require(item.owner == channel && item.acceptedAt != null && address >= item.address && destination.remaining() <= item.size - (address - item.address)) { "DMA has no matching accepted request" }
            image().read(address - aperture().base, destination)
        }
        override fun consume(channel: Int) {
            val item = request ?: error("no flash request"); require(item.owner == channel)
            record(mapOf("operation" to "dma-consume", "channel" to channel, "address" to item.address, "size" to item.size)); request = null
        }
        private fun imageState(): Map<String, Any?>? {
            val port = context.ports.find(values.getValue("imagePort") as String, ReadOnlyImagePort::class.java) ?: return null
            val hasDatUpdate = context.ports.find("firmware.update", UpdateDataPort::class.java) != null
            val isUnified = context.ports.find(values.getValue("imagePort").toString() + ".mutable", WritableImagePort::class.java)
                ?.let { it !is WritableImageSetPort && it.snapshot().regionId == "unified-nor-flash" } == true
            if (!hasDatUpdate && !isUnified) {
                // Legacy DAT-backed flash-image without firmware.update port bound yet
                val len = runCatching { port.length }.getOrNull() ?: return null
                if (len <= 0L) return null
            }
            val portSha = if (context.relaxedMode && isUnified && port is LazyDigestImagePort) {
                port.cachedSha256OrNull()
            } else {
                port.sha256
            }
            val prefix = mapOf("length" to port.length, "sha256" to portSha, "unknownTail" to if (isUnified) "none" else "refuse", "writable" to eraseMode,
                "experimentalTail" to port.experimentalTail?.let { mapOf("profile" to it.profile, "address" to aperture().base + it.offset, "size" to it.size,
                    "fill" to it.fill, "basis" to "HYPOTHESIS: finite unmeasured block remainder") })
            val regions = (port as? SupplementalImagePort)?.supplementalRegions.orEmpty()
            return if (regions.isEmpty()) prefix else prefix + mapOf("supplementalRegions" to regions.map {
                mapOf("id" to it.id, "offset" to it.offset, "length" to it.length, "file" to it.relativeFile, "sha256" to it.sha256,
                    "basis" to "explicit external input, not identified original GFX100S hardware contents", "snapshot" to "immutable-startup-copy")
            })
        }
        override fun close() { closed = true; programEngine?.close();erase?.transaction?.close();erase=null;enableStatus?.let { it.close(); if (due?.enableOperation != null||due?.kind=="erase-command") due = null } }
        override fun state() = super.state() + mapOf("resetProfile" to "explicit-zero-controller-registers-v1", "acceptanceTicks" to 8, "irqPending" to irqPending,
            "image" to imageState(),
            "registers" to offsets.associate { "0x${it.toString(16)}" to r[it / 4] }, "due" to due?.let { mapOf("deadline" to it.tick, "kind" to it.kind) },
            "request" to request?.let { mapOf("address" to it.address, "size" to it.size, "acceptedAt" to it.acceptedAt, "owner" to it.owner) }, "events" to events.toList(), "eventCount" to eventCount, "eventLogTruncated" to (eventCount > events.size), "mutations" to (eraseCount>0||(programEngine?.committedCount()?:0)>0)) + (enableStatus?.let { mapOf("pioEnableStatus" to it.state()) } ?: emptyMap()) + (if(eraseMode)mapOf("erase" to mapOf("commandTicks" to 8,"arrayTicks" to 4096,"committed" to eraseCount,"failure" to eraseFailure,"pending" to erase?.let{mapOf("rawAddress" to it.rawAddress,"offset" to it.transaction.offset,"size" to it.transaction.size,"deadline" to it.deadline,"commandAccepted" to it.commandAccepted)},"scope" to "HYP finite selected writable copy,FF erase and explicit8/4096 instruction ticks; logical selector0 only, no physical law/program/power-loss durability claim"))else emptyMap())+(programEngine?.let{mapOf("program" to it.state())}?:emptyMap())

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "Flash component closed" }
            enableStatus?.live()
            programEngine?.ensureLive()
            check(erase == null) { "Cannot snapshot soc-flash with in-flight erase transaction" }
            check(eraseFailure == null) { "Cannot snapshot soc-flash after erase failure" }
            check(programEngine?.hasStorageLease() != true) { "Cannot snapshot soc-flash with in-flight program transaction" }
            due?.let { pendingDue ->
                check(pendingDue.kind != "erase-command") { "Cannot snapshot soc-flash with pending erase command" }
            }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "registers" to r.toList(),
                "irqPending" to irqPending,
                "eraseCount" to eraseCount,
                "due" to due?.let { pendingDue ->
                    linkedMapOf<String, Any?>(
                        "tick" to pendingDue.tick,
                        "kind" to pendingDue.kind,
                        "enableOperation" to pendingDue.enableOperation?.let { operation ->
                            if (bootQspiMode && operation.opcode == 1) {
                                linkedMapOf("opcode" to operation.opcode, "selector" to operation.selector, "txBytes" to operation.txBytes, "txData" to operation.txData)
                            } else {
                                linkedMapOf("opcode" to operation.opcode, "selector" to operation.selector)
                            }
                        },
                    )
                },
                "request" to request?.let { pendingRequest ->
                    linkedMapOf<String, Any?>(
                        "address" to pendingRequest.address,
                        "size" to pendingRequest.size,
                        "acceptedAt" to pendingRequest.acceptedAt,
                        "owner" to pendingRequest.owner,
                    )
                },
                "pioEnableStatus" to enableStatus?.saveSnapshot(),
                "programEngine" to programEngine?.saveSnapshot(),
                "eventCount" to eventCount,
                "events" to events.map { entry -> LinkedHashMap(entry) },
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "Flash component closed" }
            enableStatus?.live()
            programEngine?.ensureLive()
            check(erase == null && eraseFailure == null && programEngine?.hasStorageLease() != true) {
                "Cannot restore soc-flash while storage transaction is pending or failed"
            }
            require(data.keys == SNAPSHOT_KEYS) { "Unexpected soc-flash snapshot keys" }
            require(requireInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported soc-flash snapshot schema" }
            require(data["componentId"] == id) { "soc-flash snapshot componentId mismatch" }
            val rawScalars = data["scalars"] as? Map<*, *> ?: throw IllegalArgumentException("Missing soc-flash scalars map")
            require(rawScalars.keys == values.keys) { "soc-flash scalar setting keys differ" }
            val previousValues = values
            val validatedValues = try {
                restoreScalarValues(rawScalars, DESCRIPTOR)
                values
            } finally {
                values = previousValues
            }

            val rawRegisters = data["registers"] as? List<*> ?: throw IllegalArgumentException("Missing soc-flash registers list")
            require(rawRegisters.size == r.size) { "soc-flash register count mismatch" }
            val nextRegisters = LongArray(r.size) { registerIndex ->
                val word = requireLong(rawRegisters[registerIndex], "registers[$registerIndex]")
                if (registerIndex * 4 !in offsets) {
                    require(word == 0L) { "Non-zero reserved soc-flash register at index $registerIndex" }
                } else {
                    require(word in 0L..0xffffffffL) { "soc-flash register out of 32-bit range at index $registerIndex" }
                }
                word
            }

            val nextIrqPending = requireBoolean(data["irqPending"], "irqPending")
            if ((nextRegisters[16] and 0x4000L) == 0L || (nextRegisters[17] and 0x4000L) == 0L) {
                require(!nextIrqPending) { "Inconsistent soc-flash irqPending without status and mask bit 14" }
            }

            val nextEraseCount = requireLong(data["eraseCount"], "eraseCount")
            require(nextEraseCount >= 0L) { "Negative soc-flash eraseCount" }
            if (!eraseMode) {
                require(nextEraseCount == 0L) { "Non-zero eraseCount not allowed in non-erase flash profile" }
            }

            val nextDue = data["due"]?.let { rawDue ->
                val dueMap = rawDue as? Map<*, *> ?: throw IllegalArgumentException("Invalid soc-flash due map")
                require(dueMap.keys == DUE_KEYS) { "Unexpected soc-flash due keys" }
                val dueTick = requireLong(dueMap["tick"], "due.tick")
                require(dueTick >= 0L) { "Negative soc-flash due tick" }
                val dueKind = dueMap["kind"] as? String ?: throw IllegalArgumentException("Missing soc-flash due kind")
                require(dueKind in setOf("init", "configure", "pio", "mapped-read")) { "Unsupported soc-flash due kind: $dueKind" }
                val rawOperation = dueMap["enableOperation"]
                val operation = if (rawOperation != null) {
                    require(dueKind == "pio" && enableStatus != null) { "enableOperation only valid for PIO with enableStatus" }
                    val opMap = rawOperation as? Map<*, *> ?: throw IllegalArgumentException("Invalid enableOperation map")
                    val opcode = requireInt(opMap["opcode"], "due.enableOperation.opcode")
                    val selector = requireInt(opMap["selector"], "due.enableOperation.selector")
                    if (bootQspiMode && opcode == 1) {
                        require(opMap.keys == setOf("opcode", "selector", "txBytes", "txData")) { "Unexpected WRSR enableOperation keys" }
                        val txBytes = requireInt(opMap["txBytes"], "due.enableOperation.txBytes")
                        val txData = requireLong(opMap["txData"], "due.enableOperation.txData")
                        require(selector in 0..1 && txBytes in 1..2 && txData in 0L..0xffffL) { "Invalid WRSR enableOperation" }
                        require((nextRegisters[36] ushr 24) == 1L) { "PIO register opcode differs from WRSR enableOperation" }
                        PioEnableStatus.Operation(1, selector, txBytes, txData)
                    } else {
                        require(opMap.keys == ENABLE_OPERATION_KEYS) { "Unexpected enableOperation keys" }
                        require(opcode in setOf(5, 6) && selector in 0..1) { "Invalid enableOperation opcode/selector" }
                        require((nextRegisters[36] ushr 24) == opcode.toLong()) { "PIO register opcode differs from enableOperation" }
                        PioEnableStatus.Operation(opcode, selector)
                    }
                } else {
                    if (dueKind == "pio") {
                        val commandByte = nextRegisters[36] ushr 24
                        if (bootQspiMode) {
                            require(commandByte in setOf(0x9fL, 0x13L, 0xecL)) { "Missing enableOperation for status/WREN/WRSR PIO" }
                        } else if (enableStatus != null) {
                            require(commandByte == 0x9fL) { "Missing enableOperation for status/WREN PIO" }
                        } else {
                            require(commandByte == 0x9fL || commandByte == 5L) { "Unsupported PIO command in due state" }
                        }
                    }
                    null
                }
                Due(dueTick, dueKind, operation)
            }

            val nextRequest = data["request"]?.let { rawRequest ->
                val reqMap = rawRequest as? Map<*, *> ?: throw IllegalArgumentException("Invalid soc-flash request map")
                require(reqMap.keys == REQUEST_KEYS) { "Unexpected soc-flash request keys" }
                val reqAddress = requireLong(reqMap["address"], "request.address")
                val reqSize = requireLong(reqMap["size"], "request.size")
                val reqAcceptedAt = reqMap["acceptedAt"]?.let { accepted -> requireLong(accepted, "request.acceptedAt") }
                val reqOwner = reqMap["owner"]?.let { owner -> requireInt(owner, "request.owner") }
                require(reqAddress >= 0L && reqSize > 0L) { "Invalid soc-flash request bounds" }
                if (reqAcceptedAt != null) {
                    require(reqAcceptedAt >= 0L) { "Negative soc-flash request acceptedAt" }
                }
                if (reqOwner != null) {
                    require(reqAcceptedAt != null && reqOwner in 0..15) { "Invalid soc-flash request owner" }
                }
                Request(reqAddress, reqSize, reqAcceptedAt, reqOwner)
            }
            if (nextDue?.kind == "mapped-read") {
                require(nextRequest != null && nextRequest.acceptedAt == null) {
                    "mapped-read due requires unaccepted request"
                }
            }
            if (nextRequest != null && nextRequest.acceptedAt == null) {
                require(nextDue?.kind == "mapped-read") {
                    "Unaccepted mapped-read request requires mapped-read due entry"
                }
            }

            val validatedEnable = if (enableStatus != null) {
                enableStatus.preflightSnapshot(data["pioEnableStatus"])
            } else {
                require(data["pioEnableStatus"] == null) { "Unexpected pioEnableStatus for readonly flash profile" }
                null
            }

            val validatedProgram = if (programEngine != null) {
                val prepared = programEngine.preflightSnapshot(data["programEngine"])
                if (prepared.hasPending) {
                    require(nextRequest == null) { "Flash cannot hold mapped-read request while program engine is busy" }
                    if (nextDue != null) {
                        require(nextDue.kind == "pio" && nextDue.enableOperation?.opcode == 5) {
                            "Only status PIO may be pending during flash program"
                        }
                    }
                    if (prepared.pendingAcceptedAt == null) {
                        require(nextRegisters[28] == 1L) { "Unaccepted flash program requires control register=1" }
                    } else {
                        require((nextRegisters[28] and 1L) == 0L) { "Accepted flash program cannot retain control bit 0" }
                    }
                }
                prepared
            } else {
                require(data["programEngine"] == null) { "Unexpected programEngine for non-program flash profile" }
                null
            }

            val nextEventCount = requireLong(data["eventCount"], "eventCount")
            require(nextEventCount >= 0L) { "Negative soc-flash eventCount" }
            val rawEvents = data["events"] as? List<*> ?: throw IllegalArgumentException("Missing soc-flash events list")
            val expectedEventsSize = minOf(nextEventCount, 512L).toInt()
            require(rawEvents.size == expectedEventsSize) { "soc-flash events size inconsistent with eventCount" }
            val nextEvents = rawEvents.mapIndexed { eventIndex, rawEntry ->
                normalizeFlashEvent(eventIndex, rawEntry)
            }

            values = validatedValues
            nextRegisters.copyInto(r)
            irqPending = nextIrqPending
            eraseCount = nextEraseCount
            due = nextDue
            request = nextRequest
            if (enableStatus != null && validatedEnable != null) {
                enableStatus.applySnapshot(validatedEnable)
            }
            if (programEngine != null && validatedProgram != null) {
                programEngine.applySnapshot(validatedProgram)
            }
            events.clear()
            events.addAll(nextEvents)
            eventCount = nextEventCount
            val sinkName = values.getValue("interruptPort") as String
            val restoredSink = if (sinkName.isNotEmpty()) context.ports.find(sinkName, InterruptSink::class.java) else null
            if (restoredSink != null) {
                restoredSink.setLine((values.getValue("irq") as Number).toInt(), irqPending)
                syncedIrq = irqPending
            } else {
                syncedIrq = null
            }
        }

        private fun normalizeFlashEvent(eventIndex: Int, rawEntry: Any?): Map<String, Any> {
            val entry = rawEntry as? Map<*, *> ?: throw IllegalArgumentException("Invalid soc-flash event at $eventIndex")
            val operation = entry["operation"] as? String
                ?: throw IllegalArgumentException("Missing soc-flash event operation at $eventIndex")
            return when (operation) {
                "pio-submit" -> {
                    require(entry.keys == setOf("operation", "tick", "deadline", "command")) { "Unexpected pio-submit keys at $eventIndex" }
                    linkedMapOf(
                        "operation" to operation,
                        "tick" to requireLong(entry["tick"], "events[$eventIndex].tick"),
                        "deadline" to requireLong(entry["deadline"], "events[$eventIndex].deadline"),
                        "command" to requireLong(entry["command"], "events[$eventIndex].command"),
                    )
                }
                "read-submit" -> {
                    require(entry.keys == setOf("operation", "tick", "deadline", "address", "size")) { "Unexpected read-submit keys at $eventIndex" }
                    linkedMapOf(
                        "operation" to operation,
                        "tick" to requireLong(entry["tick"], "events[$eventIndex].tick"),
                        "deadline" to requireLong(entry["deadline"], "events[$eventIndex].deadline"),
                        "address" to requireLong(entry["address"], "events[$eventIndex].address"),
                        "size" to requireLong(entry["size"], "events[$eventIndex].size"),
                    )
                }
                "erase-submit" -> {
                    require(entry.keys == setOf("operation", "tick", "commandDeadline", "arrayDeadline", "rawAddress", "logicalOffset", "size")) {
                        "Unexpected erase-submit keys at $eventIndex"
                    }
                    linkedMapOf(
                        "operation" to operation,
                        "tick" to requireLong(entry["tick"], "events[$eventIndex].tick"),
                        "commandDeadline" to requireLong(entry["commandDeadline"], "events[$eventIndex].commandDeadline"),
                        "arrayDeadline" to requireLong(entry["arrayDeadline"], "events[$eventIndex].arrayDeadline"),
                        "rawAddress" to requireLong(entry["rawAddress"], "events[$eventIndex].rawAddress"),
                        "logicalOffset" to requireLong(entry["logicalOffset"], "events[$eventIndex].logicalOffset"),
                        "size" to requireLong(entry["size"], "events[$eventIndex].size"),
                    )
                }
                "erase-array-complete" -> {
                    require(entry.keys == setOf("operation", "deadline", "observedAt", "offset", "size", "changedBytes", "beforeSha256", "afterSha256", "generation")) {
                        "Unexpected erase-array-complete keys at $eventIndex"
                    }
                    linkedMapOf(
                        "operation" to operation,
                        "deadline" to requireLong(entry["deadline"], "events[$eventIndex].deadline"),
                        "observedAt" to requireLong(entry["observedAt"], "events[$eventIndex].observedAt"),
                        "offset" to requireLong(entry["offset"], "events[$eventIndex].offset"),
                        "size" to requireLong(entry["size"], "events[$eventIndex].size"),
                        "changedBytes" to requireLong(entry["changedBytes"], "events[$eventIndex].changedBytes"),
                        "beforeSha256" to requireSha256(entry["beforeSha256"], "events[$eventIndex].beforeSha256"),
                        "afterSha256" to requireSha256(entry["afterSha256"], "events[$eventIndex].afterSha256"),
                        "generation" to requireLong(entry["generation"], "events[$eventIndex].generation"),
                    )
                }
                "init-complete", "configure-complete", "erase-command-complete", "pio-complete", "mapped-read-complete" -> {
                    require(entry.keys == setOf("operation", "deadline", "observedAt")) { "Unexpected $operation keys at $eventIndex" }
                    linkedMapOf(
                        "operation" to operation,
                        "deadline" to requireLong(entry["deadline"], "events[$eventIndex].deadline"),
                        "observedAt" to requireLong(entry["observedAt"], "events[$eventIndex].observedAt"),
                    )
                }
                "dma-consume" -> {
                    require(entry.keys == setOf("operation", "channel", "address", "size")) { "Unexpected dma-consume keys at $eventIndex" }
                    val channelIndex = requireInt(entry["channel"], "events[$eventIndex].channel")
                    require(channelIndex in 0..15) { "Invalid dma-consume channel at $eventIndex" }
                    linkedMapOf(
                        "operation" to operation,
                        "channel" to channelIndex,
                        "address" to requireLong(entry["address"], "events[$eventIndex].address"),
                        "size" to requireLong(entry["size"], "events[$eventIndex].size"),
                    )
                }
                "program-accept-ack" -> {
                    require(entry.keys == setOf("operation", "tick")) { "Unexpected program-accept-ack keys at $eventIndex" }
                    linkedMapOf(
                        "operation" to operation,
                        "tick" to requireLong(entry["tick"], "events[$eventIndex].tick"),
                    )
                }
                "program-submit" -> {
                    require(entry.keys == setOf("operation", "tick", "commandDeadline", "address", "logicalOffset", "size")) {
                        "Unexpected program-submit keys at $eventIndex"
                    }
                    linkedMapOf(
                        "operation" to operation,
                        "tick" to requireLong(entry["tick"], "events[$eventIndex].tick"),
                        "commandDeadline" to requireLong(entry["commandDeadline"], "events[$eventIndex].commandDeadline"),
                        "address" to requireLong(entry["address"], "events[$eventIndex].address"),
                        "logicalOffset" to requireLong(entry["logicalOffset"], "events[$eventIndex].logicalOffset"),
                        "size" to requireLong(entry["size"], "events[$eventIndex].size"),
                    )
                }
                "program-command-accepted" -> {
                    require(entry.keys == setOf("operation", "deadline", "observedAt", "address", "size")) {
                        "Unexpected program-command-accepted keys at $eventIndex"
                    }
                    linkedMapOf(
                        "operation" to operation,
                        "deadline" to requireLong(entry["deadline"], "events[$eventIndex].deadline"),
                        "observedAt" to requireLong(entry["observedAt"], "events[$eventIndex].observedAt"),
                        "address" to requireLong(entry["address"], "events[$eventIndex].address"),
                        "size" to requireInt(entry["size"], "events[$eventIndex].size"),
                    )
                }
                "program-dma-staged" -> {
                    require(entry.keys == setOf("operation", "completedAt", "observedAt", "arrayDeadline", "address", "size")) {
                        "Unexpected program-dma-staged keys at $eventIndex"
                    }
                    linkedMapOf(
                        "operation" to operation,
                        "completedAt" to requireLong(entry["completedAt"], "events[$eventIndex].completedAt"),
                        "observedAt" to requireLong(entry["observedAt"], "events[$eventIndex].observedAt"),
                        "arrayDeadline" to requireLong(entry["arrayDeadline"], "events[$eventIndex].arrayDeadline"),
                        "address" to requireLong(entry["address"], "events[$eventIndex].address"),
                        "size" to requireInt(entry["size"], "events[$eventIndex].size"),
                    )
                }
                "program-array-complete" -> {
                    require(entry.keys == setOf("operation", "deadline", "observedAt", "offset", "size", "changedBytes", "payloadSha256", "beforeSha256", "afterSha256", "generation")) {
                        "Unexpected program-array-complete keys at $eventIndex"
                    }
                    linkedMapOf(
                        "operation" to operation,
                        "deadline" to requireLong(entry["deadline"], "events[$eventIndex].deadline"),
                        "observedAt" to requireLong(entry["observedAt"], "events[$eventIndex].observedAt"),
                        "offset" to requireLong(entry["offset"], "events[$eventIndex].offset"),
                        "size" to requireInt(entry["size"], "events[$eventIndex].size"),
                        "changedBytes" to requireLong(entry["changedBytes"], "events[$eventIndex].changedBytes"),
                        "payloadSha256" to requireSha256(entry["payloadSha256"], "events[$eventIndex].payloadSha256"),
                        "beforeSha256" to requireSha256(entry["beforeSha256"], "events[$eventIndex].beforeSha256"),
                        "afterSha256" to requireSha256(entry["afterSha256"], "events[$eventIndex].afterSha256"),
                        "generation" to requireLong(entry["generation"], "events[$eventIndex].generation"),
                    )
                }
                else -> throw IllegalArgumentException("Unknown soc-flash event operation at $eventIndex: $operation")
            }
        }
    }
}
