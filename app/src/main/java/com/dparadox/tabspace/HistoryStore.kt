package com.dparadox.tabspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Browsing history, one list per profile (Menu > History). Newest first, at most [MAX] pages per profile.
 * Stored encrypted when "Encrypt saved data" is on. Private profiles never record anything.
 */
object HistoryStore {
    const val MAX = 500

    data class Entry(val url: String, val title: String, val time: Long)

    private fun prefs(c: Context) = c.getSharedPreferences("nezu_history", Context.MODE_PRIVATE)

    private fun read(c: Context, profileId: String): MutableList<Entry> {
        val p = prefs(c)
        val json = p.getString("h_${profileId}_enc", null)?.let { SecureStore.decrypt(it) }
            ?: p.getString("h_$profileId", null) ?: return ArrayList()
        val out = ArrayList<Entry>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(o.getString("u"), o.optString("t"), o.optLong("m")))
            }
        }
        return out
    }

    private fun write(c: Context, profileId: String, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("m", it.time)) }
        val json = arr.toString()
        val enc = if (PrivacyPrefs.encryptData(c)) SecureStore.encrypt(json) else null
        val ed = prefs(c).edit()
        if (enc != null) ed.putString("h_${profileId}_enc", enc).remove("h_$profileId")
        else ed.putString("h_$profileId", json).remove("h_${profileId}_enc")
        ed.apply()
    }

    @Synchronized
    fun add(c: Context, profileId: String, url: String, title: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (PrivacyPrefs.isPrivate(c, profileId)) return
        val list = read(c, profileId)
        val clean = url.substringBefore('#')
        // The same page again: keep one entry, move it to the top, keep the best title.
        val old = list.firstOrNull { it.url.substringBefore('#') == clean }
        list.removeAll { it.url.substringBefore('#') == clean }
        val t = title?.trim().orEmpty().ifBlank { old?.title.orEmpty() }
        list.add(0, Entry(url, t, System.currentTimeMillis()))
        while (list.size > MAX) list.removeAt(list.size - 1)
        write(c, profileId, list)
    }

    @Synchronized
    fun list(c: Context, profileId: String): List<Entry> = read(c, profileId)

    @Synchronized
    fun remove(c: Context, profileId: String, url: String) {
        val list = read(c, profileId)
        if (list.removeAll { it.url == url }) write(c, profileId, list)
    }

    @Synchronized
    fun clear(c: Context, profileId: String) {
        prefs(c).edit().remove("h_$profileId").remove("h_${profileId}_enc").apply()
    }
}
