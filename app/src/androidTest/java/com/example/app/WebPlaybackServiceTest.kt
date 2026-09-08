package com.example.app

import android.app.NotificationManager
import android.content.*
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app.playback.WebPlaybackService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebPlaybackServiceTest {
    @Test fun notificationControlsTargetOwnerAndStaleStopDoesNotStopNewSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val commands = LinkedBlockingQueue<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { commands.add(intent) }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(WebPlaybackService.COMMAND), ContextCompat.RECEIVER_NOT_EXPORTED)
        val notifications = context.getSystemService(NotificationManager::class.java)
        fun update(owner: String) = ContextCompat.startForegroundService(context,
            Intent(context, WebPlaybackService::class.java).setAction(WebPlaybackService.UPDATE)
                .putExtra("owner", owner).putExtra("title", owner).putExtra("playing", true).putExtra("position", 12_000L))
        fun waitUntil(check: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 5000
            while (!check() && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
            assertTrue("Service state timed out", check())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { update("first") }
                waitUntil { notifications.activeNotifications.any { it.id == 2002 } }
                val notification = notifications.activeNotifications.first { it.id == 2002 }.notification
                @Suppress("DEPRECATION")
                val token = notification.extras.getParcelable<MediaSession.Token>(android.app.Notification.EXTRA_MEDIA_SESSION)!!
                val controller = MediaController(context, token)
                assertEquals(PlaybackState.STATE_PLAYING, controller.playbackState!!.state)
                controller.transportControls.pause()
                val pause = commands.poll(5, TimeUnit.SECONDS)!!
                assertEquals("first", pause.getStringExtra("owner")); assertEquals("pause", pause.getStringExtra("command"))
                scenario.onActivity {
                    context.startService(Intent(context, WebPlaybackService::class.java).setAction(WebPlaybackService.UPDATE)
                        .putExtra("owner", "first").putExtra("title", "Paused video")
                        .putExtra("playing", false).putExtra("position", 24_000L).putExtra("duration", 120_000L))
                }
                waitUntil { controller.playbackState?.state == PlaybackState.STATE_PAUSED }
                assertEquals(24_000L, controller.playbackState!!.position)
                assertEquals(120_000L, controller.metadata!!.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION))
                scenario.onActivity {
                    context.startService(Intent(context, WebPlaybackService::class.java).setAction(WebPlaybackService.UPDATE)
                        .putExtra("owner", "first").putExtra("buffering", true))
                }
                waitUntil { controller.playbackState?.state == PlaybackState.STATE_BUFFERING }
                scenario.onActivity {
                    // Disposal can arrive before the replacement player starts loading.
                    context.startService(Intent(context, WebPlaybackService::class.java).setAction(WebPlaybackService.STOP).putExtra("owner", "first"))
                    update("second")
                }
                waitUntil { controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) == "second" }
                Thread.sleep(1000)
                assertEquals("second", controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
                assertTrue(notifications.activeNotifications.any { it.id == 2002 })
                context.startService(Intent(context, WebPlaybackService::class.java).setAction(WebPlaybackService.STOP).putExtra("owner", "first"))
                controller.transportControls.pause()
                val second = commands.poll(5, TimeUnit.SECONDS)!!
                assertEquals("second", second.getStringExtra("owner")); assertEquals("pause", second.getStringExtra("command"))
                context.startService(Intent(context, WebPlaybackService::class.java).setAction(WebPlaybackService.STOP).putExtra("owner", "second"))
                waitUntil { notifications.activeNotifications.none { it.id == 2002 } }
            } finally {
                context.stopService(Intent(context, WebPlaybackService::class.java))
                context.unregisterReceiver(receiver)
            }
        }
    }
}
