package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.CoupledEdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.EnabledEdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.runtime.ComponentHost
import com.github.nyabanban.fuji.emulator.runtime.DiscoveryBus
import com.github.nyabanban.fuji.emulator.runtime.ImageIrq106Status
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

internal val IMAGE_EDGE_LINES = setOf(100, 102, 106, 107, 108, 109, 133)

internal fun checkImageIrq106Snapshot(discovery: Map<*, *>?, gic: Map<String, Any?>?) {
    if (discovery == null || ImageIrq106Status.KEY !in discovery) return
    val phase = ImageIrq106Status.parseState(discovery[ImageIrq106Status.KEY]).phase
    if (phase == ImageIrq106Status.IDLE || phase == ImageIrq106Status.FINISHED) return
    require(gic?.get("mode") == "cluster")
    val shared = gic["shared"] as Map<*, *>
    val bit = 106 - 32
    fun flag(field: String): Boolean {
        val bits = Base64.getDecoder().decode(shared[field] as String)
        require(bits.size == 60)
        return bits[bit / 8].toInt() and (1 shl (bit % 8)) != 0
    }
    val active = flag("active")
    val pending = flag("pending")
    val targets = Base64.getDecoder().decode(shared["targets"] as String)
    require(targets.size == 480 && targets[bit].toInt() == 2) { "Saved IRQ106 target differs from core 1" }
    val cores = gic["cores"] as List<*>
    require(cores.size == 4 && cores.withIndex().all { (index, raw) ->
        ((raw as Map<*, *>)["activeStack"] as List<*>).contains(106) == (active && index == 1)
    })
    require(flag("edge") && !flag("levels") && (active xor pending) &&
        (phase == ImageIrq106Status.LATCHED || active)) { "IRQ106 status and saved GIC phase disagree" }
}

private fun DmaMemory.word(address: Long): Int {
    val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
    read(address, buffer)
    return buffer.getInt(0)
}

private fun DmaMemory.inRequest(): Boolean {
    val state = ByteBuffer.allocate(1)
    read(0x02d92ec8, state)
    return state.get(0).toInt() == 1 && word(0x02d92ecc) == 0x024b8c38 &&
        word(0x02d932ac) == 0x02364da0 && word(0x01a3c19c) == 0x02364da0
}

internal fun imageIrq106LineActive(host: ComponentHost): Boolean {
    val distributor = checkNotNull(host.ports.find("soc-gic.distributor", MmioDevice::class.java))
    return distributor.read(0x30c, 4) and 0x400L != 0L
}

internal fun imageIrq106InFlight(host: ComponentHost): Boolean {
    val memory = checkNotNull(host.ports.find("cpu.memory", DmaMemory::class.java))
    return imageIrq106LineActive(host) && memory.inRequest()
}

internal fun deliverImageInterrupt(
    host: ComponentHost,
    bus: DiscoveryBus?,
    line: Int,
    withStatus: Boolean,
    statusMask: Long = 1L,
) {
    host.owner.requireOwner()
    val edge = line in IMAGE_EDGE_LINES
    require(line == 296 || (edge && !withStatus))
    require(line == 106 || statusMask == 1L) { "IRQ106 status mask is only valid for line 106" }
    val port = checkNotNull(host.ports.find("soc-gic.lines", InterruptSink::class.java)) as EnabledEdgeInterruptSink
    require(port.lineEnabled(line))
    val state = port.edgeState(line)
    require(!state.high && !state.pending && state.edge == edge)
    require(host.components().none { loaded -> loaded.component.interruptRoutes().any { line in it.lines } })
    if (line == 106) {
        val distributor = checkNotNull(host.ports.find("soc-gic.distributor", MmioDevice::class.java))
        require(distributor.read(0x30c, 4) and 0x400L == 0L) { "IRQ106 is active" }
        val memory = checkNotNull(host.ports.find("cpu.memory", DmaMemory::class.java))
        require(memory.word(0x01871ad0) == 0x011b7f14) { "IRQ106 image handler is not registered" }
        if (bus?.hasImageIrq106Status == true) {
            if (!bus.isImageIrq106Repeatable) {
                require(memory.inRequest()) { "IRQ106 image request is not registered and waiting" }
            }
            val coupled = requireNotNull(port as? CoupledEdgeInterruptSink) { "GIC lacks coupled edge support" }
            bus.pulseImageIrq106(coupled, statusMask)
            return
        }
        require(statusMask == 1L) { "Custom IRQ106 status mask requires imageIrq106Status" }
    }
    if (line == 296) checkNotNull(bus).startImageEvent(withStatus) { port.setLine(line, false) }
    port.setLine(line, true)
    if (edge) port.setLine(line, false)
}
