package com.yang136.sshhelper.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.yang136.sshhelper.settings.ImageThemeVariant
import com.yang136.sshhelper.settings.coerceImageOverlayStrength
import com.yang136.sshhelper.settings.defaultImageOverlayStrength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ImageThemeRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val directory = File(appContext.filesDir, DIRECTORY_NAME)
    private val legacyTarget = File(directory, LEGACY_BACKGROUND_FILE_NAME)
    private val legacyTemporary = File(directory, "$LEGACY_BACKGROUND_FILE_NAME.tmp")
    private val legacyBackup = File(directory, "$LEGACY_BACKGROUND_FILE_NAME.bak")

    private val _state = MutableStateFlow(ImageThemeState())
    val state: StateFlow<ImageThemeState> = _state.asStateFlow()

    suspend fun initialize(
        legacyVariant: ImageThemeVariant,
        legacyOverlayStrength: Float,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            recoverInterruptedReplace(legacyTarget, legacyTemporary, legacyBackup)
            migrateLegacyBackground(legacyVariant, legacyOverlayStrength)

            val storedEntries = loadCatalog().sortedByDescending(ImageThemeEntry::importedAtEpochMs)
            val requestedActiveId = prefs.getString(KEY_ACTIVE_ID, null)
            val loadedEntries = mutableListOf<ImageThemeEntry>()
            var requestedBitmap: Bitmap? = null

            storedEntries.take(MAX_RECENT_IMAGES).forEach { stored ->
                val file = File(directory, stored.fileName)
                val decoded = file.takeIf(File::isFile)?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    ?: return@forEach
                val palette = if (stored.palette.algorithmVersion == IMAGE_THEME_ALGORITHM_VERSION) {
                    stored.palette
                } else {
                    ImagePaletteExtractor.extract(decoded)
                }
                val thumbnail = createThumbnail(decoded)
                loadedEntries += stored.copy(palette = palette, thumbnail = thumbnail)
                if (stored.id == requestedActiveId) {
                    requestedBitmap = decoded
                } else if (thumbnail !== decoded) {
                    decoded.recycle()
                }
            }

            val activeId = requestedActiveId?.takeIf { id -> loadedEntries.any { it.id == id } }
                ?: loadedEntries.firstOrNull()?.id
            val activeBitmap = requestedBitmap ?: activeId?.let { id ->
                loadedEntries.firstOrNull { it.id == id }
                    ?.let { BitmapFactory.decodeFile(File(directory, it.fileName).absolutePath) }
            }
            val activeEntry = loadedEntries.firstOrNull { it.id == activeId }

            saveCatalog(loadedEntries, activeId)
            cleanupOrphanFiles(loadedEntries)
            _state.value = ImageThemeState(
                bitmap = activeBitmap,
                palette = activeEntry?.palette,
                activeId = activeId,
                recentEntries = loadedEntries,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            // Corrupt legacy metadata or an unreadable cached image must not crash app startup.
            _state.value = ImageThemeState(
                importPhase = ImageImportPhase.ERROR,
                errorMessage = error.message ?: "图片主题数据无法恢复，已暂时停用",
            )
        }
    }

    suspend fun prepareCrop(uri: Uri, targetAspectRatio: Float): Boolean = withContext(Dispatchers.IO) {
        if (_state.value.isImporting) return@withContext false
        _state.value = _state.value.copy(
            importPhase = ImageImportPhase.PREPARING,
            cropDraft = null,
            errorMessage = null,
        )
        runCatching {
            require(targetAspectRatio in 0.3f..3f) { "无法确定裁剪比例" }
            val descriptorLength = appContext.contentResolver
                .openAssetFileDescriptor(uri, "r")
                ?.use { it.length }
                ?: -1L
            require(descriptorLength <= 0L || descriptorLength <= MAX_SOURCE_BYTES) {
                "图片文件不能超过 50 MB"
            }
            val bitmap = decodeOrientedBitmap(uri, WORKING_MAX_EDGE_PX, WORKING_MAX_PIXELS)
            _state.value = _state.value.copy(
                importPhase = ImageImportPhase.EDITING,
                cropDraft = ImageCropDraft(uri, bitmap, targetAspectRatio),
                errorMessage = null,
            )
            true
        }.getOrElse { error ->
            publishError(error.message ?: "无法读取这张图片")
            false
        }
    }

    suspend fun commitCrop(focal: ImageFocalTransform): ImageThemeEntry? = withContext(Dispatchers.IO) {
        val draft = _state.value.cropDraft ?: return@withContext null
        _state.value = _state.value.copy(importPhase = ImageImportPhase.SAVING, errorMessage = null)
        runCatching {
            val outputBitmap = scaleToMaximumEdgePreservingSource(draft.bitmap, MAX_BACKGROUND_EDGE_PX)
            val palette = ImagePaletteExtractor.extract(outputBitmap)
            val id = UUID.randomUUID().toString()
            val fileName = "$BACKGROUND_FILE_PREFIX$id.jpg"
            val temporary = File(directory, "$fileName.tmp")
            val target = File(directory, fileName)

            directory.mkdirs()
            FileOutputStream(temporary).use { output ->
                check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "无法保存处理后的背景图片"
                }
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "无法发布背景图片" }

            val variant = palette.recommendedVariant
            val entry = ImageThemeEntry(
                id = id,
                fileName = fileName,
                palette = palette,
                variant = variant,
                overlayStrength = defaultImageOverlayStrength(variant),
                importedAtEpochMs = System.currentTimeMillis(),
                focusX = focal.focusX.coerceIn(0f, 1f),
                focusY = focal.focusY.coerceIn(0f, 1f),
                zoom = focal.zoom.coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM),
                thumbnail = createThumbnail(outputBitmap),
            )
            val allEntries = listOf(entry) + _state.value.recentEntries.filterNot { it.id == id }
            val keptEntries = capRecentEntries(allEntries, MAX_RECENT_IMAGES)
            val evictedEntries = allEntries.drop(keptEntries.size)
            if (!saveCatalog(keptEntries, id)) {
                target.delete()
                error("无法保存图片历史")
            }
            evictedEntries.forEach { File(directory, it.fileName).delete() }

            _state.value = ImageThemeState(
                bitmap = outputBitmap,
                palette = palette,
                activeId = id,
                recentEntries = keptEntries,
            )
            entry
        }.getOrElse { error ->
            directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach(File::delete)
            publishError(error.message ?: "无法保存背景图片")
            null
        }
    }

    fun cancelCrop() {
        _state.value = _state.value.copy(
            importPhase = ImageImportPhase.IDLE,
            cropDraft = null,
            errorMessage = null,
        )
    }

    suspend fun select(entryId: String): ImageThemeEntry? = withContext(Dispatchers.IO) {
        val entry = _state.value.recentEntries.firstOrNull { it.id == entryId }
            ?: return@withContext null
        if (entry.id == _state.value.activeId && _state.value.bitmap != null) return@withContext entry
        val bitmap = BitmapFactory.decodeFile(File(directory, entry.fileName).absolutePath)
        if (bitmap == null) {
            delete(entryId)
            publishError("这张背景图片已损坏，已从最近使用中移除")
            return@withContext _state.value.activeEntry
        }
        if (!saveCatalog(_state.value.recentEntries, entry.id)) {
            publishError("无法保存当前图片")
            return@withContext null
        }
        _state.value = _state.value.copy(
            bitmap = bitmap,
            palette = entry.palette,
            activeId = entry.id,
            importPhase = ImageImportPhase.IDLE,
            cropDraft = null,
            errorMessage = null,
        )
        entry
    }

    suspend fun delete(entryId: String): ImageThemeEntry? = withContext(Dispatchers.IO) {
        val current = _state.value
        val removed = current.recentEntries.firstOrNull { it.id == entryId }
            ?: return@withContext current.activeEntry
        val remaining = current.recentEntries.filterNot { it.id == entryId }
        val deletingActive = entryId == current.activeId
        val validRemaining = remaining.toMutableList()
        val preferredActiveId = nextActiveIdAfterDelete(
            newestFirstIds = remaining.map(ImageThemeEntry::id),
            activeId = current.activeId,
            deletedId = entryId,
        )
        var validActive = validRemaining.firstOrNull { it.id == preferredActiveId }
        var validBitmap = if (deletingActive) null else current.bitmap
        while (deletingActive && validActive != null && validBitmap == null) {
            validBitmap = BitmapFactory.decodeFile(File(directory, validActive.fileName).absolutePath)
            if (validBitmap == null) {
                File(directory, validActive.fileName).delete()
                validRemaining.removeAll { it.id == validActive?.id }
                validActive = validRemaining.firstOrNull()
            }
        }
        if (!saveCatalog(validRemaining, validActive?.id)) {
            publishError("无法更新图片历史")
            return@withContext current.activeEntry
        }
        File(directory, removed.fileName).delete()
        _state.value = ImageThemeState(
            bitmap = validBitmap,
            palette = validActive?.palette,
            activeId = validActive?.id,
            recentEntries = validRemaining.toList(),
        )
        validActive
    }

    fun updateActiveAppearance(variant: ImageThemeVariant, overlayStrength: Float) {
        val activeId = _state.value.activeId ?: return
        val strength = coerceImageOverlayStrength(overlayStrength)
        val updated = _state.value.recentEntries.map { entry ->
            if (entry.id == activeId) entry.copy(variant = variant, overlayStrength = strength) else entry
        }
        _state.value = _state.value.copy(recentEntries = updated)
        prefs.edit()
            .putString(entryKey(activeId, FIELD_VARIANT), variant.name)
            .putFloat(entryKey(activeId, FIELD_OVERLAY), strength)
            .apply()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        directory.listFiles()?.forEach(File::delete)
        prefs.edit().clear().commit()
        _state.value = ImageThemeState()
    }

    fun clearError() {
        _state.value = _state.value.copy(
            importPhase = if (_state.value.importPhase == ImageImportPhase.ERROR) {
                ImageImportPhase.IDLE
            } else {
                _state.value.importPhase
            },
            errorMessage = null,
        )
    }

    private fun decodeOrientedBitmap(uri: Uri, maximumEdge: Int, maximumPixels: Long): Bitmap {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: error("无法打开图片")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式无法识别" }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateWorkingInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                maximumEdge,
                maximumPixels,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("图片解码失败")
        val exif = runCatching { resolver.openInputStream(uri)?.use(::ExifInterface) }.getOrNull()
        val oriented = applyOrientation(
            decoded,
            rotationDegrees = exif?.rotationDegrees ?: 0,
            flipped = exif?.isFlipped == true,
        )
        if (oriented !== decoded) decoded.recycle()
        return scaleToLimits(oriented, maximumEdge, maximumPixels)
    }

    private fun applyOrientation(bitmap: Bitmap, rotationDegrees: Int, flipped: Boolean): Bitmap {
        if (rotationDegrees == 0 && !flipped) return bitmap
        val matrix = Matrix().apply {
            if (flipped) postScale(-1f, 1f)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun migrateLegacyBackground(variant: ImageThemeVariant, overlayStrength: Float) {
        if (prefs.contains(KEY_ENTRY_IDS) || !legacyTarget.isFile) return
        val bitmap = BitmapFactory.decodeFile(legacyTarget.absolutePath) ?: return
        val palette = loadLegacyPalette() ?: ImagePaletteExtractor.extract(bitmap)
        val id = UUID.randomUUID().toString()
        val fileName = "$BACKGROUND_FILE_PREFIX$id.jpg"
        val destination = File(directory, fileName)
        val modifiedAt = legacyTarget.lastModified()
        check(legacyTarget.renameTo(destination) || runCatching {
            legacyTarget.copyTo(destination, overwrite = true)
            legacyTarget.delete()
        }.isSuccess) { "无法迁移已有背景图片" }
        val entry = ImageThemeEntry(
            id = id,
            fileName = fileName,
            palette = palette,
            variant = variant,
            overlayStrength = coerceImageOverlayStrength(overlayStrength),
            importedAtEpochMs = modifiedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
        if (!saveCatalog(listOf(entry), id)) {
            destination.renameTo(legacyTarget)
            bitmap.recycle()
            error("无法迁移已有背景图片")
        }
        bitmap.recycle()
    }

    private fun loadCatalog(): List<ImageThemeEntry> {
        val ids = prefs.getString(KEY_ENTRY_IDS, null)
            ?.split(',')
            ?.filter(String::isNotBlank)
            .orEmpty()
        return ids.mapNotNull { id ->
            val fileName = prefs.getString(entryKey(id, FIELD_FILE_NAME), null) ?: return@mapNotNull null
            val palette = loadEntryPalette(id) ?: return@mapNotNull null
            ImageThemeEntry(
                id = id,
                fileName = fileName,
                palette = palette,
                variant = runCatching {
                    ImageThemeVariant.valueOf(
                        prefs.getString(entryKey(id, FIELD_VARIANT), null).orEmpty(),
                    )
                }.getOrDefault(palette.recommendedVariant),
                overlayStrength = coerceImageOverlayStrength(
                    prefs.getFloat(
                        entryKey(id, FIELD_OVERLAY),
                        defaultImageOverlayStrength(palette.recommendedVariant),
                    ),
                ),
                importedAtEpochMs = prefs.getLong(entryKey(id, FIELD_IMPORTED_AT), 0L),
                focusX = prefs.getFloat(entryKey(id, FIELD_FOCUS_X), 0.5f).finiteOr(0.5f).coerceIn(0f, 1f),
                focusY = prefs.getFloat(entryKey(id, FIELD_FOCUS_Y), 0.5f).finiteOr(0.5f).coerceIn(0f, 1f),
                zoom = prefs.getFloat(entryKey(id, FIELD_ZOOM), 1f).finiteOr(1f).coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM),
            )
        }
    }

    private fun saveCatalog(entries: List<ImageThemeEntry>, activeId: String?): Boolean {
        val oldIds = prefs.getString(KEY_ENTRY_IDS, null)
            ?.split(',')
            ?.filter(String::isNotBlank)
            .orEmpty()
        val editor = prefs.edit()
            .putString(KEY_ENTRY_IDS, entries.joinToString(",", transform = ImageThemeEntry::id))
        if (activeId == null) editor.remove(KEY_ACTIVE_ID) else editor.putString(KEY_ACTIVE_ID, activeId)
        oldIds.filterNot { old -> entries.any { it.id == old } }.forEach { id ->
            ENTRY_FIELDS.forEach { field -> editor.remove(entryKey(id, field)) }
        }
        entries.forEach { entry ->
            editor
                .putString(entryKey(entry.id, FIELD_FILE_NAME), entry.fileName)
                .putString(entryKey(entry.id, FIELD_VARIANT), entry.variant.name)
                .putFloat(entryKey(entry.id, FIELD_OVERLAY), entry.overlayStrength)
                .putLong(entryKey(entry.id, FIELD_IMPORTED_AT), entry.importedAtEpochMs)
                .putFloat(entryKey(entry.id, FIELD_FOCUS_X), entry.focusX)
                .putFloat(entryKey(entry.id, FIELD_FOCUS_Y), entry.focusY)
                .putFloat(entryKey(entry.id, FIELD_ZOOM), entry.zoom)
                .putInt(entryKey(entry.id, FIELD_VIBRANT), entry.palette.vibrantArgb)
                .putInt(entryKey(entry.id, FIELD_MUTED), entry.palette.mutedArgb)
                .putInt(entryKey(entry.id, FIELD_SECONDARY), entry.palette.secondaryArgb)
                .putInt(entryKey(entry.id, FIELD_NEUTRAL), entry.palette.neutralArgb)
                .putFloat(entryKey(entry.id, FIELD_AVERAGE_LUMINANCE), entry.palette.averageLuminance)
                .putString(entryKey(entry.id, FIELD_RECOMMENDED_VARIANT), entry.palette.recommendedVariant.name)
                .putInt(entryKey(entry.id, FIELD_ALGORITHM_VERSION), entry.palette.algorithmVersion)
        }
        LEGACY_PREF_KEYS.forEach(editor::remove)
        return editor.commit()
    }

    private fun loadEntryPalette(id: String): ImageThemePalette? {
        if (!prefs.contains(entryKey(id, FIELD_VIBRANT))) return null
        return ImageThemePalette(
            vibrantArgb = prefs.getInt(entryKey(id, FIELD_VIBRANT), 0xFF00E676.toInt()),
            mutedArgb = prefs.getInt(entryKey(id, FIELD_MUTED), 0xFF4F6B5A.toInt()),
            secondaryArgb = prefs.getInt(entryKey(id, FIELD_SECONDARY), 0xFF66BB6A.toInt()),
            neutralArgb = prefs.getInt(entryKey(id, FIELD_NEUTRAL), 0xFF151816.toInt()),
            averageLuminance = prefs.getFloat(entryKey(id, FIELD_AVERAGE_LUMINANCE), 0.1f),
            recommendedVariant = runCatching {
                ImageThemeVariant.valueOf(
                    prefs.getString(entryKey(id, FIELD_RECOMMENDED_VARIANT), null).orEmpty(),
                )
            }.getOrDefault(ImageThemeVariant.IMMERSIVE),
            algorithmVersion = prefs.getInt(entryKey(id, FIELD_ALGORITHM_VERSION), 0),
        )
    }

    private fun loadLegacyPalette(): ImageThemePalette? {
        if (!prefs.contains(LEGACY_KEY_VIBRANT)) return null
        if (prefs.getLong(LEGACY_KEY_FILE_LENGTH, -1L) != legacyTarget.length()) return null
        return ImageThemePalette(
            vibrantArgb = prefs.getInt(LEGACY_KEY_VIBRANT, 0xFF00E676.toInt()),
            mutedArgb = prefs.getInt(LEGACY_KEY_MUTED, 0xFF4F6B5A.toInt()),
            secondaryArgb = prefs.getInt(LEGACY_KEY_SECONDARY, 0xFF66BB6A.toInt()),
            neutralArgb = prefs.getInt(LEGACY_KEY_NEUTRAL, 0xFF151816.toInt()),
            averageLuminance = prefs.getFloat(LEGACY_KEY_AVERAGE_LUMINANCE, 0.1f),
            recommendedVariant = runCatching {
                ImageThemeVariant.valueOf(prefs.getString(LEGACY_KEY_RECOMMENDED_VARIANT, null).orEmpty())
            }.getOrDefault(ImageThemeVariant.IMMERSIVE),
            algorithmVersion = prefs.getInt(LEGACY_KEY_ALGORITHM_VERSION, 0),
        )
    }

    private fun cleanupOrphanFiles(entries: List<ImageThemeEntry>) {
        val validNames = entries.mapTo(mutableSetOf(), ImageThemeEntry::fileName)
        directory.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp") ||
                file.name.endsWith(".bak") ||
                file.name.startsWith(BACKGROUND_FILE_PREFIX) && file.name !in validNames
            ) {
                file.delete()
            }
        }
    }

    private fun publishError(message: String) {
        _state.value = _state.value.copy(
            importPhase = ImageImportPhase.ERROR,
            cropDraft = null,
            errorMessage = message,
        )
    }

    companion object {
        const val MAX_BACKGROUND_EDGE_PX = 1920
        const val WORKING_MAX_EDGE_PX = 4096
        const val WORKING_MAX_PIXELS = 12_000_000L
        const val MAX_RECENT_IMAGES = 3
        const val MAX_SOURCE_BYTES = 50L * 1024L * 1024L
        const val JPEG_QUALITY = 88
        private const val PREFS_NAME = "image_theme"
        private const val DIRECTORY_NAME = "image-theme"
        private const val LEGACY_BACKGROUND_FILE_NAME = "background.jpg"
        private const val BACKGROUND_FILE_PREFIX = "background-"
        private const val KEY_ENTRY_IDS = "entryIds"
        private const val KEY_ACTIVE_ID = "activeId"
        private const val FIELD_FILE_NAME = "fileName"
        private const val FIELD_VARIANT = "variant"
        private const val FIELD_OVERLAY = "overlay"
        private const val FIELD_IMPORTED_AT = "importedAt"
        private const val FIELD_FOCUS_X = "focusX"
        private const val FIELD_FOCUS_Y = "focusY"
        private const val FIELD_ZOOM = "zoom"
        private const val FIELD_VIBRANT = "vibrant"
        private const val FIELD_MUTED = "muted"
        private const val FIELD_SECONDARY = "secondary"
        private const val FIELD_NEUTRAL = "neutral"
        private const val FIELD_AVERAGE_LUMINANCE = "averageLuminance"
        private const val FIELD_RECOMMENDED_VARIANT = "recommendedVariant"
        private const val FIELD_ALGORITHM_VERSION = "algorithmVersion"
        private val ENTRY_FIELDS = listOf(
            FIELD_FILE_NAME, FIELD_VARIANT, FIELD_OVERLAY, FIELD_IMPORTED_AT,
            FIELD_FOCUS_X, FIELD_FOCUS_Y, FIELD_ZOOM,
            FIELD_VIBRANT, FIELD_MUTED, FIELD_SECONDARY, FIELD_NEUTRAL,
            FIELD_AVERAGE_LUMINANCE, FIELD_RECOMMENDED_VARIANT, FIELD_ALGORITHM_VERSION,
        )
        private const val LEGACY_KEY_VIBRANT = "vibrant"
        private const val LEGACY_KEY_MUTED = "muted"
        private const val LEGACY_KEY_SECONDARY = "secondary"
        private const val LEGACY_KEY_NEUTRAL = "neutral"
        private const val LEGACY_KEY_AVERAGE_LUMINANCE = "averageLuminance"
        private const val LEGACY_KEY_RECOMMENDED_VARIANT = "recommendedVariant"
        private const val LEGACY_KEY_ALGORITHM_VERSION = "algorithmVersion"
        private const val LEGACY_KEY_FILE_LENGTH = "fileLength"
        private val LEGACY_PREF_KEYS = listOf(
            LEGACY_KEY_VIBRANT, LEGACY_KEY_MUTED, LEGACY_KEY_SECONDARY,
            LEGACY_KEY_NEUTRAL, LEGACY_KEY_AVERAGE_LUMINANCE,
            LEGACY_KEY_RECOMMENDED_VARIANT, LEGACY_KEY_ALGORITHM_VERSION,
            LEGACY_KEY_FILE_LENGTH,
        )
    }
}

