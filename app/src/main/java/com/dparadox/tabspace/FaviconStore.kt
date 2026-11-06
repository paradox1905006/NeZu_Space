package com.dparadox.tabspace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/**
 * Site pictures for the home screen. Cached on disk, one per site.
 * [fetch] reads the web page and takes its best picture: the touch icon (large logo), then the
 * biggest normal icon, then /favicon.ico, then the social-share picture, then Google's icon service.
 */
object FaviconStore {
    private val mem = LruCache<String, Bitmap>(80)
    private val failed = HashSet<String>()
    private val pending = HashSet<String>()
    private val upgradeTried = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    private fun file(c: Context, host: String): File {
        val dir = File(c.filesDir, "favicons").apply { mkdirs() }
        return File(dir, host.replace(Regex("[^A-Za-z0-9.-]"), "_") + ".png")
    }

    fun get(c: Context, host: String): Bitmap? {
        mem.get(host)?.let { return it }
        val f = file(c, host)
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.path) ?: return null
        mem.put(host, bmp)
        return bmp
    }

    /** Saves the icon a page itself reported. */
    fun save(c: Context, host: String, bmp: Bitmap) {
        runCatching {
            file(c, host).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            mem.put(host, bmp)
        }
    }

    /**
     * Downloads the site picture in the background; [done] runs on the main thread when a new one is saved.
     * [pageUrl]: the page to read the icon from (default: the site's start page).
     * [force]: fetch again even if an earlier try failed.
     * [onlyIfBigger]: used to improve small old icons; tried once per site and kept only when larger.
     */
    fun fetch(c: Context, host: String, pageUrl: String? = null, force: Boolean = false,
              onlyIfBigger: Boolean = false, done: () -> Unit) {
        if (host.isBlank()) return
        if (onlyIfBigger) { if (!upgradeTried.add(host)) return }
        else if (!force && host in failed) return
        if (!pending.add(host)) return
        val app = c.applicationContext
        val oldWidth = if (onlyIfBigger) get(app, host)?.width ?: 0 else 0
        Thread {
            var ok = false
            try {
                val base = pageUrl?.takeIf { it.startsWith("http") } ?: "https://$host/"
                val bmp = runCatching { findBest(base) }.getOrNull() ?: googleIcon(host)
                if (bmp != null && (!onlyIfBigger || bmp.width > oldWidth)) { save(app, host, bmp); ok = true }
            } catch (_: Exception) {}
            main.post {
                pending.remove(host)
                if (ok) done() else if (!onlyIfBigger) failed.add(host)
            }
        }.start()
    }

    private fun googleIcon(host: String): Bitmap? = runCatching {
        val c = Net.open("https://www.google.com/s2/favicons?domain=" + Uri.encode(host) + "&sz=128") ?: return null
        try { decode(Net.readBytes(c, 1_000_000) ?: return null, false) } finally { c.disconnect() }
    }.getOrNull()

    // ── Page reading ──────────────────────────────────────────────────────────

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag)
            ?.let { it.groups[2]?.value ?: it.groups[3]?.value }

    private fun findBest(base: String): Bitmap? {
        val cands = mutableListOf<Pair<Int, String>>()      // score, url
        val conn = Net.open(base)
        if (conn != null) {
            val html = try { Net.readBytes(conn, 400_000)?.toString(Charsets.UTF_8) } finally { conn.disconnect() }
            if (html != null) {
                Regex("<link\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                    val tag = m.value
                    val rel = attr(tag, "rel")?.lowercase() ?: return@forEach
                    val href = attr(tag, "href") ?: return@forEach
                    if ("mask-icon" in rel || href.endsWith(".svg", true)) return@forEach
                    val size = attr(tag, "sizes")?.let { Regex("(\\d+)x\\d+").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    when {
                        "apple-touch-icon" in rel -> cands.add((1000 + (size ?: 180)) to href)
                        "icon" in rel             -> cands.add((size ?: 32) to href)
                    }
                }
                Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                    val tag = m.value
                    val key = (attr(tag, "property") ?: attr(tag, "name"))?.lowercase()
                    if (key == "og:image") attr(tag, "content")?.let { cands.add(1 to it) }
                }
            }
        }
        cands.add(500 to "/apple-touch-icon.png")
        cands.add(10 to "/favicon.ico")

        var tries = 0
        for ((score, href) in cands.sortedByDescending { it.first }) {
            if (tries++ >= 5) break
            val abs = runCatching { URL(URL(base), href).toString() }.getOrNull() ?: continue
            val c = runCatching { Net.open(abs, referer = base) }.getOrNull() ?: continue
            val bytes = try { Net.readBytes(c, 6_000_000) } catch (_: Exception) { null } finally { c.disconnect() }
            val bmp = bytes?.let { decode(it, square = true) } ?: continue
            if (bmp.width >= 16) return bmp
        }
        return null
    }

    /** Decodes (at most about 512 px), crops the centre to a square and keeps it at 256 px or less. */
    private fun decode(bytes: ByteArray, square: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        var bmp = raw
        if (square && raw.width != raw.height) {
            val side = min(raw.width, raw.height)
            bmp = Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side)
        }
        if (bmp.width > 256) bmp = Bitmap.createScaledBitmap(bmp, 256, 256, true)
        return bmp
    }
}
