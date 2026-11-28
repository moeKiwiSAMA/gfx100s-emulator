package com.github.nyabanban.fuji.emulator.components.bcm43438

import com.github.nyabanban.fuji.emulator.api.HostNetworkInterfaceInfo
import com.github.nyabanban.fuji.emulator.api.WifiNetworkConfig
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pluggable L2/L3 bridge transport for `WifiNetworkMode.BRIDGE` supporting Windows 10/11, Linux,
 * and macOS without requiring native helper DLLs outside standard system drivers:
 *
 * 1. **Windows Npcap / WinPcap Raw L2 (`NPCAP_L2`)** via JDK 25 Panama FFM (`wpcap.dll`).
 * 2. **Windows OpenVPN TAP-Windows6 Raw L2 (`WINDOWS_TAP_L2`)** via JDK 25 Panama FFM (`kernel32.dll`
 *    `CreateFileW("\\\\.\\Global\\{GUID}.tap")` + `TAP_WIN_IOCTL_SET_MEDIA_STATUS = 0x00220018`).
 * 3. **Linux `/dev/net/tun` (`IFF_TAP | IFF_NO_PI`) Raw L2 (`LINUX_TAP_L2`)** via JDK 25 Panama FFM (`libc`).
 * 4. **Linux / macOS `libpcap` Raw L2 (`LINUX_PCAP_L2` / `MACOS_PCAP_L2`)** via JDK 25 Panama FFM.
 * 5. **Cross-Platform L2-over-UDP Tunnel (`UDP_L2`)** when `bridgeInterface` starts with `udp://`.
 * 6. **Universal Zero-Driver Fallback (`NIC_BOUND_FALLBACK`)**: binds outbound user-space NAT TCP/UDP
 *    sockets directly to the chosen host `NetworkInterface`'s IPv4 address when Npcap / TAP / CAP_NET_ADMIN
 *    is unavailable, ensuring `BRIDGE` mode never crashes or fails dead on unprivileged Windows/Linux/macOS hosts.
 */
interface BridgeTransport : AutoCloseable {
    val backendId: String
    val interfaceName: String
    val stateDescription: String
    val isRawL2: Boolean
    val isOperational: Boolean
    val boundLocalIpv4: Inet4Address?
        get() = null
    val lastError: String?
        get() = null

    /** Transmits a raw Ethernet II frame when [isRawL2] is true. */
    fun sendEthernetFrame(frame: ByteArray): Boolean = false

    override fun close() {}
}

/**
 * Non-operational status transport returned when the requested host interface does not exist
 * (`NO_INTERFACE`) or cannot be bound (`PERMISSION_DENIED`) with no IPv4 fallback available.
 */
internal class DisconnectedBridgeTransport(
    override val backendId: String = "NONE",
    override val interfaceName: String,
    override val stateDescription: String,
    override val lastError: String?,
) : BridgeTransport {
    override val isRawL2: Boolean = false
    override val isOperational: Boolean = false
}

/**
 * Universal zero-driver fallback bridge transport: routes guest traffic through the user-space
 * L2/L3/L4 stack while binding every outbound host TCP `Socket` and UDP `DatagramSocket` to the
 * exact IPv4 address of the selected host `NetworkInterface`.
 */
internal class HostNicBoundBridgeTransport(
    override val interfaceName: String,
    val displayName: String,
    override val boundLocalIpv4: Inet4Address,
    override val stateDescription: String,
    override val lastError: String? = null,
) : BridgeTransport {
    override val backendId: String = "NIC_BOUND_FALLBACK"
    override val isRawL2: Boolean = false
    override val isOperational: Boolean = true
}

/**
 * Pure-JVM cross-platform raw Ethernet-II-over-UDP bridge transport (`udp://bindHost:bindPort->peerHost:peerPort`
 * or `udp://peerHost:peerPort`). Works on Windows, Linux, and macOS with zero root/admin privileges.
 */
internal class UdpTunnelBridgeTransport(
    override val interfaceName: String,
    bindSocketAddr: InetSocketAddress,
    private val peerSocketAddr: InetSocketAddress,
    private val guestMacProvider: () -> ByteArray,
    private val rxFrameSink: (ByteArray) -> Unit,
) : BridgeTransport {
    override val backendId: String = "UDP_L2"
    override val isRawL2: Boolean = true
    private val running = AtomicBoolean(true)
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        soTimeout = 250
        bind(bindSocketAddr)
    }
    val localPort: Int = socket.localPort
    override val stateDescription: String = "CONNECTED (udp://127.0.0.1:$localPort->${peerSocketAddr.hostString}:${peerSocketAddr.port})"
    override val isOperational: Boolean get() = running.get() && !socket.isClosed
    @Volatile
    override var lastError: String? = null
        private set

    private val readerThread = Thread.ofVirtual().name("fuji-wifi-udp-bridge-rx").start {
        val buf = ByteArray(2048)
        while (running.get() && !socket.isClosed) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                socket.receive(pkt)
                if (pkt.length >= 14) {
                    val frame = buf.copyOfRange(0, pkt.length)
                    if (!BridgeTransportFactory.isEchoedFromGuest(frame, guestMacProvider())) {
                        rxFrameSink(frame)
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Normal poll cycle
            } catch (e: Exception) {
                if (running.get()) lastError = e.message
            }
        }
    }

    override fun sendEthernetFrame(frame: ByteArray): Boolean {
        if (!running.get() || frame.size < 14) return false
        return try {
            socket.send(DatagramPacket(frame, frame.size, peerSocketAddr))
            true
        } catch (e: Exception) {
            lastError = e.message
            false
        }
    }

    override fun close() {
        if (running.compareAndSet(true, false)) {
            runCatching { socket.close() }
            runCatching { readerThread.join(500) }
        }
    }
}

