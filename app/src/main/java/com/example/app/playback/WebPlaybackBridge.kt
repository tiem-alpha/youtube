package com.example.app.playback

import android.content.*
import android.view.View
import android.webkit.WebView
import androidx.core.content.ContextCompat
import java.util.UUID

class BackgroundPlaybackWebView(context: Context, private val backgroundPlayback: Boolean) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (!backgroundPlayback || visibility == View.VISIBLE) super.onWindowVisibilityChanged(visibility)
    }
}

/** One owner per composed player. Disposing the screen always ends its background session. */
class WebPlaybackBridge(private val context: Context, private val enabled: Boolean) {
    private val owner = UUID.randomUUID().toString()
    private var started = false
    private var closed = false
    private var previousSnapshot = ""
    private var receiver: BroadcastReceiver? = null

    fun attach(webView: WebView) {
        if (!enabled) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (closed || intent.getStringExtra("owner") != owner) return
                val script = when (intent.getStringExtra("command")) {
                    "play" -> "if(player&&player.playVideo)player.playVideo();"
                    "seek" -> "if(player&&player.seekTo)player.seekTo(${intent.getLongExtra("position", 0).coerceAtLeast(0) / 1000.0},true);"
                    else -> "if(player&&player.pauseVideo)player.pauseVideo();"
                }
                if (intent.getStringExtra("command") == "serviceStopped") { started = false; previousSnapshot = "" }
                webView.evaluateJavascript(script, null)
            }
        }.also { ContextCompat.registerReceiver(context, it, IntentFilter(WebPlaybackService.COMMAND), ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    fun update(state: Int, seconds: Int, title: String, duration: Int = 0, onError: () -> Unit) {
        if (!enabled || closed) return
        // Ignore initialization/cued events. A session begins only after actual playback.
        if (!started && state != 1) return
        val snapshot = "$state|$seconds|$title|$duration"
        if (snapshot == previousSnapshot && state !in listOf(1, 3)) return
        previousSnapshot = snapshot
        val intent = Intent(context, WebPlaybackService::class.java).setAction(WebPlaybackService.UPDATE)
            .putExtra("owner", owner).putExtra("title", title).putExtra("playing", state == 1)
            .putExtra("buffering", state == 3).putExtra("position", seconds.coerceAtLeast(0) * 1000L)
            .putExtra("duration", duration.coerceAtLeast(0) * 1000L).putExtra("ended", state == 0)
        try {
            if (!started) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
            started = true
        } catch (_: IllegalStateException) { started = false; onError() }
        catch (_: SecurityException) { started = false; onError() }
    }

    fun close() {
        closed = true
        receiver?.let { context.unregisterReceiver(it) }; receiver = null
        if (started) {
            runCatching { context.startService(Intent(context, WebPlaybackService::class.java)
                .setAction(WebPlaybackService.STOP).putExtra("owner", owner)) }
            started = false
        }
    }
}
