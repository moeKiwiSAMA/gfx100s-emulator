package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ComponentFactory
import com.github.nyabanban.fuji.emulator.api.HardwareComponent
import com.github.nyabanban.fuji.emulator.runtime.fixture.BusFactory
import com.github.nyabanban.fuji.emulator.runtime.fixture.ClientFactory
import com.github.nyabanban.fuji.emulator.runtime.fixture.GuiceFieldInjectedFactory
import com.github.nyabanban.fuji.emulator.runtime.fixture.LiveSettingFactory
import com.github.nyabanban.fuji.emulator.runtime.fixture.PlainZeroArgFactory
import com.github.nyabanban.fuji.emulator.runtime.fixture.PowerFactory
import com.github.nyabanban.fuji.emulator.runtime.fixture.ProbeComponent
import com.github.nyabanban.fuji.emulator.runtime.fixture.ProbeFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.*

class ComponentJarTopologyTest {
    @TempDir lateinit var root: Path

    private fun jar(file: String, id: String, factory: Class<out ComponentFactory>, vararg depends: String) {
        JarOutputStream(Files.newOutputStream(root.resolve(file))).use { archive ->
            fun entry(path: String, bytes: ByteArray) {
                archive.putNextEntry(JarEntry(path))
                archive.write(bytes)
                archive.closeEntry()
            }
            entry("META-INF/fuji/catalog.json", Formats.jsonBytes(mapOf("componentId" to id, "depends" to depends.toList())))
            entry("META-INF/services/${ComponentFactory::class.java.name}", "${factory.name}\n".toByteArray())
            for (type in listOf(factory, ProbeFactory::class.java, ProbeComponent::class.java)) {
                val path = type.name.replace('.', '/') + ".class"
                entry(path, checkNotNull(type.getResourceAsStream("/$path")).use { it.readAllBytes() })
            }
        }
    }

    private fun graph() {
        jar("01-client.jar", "client", ClientFactory::class.java, "bus", "power")
        jar("02-bus.jar", "bus", BusFactory::class.java, "power")
        jar("03-power.jar", "power", PowerFactory::class.java)
    }

    private fun withHost(check: ComponentHost.(List<String>) -> Unit) {
        OwnerLoop().use { owner ->
            owner.submit {
                ComponentHost(owner, root).use { host ->
                    val trace = mutableListOf<String>()
                    host.events.subscribe { trace += "${it.kind} ${it.source}" }.use { host.check(trace) }
                }
            }.join()
        }
    }

    private val creation = listOf("factory power", "create power", "factory bus", "create bus", "factory client", "create client")
    private val starts = listOf("start power", "start bus", "start client")
    private val closes = listOf("close client", "close bus", "close power")

    @Test fun reverseJarNamesStillInjectTheActualDependenciesAndOrderLifecycle() {
        graph()
        withHost { trace ->
            load()
            assertEquals(creation + starts, trace)
            val byId = components().associate { it.descriptor.id to it.component }
            val power = byId.getValue("power")
            val bus = byId.getValue("bus")
            val client = byId.getValue("client")
            assertSame(power, bus.state()["power"])
            assertSame(power, client.state()["power"])
            assertSame(bus, client.state()["bus"])
            close()
            assertEquals(creation + starts + closes, trace)
            assertEmptyHost()
        }
    }

    @Test fun jarPrivateClassesAreDistinctAndSiblingFactoriesStayInvisible() {
        graph()
        withHost {
            load()
            val byId = components().associateBy { it.descriptor.id }
            val power = byId.getValue("power")
            val bus = byId.getValue("bus")
            assertSame(power.loader, power.component.javaClass.classLoader)
            assertSame(bus.loader, bus.component.javaClass.classLoader)
            assertEquals(power.component.javaClass.name, bus.component.javaClass.name)
            assertNotSame(power.component.javaClass, bus.component.javaClass)
            assertSame(HardwareComponent::class.java, bus.loader.loadClass(HardwareComponent::class.java.name))
            assertFailsWith<ClassNotFoundException> { bus.loader.loadClass(PowerFactory::class.java.name) }
            assertFailsWith<ClassNotFoundException> { power.loader.loadClass(BusFactory::class.java.name) }
        }
    }

    @Test fun duplicateIdIsRejectedBeforeFactoryConstruction() {
        graph()
        jar("04-copy.jar", "bus", BusFactory::class.java, "power")
        expectPreflightFailure(Regex("duplicat|already exists", RegexOption.IGNORE_CASE))
    }

    @Test fun missingDependencyIsRejectedBeforeFactoryConstruction() {
        jar("01-power.jar", "power", PowerFactory::class.java)
        jar("02-bus.jar", "bus", BusFactory::class.java, "power", "absent-device")
        expectPreflightFailure(Regex("absent-device"))
    }

