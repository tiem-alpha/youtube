package com.example.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PlaybackState(val isReady: Boolean = false, val isPlaying: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0)

class PlaybackManager(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext = context.applicationContext
    private var controller: MediaController? = null
    private var sleepTimerJob: Job? = null
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _sleepTimerSeconds = MutableStateFlow(0)
    val sleepTimerSeconds: StateFlow<Int> = _sleepTimerSeconds.asStateFlow()

    init {
        scope.launch {
            controller = MediaController.Builder(appContext, SessionToken(appContext, ComponentName(appContext, ManagedPlaybackService::class.java))).buildAsync().await()
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
                override fun onPlaybackStateChanged(playbackState: Int) = publish()
            })
            publish()
        }
    }

    fun playAuthorizedUrl(url: String) {
        playAuthorizedUri(Uri.parse(url))
    }
    fun playAuthorizedUri(uri: Uri) {
        controller?.apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); play() }
    }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() }; publish() }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs); publish() }
    fun stop() { controller?.stop(); publish() }
    fun refresh() = publish()
    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _sleepTimerSeconds.value = minutes.coerceAtLeast(1) * 60
        sleepTimerJob = scope.launch {
            while (_sleepTimerSeconds.value > 0) {
                delay(1_000)
                _sleepTimerSeconds.value = (_sleepTimerSeconds.value - 1).coerceAtLeast(0)
            }
            controller?.pause()
            publish()
        }
    }
    fun cancelSleepTimer() { sleepTimerJob?.cancel(); _sleepTimerSeconds.value = 0 }

    private fun publish() {
        controller?.let { _state.value = PlaybackState(true, it.isPlaying, it.currentPosition, it.duration.coerceAtLeast(0)) }
    }
}
