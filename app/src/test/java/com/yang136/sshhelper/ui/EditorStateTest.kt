package com.yang136.sshhelper.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorStateTest {
    @Test
    fun loadedState_startsClean() {
        assertFalse(EditorState(loaded = true).isDirty)
    }

    @Test
    fun userEdit_marksStateDirtyAndClearsPreviousError() {
        val edited = EditorState(error = "旧错误", loaded = true)
            .applyUserEdit { it.copy(name = "服务器") }

        assertTrue(edited.isDirty)
        assertNull(edited.error)
    }
}
