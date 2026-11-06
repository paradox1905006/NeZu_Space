package com.dparadox.tabspace

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.color.MaterialColors
import java.io.File
import kotlin.math.max

/**
 * Appearance settings: theme mode, Material You (dynamic colour) and the custom
 * background picture with adjustable opacity.
 *
 * The picture is copied into the app's private storage, so it keeps working even
 * if the original file is moved or deleted.
 */
object ThemePrefs {

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    /** Bumped when something needs every open screen to be rebuilt (e.g. Material You toggled). */
    @Volatile var version = 0
        private set

    private fun p(c: Context) = c.getSharedPreferences("nezu_theme", Context.MODE_PRIVATE)

    // ── Theme mode ────────────────────────────────────────────────────────────
    fun mode(c: Context) = p(c).getInt("mode", MODE_SYSTEM)

    fun setMode(c: Context, mode: Int) {
        p(c).edit().putInt("mode", mode).apply()
        applyNightMode(mode)
    }

    fun applyNightMode(mode: Int) = AppCompatDelegate.setDefaultNightMode(
        when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    )

    // ── Material You ──────────────────────────────────────────────────────────
    fun dynamicColor(c: Context) = p(c).getBoolean("dynamic", true)

    fun setDynamicColor(c: Context, on: Boolean) {
        p(c).edit().putBoolean("dynamic", on).apply()
        version++
    }

    // ── Animations ────────────────────────────────────────────────────────────
    fun animations(c: Context) = p(c).getBoolean("animations", true)
    fun setAnimations(c: Context, on: Boolean) =
        p(c).edit().putBoolean("animations", on).apply()

    // ── Don't sleep: 0 = off, -1 = always while the app is open, otherwise minutes without touch ──
    fun keepAwake(c: Context) = p(c).getInt("keep_awake", 0)
    fun setKeepAwake(c: Context, minutes: Int) { p(c).edit().putInt("keep_awake", minutes).apply() }

    // ── Ad blocker ────────────────────────────────────────────────────────────
    fun adBlock(c: Context) = p(c).getBoolean("adblock", true)
    fun setAdBlock(c: Context, on: Boolean) {
        p(c).edit().putBoolean("adblock", on).apply()
        AdBlocker.enabled = on
    }

    // ── Background picture ────────────────────────────────────────────────────
    fun opacity(c: Context) = p(c).getInt("bg_opacity", 60)
    fun setOpacity(c: Context, percent: Int) =
        p(c).edit().putInt("bg_opacity", percent.coerceIn(0, 100)).apply()

    /** When on, web pages that have no background of their own let the picture show through. */
    fun webTransparent(c: Context) = p(c).getBoolean("web_transparent", false)
    fun setWebTransparent(c: Context, on: Boolean) =
        p(c).edit().putBoolean("web_transparent", on).apply()

    fun bgFile(c: Context) = File(c.filesDir, "app_background.jpg")
    fun hasBackground(c: Context) = bgFile(c).exists()

    private var cacheKey: String? = null
    private var cacheBmp: Bitmap? = null

    /** Copies the picked image into private storage (downscaled, EXIF-rotated). */
    fun importBackground(c: Context, uri: Uri): Boolean {
        return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2

        var bmp = c.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false

        val orientation = c.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        }

        bgFile(c).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        cacheKey = null; cacheBmp = null
        true
        } catch (_: Exception) {
            false
        }
    }

    fun clearBackground(c: Context) {
        bgFile(c).delete()
        cacheKey = null; cacheBmp = null
    }

    /** Small bitmap for the preview in Settings. */
    fun previewBitmap(c: Context): Bitmap? {
        if (!hasBackground(c)) return null
        return BitmapFactory.decodeFile(bgFile(c).path, BitmapFactory.Options().apply { inSampleSize = 2 })
    }

    /** Picture centre-cropped to exactly the screen size (cached). */
    private fun screenBitmap(c: Context, w: Int, h: Int): Bitmap? {
        val f = bgFile(c)
        if (!f.exists()) return null
        val key = "${f.lastModified()}_${w}x$h"
        if (key == cacheKey && cacheBmp != null) return cacheBmp
        val src = BitmapFactory.decodeFile(f.path) ?: return null
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            postScale(scale, scale)
            postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
        }
        Canvas(out).drawBitmap(src, m, null)
        if (src !== out) src.recycle()
        cacheKey = key; cacheBmp = out
        return out
    }

    /**
     * Paints the window background: solid surface colour, plus the picture on top of it
     * at the chosen opacity. Status/navigation bars become transparent so the picture
     * runs behind them too. Call from onCreate/onResume and whenever the slider moves.
     */
    fun applyToWindow(a: Activity) {
        val surface = MaterialColors.getColor(a, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val bars = MaterialColors.getColor(a, com.google.android.material.R.attr.colorSurfaceContainer, surface)
        val dm = a.resources.displayMetrics
        val bmp = screenBitmap(a, dm.widthPixels, dm.heightPixels)

        if (bmp == null) {
            a.window.setBackgroundDrawable(ColorDrawable(surface))
            a.window.statusBarColor = surface
            a.window.navigationBarColor = bars
            return
        }
        val pic = BitmapDrawable(a.resources, bmp).apply { alpha = opacity(a) * 255 / 100 }
        a.window.setBackgroundDrawable(LayerDrawable(arrayOf(ColorDrawable(surface), pic)))
        a.window.statusBarColor = Color.TRANSPARENT
        a.window.navigationBarColor = Color.TRANSPARENT
    }

    /** Makes a bar/sheet colour slightly see-through when a picture is set, so the picture shows. */
    fun chrome(c: Context, color: Int, alpha: Int = 0xD8): Int =
        if (hasBackground(c)) ColorUtils.setAlphaComponent(color, alpha) else color
}
