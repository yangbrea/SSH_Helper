package com.yang136.sshhelper.ui

import com.yang136.sshhelper.terminal.GhosttyNativeBridge
import com.yang136.sshhelper.terminal.GhosttyRenderSnapshot
import com.yang136.sshhelper.terminal.RenderSnapshotDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns all native Ghostty calls on a dedicated single-thread executor.
 *
 * The View never calls JNI directly: it reads the latest immutable
 * [GhosttyRenderSnapshot] produced by this engine.
 */
internal class GhosttyNativeEngine(
    private val onPtyWrite: (ByteArray) -> Unit,
) {
    var onBell: (() -> Unit)? = null
    var onTitleChange: ((String) -> Unit)? = null
    var onPwdChange: ((String) -> Unit)? = null
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GhosttyEngine").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var latestSnapshot: GhosttyRenderSnapshot? = null

    private var buffer = ByteBuffer
        .allocateDirect(INITIAL_BUFFER_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)

    fun start(cols: Int, rows: Int) {
        scope.launch {
            if (handle == 0L) {
                handle = GhosttyNativeBridge.nativeCreateManaged(cols, rows)
                refreshSnapshot()
            }
        }
    }

    suspend fun write(data: ByteArray) {
        if (data.isEmpty() || handle == 0L) return
        withContext(dispatcher) {
            if (handle == 0L) return@withContext
            GhosttyNativeBridge.nativeWrite(handle, data)
            drainPtyWrites()
            drainEvents()
            refreshSnapshot()
        }
    }

    suspend fun reset() {
        withContext(dispatcher) {
            if (handle == 0L) return@withContext
            GhosttyNativeBridge.nativeReset(handle)
            refreshSnapshot()
        }
    }

    fun requestResize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        if (handle == 0L) return
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativeResize(handle, cols, rows, cellWidthPx, cellHeightPx)
            refreshSnapshot()
        }
    }

    fun requestSearchSet(query: String, onResult: (Int) -> Unit) {
        if (handle == 0L) {
            onResult(0)
            return
        }
        scope.launch {
            if (handle == 0L) {
                onResult(0)
                return@launch
            }
            val bytes = query.takeIf { it.isNotEmpty() }?.encodeToByteArray()
            val total = GhosttyNativeBridge.nativeSearchSet(handle, bytes)
            refreshSnapshot()
            onResult(total)
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
        if (handle == 0L) return
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
        if (handle == 0L) return
        scope.launch {
            if (handle == 0L) return@launch
            val bytes = GhosttyNativeBridge.nativeEncodeMouse(
                handle, action, button, mods, x, y, anyButtonPressed,
            )
            if (bytes != null && bytes.isNotEmpty()) onPtyWrite(bytes)
        }
    }

    fun requestSearchClear() {
        if (handle == 0L) return
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativeSearchClear(handle)
            refreshSnapshot()
        }
    }

    fun requestSelectAll() {
        if (handle == 0L) return
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativeSelectAll(handle)
            refreshSnapshot()
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

    fun requestScrollViewport(deltaRows: Int) {
        if (handle == 0L || deltaRows == 0) return
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativeScrollViewport(handle, deltaRows)
            refreshSnapshot()
        }
    }

    fun requestPasteText(text: String) {
        if (handle == 0L || text.isEmpty()) return
        scope.launch {
            if (handle == 0L) return@launch
            GhosttyNativeBridge.nativePasteText(handle, text.encodeToByteArray())
            drainPtyWrites()
            drainEvents()
            refreshSnapshot()
        }
    }

    fun requestSetDefaultColors(backgroundArgb: Int, foregroundArgb: Int, cursorArgb: Int) {
        if (handle == 0L) return
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

    fun latestSnapshot(): GhosttyRenderSnapshot? = latestSnapshot

    fun close() {
        scope.launch {
            if (handle != 0L) {
                GhosttyNativeBridge.nativeFreeManaged(handle)
                handle = 0L
            }
            latestSnapshot = null
            executor.shutdown()
        }
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
                latestSnapshot = RenderSnapshotDecoder.decode(buffer, buffer.capacity())
                return
            }
            // Native does not clean dirty state on -1, so retry with a larger
            // buffer is safe.
            if (buffer.capacity() >= MAX_BUFFER_BYTES) return
            val nextCapacity = buffer.capacity() * 2
            buffer = ByteBuffer.allocateDirect(nextCapacity).order(ByteOrder.LITTLE_ENDIAN)
        }
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
}