/**
 * JDK 25 Panama FFM raw L2 bridge over `wpcap.dll` (Windows Npcap / WinPcap) or `libpcap`
 * (Linux `libpcap.so.1` / macOS `libpcap.dylib`).
 */
internal class PcapFfmBridgeTransport private constructor(
    override val backendId: String,
    override val interfaceName: String,
    val pcapDeviceName: String,
    private val libArena: Arena,
    private val pcapHandle: MemorySegment,
    private val sendPacketHandle: java.lang.invoke.MethodHandle,
    private val nextExHandle: java.lang.invoke.MethodHandle,
    private val closeHandle: java.lang.invoke.MethodHandle,
    private val guestMacProvider: () -> ByteArray,
    private val rxFrameSink: (ByteArray) -> Unit,
) : BridgeTransport {
    override val isRawL2: Boolean = true
    private val running = AtomicBoolean(true)
    private val ioLock = Any()
    override val stateDescription: String = "${backendId}_CONNECTED ($pcapDeviceName)"
    override val isOperational: Boolean get() = running.get()
    @Volatile
    override var lastError: String? = null
        private set

    private val readerThread = Thread.ofVirtual().name("fuji-wifi-pcap-rx").start {
        Arena.ofConfined().use { rxArena ->
            val hdrPtrSlot = rxArena.allocate(ValueLayout.ADDRESS)
            val dataPtrSlot = rxArena.allocate(ValueLayout.ADDRESS)
            while (running.get()) {
                try {
                    val rc: Int
                    val frame: ByteArray?
                    synchronized(ioLock) {
                        if (!running.get()) break
                        rc = nextExHandle.invokeExact(pcapHandle, hdrPtrSlot, dataPtrSlot) as Int
                        frame = if (rc == 1) {
                            // struct pcap_pkthdr: ts (16B on 64-bit Unix, 8B on Win64 LLP64!) -> caplen (u32), len (u32)
                            val isWin = backendId == "NPCAP_L2"
                            val caplenOff = if (isWin) 8L else 16L
                            val hdrSeg = hdrPtrSlot.get(ValueLayout.ADDRESS, 0).reinterpret(caplenOff + 8L)
                            val capLen = hdrSeg.get(ValueLayout.JAVA_INT_UNALIGNED, caplenOff)
                            if (capLen in 14..9216) {
                                val dataSeg = dataPtrSlot.get(ValueLayout.ADDRESS, 0).reinterpret(capLen.toLong())
                                dataSeg.toArray(ValueLayout.JAVA_BYTE)
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                    if (frame != null && !BridgeTransportFactory.isEchoedFromGuest(frame, guestMacProvider())) {
                        rxFrameSink(frame)
                    } else if (rc == 0) {
                        Thread.sleep(2)
                    } else if (rc < 0) {
                        if (running.get()) lastError = "pcap_next_ex returned $rc"
                        break
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    if (running.get()) lastError = e.message
                    break
                }
            }
        }
    }

    override fun sendEthernetFrame(frame: ByteArray): Boolean {
        if (!running.get() || frame.size < 14) return false
        return try {
            Arena.ofConfined().use { txArena ->
                val seg = txArena.allocateFrom(ValueLayout.JAVA_BYTE, *frame)
                val rc = synchronized(ioLock) {
                    if (!running.get()) -1 else sendPacketHandle.invokeExact(pcapHandle, seg, frame.size) as Int
                }
                rc == 0
            }
        } catch (e: Throwable) {
            lastError = e.message
            false
        }
    }

    override fun close() {
        if (running.compareAndSet(true, false)) {
            runCatching { readerThread.join(300) }
            synchronized(ioLock) {
                runCatching { closeHandle.invokeExact(pcapHandle) }
                runCatching { libArena.close() }
            }
        }
    }

    companion object {
        data class PcapDeviceEntry(val name: String, val description: String)

        fun tryOpen(
            requestedInterface: String,
            matchedNic: HostNetworkInterfaceInfo?,
            osName: String,
            guestMacProvider: () -> ByteArray,
            rxFrameSink: (ByteArray) -> Unit,
        ): Result<PcapFfmBridgeTransport> = runCatching {
            val lowerOs = osName.lowercase(Locale.ROOT)
            val isWin = lowerOs.contains("win")
            val isMac = lowerOs.contains("mac") || lowerOs.contains("darwin")
            val backendId = when {
                isWin -> "NPCAP_L2"
                isMac -> "MACOS_PCAP_L2"
                else -> "LINUX_PCAP_L2"
            }

            val libArena = Arena.ofShared()
            try {
                val lookup = loadPcapLookup(libArena, isWin, isMac)
                    ?: throw IllegalStateException(
                        if (isWin) "Npcap wpcap.dll not installed" else "libpcap shared library not found",
                    )
                val linker = Linker.nativeLinker()
                val openLive = linker.downcallHandle(
                    lookup.find("pcap_open_live").orElseThrow { IllegalStateException("Missing pcap_open_live") },
                    FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                    ),
                )
                val sendPacket = linker.downcallHandle(
                    lookup.find("pcap_sendpacket").orElseThrow { IllegalStateException("Missing pcap_sendpacket") },
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                    ),
                )
                val nextEx = linker.downcallHandle(
                    lookup.find("pcap_next_ex").orElseThrow { IllegalStateException("Missing pcap_next_ex") },
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                    ),
                )
                val closeFn = linker.downcallHandle(
                    lookup.find("pcap_close").orElseThrow { IllegalStateException("Missing pcap_close") },
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                )

                val devices = enumeratePcapDevices(linker, lookup)
                val pcapDev = selectPcapDeviceName(requestedInterface, matchedNic, devices, isWin)
                    ?: throw IllegalStateException("No matching pcap device for '$requestedInterface'")

                val errBuf = libArena.allocate(256)
                val devCStr = libArena.allocateFrom(pcapDev, StandardCharsets.UTF_8)
                val handle = openLive.invokeExact(devCStr, 65536, 1, 5, errBuf) as MemorySegment
                if (handle == MemorySegment.NULL) {
                    val errMsg = errBuf.reinterpret(256).getString(0, StandardCharsets.UTF_8).trim()
                    throw SecurityException(errMsg.ifEmpty { "pcap_open_live failed for $pcapDev" })
                }

                // Best-effort immediate/non-blocking responsiveness
                lookup.find("pcap_setnonblock").ifPresent { sym ->
                    runCatching {
                        val setNonBlock = linker.downcallHandle(
                            sym,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                        )
                        val ignoredRc = setNonBlock.invokeExact(handle, 1, errBuf) as Int
                        ignoredRc.hashCode()
                    }
                }

                PcapFfmBridgeTransport(
                    backendId = backendId,
                    interfaceName = matchedNic?.name ?: requestedInterface,
                    pcapDeviceName = pcapDev,
                    libArena = libArena,
                    pcapHandle = handle,
                    sendPacketHandle = sendPacket,
                    nextExHandle = nextEx,
                    closeHandle = closeFn,
                    guestMacProvider = guestMacProvider,
                    rxFrameSink = rxFrameSink,
                )
            } catch (t: Throwable) {
                runCatching { libArena.close() }
                throw t
            }
        }

        private fun loadPcapLookup(arena: Arena, isWin: Boolean, isMac: Boolean): SymbolLookup? {
            val candidates: List<String> = when {
                isWin -> {
                    val sysRoot = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
                    listOf(
                        "$sysRoot\\System32\\Npcap\\wpcap.dll",
                        "$sysRoot\\Sysnative\\Npcap\\wpcap.dll",
                        "$sysRoot\\System32\\wpcap.dll",
                        "wpcap.dll",
                    )
                }
                isMac -> listOf("/usr/lib/libpcap.A.dylib", "libpcap.dylib")
                else -> listOf(
                    "libpcap.so.1",
                    "libpcap.so.0.8",
                    "/usr/lib/x86_64-linux-gnu/libpcap.so.0.8",
                    "/usr/lib/aarch64-linux-gnu/libpcap.so.0.8",
                    "/usr/lib64/libpcap.so.1",
                    "libpcap.so",
                )
            }
            for (cand in candidates) {
                val lookup = runCatching {
                    val path = Path.of(cand)
                    if (path.isAbsolute && Files.isRegularFile(path)) {
                        SymbolLookup.libraryLookup(path, arena)
                    } else {
                        SymbolLookup.libraryLookup(cand, arena)
                    }
                }.getOrNull()
                if (lookup != null) return lookup
            }
            return null
        }

        private fun enumeratePcapDevices(linker: Linker, lookup: SymbolLookup): List<PcapDeviceEntry> {
            val findAllSym = lookup.find("pcap_findalldevs").orElse(null) ?: return emptyList()
            val freeAllSym = lookup.find("pcap_freealldevs").orElse(null) ?: return emptyList()
            val findAll = linker.downcallHandle(
                findAllSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            )
            val freeAll = linker.downcallHandle(
                freeAllSym,
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
            )
            return Arena.ofConfined().use { tmp ->
                val listPtr = tmp.allocate(ValueLayout.ADDRESS)
                val errBuf = tmp.allocate(256)
                val rc = findAll.invokeExact(listPtr, errBuf) as Int
                if (rc != 0) return@use emptyList()
                val head = listPtr.get(ValueLayout.ADDRESS, 0)
                if (head == MemorySegment.NULL) return@use emptyList()
                try {
                    val out = mutableListOf<PcapDeviceEntry>()
                    var cur = head
                    var guard = 0
                    while (cur != MemorySegment.NULL && guard++ < 128) {
                        // struct pcap_if: pcap_if *next (0), char *name (8), char *description (16) on 64-bit
                        val seg = cur.reinterpret(32)
                        val next = seg.get(ValueLayout.ADDRESS, 0)
                        val namePtr = seg.get(ValueLayout.ADDRESS, 8)
                        val descPtr = seg.get(ValueLayout.ADDRESS, 16)
                        val name = if (namePtr != MemorySegment.NULL) {
                            namePtr.reinterpret(512).getString(0, StandardCharsets.UTF_8)
                        } else ""
                        val desc = if (descPtr != MemorySegment.NULL) {
                            descPtr.reinterpret(512).getString(0, StandardCharsets.UTF_8)
                        } else ""
                        if (name.isNotBlank()) out += PcapDeviceEntry(name, desc)
                        cur = next
                    }
                    out
                } finally {
                    runCatching { freeAll.invokeExact(head) }
                }
            }
        }

        internal fun selectPcapDeviceName(
            requested: String,
            matchedNic: HostNetworkInterfaceInfo?,
            pcapDevices: List<PcapDeviceEntry>,
            isWin: Boolean,
        ): String? {
            val req = requested.trim()
            if (req.startsWith("\\Device\\NPF_", ignoreCase = true)) return req
            if (req.startsWith("{") && req.endsWith("}") && isWin) return "\\Device\\NPF_$req"
            if (pcapDevices.isEmpty()) {
                return if (!isWin && (matchedNic?.name ?: req).isNotBlank()) (matchedNic?.name ?: req) else null
            }
            val candidates = buildList {
                if (req.isNotBlank() && !req.equals("auto", ignoreCase = true)) add(req)
                matchedNic?.name?.takeIf { it.isNotBlank() }?.let(::add)
                matchedNic?.displayName?.takeIf { it.isNotBlank() }?.let(::add)
            }
            for (cand in candidates) {
                pcapDevices.firstOrNull { it.name.equals(cand, ignoreCase = true) }?.let { return it.name }
                val guidIdx = cand.indexOf('{')
                if (guidIdx >= 0) {
                    val guid = cand.substring(guidIdx)
                    pcapDevices.firstOrNull { it.name.contains(guid, ignoreCase = true) }?.let { return it.name }
                }
                pcapDevices.firstOrNull {
                    it.description.isNotBlank() && (
                        it.description.contains(cand, ignoreCase = true) ||
                            cand.contains(it.description, ignoreCase = true)
                        )
                }?.let { return it.name }
            }
            if (req.isBlank() || req.equals("auto", ignoreCase = true)) {
                return pcapDevices.firstOrNull {
                    !it.name.contains("Loopback", ignoreCase = true) && !it.name.equals("lo", ignoreCase = true) && !it.name.equals("any", ignoreCase = true)
                }?.name
            }
            return null
        }
    }
}

/**
 * Windows OpenVPN `TAP-Windows6` raw L2 Ethernet bridge (`\\.\Global\{GUID}.tap`) implemented via
 * JDK 25 Panama FFM calls to `kernel32.dll` (`CreateFileW`, `DeviceIoControl` with
 * `TAP_WIN_IOCTL_SET_MEDIA_STATUS = 0x00220018`, `ReadFile`, `WriteFile`, `CloseHandle`).
 */
internal class WindowsTapBridgeTransport private constructor(
    override val interfaceName: String,
    val tapDevicePath: String,
    private val arena: Arena,
    private val handle: MemorySegment,
    private val readFileFn: java.lang.invoke.MethodHandle,
    private val writeFileFn: java.lang.invoke.MethodHandle,
    private val closeHandleFn: java.lang.invoke.MethodHandle,
    private val guestMacProvider: () -> ByteArray,
    private val rxFrameSink: (ByteArray) -> Unit,
) : BridgeTransport {
    override val backendId: String = "WINDOWS_TAP_L2"
    override val isRawL2: Boolean = true
    private val running = AtomicBoolean(true)
    override val stateDescription: String = "WINDOWS_TAP_L2_CONNECTED ($tapDevicePath)"
    override val isOperational: Boolean get() = running.get()
    @Volatile
    override var lastError: String? = null
        private set

    private val readerThread = Thread.ofVirtual().name("fuji-wifi-wintap-rx").start {
        Arena.ofConfined().use { rxArena ->
            val buf = rxArena.allocate(2048)
            val bytesReadSeg = rxArena.allocate(ValueLayout.JAVA_INT)
            while (running.get()) {
                try {
                    val ok = readFileFn.invokeExact(handle, buf, 2048, bytesReadSeg, MemorySegment.NULL) as Int
                    if (ok != 0) {
                        val n = bytesReadSeg.get(ValueLayout.JAVA_INT, 0)
                        if (n >= 14) {
                            val frame = buf.asSlice(0, n.toLong()).toArray(ValueLayout.JAVA_BYTE)
                            if (!BridgeTransportFactory.isEchoedFromGuest(frame, guestMacProvider())) {
                                rxFrameSink(frame)
                            }
                        }
                    } else {
                        Thread.sleep(5)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    if (running.get()) lastError = e.message
                    break
                }
            }
        }
    }

    override fun sendEthernetFrame(frame: ByteArray): Boolean {
        if (!running.get() || frame.size < 14) return false
        return try {
            Arena.ofConfined().use { txArena ->
                val seg = txArena.allocateFrom(ValueLayout.JAVA_BYTE, *frame)
                val writtenSeg = txArena.allocate(ValueLayout.JAVA_INT)
                val ok = writeFileFn.invokeExact(handle, seg, frame.size, writtenSeg, MemorySegment.NULL) as Int
                ok != 0 && writtenSeg.get(ValueLayout.JAVA_INT, 0) == frame.size
            }
        } catch (e: Throwable) {
            lastError = e.message
            false
        }
    }

    override fun close() {
        if (running.compareAndSet(true, false)) {
            runCatching { closeHandleFn.invokeExact(handle) }
            runCatching { readerThread.join(300) }
            runCatching { arena.close() }
        }
    }

    companion object {
        private const val GENERIC_READ_WRITE = (0x80000000L or 0x40000000L).toInt()
        private const val OPEN_EXISTING = 3
        private const val FILE_ATTRIBUTE_SYSTEM = 0x00000004
        private const val TAP_WIN_IOCTL_SET_MEDIA_STATUS = 0x00220018

        fun tryOpen(
            requestedInterface: String,
            matchedNic: HostNetworkInterfaceInfo?,
            guestMacProvider: () -> ByteArray,
            rxFrameSink: (ByteArray) -> Unit,
        ): Result<WindowsTapBridgeTransport> = runCatching {
            val guid = resolveTapGuid(requestedInterface, matchedNic)
                ?: throw IllegalStateException("No TAP-Windows6 adapter GUID matched '$requestedInterface'")
            val tapPath = if (guid.startsWith("\\\\.\\")) guid else "\\\\.\\Global\\$guid.tap"

            val arena = Arena.ofShared()
            try {
                val lookup = SymbolLookup.libraryLookup("kernel32", arena)
                val linker = Linker.nativeLinker()
                val createFileW = linker.downcallHandle(
                    lookup.find("CreateFileW").orElseThrow(),
                    FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                    ),
                )
                val deviceIoControl = linker.downcallHandle(
                    lookup.find("DeviceIoControl").orElseThrow(),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                    ),
                )
                val readFile = linker.downcallHandle(
                    lookup.find("ReadFile").orElseThrow(),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                    ),
                )
                val writeFile = linker.downcallHandle(
                    lookup.find("WriteFile").orElseThrow(),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                    ),
                )
                val closeHandle = linker.downcallHandle(
                    lookup.find("CloseHandle").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                )

                val utf16Bytes = (tapPath + "\u0000").toByteArray(StandardCharsets.UTF_16LE)
                val pathSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, *utf16Bytes)
                val handle = createFileW.invokeExact(
                    pathSeg,
                    GENERIC_READ_WRITE,
                    0,
                    MemorySegment.NULL,
                    OPEN_EXISTING,
                    FILE_ATTRIBUTE_SYSTEM,
                    MemorySegment.NULL,
                ) as MemorySegment
                if (handle.address() == -1L || handle == MemorySegment.NULL) {
                    throw SecurityException("CreateFileW failed to open $tapPath")
                }

                // TAP_WIN_IOCTL_SET_MEDIA_STATUS = 1 (Connected)
                val mediaIn = arena.allocateFrom(ValueLayout.JAVA_INT, 1)
                val mediaOut = arena.allocate(ValueLayout.JAVA_INT)
                val bytesRet = arena.allocate(ValueLayout.JAVA_INT)
                val ioctlOk = deviceIoControl.invokeExact(
                    handle,
                    TAP_WIN_IOCTL_SET_MEDIA_STATUS,
                    mediaIn,
                    4,
                    mediaOut,
                    4,
                    bytesRet,
                    MemorySegment.NULL,
                ) as Int
                if (ioctlOk == 0) {
                    val ignoredClose = closeHandle.invokeExact(handle) as Int
                    ignoredClose.hashCode()
                    throw IllegalStateException("TAP_WIN_IOCTL_SET_MEDIA_STATUS failed on $tapPath")
                }

                WindowsTapBridgeTransport(
                    interfaceName = matchedNic?.name ?: requestedInterface.ifBlank { guid },
                    tapDevicePath = tapPath,
                    arena = arena,
                    handle = handle,
                    readFileFn = readFile,
                    writeFileFn = writeFile,
                    closeHandleFn = closeHandle,
                    guestMacProvider = guestMacProvider,
                    rxFrameSink = rxFrameSink,
                )
            } catch (t: Throwable) {
                runCatching { arena.close() }
                throw t
            }
        }

        internal fun resolveTapGuid(requested: String, matchedNic: HostNetworkInterfaceInfo?): String? {
            val req = requested.trim()
            if (req.startsWith("\\\\.\\Global\\", ignoreCase = true) && req.endsWith(".tap", ignoreCase = true)) {
                return req
            }
            val guidRegex = Regex("""\{[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\}""")
            guidRegex.find(req)?.value?.let { return it }
            val disp = matchedNic?.displayName.orEmpty()
            val looksLikeTap = req.contains("tap", ignoreCase = true) || disp.contains("TAP-Windows", ignoreCase = true)
            if (!looksLikeTap && req.isNotEmpty() && !req.equals("auto", ignoreCase = true)) return null

            val regOut = runCatching {
                val p = ProcessBuilder(
                    "reg",
                    "query",
                    "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e972-e325-11ce-bfc1-08002be10318}",
                    "/s",
                ).redirectErrorStream(true).start()
                if (!p.waitFor(2000, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                    null
                } else {
                    p.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                }
            }.getOrNull() ?: return null

            return parseWindowsRegistryTapGuid(regOut)
        }

        internal fun parseWindowsRegistryTapGuid(regOutput: String): String? {
            val blocks = regOutput.split(Regex("""\r?\n\r?\n"""))
            val guidRegex = Regex("""\{[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\}""")
            for (block in blocks) {
                val lower = block.lowercase(Locale.ROOT)
                if (lower.contains("tap0901") || lower.contains("tap-windows")) {
                    for (line in block.lineSequence()) {
                        if (line.contains("NetCfgInstanceId", ignoreCase = true)) {
                            guidRegex.find(line)?.value?.let { return it }
                        }
                    }
                }
            }
            return null
        }
    }
}

