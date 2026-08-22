package com.yang136.sshhelper.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysTest {
    @Test
    fun decodingIgnoresUnknownAndRemovesDuplicates() {
        assertEquals(listOf(ExtraKeyId.ESC, ExtraKeyId.TAB), decodeExtraKeys("ESC,BROKEN,TAB,ESC"))
    }

    @Test
    fun emptyOrCorruptConfigurationFallsBackToDefaults() {
        assertEquals(DEFAULT_EXTRA_KEYS, decodeExtraKeys(null))
        assertEquals(DEFAULT_EXTRA_KEYS, decodeExtraKeys("BROKEN"))
        assertTrue(DEFAULT_EXTRA_KEYS.contains(ExtraKeyId.KEYBOARD))
    }
}
