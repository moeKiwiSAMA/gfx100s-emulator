package com.github.nyabanban.fuji.emulator.api.display

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DotLcdTransportCodecTest {
    @Test
    fun orientUprightRotatesActiveDrawingPlane180DegreesAndPreservesOrCropsExtraRows() {
        val raw = ByteArray(DotLcdTransportCodec.WIDTH * DotLcdTransportCodec.TRANSPORT_ROWS)
        // Firmware logical top-left (0, 0) is drawn into canvas (303, 229) -> raw[229, 303]
        raw[229 * DotLcdTransportCodec.WIDTH + 303] = 1
        // Firmware logical (5, 2) is drawn into canvas (298, 227) -> raw[227, 298]
        raw[227 * DotLcdTransportCodec.WIDTH + 298] = 1
        // Firmware logical bottom-right (303, 229) is drawn into canvas (0, 0) -> raw[0, 0]
        raw[0 * DotLcdTransportCodec.WIDTH + 0] = 1
        // Extra transport padding row 235 at raw column 10 -> column-aligned (303 - 10 = 293)
        raw[235 * DotLcdTransportCodec.WIDTH + 10] = 1

        val packet = DotLcdTransportCodec.encode(raw)
        val decoded = DotLcdTransportCodec.decode(packet)
        assertContentEquals(raw, decoded)

        // 304x230 cropped active plane
        val upright230 = DotLcdTransportCodec.orientUpright(decoded, preserveExtraRows = false)
        assertEquals(DotLcdTransportCodec.WIDTH * DotLcdTransportCodec.DRAWING_ROWS, upright230.size)
        assertEquals(1.toByte(), upright230[0 * DotLcdTransportCodec.WIDTH + 0])
        assertEquals(1.toByte(), upright230[2 * DotLcdTransportCodec.WIDTH + 5])
        assertEquals(1.toByte(), upright230[229 * DotLcdTransportCodec.WIDTH + 303])
        assertEquals(3, upright230.count { it == 1.toByte() })

        // 304x240 lossless plane is an involution (double application recovers exact raw bits)
        val upright240 = DotLcdTransportCodec.orientUpright(decoded, preserveExtraRows = true)
        assertEquals(DotLcdTransportCodec.WIDTH * DotLcdTransportCodec.TRANSPORT_ROWS, upright240.size)
        assertEquals(1.toByte(), upright240[0 * DotLcdTransportCodec.WIDTH + 0])
        assertEquals(1.toByte(), upright240[2 * DotLcdTransportCodec.WIDTH + 5])
        assertEquals(1.toByte(), upright240[229 * DotLcdTransportCodec.WIDTH + 303])
        assertEquals(1.toByte(), upright240[235 * DotLcdTransportCodec.WIDTH + 293])
        assertEquals(4, upright240.count { it == 1.toByte() })
        assertContentEquals(decoded, DotLcdTransportCodec.orientUpright(upright240, preserveExtraRows = true))

        assertFailsWith<IllegalArgumentException> {
            DotLcdTransportCodec.orientUpright(ByteArray(10))
        }
    }
}
