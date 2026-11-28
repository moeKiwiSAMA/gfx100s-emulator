package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.*

/** FW-013 masks, pinned system_control.rs retention. No inferred clock/reset side effects. */
class SystemControlFactory : ComponentFactory {
    companion object {
        private val DESCRIPTOR = ComponentDescriptor("soc-system-control","0.0.1",settings=listOf(
            Setting("profile",ValueKind.TEXT,"retained-banks-experimental-v1",choices=listOf("retained-banks-experimental-v1",UsbVbusA300Input.PROFILE,UsbDwc3Controller.PROFILE,BootColdStartMailbox.PROFILE),live=false),
            Setting("booleanWrite",ValueKind.BOOLEAN,false,live=false),Setting("serialWrite",ValueKind.BOOLEAN,false,live=false),
            Setting("readWordInputFile",ValueKind.TEXT,"",live=false,description="Optional frozen HYPOTHESIS input for the exact read-only FFF48004 word"),
            Setting("bootStrapInputFile",ValueKind.TEXT,"",live=false,description="Optional frozen HYPOTHESIS boot-strap input for aligned read-only 0xFFF4A000"),
            Setting("statusA200InputFile",ValueKind.TEXT,"",live=false,description="Optional frozen HYPOTHESIS input for aligned read-only 0xFFF4A200"),
            Setting("write49530",ValueKind.BOOLEAN,false,live=false,description="Optional HYPOTHESIS ordered write-once 0xFFF49530..0xFFF49538 setup pair"),
            Setting("tail49500",ValueKind.BOOLEAN,false,live=false,description="Optional HYPOTHESIS ordered write-once 0xFFF49500..0xFFF49504 tail; requires write49530"),
            Setting("usbVbusPresent",ValueKind.BOOLEAN,false,live=true,description="Optional USB VBUS cable-detect bit0 for read-only 0xFFF4A300 (active under usb-vbus-v1 / usb-dwc3-v2 profiles)")))
        private val V1_LEGACY_SNAPSHOT_KEYS = setOf(
            "schema", "componentId", "scalars", "wordsA", "wordsB",
            "booleanLastWrite", "serialLastWrite", "readWordFff48004",
        )
        private val V2_PRE_M6_SNAPSHOT_KEYS = V1_LEGACY_SNAPSHOT_KEYS + "bootStrapFff4a000"
        private val SNAPSHOT_KEYS = V2_PRE_M6_SNAPSHOT_KEYS + setOf("statusA200Fff4a200", "write49530State", "tail49500State")
        private val USB_VBUS_SNAPSHOT_KEYS = SNAPSHOT_KEYS + "usbVbusA300State"
        private val USB_DWC3_SNAPSHOT_KEYS = USB_VBUS_SNAPSHOT_KEYS + "usbDwc3State"
        private val BOOT_COLDSTART_SNAPSHOT_KEYS = SNAPSHOT_KEYS + "bootColdStartState"
        private fun snapshotLong(value: Any?, field: String, minimum: Long = Long.MIN_VALUE, maximum: Long = Long.MAX_VALUE): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Snapshot field $field requires integer" }
            val converted = (value as? Number)?.toLong() ?: throw IllegalArgumentException("Snapshot field $field requires integer")
            require(converted in minimum..maximum) { "Snapshot field $field out of range" }
            return converted
        }
        private fun snapshotInt(value: Any?, field: String, minimum: Int = Int.MIN_VALUE, maximum: Int = Int.MAX_VALUE): Int =
            snapshotLong(value, field, minimum.toLong(), maximum.toLong()).toInt()
        private fun snapshotList(value: Any?, field: String): List<*> =
            value as? List<*> ?: throw IllegalArgumentException("Snapshot field $field requires list")
        private fun snapshotMap(value: Any?, field: String): Map<*, *> {
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all { key -> key is String }) { "Snapshot field $field requires string keys" }
            return map
        }
    }
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext,config: Map<String,Any>): HardwareComponent = Banks(context,config)
    private class Banks(context: ComponentContext,config: Map<String,Any>) : ScalarComponent("soc-system-control",context,config), StatefulComponent, NativeMmioMirrorPort {
        private val masksA=listOf(7L,0xfffff,0x1ffffff,0xff,0xfffff,0x1ffff,7,0x7b,7,0xff,3,3,0x1c,0x1ffdf,0xfffff,0x7ff)
        private val masksB=listOf(7L,1,0xfff,0x1ff,3,0x70f3f,0x1f,0x3f,3,3,1,0x1f,0x7f,0xcf,7)
        private val a=LongArray(16); private val b=LongArray(15)
        private var mirrorA: MmioMirrorHandle? = null
        private var mirrorB: MmioMirrorHandle? = null
        private var mirrorBExtra: MmioMirrorHandle? = null
        private var mirrorReadWord: MmioMirrorHandle? = null
        private var mirrorBootStrap: MmioMirrorHandle? = null
        private var mirrorStatusA200: MmioMirrorHandle? = null
        private var mirrorUsbVbus: MmioMirrorHandle? = null
        private var batteryPort: BatteryPort? = null
        private var batteryListenerLease: AutoCloseable? = null

        private fun resolveBatteryPort(): BatteryPort? {
            batteryPort?.let { return it }
            val resolved = context.ports.find("battery", BatteryPort::class.java) ?: return null
            batteryPort = resolved
            batteryListenerLease = resolved.addPowerStateListener {
                usbVbusA300?.let { input -> mirrorUsbVbus?.writeSlot(0, input.read(0L, 4).toInt()) }
            }
            return resolved
        }

        private fun effectiveUsbVbusPresent(): Boolean =
            (values.getValue("usbVbusPresent") as Boolean) || (resolveBatteryPort()?.externalPowerConnected() == true)

        override fun start() {
            resolveBatteryPort()
            usbVbusA300?.let { input -> mirrorUsbVbus?.writeSlot(0, input.read(0L, 4).toInt()) }
        }

        override fun close() {
            batteryListenerLease?.close()
            batteryListenerLease = null
            batteryPort = null
        }

        private fun syncArrayMirror(handle: MmioMirrorHandle?, arr: LongArray, first: Int, count: Int) {
            val h = handle ?: return
            for (i in 0 until count) h.writeSlotRaw(i, arr[first + i].toInt())
            h.setValidMask(h.allSlotsMask)
        }

        private fun syncAllMirrors() {
            syncArrayMirror(mirrorA, a, 0, 16)
            syncArrayMirror(mirrorB, b, 0, 14)
            syncArrayMirror(mirrorBExtra, b, 14, 1)
            readWord?.let { input -> mirrorReadWord?.writeSlot(0, input.read(0L, 4).toInt()) }
            bootStrap?.let { input -> mirrorBootStrap?.writeSlot(0, input.read(0L, 4).toInt()) }
            statusA200?.let { input -> mirrorStatusA200?.writeSlot(0, input.read(0L, 4).toInt()) }
            usbVbusA300?.let { input -> mirrorUsbVbus?.writeSlot(0, input.read(0L, 4).toInt()) }
        }

        override fun applySettings(settings: Map<String, Any>) {
            super.applySettings(settings)
            usbVbusA300?.let { input -> mirrorUsbVbus?.writeSlot(0, input.read(0L, 4).toInt()) }
        }

        override fun describeMmioMirrorWindows(): List<MmioMirrorWindowSpec> = buildList {
            add(MmioMirrorWindowSpec("$id.a", 64L, List(16) { i -> MmioMirrorSlotSpec(i * 4L, 4) }))
            add(MmioMirrorWindowSpec("$id.b", 56L, List(14) { i -> MmioMirrorSlotSpec(i * 4L, 4) }))
            add(MmioMirrorWindowSpec("$id.b-extra", 4L, listOf(MmioMirrorSlotSpec(0L, 4))))
            if (readWord != null) add(MmioMirrorWindowSpec(ReadWordInput.PORT, 4L, listOf(MmioMirrorSlotSpec(0L, 4))))
            if (bootStrap != null) add(MmioMirrorWindowSpec(BootStrapInput.PORT, 4L, listOf(MmioMirrorSlotSpec(0L, 4))))
            if (statusA200 != null) add(MmioMirrorWindowSpec(statusA200PortName, 4L, listOf(MmioMirrorSlotSpec(0L, 4))))
            if (usbVbusA300 != null) add(MmioMirrorWindowSpec(usbVbusPortName, 4L, listOf(MmioMirrorSlotSpec(0L, 4))))
        }

        override fun attachMmioMirror(portName: String, handle: MmioMirrorHandle) {
            when (portName) {
                "$id.a" -> { mirrorA = handle; syncArrayMirror(handle, a, 0, 16) }
                "$id.b" -> { mirrorB = handle; syncArrayMirror(handle, b, 0, 14) }
                "$id.b-extra" -> { mirrorBExtra = handle; syncArrayMirror(handle, b, 14, 1) }
                ReadWordInput.PORT -> { mirrorReadWord = handle; readWord?.let { handle.writeSlot(0, it.read(0L, 4).toInt()) } }
                BootStrapInput.PORT -> { mirrorBootStrap = handle; bootStrap?.let { handle.writeSlot(0, it.read(0L, 4).toInt()) } }
                statusA200PortName -> { mirrorStatusA200 = handle; statusA200?.let { handle.writeSlot(0, it.read(0L, 4).toInt()) } }
                usbVbusPortName -> { mirrorUsbVbus = handle; usbVbusA300?.let { handle.writeSlot(0, it.read(0L, 4).toInt()) } }
            }
        }

        override fun detachMmioMirrors() {
            mirrorA?.invalidateAll(); mirrorA = null
            mirrorB?.invalidateAll(); mirrorB = null
            mirrorBExtra?.invalidateAll(); mirrorBExtra = null
            mirrorReadWord?.invalidateAll(); mirrorReadWord = null
            mirrorBootStrap?.invalidateAll(); mirrorBootStrap = null
            mirrorStatusA200?.invalidateAll(); mirrorStatusA200 = null
            mirrorUsbVbus?.invalidateAll(); mirrorUsbVbus = null
        }
        private var booleanLast: Long?=null;private var serialLast: Long?=null
        private val readWord = ReadWordInput.load(context, config.getValue("readWordInputFile") as String)
        private val bootStrap = BootStrapInput.load(context, config.getValue("bootStrapInputFile") as String)
        private val statusA200 = StatusA200Input.load(context, config.getValue("statusA200InputFile") as String)
        private val write49530Enabled = config.getValue("write49530") as Boolean
        private val tail49500Enabled = config.getValue("tail49500") as Boolean
        private val profileName = config.getValue("profile") as String
        private val usbDwc3Enabled = profileName == UsbDwc3Controller.PROFILE
        private val bootColdStartEnabled = profileName == BootColdStartMailbox.PROFILE
        private val setup49530And49500 = SetupSequence49530And49500(write49530Enabled, tail49500Enabled, repeatable = usbDwc3Enabled)
        private val usbVbusEnabled = profileName == UsbVbusA300Input.PROFILE || usbDwc3Enabled
        private val usbVbusA300 = if (usbVbusEnabled) {
            UsbVbusA300Input(
                presentProvider = ::effectiveUsbVbusPresent,
                scalarPresentProvider = { values.getValue("usbVbusPresent") as Boolean },
            )
        } else null
        private val usbDwc3 = if (usbDwc3Enabled) UsbDwc3Controller(::effectiveUsbVbusPresent) else null
        private val bootColdStart = if (bootColdStartEnabled) BootColdStartMailbox() else null
        private val statusA200PortName = when {
            usbDwc3Enabled -> UsbDwc3Controller.STATUS_A200_V3_PORT
            usbVbusEnabled -> UsbVbusA300Input.STATUS_A200_V2_PORT
            else -> StatusA200Input.PORT
        }
        private val usbVbusPortName = if (usbDwc3Enabled) UsbDwc3Controller.USB_VBUS_V2_PORT else UsbVbusA300Input.PORT
        init {
            readWord?.let { input -> context.ports.register(id,ReadWordInput.PORT,MmioDevice::class.java,input) }
            bootStrap?.let { input -> context.ports.register(id,BootStrapInput.PORT,MmioDevice::class.java,input) }
            statusA200?.let { input -> context.ports.register(id,statusA200PortName,MmioDevice::class.java,input) }
            if (write49530Enabled) {
                context.ports.register(id, SetupSequence49530And49500.WRITE49530_PORT, MmioDevice::class.java, setup49530And49500.pairDevice)
            }
            if (tail49500Enabled) {
                context.ports.register(id, SetupSequence49530And49500.TAIL49500_PORT, MmioDevice::class.java, setup49530And49500.tailDevice)
            }
            usbVbusA300?.let { input -> context.ports.register(id, usbVbusPortName, MmioDevice::class.java, input) }
            usbDwc3?.let { controller ->
                context.ports.register(id, UsbDwc3Controller.DWC3_PORT, MmioDevice::class.java, controller.dwc3Device)
                context.ports.register(id, UsbDwc3Controller.PHY_PORT, MmioDevice::class.java, controller.phyDevice)
            }
            bootColdStart?.let { mailbox ->
                context.ports.register(id, BootColdStartMailbox.CTRL_8180_PORT, MmioDevice::class.java, mailbox.ctrl8180Device)
                context.ports.register(id, BootColdStartMailbox.BOOT_STATUS_MAILBOX_PORT, MmioDevice::class.java, mailbox.bootStatusMailboxDevice)
            }
            fun port(name: String,values: LongArray,masks: List<Long>,first: Int,count: Int) {
                context.ports.register(id,name,MmioDevice::class.java,object : MmioDevice {
                    override val clockOrder=70
                    private fun index(offset: Long,size: Int): Int {
                        require(size==4 && offset>=0 && offset%4==0L && offset/4<count) { "unknown A/B control word or width" }
                        return first+(offset/4).toInt()
                    }
                    override fun read(offset: Long,size: Int)=values[index(offset,size)]
                    override fun write(offset: Long,size: Int,value: Long) {
                        val i=index(offset,size); require(value in 0..0xffffffffL && value and masks[i].inv()==0L) { "A/B control word exceeds DAT mask" }; values[i]=value
                        when (name) {
                            "$id.a" -> mirrorA?.writeSlot(i - first, value.toInt())
                            "$id.b" -> mirrorB?.writeSlot(i - first, value.toInt())
                            "$id.b-extra" -> mirrorBExtra?.writeSlot(i - first, value.toInt())
                        }
                    }
                })
            }
            port("$id.a",a,masksA,0,16); port("$id.b",b,masksB,0,14); port("$id.b-extra",b,masksB,14,1)
            for(name in listOf("boolean","serial-clock")) context.ports.register(id,"$id.$name",MmioDevice::class.java,object : MmioDevice {
                override val clockOrder=70
                override fun read(offset: Long,size: Int): Long=error("write-only control input has no readback law")
                override fun write(offset: Long,size: Int,value: Long) {
                    require(offset==0L && size==4 && value in 0..0xffffffffL)
                    if(name=="boolean") { require(values.getValue("booleanWrite")==true && value in 0..1) { "boolean write input absent or invalid" };booleanLast=value }
                    else { require(values.getValue("serialWrite")==true && (0..5).any { value and (3L shl (it*2)).inv()==0L }) { "serial clock input absent or not one two-bit lane" };serialLast=value }
                }
            })
        }
        override fun additionalMmioPorts() = listOfNotNull(
            readWord?.let { ReadWordInput.PORT },
            bootStrap?.let { BootStrapInput.PORT },
            statusA200?.let { statusA200PortName },
            SetupSequence49530And49500.WRITE49530_PORT.takeIf { write49530Enabled },
            SetupSequence49530And49500.TAIL49500_PORT.takeIf { tail49500Enabled },
            usbVbusA300?.let { usbVbusPortName },
            usbDwc3?.let { UsbDwc3Controller.DWC3_PORT },
            usbDwc3?.let { UsbDwc3Controller.PHY_PORT },
            bootColdStart?.let { BootColdStartMailbox.CTRL_8180_PORT },
            bootColdStart?.let { BootColdStartMailbox.BOOT_STATUS_MAILBOX_PORT },
        )
        // Preserve the complete historical diagnostic shape when these optional inputs are absent.
        override fun state() = (super.state() - "readWordInputFile" - "bootStrapInputFile" - "statusA200InputFile" - "write49530" - "tail49500" - "usbVbusPresent") +
            (readWord?.let { mapOf("readWordInputFile" to values.getValue("readWordInputFile"), "readWordFff48004" to it.state()) } ?: emptyMap()) +
            (bootStrap?.let { mapOf("bootStrapInputFile" to values.getValue("bootStrapInputFile"), "bootStrapFff4a000" to it.state()) } ?: emptyMap()) +
            (statusA200?.let { mapOf("statusA200InputFile" to values.getValue("statusA200InputFile"), "statusA200Fff4a200" to it.state()) } ?: emptyMap()) +
            (if (write49530Enabled) mapOf("write49530" to true, "write49530State" to setup49530And49500.pairState()) else emptyMap()) +
            (if (tail49500Enabled) mapOf("tail49500" to true, "tail49500State" to setup49530And49500.tailState()) else emptyMap()) +
            (usbVbusA300?.let { mapOf("usbVbusPresent" to values.getValue("usbVbusPresent"), "usbVbusA300State" to it.state()) } ?: emptyMap()) +
            (usbDwc3?.let { mapOf("usbDwc3State" to it.state()) } ?: emptyMap()) +
            (bootColdStart?.let { mapOf("bootColdStartState" to it.state()) } ?: emptyMap()) +
            mapOf("masksA" to masksA,"masksB" to masksB,"wordsA" to a.toList(),"wordsB" to b.toList(),"booleanLastWrite" to booleanLast,"serialLastWrite" to serialLast,
                "basis" to "DAT address/mask tables independently checked by consumer; retained words/zero reset HYPOTHESIS, physical clock/reset effects unknown")

        override fun saveSnapshot(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "scalars" to saveScalarValues(),
            "wordsA" to a.toList(),
            "wordsB" to b.toList(),
            "booleanLastWrite" to booleanLast,
            "serialLastWrite" to serialLast,
            "readWordFff48004" to readWord?.saveSnapshot(),
            "bootStrapFff4a000" to bootStrap?.saveSnapshot(),
            "statusA200Fff4a200" to statusA200?.saveSnapshot(),
            "write49530State" to setup49530And49500.savePairSnapshot(),
            "tail49500State" to setup49530And49500.saveTailSnapshot(),
        ).apply {
            if (usbVbusA300 != null) {
                put("usbVbusA300State", usbVbusA300.saveSnapshot())
            }
            if (usbDwc3 != null) {
                put("usbDwc3State", usbDwc3.saveSnapshot())
            }
            if (bootColdStart != null) {
                put("bootColdStartState", bootColdStart.saveSnapshot())
            }
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            val v1Legacy = data.keys == V1_LEGACY_SNAPSHOT_KEYS && bootStrap == null && statusA200 == null && !write49530Enabled && !tail49500Enabled && !usbVbusEnabled && !bootColdStartEnabled
            val v2PreM6 = data.keys == V2_PRE_M6_SNAPSHOT_KEYS && statusA200 == null && !write49530Enabled && !tail49500Enabled && !usbVbusEnabled && !bootColdStartEnabled
            val bootStrapAbsent = data.keys == (SNAPSHOT_KEYS - "bootStrapFff4a000") && bootStrap == null && !usbVbusEnabled && !bootColdStartEnabled
            val v3Base = data.keys == SNAPSHOT_KEYS && !usbVbusEnabled && !bootColdStartEnabled
            val v4UsbVbus = data.keys == USB_VBUS_SNAPSHOT_KEYS && usbVbusEnabled && !usbDwc3Enabled
            val v4PromoteToDwc3 = data.keys == USB_VBUS_SNAPSHOT_KEYS && usbDwc3Enabled
            val v5UsbDwc3 = data.keys == USB_DWC3_SNAPSHOT_KEYS && usbDwc3Enabled
            val v6BootColdStart = data.keys == BOOT_COLDSTART_SNAPSHOT_KEYS && bootColdStartEnabled
            require(v6BootColdStart || v5UsbDwc3 || v4PromoteToDwc3 || v4UsbVbus || v3Base || v1Legacy || v2PreM6 || bootStrapAbsent) { "Unexpected snapshot keys for $id" }
            require(snapshotInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val rawScalars = snapshotMap(data["scalars"], "scalars")
            val scalarsNormalized = LinkedHashMap<Any?, Any?>(rawScalars)
            if ((v1Legacy || bootStrapAbsent) && "bootStrapInputFile" !in scalarsNormalized) {
                scalarsNormalized["bootStrapInputFile"] = ""
            }
            if ((v1Legacy || v2PreM6)) {
                if ("statusA200InputFile" !in scalarsNormalized) scalarsNormalized["statusA200InputFile"] = ""
                if ("write49530" !in scalarsNormalized) scalarsNormalized["write49530"] = false
                if ("tail49500" !in scalarsNormalized) scalarsNormalized["tail49500"] = false
            }
            if (!v4UsbVbus && !v4PromoteToDwc3 && !v5UsbDwc3 && "usbVbusPresent" !in scalarsNormalized) {
                scalarsNormalized["usbVbusPresent"] = false
            }
            require(scalarsNormalized.keys == DESCRIPTOR.settings.map { setting -> setting.key }.toSet()) {
                "Scalar setting keys differ in snapshot for $id"
            }
            val previousValues = values
            val nextWordsA: LongArray
            val nextWordsB: LongArray
            val nextBooleanLast: Long?
            val nextSerialLast: Long?
            val validated49530: SetupSequence49530And49500.ValidatedSnapshot
            val validatedDwc3: UsbDwc3Controller.ValidatedSnapshot?
            val validatedBootColdStart: BootColdStartMailbox.ValidatedSnapshot?
            try {
                if (v4PromoteToDwc3) {
                    require(scalarsNormalized["profile"] == UsbVbusA300Input.PROFILE) {
                        "Only ${UsbVbusA300Input.PROFILE} snapshot can promote into ${UsbDwc3Controller.PROFILE}"
                    }
                    restoreScalarValues(scalarsNormalized + ("profile" to profileName), DESCRIPTOR)
                } else {
                    restoreScalarValues(scalarsNormalized, DESCRIPTOR)
                }
                val rowsA = snapshotList(data["wordsA"], "wordsA")
                require(rowsA.size == masksA.size) { "Snapshot wordsA requires ${masksA.size} entries" }
                nextWordsA = LongArray(masksA.size) { slot ->
                    val wordValue = snapshotLong(rowsA[slot], "wordsA[$slot]", 0L, 0xffffffffL)
                    require((wordValue and masksA[slot].inv()) == 0L) { "Snapshot wordsA[$slot] exceeds DAT mask" }
                    wordValue
                }
                val rowsB = snapshotList(data["wordsB"], "wordsB")
                require(rowsB.size == masksB.size) { "Snapshot wordsB requires ${masksB.size} entries" }
                nextWordsB = LongArray(masksB.size) { slot ->
                    val wordValue = snapshotLong(rowsB[slot], "wordsB[$slot]", 0L, 0xffffffffL)
                    require((wordValue and masksB[slot].inv()) == 0L) { "Snapshot wordsB[$slot] exceeds DAT mask" }
                    wordValue
                }
                nextBooleanLast = data["booleanLastWrite"]?.let { rawBoolean ->
                    require(values.getValue("booleanWrite") == true) { "Snapshot booleanLastWrite present while booleanWrite disabled" }
                    snapshotLong(rawBoolean, "booleanLastWrite", 0L, 1L)
                }
                nextSerialLast = data["serialLastWrite"]?.let { rawSerial ->
                    require(values.getValue("serialWrite") == true) { "Snapshot serialLastWrite present while serialWrite disabled" }
                    val wordValue = snapshotLong(rawSerial, "serialLastWrite", 0L, 0xffffffffL)
                    require((0..5).any { lane -> (wordValue and (3L shl (lane * 2)).inv()) == 0L }) {
                        "Snapshot serialLastWrite is not one two-bit lane"
                    }
                    wordValue
                }
                require((readWord != null) == (data["readWordFff48004"] != null)) {
                    "Snapshot readWordFff48004 presence mismatch for $id"
                }
                readWord?.validateSnapshot(data["readWordFff48004"])
                if (!v1Legacy && !bootStrapAbsent) {
                    require((bootStrap != null) == (data["bootStrapFff4a000"] != null)) {
                        "Snapshot bootStrapFff4a000 presence mismatch for $id"
                    }
                    bootStrap?.validateSnapshot(data["bootStrapFff4a000"])
                }
                if (!v1Legacy && !v2PreM6) {
                    require((statusA200 != null) == (data["statusA200Fff4a200"] != null)) {
                        "Snapshot statusA200Fff4a200 presence mismatch for $id"
                    }
                    statusA200?.validateSnapshot(data["statusA200Fff4a200"])
                    validated49530 = setup49530And49500.validateSnapshots(data["write49530State"], data["tail49500State"])
                } else {
                    validated49530 = SetupSequence49530And49500.ValidatedSnapshot(0, 0)
                }
                if (usbVbusA300 != null) {
                    usbVbusA300.validateSnapshot(data["usbVbusA300State"], values.getValue("usbVbusPresent") as Boolean)
                }
                validatedDwc3 = if (usbDwc3 != null && v5UsbDwc3) {
                    usbDwc3.validateSnapshot(data["usbDwc3State"])
                } else null
                validatedBootColdStart = if (bootColdStart != null && v6BootColdStart) {
                    bootColdStart.validateSnapshot(data["bootColdStartState"])
                } else null
            } catch (failure: Throwable) {
                values = previousValues
                throw failure
            }
            nextWordsA.copyInto(a)
            nextWordsB.copyInto(b)
            booleanLast = nextBooleanLast
            serialLast = nextSerialLast
            setup49530And49500.applySnapshot(validated49530)
            if (usbDwc3 != null) {
                if (validatedDwc3 != null) usbDwc3.applySnapshot(validatedDwc3) else usbDwc3.resetToDefault()
            }
            if (bootColdStart != null && validatedBootColdStart != null) {
                bootColdStart.applySnapshot(validatedBootColdStart)
            }
            syncAllMirrors()
        }
    }
}
