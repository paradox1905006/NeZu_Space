package com.dparadox.tabspace

import android.app.Dialog
import android.content.res.Configuration
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.core.view.doOnLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors

/** Helpers that put the liquid glass look on views and sheets. */
object LiquidGlass {

    fun isDark(v: View) = (v.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Puts (or reuses) a glass background on [v] with uniform [radiusDp] corners. */
    fun apply(v: View, radiusDp: Float, tint: Int): LiquidGlassDrawable =
        apply(v, FloatArray(8) { radiusDp * v.resources.displayMetrics.density }, tint)

    fun apply(v: View, radii: FloatArray, tint: Int): LiquidGlassDrawable {
        val d = (v.background as? LiquidGlassDrawable)
            ?: LiquidGlassDrawable(radii, v.resources.displayMetrics.density).also {
                v.background = it
                v.backgroundTintList = null
            }
        d.dark = isDark(v)
        d.glassColor = tint
        if (ThemePrefs.animations(v.context)) d.start() else d.stop()
        return d
    }

    /**
     * Jelly press for a whole bar: it squishes a little under the finger and wobbles back.
     * [glass] gets the finger position for its glow. Call from a parent's touch dispatch.
     */
    fun onBarTouch(bar: View, glass: LiquidGlassDrawable?, e: MotionEvent, offsetX: Float, offsetY: Float) {
        val x = e.x - offsetX; val y = e.y - offsetY
        val animate = ThemePrefs.animations(bar.context)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                glass?.touch(x, y, true)
                if (animate) {
                    bar.pivotX = x.coerceIn(0f, bar.width.toFloat()); bar.pivotY = bar.height / 2f
                    bar.animate().scaleX(1.025f).scaleY(0.94f).setDuration(140).setInterpolator(null).start()
                }
            }
            MotionEvent.ACTION_MOVE -> glass?.touch(x, y, true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                glass?.touch(x, y, false)
                if (animate) bar.animate().scaleX(1f).scaleY(1f).setDuration(520)
                    .setInterpolator(OvershootInterpolator(3.2f)).start()
            }
        }
    }

    /** Glass sheet: translucent rounded background and, on Android 12+, a blur of what is behind. */
    fun styleSheet(sheet: BottomSheetDialog) {
        sheet.setOnShowListener {
            val panel = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val r = 28f * panel.resources.displayMetrics.density
            val tint = MaterialColors.getColor(panel, com.google.android.material.R.attr.colorSurfaceContainerLow)
            panel.doOnLayout {
                apply(panel, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), tint).maxAlpha = 0xD8
            }
            blurBehind(sheet)
        }
    }

    fun blurBehind(dialog: Dialog, radiusDp: Int = 24) {
        if (Build.VERSION.SDK_INT < 31) return
        val w = dialog.window ?: return
        w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        w.attributes = w.attributes.also { it.blurBehindRadius = (radiusDp * dialog.context.resources.displayMetrics.density).toInt() }
    }
}
