package com.yang136.sshhelper.ui

import com.yang136.sshhelper.terminal.GhosttyNativeBridge
import com.yang136.sshhelper.terminal.GhosttyRenderSnapshot
import com.yang136.sshhelper.terminal.RenderSnapshotDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns all native Ghostty calls on a dedicated single-thread executor.
 *
 * The View never calls JNI directly. Decoded [GhosttyRenderSnapshot] deltas
 * are published to the frontend, which merges them on the main thread.
 */
internal class GhosttyNativeEngine(
    private val onPtyWrite: (ByteArray) -> Unit,
) {
    var onBell: (() -> Unit)? = null
    var onTitleChange: ((String) -> Unit)? = null
    var onPwdChange: ((String) -> Unit)? = null
    @Volatile
    var onSnapshotReady: ((GhosttyRenderSnapshot) -> Unit)? = null
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GhosttyEngine").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val engineJob = SupervisorJob()
    private val scope = CoroutineScope(engineJob + dispatcher)

    @Volatile
    private var closing = false

    @Volatile
    private var handle: Long = 0L

    /** Whether the active terminal app has enabled any mouse reporting mode. */
    @Volatile
    var mouseReportingActive: Boolean = false
        private set

    @Volatile
    private var pendingResize: ResizeRequest? = null
    private var resizeJob: Job? = null

    private var buffer = ByteBuffer
        .allocateDirect(INITIAL_BUFFER_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)

    fun start(cols: Int, rows: Int) {
        scope.launch {
            if (handle == 0L) {
                handle = GhosttyNativeBridge.nativeCreateManaged(cols, rows)
                updateMouseReportingState()
                pendingResize?.let { resize ->
                    pendingResize = null
                    applyResize(resize)
                }
                refreshSnapshot()
            }
        }
    }

    suspend fun write(data: ByteArray) {
        if (data.isEmpty()) return
        withContext(dispatcher) {
            if (handle == 0L) return@withContext
            GhosttyNativeBridge.nativeWrite(handle, data)
            updateMouseReportingState()
            drainPtyWrites()
            drainEvents()
            refreshSnapshot()
        }
    }

    suspend fun reset() {
        withContext(dispatcher) {
            if (handle == 0L) return@withContext
            GhosttyNativeBridge.nativeReset(handle)
            updateMouseReportingState()
            refreshSnapshot()
        }
    }

    fun requestResize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        pendingResize = ResizeRequest(cols, rows, cellWidthPx, cellHeightPx)
        resizeJob?.cancel()
        resizeJob = scope.launch {
            if (handle == 0L) return@launch
            val resize = pendingResize ?: return@launch
            pendingResize = null
            applyResize(resize)
            refreshSnapshot()
        }
    }

    fun requestSearchSet(query: String, backwards: Boolean, onResult: (Int, Int) -> Unit) {
        if (handle == 0L) {
            onResult(-1, 0)
            return
        }
        scope.launch {
            if (handle == 0L) {
                onResult(-1, 0)
                return@launch
            }
            val bytes = query.takeIf { it.isNotEmpty() }?.encodeToByteArray()
            val total = GhosttyNativeBridge.nativeSearchSet(handle, bytes)
            val index = if (total > 0) {
                GhosttyNativeBridge.nativeSearchSelect(handle, backwards)
            } else {
                -1
            }
            refreshSnapshot()
            onResult(index, total)
        }
    }

    fun requestSearchSelect(backwards: Boolean, onResult: (Int, Int) -> Unit) {
        if (handle == 0L) {
            onResult(-1, 0)
            return
        }
        scope.launch {
            if (handle == 0L) {
                onResult(-1, 0)
                return@launch
            }
            val index = GhosttyNativeBridge.nativeSearchSelect(handle, backwards)
            val total = GhosttyNativeBridge.nativeSearchTotal(handle)
            refreshSnapshot()
            onResult(index, total)
        }
    }

    fun requestKeyEvent(
        action: Int,
        keyCode: Int,
        mods: Int,
        unshiftedCodepoint: Int,
        utf8: ByteArray?,
    ) {
        scope.launch {
            if (handle == 0L) return@launch
            val bytes = GhosttyNativeBridge.nativeEncodeKey(
                handle, action, keyCode, mods, unshiftedCodepoint, utf8,
            )
            if (bytes != null && bytes.isNotEmpty()) onPtyWrite(bytes)
        }
    }

    fun requestMouseEvent(
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
    ) {
        scope.launch {
            if (handle == 0L) return@launch
            val bytes = GhosttyNativeBridge.nativeEncodeMouse(
                handle, action, button, mods, x, y, anyButtonPressed,
            )
            if (bytes != null && bytes.isNotEmpty()) onPtyWrite(bytes)
        }
    }

    fun requestSearchClear() {
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativeSearchClear(handle)
            refreshSnapshot()
        }
    }

    fun requestSelectAll(onResult: (Boolean) -> Unit) {
        scope.launch {
            if (handle == 0L) {
                onResult(false)
                return@launch
            }
            val selected = GhosttyNativeBridge.nativeSelectAll(handle)
            refreshSnapshot()
            onResult(selected)
        }
    }

    fun requestCopySelection(onResult: (ByteArray?) -> Unit) {
        if (handle == 0L) {
            onResult(null)
            return
        }
        scope.launch {
            val bytes = if (handle == 0L) null else GhosttyNativeBridge.nativeCopySelection(handle)
            onResult(bytes)
        }
    }

    fun requestSelectionPress(col: Int, row: Int) {
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativeSelectionPress(handle, col, row)
            refreshSnapshot()
        }
    }

    fun requestSelectionDrag(col: Int, row: Int) {
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativeSelectionDrag(handle, col, row)
            refreshSnapshot()
        }
    }

    fun requestSelectionRelease(col: Int, row: Int) {
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativeSelectionRelease(handle, col, row)
            refreshSnapshot()
        }
    }

    fun requestSelectionClear() {
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativeSelectionClear(handle)
            refreshSnapshot()
        }
    }

    fun requestLinkUriAt(col: Int, row: Int, onResult: (String?) -> Unit) {
        if (handle == 0L) {
            onResult(null)
            return
        }
        scope.launch {
            val bytes = if (handle == 0L) null else GhosttyNativeBridge.nativeLinkUriAt(handle, col, row)
            onResult(bytes?.decodeToString())
        }
    }

    fun requestScrollViewport(deltaRows: Int) {
        if (deltaRows == 0) return
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativeScrollViewport(handle, deltaRows)
            refreshSnapshot()
        }
    }

    fun requestPasteText(text: String) {
        if (text.isEmpty()) return
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativePasteText(handle, text.encodeToByteArray())
            drainPtyWrites()
            drainEvents()
            refreshSnapshot()
        }
    }

    fun requestSetDefaultColors(backgroundArgb: Int, foregroundArgb: Int, cursorArgb: Int) {
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativeSetDefaultColors(
                handle,
                backgroundArgb,
                foregroundArgb,
                cursorArgb,
            )
            refreshSnapshot()
        }
    }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            if (handle != 0L) {
                GhosttyNativeBridge.nativeFreeManaged(handle)
                handle = 0L
            }
            mouseReportingActive = false
            engineJob.cancel()
            dispatcher.close()
            executor.shutdown()
        }
    }

    private fun updateMouseReportingState() {
        mouseReportingActive = handle != 0L && GhosttyNativeBridge.nativeMouseReportingActive(handle)
    }

    private fun drainPtyWrites() {
        val response = GhosttyNativeBridge.nativeDrainPtyWrites(handle)
        if (response != null && response.isNotEmpty()) {
            onPtyWrite(response)
        }
    }

    private fun refreshSnapshot() {
        while (true) {
            buffer.clear()
            val rowCount = GhosttyNativeBridge.nativeRenderSnapshot(handle, buffer)
            if (rowCount >= 0) {
                buffer.clear()
                val snapshot = RenderSnapshotDecoder.decode(buffer, buffer.capacity()) ?: return
                onSnapshotReady?.invoke(snapshot)
                return
            }
            // Native does not clean dirty state on -1, so retry with a larger
            // buffer is safe.
            if (buffer.capacity() >= MAX_BUFFER_BYTES) return
            val nextCapacity = buffer.capacity() * 2
            buffer = ByteBuffer.allocateDirect(nextCapacity).order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    private fun applyResize(resize: ResizeRequest) {
        GhosttyNativeBridge.nativeResize(
            handle,
            resize.cols,
            resize.rows,
            resize.cellWidthPx,
            resize.cellHeightPx,
        )
    }

    private fun drainEvents() {
        val flags = GhosttyNativeBridge.nativeTakeEventFlags(handle)
        if (flags == 0) return
        if (flags and EVENT_BELL != 0) onBell?.invoke()
        if (flags and EVENT_TITLE != 0) {
            val title = GhosttyNativeBridge.nativeGetTitle(handle)?.decodeToString()
            if (!title.isNullOrEmpty()) onTitleChange?.invoke(title)
        }
        if (flags and EVENT_PWD != 0) {
            val pwd = GhosttyNativeBridge.nativeGetPwd(handle)?.decodeToString()
            if (!pwd.isNullOrEmpty()) onPwdChange?.invoke(pwd)
        }
    }

    private companion object {
        const val EVENT_BELL = 1
        const val EVENT_TITLE = 1 shl 1
        const val EVENT_PWD = 1 shl 2

        const val INITIAL_BUFFER_BYTES = 1 shl 20
        const val MAX_BUFFER_BYTES = 64 shl 20
    }

    private data class ResizeRequest(
        val cols: Int,
        val rows: Int,
        val cellWidthPx: Int,
        val cellHeightPx: Int,
    )
}
