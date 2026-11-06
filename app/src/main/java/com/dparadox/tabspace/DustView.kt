package com.dparadox.tabspace

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Turns a snapshot into dust. Pieces break off from left to right, drift up and away and fade.
 * With [reverse] the same animation plays backwards: dust gathers into the picture.
 *
 * (screenLeft, screenTop) is where the snapshot sits on the screen.
 */
class DustView(
    c: Context,
    private val bmp: Bitmap,
    private val screenLeft: Int,
    private val screenTop: Int,
    private val reverse: Boolean
) : View(c) {

    private companion object {
        const val SPREAD = 0.55f      // share of the timeline used to stagger the pieces
        const val MAX_PIECES = 3200
    }

    private val density = c.resources.displayMetrics.density
    private val cell = max((4 * density).toInt(),
        ceil(sqrt(bmp.width.toDouble() * bmp.height / MAX_PIECES)).toInt())
    private val cols = (bmp.width + cell - 1) / cell
    private val rows = (bmp.height + cell - 1) / cell
    private val n = cols * rows
    private val delay = FloatArray(n)
    private val driftX = FloatArray(n)
    private val driftY = FloatArray(n)

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val loc = IntArray(2)
    private var progress = 0f
    private var finished = false

    init {
        isClickable = true   // swallow touches while the effect runs
        val r = Random(System.nanoTime())
        for (i in 0 until n) {
            val col = i % cols
            delay[i] = SPREAD * (0.75f * col / cols + 0.25f * r.nextFloat())
            driftX[i] = (20 + r.nextFloat() * 90) * density
            driftY[i] = (-70 + r.nextFloat() * 50) * density
        }
    }

    fun start(durationMs: Long, onEnd: () -> Unit) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            doOnEnd { if (!finished) { finished = true; onEnd() } }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        val ox = (screenLeft - loc[0]).toFloat()
        val oy = (screenTop - loc[1]).toFloat()
        val p = if (reverse) 1f - progress else progress

        for (i in 0 until n) {
            val t = ((p - delay[i]) / (1f - SPREAD)).coerceIn(0f, 1f)
            if (t >= 1f) continue
            val sx = (i % cols) * cell
            val sy = (i / cols) * cell
            val w = min(cell, bmp.width - sx)
            val h = min(cell, bmp.height - sy)
            src.set(sx, sy, sx + w, sy + h)

            if (t <= 0f) {
                paint.alpha = 255
                dst.set(ox + sx, oy + sy, ox + sx + w, oy + sy + h)
            } else {
                val e = t * t
                val cx = ox + sx + w / 2f + driftX[i] * e
                val cy = oy + sy + h / 2f + driftY[i] * e
                val s = 1f - 0.7f * e
                paint.alpha = ((1f - e) * 255).toInt()
                dst.set(cx - w * s / 2f, cy - h * s / 2f, cx + w * s / 2f, cy + h * s / 2f)
            }
            canvas.drawBitmap(bmp, src, dst, paint)
        }
    }
}
