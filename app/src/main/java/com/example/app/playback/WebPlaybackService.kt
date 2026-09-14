package com.example.app.playback

import android.app.*
import android.content.*
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.content.ContextCompat
import com.example.app.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One notification/session for web video and native screen-off audio, including queue handoffs. */
class WebPlaybackService : Service() {
    private lateinit var session: MediaSession
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private val handler = Handler(Looper.getMainLooper())
    private var owner = ""
    private var title = "Video YouTube"
    private var playing = false
    private var buffering = false
    private var position = 0L
    private var duration = 0L
    private var ended = false
    private var audioPlayer: ScreenOffAudioPlayer? = null
    private var audioError: String? = null
    private var handoffRequested = false
    private var handoffPlay: Boolean? = null
    private var handoffSeek: Long? = null
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            command(if (intent.action == Intent.ACTION_SCREEN_OFF) "audioTakeover" else "screenOn")
        }
    }
    private val sleepStop = Runnable {
        command("pause")
        _sleepDeadline.value = 0
        stopSelf()
    }
    private fun scheduleSleep() {
        handler.removeCallbacks(sleepStop)
        val deadline = _sleepDeadline.value
        if (deadline > 0) handler.postDelayed(sleepStop, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }
    private val idleStop = Runnable { stopSelf() }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (playing || buffering) {
                // JS intervals can be throttled while the screen is off. The native
                // service keeps the session alive and explicitly requests player state.
                publish()
                command("refresh")
            }
            handler.postDelayed(this, 15_000)
        }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { command("pause") }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        handler.postDelayed(heartbeat, 15_000)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:youtube")
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:youtube")
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Phát video nền", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "YouTube background").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { command("play") }
                override fun onPause() { command("pause") }
                override fun onStop() { _sleepDeadline.value = 0; command("pause"); stopSelf() }
                override fun onSeekTo(pos: Long) { command("seek", pos) }
            })
            isActive = true
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action == TIMER) { scheduleSleep(); return START_NOT_STICKY }
        val requestedOwner = intent.getStringExtra("owner").orEmpty()
        if (requestedOwner == owner && intent.action == AUDIO_START) {
            val id = com.example.app.domain.YouTubeLinks.videoId(intent.getStringExtra("videoId").orEmpty())
                ?: return START_NOT_STICKY
            if (audioPlayer == null) audioPlayer = ScreenOffAudioPlayer(this,
                onState = { state ->
                    title = state.title; position = state.positionMs; duration = state.durationMs
                    playing = state.playWhenReady && !state.ended && state.error == null
                    buffering = state.buffering && state.playWhenReady
                    ended = state.ended; audioError = state.error
                    publish(); sendAudioState("audioState", state)
                }, onEnded = { sendAudioState("audioEnded", it) })
            audioPlayer!!.start(id, intent.getStringExtra("title") ?: title,
                handoffSeek ?: intent.getLongExtra("position", position), handoffPlay ?: intent.getBooleanExtra("play", true),
                intent.getFloatExtra("rate", 1f))
            handoffRequested = false; handoffPlay = null; handoffSeek = null
            return START_NOT_STICKY
        }
        if (requestedOwner == owner && intent.action == AUDIO_RETURN) {
            val audio = audioPlayer
            audioPlayer = null
            audio?.release()?.let { state ->
                position = state.positionMs; playing = state.playWhenReady && !state.ended
                buffering = playing; audioError = null
                publish(); sendAudioState("audioReturn", state)
            }
            return START_NOT_STICKY
        }
        if (intent.action == STOP) {
            // Keep the service alive briefly while Compose replaces the player.
            // A new owner's loading update takes over without losing its foreground session.
            handler.postDelayed({ if (requestedOwner == owner) stopSelf() }, 750)
            return START_NOT_STICKY
        }
        if (intent.action == UPDATE) {
            if (audioPlayer != null && requestedOwner == owner) return START_NOT_STICKY
            if (requestedOwner != owner) { val old = audioPlayer; audioPlayer = null; old?.release(); audioError = null }
            if (requestedOwner != owner || getSystemService(PowerManager::class.java).isInteractive) {
                handoffRequested = false; handoffPlay = null; handoffSeek = null
            }
            owner = requestedOwner
            title = intent.getStringExtra("title") ?: "Video YouTube"
            playing = intent.getBooleanExtra("playing", false)
            buffering = intent.getBooleanExtra("buffering", false)
            position = intent.getLongExtra("position", 0)
            duration = intent.getLongExtra("duration", 0)
            ended = intent.getBooleanExtra("ended", false)
            scheduleSleep()
            publish()
            if (!getSystemService(PowerManager::class.java).isInteractive) command("audioTakeover")
        } else if (requestedOwner == owner) {
            when (intent.action) {
                PLAY -> command("play")
                PAUSE -> command("pause")
                STOP_BUTTON -> { _sleepDeadline.value = 0; command("pause"); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    private fun command(value: String, position: Long = 0) {
        audioPlayer?.let { audio ->
            when (value) {
                "play" -> { audio.play(); return }
                "pause" -> { audio.pause(); return }
                "seek" -> { audio.seekTo(position); return }
                "refresh", "audioTakeover" -> return
            }
        }
        if (value == "audioTakeover" && !handoffRequested) {
            handoffRequested = true; handoffPlay = null; handoffSeek = null
        }
        if (handoffRequested) when (value) {
            "play" -> handoffPlay = true
            "pause" -> { handoffPlay = false; playing = false; buffering = false; publish() }
            "seek" -> handoffSeek = position.coerceAtLeast(0)
        }
        if (value == "play") {
            // Hold CPU/network while Chromium resumes, before its next heartbeat.
            ended = false
            buffering = true
            publish()
        }
        sendBroadcast(Intent(COMMAND).setPackage(packageName).putExtra("owner", owner)
            .putExtra("command", value).putExtra("position", position))
    }

    private fun sendAudioState(command: String, state: AudioSnapshot) {
        sendBroadcast(Intent(COMMAND).setPackage(packageName).putExtra("owner", owner)
            .putExtra("command", command).putExtra("videoId", state.videoId).putExtra("title", state.title)
            .putExtra("position", state.positionMs).putExtra("duration", state.durationMs)
            .putExtra("play", state.playWhenReady).putExtra("buffering", state.buffering)
            .putExtra("ended", state.ended).putExtra("error", state.error))
    }

    private fun action(action: String): PendingIntent = PendingIntent.getService(this, action.hashCode(),
        Intent(this, WebPlaybackService::class.java).setAction(action).putExtra("owner", owner),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun publish() {
        val state = when { ended -> PlaybackState.STATE_STOPPED; buffering -> PlaybackState.STATE_BUFFERING; playing -> PlaybackState.STATE_PLAYING; else -> PlaybackState.STATE_PAUSED }
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO).setState(state, position, if (playing) 1f else 0f).build())
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration).build())
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title)
            .setContentText(audioError ?: when { ended -> "Đã phát hết"; buffering -> if (audioPlayer != null) "Đang tải âm thanh" else "Đang tải video"; playing -> if (audioPlayer != null) "Đang phát âm thanh" else "Đang phát"; else -> "Đã tạm dừng" })
            .setOngoing(playing || buffering)
            .setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(if (playing || buffering) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing || buffering) "Tạm dừng" else "Phát", action(if (playing || buffering) PAUSE else PLAY)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Dừng", action(STOP_BUTTON)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1)).build()
        startForeground(NOTIFICATION, notification)
        if (playing || buffering) {
            handler.removeCallbacks(idleStop)
            // Renewed on the native timer as well as player state updates.
            if (wakeLock.isHeld) wakeLock.release()
            wakeLock.acquire(60_000)
            if (!wifiLock.isHeld) wifiLock.acquire()
        } else {
            releaseLocks()
            handler.removeCallbacks(idleStop)
            handler.postDelayed(idleStop, 5 * 60_000)
        }
    }

    private fun releaseLocks() {
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { _sleepDeadline.value = 0; stopSelf() }
    override fun onDestroy() {
        running = false
        val audio = audioPlayer; audioPlayer = null
        audio?.release()?.let { sendAudioState("audioStopped", it.copy(playWhenReady = false)) }
        command("serviceStopped")
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(noisy)
        unregisterReceiver(screen)
        releaseLocks()
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val UPDATE = "com.example.app.WEB_PLAYBACK_UPDATE"
        const val STOP = "com.example.app.WEB_PLAYBACK_STOP"
        const val COMMAND = "com.example.app.WEB_PLAYBACK_COMMAND"
        const val AUDIO_START = "com.example.app.AUDIO_START"
        const val AUDIO_RETURN = "com.example.app.AUDIO_RETURN"
        private const val PLAY = "com.example.app.WEB_PLAY"
        private const val PAUSE = "com.example.app.WEB_PAUSE"
        private const val STOP_BUTTON = "com.example.app.WEB_STOP_BUTTON"
        private const val CHANNEL = "youtube_background"
        private const val NOTIFICATION = 2002
        private const val TIMER = "com.example.app.WEB_TIMER"
        private val _sleepDeadline = MutableStateFlow(0L)
        val sleepDeadline = _sleepDeadline.asStateFlow()
        fun setSleepTimer(context: Context, minutes: Int) {
            _sleepDeadline.value = if (minutes > 0) SystemClock.elapsedRealtime() + minutes.coerceAtMost(120) * 60_000L else 0L
            // Only notify an existing service. A pending timer is picked up when playback starts.
            if (running) context.startService(Intent(context, WebPlaybackService::class.java).setAction(TIMER))
        }
        private var running = false
    }
}
