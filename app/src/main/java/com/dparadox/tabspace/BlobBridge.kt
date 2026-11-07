package com.dparadox.tabspace

import android.util.Base64
import android.webkit.JavascriptInterface
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives a blob: download from the page, chunk by chunk (see MainActivity.saveBlob).
 * Only tokens the app registered itself are accepted, so a web page cannot write files on its own.
 */
class BlobBridge {
    private class Sink(val rec: DownloadRecord, val out: OutputStream)

    private val sinks = ConcurrentHashMap<String, Sink>()

    /** Called by the app (main thread) before the page starts sending. */
    fun register(rec: DownloadRecord): Boolean {
        val out = DownloadEngine.openLocal(rec) ?: return false
        sinks[rec.id] = Sink(rec, out)
        return true
    }

    @JavascriptInterface
    fun size(token: String, bytes: Long) {
        sinks[token]?.rec?.totalBytes = bytes
    }

    @JavascriptInterface
    fun chunk(token: String, base64: String) {
        val s = sinks[token] ?: return
        try {
            val b = Base64.decode(base64, Base64.DEFAULT)
            s.out.write(b)
            s.rec.downloadedBytes += b.size
        } catch (_: Exception) {
            fail(token)
        }
    }

    @JavascriptInterface
    fun end(token: String) {
        val s = sinks.remove(token) ?: return
        runCatching { s.out.close() }
        DownloadEngine.finishLocal(s.rec, true)
    }

    @JavascriptInterface
    fun fail(token: String) {
        val s = sinks.remove(token) ?: return
        runCatching { s.out.close() }
        DownloadEngine.finishLocal(s.rec, false)
    }
}
