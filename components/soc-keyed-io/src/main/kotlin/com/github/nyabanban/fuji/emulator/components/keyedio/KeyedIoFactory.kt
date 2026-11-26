package com.github.nyabanban.fuji.emulator.components.keyedio

import com.github.nyabanban.fuji.emulator.api.*

/** Pinned keyed_io.rs law. Instant completion/zero reset are explicit functional hypotheses. */
class KeyedIoFactory : ComponentFactory {
    companion object {
        private val DESCRIPTOR = ComponentDescriptor("soc-keyed-io","0.0.1",settings=listOf(
            Setting("profile",ValueKind.TEXT,"keyed-io-functional-experimental-v1",choices=listOf("keyed-io-functional-experimental-v1"),live=false)))
        private val SNAPSHOT_KEYS = setOf(
            "schema", "componentId", "scalars", "command", "status",
            "active", "enable", "disable", "routes",
        )
        private fun snapshotLong(value: Any?, field: String, minimum: Long = Long.MIN_VALUE, maximum: Long = Long.MAX_VALUE): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Snapshot field $field requires integer" }
            val converted = (value as? Number)?.toLong() ?: throw IllegalArgumentException("Snapshot field $field requires integer")
            require(converted in minimum..maximum) { "Snapshot field $field out of range" }
            return converted
        }
        private fun snapshotInt(value: Any?, field: String): Int =
            snapshotLong(value, field, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        private fun snapshotList(value: Any?, field: String): List<*> =
            value as? List<*> ?: throw IllegalArgumentException("Snapshot field $field requires list")
        private fun snapshotMap(value: Any?, field: String): Map<*, *> {
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all { key -> key is String }) { "Snapshot field $field requires string keys" }
            return map
        }
    }
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String,Any>): HardwareComponent = KeyedIo(context,config)
    private class KeyedIo(context: ComponentContext, config: Map<String,Any>) : ScalarComponent("soc-keyed-io",context,config), MmioDevice, StatefulComponent, NativeMmioMirrorPort {
        override val clockOrder = 60
        private var command=0L; private var status=0L; private var active=0L; private var enable=0L; private var disable=0L
        private val routes=LongArray(8)
        private var mirror: MmioMirrorHandle? = null
        init { context.ports.register(id,id,MmioDevice::class.java,this) }
        override fun describeMmioMirrorWindows(): List<MmioMirrorWindowSpec> = listOf(
            MmioMirrorWindowSpec(
                portName = id,
                windowSize = 0xA0L,
                slots = listOf(
                    MmioMirrorSlotSpec(0x00L, 4),
                    MmioMirrorSlotSpec(0x0CL, 4),
                    MmioMirrorSlotSpec(0x18L, 4),
                    MmioMirrorSlotSpec(0x20L, 4),
                    MmioMirrorSlotSpec(0x40L, 4),
                    MmioMirrorSlotSpec(0x80L, 4),
                    MmioMirrorSlotSpec(0x84L, 4),
                    MmioMirrorSlotSpec(0x88L, 4),
                    MmioMirrorSlotSpec(0x8CL, 4),
                    MmioMirrorSlotSpec(0x90L, 4),
                    MmioMirrorSlotSpec(0x94L, 4),
                    MmioMirrorSlotSpec(0x98L, 4),
                    MmioMirrorSlotSpec(0x9CL, 4),
                ),
            ),
        )
        override fun attachMmioMirror(portName: String, handle: MmioMirrorHandle) {
            if (portName != id) return
            mirror = handle
            syncMirror()
        }
        override fun detachMmioMirrors() {
            mirror?.invalidateAll()
            mirror = null
        }
        private fun syncMirror() {
            val h = mirror ?: return
            h.writeSlotRaw(0, command.toInt())
            h.writeSlotRaw(1, status.toInt())
            h.writeSlotRaw(2, active.toInt())
            h.writeSlotRaw(3, enable.toInt())
            h.writeSlotRaw(4, disable.toInt())
            for (i in 0..7) h.writeSlotRaw(5 + i, routes[i].toInt())
            h.setValidMask(h.allSlotsMask)
        }
        private fun check(offset: Long,size: Int) { require(size==4 && offset in 0..0x9c && offset%4==0L) { "keyed IO requires aligned words" } }
        override fun read(offset: Long,size: Int): Long {
            check(offset,size)
            return when(offset) { 0L->command; 0xcL->status; 0x18L->active; 0x20L->enable; 0x40L->disable
                in 0x80L..0x9cL -> routes[((offset-0x80)/4).toInt()]; else -> error("unmodeled keyed IO read") }
        }
        override fun write(offset: Long,size: Int,value: Long) {
            check(offset,size); require(value in 0..0xffffffffL)
            when(offset) {
                0L -> { require(value and 0x7fffffff in listOf(0xaa01L,0xaa02L)) { "unsupported keyed IO command" }
                    command=value; active=(active or enable) and disable.inv(); status=status or if(value and 255==1L) 0x10 else 0x20 }
                0x10L -> status=status and value.inv()
                0x20L -> enable=value
                0x40L -> disable=value
                in 0x80L..0x9cL -> { require(value and 0xe0e0e0e0L==0L) { "keyed IO routes exceed five bits" }; routes[((offset-0x80)/4).toInt()]=value }
                else -> error("unmodeled keyed IO write")
            }
            syncMirror()
        }
        override fun state() = super.state()+mapOf("command" to command,"status" to status,"active" to active,"enable" to enable,"disable" to disable,"routes" to routes.toList(),
            "basis" to "Pinned keyed_io.rs law; zero reset and immediate completion HYPOTHESIS, no physical clock effects")

        override fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "scalars" to saveScalarValues(),
            "command" to command,
            "status" to status,
            "active" to active,
            "enable" to enable,
            "disable" to disable,
            "routes" to routes.toList(),
        )

        override fun restoreSnapshot(data: Map<String, Any?>) {
            require(data.keys == SNAPSHOT_KEYS) { "Unexpected snapshot keys for $id" }
            require(snapshotInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = snapshotMap(data["scalars"], "scalars")
            require(scalarsRaw.keys == DESCRIPTOR.settings.map { setting -> setting.key }.toSet()) {
                "Scalar setting keys differ in snapshot for $id"
            }
            val previousValues = values
            val nextCommand: Long
            val nextStatus: Long
            val nextActive: Long
            val nextEnable: Long
            val nextDisable: Long
            val nextRoutes: LongArray
            try {
                restoreScalarValues(scalarsRaw, DESCRIPTOR)
                nextCommand = snapshotLong(data["command"], "command", 0L, 0xffffffffL)
                require(nextCommand == 0L || (nextCommand and 0x7fffffffL) in listOf(0xaa01L, 0xaa02L)) {
                    "Unsupported keyed IO command in snapshot"
                }
                nextStatus = snapshotLong(data["status"], "status", 0L, 0xffffffffL)
                require((nextStatus and 0x30L.inv()) == 0L) { "Unsupported keyed IO status bits in snapshot" }
                nextActive = snapshotLong(data["active"], "active", 0L, 0xffffffffL)
                if (nextCommand == 0L) {
                    require(nextStatus == 0L && nextActive == 0L) {
                        "Reset keyed IO command cannot carry non-zero status or active mask"
                    }
                }
                nextEnable = snapshotLong(data["enable"], "enable", 0L, 0xffffffffL)
                nextDisable = snapshotLong(data["disable"], "disable", 0L, 0xffffffffL)
                val routeRows = snapshotList(data["routes"], "routes")
                require(routeRows.size == routes.size) { "Snapshot routes requires ${routes.size} entries" }
                nextRoutes = LongArray(routes.size) { slot ->
                    val routeWord = snapshotLong(routeRows[slot], "routes[$slot]", 0L, 0xffffffffL)
                    require((routeWord and 0xe0e0e0e0L) == 0L) { "Snapshot routes[$slot] exceeds five-bit lane mask" }
                    routeWord
                }
            } catch (failure: Throwable) {
                values = previousValues
                throw failure
            }
            command = nextCommand
            status = nextStatus
            active = nextActive
            enable = nextEnable
            disable = nextDisable
            nextRoutes.copyInto(routes)
            syncMirror()
        }
    }
}
