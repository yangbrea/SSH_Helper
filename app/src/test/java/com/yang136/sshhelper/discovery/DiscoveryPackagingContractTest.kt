package com.yang136.sshhelper.discovery

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryPackagingContractTest {
    @Test
    fun manifestEnablesMulticastWithoutPrematureAndroid17Permission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.CHANGE_WIFI_MULTICAST_STATE"))
        assertFalse(manifest.contains("android.permission.ACCESS_LOCAL_NETWORK"))
    }

    @Test
    fun offlineOuiSnapshotIsPackaged() {
        val snapshot = File("src/main/assets/discovery/oui.tsv")
        assertTrue(snapshot.isFile)
        assertTrue(snapshot.readLines().size >= 500)
        assertTrue(snapshot.readText().contains("/24\t"))
    }
}
