package com.dparadox.tabspace

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Deletes browsing data of one profile. Used by Menu > Clear data, private profiles and "clear when closed". */
object DataWiper {
    const val CACHE = 1
    const val FORM = 2
    const val HISTORY = 4
    const val STORAGE = 8
    const val COOKIES = 16
    const val APP_CACHE = 32

    /** Everything a private profile forgets (the app's own temporary files are not part of a profile). */
    const val PRIVATE_MASK = CACHE or FORM or HISTORY or STORAGE or COOKIES

    val LABELS = arrayOf("Cache", "Form data", "History", "Web storage", "Cookies (login status)", "App cache")
    val BITS = intArrayOf(CACHE, FORM, HISTORY, STORAGE, COOKIES, APP_CACHE)

    /**
     * [webkitProfile]: name of the WebKit profile (null = the default one). [webViews]: the open WebViews of that profile
     * (the back/forward list can only be cleared in these). [profileId] is needed to clear the saved history list (Menu > History). Cache and form data need a WebView; a short-lived one is made when none is open.
     */
    @Suppress("DEPRECATION")
    fun wipe(ctx: Context, webkitProfile: String?, webViews: List<WebView>, mask: Int, profileId: String? = null) {
        val profile = if (webkitProfile != null && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
            runCatching { ProfileStore.getInstance().getProfile(webkitProfile) }.getOrNull() else null

        var host: WebView? = webViews.firstOrNull()
        var temp: WebView? = null
        if (host == null && (mask and (CACHE or FORM)) != 0) {
            temp = runCatching {
                WebView(ctx).also { w -> if (profile != null) WebViewCompat.setProfile(w, webkitProfile!!) }
            }.getOrNull()
            host = temp
        }

        if (mask and CACHE != 0) runCatching { host?.clearCache(true) }
        if (mask and FORM != 0) {
            webViews.forEach { runCatching { it.clearFormData() } }
            runCatching { host?.clearFormData() }
            runCatching {
                WebViewDatabase.getInstance(ctx).apply { clearFormData(); clearHttpAuthUsernamePassword() }
            }
        }
        if (mask and HISTORY != 0) {
            webViews.forEach { runCatching { it.clearHistory() } }       // back / forward list
            profileId?.let { HistoryStore.clear(ctx, it) }              // Menu > History
        }
        if (mask and STORAGE != 0) {
            runCatching { (profile?.webStorage ?: WebStorage.getInstance()).deleteAllData() }
        }
        if (mask and COOKIES != 0) {
            runCatching {
                val cm = profile?.cookieManager ?: CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
            }
        }
        if (mask and APP_CACHE != 0) {
            ctx.cacheDir.listFiles()?.forEach { f ->
                if (f.name != "WebView" && !f.name.startsWith("org.chromium")) runCatching { f.deleteRecursively() }
            }
        }
        runCatching { temp?.destroy() }
    }
}
