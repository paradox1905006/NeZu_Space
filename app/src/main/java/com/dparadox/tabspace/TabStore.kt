package com.dparadox.tabspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TabStore(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = context.getSharedPreferences("nezu_v3", Context.MODE_PRIVATE)

    /**
     * The JSON is saved encrypted (key in the Android Keystore) when Settings > Privacy & Security > "Encrypt saved data"
     * is on; otherwise, or when the Keystore does not work on this phone, as plain text. Only one of the two is kept.
     */
    private fun putJson(ed: android.content.SharedPreferences.Editor, key: String, json: String) {
        val enc = if (PrivacyPrefs.encryptData(ctx)) SecureStore.encrypt(json) else null
        if (enc != null) ed.putString(key + "_enc", enc).remove(key)
        else ed.putString(key, json).remove(key + "_enc")
    }

    private fun readJson(key: String): String {
        prefs.getString(key + "_enc", null)?.let { e -> SecureStore.decrypt(e)?.let { return it } }
        return prefs.getString(key, "[]") ?: "[]"
    }

    data class LoadResult(
        val profiles: MutableList<AppProfile>,
        val tabs: MutableList<Tab>,
        val currentTabId: String?,
        val currentProfileId: String?
    )

    private fun bookmarkJson(b: Bookmark) = JSONObject().put("id", b.id).put("title", b.title)
        .put("url", b.url).put("addedAt", b.addedAt).put("icon", b.iconUri ?: "")

    private fun bookmarkFrom(b: JSONObject) = Bookmark(
        id = b.optString("id", ""), title = b.getString("title"), url = b.getString("url"),
        addedAt = b.optLong("addedAt", 0), iconUri = b.optString("icon").ifBlank { null }
    )

    fun save(tabs: List<Tab>, currentTabId: String?,
             profiles: List<AppProfile>, currentProfileId: String?) {

        val profArr = JSONArray()
        profiles.forEach { p ->
            val bArr = JSONArray()
            p.bookmarks.forEach { bArr.put(bookmarkJson(it)) }
            val dArr = JSONArray()
            p.downloads.toList().forEach { dArr.put(it.toJson()) }
            profArr.put(JSONObject()
                .put("id", p.id).put("name", p.name)
                .put("color", p.color).put("avatarUri", p.avatarUri ?: "")
                .put("searchEngine", p.searchEngine.name)
                .put("bookmarks", bArr)
                .put("downloads", dArr))
        }

        val tabArr = JSONArray()
        tabs.forEach { t ->
            tabArr.put(JSONObject()
                .put("id", t.id).put("profileId", t.profileId)
                .put("name", t.name).put("color", t.color)
                .put("url", t.url).put("pinned", t.pinned)
                .put("title", t.title)
                .put("avatarUri", t.avatarUri ?: ""))
        }

        val ed = prefs.edit()
        putJson(ed, "profiles", profArr.toString())
        putJson(ed, "tabs", tabArr.toString())
        ed.putString("currentTab", currentTabId)
            .putString("currentProfile", currentProfileId)
            .apply()
    }

    fun load(): LoadResult {
        val profiles = mutableListOf<AppProfile>()
        val tabs = mutableListOf<Tab>()

        // Older versions kept search engine and bookmarks per tab. They are moved to the profile.
        val legacyEngine = mutableMapOf<String, SearchEngine>()
        val legacyBookmarks = mutableMapOf<String, MutableList<Bookmark>>()
        val legacyDownloads = mutableMapOf<String, MutableList<DownloadRecord>>()

        try {
            val arr = JSONArray(readJson("tabs"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pid = o.optString("profileId", "default")
                val tab = Tab(
                    id = o.getString("id"),
                    profileId = pid,
                    name = o.getString("name"),
                    color = o.getInt("color"),
                    url = o.optString("url", ""),
                    pinned = o.optBoolean("pinned", false),
                    avatarUri = o.optString("avatarUri").ifBlank { null },
                ).also { t ->
                    t.title = o.optString("title", "")
                    // Older versions kept downloads per tab. They are moved to the profile.
                    val dArr = o.optJSONArray("downloads") ?: JSONArray()
                    for (j in 0 until dArr.length()) {
                        runCatching { DownloadRecord.fromJson(dArr.getJSONObject(j)) }.getOrNull()?.let {
                            legacyDownloads.getOrPut(pid) { mutableListOf() }.add(it)
                        }
                    }
                }
                if (o.has("searchEngine") && pid !in legacyEngine) {
                    runCatching { SearchEngine.valueOf(o.getString("searchEngine")) }
                        .getOrNull()?.let { legacyEngine[pid] = it }
                }
                val bArr = o.optJSONArray("bookmarks")
                if (bArr != null) for (j in 0 until bArr.length()) {
                    runCatching { bookmarkFrom(bArr.getJSONObject(j)) }.getOrNull()?.let {
                        legacyBookmarks.getOrPut(pid) { mutableListOf() }.add(it)
                    }
                }
                tabs.add(tab)
            }
        } catch (_: Exception) {}

        try {
            val arr = JSONArray(readJson("profiles"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val engine = if (o.has("searchEngine"))
                    runCatching { SearchEngine.valueOf(o.getString("searchEngine")) }.getOrNull()
                else null
                val p = AppProfile(id = id, name = o.getString("name"),
                    color = o.getInt("color"), avatarUri = o.optString("avatarUri").ifBlank { null },
                    searchEngine = engine ?: legacyEngine[id] ?: SearchEngine.GOOGLE)
                val bArr = o.optJSONArray("bookmarks")
                if (bArr != null) for (j in 0 until bArr.length()) {
                    runCatching { bookmarkFrom(bArr.getJSONObject(j)) }.getOrNull()?.let { p.bookmarks.add(it) }
                }
                legacyBookmarks[id]?.forEach { lb ->
                    if (p.bookmarks.none { it.url == lb.url }) p.bookmarks.add(lb)
                }
                val dArr = o.optJSONArray("downloads")
                if (dArr != null) for (j in 0 until dArr.length()) {
                    runCatching { DownloadRecord.fromJson(dArr.getJSONObject(j)) }.getOrNull()?.let {
                        p.downloads.add(DownloadEngine.resolve(it))
                    }
                }
                legacyDownloads[id]?.forEach { ld ->
                    if (p.downloads.none { it.id == ld.id }) p.downloads.add(DownloadEngine.resolve(ld))
                }
                profiles.add(p)
            }
        } catch (_: Exception) {}

        return LoadResult(profiles, tabs,
            prefs.getString("currentTab", null),
            prefs.getString("currentProfile", null))
    }
}
