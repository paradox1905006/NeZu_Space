package com.dparadox.tabspace

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Sound effects. Four events, each with its own on/off switch, a built-in default sound
 * (res/raw) and an optional custom sound the user picked (copied into private storage).
 *
 *  CHANGE   - tab change and profile change
 *  DELETE   - tab delete and profile delete
 *  CREATE   - new tab and new profile
 *  APP_OPEN - the app starts
 */
object SoundFx {

    enum class Event(val key: String, val title: String, val hint: String, val rawRes: Int) {
        CHANGE("change", "Tab / profile change", "Plays when you switch tab or profile", R.raw.snd_tab_change),
        DELETE("delete", "Tab / profile delete", "Plays when you close a tab or delete a profile", R.raw.snd_delete),
        CREATE("create", "New tab / profile", "Plays when a new tab or profile is created", R.raw.snd_create),
        APP_OPEN("app_open", "App open", "Plays when NeZu Space starts", R.raw.snd_app_open),
    }

    /** Longest custom sound accepted. Sound effects should be short. */
    private const val MAX_MS = 15_000L
    private const val MAX_BYTES = 5L * 1024 * 1024

    private fun p(c: Context) = c.getSharedPreferences("nezu_sound", Context.MODE_PRIVATE)

    // ── Switches ─────────────────────────────────────────────────────────────
    fun masterOn(c: Context) = p(c).getBoolean("master", true)
    fun setMasterOn(c: Context, on: Boolean) {
        p(c).edit().putBoolean("master", on).apply()
        if (!on) stopAll()
    }

    fun eventOn(c: Context, e: Event) = p(c).getBoolean("on_${e.key}", true)
    fun setEventOn(c: Context, e: Event, on: Boolean) {
        p(c).edit().putBoolean("on_${e.key}", on).apply()
        if (!on) stop(e)
    }

    // ── Custom sounds ────────────────────────────────────────────────────────
    private fun customFile(c: Context, e: Event) = File(c.filesDir, "sound_${e.key}.snd")

    fun hasCustom(c: Context, e: Event) = customFile(c, e).exists()
    fun customName(c: Context, e: Event): String? =
        if (hasCustom(c, e)) p(c).getString("name_${e.key}", "Custom sound") else null

    /** Copies the picked audio into private storage. Returns null on success, otherwise a message to show. */
    fun importCustom(c: Context, e: Event, uri: Uri): String? {
        val app = c.applicationContext
        val tmp = File(app.cacheDir, "sound_import.tmp")
        return try {
            var total = 0L
            app.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return "Sound is too big (max 5 MB)"
                        out.write(buf, 0, n)
                    }
                }
            } ?: return "Could not open that file"

            // Must be real audio, and short.
            val mmr = MediaMetadataRetriever()
            val ms = try {
                mmr.setDataSource(tmp.path)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally { runCatching { mmr.release() } }
            if (ms == null) return "That file is not a sound"
            if (ms > MAX_MS) return "Sound is too long (max ${MAX_MS / 1000} seconds)"

            tmp.copyTo(customFile(app, e), overwrite = true)
            p(app).edit().putString("name_${e.key}", displayName(app, uri)).apply()
            null
        } catch (_: Exception) {
            "That file is not a sound"
        } finally {
            tmp.delete()
        }
    }

    fun resetToDefault(c: Context, e: Event) {
        stop(e)
        customFile(c, e).delete()
        p(c).edit().remove("name_${e.key}").apply()
    }

    private fun displayName(c: Context, uri: Uri): String {
        runCatching {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: "Custom sound"
            }
        }
        return uri.lastPathSegment ?: "Custom sound"
    }

    // ── Playback ─────────────────────────────────────────────────────────────
    private val players = HashMap<Event, MediaPlayer>()

    /** Plays the event's sound if the master switch and the event's own switch are on. */
    fun play(c: Context, e: Event) {
        if (!masterOn(c) || !eventOn(c, e)) return
        start(c.applicationContext, e)
    }

    /** Settings preview: plays regardless of the switches. */
    fun preview(c: Context, e: Event) = start(c.applicationContext, e)

    private fun start(app: Context, e: Event) {
        stop(e)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            val custom = customFile(app, e)
            if (custom.exists()) {
                mp.setDataSource(custom.path)
            } else {
                app.resources.openRawResourceFd(e.rawRes).use {
                    mp.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            }
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { release(e, it) }
            mp.setOnErrorListener { m, _, _ -> release(e, m); true }
            synchronized(players) { players[e] = mp }
            mp.prepareAsync()
        } catch (_: Exception) {
            runCatching { mp.release() }
            synchronized(players) { if (players[e] === mp) players.remove(e) }
        }
    }

    private fun release(e: Event, mp: MediaPlayer) {
        synchronized(players) { if (players[e] === mp) players.remove(e) }
        runCatching { mp.release() }
    }

    fun stop(e: Event) {
        val mp = synchronized(players) { players.remove(e) } ?: return
        runCatching { if (mp.isPlaying) mp.stop() }
        runCatching { mp.release() }
    }

    fun stopAll() = Event.values().forEach { stop(it) }
}
