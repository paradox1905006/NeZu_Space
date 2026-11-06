package com.dparadox.tabspace

import java.net.HttpURLConnection
import java.net.URL

/** Small HTTP helper (follows redirects, also from http to https). */
object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0 Mobile Safari/537.36"

    /** Opens [url] and returns the connection when the answer is 200, otherwise null. Caller must disconnect. */
    fun open(url: String, ua: String = UA, referer: String? = null, cookie: String? = null): HttpURLConnection? {
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 7000
            c.readTimeout = 9000
            c.setRequestProperty("User-Agent", ua.ifBlank { UA })
            referer?.let { c.setRequestProperty("Referer", it) }
            if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) return null
                u = URL(URL(u), loc).toString()
                return@repeat
            }
            if (code == 200) return c
            c.disconnect()
            return null
        }
        return null
    }

    /** Reads at most [max] bytes. */
    fun readBytes(c: HttpURLConnection, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        c.inputStream.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) return null
            }
        }
        return out.toByteArray()
    }
}
