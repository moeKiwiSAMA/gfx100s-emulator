package com.github.nyabanban.fuji.emulator.runtime

import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Separate classes let Gradle assign these checks to separate worker JVMs. */
class ForkIsolationFirstTest {
    @Test fun workerLogAndOptionalConcurrentPeerAreIsolated() = ForkIsolationProbe.verify("first", "second")
}
class ForkIsolationSecondTest {
    @Test fun workerLogAndOptionalConcurrentPeerAreIsolated() = ForkIsolationProbe.verify("second", "first")
}

private object ForkIsolationProbe {
    fun verify(side: String, peer: String) {
        val worker = requireNotNull(System.getProperty("org.gradle.test.worker"))
        assertTrue(worker.matches(Regex("[0-9]+")))
        assertEquals("false", System.getProperty("junit.jupiter.execution.parallel.enabled"))
        val pid = ProcessHandle.current().pid()
        val context = LoggerFactory.getILoggerFactory() as FujiLoggerContext
        val log = context.activeLogFile.toAbsolutePath().normalize()
        assertTrue(context.isStarted)
        assertEquals("worker-" + worker, log.parent.fileName.toString())
        assertEquals("test", log.parent.parent.fileName.toString())
        assertEquals("test-logs", log.parent.parent.parent.fileName.toString())
        assertFalse(log.toString().contains("\${org.gradle.test.worker}")) // Nested property expansion must resolve worker id.
        val marker = "fork-isolation-" + side + " pid=" + pid + " worker=" + worker + " token=" + java.util.UUID.randomUUID()
        context.getLogger("fork-isolation").info(marker)
        context.flushAsync(1000L)
        assertTrue(Files.readString(log).contains(marker))

        // Normal tests also work with -PfujiTestForks=1. Rendezvous is an explicit two-fork experiment.
        val directory = System.getProperty("fuji.fork.probe.dir")?.let(Path::of) ?: return
        Files.createDirectories(directory)
        fun publish(name: String, record: Map<String, Any>) {
            val pending = directory.resolve(name + ".pending")
            Files.write(pending, Formats.jsonBytes(record), CREATE_NEW)
            Files.move(pending, directory.resolve(name + ".json"), ATOMIC_MOVE)
        }
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 45000
            val responder = CompletableFuture.runAsync {
                server.accept().use { socket -> DataOutputStream(socket.getOutputStream()).apply { writeUTF(marker); flush() } }
            }
            val self = mapOf("side" to side, "worker" to worker, "pid" to pid, "port" to server.localPort, "log" to log.toString(), "marker" to marker)
            publish(side + "-ready", self)
            val peerReady = directory.resolve(peer + "-ready.json")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (!Files.exists(peerReady) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.exists(peerReady), "Two Gradle worker classes must actually run concurrently")
            val other = Formats.read(peerReady)
            val otherPid = (other.getValue("pid") as Number).toLong()
            val otherPort = (other.getValue("port") as Number).toInt()
            val otherWorker = other.getValue("worker").toString()
            assertNotEquals(worker, otherWorker)
            assertNotEquals(pid, otherPid)
            assertNotEquals(server.localPort, otherPort)
            assertNotEquals(log.toString(), other.getValue("log"))
            assertTrue(ProcessHandle.of(otherPid).orElseThrow().isAlive)
            val expectedPeerMarker = other.getValue("marker").toString()
            assertTrue(expectedPeerMarker.startsWith("fork-isolation-" + peer + " pid=" + otherPid + " worker=" + otherWorker + " token="))
            Socket(loopback, otherPort).use { socket ->
                socket.soTimeout = 10000
                assertEquals(expectedPeerMarker, DataInputStream(socket.getInputStream()).readUTF())
            }
            responder.get(10, TimeUnit.SECONDS)
            assertFalse(Files.readString(log).contains(expectedPeerMarker))
            publish(side + "-verified", self + mapOf("peerPid" to otherPid, "peerWorker" to otherWorker,
                "peerPort" to otherPort, "peerLog" to other.getValue("log"), "peerAlive" to true, "socketHandshake" to true))
        }
    }
}
