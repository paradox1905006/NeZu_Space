package com.dparadox.tabspace

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * "Liquid glass" surface for the search bar, the dock and the sheets.
 *
 * Layers (bottom to top), all clipped to the rounded shape:
 *  1. translucent tint (the theme surface colour, see-through so petals / pictures show behind)
 *  2. a soft top shine and a faint bottom caustic, like light bending through thick glass
 *  3. a slowly wandering liquid highlight that never stops moving
 *  4. a specular sheen that sweeps across every few seconds
 *  5. a glow that follows the finger while it touches the surface ([touch])
 *  6. a bright refractive rim, lighter at the top-left and bottom-right edges
 *
 * [radii] are the 8 corner radii in px, same order as Path.addRoundRect.
 */
class LiquidGlassDrawable(
    private val radii: FloatArray,
    private val density: Float,
) : Drawable(), Animatable {

    /** Surface colour; its alpha is capped so the glass is always see-through. */
    var glassColor: Int = Color.WHITE
        set(v) { field = v; updateFill(); invalidateSelf() }

    /** Dark theme: highlights are toned down so they don't glare. */
    var dark: Boolean = false
        set(v) { field = v; rebuildShaders(); invalidateSelf() }

    /** Most see-through the glass may get (0..255). */
    var maxAlpha: Int = 0xC4
        set(v) { field = v; updateFill(); invalidateSelf() }

    private val path = Path()
    private val rimPath = Path()
    private val box = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caustic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val m = Matrix()

    private var phase = 0f                 // 0..1, one full "breath" of the liquid
    private var touchX = 0f
    private var touchY = 0f
    private var press = 0f                 // 0..1 touch glow strength
    private var pressAnim: ValueAnimator? = null

    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 9000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidateSelf() }
    }

    private fun hi(a: Int) = Color.argb(if (dark) (a * 0.55f).toInt() else a, 255, 255, 255)

    private fun updateFill() {
        fill.color = ColorUtils.setAlphaComponent(glassColor, minOf(Color.alpha(glassColor), maxAlpha))
    }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        box.set(b)
        path.reset(); path.addRoundRect(box, radii, Path.Direction.CW)
        val inset = 0.6f * density
        val r2 = FloatArray(8) { max(0f, radii[it] - inset) }
        rimPath.reset()
        rimPath.addRoundRect(RectF(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset), r2, Path.Direction.CW)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        val b = box
        if (b.isEmpty) return
        val h = b.height(); val w = b.width()
        shine.shader = LinearGradient(0f, b.top, 0f, b.top + h * 0.55f,
            intArrayOf(hi(0x5A), hi(0x14), Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        caustic.shader = LinearGradient(0f, b.bottom, 0f, b.bottom - h * 0.35f,
            hi(0x22), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val band = max(h * 1.6f, w * 0.22f)
        sheen.shader = LinearGradient(0f, 0f, band, band * 0.45f,
            intArrayOf(Color.TRANSPARENT, hi(0x3C), hi(0x60), hi(0x3C), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f), Shader.TileMode.CLAMP)
        rim.strokeWidth = 1.2f * density
        rim.shader = LinearGradient(b.left, b.top, b.right, b.bottom,
            intArrayOf(hi(0xB0), hi(0x30), hi(0x14), hi(0x30), hi(0x90)),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
        blob.shader = RadialGradient(0f, 0f, 1f, hi(0x30), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(0f, 0f, 1f, intArrayOf(hi(0x70), hi(0x24), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = box
        if (b.isEmpty) return
        val w = b.width(); val h = b.height()
        canvas.save()
        canvas.clipPath(path)
        canvas.drawPath(path, fill)
        canvas.drawRect(b, shine)
        canvas.drawRect(b, caustic)

        // Liquid highlight: a soft blob drifting along a lazy figure-eight.
        val t = phase * 2f * PI.toFloat()
        val bx = b.left + w * (0.5f + 0.38f * sin(t))
        val by = b.top + h * (0.28f + 0.18f * sin(2f * t))
        val br = max(h * 1.4f, w * 0.18f) * (0.9f + 0.1f * cos(3f * t))
        m.reset(); m.setScale(br * 1.6f, br); m.postTranslate(bx, by)
        blob.shader.setLocalMatrix(m)
        canvas.drawRect(b, blob)

        // Specular sheen: sweeps once per loop during the first 30 %, then rests.
        if (phase < 0.3f) {
            val p = phase / 0.3f
            val e = p * p * (3f - 2f * p)
            val band = max(h * 1.6f, w * 0.22f)
            m.reset(); m.setTranslate(b.left - band + (w + band * 2f) * e - band * 0.5f, b.top - band * 0.2f)
            sheen.shader.setLocalMatrix(m)
            canvas.drawRect(b, sheen)
        }

        // Finger glow.
        if (press > 0.01f) {
            val gr = max(h * 1.3f, 72f * density) * (0.7f + 0.3f * press)
            m.reset(); m.setScale(gr, gr); m.postTranslate(b.left + touchX, b.top + touchY)
            glow.shader.setLocalMatrix(m)
            glow.alpha = (press * 255).toInt()
            canvas.drawRect(b, glow)
        }
        canvas.restore()
        canvas.drawPath(rimPath, rim)
    }

    /** Finger position relative to the drawable bounds; [down] false fades the glow out. */
    fun touch(x: Float, y: Float, down: Boolean) {
        touchX = x; touchY = y
        val target = if (down) 1f else 0f
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(press, target).apply {
            duration = if (down) 140 else 420
            interpolator = DecelerateInterpolator()
            addUpdateListener { press = it.animatedValue as Float; invalidateSelf() }
            start()
        }
        invalidateSelf()
    }

    override fun getOutline(outline: Outline) {
        // Uniform corners only; the sheet (top corners) falls back to its card outline.
        if (radii.all { it == radii[0] }) outline.setRoundRect(bounds, radii[0]) else outline.setRect(bounds)
        outline.alpha = 0f
    }

    override fun start() { if (!loop.isStarted) loop.start() else loop.resume() }
    override fun stop() { loop.pause() }
    override fun isRunning() = loop.isRunning && !loop.isPaused

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
