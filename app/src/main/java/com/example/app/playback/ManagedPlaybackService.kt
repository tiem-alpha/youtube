package com.example.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.SystemClock

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ManagedPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updateVideoTracks() }
    }
    private fun updateVideoTracks() {
        val player = session?.player ?: return
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !screenOn).build()
    }
    override fun onCreate() {
        super.onCreate()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 1_500, 5_000)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15_000, true)
            .build()
        val player = ExoPlayer.Builder(this).setLoadControl(loadControl).build().apply {
            // Hold CPU/Wi-Fi locks only while playback needs them, including with the screen off.
            setWakeMode(C.WAKE_MODE_NETWORK)
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        session = MediaSession.Builder(this, player).build()
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        updateVideoTracks()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START_TIMER -> {
                timerJob?.cancel()
                val deadline = SystemClock.elapsedRealtime() + intent.getIntExtra("minutes", 15).coerceIn(1, 1440) * 60000L
                timerJob = scope.launch {
                    while (isActive) {
                        val remaining = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0).toInt()
                        remainingSeconds.value = remaining
                        if (remaining == 0) { session?.player?.pause(); break }
                        delay(500)
                    }
                }
            }
            CANCEL_TIMER -> { timerJob?.cancel(); remainingSeconds.value = 0 }
        }
        return super.onStartCommand(intent, flags, startId)
    }
    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        scope.cancel(); remainingSeconds.value = 0
        session?.run { player.release(); release() }; session = null
        super.onDestroy()
    }
    companion object {
        const val START_TIMER = "com.example.app.START_TIMER"
        const val CANCEL_TIMER = "com.example.app.CANCEL_TIMER"
        private val remainingSeconds = MutableStateFlow(0)
        val timer = remainingSeconds.asStateFlow()
    }
}
