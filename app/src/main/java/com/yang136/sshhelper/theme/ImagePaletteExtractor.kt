package com.yang136.sshhelper.theme

import android.graphics.Bitmap
import androidx.palette.graphics.Palette

object ImagePaletteExtractor {
    fun extract(bitmap: Bitmap): ImageThemePalette {
        val analysis = scaleForAnalysis(bitmap)
        val palette = Palette.from(analysis)
            .maximumColorCount(24)
            .generate()
        val samples = palette.swatches.map { ImageColorSample(it.rgb, it.population) }
        val average = averageArgb(analysis)
        if (analysis !== bitmap) analysis.recycle()
        return deriveImageThemePalette(samples, average)
    }

    private fun scaleForAnalysis(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= ANALYSIS_EDGE_PX) return bitmap
        val scale = ANALYSIS_EDGE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun averageArgb(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        pixels.forEach { color ->
            val alpha = color ushr 24 and 0xFF
            if (alpha >= 128) {
                red += color ushr 16 and 0xFF
                green += color ushr 8 and 0xFF
                blue += color and 0xFF
                count++
            }
        }
        if (count == 0L) return 0xFF101114.toInt()
        return (0xFF shl 24) or
            ((red / count).toInt() shl 16) or
            ((green / count).toInt() shl 8) or
            (blue / count).toInt()
    }

    private const val ANALYSIS_EDGE_PX = 128
}
