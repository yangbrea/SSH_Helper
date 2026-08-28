package com.yang136.sshhelper.theme

import kotlin.math.max

const val MIN_CROP_ZOOM = 1f
const val MAX_CROP_ZOOM = 4f

/** Normalized source-image rectangle selected by the crop viewport. */
data class CropSelection(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

data class CropTransform(
    val zoom: Float = MIN_CROP_ZOOM,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

data class CropGeometry(
    val imageWidth: Float,
    val imageHeight: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
) {
    init {
        require(imageWidth > 0f && imageHeight > 0f)
        require(viewportWidth > 0f && viewportHeight > 0f)
    }

    val coverScale: Float = max(viewportWidth / imageWidth, viewportHeight / imageHeight)
}

/** Applies a centered zoom and pan while keeping the crop viewport fully covered. */
fun updateCropTransform(
    current: CropTransform,
    geometry: CropGeometry,
    zoomChange: Float = 1f,
    panX: Float = 0f,
    panY: Float = 0f,
): CropTransform {
    val nextZoom = (current.zoom * zoomChange).coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM)
    val zoomRatio = nextZoom / current.zoom.coerceAtLeast(MIN_CROP_ZOOM)
    return clampCropTransform(
        CropTransform(
            zoom = nextZoom,
            offsetX = current.offsetX * zoomRatio + panX,
            offsetY = current.offsetY * zoomRatio + panY,
        ),
        geometry,
    )
}

fun clampCropTransform(transform: CropTransform, geometry: CropGeometry): CropTransform {
    val zoom = transform.zoom.coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM)
    val renderedWidth = geometry.imageWidth * geometry.coverScale * zoom
    val renderedHeight = geometry.imageHeight * geometry.coverScale * zoom
    val maxOffsetX = ((renderedWidth - geometry.viewportWidth) / 2f).coerceAtLeast(0f)
    val maxOffsetY = ((renderedHeight - geometry.viewportHeight) / 2f).coerceAtLeast(0f)
    return CropTransform(
        zoom = zoom,
        offsetX = transform.offsetX.coerceIn(-maxOffsetX, maxOffsetX),
        offsetY = transform.offsetY.coerceIn(-maxOffsetY, maxOffsetY),
    )
}

/** Converts the visible viewport to a normalized rectangle in the oriented bitmap. */
fun cropSelection(transform: CropTransform, geometry: CropGeometry): CropSelection {
    val clamped = clampCropTransform(transform, geometry)
    val renderedWidth = geometry.imageWidth * geometry.coverScale * clamped.zoom
    val renderedHeight = geometry.imageHeight * geometry.coverScale * clamped.zoom
    val left = ((renderedWidth - geometry.viewportWidth) / 2f - clamped.offsetX) / renderedWidth
    val top = ((renderedHeight - geometry.viewportHeight) / 2f - clamped.offsetY) / renderedHeight
    return CropSelection(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        width = (geometry.viewportWidth / renderedWidth).coerceIn(0f, 1f),
        height = (geometry.viewportHeight / renderedHeight).coerceIn(0f, 1f),
    )
}

fun focalTransform(transform: CropTransform, geometry: CropGeometry): ImageFocalTransform {
    val selection = cropSelection(transform, geometry)
    return ImageFocalTransform(
        focusX = (selection.left + selection.width / 2f).coerceIn(0f, 1f),
        focusY = (selection.top + selection.height / 2f).coerceIn(0f, 1f),
        zoom = transform.zoom.coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM),
    )
}

/** Visible normalized source rectangle for a focal image rendered into a viewport. */
fun focalCropSelection(
    imageWidth: Float,
    imageHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    focal: ImageFocalTransform,
): CropSelection {
    require(imageWidth > 0f && imageHeight > 0f && viewportWidth > 0f && viewportHeight > 0f)
    val imageAspect = imageWidth / imageHeight
    val viewportAspect = viewportWidth / viewportHeight
    val coverWidth = if (imageAspect > viewportAspect) viewportAspect / imageAspect else 1f
    val coverHeight = if (imageAspect > viewportAspect) 1f else imageAspect / viewportAspect
    val zoom = focal.zoom.coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM)
    val width = (coverWidth / zoom).coerceIn(0f, 1f)
    val height = (coverHeight / zoom).coerceIn(0f, 1f)
    val left = (focal.focusX.coerceIn(0f, 1f) - width / 2f).coerceIn(0f, 1f - width)
    val top = (focal.focusY.coerceIn(0f, 1f) - height / 2f).coerceIn(0f, 1f - height)
    return CropSelection(left, top, width, height)
}
