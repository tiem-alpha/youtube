package com.example.app.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await

data class PlaybackState(val isReady: Boolean = false, val isPlaying: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val error: String? = null)

class PlaybackManager(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext = context.applicationContext
    private var controller: MediaController? = null
    private var pendingUri: Uri? = null
    private var released = false
    private val _state = MutableStateFlow(PlaybackState())
    val state = _state.asStateFlow()
    private val _player = MutableStateFlow<Player?>(null)
    val player = _player.asStateFlow()
    val sleepTimerSeconds = ManagedPlaybackService.timer
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = "Không phát được nguồn này. Kiểm tra liên kết, định dạng hoặc quyền truy cập file.")
        }
    }
    init {
        scope.launch {
            val future = MediaController.Builder(appContext, SessionToken(appContext, ComponentName(appContext, ManagedPlaybackService::class.java))).buildAsync()
            try {
                val connected = future.await()
                if (released) { connected.release(); return@launch }
                controller = connected; _player.value = connected
                connected.addListener(listener)
                pendingUri?.let { playAuthorizedUri(it) }; pendingUri = null
                while (isActive) { publish(); delay(500) }
            } catch (e: CancellationException) { MediaController.releaseFuture(future); throw e }
            catch (_: Exception) { _state.value = _state.value.copy(error = "Không kết nối được trình phát. Hãy mở lại ứng dụng.") }
        }
    }
    fun playAuthorizedUrl(url: String) {
        val uri = Uri.parse(url.trim())
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) { _state.value = _state.value.copy(error = "Nhập liên kết HTTPS trực tiếp đến file media."); return }
        playAuthorizedUri(uri)
    }
    fun playAuthorizedUri(uri: Uri) {
        _state.value = _state.value.copy(error = null)
        val p = controller ?: run { pendingUri = uri; return }
        p.setMediaItem(MediaItem.fromUri(uri)); p.prepare(); p.play()
    }
    fun pause() { controller?.pause() }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0)); publish() }
    fun stop() { controller?.stop(); cancelSleepTimer(); publish() }
    fun speed(value: Float) { controller?.setPlaybackSpeed(value) }
    fun startSleepTimer(minutes: Int) {
        if (controller?.isPlaying != true) { _state.value = _state.value.copy(error = "Hãy phát media trước khi đặt hẹn giờ."); return }
        appContext.startService(Intent(appContext, ManagedPlaybackService::class.java).setAction(ManagedPlaybackService.START_TIMER).putExtra("minutes", minutes.coerceIn(1, 1440)))
    }
    fun cancelSleepTimer() {
        if (sleepTimerSeconds.value > 0) appContext.startService(Intent(appContext, ManagedPlaybackService::class.java).setAction(ManagedPlaybackService.CANCEL_TIMER))
    }
    private fun publish() {
        controller?.let { _state.value = _state.value.copy(isReady = true, isPlaying = it.isPlaying, positionMs = it.currentPosition.coerceAtLeast(0), durationMs = it.duration.coerceAtLeast(0)) }
    }
    fun release() {
        released = true; scope.cancel(); controller?.removeListener(listener); controller?.release(); controller = null; _player.value = null
    }
}
