package com.github.nyabanban.fuji.emulator.api

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/** Sparse-aware SHA-256 and metadata-keyed digest cache for large (>64 MiB) SD media. */
object SparseFileDigest {
    private const val CHUNK_BYTES = 4 * 1024 * 1024
    private const val SEEK_DATA = 3
    private const val SEEK_HOLE = 4
    private const val O_RDONLY = 0
    private const val ENXIO = 6
    private const val MAX_CACHE_ENTRIES = 64

    data class FileIdentity(
        val fileKey: Any,
        val size: Long,
        val mtimeNanos: Long,
        val ctimeNanos: Long,
    )

    private val cache = ConcurrentHashMap<FileIdentity, String>()

    private val zeroBuffer: ByteBuffer = ByteBuffer.allocateDirect(CHUNK_BYTES).asReadOnlyBuffer()
    private val zeroSegment: MemorySegment = MemorySegment.ofBuffer(zeroBuffer)

    private object NativeSha256 {
        val available: Boolean
        private val evpNew: MethodHandle?
        private val evpSha256: MethodHandle?
        private val evpInit: MethodHandle?
        private val evpUpdate: MethodHandle?
        private val evpFinal: MethodHandle?
        private val evpFree: MethodHandle?
        private val sha256Md: MemorySegment?

