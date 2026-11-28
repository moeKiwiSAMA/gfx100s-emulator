package com.github.nyabanban.fuji.emulator.components.powerpeer

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.Properties

/** FW-016/SRC-012: four-byte checksum transport. All external words/retention are explicit hypotheses. */
class PowerPeerFactory : ComponentFactory {
    companion object {
        // DAT 0x0120C3A8 -> 0x011E8DE4(0x98) -> 0x015A932C: edge-triggered GIC IRQ 176 (0xB0)
        // invokes ISR 0x0120BA50 -> 0x0120B9D8(1) to invalidate Sub-MCU Cache Class 1 (selector 0x0E).
        internal const val SUBMCU_POWER_STATUS_GIC_IRQ = 176
        private val NVRAM_SELECTORS = 31..34
        private val NVRAM_ENTRY_REGEX = Regex(""""(3[1-4])"\s*:\s*(0|[1-9][0-9]{0,4})""")
        private val NVRAM_BIN_MAGIC = byteArrayOf('F'.code.toByte(), 'N'.code.toByte(), 'V'.code.toByte(), 'R'.code.toByte())
        private const val NVRAM_BIN_VERSION = 1
        private const val NVRAM_BIN_HEADER_SIZE = 8
        private const val NVRAM_BIN_PAYLOAD_SIZE = 8
        private const val NVRAM_BIN_TOTAL_SIZE = NVRAM_BIN_HEADER_SIZE + NVRAM_BIN_PAYLOAD_SIZE
        private val DESCRIPTOR=ComponentDescriptor("power-peer","0.0.1",settings=listOf(
            Setting("profile",ValueKind.TEXT,"disabled",choices=listOf("disabled","checksum-registers-experimental-v1","masked-retained-registers-experimental-v2","finite-candidate-reads-experimental-v3"),live=false),
            Setting("inputFile",ValueKind.TEXT,"",live=false),Setting("gpioPort",ValueKind.TEXT,"soc-gpio.outputs",live=false),
            Setting("registerPort",ValueKind.TEXT,"",live=false),
            Setting("powerButtonProfile",ValueKind.TEXT,"disabled",choices=listOf("disabled","gfx100s-2.13-power-switch-v1"),live=false),
            Setting("repeatLastCandidateRead",ValueKind.BOOLEAN,false,live=false),
            Setting("batteryPort",ValueKind.TEXT,"",live=false),
            Setting("nvramFile",ValueKind.TEXT,"",live=false)))
    }
    override val descriptor=DESCRIPTOR
    override fun create(context: ComponentContext,config: Map<String,Any>): HardwareComponent=Peer(context,config)
    private class Peer(context: ComponentContext,config: Map<String,Any>) : ScalarComponent("power-peer",context,config),StatefulComponent,SpiPeer {
        private val profile=config.getValue("profile") as String
        private val finite=profile=="finite-candidate-reads-experimental-v3"
        private val masked=profile=="masked-retained-registers-experimental-v2" || finite
        private val candidateReads: FiniteCandidateReads?
        private val enabled=profile!="disabled"
        private val writeMasks=IntArray(128)
        private val words=IntArray(128) { -1 };private val stored=IntArray(128) { -1 }
        private val retained=BooleanArray(128);private val disables=BooleanArray(128)
        private var idleHigh=false;private var pins: GpioOutputPort?=null
        private var phase=0;private var edge=0L;private var response=0
        private var frame=IntArray(4)
        private var eventCount=0L;private var wordCount=0L;private var selectedCount=0L;private var idleCount=0L;private var discarded=0L
        private val events=ArrayDeque<Map<String,Any>>(256);private val log=ArrayDeque<Map<String,Any>>(256)
        private var inputs: Map<String,Any> = emptyMap()
        private val registerPort=config.getOrDefault("registerPort","") as String
        private var registers: PowerRegisterConnection?=null
        private var preparedRead: PowerRegisterReadLease?=null
        private var preparedDelegatedTick=-1L
        private var preparedConnection=false
        private var preparedReadAborted=false
        private val delegated=BooleanArray(128)
        private var closed=false
        private val powerButtonProfile=config.getValue("powerButtonProfile") as String
        private val repeatLastCandidateRead=config.getOrDefault("repeatLastCandidateRead",false) as Boolean
        private val batteryPort=config.getOrDefault("batteryPort","") as String
        private val nvramFile=config.getOrDefault("nvramFile","") as String
        private val nvramPath: Path? = if (enabled && nvramFile.isNotBlank() && context.relaxedMode) resolveSafeNvramPath(context.directory, nvramFile) else null
        private var nvramDirty=false
        private var battery: BatteryPort?=null
        private var batteryListenerLease: AutoCloseable?=null
        private var gpioInputs: GpioInputPort?=null
        private var gicLines: InterruptSink?=null
        private var lastDrivenChargerGpio: Boolean?=null
        private var lastObservedSel0E: Int=-1
        private fun syncChargerHardwareLines(initialBoot: Boolean = false) {
            val b=battery ?: return
            val sink=gpioInputs ?: context.ports.find("soc-gpio.inputs", GpioInputPort::class.java)?.also { gpioInputs=it }
            val extPower=b.externalPowerConnected()
            if (sink != null && (initialBoot || lastDrivenChargerGpio != extPower)) {
                lastDrivenChargerGpio=extPower
                // DAT 0x0120CC20: GPIO(8, 0) (selector 0x00080000 -> 0xFFF41214 bit 0) = external USB power-good input
                sink.setInputLevel(8, 0, extPower)
            }
            val currentSel0E=batteryWord(14) ?: -1
            if (initialBoot) {
                lastObservedSel0E=currentSel0E
            } else if (currentSel0E >= 0 && lastObservedSel0E != currentSel0E) {
                lastObservedSel0E=currentSel0E
                val irqSink=gicLines ?: context.ports.find("soc-gic.lines", InterruptSink::class.java)?.also { gicLines=it }
                if (irqSink != null) {
                    // Pulse rising-edge GIC IRQ 176 so firmware ISR 0x0120BA50 invalidates Cache Class 1 (sel 0x0E)
                    irqSink.setLine(SUBMCU_POWER_STATUS_GIC_IRQ, false)
                    irqSink.setLine(SUBMCU_POWER_STATUS_GIC_IRQ, true)
                    irqSink.setLine(SUBMCU_POWER_STATUS_GIC_IRQ, false)
                }
            }
        }
        private fun resolveSafeNvramPath(rootDir: Path, relative: String): Path {
            val rel=Path.of(relative)
            require(!rel.isAbsolute && rel == rel.normalize() && rel.none { it.toString() in setOf(".", "..") }) {
                "Invalid power-peer nvramFile relative path: $relative"
            }
            val base=rootDir.toAbsolutePath().normalize()
            val resolved=base.resolve(rel).normalize()
            require(resolved.startsWith(base) && resolved != base) { "power-peer nvramFile escapes component directory: $relative" }
            var cur: Path? = resolved
            while (cur != null && cur != base) {
                require(!Files.isSymbolicLink(cur)) { "power-peer nvramFile cannot traverse symlink: $cur" }
                cur=cur.parent
            }
            return resolved
        }
        private fun applyLoadedNvramWord(sel: Int, v: Int) {
            require(sel in NVRAM_SELECTORS && v in 0..65535) { "Invalid power-peer NVRAM selector/value" }
            if (retained[sel] || writeMasks[sel] != 0) {
                if (writeMasks[sel] != 0) {
                    require(((v xor words[sel]) and writeMasks[sel].inv() and 65535) == 0) {
                        "power-peer NVRAM selector $sel modifies unmasked bits"
                    }
                }
                stored[sel] = v
            }
        }
        private fun isBinaryNvram(path: Path): Boolean =
            path.fileName.toString().endsWith(".bin", ignoreCase = true)
        private fun loadNvram(path: Path) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                "power-peer nvramFile must be a regular non-symlink file"
            }
            if (isBinaryNvram(path)) {
                val bytes = Files.readAllBytes(path)
                if (bytes.isEmpty()) return
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val offset = when (bytes.size) {
                    NVRAM_BIN_TOTAL_SIZE -> {
                        for (i in NVRAM_BIN_MAGIC.indices) {
                            require(bytes[i] == NVRAM_BIN_MAGIC[i]) { "Invalid power-peer NVRAM binary magic" }
                        }
                        val version = buf.getShort(4).toInt() and 0xffff
                        val count = buf.getShort(6).toInt() and 0xffff
                        require(version == NVRAM_BIN_VERSION && count == NVRAM_SELECTORS.count()) {
                            "Invalid power-peer NVRAM binary header: version=$version count=$count"
                        }
                        NVRAM_BIN_HEADER_SIZE
                    }
                    NVRAM_BIN_PAYLOAD_SIZE -> 0
                    else -> throw IllegalArgumentException("Invalid power-peer NVRAM binary size: ${bytes.size}")
                }
                for ((idx, sel) in NVRAM_SELECTORS.withIndex()) {
                    val v = buf.getShort(offset + idx * 2).toInt() and 0xffff
                    applyLoadedNvramWord(sel, v)
                }
                return
            }
            val raw=Files.readString(path, StandardCharsets.UTF_8).trim()
            if (raw.isEmpty()) return
            require(raw.startsWith("{") && raw.endsWith("}")) { "Invalid power-peer NVRAM JSON envelope" }
            val body=raw.substring(1, raw.length - 1).trim()
            if (body.isEmpty()) return
            val seen=HashSet<Int>()
            for (part in body.split(',')) {
                val match=NVRAM_ENTRY_REGEX.matchEntire(part.trim())
                    ?: throw IllegalArgumentException("Invalid power-peer NVRAM entry: ${part.trim()}")
                val sel=match.groupValues[1].toInt()
                val v=match.groupValues[2].toInt()
                require(sel in NVRAM_SELECTORS && seen.add(sel) && v in 0..65535) { "Invalid power-peer NVRAM selector/value" }
                applyLoadedNvramWord(sel, v)
            }
        }
        private fun persistNvram(path: Path) {
            val parent=path.parent
            if (parent != null) Files.createDirectories(parent)
            val tmp=Files.createTempFile(parent, ".submcu-nvram-", ".tmp")
            try {
                if (isBinaryNvram(path)) {
                    val buf = ByteBuffer.allocate(NVRAM_BIN_TOTAL_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                    buf.put(NVRAM_BIN_MAGIC)
                    buf.putShort(NVRAM_BIN_VERSION.toShort())
                    buf.putShort(NVRAM_SELECTORS.count().toShort())
                    for (sel in NVRAM_SELECTORS) {
                        val word = when {
                            stored[sel] >= 0 -> stored[sel]
                            words[sel] >= 0 -> words[sel]
                            else -> 0
                        }
                        buf.putShort((word and 0xffff).toShort())
                    }
                    Files.write(tmp, buf.array())
                } else {
                    val entries=NVRAM_SELECTORS
                        .filter { stored[it] >= 0 }
                        .joinToString(", ") { sel -> "\"$sel\": ${stored[sel]}" }
                    val payload="{$entries}\n"
                    Files.writeString(tmp, payload, StandardCharsets.UTF_8)
                }
                try {
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                }
                nvramDirty=false
            } catch (error: Throwable) {
                runCatching { Files.deleteIfExists(tmp) }
                throw error
            }
        }
        private fun isBatterySelector(reg: Int): Boolean =
            batteryPort.isNotEmpty() && (reg==3 || reg==14 || reg in 47..50)
        private fun batteryWord(reg: Int): Int? {
            if (!isBatterySelector(reg)) return null
            val b=checkNotNull(battery) { "power battery endpoint has not started" }
            val present=b.present()
            val extPower=b.externalPowerConnected()
            val activeCharging=present && b.charging()
            return when (reg) {
                // DAT 0x0120AF54 / 0x0120BCFC / 0x0120BC78 / 0x0120CC30:
                // sel 0x0E bit 0 (0x0001) = battery present & smart-battery comm OK
                // sel 0x0E bit 4 (0x0010) = external USB-C power active (drives 0x8082 POWER_SUPPLY_ON / OSD 0x050A)
                // sel 0x0E bit 5 (0x0020) = battery actively charging
                14 -> (if (present) 0x0001 else 0) or (if (extPower) 0x0010 else 0) or (if (activeCharging) 0x0020 else 0)
                // DAT 0x0120BB44 / 0x0120C4D8: sel 0x03 low 10-bit PMIC VBAT ADC (bank14[0x03E7]=507 cutoff, [0x03EB]=752 AC)
                3 -> when {
                    present -> ((b.voltageMillivolts().toLong()*1024L)/12100L).toInt().coerceIn(0,1023)
                    extPower -> ((8400L*1024L)/12100L).toInt().coerceIn(0,1023)
                    else -> 0
                }
                // DAT 0x0120C9EC: sel 0x2F Full-Charge Capacity (NP-W235 2200 mAh)
                47 -> if (present) 2200 else 0
                // DAT 0x0120CA1C / 0x0120CE14: sel 0x30 Remaining Capacity inverted from displayed_pct=clamp((99*raw_pct-705)/87,1,100)
                48 -> if (!present) 0 else {
                    val p=b.chargePercent().coerceIn(0.0,100.0)
                    if (p<=0.0) 0 else {
                        val rawPct=((87.0*p+705.0)/99.0).coerceIn(0.0,100.0)
                        Math.round(2200.0*rawPct/100.0).toInt().coerceIn(0,2200)
                    }
                }
                // DAT 0x0120CB5C: sel 0x31 high byte = battery health % (100% -> LEVEL_0)
                49 -> if (present) (100 shl 8) else 0
                // DAT 0x0120CA80 / 0x0120AF54: sel 0x32 smart-battery temp in 0.1 K (2982 = 25.0 C, must be 1..0xFFFE)
                50 -> if (present) 2982 else 0
                else -> null
            }
        }
        private data class SwitchBinding(val selector: Int,val mask: Int,val activeLow: Boolean)
        private val switchBinding=when(powerButtonProfile) {
            "disabled"->null
            "gfx100s-2.13-power-switch-v1"->{
                val data=Properties()
                checkNotNull(javaClass.getResourceAsStream("/META-INF/fuji/power-button.properties")) { "power switch binding resource missing" }.use(data::load)
                require(data.stringPropertyNames()==setOf("profile","power") && data.getProperty("profile")==powerButtonProfile)
                val fields=data.getProperty("power").split(',')
                require(fields.size==3 && fields[2] in setOf("true","false"))
                val selector=fields[0].toInt();val bit=fields[1].toInt()
                require(selector in 0..127 && bit in 0..15)
                SwitchBinding(selector,1 shl bit,fields[2].toBoolean())
            }
            else->error("unsupported power button profile")
        }
        private var buttons: ButtonPort?=null
        init {
            require(profile in listOf("disabled","checksum-registers-experimental-v1","masked-retained-registers-experimental-v2","finite-candidate-reads-experimental-v3"))
            val file=config.getValue("inputFile") as String
            require(enabled || registerPort.isBlank()) { "disabled power peer cannot acquire register endpoint" }
            require(enabled || batteryPort.isBlank()) { "disabled power peer cannot acquire battery endpoint" }
            require(enabled || nvramFile.isBlank()) { "disabled power peer cannot use nvram file" }
            require(enabled || switchBinding==null) { "power switch requires an enabled peer" }
            require(enabled==file.isNotBlank()) { "power peer profile/inputFile selection mismatch" }
            if(enabled) {
                val data=context.readData(file)
                require(data.keys==setOf("profile","chip_select","inputs","retained_registers","disable_writes")+(if(masked)setOf("write_masks")else emptySet())+(if(finite)setOf("candidate_reads")else emptySet()) && data["profile"]==profile) { "power peer requires complete explicit inputs" }
                fun objectMap(value: Any?): Map<*,*> { require(value is Map<*,*>);return value }
                fun integer(value: Any?,max: Int): Int { require(value is Byte || value is Short || value is Int || value is Long);val n=(value as Number).toLong();require(n in 0..max.toLong());return n.toInt() }
                val cs=objectMap(data["chip_select"])
                require(cs.keys==setOf("port","pin","active_high","idle_high") && integer(cs["port"],15)==12 && integer(cs["pin"],15)==5 && cs["active_high"]==true && cs["idle_high"] is Boolean) { "power peer requires DAT GPIO C5 active-high; idle input must be explicit" }
                idleHigh=cs["idle_high"] as Boolean
                objectMap(data["inputs"]).forEach { (key,value) ->
                    require(key is String && key.matches(Regex("0|[1-9][0-9]{0,2}")));val reg=key.toInt();require(reg<=127);words[reg]=integer(value,65535)
                }
                fun registers(name: String,target: BooleanArray) {
                    val values=data[name];require(values is List<*> && values.size<=128)
                    values.forEach { val reg=integer(it,127);require(!target[reg]) { "duplicate power register declaration" };target[reg]=true }
                }
                registers("retained_registers",retained);registers("disable_writes",disables)
                if(masked){val masks=objectMap(data["write_masks"]);require(masks.isNotEmpty()&&masks.size<=128);masks.forEach{(key,value)->require(key is String&&key.matches(Regex("0|[1-9][0-9]{0,2}")));val reg=key.toInt();require(reg<=127);val mask=integer(value,65535);require(mask!=0&&words[reg]>=0&&!retained[reg]&&!disables[reg]){"masked register needs explicit input and exclusive ownership"};writeMasks[reg]=mask}}
                repeat(128) { reg ->
                    require(!retained[reg] || words[reg]>=0) { "retained reset must be explicit" }
                    require(!disables[reg] || !retained[reg] && words[reg]<0) { "power disable/input overlap" }
                    if(retained[reg]||writeMasks[reg]!=0) stored[reg]=words[reg]
                }
                candidateReads=if(finite) FiniteCandidateReads(data["candidate_reads"],repeatLast=repeatLastCandidateRead) else null
                candidateReads?.candidateReadContracts()?.forEach { candidate ->
                    val reg=candidate.selector
                    require(words[reg]<0 && !retained[reg] && !disables[reg] && writeMasks[reg]==0) { "candidate read ownership overlap" }
                }
                switchBinding?.let { binding ->
                    val reg=binding.selector
                    require(words[reg]>=0 && !retained[reg] && !disables[reg] && writeMasks[reg]==0 && candidateReads?.contains(reg)!=true) { "power switch requires an exclusive fixed input word" }
                }
                // Reconstruct a frozen primitive-only snapshot; never retain parser-owned mutable containers.
                inputs=mapOf("profile" to profile,"chip_select" to mapOf("port" to 12,"pin" to 5,"active_high" to true,"idle_high" to idleHigh),
                    "inputs" to (0..127).filter { words[it]>=0 }.associate { it.toString() to words[it] },
                    "retained_registers" to (0..127).filter { retained[it] },"disable_writes" to (0..127).filter { disables[it] })+(if(masked)mapOf("write_masks" to(0..127).filter{writeMasks[it]!=0}.associate{it.toString() to writeMasks[it]})else emptyMap())+(candidateReads?.let { mapOf("candidate_reads" to it.inputSnapshot()) } ?: emptyMap())
                context.ports.register(id,"$id.spi",SpiPeer::class.java,this)
                candidateReads?.let { context.ports.register(id,"$id.candidate-inputs",DescribedProtocolCandidateInputs::class.java,it) }
            } else candidateReads=null
        }
        override fun interruptRoutes(): List<InterruptRoute> =
            if (enabled && batteryPort.isNotEmpty() && context.ports.find("soc-gic.lines", InterruptSink::class.java) != null) {
                listOf(InterruptRoute("soc-gic.lines", SUBMCU_POWER_STATUS_GIC_IRQ..SUBMCU_POWER_STATUS_GIC_IRQ))
            } else {
                emptyList()
            }
        override fun start() {
            check(!closed) { "power peer closed" }
            if(!enabled)return
            pins=checkNotNull(context.ports.find(values.getValue("gpioPort") as String,GpioOutputPort::class.java)) { "power peer GPIO output port missing" }
            if(switchBinding!=null) buttons=checkNotNull(context.ports.find("buttons",ButtonPort::class.java)) { "power switch profile requires buttons port" }
            if(batteryPort.isNotEmpty()) {
                val resolved=checkNotNull(context.ports.find(batteryPort,BatteryPort::class.java)) { "power battery endpoint missing" }
                battery=resolved
                gpioInputs=context.ports.find("soc-gpio.inputs", GpioInputPort::class.java)
                gicLines=context.ports.find("soc-gic.lines", InterruptSink::class.java)
                syncChargerHardwareLines(initialBoot=true)
                batteryListenerLease=resolved.addPowerStateListener { syncChargerHardwareLines(initialBoot=false) }
            }
            nvramPath?.let { loadNvram(it) }
            if(registerPort.isNotEmpty()) {
                val port=checkNotNull(context.ports.find(registerPort,PowerRegisterPort::class.java)) { "power register endpoint missing" }
                val connection=port.connect(id)
                try {
                    val claims=connection.registers();require(claims.isNotEmpty() && claims.size<=128)
                    val selected=BooleanArray(128)
                    claims.forEach{reg->require(reg in 0..127 && !selected[reg]) { "invalid/duplicate power register endpoint claim" };selected[reg]=true;require(words[reg]<0 && !retained[reg] && !disables[reg] && candidateReads?.contains(reg)!=true) { "external power register ownership overlap" }}
                    selected.copyInto(delegated);registers=connection;preparedConnection=connection is PreparedPowerRegisterConnection
                } catch(error:Throwable) {connection.close();throw error}
            }
        }
        override fun validateSettings(settings: Map<String,Any>) {
            require("powerButtonProfile" !in settings || settings["powerButtonProfile"]==powerButtonProfile) { "power switch profile requires restart" }
            require("batteryPort" !in settings || settings["batteryPort"]==batteryPort) { "power battery port requires restart" }
            require("nvramFile" !in settings || settings["nvramFile"]==nvramFile) { "power nvram file requires restart" }
        }
        override fun applySettings(settings: Map<String,Any>) {validateSettings(settings);super.applySettings(settings)}
        override fun close() {
            if (!closed && nvramDirty) { nvramPath?.let { runCatching { persistNvram(it) } } }
            batteryListenerLease?.close();batteryListenerLease=null;gpioInputs=null;gicLines=null;lastDrivenChargerGpio=null;lastObservedSel0E=-1
            preparedRead?.cancel();preparedRead=null;preparedDelegatedTick=-1L;candidateReads?.close();closed=true;registers?.close();registers=null;pins=null;buttons=null;battery=null;delegated.fill(false)
        }
        private inner class ReattachingDelegatedLease(
            val selector: Int,
            override val value: Int,
            val headerTick: Long,
        ) : PowerRegisterReadLease {
            private var attached: PowerRegisterReadLease?=null
            private var settled=false
            private fun resolve(): PowerRegisterReadLease {
                attached?.let { return it }
                val connection=checkNotNull(registers as? PreparedPowerRegisterConnection) { "Prepared power register endpoint unavailable" }
                val lease=connection.prepareRead(selector,headerTick)
                check(lease.value==value) { "Restored delegated power read value mismatch" }
                attached=lease
                return lease
            }
            override fun checkCommit(tick: Long) {
                check(!closed && !settled) { "Prepared power read lease is not live" }
                resolve().checkCommit(tick)
            }
            override fun commit(tick: Long) {
                check(!closed && !settled) { "Prepared power read lease is not live" }
                val lease=resolve()
                lease.commit(tick)
                settled=true
            }
            override fun cancel() {
                if(!settled) {
                    settled=true
                    val existing=attached ?: (registers as? PreparedPowerRegisterConnection)?.let { connection ->
                        runCatching { connection.prepareRead(selector,headerTick) }.getOrNull()
                    }
                    existing?.cancel()
                }
            }
            fun detach() { settled=true }
        }
        private data class DelegatedPendingSpec(val selector: Int,val value: Int,val headerTick: Long)
        private fun externalWord(reg: Int): Int {
            val raw=words[reg];val binding=switchBinding
            if(binding==null || reg!=binding.selector)return raw
            val active=checkNotNull(buttons) { "power switch profile has not started" }.pressed("power")
            return if(active!=binding.activeLow)raw or binding.mask else raw and binding.mask.inv()
        }
        private fun append(queue: ArrayDeque<Map<String,Any>>,entry: Map<String,Any>) { if(queue.size==256) queue.removeFirst();queue.addLast(entry) }
        override fun exchange(bits: Int,tx: Int,tick: Long): Int {
            check(enabled && !closed);require(bits in 1..16 && tx in 0 until (1 shl bits) && tick>=0) { "invalid power serial word" }
            syncChargerHardwareLines()
            val gpio=checkNotNull(pins);val level=gpio.outputLevel(12,5);val changed=gpio.outputSequence(12,5)
            if(level) require(bits==8) { "checksum power protocol requires eight-bit words" }
            if(changed!=edge) { preparedRead?.cancel();preparedRead=null;preparedDelegatedTick=-1L;preparedReadAborted=false;discarded+=phase;phase=0;edge=changed }
            if(!level) {
                val reply=if(idleHigh) (1 shl bits)-1 else 0
                wordCount++;idleCount++;append(log,mapOf("tick" to tick,"tx" to tx,"rx" to reply,"selected" to false,"edge" to edge,"phase" to 0));return reply
            }
            val next=frame.copyOf();next[phase]=tx
            val reg=next[0] and 127;val write=next[0] and 128!=0;var result=response
            var headerRead: PowerRegisterReadLease?=null
            var headerDelegatedTick=-1L
            var committedWordLog: Map<String,Any>?=null
            val reply=when(phase) {
                0 -> {
                    require(!write || (!isBatterySelector(reg) && candidateReads?.contains(reg)!=true)) { "candidate read input cannot accept write register$reg" }
                    val liveBattery=if(!write) batteryWord(reg) else null
                    result=if(write) 0 else if(liveBattery!=null) liveBattery else if(candidateReads?.contains(reg)==true) {
                        val lease=candidateReads.prepareRead(reg,tick)
                        headerRead=lease;lease.value
                    } else if(delegated[reg]) {
                        val connection=checkNotNull(registers)
                        if(connection is PreparedPowerRegisterConnection) {
                            val lease=connection.prepareRead(reg,tick)
                            try { require(lease.value in 0..65535) { "power endpoint returned invalid raw16 input" };headerRead=lease;headerDelegatedTick=tick;lease.value }
                            catch(error:Throwable) {lease.cancel();throw error}
                        } else connection.read(reg,tick)
                    } else {
                        val baseWord=if(stored[reg]>=0) stored[reg] else externalWord(reg)
                        // DAT 0x0120BF44 (called by TSK_USBCHARGE 0x0235523C): sel 0x12 bits[5:4]=0b01 (0x0010) = USB power mode 1
                        if(reg==18 && batteryPort.isNotEmpty()) {
                            val safeBase=if(baseWord>=0) baseWord else 0
                            if(battery?.externalPowerConnected()==true) (safeBase and 0x0030.inv()) or 0x0010 else safeBase
                        } else baseWord
                    }
                    require(result in 0..65535) { "power peer missing explicit input register$reg" };0
                }
                1 -> result ushr 8
                2 -> result and 255
                else -> {
                    if(next.sum() and 255!=255) {
                        if(preparedRead!=null) {preparedRead?.cancel();preparedRead=null;preparedDelegatedTick=-1L;preparedReadAborted=true}
                        require(false) { "invalid checksum power request frame" }
                    }
                    check(!preparedReadAborted) { "Prepared power read cancelled; a new CS boundary is required" }
                    if(!write && (delegated[reg] && preparedConnection || (!isBatterySelector(reg) && candidateReads?.contains(reg)==true))) checkNotNull(preparedRead) { "Prepared power read has no live lease" }
                    val value=(next[1] shl 8) or next[2]
                    if(write) {if(delegated[reg]) checkNotNull(registers).checkWrite(reg,value,tick) else if(writeMasks[reg]!=0)require(((value xor stored[reg])and writeMasks[reg].inv()and 65535)==0){"power write changes undeclared bits register$reg"} else require(retained[reg] || disables[reg] && value==0) { "unmodeled power write register$reg value$value" }}
                    val checksum=if(write) next[3] else (reg+(result ushr 8)+(result and 255)).inv() and 255
                    val event=mapOf("tick" to tick,"register" to reg,"write" to write,"value" to value,"response" to result,"frame" to next.toList(),"replyChecksum" to checksum)
                    committedWordLog=mapOf("tick" to tick,"tx" to tx,"rx" to checksum,"selected" to true,"edge" to edge,"phase" to phase)
                    if(!write) preparedRead?.checkCommit(tick)
                    if(write) {
                        if(delegated[reg]) {
                            checkNotNull(registers).write(reg,value,tick)
                        } else if(retained[reg]||writeMasks[reg]!=0) {
                            val previous=stored[reg]
                            stored[reg]=value
                            if(nvramPath!=null && reg in NVRAM_SELECTORS && value!=previous) {
                                nvramDirty=true
                                persistNvram(nvramPath)
                            }
                        }
                    }
                    if(!write) preparedRead?.commit(tick)
                    append(events,event);eventCount++;checksum
                }
            }
            append(log,committedWordLog ?: mapOf("tick" to tick,"tx" to tx,"rx" to reply,"selected" to true,"edge" to edge,"phase" to phase))
            if(phase==0) { preparedRead=headerRead;preparedDelegatedTick=headerDelegatedTick } else if(phase==3) { preparedRead=null;preparedDelegatedTick=-1L }
            frame=next;response=result;phase=(phase+1)%4;wordCount++;selectedCount++;return reply
        }
        override fun saveSnapshot(): Map<String,Any?> {
            check(!closed) { "power peer closed" }
            val delegatedPending=if(preparedRead!=null && candidateReads?.hasPending()!=true && preparedDelegatedTick>=0L) {
                linkedMapOf("selector" to (frame[0] and 127),"value" to response,"headerTick" to preparedDelegatedTick)
            } else null
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "enabled" to enabled,
                "phase" to phase,
                "edge" to edge,
                "response" to response,
                "frame" to frame.toList(),
                "stored" to (0..127).filter { stored[it]>=0 }.associate { selector -> selector.toString() to stored[selector] },
                "preparedReadAborted" to preparedReadAborted,
                "preparedDelegatedRead" to delegatedPending,
                "candidateReads" to candidateReads?.saveSnapshot(),
                "eventCount" to eventCount,
                "wordCount" to wordCount,
                "selectedWordCount" to selectedCount,
                "idleWordCount" to idleCount,
                "discardedWords" to discarded,
                "events" to events.map { entry -> LinkedHashMap(entry) },
                "wordsLog" to log.map { entry -> LinkedHashMap(entry) },
            )
        }
        override fun restoreSnapshot(data: Map<String,Any?>) {
            check(!closed) { "power peer closed" }
            val expectedKeys=setOf(
                "schema","componentId","scalars","enabled","phase","edge","response","frame","stored",
                "preparedReadAborted","preparedDelegatedRead","candidateReads","eventCount","wordCount",
                "selectedWordCount","idleWordCount","discardedWords","events","wordsLog",
            )
            require(data.keys==expectedKeys) { "Unexpected or missing keys in power-peer snapshot" }
            require(snapshotInt(data["schema"],"schema",1,1)==snapshotSchemaVersion) { "Unsupported power-peer snapshot schema" }
            require(data["componentId"]==id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw=data["scalars"] as? Map<*,*> ?: throw IllegalArgumentException("Missing scalar settings map for $id")
            val expectedScalarKeys=DESCRIPTOR.settings.map { setting -> setting.key }.toSet()
            val optionalScalarKeys=setOf("repeatLastCandidateRead","batteryPort","nvramFile")
            require(scalarsRaw.keys.all { it in expectedScalarKeys } && (expectedScalarKeys-optionalScalarKeys).all { it in scalarsRaw.keys }) { "Scalar setting keys differ for $id" }
            val normalizedScalars=LinkedHashMap<String,Any?>()
            scalarsRaw.forEach { (k,v) -> normalizedScalars[k.toString()]=v }
            if("repeatLastCandidateRead" !in normalizedScalars) normalizedScalars["repeatLastCandidateRead"]=false
            if("batteryPort" !in normalizedScalars) normalizedScalars["batteryPort"]=""
            if("nvramFile" !in normalizedScalars) normalizedScalars["nvramFile"]=""
            val savedEnabled=data["enabled"] as? Boolean ?: throw IllegalArgumentException("Missing enabled boolean in power-peer snapshot")
            val savedProfile=scalarsRaw["profile"] as? String ?: throw IllegalArgumentException("Invalid profile in power-peer snapshot")
            require(profileRank(savedProfile)<=profileRank(profile)) { "Incompatible power-peer profile downgrade in snapshot" }
            val savedButtonProfile=scalarsRaw["powerButtonProfile"] as? String ?: throw IllegalArgumentException("Invalid powerButtonProfile in snapshot")
            require(savedButtonProfile=="disabled" || savedButtonProfile==powerButtonProfile) { "Incompatible powerButtonProfile in snapshot" }
            val savedRegisterPort=scalarsRaw["registerPort"] as? String ?: throw IllegalArgumentException("Invalid registerPort in snapshot")
            require(savedRegisterPort.isEmpty() || savedRegisterPort==registerPort) { "Incompatible registerPort in snapshot" }
            val savedRepeat=(scalarsRaw["repeatLastCandidateRead"] as? Boolean) ?: false
            require(!savedRepeat || repeatLastCandidateRead) { "Incompatible repeatLastCandidateRead downgrade in snapshot" }
            val savedBatteryPort=(scalarsRaw["batteryPort"] as? String) ?: ""
            require(savedBatteryPort.isEmpty() || savedBatteryPort==batteryPort) { "Incompatible batteryPort in snapshot" }
            val savedNvramFile=(scalarsRaw["nvramFile"] as? String) ?: ""
            require(savedNvramFile.isEmpty() || savedNvramFile==nvramFile) { "Incompatible nvramFile in snapshot" }
            val nextPhase=snapshotInt(data["phase"],"phase",0,3)
            val nextEdge=snapshotLong(data["edge"],"edge",0L,Long.MAX_VALUE)
            val nextResponse=snapshotInt(data["response"],"response",0,65535)
            val rawFrame=data["frame"] as? List<*> ?: throw IllegalArgumentException("Missing frame list in power-peer snapshot")
            require(rawFrame.size==4) { "power-peer frame requires 4 byte entries" }
            val nextFrame=IntArray(4) { index -> snapshotInt(rawFrame[index],"frame[$index]",0,255) }
            val nextAborted=data["preparedReadAborted"] as? Boolean ?: throw IllegalArgumentException("Missing preparedReadAborted boolean")
            val nextEventCount=snapshotLong(data["eventCount"],"eventCount",0L,Long.MAX_VALUE)
            val nextWordCount=snapshotLong(data["wordCount"],"wordCount",0L,Long.MAX_VALUE)
            val nextSelectedCount=snapshotLong(data["selectedWordCount"],"selectedWordCount",0L,Long.MAX_VALUE)
            val nextIdleCount=snapshotLong(data["idleWordCount"],"idleWordCount",0L,Long.MAX_VALUE)
            val nextDiscarded=snapshotLong(data["discardedWords"],"discardedWords",0L,Long.MAX_VALUE)
            require(Math.addExact(nextSelectedCount,nextIdleCount)==nextWordCount) { "power-peer word count breakdown mismatch" }
            val nextEvents=parseEventLog(data["events"],nextEventCount)
            val nextWordsLog=parseWordsLog(data["wordsLog"],nextWordCount)
            val rawStored=data["stored"] as? Map<*,*> ?: throw IllegalArgumentException("Missing stored register map")
            if(!savedEnabled) {
                require(scalarsRaw["profile"]=="disabled") { "Saved enabled=false disagrees with saved profile" }
                require(
                    nextPhase==0 && nextEdge==0L && nextResponse==0 && nextFrame.all { byteVal -> byteVal==0 } &&
                        rawStored.isEmpty() && !nextAborted && data["preparedDelegatedRead"]==null && data["candidateReads"]==null &&
                        nextEventCount==0L && nextWordCount==0L && nextDiscarded==0L,
                ) { "Disabled power-peer snapshot contains non-zero runtime state" }
                restoreScalarValues(
                    normalizedScalars,
                    DESCRIPTOR,
                    compatibleNonLiveKeys=setOf("profile","inputFile","registerPort","powerButtonProfile","repeatLastCandidateRead","batteryPort","nvramFile"),
                )
                detachPreparedReadWithoutCancel()
                candidateReads?.reset()
                repeat(128) { selector ->
                    stored[selector]=if(enabled && (retained[selector] || writeMasks[selector]!=0)) words[selector] else -1
                }
                phase=0;edge=0L;response=0;frame=IntArray(4);preparedReadAborted=false
                eventCount=0L;wordCount=0L;selectedCount=0L;idleCount=0L;discarded=0L
                events.clear();log.clear()
                return
            }
            require(enabled) { "Cannot restore enabled power-peer snapshot into disabled component" }
            val sameConfig=scalarsRaw["profile"]==profile && scalarsRaw["inputFile"]==values["inputFile"]
            val nextStored=IntArray(128) { selector ->
                if(retained[selector] || writeMasks[selector]!=0) words[selector] else -1
            }
            val seenStored=HashSet<Int>()
            for((rawKey,rawValue) in rawStored) {
                val keyText=rawKey as? String ?: rawKey?.toString() ?: throw IllegalArgumentException("Null stored register key")
                require(keyText.matches(Regex("0|[1-9][0-9]{0,2}"))) { "Invalid stored register key: $keyText" }
                val selector=keyText.toInt()
                require(selector in 0..127 && seenStored.add(selector)) { "Duplicate or out-of-range stored register: $selector" }
                require(retained[selector] || writeMasks[selector]!=0) { "Snapshot stored register $selector is not storable" }
                val registerValue=snapshotInt(rawValue,"stored[$selector]",0,65535)
                if(writeMasks[selector]!=0) {
                    require(((registerValue xor words[selector]) and writeMasks[selector].inv() and 65535)==0) {
                        "Snapshot stored register $selector modifies unmasked bits"
                    }
                }
                nextStored[selector]=registerValue
            }
            if(sameConfig) {
                val expectedStorable=(0..127).filter { selector -> retained[selector] || writeMasks[selector]!=0 }.toSet()
                require(seenStored==expectedStorable) { "Stored register key set differs from live configuration" }
            }
            val rawCandidates=data["candidateReads"]
            val parsedCandidates=if(rawCandidates!=null) {
                val liveCandidates=candidateReads ?: throw IllegalArgumentException("Snapshot includes candidateReads for non-v3 power-peer")
                if (liveCandidates.extendedBattery && rawCandidates is Map<*, *> && "selector03Prefix" !in rawCandidates) {
                    val oldFile = scalarsRaw["inputFile"] as? String
                    require(savedProfile == profile && !oldFile.isNullOrBlank() && oldFile != values["inputFile"]) {
                        "Legacy selector 03 extension requires the snapshot's separate original input file"
                    }
                    val original = context.readData(oldFile)
                    val selected = context.readData(values.getValue("inputFile") as String)
                    val originalRows = original["candidate_reads"] as? List<*> ?: error("Missing original candidate reads")
                    val selectedRows = liveCandidates.inputSnapshot()
                    require(original - "candidate_reads" == selected - "candidate_reads" && originalRows.size == selectedRows.size)
                    originalRows.zip(selectedRows).forEach { (previous, next) ->
                        require(previous is Map<*, *>)
                        if (next["selector"] == 3) {
                            val samples = previous["values"] as? List<*> ?: error("Missing original selector 03 samples")
                            require(samples.size in 1..96 && next - "values" == previous - "values" &&
                                (next["values"] as List<*>).take(samples.size) == samples) {
                                "Legacy selector 03 sample prefix differs from selected input"
                            }
                        } else require(previous == next) { "Unrelated candidate input differs" }
                    }
                    FiniteCandidateReads(originalRows).use { it.parseSnapshot(rawCandidates, strictSelectors = true) }
                }
                liveCandidates.parseSnapshot(rawCandidates,strictSelectors=sameConfig || liveCandidates.extendedBattery)
            } else {
                require(scalarsRaw["profile"]!="finite-candidate-reads-experimental-v3") { "Missing candidateReads for v3 power-peer snapshot" }
                null
            }
            val nextDelegated=data["preparedDelegatedRead"]?.let { rawDelegated ->
                val delegatedMap=rawDelegated as? Map<*,*> ?: throw IllegalArgumentException("Invalid preparedDelegatedRead map")
                require(delegatedMap.keys==setOf("selector","value","headerTick")) { "Invalid preparedDelegatedRead keys" }
                val selector=snapshotInt(delegatedMap["selector"],"preparedDelegatedRead.selector",0,127)
                val regValue=snapshotInt(delegatedMap["value"],"preparedDelegatedRead.value",0,65535)
                val headerTick=snapshotLong(delegatedMap["headerTick"],"preparedDelegatedRead.headerTick",0L,Long.MAX_VALUE)
                require(registerPort.isNotEmpty()) { "Delegated prepared read requires configured registerPort" }
                if(pins!=null) require(delegated[selector] && preparedConnection) { "Delegated prepared read selector $selector not claimed by prepared endpoint" }
                DelegatedPendingSpec(selector,regValue,headerTick)
            }
            val hasCandidatePending=parsedCandidates!=null && parsedCandidates.pendingSelector>=0
            require(!(hasCandidatePending && nextDelegated!=null)) { "Conflicting candidate and delegated prepared reads in snapshot" }
            if(nextPhase==0) {
                require(!nextAborted && !hasCandidatePending && nextDelegated==null) { "Idle phase=0 cannot carry prepared or aborted read state" }
            }
            if(nextAborted) {
                require(nextPhase==3 && !hasCandidatePending && nextDelegated==null && (candidateReads!=null || registerPort.isNotEmpty())) {
                    "Invalid preparedReadAborted state in power-peer snapshot"
                }
            }
            if(hasCandidatePending) {
                val pendingCandidates=checkNotNull(parsedCandidates)
                val expectedValue=checkNotNull(candidateReads).valueAt(pendingCandidates.pendingSelector,pendingCandidates.pendingIndex)
                require(
                    nextPhase in 1..3 && (nextFrame[0] and 128)==0 &&
                        (nextFrame[0] and 127)==pendingCandidates.pendingSelector && nextResponse==expectedValue,
                ) { "Candidate prepared read disagrees with transport frame state" }
            }
            if(nextDelegated!=null) {
                require(
                    nextPhase in 1..3 && (nextFrame[0] and 128)==0 &&
                        (nextFrame[0] and 127)==nextDelegated.selector && nextResponse==nextDelegated.value,
                ) { "Delegated prepared read disagrees with transport frame state" }
            }
            if(nextPhase in 1..3 && (nextFrame[0] and 128)==0 && !nextAborted) {
                val headerSelector=nextFrame[0] and 127
                if(!isBatterySelector(headerSelector) && candidateReads?.contains(headerSelector)==true) {
                    require(hasCandidatePending) { "Active candidate read frame missing pending candidate lease" }
                } else if(pins!=null && delegated[headerSelector] && preparedConnection) {
                    require(nextDelegated!=null) { "Active delegated prepared read frame missing delegated lease" }
                }
            }
            restoreScalarValues(
                normalizedScalars,
                DESCRIPTOR,
                compatibleNonLiveKeys=setOf("profile","inputFile","registerPort","powerButtonProfile","repeatLastCandidateRead","batteryPort","nvramFile"),
            )
            detachPreparedReadWithoutCancel()
            val restoredCandidateLease=if(parsedCandidates!=null) {
                checkNotNull(candidateReads).applySnapshot(parsedCandidates)
            } else {
                candidateReads?.reset()
                null
            }
            if(restoredCandidateLease!=null) {
                preparedRead=restoredCandidateLease
                preparedDelegatedTick=-1L
            } else if(nextDelegated!=null) {
                preparedRead=ReattachingDelegatedLease(nextDelegated.selector,nextDelegated.value,nextDelegated.headerTick)
                preparedDelegatedTick=nextDelegated.headerTick
            } else {
                preparedRead=null
                preparedDelegatedTick=-1L
            }
            nextStored.copyInto(stored)
            phase=nextPhase
            edge=nextEdge
            response=nextResponse
            frame=nextFrame
            preparedReadAborted=nextAborted
            eventCount=nextEventCount
            wordCount=nextWordCount
            selectedCount=nextSelectedCount
            idleCount=nextIdleCount
            discarded=nextDiscarded
            events.clear();events.addAll(nextEvents)
            log.clear();log.addAll(nextWordsLog)
        }
        private fun detachPreparedReadWithoutCancel() {
            val current=preparedRead
            if(current is ReattachingDelegatedLease) current.detach() else current?.cancel()
            preparedRead=null
            preparedDelegatedTick=-1L
        }
        private fun parseEventLog(raw: Any?,totalCount: Long): List<Map<String,Any>> {
            val rows=raw as? List<*> ?: throw IllegalArgumentException("Missing power-peer events list")
            val expectedSize=minOf(totalCount,256L).toInt()
            require(rows.size==expectedSize) { "power-peer events list size mismatches eventCount" }
            return rows.mapIndexed { index,item ->
                val row=item as? Map<*,*> ?: throw IllegalArgumentException("Invalid power-peer event at $index")
                require(row.keys==setOf("tick","register","write","value","response","frame","replyChecksum")) {
                    "Invalid power-peer event keys at $index"
                }
                val tick=snapshotLong(row["tick"],"events[$index].tick",0L,Long.MAX_VALUE)
                val register=snapshotInt(row["register"],"events[$index].register",0,127)
                val write=row["write"] as? Boolean ?: throw IllegalArgumentException("Invalid events[$index].write")
                val regValue=snapshotInt(row["value"],"events[$index].value",0,65535)
                val replyWord=snapshotInt(row["response"],"events[$index].response",0,65535)
                val rawBytes=row["frame"] as? List<*> ?: throw IllegalArgumentException("Invalid events[$index].frame")
                require(rawBytes.size==4) { "Invalid events[$index].frame length" }
                val frameBytes=rawBytes.mapIndexed { byteIndex,byteVal ->
                    snapshotInt(byteVal,"events[$index].frame[$byteIndex]",0,255)
                }
                val replyChecksum=snapshotInt(row["replyChecksum"],"events[$index].replyChecksum",0,255)
                linkedMapOf<String,Any>(
                    "tick" to tick,
                    "register" to register,
                    "write" to write,
                    "value" to regValue,
                    "response" to replyWord,
                    "frame" to frameBytes,
                    "replyChecksum" to replyChecksum,
                )
            }
        }
        private fun parseWordsLog(raw: Any?,totalCount: Long): List<Map<String,Any>> {
            val rows=raw as? List<*> ?: throw IllegalArgumentException("Missing power-peer wordsLog list")
            val expectedSize=minOf(totalCount,256L).toInt()
            require(rows.size==expectedSize) { "power-peer wordsLog size mismatches wordCount" }
            return rows.mapIndexed { index,item ->
                val row=item as? Map<*,*> ?: throw IllegalArgumentException("Invalid power-peer word log at $index")
                require(row.keys==setOf("tick","tx","rx","selected","edge","phase")) {
                    "Invalid power-peer word log keys at $index"
                }
                val tick=snapshotLong(row["tick"],"wordsLog[$index].tick",0L,Long.MAX_VALUE)
                val txWord=snapshotInt(row["tx"],"wordsLog[$index].tx",0,65535)
                val rxWord=snapshotInt(row["rx"],"wordsLog[$index].rx",0,65535)
                val selected=row["selected"] as? Boolean ?: throw IllegalArgumentException("Invalid wordsLog[$index].selected")
                val edgeSeq=snapshotLong(row["edge"],"wordsLog[$index].edge",0L,Long.MAX_VALUE)
                val wordPhase=snapshotInt(row["phase"],"wordsLog[$index].phase",0,3)
                linkedMapOf<String,Any>(
                    "tick" to tick,
                    "tx" to txWord,
                    "rx" to rxWord,
                    "selected" to selected,
                    "edge" to edgeSeq,
                    "phase" to wordPhase,
                )
            }
        }
        private fun profileRank(name: String): Int = when(name) {
            "disabled" -> 0
            "checksum-registers-experimental-v1" -> 1
            "masked-retained-registers-experimental-v2" -> 2
            "finite-candidate-reads-experimental-v3" -> 3
            else -> throw IllegalArgumentException("Unknown power-peer profile: $name")
        }
        private fun snapshotLong(value: Any?,field: String,min: Long,max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for $field" }
            val number=(value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $field")
            require(number in min..max) { "Out-of-range integer for $field" }
            return number
        }
        private fun snapshotInt(value: Any?,field: String,min: Int,max: Int): Int =
            snapshotLong(value,field,min.toLong(),max.toLong()).toInt()
        override fun state(): Map<String,Any?> = (super.state()-"registerPort"-"batteryPort"-"nvramFile")+(candidateReads?.let { mapOf("scriptedCandidateReads" to it.state()) } ?: emptyMap())+(if(preparedConnection || candidateReads!=null)mapOf("preparedReadState" to if(closed)"closed" else if(preparedReadAborted)"cancelled-awaiting-cs" else if(preparedRead!=null)"prepared" else "idle")else emptyMap())+(if(registerPort.isEmpty()) emptyMap() else mapOf("registerPort" to registerPort,"delegatedRegisters" to (0..127).filter{delegated[it]}))+(if(batteryPort.isEmpty()) emptyMap() else mapOf("batteryPort" to batteryPort))+(if(nvramFile.isEmpty()) emptyMap() else mapOf("nvramFile" to nvramFile))+mapOf("enabled" to enabled,"inputs" to inputs,"phase" to phase,"edge" to edge,"frame" to frame.toList(),"response" to response,
            "stored" to (0..127).filter { stored[it]>=0 }.associate { it.toString() to stored[it] },"events" to events.toList(),"eventCount" to eventCount,"eventLogTruncated" to (eventCount>events.size),
            "wordCount" to wordCount,"selectedWordCount" to selectedCount,"idleWordCount" to idleCount,"discardedWords" to discarded,"wordsLog" to log.toList(),"wordLogTruncated" to (wordCount>log.size),
            "basis" to (if(masked)"Explicit masked retained-word HYP: declared initial bits, unchanged outside write mask, checksum commit/readback only; no physical power/ready/display effects or persistence" else "DAT checksum/CS transport, explicit raw external words/retained resets/idle law; no ready/battery/RTC meaning, power side effects or persistence claim")+(if(switchBinding==null)"" else "; SOURCE power switch binding samples host Boolean only at read header; selected levels HYP, guest cache refresh/event/boot transition unverified"))
    }
}
