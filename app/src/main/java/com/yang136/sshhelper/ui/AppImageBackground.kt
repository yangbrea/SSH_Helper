package com.yang136.sshhelper.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.MaterialTheme
import com.yang136.sshhelper.theme.ImageFocalTransform
import com.yang136.sshhelper.theme.focalCropSelection
import kotlin.math.roundToInt

val LocalImageBackgroundActive = staticCompositionLocalOf { false }

internal enum class StructuralSurfaceRole { CONTENT, NAVIGATION }

internal const val PRESET_CONTENT_SURFACE_ALPHA = 0.88f
internal const val PRESET_NAVIGATION_SURFACE_ALPHA = 0.92f
internal const val IMAGE_CONTENT_SURFACE_ALPHA = 0.86f
internal const val IMAGE_NAVIGATION_SURFACE_ALPHA = 0.90f

internal fun structuralSurfaceAlpha(imageBackgroundActive: Boolean, role: StructuralSurfaceRole): Float =
    when (role) {
        StructuralSurfaceRole.CONTENT -> if (imageBackgroundActive) {
            IMAGE_CONTENT_SURFACE_ALPHA
        } else {
            PRESET_CONTENT_SURFACE_ALPHA
        }
        StructuralSurfaceRole.NAVIGATION -> if (imageBackgroundActive) {
            IMAGE_NAVIGATION_SURFACE_ALPHA
        } else {
            PRESET_NAVIGATION_SURFACE_ALPHA
        }
    }

@Composable
fun imageAwareScaffoldColor(): Color =
    if (LocalImageBackgroundActive.current) Color.Transparent else MaterialTheme.colorScheme.background

@Composable
internal fun structuralSurfaceColor(
    color: Color,
    role: StructuralSurfaceRole = StructuralSurfaceRole.CONTENT,
): Color = color.copy(alpha = structuralSurfaceAlpha(LocalImageBackgroundActive.current, role))

@Composable
fun imageAwareContentColor(): Color = MaterialTheme.colorScheme.onSurface

@Composable
fun AppImageBackground(
    bitmap: Bitmap?,
    overlayStrength: Float,
    lightTheme: Boolean,
    focusX: Float = 0.5f,
    focusY: Float = 0.5f,
    zoom: Float = 1f,
    content: @Composable () -> Unit,
) {
    val active = bitmap != null
    CompositionLocalProvider(LocalImageBackgroundActive provides active) {
        Box(Modifier.fillMaxSize()) {
            bitmap?.let {
                val image = it.asImageBitmap()
                Canvas(Modifier.fillMaxSize()) {
                    if (size.width <= 0f || size.height <= 0f) return@Canvas
                    val crop = focalCropSelection(
                        imageWidth = image.width.toFloat(),
                        imageHeight = image.height.toFloat(),
                        viewportWidth = size.width,
                        viewportHeight = size.height,
                        focal = ImageFocalTransform(focusX, focusY, zoom),
                    )
                    val sourceWidth = (crop.width * image.width).roundToInt().coerceIn(1, image.width)
                    val sourceHeight = (crop.height * image.height).roundToInt().coerceIn(1, image.height)
                    drawImage(
                        image = image,
                        srcOffset = IntOffset(
                            (crop.left * image.width).roundToInt().coerceIn(0, image.width - sourceWidth),
                            (crop.top * image.height).roundToInt().coerceIn(0, image.height - sourceHeight),
                        ),
                        srcSize = IntSize(sourceWidth, sourceHeight),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            if (lightTheme) Color.White.copy(alpha = overlayStrength)
                            else Color.Black.copy(alpha = overlayStrength),
                        ),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    if (lightTheme) Color.White.copy(alpha = 0.22f)
                                    else Color.Black.copy(alpha = 0.34f),
                                    Color.Transparent,
                                    if (lightTheme) Color.White.copy(alpha = 0.28f)
                                    else Color.Black.copy(alpha = 0.44f),
                                ),
                            ),
                        ),
                )
            }
            content()
        }
    }
}
