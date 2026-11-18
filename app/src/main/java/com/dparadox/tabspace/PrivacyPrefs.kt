package com.dparadox.tabspace

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/** All privacy and security settings (Settings > Privacy & Security) and a few helpers that use them. */
object PrivacyPrefs {

    private fun p(c: Context) = c.getSharedPreferences("nezu_privacy", Context.MODE_PRIVATE)

    // ── App lock ─────────────────────────────────────────────────────────────
    fun appLock(c: Context) = p(c).getBoolean("app_lock", false)
    fun setAppLock(c: Context, on: Boolean) = p(c).edit().putBoolean("app_lock", on).apply()

    /** How long the app may be out of sight before it asks again. 0 = every time. */
    fun lockDelayMs(c: Context) = p(c).getLong("lock_delay", 0L)
    fun setLockDelayMs(c: Context, ms: Long) = p(c).edit().putLong("lock_delay", ms).apply()

    fun lockedProfiles(c: Context): Set<String> = p(c).getStringSet("locked_profiles", emptySet()).orEmpty()
    fun setLockedProfiles(c: Context, ids: Set<String>) =
        p(c).edit().putStringSet("locked_profiles", HashSet(ids)).apply()
    fun isLocked(c: Context, profileId: String) = profileId in lockedProfiles(c)

    // ── Screen ───────────────────────────────────────────────────────────────
    /** Blocks screenshots and shows a blank picture in the recent-apps list. */
    fun secureScreen(c: Context) = p(c).getBoolean("secure_screen", false)
    fun setSecureScreen(c: Context, on: Boolean) = p(c).edit().putBoolean("secure_screen", on).apply()

    // ── Private profiles and auto-clear ──────────────────────────────────────
    fun privateProfiles(c: Context): Set<String> = p(c).getStringSet("private_profiles", emptySet()).orEmpty()
    fun setPrivateProfiles(c: Context, ids: Set<String>) =
        p(c).edit().putStringSet("private_profiles", HashSet(ids)).apply()
    fun isPrivate(c: Context, profileId: String) = profileId in privateProfiles(c)

    fun autoClear(c: Context) = p(c).getBoolean("auto_clear", false)
    fun setAutoClear(c: Context, on: Boolean) = p(c).edit().putBoolean("auto_clear", on).apply()
    fun autoClearMask(c: Context) = p(c).getInt("auto_clear_mask", DataWiper.CACHE or DataWiper.HISTORY)
    fun setAutoClearMask(c: Context, mask: Int) = p(c).edit().putInt("auto_clear_mask", mask).apply()

    // ── Browsing ─────────────────────────────────────────────────────────────
    fun confirmExternal(c: Context) = p(c).getBoolean("confirm_external", true)
    fun setConfirmExternal(c: Context, on: Boolean) = p(c).edit().putBoolean("confirm_external", on).apply()

    fun blockThirdPartyCookies(c: Context) = p(c).getBoolean("block_3p_cookies", false)
    fun setBlockThirdPartyCookies(c: Context, on: Boolean) = p(c).edit().putBoolean("block_3p_cookies", on).apply()

    fun stripTracking(c: Context) = p(c).getBoolean("strip_tracking", true)
    fun setStripTracking(c: Context, on: Boolean) = p(c).edit().putBoolean("strip_tracking", on).apply()

    fun blockTrackers(c: Context) = p(c).getBoolean("block_trackers", false)
    fun setBlockTrackers(c: Context, on: Boolean) = p(c).edit().putBoolean("block_trackers", on).apply()

    fun httpsOnly(c: Context) = p(c).getBoolean("https_only", false)
    fun setHttpsOnly(c: Context, on: Boolean) = p(c).edit().putBoolean("https_only", on).apply()

    fun safeBrowsing(c: Context) = p(c).getBoolean("safe_browsing", true)
    fun setSafeBrowsing(c: Context, on: Boolean) = p(c).edit().putBoolean("safe_browsing", on).apply()

    fun clipboardClear(c: Context) = p(c).getBoolean("clipboard_clear", true)
    fun setClipboardClear(c: Context, on: Boolean) = p(c).edit().putBoolean("clipboard_clear", on).apply()

