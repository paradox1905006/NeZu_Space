package com.dparadox.tabspace

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Own downloader with pause / resume (HTTP Range). Keeps running in the background through [DownloadService].
 * The file grows as "<id>.part" in the app's folder; when it is complete it is copied to the phone's Downloads folder.
 */
object DownloadEngine {
    const val CHANNEL_PROGRESS = "nezu_dl_progress"
    const val CHANNEL_DONE = "nezu_dl_done"
    private const val CANCELLED = -2

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    /** The record objects that belong to downloads started in this process (shared with the UI). */
    private val live = ConcurrentHashMap<String, DownloadRecord>()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Called on the main thread when a download changes state (finished, paused, failed). */
    @Volatile var listener: (() -> Unit)? = null

    private class Job(val rec: DownloadRecord, val cookie: String, @Volatile var rename: Boolean) {
        @Volatile var pause = false
        @Volatile var cancel = false
        @Volatile var conn: HttpURLConnection? = null
    }

    fun init(c: Context) {
        app = c.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Downloads in progress",
            NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished downloads",
            NotificationManager.IMPORTANCE_DEFAULT))
    }

    private val prefs get() = app.getSharedPreferences("nezu_dl", Context.MODE_PRIVATE)

    // ── State that survives the app being closed ─────────────────────────────

    fun saveState(rec: DownloadRecord) {
        if (!rec.isEngine) return
        runCatching {
            prefs.edit().putString(rec.id, JSONObject()
                .put("status", rec.status).put("downloaded", rec.downloadedBytes)
                .put("total", rec.totalBytes).put("path", rec.filePath ?: "")
                .put("name", rec.fileName).toString()).apply()
        }
    }

    /** Returns the running object of this download when there is one, otherwise [rec] with its newest saved state. */
    fun resolve(rec: DownloadRecord): DownloadRecord {
        if (!rec.isEngine) return rec
        live[rec.id]?.let { return it }
        runCatching {
            prefs.getString(rec.id, null)?.let { s ->
                val o = JSONObject(s)
                rec.status = o.optInt("status", rec.status)
                rec.downloadedBytes = o.optLong("downloaded", rec.downloadedBytes)
                rec.totalBytes = o.optLong("total", rec.totalBytes)
                o.optString("path").ifBlank { null }?.let { rec.filePath = it }
                o.optString("name").ifBlank { null }?.let { rec.fileName = it }
            }
        }
        // The app was closed while it was downloading: it can be resumed.
        if (rec.isActive) rec.status = DownloadManager.STATUS_PAUSED
        return rec
    }

    // ── Control ──────────────────────────────────────────────────────────────

    fun activeRecords(): List<DownloadRecord> = jobs.values.map { it.rec }

    fun isRunning(rec: DownloadRecord) = jobs.containsKey(rec.id)

    /** Starts or resumes. [rename]: take the file name from the server's answer (links pasted by hand). */
    fun start(rec: DownloadRecord, rename: Boolean = false) {
        if (!rec.isEngine || jobs.containsKey(rec.id)) return
        live[rec.id] = rec
        val cookie = runCatching { CookieManager.getInstance().getCookie(rec.url) }.getOrNull().orEmpty()
        val job = Job(rec, cookie, rename)
        jobs[rec.id] = job
        rec.status = DownloadManager.STATUS_RUNNING
        rec.speed = 0
        saveState(rec)
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (_: Exception) { /* downloads still run while the app is open */ }
        Thread({ work(job) }, "dl-${rec.id}").start()
    }

    fun pause(rec: DownloadRecord) {
        val job = jobs[rec.id] ?: return
        job.pause = true
        rec.status = DownloadManager.STATUS_PAUSED
        rec.speed = 0
        breakConnection(job)
    }

    /** Stops the download and deletes its half-finished file. A finished file in Downloads is left alone. */
    fun discard(rec: DownloadRecord) {
        if (!rec.isEngine) return
        val job = jobs[rec.id]
        if (job != null) { job.cancel = true; breakConnection(job) }
        else partFile(rec).delete()
        live.remove(rec.id)
        runCatching { prefs.edit().remove(rec.id).apply() }
    }

    private fun breakConnection(job: Job) {
        Thread { runCatching { job.conn?.disconnect() } }.start()
    }

    // ── The finished file ────────────────────────────────────────────────────

    fun fileExists(rec: DownloadRecord): Boolean {
        val p = rec.filePath ?: return false
        return try {
            if (p.startsWith("content://")) {
                app.contentResolver.openFileDescriptor(Uri.parse(p), "r")?.close(); true
            } else File(p).exists()
        } catch (_: Exception) { false }
    }

    fun uriOf(rec: DownloadRecord): Uri? {
        val p = rec.filePath ?: return null
        return try {
            if (p.startsWith("content://")) Uri.parse(p)
            else FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", File(p))
        } catch (_: Exception) { null }
    }

    fun deleteFile(rec: DownloadRecord) {
        val p = rec.filePath
        runCatching {
            if (p != null) {
                if (p.startsWith("content://")) app.contentResolver.delete(Uri.parse(p), null, null)
                else File(p).delete()
            }
        }
        discard(rec)
    }

    /**
     * File name for a download. Unlike URLUtil.guessFileName it keeps the real extension of the link
     * (.apk, .zip ...) even when the server says "application/octet-stream" (that made ".bin" files).
     */
    fun fileNameFor(url: String, contentDisposition: String?, mime: String?): String {
        var name: String? = null
        val cd = contentDisposition.orEmpty()
        Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
            name = runCatching { java.net.URLDecoder.decode(it.groupValues[1].trim().trim('"'), "UTF-8") }.getOrNull()
        }
        if (name.isNullOrBlank()) {
            Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let {
                name = it.groupValues[1].trim()
            }
        }
        if (name.isNullOrBlank()) {
            val seg = runCatching { Uri.parse(url).lastPathSegment }.getOrNull().orEmpty()
            if (seg.contains('.') && !seg.endsWith(".")) name = seg
        }
        var n = name?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.replace(Regex("[:*?\"<>|]"), "_")?.trim().orEmpty()
        if (n.isBlank()) return URLUtil.guessFileName(url, contentDisposition, mime)
        if (!n.substringAfterLast('.', "").let { it.isNotEmpty() && it.length <= 6 } && !mime.isNullOrBlank() &&
            mime != "application/octet-stream") {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { n = "$n.$it" }
        }
        return n
    }

    fun mimeFor(rec: DownloadRecord): String {
        if (rec.mimeType.isNotBlank() && rec.mimeType != "application/octet-stream") return rec.mimeType
        val ext = rec.fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    // ── Worker ───────────────────────────────────────────────────────────────

    private fun partFile(rec: DownloadRecord): File {
        val base = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
        return File(File(base, "nezu"), rec.id + ".part")
    }

    private fun work(job: Job) {
        val rec = job.rec
        val outcome = try {
            transfer(job)
        } catch (_: Exception) {
            when {
                job.cancel -> CANCELLED
                job.pause -> DownloadManager.STATUS_PAUSED
                rec.downloadedBytes > 0 -> DownloadManager.STATUS_PAUSED   // connection lost: can be resumed
                else -> DownloadManager.STATUS_FAILED
            }
        }
        finish(job, outcome)
    }

    private fun finish(job: Job, outcome: Int) {
        val rec = job.rec
        rec.speed = 0
        jobs.remove(rec.id)
        if (outcome == CANCELLED) {
            partFile(rec).delete()
            live.remove(rec.id)
            runCatching { prefs.edit().remove(rec.id).apply() }
        } else {
            rec.status = outcome
            saveState(rec)
        }
        main.post {
            listener?.invoke()
            if (outcome == DownloadManager.STATUS_SUCCESSFUL) notifyDone(rec)
        }
    }

    private fun connect(job: Job, offset: Long): HttpURLConnection {
        val rec = job.rec
        var url = rec.url
        val firstHost = URL(url).host
        var hops = 0
        while (true) {
            if (job.pause || job.cancel) throw IOException("stopped")
            val u = URL(url)
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", rec.userAgent.ifBlank { Net.UA })
            c.setRequestProperty("Accept-Encoding", "identity")
            if (job.cookie.isNotBlank() && u.host == firstHost) c.setRequestProperty("Cookie", job.cookie)
            if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
            job.conn = c
            val code = c.responseCode
            if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && hops < 8) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw IOException("bad redirect")
                url = URL(u, loc).toString()
                hops++
                continue
            }
            return c
        }
    }

    private fun transfer(job: Job): Int {
        val rec = job.rec
        val part = partFile(rec)
        part.parentFile?.mkdirs()
        var offset = if (part.exists()) part.length() else 0L
        var attempts = 0

        while (true) {
            attempts++
            val c = connect(job, offset)
            val code = c.responseCode
            if (code == 416 && offset > 0 && attempts < 3) {      // server does not like our range: start again
                c.disconnect(); part.delete(); offset = 0; continue
            }
            if (code != 200 && code != 206) { c.disconnect(); return DownloadManager.STATUS_FAILED }

            val resumed = code == 206 && offset > 0
            if (!resumed) { offset = 0; part.delete() }          // 200: the server sends the whole file

            val len = c.contentLengthLong
            val total = if (code == 206) {
                c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: if (len >= 0) offset + len else -1L
            } else len
            if (total > 0) rec.totalBytes = total

            val ctype = c.contentType?.substringBefore(';')?.trim().orEmpty()
            if (rec.mimeType.isBlank() && ctype.isNotBlank()) rec.mimeType = ctype
            if (job.rename) {
                job.rename = false
                val n = fileNameFor(c.url.toString(), c.getHeaderField("Content-Disposition"), ctype)
                if (n.isNotBlank()) rec.fileName = n
            }

            val raf = RandomAccessFile(part, "rw")
            try {
                raf.seek(offset)
                rec.downloadedBytes = offset
                val input = c.inputStream
                val buf = ByteArray(64 * 1024)
                var lastTick = System.currentTimeMillis()
                var tickBytes = rec.downloadedBytes
                var lastSave = lastTick
                while (true) {
                    if (job.pause || job.cancel) break
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    rec.downloadedBytes += n
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 1000) {
                        rec.speed = (rec.downloadedBytes - tickBytes) * 1000L / (now - lastTick)
                        tickBytes = rec.downloadedBytes; lastTick = now
                    }
                    if (now - lastSave >= 3000) { saveState(rec); lastSave = now }
                }
            } finally {
                runCatching { raf.close() }
                runCatching { c.disconnect() }
            }

            if (job.cancel) return CANCELLED
            if (job.pause) return DownloadManager.STATUS_PAUSED
            if (rec.totalBytes > 0 && rec.downloadedBytes < rec.totalBytes)
                return DownloadManager.STATUS_PAUSED             // connection ended early: resume later
            return publish(rec, part)
        }
    }

    /** Moves the finished file to the phone's Downloads folder (Android 10+); otherwise keeps it in the app folder. */
    private fun publish(rec: DownloadRecord, part: File): Int {
        val name = rec.fileName.ifBlank { "download" }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeFor(rec))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val r = app.contentResolver
                val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    try {
                        r.openOutputStream(uri)!!.use { out ->
                            part.inputStream().use { it.copyTo(out, 64 * 1024) }
                        }
                        r.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                        part.delete()
                        rec.filePath = uri.toString()
                        return DownloadManager.STATUS_SUCCESSFUL
                    } catch (_: Exception) {
                        runCatching { r.delete(uri, null, null) }
                    }
                }
            } catch (_: Exception) {}
        }
        val dir = part.parentFile ?: return DownloadManager.STATUS_FAILED
        val dest = uniqueFile(dir, name)
        return if (part.renameTo(dest)) {
            rec.filePath = dest.absolutePath
            DownloadManager.STATUS_SUCCESSFUL
        } else DownloadManager.STATUS_FAILED
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    private fun notifyDone(rec: DownloadRecord) {
        runCatching {
            val open = PendingIntent.getActivity(app, 0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(app, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(rec.fileName)
                .setContentText("Download complete")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            app.getSystemService(NotificationManager::class.java).notify(rec.id.hashCode(), n)
        }
    }
}
