package com.github.nyabanban.fuji.emulator.desktop.panels

import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioShutterTest {
    private class Camera {
        var tick = 0L
        var wallNanos = 0L
        var running = true
        var ready: CompletableFuture<Boolean?>? = CompletableFuture.completedFuture(false)
        var busy: CompletableFuture<Boolean?>? = null
        var acknowledge: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        val edges = mutableListOf<String>()
        private var nextPoll: (() -> Unit)? = null
        val inputs = StudioInputState({ key, down ->
            edges += "$key=$down"
            acknowledge
        }, appliedTickProvider = { tick })
        val shutter = StudioShutter(inputs, { tick }, { running }, { ready }, { wallNanos }, { busy }) { action ->
            nextPoll = action
            AutoCloseable { if (nextPoll === action) nextPoll = null }
        }
        fun step(ms: Long) {
            tick += ms * 54_000
            val action = nextPoll
            nextPoll = null
            action?.invoke()
        }
    }

    @Test fun quickClickWaitsForBothAppliedS1AndCameraAcceptance() {
        val camera = Camera()
        val halfAck = CompletableFuture<Unit>()
        camera.acknowledge = halfAck
        camera.shutter.press("mouse:body")
        camera.shutter.releaseMouse()
        camera.step(500)
        assertEquals(listOf("shutterHalf=true"), camera.edges)
        halfAck.complete(Unit)
        camera.acknowledge = CompletableFuture.completedFuture(Unit)
        camera.step(75)
        camera.step(300)
        assertFalse(camera.inputs.desired("shutterFull"))
        camera.ready = CompletableFuture.completedFuture(true)
        camera.step(1)
        camera.step(74)
        assertTrue(camera.inputs.desired("shutterFull"))
        camera.step(1)
        camera.step(75)
        assertEquals(listOf("shutterHalf=true", "shutterFull=true", "shutterFull=false", "shutterHalf=false"), camera.edges)
    }

    @Test fun simultaneousBindingsAndLongHoldPreserveAnIndependentHalfPress() {
        val camera = Camera()
        camera.ready = CompletableFuture.completedFuture(true)
        camera.inputs.press("keyboard:manual", "shutterHalf")
        camera.step(350)
        camera.shutter.press("mouse:body")
        camera.shutter.press("gamepad:trigger")
        camera.shutter.releaseMouse()
        camera.step(1000)
        assertTrue(camera.inputs.desired("shutterFull"))
        camera.shutter.release("gamepad:trigger")
        camera.step(75)
        camera.step(75)
        assertTrue(camera.inputs.desired("shutterHalf"))
        camera.inputs.release("keyboard:manual", "shutterHalf")
        assertEquals(listOf("shutterHalf=true", "shutterFull=true", "shutterFull=false", "shutterHalf=false"), camera.edges)
    }

    @Test fun twoRapidClicksHaveSeparateScannedDownAndUpEdges() {
        val camera = Camera()
        camera.ready = CompletableFuture.completedFuture(true)
        repeat(2) {
            camera.shutter.press("mouse:body")
            camera.shutter.release("mouse:body")
        }
        repeat(9) { camera.step(75) }
        assertEquals(List(2) { listOf("shutterHalf=true", "shutterFull=true", "shutterFull=false", "shutterHalf=false") }.flatten(),
            camera.edges)
    }

    @Test fun cancellationOrClockResetCannotDeliverAnOldFullPress() {
        val camera = Camera()
        val staleRead = CompletableFuture<Boolean?>()
        camera.ready = staleRead
        camera.shutter.press("mouse:body")
        camera.step(75)
        camera.shutter.cancel()
        staleRead.complete(true)
        camera.step(150)
        camera.shutter.press("mouse:body")
        camera.step(-200)
        camera.shutter.press("mouse:body")
        camera.running = false
        camera.step(10)
        assertEquals(List(3) { listOf("shutterHalf=true", "shutterHalf=false") }.flatten(), camera.edges)
    }

    @Test fun missingProjectionOrUnresolvedFocusStillDeliversARealContact() {
        for ((result, limit) in listOf(null to 250L, CompletableFuture.completedFuture<Boolean?>(false) to 8000L)) {
            val camera = Camera()
            camera.ready = result
            camera.shutter.press("keyboard:pulse")
            camera.shutter.release("keyboard:pulse")
            camera.step(limit - 1)
            assertFalse(camera.inputs.desired("shutterFull"))
            camera.step(1)
            assertTrue(camera.inputs.desired("shutterFull"))
        }
    }

    @Test fun releasedClickTimesOutOnlyWhileTheGuestMakesNoProgress() {
        val camera = Camera()
        camera.shutter.press("mouse:body")
        camera.shutter.releaseMouse()
        repeat(4) {
            camera.wallNanos += 2_900_000_000L
            camera.step(1)
            assertTrue(camera.inputs.desired("shutterHalf"))
        }
        camera.wallNanos += 2_999_999_999L
        camera.step(0)
        assertTrue(camera.inputs.desired("shutterHalf"))
        camera.wallNanos++
        camera.step(0)
        assertFalse(camera.inputs.desired("shutterHalf"))
        camera.ready = CompletableFuture.completedFuture(true)
        camera.step(1000)
        assertFalse(camera.inputs.desired("shutterFull"))
    }

    @Test fun aSecondClickWaitsWithBothContactsReleasedDuringReadout() {
        val camera = Camera()
        camera.ready = CompletableFuture.completedFuture(true)
        camera.shutter.press("mouse:body")
        camera.shutter.release("mouse:body")
        repeat(4) { camera.step(75) }
        camera.busy = CompletableFuture.completedFuture(true)
        camera.shutter.press("mouse:body")
        camera.shutter.release("mouse:body")
        camera.step(4500)
        assertEquals(4, camera.edges.size)
        camera.busy = CompletableFuture.completedFuture(false)
        camera.step(1)
        camera.step(74)
        assertEquals(4, camera.edges.size)
        camera.step(1)
        repeat(5) { camera.step(75) }
        assertEquals(listOf("shutterHalf=true", "shutterFull=true", "shutterFull=false", "shutterHalf=false"),
            camera.edges.takeLast(4))
        assertEquals(8, camera.edges.size)
    }

    @Test fun cameraBusyAtTheFullEdgeReleasesHalfAndTimesOutWithoutShooting() {
        val camera = Camera()
        camera.busy = CompletableFuture.completedFuture(true)
        camera.ready = CompletableFuture.completedFuture(true)
        camera.shutter.press("mouse:body")
        camera.shutter.releaseMouse()
        camera.step(75)
        assertEquals(listOf("shutterHalf=true", "shutterHalf=false"), camera.edges)
        camera.step(30_000)
        camera.busy = CompletableFuture.completedFuture(false)
        camera.step(500)
        assertEquals(2, camera.edges.size)
    }
}
