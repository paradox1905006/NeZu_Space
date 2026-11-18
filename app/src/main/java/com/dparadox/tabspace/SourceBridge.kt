package com.dparadox.tabspace

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import java.util.concurrent.ConcurrentHashMap

/** Hands the page source (read by a script inside the page) back to the app. Only answers a request the app started (secret nonce). */
class SourceBridge {
    private val pending = ConcurrentHashMap<String, (String?) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun expect(nonce: String, cb: (String?) -> Unit) { pending[nonce] = cb }
    fun cancel(nonce: String) { pending.remove(nonce) }

    @JavascriptInterface
    fun put(nonce: String?, text: String?) {
        if (nonce == null) return
        val cb = pending.remove(nonce) ?: return
        main.post { cb(text) }
    }
}