private fun entryKey(id: String, field: String): String = "entry.$id.$field"

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

internal fun calculateInSampleSize(width: Int, height: Int, maximumEdge: Int): Int =
    generateSequence(1) { it * 2 }
        .takeWhile { sample ->
            width / (sample * 2) >= maximumEdge || height / (sample * 2) >= maximumEdge
        }
        .lastOrNull()
        ?.times(2)
        ?: 1

internal fun calculateWorkingInSampleSize(
    width: Int,
    height: Int,
    maximumEdge: Int,
    maximumPixels: Long,
): Int {
    var sample = 1
    while (width / (sample * 2) >= 1 && height / (sample * 2) >= 1 &&
        (width / sample > maximumEdge || height / sample > maximumEdge ||
            width.toLong() * height / sample / sample > maximumPixels)
    ) {
        sample *= 2
    }
    return sample
}

internal fun cropPixelBounds(
    bitmapWidth: Int,
    bitmapHeight: Int,
    selection: CropSelection,
): IntArray {
    require(selection.width > 0f && selection.height > 0f) { "裁剪区域无效" }
    val left = (selection.left.coerceIn(0f, 1f) * bitmapWidth).roundToInt()
        .coerceIn(0, bitmapWidth - 1)
    val top = (selection.top.coerceIn(0f, 1f) * bitmapHeight).roundToInt()
        .coerceIn(0, bitmapHeight - 1)
    val right = ((selection.left + selection.width).coerceIn(0f, 1f) * bitmapWidth).roundToInt()
        .coerceIn(left + 1, bitmapWidth)
    val bottom = ((selection.top + selection.height).coerceIn(0f, 1f) * bitmapHeight).roundToInt()
        .coerceIn(top + 1, bitmapHeight)
    return intArrayOf(left, top, right, bottom)
}

