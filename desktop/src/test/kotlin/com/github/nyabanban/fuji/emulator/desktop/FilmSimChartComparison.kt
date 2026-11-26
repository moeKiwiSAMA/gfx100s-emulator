package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertTrue

/** Compares the 24 interior chart samples of depth-studio on the locked LCD frame and in the decoded JPEG. */
internal object FilmSimChartComparison {
    data class Sample(val y: Int, val cb: Int, val cr: Int)

    private fun <T> chart(width: Int, height: Int, sample: (Int, Int) -> T): List<T> = List(24) { index ->
        val x = ((81.0 + 32.3 * (index % 6)) * width / 325.0).toInt()
        val y = ((70.0 + 21.1 * (index / 6)) * height / 244.0).toInt()
        sample(x, y)
    }

    private fun fromRgb(rgb: Int): Sample {
        val r = (rgb ushr 16) and 255
        val g = (rgb ushr 8) and 255
        val b = rgb and 255
        return Sample(
            (77 * r + 150 * g + 29 * b + 128) shr 8,
            ((-43 * r - 85 * g + 128 * b + 128) shr 8) + 128,
            ((128 * r - 107 * g - 21 * b + 128) shr 8) + 128,
        )
    }

    fun lockedPreview(rt: EmulatorRuntime): List<Sample> = rt.owner.submit {
        val lcd = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val pixels = IntArray(lcd.width * lcd.height)
        check(lcd.copyArgb(pixels))
        chart(lcd.width, lcd.height) { x, y -> fromRgb(pixels[y * lcd.width + x]) }
    }.join()

    fun checkJpeg(label: String, reference: List<Sample>, file: Path) {
        val image = ImageIO.createImageInputStream(file.toFile()).use { input ->
            val reader = ImageIO.getImageReaders(input).next()
            try {
                reader.input = input
                val params = reader.defaultReadParam
                params.setSourceSubsampling(16, 16, 0, 0)
                reader.read(0, params)
            } finally {
                reader.dispose()
            }
        }
        val decoded = chart(image.width, image.height) { x, y -> fromRgb(image.getRGB(x, y)) }
        val pairs = reference.zip(decoded)
        check(pairs.size == 24)
        val luma = pairs.map { (a, b) -> abs(a.y - b.y) }.average()
        val chroma = pairs.map { (a, b) -> (abs(a.cb - b.cb) + abs(a.cr - b.cr)) * 0.5 }.average()
        println("[GAUNTLET][CHART][$label] locked LCD versus JPEG mean absolute error Y=$luma CbCr=$chroma")
        assertTrue(luma <= 4.0 && chroma <= 8.0, "$label diverged from the locked LCD chart: Y=$luma CbCr=$chroma")
    }
}
