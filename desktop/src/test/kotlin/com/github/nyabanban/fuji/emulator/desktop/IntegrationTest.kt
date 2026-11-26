package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.runtime.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class IntegrationTest {
    private val project = Path.of(System.getProperty("componentProjectRoot"))
    internal fun fixture(): Path {
        val root = Files.createTempDirectory("fuji-jars")
        Files.createDirectories(root.resolve("compoments"))
        Files.list(project.resolve("components")).use { modules ->
            modules.filter { Files.isRegularFile(it.resolve("build.gradle.kts")) }.forEach { module ->
                Files.list(module.resolve("build/libs")).use { jars ->
                    jars.filter { it.toString().endsWith(".jar") }.forEach { jar ->
                        Files.copy(jar, root.resolve("compoments").resolve(jar.fileName))
                    }
                }
            }
        }
        return root
    }

    internal fun host(root: Path, action: (OwnerLoop, ComponentHost) -> Unit) {
        OwnerLoop().use { owner ->
            val host = ComponentHost(owner, root.resolve("compoments"))
            try {
                owner.submit { host.load() }.join()
                action(owner, host)
            } finally {
                owner.submit { host.close() }.join()
            }
        }
    }

    @Test
    fun isolatedHardwareJarsConfigPersistenceDirectPortsAndFrames() {
        val root = fixture()
        try {
            host(root) { owner, host ->
                owner.submit {
                    assertTrue(host.components().size >= 40, "Expected >= 40 hardware components, got ${host.components().size}")
                    val vglib = host.components().single { it.descriptor.id == "soc-vglib-init" }.component
                    assertEquals(false, vglib.state()["enabled"])
                    assertTrue(vglib.additionalMmioPorts().isEmpty())
                    assertNull(host.ports.find("soc-vglib-init.registers", MmioDevice::class.java))
                    assertNull(host.ports.find("rtc.registers", PowerRegisterPort::class.java))
                    assertTrue(host.components().all { it.component.javaClass.classLoader === it.loader })
                    assertFails { host.ports.find("battery", ButtonPort::class.java) }
                    assertEquals(100.0, host.ports.find("battery", BatteryPort::class.java)!!.chargePercent())
                    val display = host.ports.find("display.main", DisplayPort::class.java)!!
                    val pixels = IntArray(display.width * display.height)
                    assertFalse(display.copyArgb(pixels))
                    pixels[11] = 0xff123456.toInt()
                    display.presentArgb(pixels, display.width, display.height)
                    pixels[11] = 0
                    assertTrue(display.copyArgb(pixels))
                    assertEquals(0xff123456.toInt(), pixels[11])
                    assertEquals(63.0, host.ports.find("mount", MountPort::class.java)!!.attachedLens()!!.focalLengthMm)
                    assertFails {
                        host.ports.find("cmos", SensorPort::class.java)!!.expose(
                            Exposure(0, 1000, 100, host.ports.find("lens", LensPort::class.java)!!.parameters()),
                            ByteBuffer.allocate(10),
                        )
                    }
                }.join()
                host.patch("battery", 0, mapOf("chargePercent" to 37.0)).join()
                assertFails { host.patch("battery", 0, mapOf("chargePercent" to 0.0)).join() }
                assertFails { host.patch("battery", 1, mapOf("chargePercent" to -1)).join() }
                assertFails { host.patch("battery", 1, mapOf("chargePercent" to 20, "unknown" to true)).join() }
                assertFails { host.patch("display", 0, mapOf("width" to 320)).join() }
                host.patch("buttons", 0, mapOf("menu" to true)).join()
                owner.submit { assertTrue(host.ports.find("buttons", ButtonPort::class.java)!!.pressed("menu")) }.join()
                assertEquals(
                    37.0,
                    (Formats.read(root.resolve("compoments/battery/battery.yaml"))["chargePercent"] as Number).toDouble(),
                )
            }
            host(root) { owner, host ->
                owner.submit { assertEquals(37.0, host.ports.find("battery", BatteryPort::class.java)!!.chargePercent()) }.join()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun httpValidatesAndMutatesOnOwnerAndUsesOptimisticRevisions() {
        val root = fixture()
        try {
            host(root) { _, host ->
                ControlServer(host, 0).use { server ->
                    HttpClient.newHttpClient().use { client ->
                        val base = "http://127.0.0.1:${server.address.port}"
                        fun patch(body: String, origin: Boolean = false): HttpResponse<String> {
                            val builder = HttpRequest.newBuilder(URI.create("$base/api/v1/components/battery"))
                                .header("Content-Type", "application/json")
                            if (origin) builder.header("Origin", "https://example.invalid")
                            return client.send(
                                builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(),
                                HttpResponse.BodyHandlers.ofString(),
                            )
                        }
                        assertEquals(200, patch("""{"revision":0,"changes":{"chargePercent":41}}""").statusCode())
                        assertEquals(409, patch("""{"revision":0,"changes":{"chargePercent":1}}""").statusCode())
                        assertEquals(400, patch("""{"revision":1,"changes":{"chargePercent":101}}""").statusCode())
                        assertEquals(400, patch("""{"revision":1,"changes":{"chargePercent":4}}""", true).statusCode())
                        val state = client.send(
                            HttpRequest.newBuilder(URI.create("$base/api/v1/state")).GET().build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                        assertEquals(200, state.statusCode())
                        assertTrue(state.body().contains("chargePercent"))
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun twoSdSlotsUseBoundedFileBackedIoAndWriteProtection() {
        val root = fixture()
        try {
            val data = root.resolve("compoments/sdcard-reader")
            Files.createDirectories(data)
            Files.write(data.resolve("one.img"), ByteArray(1024) { (it and 255).toByte() })
            Files.write(data.resolve("two.img"), ByteArray(512))
            Formats.atomicYaml(
                data.resolve("sdcard-reader.yaml"),
                mapOf(
                    "slot1Image" to "one.img",
                    "slot1Inserted" to true,
                    "slot2Image" to "two.img",
                    "slot2Inserted" to true,
                    "slot2ReadOnly" to false,
                ),
            )
            host(root) { owner, host ->
                owner.submit {
                    val sd = host.ports.find("sdcard-reader", BlockStoragePort::class.java)!!
                    val bytes = ByteBuffer.allocate(8)
                    assertEquals(8, sd.read(1, 254, bytes))
                    assertEquals(254.toByte(), bytes.array()[0])
                    assertFails { sd.read(1, 1020, ByteBuffer.allocate(8)) }
                    assertFails { sd.write(1, 0, ByteBuffer.wrap(byteArrayOf(9))) }
                    assertEquals(2, sd.write(2, 510, ByteBuffer.wrap(byteArrayOf(7, 8))))
                    assertEquals(1024L, Files.size(data.resolve("one.img")))
                }.join()
            }
            assertContentEquals(byteArrayOf(7, 8), Files.readAllBytes(data.resolve("two.img")).copyOfRange(510, 512))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun duplicateJarFailsAndReleasesAllRegisteredPorts() {
        val root = fixture()
        try {
            Files.copy(root.resolve("compoments/battery-0.3.0.jar"), root.resolve("compoments/z-duplicate.jar"))
            OwnerLoop().use { owner ->
                val host = ComponentHost(owner, root.resolve("compoments"))
                assertFails { owner.submit { host.load() }.join() }
                owner.submit {
                    assertNull(host.ports.find("battery", BatteryPort::class.java))
                    assertTrue(host.components().isEmpty())
                }.join()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