internal fun <T> capRecentEntries(entries: List<T>, maximum: Int = 3): List<T> {
    require(maximum > 0)
    return entries.take(maximum)
}

internal fun nextActiveIdAfterDelete(
    newestFirstIds: List<String>,
    activeId: String?,
    deletedId: String,
): String? = when {
    deletedId != activeId -> activeId?.takeIf { it in newestFirstIds && it != deletedId }
    else -> newestFirstIds.firstOrNull { it != deletedId }
}

private fun cropBitmap(bitmap: Bitmap, selection: CropSelection): Bitmap {
    val bounds = cropPixelBounds(bitmap.width, bitmap.height, selection)
    return Bitmap.createBitmap(
        bitmap,
        bounds[0],
        bounds[1],
        bounds[2] - bounds[0],
        bounds[3] - bounds[1],
    )
}

private fun scaleToLimits(bitmap: Bitmap, maximumEdge: Int, maximumPixels: Long): Bitmap {
    val edgeScale = maximumEdge.toFloat() / maxOf(bitmap.width, bitmap.height)
    val pixelScale = sqrt(maximumPixels.toDouble() / (bitmap.width.toLong() * bitmap.height)).toFloat()
    val scale = minOf(1f, edgeScale, pixelScale)
    if (scale >= 1f) return bitmap
    val scaled = Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).roundToInt().coerceAtLeast(1),
        (bitmap.height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}

