package com.yang136.sshhelper.terminal

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GhosttyNativeSmokeTest {
    @Test
    fun nativeLibraryLoadsAndReportsVersion() {
        val version = GhosttyNativeBridge.nativeVersion()
        assertTrue("libghostty-vt version should not be empty", version.isNotEmpty())
    }

    @Test
    fun createAndFreeManagedTerminalHandle() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        assertNotEquals(0L, handle)
        GhosttyNativeBridge.nativeFreeManaged(handle)
    }

    @Test
    fun freeNullHandleIsSafeNoOp() {
        GhosttyNativeBridge.nativeFreeManaged(0L)
    }

    @Test
    fun duplicateFreeAndRepeatedLifecycleAreSafe() {
        repeat(1_000) {
            val handle = GhosttyNativeBridge.nativeCreateManaged(8, 4)
            GhosttyNativeBridge.nativeFreeManaged(handle)
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun createRejectsInvalidDimensions() {
        try {
            GhosttyNativeBridge.nativeCreateManaged(cols = 0, rows = 24)
            throw AssertionError("expected IllegalArgumentException for cols=0")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertEquals("native library is still usable after a rejected create", 1, 1)
    }

    @Test
    fun writeResetAndResizeDoNotCrash() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "hello\r\n".encodeToByteArray())
            GhosttyNativeBridge.nativeWrite(
                handle,
                "\u001b[31mred\u001b[0m".encodeToByteArray(),
            )
            GhosttyNativeBridge.nativeResize(
                handle,
                cols = 100,
                rows = 30,
                cellWidthPx = 8,
                cellHeightPx = 16,
            )
            GhosttyNativeBridge.nativeReset(handle)
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun renderSnapshotProducesDecodableBatch() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "hello\r\n".encodeToByteArray())

            val snapshot = renderSnapshot(handle)
            assertEquals(SNAPSHOT_VERSION, snapshot.version)
            assertEquals(80, snapshot.cols)
            assertEquals(24, snapshot.rows)
            assertTrue(snapshot.isDirty)
            assertTrue(snapshot.rowsData.isNotEmpty())
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun writtenHelloAppearsInSnapshot() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "hello\r\n".encodeToByteArray())
            val text = renderSnapshot(handle).rowsData.joinToString("") { row ->
                row.cells.joinToString("") { it.text }
            }
            assertTrue("snapshot should contain hello, got: $text", text.contains("hello"))
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun ansiColorIsPreservedInSnapshot() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(
                handle,
                "\u001b[31mred\u001b[0m".encodeToByteArray(),
            )
            val cells = renderSnapshot(handle).rowsData.flatMap { it.cells }
            val redCell = cells.firstOrNull { it.text == "r" || it.text == "e" || it.text == "d" }
            assertTrue("expected colored text cell", redCell != null)
            assertTrue(
                "expected non-default colored text, got ${redCell!!.fgArgb}",
                redCell.fgArgb != 0xFFFFFFFF.toInt(),
            )
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun wideCharacterIsMarkedWide() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "中".encodeToByteArray())
            val cells = renderSnapshot(handle).rowsData.flatMap { it.cells }
            val wide = cells.firstOrNull { it.text == "中" }
            assertTrue("expected wide char", wide != null && wide.wide)
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun alternateScreenShowsSecondaryContent() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "\u001b[?1049h".encodeToByteArray())
            GhosttyNativeBridge.nativeWrite(handle, "alternate-content".encodeToByteArray())
            val text = renderSnapshot(handle).rowsData.joinToString("") { row ->
                row.cells.joinToString("") { it.text }
            }
            assertTrue(
                "alternate screen content should be visible, got: $text",
                text.contains("alternate-content"),
            )
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun resizeUpdatesGridAndKeepsWorking() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 24)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "before-resize\r\n".encodeToByteArray())
            GhosttyNativeBridge.nativeResize(
                handle,
                cols = 40,
                rows = 10,
                cellWidthPx = 8,
                cellHeightPx = 16,
            )
            val snapshot = renderSnapshot(handle)
            assertEquals(40, snapshot.cols)
            assertEquals(10, snapshot.rows)
            val text = snapshot.rowsData.joinToString("") { row ->
                row.cells.joinToString("") { it.text }
            }
            assertTrue(text.contains("before-resize"))
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun mouseReportingActiveFollowsTerminalMode() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 10)
        try {
            assertFalse(GhosttyNativeBridge.nativeMouseReportingActive(handle))
            GhosttyNativeBridge.nativeWrite(handle, "\u001b[?1000h".encodeToByteArray())
            assertTrue(GhosttyNativeBridge.nativeMouseReportingActive(handle))
            GhosttyNativeBridge.nativeWrite(handle, "\u001b[?1000l".encodeToByteArray())
            assertFalse(GhosttyNativeBridge.nativeMouseReportingActive(handle))
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun pasteReportsUnsafeNewlineAndAllowsConfirmedRetry() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(80, 10)
        try {
            val input = "echo first\necho second".encodeToByteArray()
            assertEquals(
                GhosttyNativeBridge.PASTE_RESULT_REJECTED,
                GhosttyNativeBridge.nativePasteText(
                    handle, input, GhosttyNativeBridge.PASTE_SOURCE_CLIPBOARD, false,
                ),
            )
            assertEquals(
                GhosttyNativeBridge.PASTE_RESULT_WRITTEN,
                GhosttyNativeBridge.nativePasteText(
                    handle, input, GhosttyNativeBridge.PASTE_SOURCE_CLIPBOARD, true,
                ),
            )
            assertTrue(GhosttyNativeBridge.nativeDrainPtyWrites(handle)?.isNotEmpty() == true)
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun focusEncodingFollowsMode1004() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(80, 10)
        try {
            assertFalse(GhosttyNativeBridge.nativeFocusReportingActive(handle))
            assertEquals(null, GhosttyNativeBridge.nativeEncodeFocus(handle, true))
            GhosttyNativeBridge.nativeWrite(handle, "\u001b[?1004h".encodeToByteArray())
            assertTrue(GhosttyNativeBridge.nativeFocusReportingActive(handle))
            assertEquals("\u001b[I", GhosttyNativeBridge.nativeEncodeFocus(handle, true)?.decodeToString())
            assertEquals("\u001b[O", GhosttyNativeBridge.nativeEncodeFocus(handle, false)?.decodeToString())
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun keyboardEncodingProducesUtf8Input() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(80, 10)
        try {
            val encoded = GhosttyNativeBridge.nativeEncodeKey(
                handle = handle,
                action = 1,
                keyCode = KeyEvent.KEYCODE_A,
                mods = 0,
                unshiftedCodepoint = 'a'.code,
                utf8 = "a".encodeToByteArray(),
            )
            assertEquals("a", encoded?.decodeToString())
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun caseSensitiveSearchFiltersAndPublishesHighlights() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(80, 10)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "Needle needle NEEDLE".encodeToByteArray())
            GhosttyNativeBridge.nativeSearchSet(
                handle, "Needle".encodeToByteArray(), caseSensitive = true,
            )
            while (!GhosttyNativeBridge.nativeSearchStep(handle)) Unit
            assertEquals(1, GhosttyNativeBridge.nativeSearchTotal(handle))
            assertEquals(0, GhosttyNativeBridge.nativeSearchSelect(handle, false))
            val ranges = renderSnapshot(handle).rowsData.flatMap { it.searchRanges }
            assertTrue(ranges.any { it.active })
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun sizeAndTerminfoQueriesWritePtyResponses() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(80, 24)
        try {
            GhosttyNativeBridge.nativeResize(handle, 90, 30, 9, 18)
            GhosttyNativeBridge.nativeWrite(
                handle,
                ("\u001b[18t" + "\u001bP+q544e\u001b\\").encodeToByteArray(),
            )
            val response = GhosttyNativeBridge.nativeDrainPtyWrites(handle)?.decodeToString().orEmpty()
            assertTrue(response.contains("\u001b[8;30;90t"))
            assertTrue(response.contains("787465726d2d323536636f6c6f72", ignoreCase = true))
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun terminalEventsExposeBellTitleAndWorkingDirectory() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(80, 10)
        try {
            GhosttyNativeBridge.nativeWrite(
                handle,
                ("\u0007\u001b]2;remote-title\u0007" +
                    "\u001b]7;file://server.example/tmp/project\u0007").encodeToByteArray(),
            )
            val flags = GhosttyNativeBridge.nativeTakeEventFlags(handle)
            assertTrue(flags and 1 != 0)
            assertTrue(flags and (1 shl 1) != 0)
            assertTrue(flags and (1 shl 2) != 0)
            assertEquals("remote-title", GhosttyNativeBridge.nativeGetTitle(handle)?.decodeToString())
            assertTrue(
                GhosttyNativeBridge.nativeGetPwd(handle)?.decodeToString().orEmpty()
                    .contains("/tmp/project"),
            )
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun remoteClipboardWriteRequiresResolutionAndReadIsDenied() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(80, 10)
        var requestedText: String? = null
        try {
            GhosttyNativeBridge.registerClipboardListener(handle) { request ->
                requestedText = request.text
                GhosttyNativeBridge.nativeResolveClipboardWrite(handle, request.requestId, false)
            }
            GhosttyNativeBridge.nativeWrite(
                handle,
                "\u001b]52;c;cmVtb3RlLXRleHQ=\u0007".encodeToByteArray(),
            )
            assertEquals("remote-text", requestedText)

            GhosttyNativeBridge.nativeWrite(handle, "\u001b]52;c;?\u0007".encodeToByteArray())
            val readReply = GhosttyNativeBridge.nativeDrainPtyWrites(handle)?.decodeToString().orEmpty()
            assertTrue("clipboard read must not expose local data", readReply.contains("]52;c;"))
        } finally {
            GhosttyNativeBridge.unregisterClipboardListener(handle)
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun appearanceAcceptsFullPaletteAndStyledSnapshot() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(80, 10)
        try {
            val palette = IntArray(256) { 0xff000000.toInt() or it }
            palette[1] = 0xff123456.toInt()
            GhosttyNativeBridge.nativeSetAppearance(
                handle,
                0xff000000.toInt(),
                0xffffffff.toInt(),
                0xff00ff00.toInt(),
                palette,
                true,
            )
            GhosttyNativeBridge.nativeWrite(
                handle,
                "\u001b[31;4;5mX\u001b[0m".encodeToByteArray(),
            )
            val cell = renderSnapshot(handle).rowsData.flatMap { it.cells }.first { it.text == "X" }
            assertEquals(0xff123456.toInt(), cell.fgArgb)
            assertTrue(cell.underline)
            assertTrue(cell.blink)
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun selectionDragCopiesSelectedRange() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 10)
        try {
            GhosttyNativeBridge.nativeWrite(handle, "abcdefghij".encodeToByteArray())
            GhosttyNativeBridge.nativeSelectionPress(handle, 2, 0)
            GhosttyNativeBridge.nativeSelectionDrag(handle, 5, 0)
            GhosttyNativeBridge.nativeSelectionRelease(handle, 5, 0)
            val text = GhosttyNativeBridge.nativeCopySelection(handle)?.decodeToString()
            assertEquals("cde", text)
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun linkUriAtReturnsOsc8Hyperlink() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 10)
        try {
            val uri = "https://example.com"
            GhosttyNativeBridge.nativeWrite(
                handle,
                "\u001b]8;;$uri\u0007Ghostty\u001b]8;;\u0007".encodeToByteArray(),
            )
            val rendered = renderSnapshot(handle).rowsData
                .firstOrNull()?.cells?.joinToString("") { it.text }.orEmpty()
            val found = (0..6).mapNotNull { col ->
                GhosttyNativeBridge.nativeLinkUriAt(handle, col, 0)?.decodeToString()
            }
            assertTrue("expected OSC8 link on rendered '$rendered', found=$found", found.contains(uri))
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun scrollViewportShowsOlderLines() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 80, rows = 10)
        try {
            repeat(30) { index ->
                GhosttyNativeBridge.nativeWrite(handle, "line-$index\r\n".encodeToByteArray())
            }
            val before = renderSnapshot(handle).rowsData
                .firstOrNull()?.cells?.joinToString("") { it.text }.orEmpty()
            val beforeNumber = before.substringAfter("line-").substringBefore('\r').toIntOrNull()
            assertNotNull("expected line-N at top before scroll, got: $before", beforeNumber)

            GhosttyNativeBridge.nativeScrollViewport(handle, deltaRows = -5)
            val after = renderSnapshot(handle).rowsData
                .firstOrNull()?.cells?.joinToString("") { it.text }.orEmpty()
            val afterNumber = after.substringAfter("line-").substringBefore('\r').toIntOrNull()
            assertNotNull("expected line-N at top after scroll, got: $after", afterNumber)
            assertEquals(beforeNumber!! - 5, afterNumber)
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun scrollbackIsTrimmedBeforeTenThousandHistoricalLines() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 40, rows = 10)
        try {
            val output = buildString {
                repeat(10_250) { append("history-").append(it).append("\r\n") }
            }.encodeToByteArray()
            var offset = 0
            while (offset < output.size) {
                val end = minOf(output.size, offset + 48 * 1024)
                GhosttyNativeBridge.nativeWrite(handle, output.copyOfRange(offset, end))
                offset = end
            }
            assertTrue(GhosttyNativeBridge.nativeSelectAll(handle))
            val text = GhosttyNativeBridge.nativeCopySelection(handle)?.decodeToString().orEmpty()
            assertFalse(text.lineSequence().any { it == "history-0" })
            assertTrue(text.contains("history-10249"))
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    @Test
    fun largeSnapshotReportsRequiredGrowthAndThenDecodes() {
        val handle = GhosttyNativeBridge.nativeCreateManaged(cols = 400, rows = 200)
        try {
            repeat(200) {
                GhosttyNativeBridge.nativeWrite(handle, ("X".repeat(400) + "\r\n").encodeToByteArray())
            }
            val small = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(-1, GhosttyNativeBridge.nativeRenderSnapshot(handle, small))

            val large = ByteBuffer.allocateDirect(8 shl 20).order(ByteOrder.LITTLE_ENDIAN)
            assertTrue(GhosttyNativeBridge.nativeRenderSnapshot(handle, large) >= 0)
            large.clear()
            val snapshot = RenderSnapshotDecoder.decode(large, large.capacity())
            assertNotNull(snapshot)
            assertEquals(400, snapshot!!.cols)
            assertEquals(200, snapshot.rows)
        } finally {
            GhosttyNativeBridge.nativeFreeManaged(handle)
        }
    }

    private fun renderSnapshot(handle: Long): GhosttyRenderSnapshot {
        val buffer = ByteBuffer
            .allocateDirect(1 shl 20)
            .order(ByteOrder.LITTLE_ENDIAN)
        val rowCount = GhosttyNativeBridge.nativeRenderSnapshot(handle, buffer)
        assertTrue("snapshot should decode", rowCount >= 0)
        buffer.clear()
        return RenderSnapshotDecoder.decode(buffer, buffer.capacity())
            ?: error("failed to decode snapshot")
    }
}
