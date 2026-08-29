package com.yang136.sshhelper.preview

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.yang136.sshhelper.sftp.RemoteFile
import com.yang136.sshhelper.ssh.SessionId
import com.yang136.sshhelper.ssh.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing state of the streaming audio preview. Position is read live from [PreviewPlaybackManager.playerOrNull]. */
data class AudioPreviewState(
    val file: RemoteFile? = null,
    val hostName: String = "",
    val isPrepared: Boolean = false,
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val error: String? = null,
)

/**
 * Drives an [ExoPlayer] that streams a remote file over SFTP (see [SftpDataSource]).
 * Each [start] creates one dedicated SFTP channel and releases it on [stop]; never leave
 * a started preview running, or the channel stays open until the SSH session is closed.
 */
class PreviewPlaybackManager(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val sessionId: SessionId,
    private val cache: Cache,
) {
    private val mutableState = MutableStateFlow(AudioPreviewState())
    val state: StateFlow<AudioPreviewState> = mutableState.asStateFlow()

    private var player: ExoPlayer? = null
    private var factory: SftpDataSourceFactory? = null

    /** Live player for position/duration reads; null while idle. */
    val playerOrNull: ExoPlayer? get() = player

    fun start(file: RemoteFile, hostName: String) {
        stop()
        val newFactory = SftpDataSourceFactory(sessionManager, sessionId, file.path, hostName)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(newFactory)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
        val newPlayer = ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val mediaSource = ProgressiveMediaSource.Factory(cacheFactory)
            .createMediaSource(MediaItem.fromUri(cacheAwareUri(file, hostName)))
        newPlayer.setMediaSource(mediaSource)
        newPlayer.addListener(playerListener)
        newPlayer.prepare()
        newPlayer.playWhenReady = true
        factory = newFactory
        player = newPlayer
        mutableState.value = AudioPreviewState(file = file, hostName = hostName, isBuffering = true)
    }

    fun togglePlayPause() {
        val current = player ?: return
        if (current.isPlaying) current.pause() else current.play()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun stop() {
        val current = player ?: return
        current.removeListener(playerListener)
        current.release()
        player = null
        factory?.release()
        factory = null
        mutableState.value = AudioPreviewState()
    }

    /**
     * The cache key is the full URI, so embedding the file size makes a changed file
     * (same path, different size) miss the cache instead of replaying stale audio.
     */
    private fun cacheAwareUri(file: RemoteFile, hostName: String): Uri =
        Uri.parse("sftp://$hostName${file.path}" + if (file.size >= 0) "?size=${file.size}" else "")

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mutableState.value = mutableState.value.copy(isPlaying = isPlaying, isBuffering = false)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val current = mutableState.value
            mutableState.value = when (playbackState) {
                Player.STATE_BUFFERING -> current.copy(isBuffering = true)
                Player.STATE_READY -> current.copy(
                    isPrepared = true,
                    isBuffering = false,
                    durationMs = player?.duration?.takeIf { it > 0 } ?: current.durationMs,
                )
                Player.STATE_ENDED -> current.copy(isPlaying = false, isBuffering = false)
                else -> current.copy(isBuffering = false)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val message = if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            ) {
                "读取远程文件失败，请检查 SSH 连接"
            } else {
                error.message ?: "播放失败"
            }
            mutableState.value = mutableState.value.copy(error = message, isBuffering = false, isPlaying = false)
        }
    }
}
