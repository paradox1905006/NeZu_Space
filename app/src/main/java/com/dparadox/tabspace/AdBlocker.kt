package com.dparadox.tabspace

import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream

/**
 * Simple built-in ad and tracker blocker.
 *  1. Requests to known ad / tracker hosts are answered with an empty response.
 *  2. A small style sheet hides the empty ad boxes that are left on the page.
 *  3. Redirects to ad hosts that the user did not start (no touch) are cancelled.
 */
object AdBlocker {

    /** Read on WebView background threads, so keep it volatile. */
    @Volatile var enabled = true

    /** Hosts and all their sub-domains. */
    private val domains = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "adservice.google.com", "2mdn.net", "moatads.com", "adnxs.com", "adsrvr.org", "taboola.com",
        "outbrain.com", "criteo.com", "criteo.net", "rubiconproject.com", "pubmatic.com", "openx.net",
        "amazon-adsystem.com", "scorecardresearch.com", "adform.net", "smartadserver.com",
        "casalemedia.com", "33across.com", "advertising.com", "adcolony.com", "applovin.com",
        "inmobi.com", "mopub.com", "popads.net", "popcash.net", "propellerads.com", "exoclick.com",
        "juicyads.com", "trafficjunky.net", "adsterra.com", "revcontent.com", "mgid.com", "zedo.com",
        "yieldmo.com", "teads.tv", "serving-sys.com", "adblade.com", "clickadu.com", "hilltopads.net",
        "onclickads.net", "ads.yahoo.com", "ads.twitter.com", "an.facebook.com", "unityads.unity3d.com",
        "adsafeprotected.com", "quantserve.com", "bidswitch.net", "contextweb.com", "lijit.com",
        "sharethrough.com", "tapad.com", "adition.com", "richaudience.com", "ad.gt", "bidr.io",
        "monetag.com", "a-ads.com", "ero-advertising.com", "tsyndicate.com", "realsrv.com",
        "adskeeper.com", "yandexadexchange.net", "an.yandex.ru", "mc.yandex.ru"
    )

    private val hostPrefixes = listOf("ads.", "adserver.", "adservice.", "ad-delivery.", "pagead.", "banners.")

    // ── Images the user marked as ads ("Mark as ad" in the image menu) ──────────
    private val userUrls: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private fun prefs(c: android.content.Context) = c.getSharedPreferences("nezu_adblock", 0)

    fun load(c: android.content.Context) {
        userUrls.clear()
        userUrls.addAll(prefs(c).getStringSet("urls", emptySet()).orEmpty())
    }

    fun markUrl(c: android.content.Context, url: String) {
        userUrls.add(url.substringBefore('#'))
        prefs(c).edit().putStringSet("urls", HashSet(userUrls)).apply()
    }

    fun isBlockedHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        if (hostPrefixes.any { h.startsWith(it) }) return true
        var d = h
        while (true) {
            if (d in domains) return true
            val i = d.indexOf('.')
            if (i < 0 || i == d.lastIndexOf('.')) return false
            d = d.substring(i + 1)
        }
    }

    private val empty get() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Returns an empty response for blocked sub-resources, null to let the request through. */
    fun intercept(host: String?, isMainFrame: Boolean, url: String? = null): WebResourceResponse? =
        if (enabled && !isMainFrame &&
            (isBlockedHost(host) || (url != null && url.substringBefore('#') in userUrls))) empty else null

    private const val CSS = "ins.adsbygoogle,.adsbygoogle,[id^=google_ads_iframe],[id^=div-gpt-ad]," +
        "[id^=taboola],[class*=taboola],[data-ad-slot],[data-google-query-id]," +
        "iframe[src*='doubleclick.net'],iframe[src*='googlesyndication.com'],iframe[src*='adnxs.com']," +
        "iframe[src*='amazon-adsystem.com'],iframe[src*='outbrain.com'],iframe[src*='taboola.com']," +
        ".ad-banner,.ad-container,.ad-slot,.advert,.advertisement,.sponsored-ad,[aria-label=Advertisement]" +
        "{display:none!important;height:0!important;min-height:0!important;}"

    /** Hides ad boxes. Called when a page has loaded; new boxes are caught by a small observer. */
    fun hideAdBoxes(wv: WebView) {
        if (!enabled) return
        wv.evaluateJavascript(
            "(function(){if(window.__nezuAdCss)return;window.__nezuAdCss=1;" +
            "var s=document.createElement('style');s.textContent=\"$CSS\";" +
            "(document.head||document.documentElement).appendChild(s);})();", null)
    }
}
