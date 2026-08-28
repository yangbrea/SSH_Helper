package com.yang136.sshhelper.theme

import android.graphics.Bitmap
import android.net.Uri
import com.yang136.sshhelper.settings.ImageThemeVariant

const val IMAGE_THEME_ALGORITHM_VERSION = 1

/** Persisted output of image analysis. ARGB values are kept platform-neutral. */
data class ImageThemePalette(
    val vibrantArgb: Int,
    val mutedArgb: Int,
    val secondaryArgb: Int,
    val neutralArgb: Int,
    val averageLuminance: Float,
    val recommendedVariant: ImageThemeVariant,
    val algorithmVersion: Int = IMAGE_THEME_ALGORITHM_VERSION,
)

enum class ImageImportPhase { IDLE, PREPARING, EDITING, SAVING, ERROR }

/** Transient picker result. The URI and uncropped bitmap are never persisted. */
data class ImageCropDraft(
    val sourceUri: Uri,
    val bitmap: Bitmap,
    val targetAspectRatio: Float,
)

/** Focal point and magnification used to crop one source image for any window ratio. */
data class ImageFocalTransform(
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val zoom: Float = 1f,
)

/** One locally persisted, already-cropped background and its independent appearance. */
data class ImageThemeEntry(
    val id: String,
    val fileName: String,
    val palette: ImageThemePalette,
    val variant: ImageThemeVariant,
    val overlayStrength: Float,
    val importedAtEpochMs: Long,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val zoom: Float = 1f,
    val thumbnail: Bitmap? = null,
)

/** Runtime state. A crop is published only after its file, metadata and palette are ready. */
data class ImageThemeState(
    val bitmap: Bitmap? = null,
    val palette: ImageThemePalette? = null,
    val activeId: String? = null,
    val recentEntries: List<ImageThemeEntry> = emptyList(),
    val importPhase: ImageImportPhase = ImageImportPhase.IDLE,
    val cropDraft: ImageCropDraft? = null,
    val errorMessage: String? = null,
) {
    val hasImage: Boolean get() = bitmap != null && palette != null
    val isImporting: Boolean
        get() = importPhase == ImageImportPhase.PREPARING || importPhase == ImageImportPhase.SAVING
    val activeEntry: ImageThemeEntry?
        get() = recentEntries.firstOrNull { it.id == activeId }
}
