package com.github.nyabanban.fuji.emulator.api

/** Single owner; presentation and copies never share mutable arrays across threads. */
class ArgbFrameBuffer(override val width: Int, override val height: Int) : DisplayPort {
    private val pixels = IntArray(Math.multiplyExact(width, height))
    override var sequence = 0L
        private set
    override var contentSequence = 0L
        private set
    override var skippedIdenticalFrames = 0L
        private set
    override fun copyArgb(target: IntArray): Boolean {
        require(target.size == pixels.size)
        if (sequence == 0L) return false
        pixels.copyInto(target); return true
    }
    override fun presentArgb(pixels: IntArray, width: Int, height: Int) {
        require(width == this.width && height == this.height && pixels.size == this.pixels.size)
        if (sequence > 0L && java.util.Arrays.equals(this.pixels, pixels)) {
            sequence++
            skippedIdenticalFrames++
            return
        }
        pixels.copyInto(this.pixels)
        sequence++
        contentSequence++
    }
    fun saveSnapshot(): Map<String, Any?> {
        val encoded = if (sequence == 0L) null else {
            val bytes = java.nio.ByteBuffer.allocate(pixels.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.asIntBuffer().put(pixels)
            java.util.Base64.getEncoder().encodeToString(bytes.array())
        }
        return linkedMapOf(
            "schema" to 1,
            "width" to width,
            "height" to height,
            "sequence" to sequence,
            "pixelsBase64" to encoded,
        )
    }
    fun restoreSnapshot(data: Map<*, *>) {
        require((data["schema"] as? Number)?.toInt() == 1) { "Unsupported ArgbFrameBuffer snapshot schema" }
        require((data["width"] as? Number)?.toInt() == width && (data["height"] as? Number)?.toInt() == height) {
            "ArgbFrameBuffer dimensions differ"
        }
        val nextSequence = (data["sequence"] as? Number)?.toLong() ?: error("Missing ArgbFrameBuffer sequence")
        require(nextSequence >= 0L) { "Negative ArgbFrameBuffer sequence" }
        val encoded = data["pixelsBase64"] as? String
        if (nextSequence == 0L) {
            require(encoded == null) { "Zero-sequence ArgbFrameBuffer must not carry pixel payload" }
            pixels.fill(0)
            sequence = 0L
            contentSequence = 0L
            skippedIdenticalFrames = 0L
        } else {
            require(encoded != null) { "Non-zero ArgbFrameBuffer snapshot requires pixel payload" }
            val raw = java.util.Base64.getDecoder().decode(encoded)
            require(raw.size == pixels.size * 4) { "ArgbFrameBuffer pixel byte length mismatch" }
            java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
            sequence = nextSequence
            contentSequence = nextSequence
            skippedIdenticalFrames = 0L
        }
    }
}
