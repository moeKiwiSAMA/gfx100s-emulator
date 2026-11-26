package com.github.nyabanban.fuji.emulator.components.dram

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class DramSnapshotFactoryTest {
 @TempDir lateinit var temporary: Path
 private val f = DramFixture()
 private val cf = CommandFixture()
 private class Registry : Ports {
  val values = linkedMapOf<String, Any>()
  override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
   require(type.isInstance(port) && name !in values); values[name] = port
  }
  override fun <T : Any> find(name: String, type: Class<T>): T? = values[name]?.let { type.cast(it) }
  override fun removeOwner(owner: String) { values.clear() }
 }
 private inner class Context(val document: MutableMap<String, Any?>) : ComponentContext {
  override val directory = temporary
  override val ports = Registry()
  var tick = 0L; var loads = 0; var published = 0
  val files = (0..3).associate { bank ->
   "command-$bank.yaml" to mutableMapOf<String, Any?>(
    "schema" to 1, "bank" to bank, "profile" to DramFactory.SNAPSHOT_PROFILE,
    "firmwareSha256" to DramFactory.FIRMWARE,
    "modes" to (0..4).associate { mode -> mode.toString() to cf.tables(bank)[mode].map { (o, v) -> mapOf("offset" to o, "value" to v) } },
   )
  }.toMutableMap()
  override val clock = EmulationClock { tick }
  override val events = object : EventChannel {
   override fun publish(event: ComponentEvent) { published++ }
   override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
  }
  override fun dataPath(relative: String) = directory.resolve(relative).normalize().also { require(relative.isNotBlank() && it.startsWith(directory)) }
  override fun readData(relative: String): Map<String, Any?> { dataPath(relative); loads++; return if (relative == "input.yaml") document else files.getValue(relative) }
 }
 private fun doc(): MutableMap<String, Any?> = linkedMapOf(
  "schema" to 1, "profile" to DramFactory.SNAPSHOT_PROFILE, "firmwareSha256" to DramFactory.FIRMWARE,
  "programmingWords" to f.programming.map { (o, v) -> mapOf("offset" to o, "value" to v) },
  "configurationWords" to f.config.map { (o, v) -> mapOf("offset" to o, "value" to v) },
  "commandTableFiles" to (0..3).associate { it.toString() to "command-$it.yaml" },
  "banks" to mutableMapOf("1" to linkedMapOf<String, Any?>(
   "responseTicks" to listOf(80, 80, 80, 80, 80), "initialController7044" to 0x80, "initialController7364" to 0x40,
   "responses" to (0..4).associate { it.toString() to f.reply(it).toList() },
   "commandPlans" to listOf(mapOf("mode" to 4, "responseTicks" to 80, "responseWord" to 0, "counterWord" to null)),
   "snapshotResponse" to mutableMapOf<String, Any?>("responseTicks" to 80, "pendingStatusWord" to 0, "responseWord" to 1),
  )),
 )
 @Suppress("UNCHECKED_CAST") private fun bank(d: Map<String, Any?>) = ((d.getValue("banks") as Map<String, Any?>).getValue("1") as MutableMap<String, Any?>)
 @Suppress("UNCHECKED_CAST") private fun input(d: Map<String, Any?>) = bank(d).getValue("snapshotResponse") as MutableMap<String, Any?>

 @Test fun malformedSnapshotInputsAndUnqualifiedBankZeroRefuseBeforePublishingAnyPort() {
  for (i in 0..9) {
   val d = doc()
   when (i) {
    0 -> bank(d).remove("snapshotResponse")
    1 -> bank(d)["snapshotResponse"] = null
    2 -> input(d).remove("responseWord")
    3 -> input(d)["extra"] = 1
    4 -> input(d)["responseTicks"] = 0
    5 -> input(d)["pendingStatusWord"] = 1
    6 -> input(d)["responseWord"] = 0x100000000L
    7 -> input(d)["responseWord"] = "1"
    8 -> d["banks"] = mapOf("0" to bank(d))
    9 -> d["firmwareSha256"] = "0".repeat(64)
   }
   val c = Context(d)
   assertFails { DramFactory().create(c, mapOf("profile" to DramFactory.SNAPSHOT_PROFILE, "inputFile" to "input.yaml")) }
   assertTrue(c.ports.values.isEmpty())
  }
 }

 @Test fun oldProfilesNeverExposeSnapshotWordsOrTheExternalWindow() {
  for (profile in listOf(DramFactory.PROFILE, DramFactory.COMMAND_PROFILE)) {
   val d = doc(); d["profile"] = profile; bank(d).remove("snapshotResponse")
   if (profile == DramFactory.PROFILE) { d.remove("commandTableFiles"); bank(d).remove("commandPlans") }
   val c = Context(d); c.files.values.forEach { it["profile"] = profile }
   val h = DramFactory().create(c, mapOf("profile" to profile, "inputFile" to "input.yaml"))
   assertEquals(listOf("soc-dram.controller-1", "soc-dram.phy-1"), h.additionalMmioPorts())
   assertNull(c.ports.find("soc-dram.snapshot-ack-1", MmioDevice::class.java))
   val ctrl = c.ports.find("soc-dram.controller-1", MmioDevice::class.java)!!
   val before = h.state()
   assertFails { ctrl.write(0x7114, 4, 0) }
   assertFails { ctrl.read(0x7090, 4) }
   assertEquals(before, h.state())
   h.close()
  }
 }
}
