package com.example.app.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** Let player controls handle taps/seeking; take over only after a hold and downward drag. */
class HoldToMinimizeLayout(context: Context) : FrameLayout(context) {
    var dragEnabled = false
    var onDrag: (Float) -> Unit = {}
    var onMinimize: () -> Unit = {}
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = 64 * resources.displayMetrics.density
    private var startX = 0f
    private var startY = 0f
    private var eligible = false
    private var dragging = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            startX = event.rawX; startY = event.rawY
            eligible = dragEnabled; dragging = false
        }
        val dx = event.rawX - startX
        val dy = event.rawY - startY
        if (event.pointerCount > 1 || !dragEnabled) eligible = false
        if (eligible && !dragging && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val held = event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()
            if ((!held && (abs(dx) > slop || abs(dy) > slop)) || (abs(dx) > slop && abs(dx) > abs(dy)) || dy < -slop) eligible = false
            else if (held && dy > slop) {
                dragging = true
                // Cancel the iframe's gesture before taking ownership, including WebView controls.
                MotionEvent.obtain(event).also { cancel ->
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
        if (dragging) {
            if (!eligible || event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_UP) {
                val minimize = eligible && event.actionMasked == MotionEvent.ACTION_UP && dy >= threshold
                dragging = false; eligible = false
                onDrag(0f)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (minimize) onMinimize()
            } else onDrag(dy.coerceIn(0f, height.toFloat()))
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        dragging = false; eligible = false; onDrag(0f)
        super.onDetachedFromWindow()
    }
}
