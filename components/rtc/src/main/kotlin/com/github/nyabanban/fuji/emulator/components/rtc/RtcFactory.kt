package com.github.nyabanban.fuji.emulator.components.rtc

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class RtcFactory(
    private val epochSecondsProvider: () -> Long = { System.currentTimeMillis() / 1000L },
) : ComponentFactory {
    constructor() : this({ System.currentTimeMillis() / 1000L })

    companion object {
        /** Calibrated virtual tick rate matching GXUP0004.DAT kernel 0x5A920 timer divisor (54,000,000 Hz). */
        const val CALIBRATED_TICKS_PER_SECOND = 54_000_000L
        /** GFX100S RTC epoch: 2000-01-01T00:00:00Z in Unix epoch seconds (initialSeconds=662774400 => 2021-01-01Z). */
        const val GFX_RTC_EPOCH_UNIX_SECONDS = 946_684_800L
        const val CLOCK_MODE_INPUT_FILE = "input-file"
        const val CLOCK_MODE_CALIBRATED_54MHZ = "calibrated-54mhz"
        const val CLOCK_MODE_HOST_WALL_CLOCK = "host-wall-clock"

        private val LEGACY_SCALAR_KEYS = setOf("profile", "inputFile")
        private val LEGACY_SCALAR_KEYS_V2 = setOf("profile", "inputFile", "clockMode")
        private val STATE_ENTRY_REGEX = Regex(""""(seconds|savedWallEpochSeconds|control)"\s*:\s*(-?[0-9]{1,19})""")
        private val DESCRIPTOR=ComponentDescriptor("rtc","0.0.1",settings=listOf(
            Setting("profile",ValueKind.TEXT,"disabled",choices=listOf("disabled","gfx100s-rtc-functional-experimental-v1","gfx100s-rtc-scripted-stage-read-experimental-v2"),live=false),
            Setting("inputFile",ValueKind.TEXT,"",live=false),
            Setting("clockMode",ValueKind.TEXT,CLOCK_MODE_INPUT_FILE,choices=listOf(CLOCK_MODE_INPUT_FILE,CLOCK_MODE_CALIBRATED_54MHZ,CLOCK_MODE_HOST_WALL_CLOCK),live=false),
            Setting("stateFile",ValueKind.TEXT,"",live=false),
        ))
    }
    override val descriptor=DESCRIPTOR
    override fun create(context: ComponentContext,config: Map<String,Any>): HardwareComponent=Rtc(context,config,epochSecondsProvider)
    private class Rtc(
        context: ComponentContext,
        config: Map<String,Any>,
        private val epochSecondsProvider: () -> Long,
    ) : ScalarComponent("rtc",context,config),StatefulComponent {
        private val profile=config.getValue("profile") as String
        private val clockMode=(config["clockMode"] as? String) ?: CLOCK_MODE_INPUT_FILE
        private val stateFile=(config["stateFile"] as? String).orEmpty()
        private val statePath: Path?
        private val bank: RtcBank?
        private val scripted: ScriptedRtcReadPort?
        private val inputs: Map<String,Any?>
        private var closed=false
        private data class PersistedRtcState(val seconds: Long, val savedWallEpochSeconds: Long, val control: Int)
        private fun resolveSafeStatePath(rootDir: Path, relative: String): Path {
            val rel=Path.of(relative)
            require(!rel.isAbsolute && rel == rel.normalize() && rel.none { it.toString() in setOf(".", "..") }) {
                "Invalid rtc stateFile relative path: $relative"
            }
            val base=rootDir.toAbsolutePath().normalize()
            val resolved=base.resolve(rel).normalize()
            require(resolved.startsWith(base) && resolved != base) { "rtc stateFile escapes component directory: $relative" }
            var cur: Path? = resolved
            while (cur != null && cur != base) {
                require(!Files.isSymbolicLink(cur)) { "rtc stateFile cannot traverse symlink: $cur" }
                cur=cur.parent
            }
            return resolved
        }
        private fun loadPersistedState(path: Path): PersistedRtcState? {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
            require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                "rtc stateFile must be a regular non-symlink file"
            }
            val raw=Files.readString(path, StandardCharsets.UTF_8).trim()
            if (raw.isEmpty()) return null
            require(raw.startsWith("{") && raw.endsWith("}")) { "Invalid rtc state JSON envelope" }
            val body=raw.substring(1, raw.length - 1).trim()
            if (body.isEmpty()) return null
            val parsed=HashMap<String, Long>()
            for (part in body.split(',')) {
                val match=STATE_ENTRY_REGEX.matchEntire(part.trim())
                    ?: throw IllegalArgumentException("Invalid rtc state entry: ${part.trim()}")
                val key=match.groupValues[1]
                val value=match.groupValues[2].toLong()
                require(parsed.put(key, value) == null) { "Duplicate rtc state key: $key" }
            }
            val sec=parsed["seconds"] ?: throw IllegalArgumentException("Missing rtc state seconds")
            val wall=parsed["savedWallEpochSeconds"] ?: throw IllegalArgumentException("Missing rtc state savedWallEpochSeconds")
            val ctrl=parsed["control"] ?: throw IllegalArgumentException("Missing rtc state control")
            require(sec in 0L..0xffffffffL && wall >= 0L && ctrl in 0L..0xffffL) { "Out-of-range rtc persisted state" }
            return PersistedRtcState(sec, wall, ctrl.toInt() and 0xfdff)
        }
        private fun persistState(path: Path, seconds: Long, control: Int) {
            val wallNow=epochSecondsProvider().coerceAtLeast(0L)
            val safeSec=seconds and 0xffffffffL
            val safeCtrl=control and 0xfdff
            val payload="""{"seconds": $safeSec, "savedWallEpochSeconds": $wallNow, "control": $safeCtrl}""" + "\n"
            val parent=path.parent
            if (parent != null) Files.createDirectories(parent)
            val tmp=Files.createTempFile(parent, ".rtc-state-", ".tmp")
            try {
                Files.writeString(tmp, payload, StandardCharsets.UTF_8)
                try {
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (error: Throwable) {
                runCatching { Files.deleteIfExists(tmp) }
                throw error
            }
        }
        init {
            require(profile in listOf("disabled","gfx100s-rtc-functional-experimental-v1","gfx100s-rtc-scripted-stage-read-experimental-v2")) { "Unknown RTC profile" }
            require(clockMode in listOf(CLOCK_MODE_INPUT_FILE, CLOCK_MODE_CALIBRATED_54MHZ, CLOCK_MODE_HOST_WALL_CLOCK)) { "Unknown RTC clockMode" }
            val enabled=profile!="disabled"
            val scriptedReads=profile=="gfx100s-rtc-scripted-stage-read-experimental-v2"
            val file=config.getValue("inputFile") as String
            require(enabled==file.isNotBlank()) { "RTC profile/inputFile selection mismatch" }
            statePath = if (enabled && stateFile.isNotBlank() && context.relaxedMode) resolveSafeStatePath(context.directory, stateFile) else null
            if(enabled) {
                val data=context.readData(file)
                require(data.keys==setOf("schema","profile","ticksPerSecond","initialSeconds","initialControl","counterRunning","loadDelayTicks") + if(scriptedReads)setOf("stageReadInputs") else emptySet<String>()) { "complete explicit RTC inputs required" }
                val schema=if(scriptedReads)2L else 1L
                require(integer(data["schema"],schema,schema)==schema && data["profile"]==profile)
                val fileFrequency=integer(data["ticksPerSecond"],1,Long.MAX_VALUE)
                val fileSeconds=integer(data["initialSeconds"],0,0xffffffffL)
                val frequency=when(clockMode) {
                    CLOCK_MODE_INPUT_FILE -> fileFrequency
                    CLOCK_MODE_CALIBRATED_54MHZ, CLOCK_MODE_HOST_WALL_CLOCK -> CALIBRATED_TICKS_PER_SECOND
                    else -> error("Unknown RTC clockMode")
                }
                val persisted=statePath?.let { loadPersistedState(it) }
                val seconds=if (persisted != null) {
                    val wallDelta=(epochSecondsProvider() - persisted.savedWallEpochSeconds).coerceAtLeast(0L)
                    (persisted.seconds + wallDelta) and 0xffffffffL
                } else when(clockMode) {
                    CLOCK_MODE_HOST_WALL_CLOCK -> (epochSecondsProvider() - GFX_RTC_EPOCH_UNIX_SECONDS).coerceIn(0L, 0xffffffffL)
                    else -> fileSeconds
                }
                val control=persisted?.control ?: integer(data["initialControl"],0,65535).toInt()
                val delay=integer(data["loadDelayTicks"],1,Long.MAX_VALUE)
                val running=data["counterRunning"];require(running is Boolean)
                val persistPath=statePath
                val activeBank=RtcBank(
                    ticksPerSecond = frequency,
                    initialSeconds = seconds,
                    initialControl = control,
                    counterRunning = running,
                    loadDelayTicks = delay,
                    onLoadCommitted = if (persistPath != null) { sec, ctrl -> persistState(persistPath, sec, ctrl) } else null,
                )
                bank=activeBank
                val stageInputs=if(scriptedReads) {
                    val raw=data["stageReadInputs"];require(raw is Map<*,*> && raw.keys==setOf("29","30")) { "Both RTC stage-read selectors must be explicit" }
                    (29..30).associateWith { selector ->
                        val values=raw[selector.toString()];require(values is List<*> && values.size in 1..64) { "RTC sequence must contain 1..64 raw16 samples" }
                        values.map { integer(it,0,65535).toInt() }.toIntArray()
                    }
                } else null
                scripted=stageInputs?.let { ScriptedRtcReadPort(activeBank,it) }
                inputs=mapOf("schema" to schema.toInt(),"profile" to data.getValue("profile"),"ticksPerSecond" to frequency,"initialSeconds" to seconds,"initialControl" to control,"counterRunning" to running,"loadDelayTicks" to delay) +
                    if(stageInputs==null)emptyMap() else mapOf("stageReadInputs" to stageInputs.entries.associate { it.key.toString() to it.value.toList() })
                context.ports.register(id,"rtc.registers",PowerRegisterPort::class.java,scripted ?: bank)
            } else {bank=null;scripted=null;inputs=emptyMap()}
        }
        private fun integer(value: Any?,min: Long,max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "RTC field requires exact integer" }
            val n=(value as Number).toLong();require(n in min..max);return n
        }
        override fun close() {
            val persistPath = statePath
            val activeBank = bank
            if (!closed && persistPath != null && activeBank != null) {
                val nowTick=context.clock.ticks()
                runCatching { persistState(persistPath, activeBank.currentSeconds(nowTick), activeBank.currentControl(nowTick)) }
                    .onFailure { err ->
                        java.util.logging.Logger.getLogger(RtcFactory::class.java.name)
                            .warning("[FALLBACK][rtc-state] Failed to persist RTC state to $persistPath on close: ${err.message}")
                    }
            }
            closed=true;scripted?.close();bank?.close()
        }
        override fun saveSnapshot(): Map<String,Any?> {
            check(!closed && bank?.isClosed!=true && scripted?.isClosed!=true) { "RTC component closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "enabled" to (bank!=null),
                "bank" to bank?.saveSnapshot(),
                "scriptedStageReads" to scripted?.saveSnapshot(),
            )
        }
        override fun restoreSnapshot(data: Map<String,Any?>) {
            check(!closed && bank?.isClosed!=true && scripted?.isClosed!=true) { "RTC component closed" }
            val expectedKeys=setOf("schema","componentId","scalars","enabled","bank","scriptedStageReads")
            require(data.keys==expectedKeys) { "Unexpected or missing keys in rtc snapshot" }
            require(snapshotInt(data["schema"],"schema",1,1)==snapshotSchemaVersion) { "Unsupported rtc snapshot schema" }
            require(data["componentId"]==id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw=data["scalars"] as? Map<*,*> ?: throw IllegalArgumentException("Missing scalar settings map for $id")
            val currentKeys = DESCRIPTOR.settings.map { setting -> setting.key }.toSet()
            require(scalarsRaw.keys.all { it is String && it in currentKeys } && LEGACY_SCALAR_KEYS.all { it in scalarsRaw.keys }) {
                "Scalar setting keys differ for $id"
            }
            val normalizedScalars = LinkedHashMap<Any?, Any?>(scalarsRaw).apply {
                putIfAbsent("clockMode", CLOCK_MODE_INPUT_FILE)
                putIfAbsent("stateFile", "")
            }
            val savedProfile=normalizedScalars["profile"] as? String ?: throw IllegalArgumentException("Invalid RTC profile in snapshot")
            require(profileRank(savedProfile)<=profileRank(profile)) { "Incompatible RTC profile downgrade in snapshot" }
            val savedEnabled=data["enabled"] as? Boolean ?: throw IllegalArgumentException("Missing enabled boolean in rtc snapshot")
            if(!savedEnabled) {
                require(savedProfile=="disabled" && data["bank"]==null && data["scriptedStageReads"]==null) {
                    "Disabled RTC snapshot contains active bank or scripted state"
                }
                restoreScalarValues(normalizedScalars,DESCRIPTOR,compatibleNonLiveKeys=setOf("profile","inputFile","clockMode","stateFile"))
                scripted?.reset()
                bank?.reset()
                return
            }
            val liveBank=bank ?: throw IllegalArgumentException("Cannot restore enabled RTC snapshot into disabled RTC")
            val parsedBank=liveBank.parseSnapshot(data["bank"])
            val rawScripted=data["scriptedStageReads"]
            val parsedScripted=if(rawScripted!=null) {
                require(savedProfile=="gfx100s-rtc-scripted-stage-read-experimental-v2") { "Unexpected scriptedStageReads in v1 RTC snapshot" }
                val liveScripted=scripted ?: throw IllegalArgumentException("Cannot restore scripted RTC snapshot into unscripted RTC")
                liveScripted.parseSnapshot(rawScripted,parsedBank)
            } else {
                require(savedProfile=="gfx100s-rtc-functional-experimental-v1") { "Missing scriptedStageReads for v2 RTC snapshot" }
                null
            }
            restoreScalarValues(normalizedScalars,DESCRIPTOR,compatibleNonLiveKeys=setOf("profile","inputFile","clockMode","stateFile"))
            liveBank.applySnapshot(parsedBank)
            if(parsedScripted!=null) {
                checkNotNull(scripted).applySnapshot(parsedScripted)
            } else {
                scripted?.reset()
            }
            val persistPath = statePath
            if (persistPath != null) {
                val nowTick=context.clock.ticks()
                runCatching { persistState(persistPath, liveBank.currentSeconds(nowTick), liveBank.currentControl(nowTick)) }
            }
        }
        private fun profileRank(name: String): Int = when(name) {
            "disabled" -> 0
            "gfx100s-rtc-functional-experimental-v1" -> 1
            "gfx100s-rtc-scripted-stage-read-experimental-v2" -> 2
            else -> throw IllegalArgumentException("Unknown RTC profile: $name")
        }
        private fun snapshotLong(value: Any?,field: String,min: Long,max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for $field" }
            val number=(value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $field")
            require(number in min..max) { "Out-of-range integer for $field" }
            return number
        }
        private fun snapshotInt(value: Any?,field: String,min: Int,max: Int): Int =
            snapshotLong(value,field,min.toLong(),max.toLong()).toInt()
        override fun state(): Map<String,Any?> = super.state()+mapOf("enabled" to (bank!=null),"inputs" to inputs,"registers" to bank?.state(context.clock.ticks()),
            "basis" to (if(scripted==null) "Explicit functional RTC hypothesis. GFX DAT register consumers and staged load sequence; initial values, oscillator/timebase, independent half reads, running law and delayed strobe completion unmeasured. No physical RTC, persistence, battery or menu claim" else "Explicit finite scripted stage-read HYP. Only a checksum-qualified complete read frame consumes an input; no staged reset/readback, counter alias, physical RTC, persistence or boot claim")) +
            (scripted?.let { mapOf("scriptedStageReads" to it.state()) } ?: emptyMap())
    }
}
