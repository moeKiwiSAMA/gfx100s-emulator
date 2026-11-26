package com.github.nyabanban.fuji.emulator.api.display

/** Strict DAT-derived packet view, not a physical display controller or framebuffer mapping. */
object DotLcdTransportCodec {
    const val WIDTH=304
    const val TRANSPORT_ROWS=240
    const val DRAWING_ROWS=230
    const val BYTES=19488
    private val header=intArrayOf(0xc0810000.toInt(),0,0xc0060000.toInt(),0x0004014e)
    private val footer=intArrayOf(0xd8d10000.toInt(),0x00040070,0xc00e0000.toInt(),0x00070000)
    fun read(bytes:ByteArray,at:Int):Int {
        require(at>=0 && at<=bytes.size-4)
        return (bytes[at].toInt() and 255) or ((bytes[at+1].toInt() and 255) shl 8) or
            ((bytes[at+2].toInt() and 255) shl 16) or ((bytes[at+3].toInt() and 255) shl 24)
    }
    private fun write(bytes:ByteArray,at:Int,value:Int) {for(i in 0..3)bytes[at+i]=(value ushr (i*8)).toByte()}
    fun reverse9(value:Int):Int {require(value in 0..511);var result=0;for(bit in 0..8)if(value and (1 shl bit)!=0)result=result or (1 shl (8-bit));return result}
    /** Full emitted bit plane. Ten extra rows are retained and not claimed to be visible pixels. */
    fun decode(bytes:ByteArray):ByteArray {
        require(bytes.size==BYTES) {"DotLCD exact transport length required"}
        for(i in header.indices)require(read(bytes,i*4)==header[i]) {"DotLCD fixed header differs"}
        for(i in footer.indices)require(read(bytes,BYTES-16+i*4)==footer[i]) {"DotLCD fixed footer differs"}
        for(x in 0 until WIDTH) {
            val at=16+x*64
            require(read(bytes,at)==(0xd8c18000.toInt() or reverse9(WIDTH-x))) {"DotLCD column order or reserved bits differ"}
            for(group in 0 until 15) {
                val value=read(bytes,at+4+group*4)
                require(value ushr 16==0xd8c1) {"DotLCD payload command/reserved bits differ"}

            }
        }
        val bits=ByteArray(WIDTH*TRANSPORT_ROWS)
        for(x in 0 until WIDTH)for(group in 0 until 15) {
            val value=read(bytes,16+x*64+4+group*4)
            for(bit in 0..15)bits[(group*16+bit)*WIDTH+x]=((value ushr (15-bit)) and 1).toByte()
        }
        return bits
    }
    /** Reconstructs every packet byte including fixed words; rejects nonbinary or partial planes. */
    fun encode(bits:ByteArray):ByteArray {
        require(bits.size==WIDTH*TRANSPORT_ROWS && bits.all {it==0.toByte() || it==1.toByte()}) {"Complete binary transport plane required"}
        val bytes=ByteArray(BYTES)
        for(i in header.indices)write(bytes,i*4,header[i])
        for(i in footer.indices)write(bytes,BYTES-16+i*4,footer[i])
        for(x in 0 until WIDTH) {
            val at=16+x*64;write(bytes,at,0xd8c18000.toInt() or reverse9(WIDTH-x))
            for(group in 0 until 15) {
                var value=0xd8c10000.toInt()
                for(bit in 0..15)value=value or (bits[(group*16+bit)*WIDTH+x].toInt() shl (15-bit))
                write(bytes,at+4+group*4,value)
            }
        }
        return bytes
    }

    /**
     * Reorients raw wire-order `304×240` transport bits into upright logical screen order.
     *
     * Verified against GFX100S 2.13 firmware (`DAT` `section4.bin`):
     * - Both canvas writers (`0x0124C704` bitmap blitter and `0x0124C8E0` rectangle fill) map logical
     *   screen `(x, y)` (`0..303`, `0..229`) to canvas `(303 - x, 229 - y)`.
     * - The packet encoder (`0x0124C9F4`) emits canvas columns `0..303` with descending wire addresses
     *   `304..1` (`reverse9(304 - col)`) and rows `0..229` followed by `10` trailing padding rows.
     *
     * Therefore active rows `0..229` are rotated 180 degrees (`(x, y) <- (303 - x, 229 - y)`).
     * When [preserveExtraRows] is `true`, returns `304×240` with trailing rows `230..239` preserved
     * in column-aligned order (`x <- 303 - x`); when `false`, returns the `304×230` active drawing plane.
     */
    fun orientUpright(bits: ByteArray, preserveExtraRows: Boolean = true): ByteArray {
        require(bits.size == WIDTH * TRANSPORT_ROWS) { "Complete 304x240 transport plane required" }
        val outRows = if (preserveExtraRows) TRANSPORT_ROWS else DRAWING_ROWS
        val out = ByteArray(WIDTH * outRows)
        for (y in 0 until DRAWING_ROWS) {
            val srcRowOffset = (DRAWING_ROWS - 1 - y) * WIDTH
            val dstRowOffset = y * WIDTH
            for (x in 0 until WIDTH) {
                out[dstRowOffset + x] = bits[srcRowOffset + (WIDTH - 1 - x)]
            }
        }
        if (preserveExtraRows) {
            for (y in DRAWING_ROWS until TRANSPORT_ROWS) {
                val rowOffset = y * WIDTH
                for (x in 0 until WIDTH) {
                    out[rowOffset + x] = bits[rowOffset + (WIDTH - 1 - x)]
                }
            }
        }
        return out
    }
}
