package com.example.app

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app.ui.HoldToMinimizeLayout
import org.junit.Assert.*
import org.junit.Test

class HoldToMinimizeTest {
    private fun scenario(block: (HoldToMinimizeLayout, MutableList<Int>, (Int, Long, Float, Float) -> Unit) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val actions = mutableListOf<Int>()
            val layout = HoldToMinimizeLayout(instrumentation.targetContext).apply {
                dragEnabled = true
                addView(object : View(context) {
                    override fun onTouchEvent(event: MotionEvent): Boolean { actions += event.actionMasked; return true }
                })
                measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                layout(0, 0, 1000, 1000)
            }
            val send = { action: Int, elapsed: Long, x: Float, y: Float ->
                MotionEvent.obtain(1000, 1000 + elapsed, action, x, y, 0).also {
                    layout.dispatchTouchEvent(it); it.recycle()
                }
                Unit
            }
            block(layout, actions, send)
        }
    }

    @Test fun heldDownwardDragCancelsPlayerTouchAndMinimizesOnce() = scenario { layout, actions, send ->
        var minimized = 0
        var offset = 0f
        layout.onMinimize = { minimized++ }; layout.onDrag = { offset = it }
        val held = ViewConfiguration.getLongPressTimeout().toLong() + 50
        send(MotionEvent.ACTION_DOWN, 0, 50f, 50f)
        send(MotionEvent.ACTION_MOVE, held, 50f, 500f)
        assertTrue(offset > 0)
        assertEquals(MotionEvent.ACTION_CANCEL, actions.last())
        send(MotionEvent.ACTION_UP, held + 50, 50f, 500f)
        assertEquals(1, minimized); assertEquals(0f, offset, 0.001f)
    }

    @Test fun quickSwipeAndTapRemainWithPlayer() = scenario { layout, actions, send ->
        var minimized = false
        layout.onMinimize = { minimized = true }
        send(MotionEvent.ACTION_DOWN, 0, 50f, 50f)
        send(MotionEvent.ACTION_MOVE, 20, 50f, 500f)
        send(MotionEvent.ACTION_UP, 1000, 50f, 500f)
        assertFalse(minimized); assertFalse(actions.contains(MotionEvent.ACTION_CANCEL))
        send(MotionEvent.ACTION_DOWN, 0, 50f, 50f)
        send(MotionEvent.ACTION_UP, 50, 50f, 50f)
        assertEquals(MotionEvent.ACTION_UP, actions.last())
    }

    @Test fun cancelledDragResetsOffsetWithoutMinimizing() = scenario { layout, _, send ->
        var minimized = false
        var offset = 0f
        layout.onMinimize = { minimized = true }; layout.onDrag = { offset = it }
        val held = ViewConfiguration.getLongPressTimeout().toLong() + 50
        send(MotionEvent.ACTION_DOWN, 0, 50f, 50f)
        send(MotionEvent.ACTION_MOVE, held, 50f, 500f)
        send(MotionEvent.ACTION_CANCEL, held + 50, 50f, 500f)
        assertFalse(minimized); assertEquals(0f, offset, 0.001f)
    }

    @Test fun horizontalSeekingAndDisabledDragRemainWithPlayer() = scenario { layout, actions, send ->
        var minimized = false
        layout.onMinimize = { minimized = true }
        val held = ViewConfiguration.getLongPressTimeout().toLong() + 50
        send(MotionEvent.ACTION_DOWN, 0, 50f, 50f)
        send(MotionEvent.ACTION_MOVE, held, 500f, 50f)
        send(MotionEvent.ACTION_UP, held + 50, 500f, 50f)
        layout.dragEnabled = false
        send(MotionEvent.ACTION_DOWN, 0, 50f, 50f)
        send(MotionEvent.ACTION_MOVE, held, 50f, 500f)
        send(MotionEvent.ACTION_UP, held + 50, 50f, 500f)
        assertFalse(minimized); assertFalse(actions.contains(MotionEvent.ACTION_CANCEL))
    }
}
