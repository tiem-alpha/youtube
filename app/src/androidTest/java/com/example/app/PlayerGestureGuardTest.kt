package com.example.app

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.example.app.ui.HoldToMinimizeLayout
import com.example.app.ui.PlayerGestureGuard
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PlayerGestureGuardTest {
    @SuppressLint("SetJavaScriptEnabled")
    @Test fun iframePopupBlocksMinimizeAndClosingItRestoresDrag() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var web: WebView
        lateinit var layout: HoldToMinimizeLayout
        lateinit var guard: PlayerGestureGuard
        var minimized = 0
        fun waitFor(blocked: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var matched = false
            while (!matched && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { matched = guard.popupOpen == blocked }
                if (!matched) Thread.sleep(50)
            }
            assertTrue("Expected popup blocking = $blocked", matched)
        }
        fun command(value: String) {
            instrumentation.runOnMainSync {
                web.evaluateJavascript("document.querySelector('iframe').contentWindow.postMessage('$value','https://www.youtube.com');", null)
            }
        }
        fun swipe() {
            instrumentation.runOnMainSync {
                val start = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
                    MotionEvent.obtain(start, start + i * 30L, action, 50f, if (i == 0) 50f else 500f, 0).also {
                        layout.dispatchTouchEvent(it); it.recycle()
                    }
                }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                guard = PlayerGestureGuard()
                web = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                            WebResourceResponse("text/html", "UTF-8", FIXTURE.byteInputStream())
                    }
                }
                guard.install(web)
                layout = HoldToMinimizeLayout(activity).apply {
                    dragEnabled = true; dragBlocked = { guard.popupOpen }
                    onMinimize = { minimized++ }
                    addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
                }
                activity.setContentView(layout)
                web.loadDataWithBaseURL("https://com.example.app/", """
                    <html><body style="margin:0"><iframe style="border:0;width:100%;height:100vh"
                    src="https://www.youtube.com/embed/gesture-fixture"></iframe></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            try {
                waitFor(false)
                command("open"); waitFor(true)
                swipe()
                instrumentation.runOnMainSync { assertEquals(0, minimized) }
                command("submenu"); waitFor(true)
                swipe()
                instrumentation.runOnMainSync { assertEquals(0, minimized) }
                command("close"); waitFor(false)
                swipe()
                instrumentation.runOnMainSync { assertEquals(1, minimized) }
            } finally {
                instrumentation.runOnMainSync { layout.removeView(web); web.destroy() }
            }
        }
    }

    companion object {
        private val FIXTURE = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0"><div id="movie_player" style="height:100vh">
            <div id="menu" class="ytp-settings-menu" role="menu" style="display:none;height:140px;overflow-y:auto">
            <div style="height:900px">Speed<br>Quality<br>Captions</div></div></div>
            <script>addEventListener('message', function(e) {
                if(e.origin !== 'https://com.example.app') return;
                var menu = document.getElementById('menu');
                menu.style.display = e.data === 'close' ? 'none' : 'block';
                if(e.data === 'submenu') menu.setAttribute('role','listbox');
            });</script></body></html>
        """.trimIndent()
    }
}
