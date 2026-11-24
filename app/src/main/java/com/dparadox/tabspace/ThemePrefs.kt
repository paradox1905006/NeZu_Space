package com.dparadox.tabspace

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.color.MaterialColors
import java.io.File
import java.util.WeakHashMap
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

    fun applyNightMode(mode: Int) {
        LockState.markExternalLaunch()   // light/dark rebuilds the screens: that is not leaving the app
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

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

    // ── Cherry blossom petals falling behind the UI ──
    fun sakura(c: Context) = p(c).getBoolean("sakura", true)
    fun setSakura(c: Context, on: Boolean) = p(c).edit().putBoolean("sakura", on).apply()

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

    /** The still picture: the picture itself, or the first frame of a GIF / video. Everything that needs "a picture" uses this. */
    fun bgFile(c: Context) = File(c.filesDir, "app_background.jpg")
    fun hasBackground(c: Context) = bgFile(c).exists()

    // Moving backgrounds: an animated GIF, or a looping video.
    const val TYPE_IMAGE = "image"
    const val TYPE_GIF = "gif"
    const val TYPE_VIDEO = "video"

    fun gifFile(c: Context) = File(c.filesDir, "app_background.gif")
    fun videoFile(c: Context) = File(c.filesDir, "app_background.mp4")

    fun bgType(c: Context): String = if (!hasBackground(c)) TYPE_IMAGE else p(c).getString("bg_type", TYPE_IMAGE) ?: TYPE_IMAGE
    private fun setBgType(c: Context, type: String) = p(c).edit().putString("bg_type", type).apply()

    /** Sound of the background video. Off by default. */
    fun videoSound(c: Context) = p(c).getBoolean("bg_video_sound", false)
    fun setVideoSound(c: Context, on: Boolean) = p(c).edit().putBoolean("bg_video_sound", on).apply()

    private const val MAX_GIF_BYTES = 30L * 1024 * 1024
    private const val MAX_VIDEO_BYTES = 150L * 1024 * 1024

    /** Picture, GIF or video picked in Settings. Returns false when it cannot be used. */
    fun importBackgroundMedia(c: Context, uri: Uri): Boolean {
        val mime = c.contentResolver.getType(uri).orEmpty()
        return when {
            mime == "image/gif" -> importGif(c, uri)
            mime.startsWith("video/") -> importVideo(c, uri)
            else -> importBackground(c, uri).also { ok ->
                if (ok) { gifFile(c).delete(); videoFile(c).delete(); setBgType(c, TYPE_IMAGE) }
            }
        }
    }

    private fun copyLimited(c: Context, uri: Uri, dest: File, maxBytes: Long): Boolean {
        val tmp = File(dest.path + ".tmp")
        return try {
            var total = 0L
            val input = c.contentResolver.openInputStream(uri) ?: return false
            input.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return false
                        out.write(buf, 0, n)
                    }
                }
            }
            dest.delete()
            tmp.renameTo(dest)
        } catch (_: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    private fun importGif(c: Context, uri: Uri): Boolean {
        if (!copyLimited(c, uri, gifFile(c), MAX_GIF_BYTES)) return false
        // The first frame becomes the still picture (used where nothing can animate).
        if (!importBackground(c, uri)) { gifFile(c).delete(); return false }
        videoFile(c).delete()
        setBgType(c, TYPE_GIF)
        return true
    }

    private fun importVideo(c: Context, uri: Uri): Boolean {
        val tmp = File(c.filesDir, "app_background_new.mp4")
        if (!copyLimited(c, uri, tmp, MAX_VIDEO_BYTES)) return false
        val frame: Bitmap? = try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(tmp.path)
                r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0L)
            } finally { runCatching { r.release() } }
        } catch (_: Exception) { null }
        if (frame == null) { tmp.delete(); return false }
        val saved = runCatching { bgFile(c).outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            .getOrDefault(false)
        if (!saved) { tmp.delete(); return false }
        videoFile(c).delete()
        if (!tmp.renameTo(videoFile(c))) { tmp.delete(); return false }
        gifFile(c).delete()
        cacheKey = null; cacheBmp = null
        setBgType(c, TYPE_VIDEO)
        return true
    }

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
        gifFile(c).delete()
        videoFile(c).delete()
        setBgType(c, TYPE_IMAGE)
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
    fun applyToWindow(a: Activity, videoOverlay: Boolean = false) {
        val surface = MaterialColors.getColor(a, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val bars = MaterialColors.getColor(a, com.google.android.material.R.attr.colorSurfaceContainer, surface)
        val dm = a.resources.displayMetrics
        val bmp = screenBitmap(a, dm.widthPixels, dm.heightPixels)

        if (bmp == null) {
            gifOwners.remove(a)
            a.window.setBackgroundDrawable(ColorDrawable(surface))
            a.window.statusBarColor = surface
            a.window.navigationBarColor = bars
            return
        }
        val type = bgType(a)

        // Video: the window keeps only the plain colour, VideoBackground draws the moving picture above it.
        if (type == TYPE_VIDEO && videoOverlay && videoFile(a).exists()) {
            gifOwners.remove(a)
            a.window.setBackgroundDrawable(ColorDrawable(surface))
            a.window.statusBarColor = Color.TRANSPARENT
            a.window.navigationBarColor = Color.TRANSPARENT
            return
        }

        // Animated GIF (Android 9+): an animated drawable is the window background.
        if (type == TYPE_GIF && Build.VERSION.SDK_INT >= 28 && gifFile(a).exists()) {
            val key = gifFile(a).lastModified().toString()
            val owned = gifOwners[a]
            if (owned != null && owned.first == key) {          // same GIF: only the opacity may have changed
                owned.second.alpha = opacity(a) * 255 / 100
            } else {
                val anim = gifDrawable(a)
                if (anim != null) {
                    a.window.setBackgroundDrawable(LayerDrawable(arrayOf(ColorDrawable(surface), anim)))
                    gifOwners[a] = key to anim
                }
            }
            if (gifOwners[a] != null) {
                a.window.statusBarColor = Color.TRANSPARENT
                a.window.navigationBarColor = Color.TRANSPARENT
                return
            }
        }

        gifOwners.remove(a)
        val pic = BitmapDrawable(a.resources, bmp).apply { alpha = opacity(a) * 255 / 100 }
        a.window.setBackgroundDrawable(LayerDrawable(arrayOf(ColorDrawable(surface), pic)))
        a.window.statusBarColor = Color.TRANSPARENT
        a.window.navigationBarColor = Color.TRANSPARENT
    }

    /** The GIF drawable each screen currently shows (decoding is slow, so slider moves only change its opacity). */
    private val gifOwners = WeakHashMap<Activity, Pair<String, CenterCropDrawable>>()

    @androidx.annotation.RequiresApi(28)
    private fun gifDrawable(a: Activity): CenterCropDrawable? = try {
        val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(gifFile(a))) { decoder, info, _ ->
            val longSide = max(info.size.width, info.size.height)
            if (longSide > 1080) decoder.setTargetSampleSize((longSide + 1079) / 1080)   // keep memory reasonable
        }
        if (d is AnimatedImageDrawable) {
            d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            d.start()
        }
        CenterCropDrawable(d).apply { alpha = opacity(a) * 255 / 100 }
    } catch (_: Throwable) {
        null
    }

    /** Makes a bar/sheet colour slightly see-through when a picture is set, so the picture shows. */
    fun chrome(c: Context, color: Int, alpha: Int = 0xD8): Int =
        if (hasBackground(c)) ColorUtils.setAlphaComponent(color, alpha) else color
}
