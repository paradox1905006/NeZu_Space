package com.dparadox.tabspace

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Book page turn. A picture of the old page is bent around a moving fold line like a page of a
 * book: it lifts, curls over a roller that grows and shrinks, shows its (paper) back and rolls out
 * of sight, revealing the real new page underneath. Forward = curls away to the left, backward = right.
 */
class PageCurlView(
    c: Context,
    page: Bitmap,
    private val forward: Boolean
) : View(c) {

    private companion object { const val COLS = 72 }

    /** Backward turns are drawn mirrored, so the picture is mirrored once up front. */
    private val bmp: Bitmap = if (forward) page else Bitmap.createBitmap(
        page, 0, 0, page.width, page.height, Matrix().apply { preScale(-1f, 1f) }, true)

    private val verts = FloatArray((COLS + 1) * 2 * 2)
    private val colors = IntArray((COLS + 1) * 2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fx = Paint()
    private val density = c.resources.displayMetrics.density

    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private var animator: ValueAnimator? = null

    init { isClickable = true }

    fun start(durationMs: Long, onEnd: () -> Unit) {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = PathInterpolator(0.45f, 0f, 0.25f, 1f)
            addUpdateListener { progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (!forward && !bmp.isRecycled) bmp.recycle()
                    onEnd()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pi = PI.toFloat()

        // Roller grows while the page lifts and shrinks again as it leaves.
        val r = w * (0.055f + 0.10f * sin(pi * progress))
        val fold = (w + pi * r) * (1f - progress) - pi * r

        if (!forward) { canvas.save(); canvas.scale(-1f, 1f, w / 2f, 0f) }

        for (i in 0..COLS) {
            val u = w * i / COLS
            val d = u - fold
            val nx: Float
            val shade: Float
            val scale: Float
            if (d <= 0f) {
                nx = u; shade = 1f; scale = 1f
            } else {
                val th = d / r
                if (th < pi) {
                    nx = fold + r * sin(th)
                    val lift = (1f - cos(th)) / 2f
                    shade = 0.74f + 0.26f * cos(th)
                    scale = 1f + 0.05f * lift
                } else {
                    nx = fold - (d - pi * r)
                    shade = 0.78f
                    scale = 1.05f
                }
            }
            val top = h / 2f - h / 2f * scale
            val bot = h / 2f + h / 2f * scale
            verts[i * 2] = nx;               verts[i * 2 + 1] = top
            verts[(COLS + 1 + i) * 2] = nx;  verts[(COLS + 1 + i) * 2 + 1] = bot
            val g = (shade * 255).toInt().coerceIn(0, 255)
            val col = Color.rgb(g, g, g)
            colors[i] = col; colors[COLS + 1 + i] = col
        }

        val rollRight = min(w, fold + r)                       // right edge of the roller
        val backLeft = max(0f, 2f * fold + pi * r - w)         // left edge of the folded-back page
        val fade = 1f - progress * progress

        // Shadow on the page that is being revealed.
        if (rollRight < w) {
            val len = 70 * density
            fx.shader = LinearGradient(rollRight, 0f, rollRight + len, 0f,
                Color.argb((120 * fade).toInt(), 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(max(0f, rollRight), 0f, min(w, rollRight + len), h, fx)
        }

        canvas.drawBitmapMesh(bmp, COLS, 1, verts, 0, colors, 0, paint)

        // Back of the page: pale paper over the mirrored print, darker where it meets the roller.
        if (fold > backLeft + 1f) {
            fx.shader = LinearGradient(backLeft, 0f, fold, 0f,
                Color.argb(190, 245, 242, 235), Color.argb(150, 225, 222, 215), Shader.TileMode.CLAMP)
            canvas.drawRect(backLeft, 0f, fold, h, fx)
            // Shadow the folded-back page throws on the page below.
            val len = 40 * density
            fx.shader = LinearGradient(backLeft, 0f, backLeft - len, 0f,
                Color.argb((90 * fade).toInt(), 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(max(0f, backLeft - len), 0f, backLeft, h, fx)
        }

        // Soft light along the top of the roller.
        if (fold < w && fold + r > 0f) {
            fx.shader = LinearGradient(max(fold, 0f), 0f, rollRight, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(80, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(max(fold, 0f), 0f, rollRight, h, fx)
        }
        fx.shader = null

        if (!forward) canvas.restore()
    }
}
