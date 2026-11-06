package com.dparadox.tabspace

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Swipe an item of a RecyclerView sideways to dismiss it.
 * [onDismiss] receives the adapter position, the item view (still where the finger left it) and a
 * [restore] callback that slides the item back if the dismissal is not carried out.
 */
class SwipeDismissListener(
    private val onDismiss: (position: Int, view: View, restore: () -> Unit) -> Unit
) : RecyclerView.OnItemTouchListener {

    private var downX = 0f
    private var downY = 0f
    private var target: View? = null
    private var dragging = false
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(rv.context).scaledTouchSlop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                target = rv.findChildViewUnder(e.x, e.y)
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging && target != null && abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    velocity = VelocityTracker.obtain()
                    rv.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) target = null
        }
        return dragging
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        val v = target ?: return
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> v.translationX = e.x - downX
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val dx = e.x - downX
                val far = abs(dx) > v.width * 0.35f
                val fast = abs(vx) > 1200f && vx * dx > 0
                val pos = rv.getChildAdapterPosition(v)
                end()
                if ((far || fast) && pos != RecyclerView.NO_POSITION) {
                    onDismiss(pos, v) { slideBack(v) }
                } else slideBack(v)
            }
            MotionEvent.ACTION_CANCEL -> { end(); slideBack(v) }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    private fun end() {
        velocity?.recycle(); velocity = null
        dragging = false
        target = null
    }

    private fun slideBack(v: View) {
        v.animate().translationX(0f).setDuration(180).start()
    }
}
