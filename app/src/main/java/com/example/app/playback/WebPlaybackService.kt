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

/** Keeps the visible-screen WebView's media alive while its Activity is backgrounded. */
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
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { command("pause") }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
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
    }

    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action == TIMER) { scheduleSleep(); return START_NOT_STICKY }
        val requestedOwner = intent.getStringExtra("owner").orEmpty()
        if (intent.action == STOP) {
            if (requestedOwner == owner) stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == UPDATE) {
            owner = requestedOwner
            title = intent.getStringExtra("title") ?: "Video YouTube"
            playing = intent.getBooleanExtra("playing", false)
            buffering = intent.getBooleanExtra("buffering", false)
            position = intent.getLongExtra("position", 0)
            duration = intent.getLongExtra("duration", 0)
            ended = intent.getBooleanExtra("ended", false)
            scheduleSleep()
            publish()
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
        sendBroadcast(Intent(COMMAND).setPackage(packageName).putExtra("owner", owner)
            .putExtra("command", value).putExtra("position", position))
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
            .setContentText(when { ended -> "Đã phát hết"; buffering -> "Đang tải video"; playing -> "Đang phát"; else -> "Đã tạm dừng" })
            .setOngoing(playing || buffering)
            .setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(if (playing || buffering) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing || buffering) "Tạm dừng" else "Phát", action(if (playing || buffering) PAUSE else PLAY)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Dừng", action(STOP_BUTTON)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1)).build()
        startForeground(NOTIFICATION, notification)
        if (playing || buffering) {
            handler.removeCallbacks(idleStop)
            // Refreshed by player heartbeats; bounded even if the renderer disappears.
            if (wakeLock.isHeld) wakeLock.release()
            wakeLock.acquire(60_000)
            if (!wifiLock.isHeld) wifiLock.acquire()
            handler.postDelayed(idleStop, 60_000)
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
        command("serviceStopped")
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(noisy)
        releaseLocks()
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val UPDATE = "com.example.app.WEB_PLAYBACK_UPDATE"
        const val STOP = "com.example.app.WEB_PLAYBACK_STOP"
        const val COMMAND = "com.example.app.WEB_PLAYBACK_COMMAND"
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
