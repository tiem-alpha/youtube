package com.example.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app.ui.YouTubePlayer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BackgroundQueueTest {
    @Test fun advancesTwiceAndResumesWithoutForegroundRecomposition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var web: WebView
        val ready = LinkedBlockingQueue<Boolean>()
        val advanced = LinkedBlockingQueue<String>()
        val ids = ArrayDeque(listOf("bbbbbbbbbbb", "ccccccccccc"))
        fun find(view: View): WebView? = if (view is WebView) view else
            (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
        fun script(js: String): String {
            val results = LinkedBlockingQueue<String>()
            instrumentation.runOnMainSync { web.evaluateJavascript(js) { results.add(it) } }
            return results.poll(5, TimeUnit.SECONDS) ?: error("JavaScript timed out")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YouTubePlayer("aaaaaaaaaaa", 0, Modifier.size(300.dp), backgroundPlayback = true,
                        nextVideo = { ids.removeFirstOrNull()?.let { advanced.add(it); it to it } })
                }
            }
            Thread.sleep(500)
            scenario.onActivity { activity ->
                web = find(activity.window.decorView)!!
                web.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { ready.offer(true) }
                }
                // Deterministic iframe stand-in: exercise native callbacks without YouTube/network.
                web.loadDataWithBaseURL("https://com.example.app/", """
                    <html><script>
                    var loaded='',playing=false;
                    var player={playVideo:function(){playing=true;Companion.playback(1,0,100);},pauseVideo:function(){playing=false;Companion.playback(2,0,100);}};
                    function loadRequestedVideo(id,start){loaded=id;player.playVideo();}
                    function reportPlayback(){}
                    </script></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            assertNotNull(ready.poll(10, TimeUnit.SECONDS))
            scenario.moveToState(Lifecycle.State.CREATED)
            script("Companion.finished();")
            assertEquals("bbbbbbbbbbb", advanced.poll(5, TimeUnit.SECONDS))
            assertEquals("\"bbbbbbbbbbb\"", script("loaded"))
            script("Companion.finished();")
            assertEquals("ccccccccccc", advanced.poll(5, TimeUnit.SECONDS))
            assertEquals("\"ccccccccccc\"", script("loaded"))
            script("player.pauseVideo();")
            Thread.sleep(500)
            val notifications = instrumentation.targetContext.getSystemService(android.app.NotificationManager::class.java)
            val notification = notifications.activeNotifications.first { it.id == 2002 }.notification
            assertEquals("ccccccccccc", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
            notification.actions[0].actionIntent.send()
            Thread.sleep(500)
            assertEquals("true", script("playing"))
        }
    }
}
