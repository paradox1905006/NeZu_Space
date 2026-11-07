package com.dparadox.tabspace

import android.app.Activity
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.max

/**
 * Plays the chosen background video (looped, centre-cropped) behind the whole app UI.
 * The view sits at the very back of the window, so the translucent bars, the home screen and
 * (when "Show behind web pages" is on) transparent web pages all show the video through.
 *
 * Call [refresh] from onResume, [pause] from onPause and [release] from onDestroy.
 */
class VideoBackground(private val activity: Activity) {

    private var texture: TextureView? = null
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var videoW = 0
    private var videoH = 0
    private var wantPlay = false

    /** Shows, updates or removes the video according to the current settings. */
    fun refresh() {
        val on = ThemePrefs.bgType(activity) == ThemePrefs.TYPE_VIDEO &&
            ThemePrefs.videoFile(activity).exists()
        if (!on) { remove(); return }
        val tv = texture ?: addView()
        tv.alpha = ThemePrefs.opacity(activity) / 100f
        applyVolume()
        resume()
    }

    fun resume() {
        wantPlay = true
        player?.let { runCatching { if (!it.isPlaying) it.start() } }
    }

    fun pause() {
        wantPlay = false
        player?.let { runCatching { if (it.isPlaying) it.pause() } }
    }

    fun release() = remove()

    // ── Internals ────────────────────────────────────────────────────────────

    private fun addView(): TextureView {
        val tv = TextureView(activity).apply {
            isOpaque = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    surface = Surface(st)
                    prepare()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fit()
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    stopPlayer()
                    surface?.release(); surface = null
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        // Behind everything: the window background is drawn first, then this view, then the app UI.
        (activity.window.decorView as ViewGroup).addView(tv, 0)
        texture = tv
        return tv
    }

    private fun remove() {
        wantPlay = false
        stopPlayer()
        texture?.let { (it.parent as? ViewGroup)?.removeView(it) }
        texture = null
        surface?.release(); surface = null
    }

    private fun prepare() {
        stopPlayer()
        val s = surface ?: return
        val file = ThemePrefs.videoFile(activity)
        if (!file.exists()) return
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
            mp.setDataSource(file.path)
            mp.setSurface(s)
            mp.isLooping = true
            mp.setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; fit() }
            mp.setOnPreparedListener { if (wantPlay) it.start() }
            mp.setOnErrorListener { _, _, _ -> stopPlayer(); true }
            player = mp
            applyVolume()
            mp.prepareAsync()
        } catch (_: Exception) {
            runCatching { mp.release() }
            player = null
        }
    }

    private fun applyVolume() {
        val v = if (ThemePrefs.videoSound(activity)) 1f else 0f
        runCatching { player?.setVolume(v, v) }
    }

    private fun stopPlayer() {
        val mp = player ?: return
        player = null
        runCatching { mp.setOnPreparedListener(null); mp.setOnErrorListener(null) }
        runCatching { mp.release() }
    }

    /** TextureView stretches the video to its size; scale it back up so it covers the screen without distortion. */
    private fun fit() {
        val tv = texture ?: return
        val w = tv.width.toFloat()
        val h = tv.height.toFloat()
        if (videoW <= 0 || videoH <= 0 || w <= 0f || h <= 0f) return
        val cover = max(w / videoW, h / videoH)
        val sx = videoW * cover / w
        val sy = videoH * cover / h
        tv.setTransform(Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
    }
}
