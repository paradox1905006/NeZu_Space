package com.dparadox.tabspace

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Detects a horizontal swipe anywhere on it (children keep working for taps).
 * [onSwipe] gets +1 for a swipe to the left (next) and -1 for a swipe to the right (previous).
 */
class SwipeFrameLayout @JvmOverloads constructor(
    c: Context, a: AttributeSet? = null, s: Int = 0
) : FrameLayout(c, a, s) {

    var onSwipe: ((Int) -> Unit)? = null
    var canSwipe: () -> Boolean = { true }

    /** Sees every touch on the bar first (liquid glass glow + jelly press). */
    var onTouchAt: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        onTouchAt?.invoke(e)
        return super.dispatchTouchEvent(e)
    }

    /** A child that handles its own gestures (e.g. the tab button). Touches that start on it are ignored here. */
    var excluded: View? = null

    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val minDist = 72 * c.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var ignore = false

    private fun startsOnExcluded(e: MotionEvent): Boolean {
        val v = excluded ?: return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return e.rawX >= loc[0] && e.rawX <= loc[0] + v.width &&
               e.rawY >= loc[1] && e.rawY <= loc[1] + v.height
    }

    private fun isHorizontal(e: MotionEvent): Boolean {
        val dx = e.x - downX
        val dy = e.y - downY
        return abs(dx) > slop && abs(dx) > abs(dy) * 1.5f
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; ignore = startsOnExcluded(e) }
            MotionEvent.ACTION_MOVE ->
                if (!ignore && !dragging && canSwipe() && isHorizontal(e)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; ignore = startsOnExcluded(e) }
            MotionEvent.ACTION_MOVE -> {
                if (!ignore && !dragging && canSwipe() && isHorizontal(e)) dragging = true
                if (dragging) getChildAt(0)?.translationX = (e.x - downX) * 0.3f
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val fired = dragging && abs(dx) >= minDist
                finish()
                if (fired) onSwipe?.invoke(if (dx < 0) 1 else -1)
            }
            MotionEvent.ACTION_CANCEL -> finish()
        }
        return true
    }

    private fun finish() {
        dragging = false
        getChildAt(0)?.animate()?.translationX(0f)?.setDuration(160)?.start()
    }
}
