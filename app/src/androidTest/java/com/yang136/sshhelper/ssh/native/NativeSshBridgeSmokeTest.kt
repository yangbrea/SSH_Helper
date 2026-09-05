package com.yang136.sshhelper.ssh.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device/emulator smoke test for libsshhelper_ssh.so.
 *
 * CI currently compiles the instrumentation APK without running it on a
 * device; this file is the acceptance check that must be run during the
 * release-gate device matrix.
 */
@RunWith(AndroidJUnit4::class)
class NativeSshBridgeSmokeTest {
    @Test
    fun nativeLibraryLoadsAndReportsVersions() {
        val version = NativeSshBridge.nativeVersion()
        assertTrue("nativeVersion should mention libssh2: $version", version.contains("libssh2"))
        assertTrue("nativeVersion should mention OpenSSL: $version", version.contains("OpenSSL"))

        val capabilities = NativeSshBridge.nativeCapabilities()
        assertTrue("capabilities should include ABI: $capabilities", capabilities.contains("abi="))
        assertTrue(
            "capabilities should enforce modern algorithms only: $capabilities",
            capabilities.contains("crypto_backend=openssl") &&
                capabilities.contains("legacy_algorithms=false"),
        )
    }

    @Test
    fun handleLifecycleIsIdempotentAndSafe() {
        val handle = NativeSshBridge.nativeCreate()
        assertNotEquals("nativeCreate must return a non-zero handle", 0L, handle)
        assertNull("a new runtime must not contain events", NativeSshBridge.nativeAwaitEvent(handle, 0))
        assertFalse("unknown requests cannot be cancelled", NativeSshBridge.nativeCancel(handle, 1L))

        NativeSshBridge.nativeClose(handle)
        NativeSshBridge.nativeClose(handle)
        NativeSshBridge.nativeClose(0L)
        NativeSshBridge.nativeClose(handle + 100_000L)

        // Creating a fresh handle after close must still work.
        val second = NativeSshBridge.nativeCreate()
        assertNotEquals("second create must return a non-zero handle", 0L, second)
        assertFalse("registry IDs must not be reused while first handle is live", handle == second)
        NativeSshBridge.nativeClose(second)
    }
}
