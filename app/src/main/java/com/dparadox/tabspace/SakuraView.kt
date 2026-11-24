package com.dparadox.tabspace

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Cherry blossom petals drifting down behind the whole UI.
 *
 * Each petal falls at its own speed, sways from side to side, spins and tumbles (the
 * horizontal squash fakes a 3D flip). Bigger petals are "closer": they fall faster and
 * are more opaque, which gives a soft parallax. A slow gust of wind pushes all of them.
 *
 * The view sits right above the window background (and the moving video background),
 * so petals show on the home screen and through the translucent glass bars.
 */
class SakuraView(c: Context) : View(c) {

    private class Petal {
        var x = 0f; var y = 0f
        var size = 0f          // px, petal height
        var fall = 0f          // px / s
        var swayAmp = 0f       // px
        var swayFreq = 0f      // rad / s
        var swayPhase = 0f
        var rot = 0f           // degrees
        var rotSpeed = 0f      // degrees / s
        var flip = 0f          // rad, 3D tumble
        var flipSpeed = 0f     // rad / s
        var paint = 0
        var alpha = 1f
    }

    private val density = c.resources.displayMetrics.density
    private val rnd = Random(System.nanoTime())
    private val petals = ArrayList<Petal>()
    private var lastFrame = 0L
    private var clock = 0f
    private var running = false
    private var fade = 0f                  // 0 hidden .. 1 visible, eased on show / hide
    private var fadeTarget = 0f

    /** Unit petal: height 1, base at the bottom, the typical notch at the tip. */
    private val shape = Path().apply {
        moveTo(0f, 0.5f)
        cubicTo(-0.46f, 0.26f, -0.44f, -0.34f, -0.13f, -0.5f)
        lineTo(0f, -0.37f)
        lineTo(0.13f, -0.5f)
        cubicTo(0.44f, -0.34f, 0.46f, 0.26f, 0f, 0.5f)
        close()
    }
    private val vein = Path().apply { moveTo(0f, 0.42f); quadTo(0.03f, 0f, 0f, -0.3f) }

    /** Base colour → tip colour pairs: deep sakura, classic pink, pale blush, almost white. */
    private val palette = arrayOf(
        0xFFF48FB1.toInt() to 0xFFFFE4EE.toInt(),
        0xFFF8A5C2.toInt() to 0xFFFFF0F5.toInt(),
        0xFFFFB7CE.toInt() to 0xFFFFF7FA.toInt(),
        0xFFFAD0DD.toInt() to 0xFFFFFFFF.toInt(),
    )
    private val paints = palette.map { (base, tip) ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(0f, 0.5f, 0f, -0.5f, base, tip, Shader.TileMode.CLAMP)
        }
    }
    private val veinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.035f
        color = Color.argb(70, 214, 90, 130)
        strokeCap = Paint.Cap.ROUND
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    /** Starts or stops the fall. Stopping fades the petals out instead of freezing them. */
    fun setRunning(on: Boolean) {
        val allowed = on && ValueAnimator.areAnimatorsEnabled()
        fadeTarget = if (allowed) 1f else 0f
        if (allowed && !running) {
            running = true
            lastFrame = 0L
            postInvalidateOnAnimation()
        } else if (!allowed && fade <= 0f) {
            running = false
        } else if (!allowed) {
            postInvalidateOnAnimation()   // keep drawing until the fade-out is done
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val area = (w / density) * (h / density)
        val count = (area / 9500f).toInt().coerceIn(14, 42)
        petals.clear()
        repeat(count) { petals.add(Petal().also { spawn(it, w, h, anywhere = true) }) }
    }

    private fun spawn(p: Petal, w: Int, h: Int, anywhere: Boolean) {
        val depth = rnd.nextFloat()                       // 0 far .. 1 near
        p.size = (9f + depth * 11f + rnd.nextFloat() * 3f) * density
        p.fall = (26f + depth * 46f + rnd.nextFloat() * 14f) * density
        p.swayAmp = (14f + rnd.nextFloat() * 34f) * density
        p.swayFreq = 0.6f + rnd.nextFloat() * 1.1f
        p.swayPhase = rnd.nextFloat() * 2f * PI.toFloat()
        p.rot = rnd.nextFloat() * 360f
        p.rotSpeed = (if (rnd.nextBoolean()) 1 else -1) * (25f + rnd.nextFloat() * 70f)
        p.flip = rnd.nextFloat() * 2f * PI.toFloat()
        p.flipSpeed = 1.2f + rnd.nextFloat() * 2.4f
        p.paint = rnd.nextInt(paints.size)
        p.alpha = 0.55f + depth * 0.4f
        p.x = rnd.nextFloat() * (w + p.swayAmp * 2) - p.swayAmp
        p.y = if (anywhere) rnd.nextFloat() * h else -p.size - rnd.nextFloat() * h * 0.3f
    }

    override fun onDraw(canvas: Canvas) {
        if (!running && fade <= 0f) return
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1_000_000_000f, 0.05f)
        lastFrame = now
        clock += dt

        // Ease the overall opacity towards its target (about a third of a second).
        fade += (fadeTarget - fade) * min(1f, dt * 6f)
        if (fadeTarget == 0f && fade < 0.01f) { fade = 0f; running = false; return }

        val w = width; val h = height
        // A slow gust: mostly calm, now and then a soft push to the right.
        val gust = (sin(clock * 0.23f) * 0.5f + 0.5f).let { it * it * it } * 38f * density
        val breeze = sin(clock * 0.11f + 1.3f) * 8f * density

        for (p in petals) {
            p.y += p.fall * dt
            p.x += (gust + breeze) * dt * (p.size / (20f * density))
            p.rot += p.rotSpeed * dt
            p.flip += p.flipSpeed * dt
            if (p.y - p.size > h || p.x - p.swayAmp > w + p.size * 2) spawn(p, w, h, anywhere = false)

            val sx = p.x + sin(clock * p.swayFreq + p.swayPhase) * p.swayAmp
            val tumble = cos(p.flip)                         // -1..1: petal seen face-on .. edge-on
            val squash = 0.25f + 0.75f * kotlin.math.abs(tumble)
            val paint = paints[p.paint]
            paint.alpha = (p.alpha * fade * (0.75f + 0.25f * kotlin.math.abs(tumble)) * 255).toInt()

            canvas.save()
            canvas.translate(sx, p.y)
            canvas.rotate(p.rot)
            canvas.scale(p.size * squash, p.size)
            canvas.drawPath(shape, paint)
            if (squash > 0.6f) {
                veinPaint.alpha = (70 * fade * p.alpha).toInt()
                canvas.drawPath(vein, veinPaint)
            }
            canvas.restore()
        }
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }
}
