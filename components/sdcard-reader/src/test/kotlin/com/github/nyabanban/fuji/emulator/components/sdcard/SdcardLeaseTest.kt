package com.github.nyabanban.fuji.emulator.components.sdcard

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class SdcardLeaseTest {
    @TempDir lateinit var home: Path
    private class Context(override val directory: Path) : ComponentContext {
        private val claims = mutableMapOf<String, Any>()
        override val clock = EmulationClock { 0 }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) { check(claims.putIfAbsent(name, port) == null) }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String) = directory.resolve(relative).normalize().also { require(it.startsWith(directory) && it != directory) }
    }
    private fun image(name: String = "one.img", fill: Int = 0x5a): Path = home.resolve(name).also { Files.write(it, ByteArray(1024) { fill.toByte() }) }
    private fun open(first: String = "one.img", second: String? = null, readOnly: Boolean = false, factory: ComponentFactory = SdcardFactory()): HardwareComponent {
        val config = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "slot1Image" to first, "slot1Inserted" to true, "slot1ReadOnly" to readOnly,
            "slot2Image" to (second ?: "absent.img"), "slot2Inserted" to (second != null), "slot2ReadOnly" to readOnly,
        )
        return factory.create(Context(home), config)
    }
    private fun lock(name: String = "one.img") = home.resolve("$name.lock")
    private fun probe(path: Path, hold: Boolean = false): Process {
        val classpath = listOf(SdLeaseProbe::class.java, Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        return ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, SdLeaseProbe::class.java.name, path.toString(), hold.toString()).start()
    }
    private fun assertExternalLease(path: Path, available: Boolean) {
        val process = probe(path)
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "lock probe timed out")
            val output = process.inputStream.bufferedReader().use { it.readLines() }
            val errors = process.errorStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), errors)
            assertEquals(listOf(if (available) "available" else "leased"), output)
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
    @Test fun exclusiveLeaseSurvivesFailedDuplicateOpenAndPreservesInode() {
        val path = image()
        val bytes = Files.readAllBytes(path)
        val alias = home.resolve("original-lock")
        open().use { component ->
            Files.createLink(alias, lock())
            assertExternalLease(lock(), false)
            assertFails { open() }
            assertExternalLease(lock(), false)
            component.close()
        }
        assertExternalLease(lock(), true)
        assertTrue(Files.isSameFile(alias, lock()))
        open().use {
            assertTrue(Files.isSameFile(alias, lock()))
            assertExternalLease(lock(), false)
        }
        assertContentEquals(bytes, Files.readAllBytes(path))
    }
    @Test fun readonlyImageAlsoHasExclusiveLeaseAndRejectsWritesWithoutConsumption() {
        val path = image()
        val bytes = Files.readAllBytes(path)
        open(readOnly = true).use { component ->
            val port = component as CheckedBlockStoragePort
            assertTrue(port.readOnly(1))
            assertExternalLease(lock(), false)
            assertFails { open(readOnly = true) }
            assertExternalLease(lock(), false)
            val source = ByteBuffer.wrap(byteArrayOf(1, 2))
            assertFails { port.write(1, 0, source) }
            assertEquals(0, source.position())
            assertFails { port.checkAccess(1, 0, 2, true) }
            val target = ByteBuffer.allocateDirect(2)
            assertEquals(2, port.read(1, 0, target))
            assertEquals(0x5a.toByte(), target.get(0))
        }
        assertExternalLease(lock(), true)
        assertContentEquals(bytes, Files.readAllBytes(path))
    }
    @Test fun secondSlotLeaseFailureReleasesFirstSlotWithoutReleasingOtherOwner() {
        image(); image("two.img")
        open("two.img").use {
            assertFails { open(second = "two.img") }
            assertExternalLease(lock(), true)
            assertExternalLease(lock("two.img"), false)
            open().use { assertExternalLease(lock(), false) }
        }
        open(second = "two.img").close()
    }
    @Test fun independentJarClassloadersShareGuardAndRetainActualLeaseAfterRefusal() {
        image()
        val jar = Path.of(System.getProperty("fuji.sdcard.testJar"))
        assertTrue(Files.isRegularFile(jar))
        fun loader() = object : URLClassLoader(arrayOf(jar.toUri().toURL()), ComponentFactory::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
                if (!name.startsWith("com.github.nyabanban.fuji.emulator.components.sdcard.")) return@synchronized super.loadClass(name, resolve)
                val type = findLoadedClass(name) ?: findClass(name)
                if (resolve) resolveClass(type)
                type
            }
        }
        loader().use { firstLoader -> loader().use { secondLoader ->
            fun factory(loader: ClassLoader) = loader.loadClass(SdcardFactory::class.java.name).getConstructor().newInstance() as ComponentFactory
            val first = factory(firstLoader); val second = factory(secondLoader)
            assertNotSame(first.javaClass, second.javaClass)
            open(factory = first).use {
                assertFailsWith<IllegalArgumentException> { open(factory = second) }
                assertExternalLease(lock(), false)
            }
            open(factory = second).use { assertExternalLease(lock(), false) }
        } }
        assertExternalLease(lock(), true)
    }
    @Test fun hardLinkedLeaseFromAnotherFactoryIsRefusedWithoutDroppingExistingLock() {
        image(); image("two.img")
        open().use {
            Files.createLink(lock("two.img"), lock())
            assertFailsWith<IllegalArgumentException> { open("two.img") }
            assertExternalLease(lock(), false)
        }
        open("two.img").close()
    }
    @Test fun crossFactoryImageHardLinkRefusesBeforeOpeningAnotherSidecar() {
        val path = image()
        Files.createLink(home.resolve("alias.img"), path)
        open().use {
            val error = assertFailsWith<IllegalArgumentException> { open("alias.img") }
            assertTrue(error.message.orEmpty().contains("already leased through another path"))
            assertFalse(Files.exists(lock("alias.img")))
            assertExternalLease(lock(), false)
        }
        open("alias.img").close()
        assertContentEquals(ByteArray(1024) { 0x5a }, Files.readAllBytes(path))
    }
    @Test fun nonemptySecondSidecarRefusesWholeSetWithoutCreatingFirstSidecarOrChangingBytes() {
        val path = image(); val other = image("two.img")
        Files.write(lock("two.img"), byteArrayOf(7, 8))
        val error = assertFailsWith<IllegalArgumentException> { open(second = "two.img") }
        assertEquals("SD lease file must be empty", error.message)
        assertFalse(Files.exists(lock()))
        assertContentEquals(byteArrayOf(7, 8), Files.readAllBytes(lock("two.img")))
        assertContentEquals(ByteArray(1024) { 0x5a }, Files.readAllBytes(path))
        assertContentEquals(ByteArray(1024) { 0x5a }, Files.readAllBytes(other))
    }
    @Test fun externallyOwnedSecondSlotReleasesFirstSlotAndBothCanReopenAfterOwnerCloses() {
        image(); image("two.img")
        Files.createFile(lock("two.img"))
        val process = probe(lock("two.img"), true)
        try {
            val output = process.inputStream.bufferedReader()
            assertEquals("held", CompletableFuture.supplyAsync { output.readLine() }.get(15, TimeUnit.SECONDS))
            val error = assertFailsWith<IllegalStateException> { open(second = "two.img") }
            assertTrue(error.message.orEmpty().contains("SD image already leased"))
            assertExternalLease(lock(), true)
            assertExternalLease(lock("two.img"), false)
            process.outputStream.close()
            assertTrue(process.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), process.errorStream.bufferedReader().readText())
        } finally { if (process.isAlive) process.destroyForcibly() }
        open(second = "two.img").use {
            assertExternalLease(lock(), false); assertExternalLease(lock("two.img"), false)
        }
    }
    @Test fun normalTwoSlotByteBufferIoAndBoundsRemainUnchanged() {
        image(); image("two.img", 0x6b)
        open(second = "two.img").use { component ->
            val port = component as CheckedBlockStoragePort
            assertEquals(1024L, port.size(1)); assertEquals(1024L, port.size(2))
            val bytes = ByteBuffer.wrap(byteArrayOf(0x7f, 1, 2, 3, 0x7e)).also { it.position(1); it.limit(4) }
            assertEquals(3, port.write(2, 511, bytes)); assertEquals(4, bytes.position())
            val target = ByteBuffer.wrap(ByteArray(5) { 0x70 }).also { it.position(1); it.limit(4) }
            assertEquals(3, port.read(2, 511, target)); assertEquals(4, target.position())
            assertContentEquals(byteArrayOf(0x70, 1, 2, 3, 0x70), target.array())
            val bad = ByteBuffer.wrap(byteArrayOf(4, 5))
            assertFails { port.write(1, 1023, bad) }; assertEquals(0, bad.position())
            assertFails { port.read(1, Long.MAX_VALUE, bad) }; assertEquals(0, bad.position())
            assertExternalLease(lock(), false); assertExternalLease(lock("two.img"), false)
        }
        assertContentEquals(ByteArray(1024) { 0x5a }, Files.readAllBytes(home.resolve("one.img")))
    }
    @Test fun identicalAndHardLinkedSlotImagesRefuseBeforeAnyLeaseIsCreated() {
        val path = image(); val bytes = Files.readAllBytes(path)
        assertFailsWith<IllegalArgumentException> { open(second = "one.img") }
        Files.createLink(home.resolve("alias.img"), path)
        assertFailsWith<IllegalArgumentException> { open(second = "alias.img") }
        assertFalse(Files.exists(lock())); assertFalse(Files.exists(lock("alias.img")))
        assertContentEquals(bytes, Files.readAllBytes(path))
    }
    @Test fun imageLockAndLockLockAliasesRefuseWithoutMutatingMedia() {
        val first = image(); val second = image("two.img", 0x6b)
        val bytes = Files.readAllBytes(first); val other = Files.readAllBytes(second)
        Files.createLink(lock(), second)
        assertFailsWith<IllegalArgumentException> { open(second = "two.img") }
        assertFalse(Files.exists(lock("two.img")))
        Files.delete(lock())
        Files.createLink(lock(), first)
        assertFailsWith<IllegalArgumentException> { open() }
        Files.delete(lock())
        Files.write(lock(), byteArrayOf(7, 8))
        Files.createLink(lock("two.img"), lock())
        assertFailsWith<IllegalArgumentException> { open(second = "two.img") }
        assertContentEquals(byteArrayOf(7, 8), Files.readAllBytes(lock()))
        assertContentEquals(bytes, Files.readAllBytes(first)); assertContentEquals(other, Files.readAllBytes(second))
        Files.delete(lock("two.img")); Files.delete(lock())
        Files.write(lock(), other)
        assertFailsWith<IllegalArgumentException> { open(second = "one.img.lock") }
        assertFalse(Files.exists(lock("one.img.lock")))
        assertContentEquals(other, Files.readAllBytes(lock()))
    }
    @Test fun nonNormalizedNamesRefuseBeforeLeaseCreation() {
        val path = image()
        Files.createDirectory(home.resolve("sub"))
        for (name in listOf("./one.img", "sub/../one.img", path.toString(), "")) assertFailsWith<IllegalArgumentException> { open(name) }
        assertFalse(Files.exists(lock()))
    }
    @Test fun symlinkImagesLocksAndParentsRefuseBeforeLeaseCreation() {
        val path = image(); val bytes = Files.readAllBytes(path)
        try {
            Files.createSymbolicLink(home.resolve("alias.img"), path)
        } catch (failure: java.nio.file.FileSystemException) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw failure
            org.junit.jupiter.api.Assumptions.abort<Unit>("Creating test symlinks failed: ${failure.reason}")
        }
        assertFailsWith<IllegalArgumentException> { open("alias.img") }
        Files.createDirectories(home.resolve("sub")); Files.createSymbolicLink(home.resolve("alias-dir"), home.resolve("sub"))
        image("sub/nested.img")
        assertFailsWith<IllegalArgumentException> { open("alias-dir/nested.img") }
        assertFalse(Files.exists(lock())); assertFalse(Files.exists(lock("alias.img"))); assertFalse(Files.exists(lock("sub/nested.img")))
        Files.createSymbolicLink(lock(), path)
        assertFailsWith<IllegalArgumentException> { open() }
        assertContentEquals(bytes, Files.readAllBytes(path))
    }
    @Test fun invalidSecondImageRefusesBeforeCreatingFirstLease() {
        image(); Files.write(home.resolve("two.img"), byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { open(second = "two.img") }
        assertFalse(Files.exists(lock())); assertFalse(Files.exists(lock("two.img")))
        Files.createDirectory(lock("two.img"))
        assertFailsWith<IllegalArgumentException> { open(second = "two.img") }
        assertFalse(Files.exists(lock()))
    }
}

/** Independent JVM proves an operating-system lease, including after a failed same-JVM open. */
object SdLeaseProbe {
    @JvmStatic fun main(args: Array<String>) {
        FileChannel.open(Path.of(args[0]), WRITE).use { channel ->
            val lease = channel.tryLock()
            if (args[1].toBoolean()) {
                check(lease != null)
                println("held")
                System.`in`.read()
            } else println(if (lease == null) "leased" else "available")
            lease?.close()
        }
    }
}
