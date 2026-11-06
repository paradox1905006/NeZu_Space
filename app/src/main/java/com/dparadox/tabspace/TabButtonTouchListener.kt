package com.dparadox.tabspace

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Touch handling of the tab-count button in the bottom bar:
 *  - tap         → performClick (open / close the tab panel)
 *  - long press  → [onLongPress]  (new tab)
 *  - swipe left / right → [onSwipe] with +1 (next tab) / -1 (previous tab)
 * While dragging, [moveTarget] follows the finger a little so the swipe feels alive.
 */
class TabButtonTouchListener(
    context: Context,
    private val moveTarget: () -> View?,
    private val onLongPress: () -> Unit,
    private val onSwipe: (Int) -> Unit,
) : View.OnTouchListener {

    private val density = context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minDist = 28 * density
    private val maxDrag = 40 * density
    private val handler = Handler(Looper.getMainLooper())

    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var moved = false
    private var longFired = false
    private var longTask: Runnable? = null

    private fun cancelLong() { longTask?.let { handler.removeCallbacks(it) }; longTask = null }

    private fun settle() {
        moveTarget()?.animate()?.translationX(0f)?.setDuration(160)?.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                swiping = false; moved = false; longFired = false
                v.isPressed = true
                v.parent?.requestDisallowInterceptTouchEvent(true)
                val task = Runnable {
                    if (!swiping && !moved) {
                        longFired = true
                        v.isPressed = false
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onLongPress()
                    }
                }
                longTask = task
                handler.postDelayed(task, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && (abs(dx) > slop || abs(dy) > slop)) { moved = true; cancelLong() }
                if (!swiping && !longFired && abs(dx) > slop && abs(dx) > abs(dy)) {
                    swiping = true; v.isPressed = false
                }
                if (swiping) moveTarget()?.translationX = (dx * 0.5f).coerceIn(-maxDrag, maxDrag)
            }
            MotionEvent.ACTION_UP -> {
                cancelLong()
                v.isPressed = false
                val dx = e.rawX - downX
                when {
                    longFired -> {}
                    swiping -> if (abs(dx) >= minDist) {
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSwipe(if (dx < 0) 1 else -1)
                    }
                    !moved -> v.performClick()
                }
                settle()
            }
            MotionEvent.ACTION_CANCEL -> { cancelLong(); v.isPressed = false; settle() }
        }
        return true
    }
}
