package com.github.nyabanban.fuji.emulator.api

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Shared by all SD provider classloaders; owns one real FD for a persistent sibling lock inode. */
class SdImageLease private constructor(
    private val image: Path,
    private val path: Path,
    private val isDirectory: Boolean,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private var closed = false

    override fun close() = synchronized(active) {
        if (!closed) {
            try { lock.close() } finally {
                try { channel.close() } finally { active.remove(path); closed = true }
            }
        }
    }

    companion object {
        private val active = mutableMapOf<Path, SdImageLease>()

        fun acquire(image: Path, path: Path): SdImageLease = acquireInternal(image, path, allowDirectory = false)

        fun acquireDirectory(directory: Path, path: Path): SdImageLease = acquireInternal(directory, path, allowDirectory = true)

        private fun pathsOverlap(a: Path, aDir: Boolean, b: Path, bDir: Boolean): Boolean {
            if (a == b) return true
            if (Files.exists(a, NOFOLLOW_LINKS) && Files.exists(b, NOFOLLOW_LINKS) && Files.isSameFile(a, b)) return true
            if (aDir && b.startsWith(a)) return true
            if (bDir && a.startsWith(b)) return true
            return false
        }

        private fun acquireInternal(target: Path, path: Path, allowDirectory: Boolean): SdImageLease = synchronized(active) {
            val media = target.toAbsolutePath().normalize()
            val key = path.toAbsolutePath().normalize()
            if (allowDirectory) {
                require(Files.isDirectory(media, NOFOLLOW_LINKS)) { "SD directory backend target must be a directory" }
            } else {
                require(Files.isRegularFile(media, NOFOLLOW_LINKS)) { "SD image must be a regular file" }
            }
            require(!Files.isSymbolicLink(key) && (!Files.exists(key, NOFOLLOW_LINKS) || Files.isRegularFile(key, NOFOLLOW_LINKS))) { "SD lease must be a regular file" }
            require(!Files.exists(key, NOFOLLOW_LINKS) || Files.size(key) == 0L) { "SD lease file must be empty" }
            // On POSIX, closing ANY FD for an inode drops this process's fcntl locks.
            // Reject overlap before opening a second FD, including across component classloaders.
            require(active.keys.none { it == key || (Files.exists(key, NOFOLLOW_LINKS) && Files.isSameFile(it, key)) }) { "SD image already leased: $key" }
            require(active.values.none { pathsOverlap(it.image, it.isDirectory, media, allowDirectory) }) { "SD image already leased through another path: $media" }
            val channel = FileChannel.open(key, CREATE, WRITE, NOFOLLOW_LINKS)
            try {
                val lock = channel.tryLock() ?: error("SD image already leased: $key")
                SdImageLease(media, key, allowDirectory, channel, lock).also { active[key] = it }
            } catch (error: Throwable) { channel.close(); throw error }
        }
    }
}
