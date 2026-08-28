package com.yang136.sshhelper.theme

import com.yang136.sshhelper.settings.ImageThemeVariant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class ImageColorSample(val argb: Int, val population: Int)

data class ImageThemeTokens(
    val dark: Boolean,
    val primary: Int,
    val onPrimary: Int,
    val secondary: Int,
    val onSecondary: Int,
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val onBackground: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
)

internal data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

fun deriveImageThemePalette(
    samples: List<ImageColorSample>,
    averageArgb: Int,
): ImageThemePalette {
    val usable = samples.filter { it.population > 0 && alpha(it.argb) >= 128 }
    val maximumPopulation = usable.maxOfOrNull { it.population }?.coerceAtLeast(1) ?: 1
    val colorful = usable.filter {
        val hsl = argbToHsl(it.argb)
        hsl.saturation >= 0.10f && hsl.lightness in 0.08f..0.92f
    }

    val fallbackAccent = DEFAULT_IMAGE_ACCENT
    val vibrantSample = colorful.maxByOrNull { sample ->
        val hsl = argbToHsl(sample.argb)
        val population = sample.population.toFloat() / maximumPopulation
        population * 0.48f + hsl.saturation * 0.38f +
            (1f - abs(hsl.lightness - 0.52f) * 2f).coerceIn(0f, 1f) * 0.14f
    }
    val vibrant = vibrantSample?.argb ?: fallbackAccent
    val vibrantHsl = argbToHsl(vibrant)

    val muted = usable.maxByOrNull { sample ->
        val hsl = argbToHsl(sample.argb)
        val population = sample.population.toFloat() / maximumPopulation
        val targetSaturation = 1f - abs(hsl.saturation - 0.30f).coerceIn(0f, 1f)
        population * 0.62f + targetSaturation * 0.25f +
            (1f - abs(hsl.lightness - 0.50f) * 2f).coerceIn(0f, 1f) * 0.13f
    }?.argb ?: withHsl(vibrant, saturation = 0.30f)

    val secondary = colorful
        .filter { hueDistance(argbToHsl(it.argb).hue, vibrantHsl.hue) >= 30f }
        .maxByOrNull { sample ->
            val hsl = argbToHsl(sample.argb)
            sample.population.toFloat() / maximumPopulation * 0.58f + hsl.saturation * 0.42f
        }?.argb
        ?: hslToArgb(
            Hsl(
                hue = (vibrantHsl.hue + 55f) % 360f,
                saturation = max(0.34f, vibrantHsl.saturation * 0.72f),
                lightness = vibrantHsl.lightness.coerceIn(0.36f, 0.64f),
            ),
        )

    val averageHsl = argbToHsl(averageArgb)
    val neutral = hslToArgb(averageHsl.copy(saturation = min(averageHsl.saturation, 0.10f)))
    val averageLuminance = relativeLuminance(averageArgb).toFloat()
    val recommended = when {
        vibrantSample == null || vibrantHsl.saturation < 0.18f -> ImageThemeVariant.SOFT
        averageLuminance > 0.78f && averageHsl.lightness > 0.78f -> ImageThemeVariant.BRIGHT
        else -> ImageThemeVariant.IMMERSIVE
    }

    return ImageThemePalette(
        vibrantArgb = opaque(vibrant),
        mutedArgb = opaque(muted),
        secondaryArgb = opaque(secondary),
        neutralArgb = opaque(neutral),
        averageLuminance = averageLuminance,
        recommendedVariant = recommended,
    )
}

fun imageThemeTokens(
    palette: ImageThemePalette,
    variant: ImageThemeVariant,
): ImageThemeTokens {
    val dark = variant != ImageThemeVariant.BRIGHT
    val seed = when (variant) {
        ImageThemeVariant.IMMERSIVE -> palette.vibrantArgb
        ImageThemeVariant.SOFT -> palette.mutedArgb
        ImageThemeVariant.BRIGHT -> palette.vibrantArgb
    }
    val seedHsl = argbToHsl(seed)
    val secondaryHsl = argbToHsl(palette.secondaryArgb)
    val neutralHsl = argbToHsl(palette.neutralArgb)

    val primary = if (dark) {
        hslToArgb(
            seedHsl.copy(
                saturation = when (variant) {
                    ImageThemeVariant.IMMERSIVE -> max(0.48f, seedHsl.saturation)
                    else -> seedHsl.saturation.coerceIn(0.28f, 0.58f)
                },
                lightness = seedHsl.lightness.coerceIn(0.58f, 0.72f),
            ),
        )
    } else {
        hslToArgb(
            seedHsl.copy(
                saturation = seedHsl.saturation.coerceIn(0.42f, 0.78f),
                lightness = seedHsl.lightness.coerceIn(0.30f, 0.43f),
            ),
        )
    }
    val secondary = hslToArgb(
        secondaryHsl.copy(
            saturation = secondaryHsl.saturation.coerceIn(0.26f, 0.62f),
            lightness = if (dark) secondaryHsl.lightness.coerceIn(0.58f, 0.72f)
            else secondaryHsl.lightness.coerceIn(0.30f, 0.45f),
        ),
    )

    val background = hslToArgb(
        neutralHsl.copy(
            saturation = min(neutralHsl.saturation, if (dark) 0.10f else 0.07f),
            lightness = if (dark) 0.055f else 0.965f,
        ),
    )
    val surface = hslToArgb(
        neutralHsl.copy(
            saturation = min(neutralHsl.saturation, 0.11f),
            lightness = if (dark) 0.095f else 0.985f,
        ),
    )
    val surfaceVariant = hslToArgb(
        neutralHsl.copy(
            saturation = min(neutralHsl.saturation + 0.02f, 0.13f),
            lightness = if (dark) 0.155f else 0.90f,
        ),
    )
    val onBackground = readableForeground(background, minimumContrast = 4.5)
    val onSurfaceVariant = readableMutedForeground(surfaceVariant, dark)
    val outline = hslToArgb(
        neutralHsl.copy(
            saturation = min(neutralHsl.saturation, 0.10f),
            lightness = if (dark) 0.47f else 0.52f,
        ),
    )

    return ImageThemeTokens(
        dark = dark,
        primary = primary,
        onPrimary = readableForeground(primary, minimumContrast = 4.5),
        secondary = secondary,
        onSecondary = readableForeground(secondary, minimumContrast = 4.5),
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        onBackground = onBackground,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
    )
}

