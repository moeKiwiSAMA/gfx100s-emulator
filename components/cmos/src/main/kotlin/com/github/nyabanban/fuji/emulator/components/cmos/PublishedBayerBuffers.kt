package com.github.nyabanban.fuji.emulator.components.cmos

/**
 * The worker renders into [back] outside the publication lock. The single sensor owner
 * pins its source under that lock and may read it until its next exposure, even if the
 * worker publishes twice while that read is in progress.
 */
internal class PublishedBayerBuffers(frameBytes: Int) {
    var front = ByteArray(frameBytes)
        private set
    var back = ByteArray(frameBytes)
        private set
    private var spare = ByteArray(frameBytes)
    private var pinned: ByteArray? = null

    fun pinForOwner(): ByteArray {
        pinned = front
        return front
    }

    fun publishBack() {
        val previous = front
        front = back
        if (previous === pinned) {
            back = spare
            spare = previous
        } else {
            back = previous
        }
    }
}
