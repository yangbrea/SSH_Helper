package com.yang136.sshhelper.forward

import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardServicePolicyTest {
    @Test
    fun `visible app avoids OEM foreground service promotion deadline`() {
        assertEquals(
            ForwardServiceStartMode.REGULAR,
            forwardServiceStartMode(appInForeground = true),
        )
    }

    @Test
    fun `background app retains Android foreground service contract`() {
        assertEquals(
            ForwardServiceStartMode.FOREGROUND,
            forwardServiceStartMode(appInForeground = false),
        )
    }
}