private fun scaleToMaximumEdge(bitmap: Bitmap, maximumEdge: Int): Bitmap =
    scaleToLimits(bitmap, maximumEdge, Long.MAX_VALUE)

private fun scaleToMaximumEdgePreservingSource(bitmap: Bitmap, maximumEdge: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maximumEdge) return bitmap
    val scale = maximumEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).roundToInt().coerceAtLeast(1),
        (bitmap.height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
}

private fun createThumbnail(bitmap: Bitmap): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= 256) return bitmap
    val scale = 256f / longest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).roundToInt().coerceAtLeast(1),
        (bitmap.height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
}

internal fun safeReplace(temporary: File, target: File, backup: File): Boolean {
    backup.delete()
    val hadTarget = target.exists()
    if (hadTarget && !target.renameTo(backup)) return false
    if (temporary.renameTo(target)) {
        backup.delete()
        return true
    }
    if (hadTarget) backup.renameTo(target)
    return false
}

internal fun recoverInterruptedReplace(target: File, temporary: File, backup: File) {
    when {
        target.exists() -> {
            temporary.delete()
            backup.delete()
        }
        temporary.exists() -> {
            if (!temporary.renameTo(target) && backup.exists()) backup.renameTo(target)
        }
        backup.exists() -> backup.renameTo(target)
    }
}