/**
 * Linux `/dev/net/tun` (`IFF_TAP | IFF_NO_PI`) raw L2 bridge implemented via JDK 25 Panama FFM (`libc`).
 */
internal class LinuxTapBridgeTransport private constructor(
    override val interfaceName: String,
    private val fd: Int,
    private val readFn: java.lang.invoke.MethodHandle,
    private val writeFn: java.lang.invoke.MethodHandle,
    private val closeFn: java.lang.invoke.MethodHandle,
    private val guestMacProvider: () -> ByteArray,
    private val rxFrameSink: (ByteArray) -> Unit,
) : BridgeTransport {
    override val backendId: String = "LINUX_TAP_L2"
    override val isRawL2: Boolean = true
    private val running = AtomicBoolean(true)
    override val stateDescription: String = "LINUX_TAP_L2_CONNECTED ($interfaceName)"
    override val isOperational: Boolean get() = running.get()
    @Volatile
    override var lastError: String? = null
        private set

    private val readerThread = Thread.ofVirtual().name("fuji-wifi-linuxtap-rx").start {
        Arena.ofConfined().use { rxArena ->
            val buf = rxArena.allocate(2048)
            while (running.get()) {
                try {
                    val n = (readFn.invokeExact(fd, buf, 2048L) as Long).toInt()
                    if (n >= 14) {
                        val frame = buf.asSlice(0, n.toLong()).toArray(ValueLayout.JAVA_BYTE)
                        if (!BridgeTransportFactory.isEchoedFromGuest(frame, guestMacProvider())) {
                            rxFrameSink(frame)
                        }
                    } else if (n < 0) {
                        Thread.sleep(4)
                    } else if (n == 0) {
                        break
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    if (running.get()) lastError = e.message
                    break
                }
            }
        }
    }

    override fun sendEthernetFrame(frame: ByteArray): Boolean {
        if (!running.get() || frame.size < 14) return false
        return try {
            Arena.ofConfined().use { txArena ->
                val seg = txArena.allocateFrom(ValueLayout.JAVA_BYTE, *frame)
                val written = (writeFn.invokeExact(fd, seg, frame.size.toLong()) as Long).toInt()
                written == frame.size
            }
        } catch (e: Throwable) {
            lastError = e.message
            false
        }
    }

    override fun close() {
        if (running.compareAndSet(true, false)) {
            runCatching {
                val ignoredClose = closeFn.invokeExact(fd) as Int
                ignoredClose.hashCode()
            }
            runCatching { readerThread.join(300) }
        }
    }

    companion object {
        private const val O_RDWR = 2
        private const val O_NONBLOCK = 0x0800
        private const val TUNSETIFF = 0x400454caL
        private const val IFF_TAP = 0x0002
        private const val IFF_NO_PI = 0x1000

        fun tryOpen(
            tapName: String,
            guestMacProvider: () -> ByteArray,
            rxFrameSink: (ByteArray) -> Unit,
        ): Result<LinuxTapBridgeTransport> = runCatching {
            val tunDev = Path.of("/dev/net/tun")
            if (!Files.exists(tunDev)) {
                throw NoSuchElementException("/dev/net/tun not present")
            }
            val linker = Linker.nativeLinker()
            val lookup = linker.defaultLookup()
            val openFn = linker.downcallHandle(
                lookup.find("open").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            )
            // Variadic ioctl(int fd, unsigned long request, void *arg)
            val ioctlFn = linker.downcallHandle(
                lookup.find("ioctl").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
                Linker.Option.firstVariadicArg(2),
            )
            val readFn = linker.downcallHandle(
                lookup.find("read").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            )
            val writeFn = linker.downcallHandle(
                lookup.find("write").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            )
            val closeFn = linker.downcallHandle(
                lookup.find("close").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            )

            Arena.ofConfined().use { tmp ->
                val pathSeg = tmp.allocateFrom("/dev/net/tun", StandardCharsets.UTF_8)
                val fd = openFn.invokeExact(pathSeg, O_RDWR or O_NONBLOCK) as Int
                if (fd < 0) {
                    throw SecurityException("PERMISSION_DENIED opening /dev/net/tun")
                }
                val ifr = tmp.allocate(40)
                val cleanName = tapName.trim().take(15)
                if (cleanName.isNotEmpty() && !cleanName.equals("auto", ignoreCase = true)) {
                    val nameBytes = cleanName.toByteArray(StandardCharsets.US_ASCII)
                    MemorySegment.copy(nameBytes, 0, ifr, ValueLayout.JAVA_BYTE, 0, nameBytes.size)
                }
                ifr.set(ValueLayout.JAVA_SHORT_UNALIGNED, 16, (IFF_TAP or IFF_NO_PI).toShort())
                val rc = ioctlFn.invokeExact(fd, TUNSETIFF, ifr) as Int
                if (rc < 0) {
                    val ignoredClose = closeFn.invokeExact(fd) as Int
                    ignoredClose.hashCode()
                    throw SecurityException("PERMISSION_DENIED on TUNSETIFF($cleanName)")
                }
                val actualName = ifr.asSlice(0, 16).getString(0, StandardCharsets.US_ASCII).trimEnd('\u0000')
                LinuxTapBridgeTransport(
                    interfaceName = actualName.ifEmpty { cleanName.ifEmpty { "tap0" } },
                    fd = fd,
                    readFn = readFn,
                    writeFn = writeFn,
                    closeFn = closeFn,
                    guestMacProvider = guestMacProvider,
                    rxFrameSink = rxFrameSink,
                )
            }
        }
    }
}

/**
 * OS-aware factory selecting the optimal raw L2 bridge backend (`Npcap`, `TAP-Windows6`,
 * `/dev/net/tun`, `libpcap`, `UDP_L2`, or custom injected [BridgeTransport]) and seamlessly
 * falling back to [HostNicBoundBridgeTransport] whenever raw L2 drivers/privileges are absent.
 */
object BridgeTransportFactory {
    internal fun isEchoedFromGuest(frame: ByteArray, guestMac: ByteArray): Boolean {
        if (frame.size < 14 || guestMac.size < 6) return false
        for (i in 0 until 6) {
            if (frame[6 + i] != guestMac[i]) return false
        }
        return true
    }

    fun resolveHostNic(
        requested: String,
        candidates: List<HostNetworkInterfaceInfo> = HostNetworkInterfaceInfo.discoverAll(includeDown = true, includeLoopback = false),
    ): HostNetworkInterfaceInfo? {
        val req = requested.trim()
        if (req.isEmpty() || req.equals("auto", ignoreCase = true)) {
            return candidates.firstOrNull { it.up && !it.loopback && it.ipv4Addresses.isNotEmpty() }
                ?: candidates.firstOrNull { it.up && !it.loopback }
        }
        return candidates.firstOrNull { it.name.equals(req, ignoreCase = true) }
            ?: candidates.firstOrNull { it.displayName.equals(req, ignoreCase = true) }
            ?: candidates.firstOrNull { req in it.ipv4Addresses }
            ?: candidates.firstOrNull { it.macAddress.isNotEmpty() && it.macAddress.equals(req, ignoreCase = true) }
            ?: candidates.firstOrNull {
                it.displayName.contains(req, ignoreCase = true) || it.name.contains(req, ignoreCase = true)
            }
    }

    fun resolveInet4ForNic(nicInfo: HostNetworkInterfaceInfo?): Inet4Address? {
        if (nicInfo == null) return null
        val ipText = nicInfo.ipv4Addresses.firstOrNull() ?: return null
        return runCatching { InetAddress.getByName(ipText) as? Inet4Address }.getOrNull()
    }

    fun createBridgeTransport(
        config: WifiNetworkConfig,
        guestMacProvider: () -> ByteArray,
        rxFrameSink: (ByteArray) -> Unit,
        osName: String = System.getProperty("os.name", ""),
        hostInterfaces: List<HostNetworkInterfaceInfo> = HostNetworkInterfaceInfo.discoverAll(includeDown = true, includeLoopback = false),
        customTransportFactory: ((WifiNetworkConfig, (ByteArray) -> Unit) -> BridgeTransport?)? = null,
    ): BridgeTransport {
        customTransportFactory?.invoke(config, rxFrameSink)?.let { return it }

        val req = config.bridgeInterface.trim()
        if (req.startsWith("udp://", ignoreCase = true)) {
            return createUdpBridge(req, guestMacProvider, rxFrameSink)
        }

        val lowerOs = osName.lowercase(Locale.ROOT)
        val isWin = lowerOs.contains("win")
        val isLinux = lowerOs.contains("linux")
        val matchedNic = resolveHostNic(req, hostInterfaces)
        val rawErrors = mutableListOf<String>()

        if (isWin) {
            val prefersTap = req.contains("tap", ignoreCase = true) ||
                matchedNic?.displayName?.contains("TAP-Windows", ignoreCase = true) == true
            if (prefersTap) {
                WindowsTapBridgeTransport.tryOpen(req, matchedNic, guestMacProvider, rxFrameSink)
                    .onSuccess { return it }
                    .onFailure { rawErrors += (it.message ?: it.javaClass.simpleName) }
            }
            PcapFfmBridgeTransport.tryOpen(req, matchedNic, osName, guestMacProvider, rxFrameSink)
                .onSuccess { return it }
                .onFailure { rawErrors += (it.message ?: it.javaClass.simpleName) }
            if (!prefersTap) {
                WindowsTapBridgeTransport.tryOpen(req, matchedNic, guestMacProvider, rxFrameSink)
                    .onSuccess { return it }
                    .onFailure { rawErrors += (it.message ?: it.javaClass.simpleName) }
            }
        } else if (isLinux) {
            val looksLikeTap = req.startsWith("tap", ignoreCase = true) || matchedNic?.name?.startsWith("tap") == true
            if (looksLikeTap) {
                LinuxTapBridgeTransport.tryOpen(matchedNic?.name ?: req, guestMacProvider, rxFrameSink)
                    .onSuccess { return it }
                    .onFailure { rawErrors += (it.message ?: it.javaClass.simpleName) }
            }
            PcapFfmBridgeTransport.tryOpen(req, matchedNic, osName, guestMacProvider, rxFrameSink)
                .onSuccess { return it }
                .onFailure { rawErrors += (it.message ?: it.javaClass.simpleName) }
        } else {
            // macOS / BSD via libpcap
            PcapFfmBridgeTransport.tryOpen(req, matchedNic, osName, guestMacProvider, rxFrameSink)
                .onSuccess { return it }
                .onFailure { rawErrors += (it.message ?: it.javaClass.simpleName) }
        }

        // Universal Zero-Driver Fallback: bind outbound user-space NAT sockets to the chosen host NIC's IPv4!
        val nicIpv4 = resolveInet4ForNic(matchedNic)
        if (matchedNic != null && nicIpv4 != null) {
            val hint = if (isWin) {
                "NIC_BOUND_FALLBACK (Install Npcap for raw L2)"
            } else {
                val perm = rawErrors.any {
                    it.contains("PERMISSION", ignoreCase = true) ||
                        it.contains("Operation not permitted", ignoreCase = true) ||
                        it.contains("you don't have permission", ignoreCase = true)
                }
                if (perm) {
                    "NIC_BOUND_FALLBACK (PERMISSION_DENIED for raw L2; bound to ${matchedNic.name} ${nicIpv4.hostAddress})"
                } else {
                    "NIC_BOUND_FALLBACK (Bound to ${matchedNic.name} ${nicIpv4.hostAddress})"
                }
            }
            return HostNicBoundBridgeTransport(
                interfaceName = matchedNic.name,
                displayName = matchedNic.displayName,
                boundLocalIpv4 = nicIpv4,
                stateDescription = hint,
                lastError = null,
            )
        }

        if (matchedNic == null && req.isNotBlank() && !req.equals("auto", ignoreCase = true)) {
            return DisconnectedBridgeTransport(
                backendId = "NONE",
                interfaceName = req,
                stateDescription = "NO_INTERFACE",
                lastError = "Host network interface '$req' not found",
            )
        }

        val permErr = rawErrors.firstOrNull {
            it.contains("PERMISSION", ignoreCase = true) ||
                it.contains("Access is denied", ignoreCase = true) ||
                it.contains("Operation not permitted", ignoreCase = true)
        }
        return DisconnectedBridgeTransport(
            backendId = "NONE",
            interfaceName = matchedNic?.name ?: req,
            stateDescription = if (permErr != null) "PERMISSION_DENIED" else "NO_INTERFACE",
            lastError = permErr ?: rawErrors.firstOrNull() ?: "No active IPv4 host network interface available",
        )
    }

    private fun createUdpBridge(
        spec: String,
        guestMacProvider: () -> ByteArray,
        rxFrameSink: (ByteArray) -> Unit,
    ): BridgeTransport = try {
        val body = spec.removePrefix("udp://").removePrefix("UDP://")
        val (bindAddr, peerAddr) = if ("->" in body) {
            val (lhs, rhs) = body.split("->", limit = 2)
            parseHostPort(lhs) to parseHostPort(rhs)
        } else {
            InetSocketAddress(InetAddress.getLoopbackAddress(), 0) to parseHostPort(body)
        }
        UdpTunnelBridgeTransport(spec, bindAddr, peerAddr, guestMacProvider, rxFrameSink)
    } catch (e: Exception) {
        DisconnectedBridgeTransport("UDP_L2", spec, "INVALID_ENDPOINT", e.message)
    }

    private fun parseHostPort(raw: String): InetSocketAddress {
        val idx = raw.lastIndexOf(':')
        require(idx > 0) { "Expected host:port in '$raw'" }
        val host = raw.substring(0, idx).trim()
        val port = raw.substring(idx + 1).trim().toInt()
        return InetSocketAddress(InetAddress.getByName(host), port)
    }
}
