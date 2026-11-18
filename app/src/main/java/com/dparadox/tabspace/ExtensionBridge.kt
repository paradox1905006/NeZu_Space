package com.dparadox.tabspace

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * chrome.storage for extension scripts. Every call needs the extension id and its secret token, which only
 * the injected script knows, so a web page cannot read or write an extension's data.
 */
class ExtensionBridge(private val c: Context) {

    private val lock = Any()

    private fun <T> guarded(id: String, token: String, fallback: T, block: () -> T): T {
        if (!ExtensionStore.tokenOk(c, id, token)) return fallback
        return try { synchronized(lock) { block() } } catch (_: Exception) { fallback }
    }

    @JavascriptInterface
    fun storageGet(id: String, token: String, arg: String): String = guarded(id, token, "{}") {
        val store = ExtensionStore.storageRead(c, id)
        val out = JSONObject()
        when (val spec = JSONTokener(arg).nextValue()) {
            is String -> if (store.has(spec)) out.put(spec, store.get(spec))
            is JSONArray -> for (i in 0 until spec.length()) { val k = spec.getString(i); if (store.has(k)) out.put(k, store.get(k)) }
            is JSONObject -> spec.keys().forEach { k -> out.put(k, if (store.has(k)) store.get(k) else spec.get(k)) }
            else -> store.keys().forEach { k -> out.put(k, store.get(k)) }
        }
        out.toString()
    }

    @JavascriptInterface
    fun storageSet(id: String, token: String, arg: String): String = guarded(id, token, "{}") {
        val items = JSONTokener(arg).nextValue() as? JSONObject ?: return@guarded "{}"
        val store = ExtensionStore.storageRead(c, id)
        val changes = JSONObject()
        items.keys().forEach { k ->
            val change = JSONObject()
            if (store.has(k)) change.put("oldValue", store.get(k))
            change.put("newValue", items.get(k))
            changes.put(k, change)
            store.put(k, items.get(k))
        }
        if (ExtensionStore.storageWrite(c, id, store)) changes.toString() else "{}"
    }

    @JavascriptInterface
    fun storageRemove(id: String, token: String, arg: String): String = guarded(id, token, "{}") {
        val keys = ArrayList<String>()
        when (val spec = JSONTokener(arg).nextValue()) {
            is String -> keys.add(spec)
            is JSONArray -> for (i in 0 until spec.length()) keys.add(spec.getString(i))
        }
        val store = ExtensionStore.storageRead(c, id)
        val changes = JSONObject()
        keys.forEach { k -> if (store.has(k)) { changes.put(k, JSONObject().put("oldValue", store.get(k))); store.remove(k) } }
        ExtensionStore.storageWrite(c, id, store)
        changes.toString()
    }

    @JavascriptInterface
    fun storageClear(id: String, token: String, arg: String): String = guarded(id, token, "{}") {
        val store = ExtensionStore.storageRead(c, id)
        val changes = JSONObject()
        store.keys().forEach { k -> changes.put(k, JSONObject().put("oldValue", store.get(k))) }
        ExtensionStore.storageWrite(c, id, JSONObject())
        changes.toString()
    }
}
