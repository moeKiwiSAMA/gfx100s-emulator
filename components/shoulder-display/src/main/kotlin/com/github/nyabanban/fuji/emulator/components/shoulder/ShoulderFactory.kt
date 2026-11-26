package com.github.nyabanban.fuji.emulator.components.shoulder

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.api.display.DotLcdTransportCodec
import java.nio.ByteBuffer

/** Opt-in archived-DAT transport view; panel identity, orientation and physical polarity remain unknown. */
class ShoulderFactory : ComponentFactory {
    companion object {
        const val UNBOUND="unbound"
        const val DOTLCD="dotlcd-serial1-experimental-v1"
        const val ORIENTATION_RAW="raw"
        const val ORIENTATION_UPRIGHT="upright"
        const val CAPTURE="soc-serial-engine.capture-1"
        const val DISPLAY="display.shoulder"
        const val BACKLIGHT_GPIO_PORT = "soc-gpio.outputs"
        /** DAT-verified GFX100S 2.13 shoulder sub-LCD backlight GPIO: Bank 8, Pin 9 (0x00080009, 0xFFF41200, active-HIGH). */
        const val BACKLIGHT_GPIO_BANK = 8
        const val BACKLIGHT_GPIO_PIN = 9
        private val VALID_CONFIG_KEYS = setOf("width", "height", "brightness", "profile", "orientation")
        private val REQUIRED_CONFIG_KEYS = setOf("width", "height", "brightness")
        private val SNAPSHOT_KEYS = setOf(
            "schema",
            "componentId",
            "scalarValues",
            "frame",
            "eventCount",
            "accepted",
            "rejected",
            "duplicateEvents",
            "skippedSequences",
            "lastSeen",
            "lastRejection",
            "lastAccepted",
        )
        private val FRAME_KEYS = setOf("schema", "width", "height", "sequence", "pixelsBase64")
        private val CAPTURE_KEYS = setOf("sequence", "size", "sha256", "completedAt", "observedAt")
    }
    override val descriptor = ComponentDescriptor("shoulder-display", "0.0.1", settings = listOf(
        Setting("width", ValueKind.INTEGER, 320L, 1.0, 1024.0, live = false),
        Setting("height", ValueKind.INTEGER, 160L, 1.0, 1024.0, live = false),
        Setting("brightness", ValueKind.DECIMAL, 1.0, 0.0, 1.0),
        Setting("profile",ValueKind.TEXT,UNBOUND,choices=listOf(UNBOUND,DOTLCD),live=false,
            description="Explicit experimental transport binding; physical shoulder mapping/polarity UNKNOWN"),
        Setting("orientation",ValueKind.TEXT,ORIENTATION_RAW,choices=listOf(ORIENTATION_RAW,ORIENTATION_UPRIGHT),live=false,
            description="Packet display orientation: 'raw' preserves wire order (304x240); 'upright' applies DAT-verified 180-degree canvas orientation (304x230 or 304x240)"),
    ))
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = Shoulder(context, config, descriptor)
    private class Shoulder(
        context: ComponentContext,
        config: Map<String, Any>,
        private val descriptor: ComponentDescriptor,
    ) : ScalarComponent(
        "shoulder-display",
        context,
        config
            .let { if ((it["profile"] ?: UNBOUND) == UNBOUND) it - "profile" else it }
            .let { if ((it["orientation"] ?: ORIENTATION_RAW) == ORIENTATION_RAW) it - "orientation" else it },
    ), StatefulComponent {
        private val profile=(config["profile"]?:UNBOUND).also {require(it in listOf(UNBOUND,DOTLCD))} as String
        private val orientation=(config["orientation"]?:ORIENTATION_RAW).also {require(it in listOf(ORIENTATION_RAW,ORIENTATION_UPRIGHT))} as String
        private val enabled=profile==DOTLCD
        private val upright=orientation==ORIENTATION_UPRIGHT
        private fun dimension(value:Any?):Int {
            require(value is Byte || value is Short || value is Int || value is Long)
            return (value as Number).toLong().also {require(it in 1..1024)}.toInt()
        }
        private val width=dimension(config["width"])
        private val height=dimension(config["height"])
        private val frame=ArgbFrameBuffer(width,height)
        private val packet=if(enabled)ByteArray(DotLcdTransportCodec.BYTES)else null
        private val pixelScratch=if(enabled)IntArray(width*height)else null
        private var subscription:AutoCloseable?=null
        private var source:TransferCapturePort?=null
        private var closed=false
        private var eventCount=0L
        private var duplicateEvents=0L
        private var skippedSequences=0L
        private var accepted=0L
        private var rejected=0L
        private var lastSeen=0L
        private var lastAccepted:TransferCapture?=null
        private var lastRejection:String?=null
        private fun owner() {check(context.ports.find(DISPLAY,DisplayPort::class.java)!=null) {"Shoulder port detached"}}
        private fun live() {owner();check(!closed) {"Shoulder display closed"}}
        private fun exact(value:Any?):Long {
            require(value is Byte || value is Short || value is Int || value is Long)
            return (value as Number).toLong()
        }
        private val display=object:DisplayPort {
            override val width:Int get(){live();return frame.width}
            override val height:Int get(){live();return frame.height}
            override val sequence:Long get(){live();return frame.sequence}
            override fun copyArgb(target:IntArray):Boolean {live();return frame.copyArgb(target)}
            override fun presentArgb(pixels:IntArray,width:Int,height:Int) {
                live();check(!enabled) {"DotLCD display accepts only completed transport captures"}
                frame.presentArgb(pixels,width,height)
            }
        }
        init {
            require(config.keys.containsAll(REQUIRED_CONFIG_KEYS) && config.keys.all { it in VALID_CONFIG_KEYS })
            if (enabled) {
                if (upright) {
                    require(
                        width == DotLcdTransportCodec.WIDTH &&
                            (height == DotLcdTransportCodec.DRAWING_ROWS || height == DotLcdTransportCodec.TRANSPORT_ROWS)
                    ) { "DotLCD upright view requires 304x230 or 304x240 geometry" }
                } else {
                    require(width == DotLcdTransportCodec.WIDTH && height == DotLcdTransportCodec.TRANSPORT_ROWS) {
                        "DotLCD view preserves all304x240 emitted bits"
                    }
                }
            }
            val brightness=config["brightness"];require(brightness is Number && brightness.toDouble().isFinite() && brightness.toDouble() in 0.0..1.0)
            values = values.mapValues { (key, value) ->
                when (key) {
                    "width", "height" -> (value as Number).toLong()
                    "brightness" -> (value as Number).toDouble()
                    else -> value
                }
            }
            context.ports.register(id,DISPLAY,DisplayPort::class.java,display)
        }
        override fun start() {
            live();check(subscription==null && source==null) {"Shoulder display already started"}
            if(enabled) {
                source=checkNotNull(context.ports.find(CAPTURE,TransferCapturePort::class.java)) {"Explicit DotLCD capture port missing"}
                subscription=context.events.subscribe(::observe)
            }
        }
        private fun observe(event:ComponentEvent) {
            if(closed || event.source!="soc-serial-engine" || event.kind!="transfer-capture-complete" || event.values["port"]!=CAPTURE)return
            live();eventCount++
            try {
                require(event.values.keys==setOf("port","sequence","size")) {"Capture event shape differs"}
                val sequence=exact(event.values["sequence"]);require(sequence>0)
                if(sequence<=lastSeen){duplicateEvents++;return}
                val capture=checkNotNull(source).capture()
                require(capture!=null && capture.sequence==sequence && capture.size.toLong()==exact(event.values["size"]) &&
                    capture.size==DotLcdTransportCodec.BYTES && capture.observedAt==event.tick && event.tick==context.clock.ticks() &&
                    capture.completedAt>=0 && capture.completedAt<=capture.observedAt) {"Current complete capture identity differs"}
                // Invalid event metadata must not suppress a later notification for the actual generation.
                skippedSequences=Math.addExact(skippedSequences,sequence-lastSeen-1);lastSeen=sequence
                val bytes=checkNotNull(packet)
                val payload=ByteBuffer.wrap(bytes)
                checkNotNull(source).read(sequence,0,payload)
                check(!payload.hasRemaining()) {"Capture read incomplete"}
                val rawBits=DotLcdTransportCodec.decode(bytes)
                val bits=if(upright)DotLcdTransportCodec.orientUpright(rawBits,preserveExtraRows=(height==DotLcdTransportCodec.TRANSPORT_ROWS))else rawBits
                val pixels=checkNotNull(pixelScratch)
                for(i in bits.indices){pixels[i]=if(bits[i].toInt()==1)0xffffffff.toInt()else 0xff000000.toInt()}
                frame.presentArgb(pixels,width,height)
                accepted++;lastAccepted=capture;lastRejection=null
            } catch(error:Exception) {
                // Observers never make a completed source transaction fail or manufacture a replacement frame.
                rejected++;lastRejection=(error.javaClass.simpleName+": "+(error.message?:"capture rejected")).take(192)
            }
        }
        override fun validateSettings(settings:Map<String,Any>) {
            live();require((settings["profile"]?:UNBOUND)==profile && (settings["orientation"]?:ORIENTATION_RAW)==orientation && dimension(settings["width"])==width && dimension(settings["height"])==height) {"Shoulder geometry/binding requires restart"}
            require(settings.keys.containsAll(REQUIRED_CONFIG_KEYS) && settings.keys.all { it in VALID_CONFIG_KEYS })
            val b=settings["brightness"];require(b is Number && b.toDouble().isFinite() && b.toDouble() in 0.0..1.0)
        }
        override fun applySettings(settings:Map<String,Any>) {
            live();validateSettings(values+settings)
            val filtered = settings
                .let { if (enabled) it else it - "profile" }
                .let { if (upright) it else it - "orientation" }
            super.applySettings(filtered)
        }
        override fun close() {if(closed)return;owner();closed=true;subscription?.close();subscription=null;source=null}
        private fun backlightOn(): Boolean =
            context.ports.find(BACKLIGHT_GPIO_PORT, GpioOutputPort::class.java)
                ?.outputLevel(BACKLIGHT_GPIO_BANK, BACKLIGHT_GPIO_PIN)
                ?: false
        override fun state():Map<String,Any?> {
            owner()
            val backlight = backlightOn()
            val legacy=super.state()+mapOf("sequence" to frame.sequence,"firmwareDisplayBinding" to if(enabled)DOTLCD else "unqualified","backlightOn" to backlight)
            return if(!enabled)legacy else legacy+mapOf("closed" to closed,"capturePort" to CAPTURE,"physicalPanelIdentity" to "UNKNOWN",
                "physicalPolarity" to "UNKNOWN","palette" to "Diagnostic bit1-white/bit0-black, not physical polarity",
                "backlightHardwareBinding" to "DAT: soc-gpio bank $BACKLIGHT_GPIO_BANK pin $BACKLIGHT_GPIO_PIN (0xFFF41200, active-HIGH)",
                "emittedRows" to DotLcdTransportCodec.TRANSPORT_ROWS,"drawingRows" to DotLcdTransportCodec.DRAWING_ROWS,"extraRowsPreserved" to (height-DotLcdTransportCodec.DRAWING_ROWS),
                "captureEvents" to eventCount,"acceptedCaptures" to accepted,"rejectedCaptures" to rejected,"duplicateEvents" to duplicateEvents,
                "skippedCaptureSequences" to skippedSequences,"lastSeenCaptureSequence" to lastSeen,"lastRejection" to lastRejection,
                "lastAcceptedCapture" to lastAccepted?.let {mapOf("sequence" to it.sequence,"size" to it.size,"sha256" to it.sha256,"completedAt" to it.completedAt,"observedAt" to it.observedAt)},
                "retention" to "Latest frame only; accepted count is not a retained complete history", "sourceScope" to "Completed typed capture, not an arbitrary RAM read or physical panel qualification")
        }
        override fun saveSnapshot(): Map<String, Any?> {
            live()
            val savedAccepted = lastAccepted?.let { capture ->
                linkedMapOf<String, Any?>(
                    "sequence" to capture.sequence,
                    "size" to capture.size,
                    "sha256" to capture.sha256,
                    "completedAt" to capture.completedAt,
                    "observedAt" to capture.observedAt,
                )
            }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalarValues" to saveScalarValues(),
                "frame" to frame.saveSnapshot(),
                "eventCount" to eventCount,
                "accepted" to accepted,
                "rejected" to rejected,
                "duplicateEvents" to duplicateEvents,
                "skippedSequences" to skippedSequences,
                "lastSeen" to lastSeen,
                "lastRejection" to lastRejection,
                "lastAccepted" to savedAccepted,
            )
        }
        override fun restoreSnapshot(data: Map<String, Any?>) {
            live()
            require(snapshotInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            require(data.keys.all { key -> key in SNAPSHOT_KEYS }) { "Unexpected key in snapshot for $id" }
            val frameMap = data["frame"] as? Map<*, *> ?: error("Missing shoulder-display frame snapshot")
            require(frameMap.keys.all { key -> key in FRAME_KEYS }) { "Unexpected key in shoulder-display frame snapshot" }
            require(snapshotInt(frameMap["schema"], "frame.schema") == 1) { "Unsupported ArgbFrameBuffer snapshot schema" }
            require(
                snapshotInt(frameMap["width"], "frame.width") == width &&
                    snapshotInt(frameMap["height"], "frame.height") == height
            ) { "ArgbFrameBuffer dimensions differ" }
            val frameSequence = snapshotNonNegativeLong(frameMap["sequence"], "frame.sequence")
            val rawPixels = frameMap["pixelsBase64"]
            require(rawPixels == null || rawPixels is String) { "Invalid ArgbFrameBuffer pixelsBase64" }
            val stagedFrame = ArgbFrameBuffer(width, height)
            stagedFrame.restoreSnapshot(frameMap)

            val nextEventCount = snapshotNonNegativeLong(data["eventCount"], "eventCount")
            val nextAccepted = snapshotNonNegativeLong(data["accepted"], "accepted")
            val nextRejected = snapshotNonNegativeLong(data["rejected"], "rejected")
            val nextDuplicateEvents = snapshotNonNegativeLong(data["duplicateEvents"], "duplicateEvents")
            val nextSkippedSequences = snapshotNonNegativeLong(data["skippedSequences"], "skippedSequences")
            val nextLastSeen = snapshotNonNegativeLong(data["lastSeen"], "lastSeen")

            val rawRejection = data["lastRejection"]
            require(rawRejection == null || (rawRejection is String && rawRejection.isNotEmpty() && rawRejection.length <= 192)) {
                "Invalid shoulder-display lastRejection"
            }
            val nextLastRejection = rawRejection

            val rawAccepted = data["lastAccepted"]
            val nextLastAccepted = if (rawAccepted == null) {
                null
            } else {
                val captureMap = rawAccepted as? Map<*, *> ?: error("Invalid shoulder-display lastAccepted map")
                require(captureMap.keys == CAPTURE_KEYS) { "Unexpected keys in shoulder-display lastAccepted" }
                val captureSequence = snapshotLong(captureMap["sequence"], "lastAccepted.sequence")
                val captureSize = snapshotInt(captureMap["size"], "lastAccepted.size")
                val captureSha256 = captureMap["sha256"] as? String ?: error("Missing lastAccepted.sha256")
                require(captureSha256.isNotEmpty() && captureSha256.length <= 256) { "Invalid lastAccepted.sha256" }
                val captureCompletedAt = snapshotNonNegativeLong(captureMap["completedAt"], "lastAccepted.completedAt")
                val captureObservedAt = snapshotNonNegativeLong(captureMap["observedAt"], "lastAccepted.observedAt")
                require(
                    captureSequence > 0L &&
                        captureSize == DotLcdTransportCodec.BYTES &&
                        captureCompletedAt <= captureObservedAt
                ) { "Invalid lastAccepted TransferCapture bounds" }
                TransferCapture(captureSequence, captureSize, captureSha256, captureCompletedAt, captureObservedAt)
            }

            val totalCategorized = Math.addExact(Math.addExact(nextAccepted, nextRejected), nextDuplicateEvents)
            require(nextEventCount == totalCategorized) { "Inconsistent shoulder-display capture event counts" }
            require((nextAccepted == 0L) == (nextLastAccepted == null)) { "Inconsistent shoulder-display accepted capture state" }
            if (nextRejected == 0L) {
                require(nextLastRejection == null) { "Unexpected lastRejection without rejected captures" }
            }
            if (nextLastSeen == 0L) {
                require(nextSkippedSequences == 0L && nextAccepted == 0L) { "Non-zero capture progress with zero lastSeen" }
            }
            require(nextSkippedSequences <= nextLastSeen) { "skippedSequences exceeds lastSeen" }
            val nonDuplicateAttempts = nextLastSeen - nextSkippedSequences
            require(
                nextAccepted <= nonDuplicateAttempts &&
                    nonDuplicateAttempts <= Math.addExact(nextAccepted, nextRejected)
            ) { "Inconsistent shoulder-display lastSeen and skippedSequences" }
            if (nextLastAccepted != null) {
                require(nextLastAccepted.sequence <= nextLastSeen) { "lastAccepted sequence exceeds lastSeen" }
            }
            if (!enabled) {
                require(
                    nextEventCount == 0L &&
                        nextLastSeen == 0L &&
                        nextLastRejection == null &&
                        nextLastAccepted == null
                ) { "Unbound shoulder-display cannot restore DotLCD capture state" }
            } else {
                require(frameSequence == nextAccepted) { "DotLCD frame sequence must match accepted capture count" }
                if (frameSequence > 0L) {
                    val stagedPixels = IntArray(Math.multiplyExact(width, height))
                    check(stagedFrame.copyArgb(stagedPixels))
                    require(stagedPixels.all { pixel -> pixel == 0xffffffff.toInt() || pixel == 0xff000000.toInt() }) {
                        "DotLCD frame contains non-diagnostic pixel value"
                    }
                }
            }

            restoreScalarValues(data["scalarValues"], descriptor, compatibleNonLiveKeys = setOf("profile"))
            frame.restoreSnapshot(frameMap)
            eventCount = nextEventCount
            accepted = nextAccepted
            rejected = nextRejected
            duplicateEvents = nextDuplicateEvents
            skippedSequences = nextSkippedSequences
            lastSeen = nextLastSeen
            lastRejection = nextLastRejection
            lastAccepted = nextLastAccepted
        }
        private fun snapshotLong(value: Any?, field: String): Long = when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            is java.math.BigInteger -> {
                require(value.bitLength() <= 63) { "$field out of Long range" }
                value.toLong()
            }
            is java.math.BigDecimal -> try {
                value.longValueExact()
            } catch (exception: ArithmeticException) {
                throw IllegalArgumentException("$field requires exact integer", exception)
            }
            is Number -> {
                val decimal = value.toDouble()
                require(
                    decimal.isFinite() &&
                        decimal % 1.0 == 0.0 &&
                        decimal >= Long.MIN_VALUE.toDouble() &&
                        decimal < 9223372036854775808.0
                ) { "$field requires exact integer" }
                decimal.toLong()
            }
            else -> throw IllegalArgumentException("$field requires integer")
        }
        private fun snapshotNonNegativeLong(value: Any?, field: String): Long {
            val number = snapshotLong(value, field)
            require(number >= 0L) { "$field must be non-negative" }
            return number
        }
        private fun snapshotInt(value: Any?, field: String): Int {
            val number = snapshotLong(value, field)
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$field out of Int range" }
            return number.toInt()
        }
    }
}
