package com.github.nyabanban.fuji.emulator.components.spiinput

import com.github.nyabanban.fuji.emulator.api.*

/** Pinned FixedPin law, explicit raw MISO input. No peripheral identity/protocol/CS/physical claim. */
class SpiInputFactory : ComponentFactory {
    override val descriptor=ComponentDescriptor("spi-input","0.0.1",settings=listOf(
        Setting("profile",ValueKind.TEXT,"disabled",choices=listOf("disabled","spi-fixed-pins-experimental-v1"),live=false),Setting("inputFile",ValueKind.TEXT,"",live=false)))
    override fun create(context: ComponentContext,config: Map<String,Any>): HardwareComponent=Pins(context,config)
    private inner class Pins(context: ComponentContext,config: Map<String,Any>) : ScalarComponent("spi-input",context,config),StatefulComponent {
        private val pins=ArrayList<Map<String,Any>>()
        init {
            val enabled=config.getValue("profile")!="disabled";val file=config.getValue("inputFile") as String;require(enabled==file.isNotBlank())
            if(enabled) {
                val data=context.readData(file);require(data.keys==setOf("pins"));val rows=data["pins"];require(rows is List<*> && rows.size in 1..6);val seen=HashSet<String>()
                rows.forEach { raw->
                    require(raw is Map<*,*> && raw.keys==setOf("id","miso"));val name=raw["id"];val high=raw["miso"]
                    require(name is String && name.matches(Regex("[a-z][a-z0-9-]{0,31}")) && seen.add(name) && high is Boolean)
                    val peer=object: SpiPeer {override fun exchange(bits: Int,tx: Int,tick: Long): Int {
                        require(bits in 4..16 && tx in 0..((1 shl bits)-1) && tick>=0)
                        return if(high) (1 shl bits)-1 else 0
                    }}
                    context.ports.register(id,"$id.$name",SpiPeer::class.java,peer);pins.add(mapOf("id" to name,"miso" to high))
                }
            }
        }
        override fun state(): Map<String,Any?> = super.state()+mapOf("pins" to pins.toList(),"basis" to "Frozen explicitly selected raw fixed MISO. No endpoint protocol, CS gating, physical pin measurement or original data reply claim")
        override fun saveSnapshot(): Map<String,Any?> = linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "scalarValues" to saveScalarValues(),
            "pins" to pins.map { pinEntry -> LinkedHashMap(pinEntry) },
        )
        override fun restoreSnapshot(data: Map<String,Any?>) {
            require(data.keys == setOf("schema", "componentId", "scalarValues", "pins")) { "Unexpected spi-input snapshot keys" }
            val schemaValue = data["schema"]
            require(schemaValue is Byte || schemaValue is Short || schemaValue is Int || schemaValue is Long) { "Snapshot schema must be integral" }
            require((schemaValue as? Number)?.toLong() == snapshotSchemaVersion.toLong()) { "Unsupported spi-input snapshot schema" }
            require(data["componentId"] == id) { "Mismatched spi-input componentId" }
            val rawScalars = data["scalarValues"] as? Map<*, *> ?: throw IllegalArgumentException("Missing spi-input scalarValues")
            require(rawScalars.keys.all { key -> key is String && descriptor.settings.any { setting -> setting.key == key } }) {
                "Unknown scalar setting key in snapshot for $id"
            }
            require(descriptor.settings.all { setting -> rawScalars.containsKey(setting.key) }) {
                "Missing scalar setting key in snapshot for $id"
            }
            for (setting in descriptor.settings) {
                val rawSetting = rawScalars[setting.key] ?: throw IllegalArgumentException("Null setting ${setting.key} in $id")
                val validated = setting.validate(rawSetting)
                require(values[setting.key] == validated) { "Restart-only setting ${setting.key} differs in snapshot for $id" }
            }
            val rawPins = data["pins"] as? List<*> ?: throw IllegalArgumentException("Invalid pins list in spi-input snapshot")
            val enabled = values["profile"] != "disabled"
            if (enabled) require(rawPins.size in 1..6) { "Enabled spi-input snapshot requires 1..6 pins" }
            else require(rawPins.isEmpty()) { "Disabled spi-input snapshot must not contain pins" }
            val seenIds = HashSet<String>()
            val parsedPins = rawPins.map { rawPin ->
                val pinMap = rawPin as? Map<*, *> ?: throw IllegalArgumentException("Invalid pin entry in spi-input snapshot")
                require(pinMap.keys == setOf("id", "miso")) { "Unexpected pin keys in spi-input snapshot" }
                val pinId = pinMap["id"] as? String ?: throw IllegalArgumentException("Invalid pin id in spi-input snapshot")
                val misoHigh = pinMap["miso"] as? Boolean ?: throw IllegalArgumentException("Invalid pin miso in spi-input snapshot")
                require(pinId.matches(Regex("[a-z][a-z0-9-]{0,31}")) && seenIds.add(pinId)) { "Invalid or duplicate pin id in spi-input snapshot" }
                mapOf("id" to pinId, "miso" to misoHigh)
            }
            require(parsedPins == pins) { "Frozen spi-input pins differ from snapshot" }
            restoreScalarValues(rawScalars, descriptor)
        }
    }
}
