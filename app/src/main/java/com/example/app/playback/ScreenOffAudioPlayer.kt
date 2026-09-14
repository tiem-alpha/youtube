package com.example.app.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.app.data.NewPipePublicSource
import com.example.app.data.ResolvedAudio
import kotlinx.coroutines.*

data class AudioSnapshot(
    val videoId: String, val title: String, val positionMs: Long, val durationMs: Long = 0,
    val playWhenReady: Boolean = true, val buffering: Boolean = true,
    val ended: Boolean = false, val error: String? = null
)

/** Owned by the foreground service, independent of Activity/Compose frame scheduling. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ScreenOffAudioPlayer(
    private val context: Context,
    private val resolve: suspend (String) -> ResolvedAudio = { NewPipePublicSource().audio(it) },
    private val onState: (AudioSnapshot) -> Unit,
    private val onEnded: (AudioSnapshot) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var request: Job? = null
    private var player: ExoPlayer? = null
    private var generation = 0
    private var speed = 1f
    private var retries = 0
    private var endDelivered = false
    var snapshot: AudioSnapshot? = null
        private set

    init {
        scope.launch { while (isActive) { delay(1000); publishPlayer() } }
    }

    fun start(videoId: String, title: String, positionMs: Long, play: Boolean, rate: Float) {
        retries = 0; speed = rate.coerceIn(0.25f, 2f)
        snapshot = AudioSnapshot(videoId, title, positionMs.coerceAtLeast(0), playWhenReady = play)
        load()
    }

    private fun load() {
        val current = snapshot ?: return
        val ticket = ++generation
        request?.cancel(); player?.release(); player = null; endDelivered = false
        snapshot = current.copy(buffering = true, ended = false, error = null)
        onState(snapshot!!)
        request = scope.launch {
            try {
                val source = withTimeout(30_000) { resolve(current.videoId) }
                if (ticket != generation) return@launch
                val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000)
                    .setUserAgent("Mozilla/5.0")
                val native = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
                    .build()
                player = native
                native.setWakeMode(C.WAKE_MODE_NETWORK)
                native.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                native.setHandleAudioBecomingNoisy(true)
                native.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (ticket == generation) publishPlayer()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (ticket != generation) return
                        if (retries++ < 1) { publishPlayer(); load() }
                        else fail()
                    }
                })
                native.setPlaybackSpeed(speed)
                native.setMediaItem(MediaItem.Builder().setUri(source.url).setMimeType(source.mimeType).build(), snapshot!!.positionMs)
                native.playWhenReady = snapshot!!.playWhenReady
                native.prepare()
            } catch (e: TimeoutCancellationException) { if (ticket == generation) fail() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (ticket == generation) fail() }
        }
    }

    private fun fail() {
        ++generation
        player?.release(); player = null
        snapshot = snapshot?.copy(playWhenReady = false, buffering = false,
            error = "Không tải được âm thanh riêng. Bấm Phát để thử lại hoặc mở app để xem video.")
        snapshot?.let(onState)
    }

    private fun publishPlayer() {
        val native = player ?: return
        val old = snapshot ?: return
        snapshot = old.copy(positionMs = native.currentPosition.coerceAtLeast(0),
            durationMs = native.duration.takeIf { it > 0 } ?: old.durationMs,
            playWhenReady = native.playWhenReady,
            buffering = native.playbackState in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING),
            ended = native.playbackState == Player.STATE_ENDED)
        val state = snapshot!!
        onState(state)
        if (state.ended && state.playWhenReady && !endDelivered) { endDelivered = true; onEnded(state) }
    }

    fun play() {
        val current = snapshot ?: return
        val wasEnded = current.ended
        snapshot = current.copy(playWhenReady = true, ended = false,
            positionMs = if (wasEnded) 0 else current.positionMs)
        if (snapshot?.error != null || wasEnded) {
            retries = 0; load()
        } else { player?.play(); snapshot?.let(onState) }
    }
    fun pause() { snapshot = snapshot?.copy(playWhenReady = false); player?.pause(); snapshot?.let(onState) }
    fun seekTo(positionMs: Long) {
        snapshot = snapshot?.copy(positionMs = positionMs.coerceAtLeast(0))
        player?.seekTo(positionMs.coerceAtLeast(0)); snapshot?.let(onState)
    }
    fun release(): AudioSnapshot? {
        player?.let { native -> snapshot = snapshot?.copy(positionMs = native.currentPosition.coerceAtLeast(0),
            playWhenReady = native.playWhenReady, ended = native.playbackState == Player.STATE_ENDED) }
        ++generation; scope.cancel(); player?.release(); player = null
        return snapshot
    }
}