    @Test fun cycleIsRejectedBeforeConstructingEvenAnIndependentFactory() {
        jar("01-power.jar", "power", PowerFactory::class.java)
        jar("02-bus.jar", "bus", BusFactory::class.java, "client", "power")
        jar("03-client.jar", "client", ClientFactory::class.java, "bus", "power")
        expectPreflightFailure(Regex("cycl", RegexOption.IGNORE_CASE))
    }

    @Test fun aTransitiveDependencyIsNotAnInjectableNamedBinding() {
        jar("01-client.jar", "client", ClientFactory::class.java, "bus")
        jar("02-bus.jar", "bus", BusFactory::class.java, "power")
        jar("03-power.jar", "power", PowerFactory::class.java)
        withHost { trace ->
            val failure = assertFails { load() }
            assertContains(failure.messages(), "power")
            assertContains(failure.messages(), HardwareComponent::class.java.simpleName)
            assertEquals(creation.take(4) + listOf("close bus", "close power"), trace)
            assertEmptyHost()
        }
    }

    private fun expectPreflightFailure(reason: Regex) = withHost { trace ->
        val failure = assertFails { load() }
        assertContains(failure.messages(), reason)
        assertEquals(emptyList(), trace)
        assertEmptyHost()
    }

    @Test fun createFailureRemovesPartialPortsAndClosesPreviouslyCreatedComponents() {
        expectRollback("create", creation + listOf("close bus", "close power"))
    }

    @Test fun startFailureClosesEveryCreatedComponentIncludingThoseYetToStart() {
        expectRollback("start", creation + listOf("start power", "start bus") + closes)
    }

    private fun expectRollback(phase: String, expected: List<String>) {
        graph()
        val target = if (phase == "create") "client" else "bus"
        val marker = Files.createFile(Files.createDirectory(root.resolve(target)).resolve("fail-$phase"))
        withHost { trace ->
            val failure = assertFails { load() }
            assertContains(failure.messages(), "probe $phase failed")
            assertEquals(expected, trace)
            assertEmptyHost()
            Files.delete(marker)
            load()
            close()
            assertEquals(expected + creation + starts + closes, trace)
            assertEmptyHost()
        }
    }

    @Test fun fastPathZeroArgAndGuiceInjectFallbackLoadDeterministicallyWithParallelScan() {
        assertFalse(requiresGuiceInjection(PlainZeroArgFactory::class.java))
        assertTrue(requiresGuiceInjection(PowerFactory::class.java))
        assertTrue(requiresGuiceInjection(GuiceFieldInjectedFactory::class.java))

        // 5 JARs (>= 4 triggers parallel ComponentJar.read + sha256 scan) mixing 0-arg fast-path & Guice @Inject
        jar("01-client.jar", "client", ClientFactory::class.java, "bus", "power")
        jar("02-bus.jar", "bus", BusFactory::class.java, "power")
        jar("03-power.jar", "power", PowerFactory::class.java)
        jar("04-plain.jar", "plain-zero-arg", PlainZeroArgFactory::class.java)
        jar("05-guice-field.jar", "guice-field", GuiceFieldInjectedFactory::class.java)

        withHost {
            load()
            val byId = components().associateBy { it.descriptor.id }
            assertEquals(setOf("client", "bus", "power", "plain-zero-arg", "guice-field"), byId.keys)
            assertEquals(sha256(root.resolve("04-plain.jar")), byId.getValue("plain-zero-arg").jarSha256)
            assertEquals(sha256(root.resolve("05-guice-field.jar")), byId.getValue("guice-field").jarSha256)
            assertSame(byId.getValue("power").component, byId.getValue("bus").component.state()["power"])
            assertSame(byId.getValue("bus").component, byId.getValue("client").component.state()["bus"])
        }
    }

    @Test fun patchLatestDirectCoalescesAsyncYamlPersistenceAndFlushesOnClose() {
        jar("06-live-setting.jar", "live-setting", LiveSettingFactory::class.java)
        val configFile = root.resolve("live-setting/live-setting.yaml")
        withHost {
            load()
            assertEquals(0L, (Formats.read(configFile)["level"] as Number).toLong())
            for (level in 1L..10L) {
                patchLatestDirect("live-setting", mapOf("level" to level), persistToDisk = true)
            }
            val item = components().single { it.descriptor.id == "live-setting" }
            assertEquals(10L, item.config["level"])
            assertEquals(10L, item.revision)
            // close() synchronously flushes all coalesced pending YAML persists to disk
            close()
        }
        assertEquals(10L, (Formats.read(configFile)["level"] as Number).toLong())
    }

    private fun ComponentHost.assertEmptyHost() {
        assertTrue(components().isEmpty())
        for (id in listOf("client", "bus", "power")) {
            assertNull(ports.find(id, HardwareComponent::class.java), id)
        }
    }

    private fun Throwable.messages() = generateSequence(this) { it.cause }.joinToString("\n") { it.toString() }
}
