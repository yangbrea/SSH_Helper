package com.yang136.sshhelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yang136.sshhelper.theme.*

@Composable
fun ImageCropScreen(
    draft: ImageCropDraft,
    saving: Boolean,
    onCancel: () -> Unit,
    onConfirm: (ImageFocalTransform) -> Unit,
) {
    val saver = remember { Saver<CropTransform, List<Float>>(save = { listOf(it.zoom, it.offsetX, it.offsetY) }, restore = { CropTransform(it[0], it[1], it[2]) }) }
    var transform by rememberSaveable(draft.bitmap, stateSaver = saver) { mutableStateOf(CropTransform()) }
    var geometry by remember(draft.bitmap) { mutableStateOf<CropGeometry?>(null) }
    BackHandler(enabled = !saving, onBack = onCancel)
    Column(Modifier.fillMaxSize().background(Color(0xFF080A0D)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel, enabled = !saving) { Icon(Icons.Default.Close, "取消裁剪", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("调整背景构图", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("拖动图片，双指或滑杆缩放", color = Color.White.copy(alpha = .68f), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { transform = CropTransform() }, enabled = !saving && transform != CropTransform()) {
                Icon(Icons.Default.Refresh, null)
                Text("重置")
            }
        }
        CropPreview(draft, transform, { transform = it }, { geometry = it }, Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ZoomOut, null, tint = Color.White.copy(alpha = .76f))
            Slider(
                value = transform.zoom,
                onValueChange = { requested -> geometry?.let { transform = updateCropTransform(transform, it, requested / transform.zoom) } },
                valueRange = MIN_CROP_ZOOM..MAX_CROP_ZOOM,
                enabled = !saving,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Icon(Icons.Default.ZoomIn, null, tint = Color.White.copy(alpha = .76f))
            Text("${"%.1f".format(transform.zoom)}×", color = Color.White, modifier = Modifier.padding(start = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("取消") }
            Button(onClick = { geometry?.let { onConfirm(focalTransform(transform, it)) } }, enabled = !saving && geometry != null, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("使用此构图") }
        }
    }
    if (saving) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(14.dp))
            Text("正在保存并生成配色…", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CropPreview(
    draft: ImageCropDraft,
    transform: CropTransform,
    onTransform: (CropTransform) -> Unit,
    onGeometry: (CropGeometry) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val availableAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        val viewport = if (availableAspect > draft.targetAspectRatio) Modifier.fillMaxHeight().aspectRatio(draft.targetAspectRatio)
        else Modifier.fillMaxWidth().aspectRatio(draft.targetAspectRatio)
        CropViewport(draft, transform, onTransform, onGeometry, viewport)
    }
}

@Composable
private fun CropViewport(
    draft: ImageCropDraft,
    transform: CropTransform,
    onTransform: (CropTransform) -> Unit,
    onGeometry: (CropGeometry) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    var viewportSize by remember(draft.bitmap) { mutableStateOf(IntSize.Zero) }
    val geometry = remember(draft.bitmap, viewportSize) {
        viewportSize.takeIf { it.width > 0 && it.height > 0 }?.let {
            CropGeometry(draft.bitmap.width.toFloat(), draft.bitmap.height.toFloat(), it.width.toFloat(), it.height.toFloat())
        }
    }
    LaunchedEffect(geometry) { geometry?.let(onGeometry) }
    val currentTransform by rememberUpdatedState(transform)
    val currentGeometry by rememberUpdatedState(geometry)
    val gestures = rememberTransformableState { zoom, pan, _ ->
        currentGeometry?.let { onTransform(updateCropTransform(currentTransform, it, zoom, pan.x, pan.y)) }
    }
    Box(
        modifier.onSizeChanged { viewportSize = it }.clipToBounds().background(Color.Black)
            .border(2.dp, MaterialTheme.colorScheme.primary).transformable(gestures)
            .semantics { contentDescription = "图片裁剪区域，可拖动并双指缩放" },
        contentAlignment = Alignment.Center,
    ) {
        geometry?.let {
            val renderedWidth = it.imageWidth * it.coverScale * transform.zoom
            val renderedHeight = it.imageHeight * it.coverScale * transform.zoom
            Image(
                bitmap = draft.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.requiredSize(with(density) { renderedWidth.toDp() }, with(density) { renderedHeight.toDp() })
                    .graphicsLayer { translationX = transform.offsetX; translationY = transform.offsetY },
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val grid = Color.White.copy(alpha = .52f)
            drawLine(grid, androidx.compose.ui.geometry.Offset(size.width / 3f, 0f), androidx.compose.ui.geometry.Offset(size.width / 3f, size.height), 1.dp.toPx())
            drawLine(grid, androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, 0f), androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, size.height), 1.dp.toPx())
            drawLine(grid, androidx.compose.ui.geometry.Offset(0f, size.height / 3f), androidx.compose.ui.geometry.Offset(size.width, size.height / 3f), 1.dp.toPx())
            drawLine(grid, androidx.compose.ui.geometry.Offset(0f, size.height * 2f / 3f), androidx.compose.ui.geometry.Offset(size.width, size.height * 2f / 3f), 1.dp.toPx())
        }
    }
}
