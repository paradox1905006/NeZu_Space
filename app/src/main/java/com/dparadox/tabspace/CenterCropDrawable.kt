package com.dparadox.tabspace

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * Draws [inner] scaled so it covers the whole bounds (centre-crop), keeping its aspect ratio.
 * Used for animated GIF backgrounds: AnimatedImageDrawable would otherwise stretch to the screen.
 * Animation frames reach the screen through the normal Drawable.Callback chain.
 */
class CenterCropDrawable(val inner: Drawable) : Drawable(), Drawable.Callback {

    init { inner.callback = this }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val iw = inner.intrinsicWidth
        val ih = inner.intrinsicHeight
        if (iw <= 0 || ih <= 0 || b.isEmpty) return
        val scale = max(b.width() / iw.toFloat(), b.height() / ih.toFloat())
        val dw = iw * scale
        val dh = ih * scale
        val save = canvas.save()
        canvas.clipRect(b)
        canvas.translate(b.left + (b.width() - dw) / 2f, b.top + (b.height() - dh) / 2f)
        canvas.scale(scale, scale)
        inner.setBounds(0, 0, iw, ih)
        inner.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { inner.alpha = alpha; invalidateSelf() }
    override fun getAlpha(): Int = inner.alpha
    override fun setColorFilter(cf: ColorFilter?) { inner.colorFilter = cf }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        inner.setVisible(visible, restart)
        return super.setVisible(visible, restart)
    }

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
}
