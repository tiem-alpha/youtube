package com.example.app.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** Let player controls handle taps/seeking; take over a predominantly downward drag. */
class HoldToMinimizeLayout(context: Context) : FrameLayout(context) {
    var dragEnabled = false
    var dragBlocked: () -> Boolean = { false }
    var onDrag: (Float) -> Unit = {}
    var onMinimize: () -> Unit = {}
    var onExpand: (() -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = 64 * resources.displayMetrics.density
    private var startX = 0f
    private var startY = 0f
    private var eligible = false
    private var dragging = false

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // A control/scroll container owns the whole gesture, including at its scroll edge.
        // Releasing interception must not turn that same touch into a minimize gesture.
        if (disallowIntercept && !dragging) eligible = false
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (onExpand != null) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                startX = event.rawX; startY = event.rawY; eligible = true
            }
            if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) eligible = false
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val dx = abs(event.rawX - startX)
                val dy = event.rawY - startY
                if (eligible && ((dx <= slop && abs(dy) <= slop) || (-dy >= threshold && -dy > dx * 1.5f))) onExpand?.invoke()
                eligible = false
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            startX = event.rawX; startY = event.rawY
            eligible = dragEnabled && !dragBlocked(); dragging = false
        }
        val dx = event.rawX - startX
        val dy = event.rawY - startY
        if (event.pointerCount > 1 || !dragEnabled || dragBlocked()) eligible = false
        if (eligible && !dragging && event.actionMasked == MotionEvent.ACTION_MOVE) {
            if ((abs(dx) > slop && abs(dx) > abs(dy)) || dy < -slop) eligible = false
            else if (dy > slop && dy > abs(dx) * 1.5f) {
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