        init {
            var ok = false
            var mNew: MethodHandle? = null
            var mSha: MethodHandle? = null
            var mInit: MethodHandle? = null
            var mUpd: MethodHandle? = null
            var mFin: MethodHandle? = null
            var mFree: MethodHandle? = null
            var md: MemorySegment? = null
            if (System.getProperty("os.name").lowercase().contains("linux")) {
                try {
                    val linker = Linker.nativeLinker()
                    val lookup = listOf(
                        "/lib/x86_64-linux-gnu/libcrypto.so.3",
                        "/usr/lib/x86_64-linux-gnu/libcrypto.so.3",
                        "/usr/lib64/libcrypto.so.3",
                    ).firstOrNull { Files.isRegularFile(Path.of(it)) }?.let {
                        java.lang.foreign.SymbolLookup.libraryLookup(Path.of(it), Arena.global())
                    }
                    if (lookup != null) {
                        val sNew = lookup.find("EVP_MD_CTX_new").orElse(null)
                        val sSha = lookup.find("EVP_sha256").orElse(null)
                        val sInit = lookup.find("EVP_DigestInit_ex").orElse(null)
                        val sUpd = lookup.find("EVP_DigestUpdate").orElse(null)
                        val sFin = lookup.find("EVP_DigestFinal_ex").orElse(null)
                        val sFree = lookup.find("EVP_MD_CTX_free").orElse(null)
                        if (sNew != null && sSha != null && sInit != null && sUpd != null && sFin != null && sFree != null) {
                            mNew = linker.downcallHandle(sNew, FunctionDescriptor.of(ValueLayout.ADDRESS))
                            mSha = linker.downcallHandle(sSha, FunctionDescriptor.of(ValueLayout.ADDRESS))
                            mInit = linker.downcallHandle(sInit, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                            mUpd = linker.downcallHandle(sUpd, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
                            mFin = linker.downcallHandle(sFin, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                            mFree = linker.downcallHandle(sFree, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
                            md = mSha.invokeExact() as MemorySegment
                            ok = md.address() != 0L
                        }
                    }
                } catch (_: Throwable) {
                    ok = false
                }
            }
            available = ok
            evpNew = mNew
            evpSha256 = mSha
            evpInit = mInit
            evpUpdate = mUpd
            evpFinal = mFin
            evpFree = mFree
            sha256Md = md
        }

        class Session : AutoCloseable {
            private val ctx: MemorySegment = evpNew!!.invokeExact() as MemorySegment
            private var closed = false
            init {
                check(ctx.address() != 0L) { "EVP_MD_CTX_new failed" }
                val ok = evpInit!!.invokeExact(ctx, sha256Md!!, MemorySegment.NULL) as Int
                check(ok == 1) { "EVP_DigestInit_ex failed" }
            }
            fun update(segment: MemorySegment, len: Long) {
                if (len <= 0L) return
                val ok = evpUpdate!!.invokeExact(ctx, segment, len) as Int
                check(ok == 1) { "EVP_DigestUpdate failed" }
            }
            fun updateZeros(byteCount: Long) {
                var rem = byteCount
                while (rem > 0L) {
                    val chunk = minOf(CHUNK_BYTES.toLong(), rem)
                    update(zeroSegment, chunk)
                    rem -= chunk
                }
            }
            fun finishHex(): String {
                Arena.ofConfined().use { outArena ->
                    val outBuf = outArena.allocate(32L, 8L)
                    val outLen = outArena.allocate(4L, 4L)
                    val ok = evpFinal!!.invokeExact(ctx, outBuf, outLen) as Int
                    check(ok == 1) { "EVP_DigestFinal_ex failed" }
                    val bytes = outBuf.toArray(ValueLayout.JAVA_BYTE)
                    return HexFormat.of().formatHex(bytes)
                }
            }
            override fun close() {
                if (!closed) {
                    closed = true
                    evpFree!!.invokeExact(ctx)
                }
            }
        }
    }

    private object LinuxSeek {
        val available: Boolean
        private val openHandle: MethodHandle?
        private val lseekHandle: MethodHandle?
        private val closeHandle: MethodHandle?
        private val stateLayout: MemoryLayout?
        private val errnoHandle: VarHandle?

        init {
            val isLinux = System.getProperty("os.name").lowercase().contains("linux")
            var ok = false
            var o: MethodHandle? = null
            var l: MethodHandle? = null
            var c: MethodHandle? = null
            var sl: MemoryLayout? = null
            var eh: VarHandle? = null
            if (isLinux) {
                try {
                    val linker = Linker.nativeLinker()
                    val lookup = linker.defaultLookup()
                    val captureOpt = Linker.Option.captureCallState("errno")
                    sl = Linker.Option.captureStateLayout()
                    eh = sl.varHandle(MemoryLayout.PathElement.groupElement("errno"))
                    val openSym = lookup.find("open").orElse(null)
                    val lseekSym = lookup.find("lseek").orElse(null)
                    val closeSym = lookup.find("close").orElse(null)
                    if (openSym != null && lseekSym != null && closeSym != null) {
                        o = linker.downcallHandle(openSym, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), captureOpt)
                        l = linker.downcallHandle(lseekSym, FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT), captureOpt)
                        c = linker.downcallHandle(closeSym, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
                        ok = true
                    }
                } catch (_: Throwable) {
                    ok = false
                }
            }
            available = ok
            openHandle = o
            lseekHandle = l
            closeHandle = c
            stateLayout = sl
            errnoHandle = eh
        }

        fun tryComputeSparseSha256(path: Path, channel: FileChannel, size: Long): String? {
            if (!available || size <= 0L) return null
            val openFn = openHandle ?: return null
            val lseekFn = lseekHandle ?: return null
            val closeFn = closeHandle ?: return null
            val layout = stateLayout ?: return null
            val errnoVar = errnoHandle ?: return null

            Arena.ofConfined().use { arena ->
                val callState = arena.allocate(layout)
                val pathSegment = arena.allocateFrom(path.toAbsolutePath().normalize().toString())
                val fd = openFn.invokeExact(callState, pathSegment, O_RDONLY) as Int
                if (fd < 0) return null
                try {
                    val nativeSession = if (NativeSha256.available) NativeSha256.Session() else null
                    val digest = if (nativeSession == null) MessageDigest.getInstance("SHA-256") else null
                    try {
                        val readBuffer = ByteBuffer.allocateDirect(CHUNK_BYTES)
                        val readSegment = MemorySegment.ofBuffer(readBuffer)
                        var pos = 0L
                        while (pos < size) {
                            val dataStart = lseekFn.invokeExact(callState, fd, pos, SEEK_DATA) as Long
                            if (dataStart < 0L) {
                                val err = errnoVar.get(callState, 0L) as Int
                                if (err == ENXIO) {
                                    if (nativeSession != null) nativeSession.updateZeros(size - pos)
                                    else hashZeros(digest!!, size - pos)
                                    pos = size
                                    break
                                }
                                return null
                            }
                            if (dataStart > pos) {
                                val holeEnd = minOf(dataStart, size)
                                if (nativeSession != null) nativeSession.updateZeros(holeEnd - pos)
                                else hashZeros(digest!!, holeEnd - pos)
                                pos = holeEnd
                                if (pos >= size) break
                            }
                            val holeStart = lseekFn.invokeExact(callState, fd, pos, SEEK_HOLE) as Long
                            if (holeStart < 0L) return null
                            val dataEnd = minOf(holeStart, size)
                            if (dataEnd <= pos) return null
                            while (pos < dataEnd) {
                                val chunk = minOf(CHUNK_BYTES.toLong(), dataEnd - pos).toInt()
                                readBuffer.clear()
                                readBuffer.limit(chunk)
                                while (readBuffer.hasRemaining()) {
                                    val n = channel.read(readBuffer, pos + readBuffer.position())
                                    check(n > 0) { "short SD hash read" }
                                }
                                if (nativeSession != null) {
                                    nativeSession.update(readSegment, chunk.toLong())
                                } else {
                                    readBuffer.flip()
                                    digest!!.update(readBuffer)
                                }
                                pos += chunk
                            }
                        }
                        return nativeSession?.finishHex() ?: HexFormat.of().formatHex(digest!!.digest())
                    } finally {
                        nativeSession?.close()
                    }
                } finally {
                    try {
                        closeFn.invokeExact(fd) as Int
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private object WindowsSparse {
        private const val GENERIC_READ = 0x80000000.toInt()
        private const val FILE_SHARE_ALL = 0x00000007
        private const val OPEN_EXISTING = 3
        private const val FILE_ATTRIBUTE_NORMAL = 0x00000080
        private const val FSCTL_QUERY_ALLOCATED_RANGES = 0x000940CF
        private const val ERROR_MORE_DATA = 234
        private const val INVALID_HANDLE_VALUE = -1L
        private const val MAX_RANGES_PER_CALL = 256

        val available: Boolean
        private val createFileW: MethodHandle?
        private val deviceIoControl: MethodHandle?
        private val closeHandle: MethodHandle?
        private val stateLayout: MemoryLayout?
        private val lastErrorHandle: VarHandle?

        init {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            var ok = false
            var cf: MethodHandle? = null
            var io: MethodHandle? = null
            var cl: MethodHandle? = null
            var sl: MemoryLayout? = null
            var le: VarHandle? = null
            if (isWindows) {
                try {
                    val linker = Linker.nativeLinker()
                    val kernel32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", Arena.global())
                    val captureOpt = Linker.Option.captureCallState("GetLastError")
                    sl = Linker.Option.captureStateLayout()
                    le = sl.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"))
                    val sCf = kernel32.find("CreateFileW").orElse(null)
                    val sIo = kernel32.find("DeviceIoControl").orElse(null)
                    val sCl = kernel32.find("CloseHandle").orElse(null)
                    if (sCf != null && sIo != null && sCl != null) {
                        cf = linker.downcallHandle(
                            sCf,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG,
                            ),
                            captureOpt,
                        )
                        io = linker.downcallHandle(
                            sIo,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                            ),
                            captureOpt,
                        )
                        cl = linker.downcallHandle(
                            sCl,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
                        )
                        ok = true
                    }
                } catch (_: Throwable) {
                    ok = false
                }
            }
            available = ok
            createFileW = cf
            deviceIoControl = io
            closeHandle = cl
            stateLayout = sl
            lastErrorHandle = le
        }

        fun tryComputeSparseSha256(path: Path, channel: FileChannel, size: Long): String? {
            if (!available || size <= 0L) return null
            val openFn = createFileW ?: return null
            val ioctlFn = deviceIoControl ?: return null
            val closeFn = closeHandle ?: return null
            val layout = stateLayout ?: return null
            val errVar = lastErrorHandle ?: return null

            Arena.ofConfined().use { arena ->
                val callState = arena.allocate(layout)
                val wideChars = (path.toAbsolutePath().normalize().toString() + "\u0000").toCharArray()
                val pathSeg = arena.allocate(ValueLayout.JAVA_CHAR, wideChars.size.toLong())
                MemorySegment.copy(wideChars, 0, pathSeg, ValueLayout.JAVA_CHAR, 0L, wideChars.size)
                val handle = openFn.invokeExact(
                    callState,
                    pathSeg,
                    GENERIC_READ,
                    FILE_SHARE_ALL,
                    MemorySegment.NULL,
                    OPEN_EXISTING,
                    FILE_ATTRIBUTE_NORMAL,
                    0L,
                ) as Long
                if (handle == INVALID_HANDLE_VALUE || handle == 0L) return null
                try {
                    val querySeg = arena.allocate(16L, 8L)
                    val outBytes = MAX_RANGES_PER_CALL * 16
                    val outSeg = arena.allocate(outBytes.toLong(), 8L)
                    val returnedSeg = arena.allocate(4L, 4L)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val readBuffer = ByteBuffer.allocateDirect(CHUNK_BYTES)
                    var queryStart = 0L
                    var pos = 0L
                    while (queryStart < size) {
                        querySeg.set(ValueLayout.JAVA_LONG, 0L, queryStart)
                        querySeg.set(ValueLayout.JAVA_LONG, 8L, size - queryStart)
                        returnedSeg.set(ValueLayout.JAVA_INT, 0L, 0)
                        val rc = ioctlFn.invokeExact(
                            callState,
                            handle,
                            FSCTL_QUERY_ALLOCATED_RANGES,
                            querySeg,
                            16,
                            outSeg,
                            outBytes,
                            returnedSeg,
                            MemorySegment.NULL,
                        ) as Int
                        val moreData = if (rc == 0) {
                            val err = errVar.get(callState, 0L) as Int
                            if (err != ERROR_MORE_DATA) return null
                            true
                        } else {
                            false
                        }
                        val bytesBack = returnedSeg.get(ValueLayout.JAVA_INT, 0L)
                        if (bytesBack < 0 || bytesBack % 16 != 0 || bytesBack > outBytes) return null
                        val count = bytesBack / 16
                        if (count == 0) {
                            if (moreData) return null
                            break
                        }
                        var lastEnd = queryStart
                        for (i in 0 until count) {
                            val rangeStart = outSeg.get(ValueLayout.JAVA_LONG, i * 16L)
                            val rangeLen = outSeg.get(ValueLayout.JAVA_LONG, i * 16L + 8L)
                            if (rangeStart < pos || rangeLen <= 0L) return null
                            val rangeEnd = minOf(size, rangeStart + rangeLen)
                            if (rangeStart > pos) {
                                hashZeros(digest, rangeStart - pos)
                                pos = rangeStart
                            }
                            while (pos < rangeEnd) {
                                val chunk = minOf(CHUNK_BYTES.toLong(), rangeEnd - pos).toInt()
                                readBuffer.clear()
                                readBuffer.limit(chunk)
                                while (readBuffer.hasRemaining()) {
                                    val n = channel.read(readBuffer, pos + readBuffer.position())
                                    check(n > 0) { "short SD hash read" }
                                }
                                readBuffer.flip()
                                digest.update(readBuffer)
                                pos += chunk
                            }
                            lastEnd = maxOf(lastEnd, rangeEnd)
                        }
                        if (!moreData) break
                        if (lastEnd <= queryStart) return null
                        queryStart = lastEnd
                    }
                    if (pos < size) {
                        hashZeros(digest, size - pos)
                    }
                    return HexFormat.of().formatHex(digest.digest())
                } finally {
                    try {
                        closeFn.invokeExact(handle) as Int
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun hashZeros(digest: MessageDigest, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val chunk = minOf(CHUNK_BYTES.toLong(), remaining).toInt()
            val slice = zeroBuffer.duplicate()
            slice.position(0)
            slice.limit(chunk)
            digest.update(slice)
            remaining -= chunk
        }
    }

    fun readIdentity(path: Path): FileIdentity? = try {
        val attrs = Files.readAttributes(path, "unix:fileKey,size,lastModifiedTime,ctime", NOFOLLOW_LINKS)
        val key = attrs["fileKey"] ?: return null
        val size = (attrs["size"] as? Number)?.toLong() ?: return null
        val mtime = (attrs["lastModifiedTime"] as? FileTime)?.toInstant() ?: return null
        val ctime = (attrs["ctime"] as? FileTime)?.toInstant() ?: return null
        val mNanos = mtime.epochSecond * 1_000_000_000L + mtime.nano
        val cNanos = ctime.epochSecond * 1_000_000_000L + ctime.nano
        FileIdentity(key, size, mNanos, cNanos)
    } catch (_: Throwable) {
        try {
            val basic = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val key = basic.fileKey() ?: path.toAbsolutePath().normalize()
            val mtime = basic.lastModifiedTime().toInstant()
            val mNanos = mtime.epochSecond * 1_000_000_000L + mtime.nano
            FileIdentity(key, basic.size(), mNanos, mNanos)
        } catch (_: Throwable) {
            null
        }
    }

    fun invalidate(path: Path?) {
        if (path == null) return
        val id = readIdentity(path) ?: return
        cache.remove(id)
    }

    fun computeSha256(path: Path?, channel: FileChannel, size: Long): String {
        if (path != null) {
            LinuxSeek.tryComputeSparseSha256(path, channel, size)?.let { return it }
            WindowsSparse.tryComputeSparseSha256(path, channel, size)?.let { return it }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val chunkBuffer = ByteBuffer.allocate(CHUNK_BYTES)
        var offset = 0L
        while (offset < size) {
            val targetChunk = minOf(CHUNK_BYTES.toLong(), size - offset).toInt()
            chunkBuffer.clear()
            chunkBuffer.limit(targetChunk)
            while (chunkBuffer.hasRemaining()) {
                val n = channel.read(chunkBuffer, offset + chunkBuffer.position())
                check(n > 0) { "short SD hash read" }
            }
            chunkBuffer.flip()
            digest.update(chunkBuffer)
            offset += targetChunk
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    fun cachedOrCompute(path: Path?, channel: FileChannel, size: Long): String {
        val identity = path?.let(::readIdentity)?.takeIf { it.size == size }
        if (identity != null) {
            cache[identity]?.let { return it }
        }
        val computed = computeSha256(path, channel, size)
        if (identity != null) {
            if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
            cache[identity] = computed
        }
        return computed
    }
}