    fun downloadWarn(c: Context) = p(c).getBoolean("download_warn", true)
    fun setDownloadWarn(c: Context, on: Boolean) = p(c).edit().putBoolean("download_warn", on).apply()

    /** Tabs, profiles, shortcuts and download list are stored encrypted with a key of the Android Keystore. */
    fun encryptData(c: Context) = p(c).getBoolean("encrypt_data", true)
    fun setEncryptData(c: Context, on: Boolean) = p(c).edit().putBoolean("encrypt_data", on).apply()

    // ── Links ────────────────────────────────────────────────────────────────
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "igshid", "mc_eid", "mc_cid",
        "_ga", "_gl", "ref_src", "twclid", "vero_id", "oly_enc_id", "oly_anon_id"
    )

    /** Removes tracking parameters (utm_*, fbclid, gclid ...) from a web link. Other links are returned as they are. */
    fun cleanUrl(c: Context, url: String): String {
        if (!stripTracking(c)) return url
        return try {
            val u = Uri.parse(url)
            if (u.scheme != "http" && u.scheme != "https") return url
            if (!u.isHierarchical) return url
            val names = u.queryParameterNames
            if (names.isEmpty()) return url
            val keep = names.filter { n -> !(n.startsWith("utm_", true) || n.lowercase() in TRACKING_PARAMS) }
            if (keep.size == names.size) return url
            val b = u.buildUpon().clearQuery()
            keep.forEach { n -> u.getQueryParameters(n).forEach { v -> b.appendQueryParameter(n, v) } }
            b.build().toString()
        } catch (_: Exception) { url }
    }

    /** Hosts that never get the https upgrade (local network, localhost). */
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal")) return true
        if (!h.all { it.isDigit() || it == '.' }) return false
        val o = h.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return false
        return o[0] == 10 || o[0] == 127 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31)
    }

    // ── Downloads ────────────────────────────────────────────────────────────
    private val RISKY_EXT = setOf("apk", "xapk", "apks", "aab", "exe", "msi", "bat", "cmd", "scr", "com", "jar",
        "dex", "sh", "vbs", "ps1", "dmg", "pkg", "deb", "rpm", "appimage", "lnk", "iso")

    fun isRiskyFile(name: String, mime: String?): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in RISKY_EXT || mime == "application/vnd.android.package-archive"
    }

    // ── Site permissions (camera, microphone, location) ──────────────────────
    const val PERM_CAMERA = "camera"
    const val PERM_MIC = "microphone"
    const val PERM_LOCATION = "location"

    private fun perms(c: Context): JSONObject =
        runCatching { JSONObject(p(c).getString("site_perms", "{}") ?: "{}") }.getOrDefault(JSONObject())

    /** true = allowed, false = blocked, null = not decided yet. */
    fun sitePermission(c: Context, host: String, type: String): Boolean? {
        val h = perms(c).optJSONObject(host.lowercase()) ?: return null
        return if (h.has(type)) h.optBoolean(type) else null
    }

    fun setSitePermission(c: Context, host: String, type: String, allow: Boolean) {
        val all = perms(c)
        val key = host.lowercase()
        val h = all.optJSONObject(key) ?: JSONObject().also { all.put(key, it) }
        h.put(type, allow)
        p(c).edit().putString("site_perms", all.toString()).apply()
    }

    /** Hosts that have at least one saved decision, with the decisions. */
    fun siteDecisions(c: Context): List<Pair<String, Map<String, Boolean>>> {
        val all = perms(c)
        val out = ArrayList<Pair<String, Map<String, Boolean>>>()
        all.keys().forEach { host ->
            val h = all.optJSONObject(host) ?: return@forEach
            val m = LinkedHashMap<String, Boolean>()
            h.keys().forEach { t -> m[t] = h.optBoolean(t) }
            if (m.isNotEmpty()) out.add(host to m)
        }
        return out.sortedBy { it.first }
    }

    fun clearSite(c: Context, host: String) {
        val all = perms(c)
        all.remove(host.lowercase())
        p(c).edit().putString("site_perms", all.toString()).apply()
    }

    fun clearAllSites(c: Context) = p(c).edit().remove("site_perms").apply()
}
