package com.example.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app.playback.BackgroundPlaybackLayout
import com.example.app.playback.BackgroundPlaybackWebView
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundPlaybackVisibilityTest {
    private class VisibilityProbe(context: Context) : View(context) {
        val events = mutableListOf<Int>()
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            events.add(visibility)
        }
    }

    @Test fun backgroundWebViewKeepsVideoChildrenVisible() = checkVisibility(fullscreen = false, background = true)
    @Test fun foregroundWebViewStillHidesVideoChildren() = checkVisibility(fullscreen = false, background = false)
    @Test fun backgroundFullscreenKeepsChromiumViewVisible() = checkVisibility(fullscreen = true, background = true)
    @Test fun foregroundFullscreenStillHidesChromiumView() = checkVisibility(fullscreen = true, background = false)

    private fun checkVisibility(fullscreen: Boolean, background: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val container: ViewGroup = if (fullscreen) BackgroundPlaybackLayout(context, background)
                else BackgroundPlaybackWebView(context, background)
            val child = VisibilityProbe(context)
            try {
                container.addView(child)
                child.events.clear()
                container.dispatchWindowVisibilityChanged(View.VISIBLE)
                container.dispatchWindowVisibilityChanged(View.INVISIBLE)
                container.dispatchWindowVisibilityChanged(View.GONE)
                container.dispatchWindowVisibilityChanged(View.VISIBLE)
                assertEquals(
                    if (background) listOf(View.VISIBLE, View.VISIBLE)
                    else listOf(View.VISIBLE, View.INVISIBLE, View.GONE, View.VISIBLE), child.events
                )
            } finally {
                container.removeAllViews()
                (container as? BackgroundPlaybackWebView)?.destroy()
            }
        }
    }
}
