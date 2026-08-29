package com.yang136.sshhelper.preview

import android.content.Context
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * App-scoped Media3 [SimpleCache] for streamed previews. Lives in the app cache dir (auto
 * purged by the system when storage runs low) and evicts least-recently-used spans so a
 * reopened file resumes from already-downloaded chunks instead of re-reading the network.
 * A single instance per process is mandatory: two [SimpleCache]s on the same directory
 * conflict over its file lock.
 */
class PreviewCache(application: Context) {
    private val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
    val cache: Cache = SimpleCache(
        File(application.cacheDir, "preview-cache"),
        evictor,
    )

    companion object {
        const val MAX_CACHE_BYTES = 256L * 1024 * 1024
    }
}