fun contrastRatio(foreground: Int, background: Int): Double {
    val lighter = max(relativeLuminance(foreground), relativeLuminance(background))
    val darker = min(relativeLuminance(foreground), relativeLuminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun readableForeground(background: Int, minimumContrast: Double): Int {
    val light = 0xFFF7F8FA.toInt()
    val dark = 0xFF111216.toInt()
    val lightContrast = contrastRatio(light, background)
    val darkContrast = contrastRatio(dark, background)
    val chosen = if (lightContrast >= darkContrast) light else dark
    return if (max(lightContrast, darkContrast) >= minimumContrast) chosen else {
        if (relativeLuminance(background) < 0.5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }
}

private fun readableMutedForeground(background: Int, darkTheme: Boolean): Int {
    val preferred = if (darkTheme) 0xFFBBC2C7.toInt() else 0xFF555C61.toInt()
    return if (contrastRatio(preferred, background) >= 4.5) preferred
    else readableForeground(background, 4.5)
}

internal fun argbToHsl(argb: Int): Hsl {
    val r = red(argb) / 255f
    val g = green(argb) / 255f
    val b = blue(argb) / 255f
    val maximum = max(r, max(g, b))
    val minimum = min(r, min(g, b))
    val delta = maximum - minimum
    val lightness = (maximum + minimum) / 2f
    val saturation = if (delta == 0f) 0f else delta / (1f - abs(2f * lightness - 1f))
    val hue = when {
        delta == 0f -> 0f
        maximum == r -> 60f * (((g - b) / delta) % 6f)
        maximum == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return Hsl(hue, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))
}

internal fun hslToArgb(hsl: Hsl): Int {
    val c = (1f - abs(2f * hsl.lightness - 1f)) * hsl.saturation
    val x = c * (1f - abs((hsl.hue / 60f) % 2f - 1f))
    val m = hsl.lightness - c / 2f
    val (r1, g1, b1) = when (hsl.hue.mod(360f)) {
        in 0f..<60f -> Triple(c, x, 0f)
        in 60f..<120f -> Triple(x, c, 0f)
        in 120f..<180f -> Triple(0f, c, x)
        in 180f..<240f -> Triple(0f, x, c)
        in 240f..<300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return argb(
        255,
        ((r1 + m) * 255f).toInt().coerceIn(0, 255),
        ((g1 + m) * 255f).toInt().coerceIn(0, 255),
        ((b1 + m) * 255f).toInt().coerceIn(0, 255),
    )
}

private fun withHsl(argb: Int, saturation: Float): Int =
    hslToArgb(argbToHsl(argb).copy(saturation = saturation))

private fun hueDistance(a: Float, b: Float): Float {
    val difference = abs(a - b)
    return min(difference, 360f - difference)
}

private fun relativeLuminance(argb: Int): Double {
    fun component(value: Int): Double {
        val normalized = value / 255.0
        return if (normalized <= 0.04045) normalized / 12.92
        else ((normalized + 0.055) / 1.055).pow(2.4)
    }
    return component(red(argb)) * 0.2126 + component(green(argb)) * 0.7152 +
        component(blue(argb)) * 0.0722
}

private fun alpha(argb: Int) = argb ushr 24 and 0xFF
private fun red(argb: Int) = argb ushr 16 and 0xFF
private fun green(argb: Int) = argb ushr 8 and 0xFF
private fun blue(argb: Int) = argb and 0xFF
private fun argb(a: Int, r: Int, g: Int, b: Int) =
    (a shl 24) or (r shl 16) or (g shl 8) or b
private fun opaque(argb: Int) = argb or (0xFF shl 24)

private const val DEFAULT_IMAGE_ACCENT = 0xFF00E676.toInt()
