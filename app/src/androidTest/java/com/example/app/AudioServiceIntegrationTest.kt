package com.example.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app.playback.AudioSnapshot
import com.example.app.playback.WebPlaybackBridge
import com.example.app.playback.WebPlaybackService
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Real standalone YouTube audio, one session across the web/audio boundary. */
class AudioServiceIntegrationTest {
    @Test fun notificationControlsAudioAndReturnsItsExactClockToVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        val latest = AtomicReference<AudioSnapshot>()
        val returned = AtomicReference<AudioSnapshot>()
        lateinit var bridge: WebPlaybackBridge
        lateinit var web: WebView
        var initialized = false
        fun waitUntil(check: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 40_000
            while (!check() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(100)
            assertTrue("Audio handoff timed out: ${latest.get()?.copy(error = latest.get()?.error)}", check())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    web = WebView(activity)
                    bridge = WebPlaybackBridge(activity, true)
                    initialized = true
                    bridge.attach(web, onAudioState = latest::set, onAudioReturn = returned::set)
                    bridge.update(1, 5, "Audio handoff test") { fail("Foreground service rejected") }
                    assertTrue(bridge.startAudio("jNQXAC9IVRw", "Audio handoff test", 5000, true, 1f))
                }
                waitUntil { latest.get()?.let { !it.buffering && it.error == null && it.positionMs > 5000 } == true }
                scenario.moveToState(Lifecycle.State.CREATED)
                val notification = notifications.activeNotifications.first { it.id == 2002 }.notification
                @Suppress("DEPRECATION")
                val token = notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)!!
                val controller = MediaController(context, token)
                controller.transportControls.pause()
                waitUntil { latest.get()?.playWhenReady == false }
                controller.transportControls.seekTo(8000)
                waitUntil { latest.get()?.positionMs == 8000L }
                controller.transportControls.play()
                waitUntil { latest.get()?.let { it.playWhenReady && it.positionMs > 8500 } == true }
                instrumentation.runOnMainSync { bridge.returnToVideo() }
                waitUntil { returned.get() != null }
                assertEquals("jNQXAC9IVRw", returned.get().videoId)
                assertTrue(returned.get().positionMs > 8500)
                assertTrue(returned.get().playWhenReady)
                assertEquals("Audio handoff test", controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
                @Suppress("DEPRECATION")
                val restoredToken = notifications.activeNotifications.first { it.id == 2002 }.notification
                    .extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
                assertEquals(token, restoredToken)
            } finally {
                instrumentation.runOnMainSync { if (initialized) { bridge.close(); web.destroy() } }
                context.stopService(Intent(context, WebPlaybackService::class.java))
            }
        }
    }
}
