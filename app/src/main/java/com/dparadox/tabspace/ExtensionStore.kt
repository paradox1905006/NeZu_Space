package com.dparadox.tabspace

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * Extensions (Settings > Extensions). A Chrome-style extension is a zip with a manifest.json. What works:
 * content scripts (js + css) that run on the sites in "matches", a small chrome.storage / chrome.runtime /
 * chrome.i18n, and the extension's own files through chrome.runtime.getURL. What does not: background scripts,
 * toolbar popups and network rules. Each extension is switched on per profile.
 */
object ExtensionStore {

    /** Extension files are served from this made-up address (see serve()); it never touches the network. */
    const val HOST = "nezu-ext.invalid"

    /** Goes up on every change, so open tabs know to pick the new setup up. */
    @Volatile var version = 0

    private const val MAX_TOTAL = 200L * 1024 * 1024
    private const val MAX_ENTRIES = 8000
    private const val MAX_SCRIPT = 6L * 1024 * 1024

    class ContentScript(
        val matches: List<String>, val excludes: List<String>,
        val js: List<String>, val css: List<String>,
        val runAt: String, val allFrames: Boolean
    )

    class Ext(
        val id: String, val root: File, val rootRel: String,
        val name: String, val version: String, val description: String,
        val scripts: List<ContentScript>, val manifest: JSONObject,
        val messages: JSONObject?, val unsupported: List<String>,
        var enabled: Boolean, var profiles: Set<String>
    ) {
        /** Websites the scripts run on, for the confirm and details dialogs. */
        fun sites(): List<String> {
            val out = LinkedHashSet<String>()
            for (cs in scripts) for (p in cs.matches) {
                if (p == "<all_urls>") { out.add("All websites"); continue }
                val host = Regex("^[^:]+://([^/]*)/").find(p)?.groupValues?.get(1) ?: p
                out.add(if (host == "*") "All websites" else host)
            }
            return out.toList()
        }
    }

    class Staged(val ext: Ext)

    private class Meta(val id: String, val rootRel: String, var enabled: Boolean, var profiles: Set<String>)

    private val lock = Any()
    private var cache: List<Ext>? = null
    private val scriptCache = HashMap<String, String?>()
    private val tokens = ConcurrentHashMap<String, String>()

    private fun dir(c: Context) = File(c.applicationContext.filesDir, "extensions")
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("nezu_extensions", Context.MODE_PRIVATE)

    // ── Saved list ───────────────────────────────────────────────────────────
    private fun loadMeta(c: Context): MutableList<Meta> {
        val out = ArrayList<Meta>()
        runCatching {
            val arr = JSONArray(prefs(c).getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ps = HashSet<String>()
                o.optJSONArray("profiles")?.let { a -> for (k in 0 until a.length()) ps.add(a.getString(k)) }
                out.add(Meta(o.getString("id"), o.optString("rootRel", ""), o.optBoolean("enabled", true), ps))
            }
        }
        return out
    }

    private fun saveMeta(c: Context, list: List<Meta>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("rootRel", m.rootRel).put("enabled", m.enabled)
                .put("profiles", JSONArray(m.profiles.toList())))
        }
        prefs(c).edit().putString("list", arr.toString()).apply()
    }

    private fun changed() {
        synchronized(lock) { cache = null; scriptCache.clear() }
        version++
    }

    fun all(c: Context): List<Ext> = synchronized(lock) {
        cache ?: loadMeta(c).mapNotNull { m ->
            parse(File(File(dir(c), m.id), m.rootRel), m.id, m.rootRel)?.also { it.enabled = m.enabled; it.profiles = m.profiles }
        }.also { cache = it }
    }

    fun get(c: Context, id: String) = all(c).firstOrNull { it.id == id }

    /** Enabled extensions that were switched on for this profile. */
    fun forProfile(c: Context, profileId: String) = all(c).filter { it.enabled && profileId in it.profiles }

    fun setEnabled(c: Context, id: String, on: Boolean) {
        val l = loadMeta(c); l.firstOrNull { it.id == id }?.enabled = on; saveMeta(c, l); changed()
    }

    fun setProfiles(c: Context, id: String, ids: Set<String>) {
        val l = loadMeta(c); l.firstOrNull { it.id == id }?.profiles = ids; saveMeta(c, l); changed()
    }

    fun remove(c: Context, id: String) {
        val l = loadMeta(c); l.removeAll { it.id == id }; saveMeta(c, l)
        File(dir(c), id).deleteRecursively()
        c.applicationContext.getSharedPreferences("nezu_ext_data", Context.MODE_PRIVATE).edit().remove(id).apply()
        tokens.remove(id)
        changed()
    }

    // ── Adding ───────────────────────────────────────────────────────────────

    /** Unpacks the zip and reads its manifest. Nothing is active until commit(). Throws with a readable message. */
    fun stage(c: Context, uri: Uri): Staged {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val dest = File(dir(c), id)
        try {
            dest.mkdirs()
            unzip(c, uri, dest)
            val rootRel = findRoot(dest) ?: throw IOException("No manifest.json found in this zip")
            val ext = parse(File(dest, rootRel), id, rootRel) ?: throw IOException("manifest.json could not be read")
            if (ext.scripts.isEmpty()) throw IOException("This extension has no page scripts (content_scripts), so there is nothing it could run here")
            return Staged(ext)
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw if (e is IOException) e else IOException("Could not read this extension")
        }
    }

    fun commit(c: Context, st: Staged, enabled: Boolean = true): Ext {
        val l = loadMeta(c)
        l.add(Meta(st.ext.id, st.ext.rootRel, enabled, emptySet()))
        saveMeta(c, l); changed()
        return get(c, st.ext.id) ?: st.ext
    }

    fun discard(c: Context, st: Staged) { File(dir(c), st.ext.id).deleteRecursively() }

    private fun unzip(c: Context, uri: Uri, dest: File) {
        var total = 0L
        var n = 0
        val base = dest.canonicalPath + File.separator
        val input = c.contentResolver.openInputStream(uri) ?: throw IOException("Can't open this file")
        ZipInputStream(input.buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (++n > MAX_ENTRIES) throw IOException("Too many files in this zip")
                val name = e.name.replace('\\', '/')
                if (!name.startsWith("__MACOSX/")) {
                    val out = File(dest, name)
                    if (!out.canonicalPath.startsWith(base)) throw IOException("This zip has an unsafe file path")
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { os ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val r = zis.read(buf)
                                if (r < 0) break
                                total += r
                                if (total > MAX_TOTAL) throw IOException("This extension is too big (over 200 MB)")
                                os.write(buf, 0, r)
                            }
                        }
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
    }

    /** "" when manifest.json is at the top of the zip, else the name of the one folder that holds it. */
    private fun findRoot(dest: File): String? {
        if (File(dest, "manifest.json").isFile) return ""
        return dest.listFiles()?.filter { it.isDirectory && File(it, "manifest.json").isFile }?.firstOrNull()?.name
    }

    // ── Manifest ─────────────────────────────────────────────────────────────
    private fun parse(root: File, id: String, rootRel: String): Ext? = runCatching {
        val text = File(root, "manifest.json").readText().removePrefix("\uFEFF")
        val m = JSONObject(text)
        val locale = m.optString("default_locale", "en").ifBlank { "en" }
        val msgs = listOf(locale, "en").firstNotNullOfOrNull { l ->
            File(root, "_locales/$l/messages.json").takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        }
        fun res(s: String): String {
            if (!s.startsWith("__MSG_") || !s.endsWith("__")) return s
            return msgs?.optJSONObject(s.removePrefix("__MSG_").removeSuffix("__"))?.optString("message")?.ifBlank { null } ?: s
        }
        fun strs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
        val scripts = ArrayList<ContentScript>()
        m.optJSONArray("content_scripts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val matches = strs(o.optJSONArray("matches"))
                if (matches.isEmpty()) continue
                scripts.add(ContentScript(matches, strs(o.optJSONArray("exclude_matches")),
                    strs(o.optJSONArray("js")), strs(o.optJSONArray("css")),
                    o.optString("run_at", "document_idle"), o.optBoolean("all_frames", false)))
            }
        }
        val unsupported = ArrayList<String>()
        val bg = m.optJSONObject("background")
        if (bg != null) unsupported.add("background script")
        val act = m.optJSONObject("action") ?: m.optJSONObject("browser_action") ?: m.optJSONObject("page_action")
        if (act?.optString("default_popup")?.isNotBlank() == true) unsupported.add("toolbar popup")
        if (m.has("options_ui") || m.has("options_page")) unsupported.add("options page")
        val perms = strs(m.optJSONArray("permissions"))
        if (perms.any { it == "declarativeNetRequest" || it == "webRequest" || it == "webRequestBlocking" }) unsupported.add("network rules")
        Ext(id, root, rootRel, res(m.optString("name", "Extension")), m.optString("version", ""),
            res(m.optString("description", "")), scripts, m, msgs, unsupported, true, emptySet())
    }.getOrNull()

    // ── Match patterns (chrome match patterns -> JS regex + WebView origin rules) ──
    private class Pat(val regex: String, val rules: List<String>)

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) { if (ch in ".*+?^$(){}|[]\\/") sb.append('\\'); sb.append(ch) }
        return sb.toString()
    }

    private fun compile(p: String): Pat? {
        if (p == "<all_urls>") return Pat("^https?://.*$", listOf("*"))
        val m = Regex("^(\\*|https?)://([^/]*)(/.*)$").matchEntire(p) ?: return null
        val (scheme, host, path) = m.destructured
        val schemes = if (scheme == "*") listOf("http", "https") else listOf(scheme)
        val schemeRe = if (scheme == "*") "https?" else scheme
        val port = "(?::\\d+)?"
        val hostRe: String
        val rules = ArrayList<String>()
        when {
            host == "*" -> { hostRe = "[^/]+"; rules.add("*") }
            host.startsWith("*.") -> {
                val h = host.substring(2)
                hostRe = "(?:[^/]+\\.)?" + esc(h) + port
                schemes.forEach { rules.add("$it://*.$h"); rules.add("$it://$h") }
            }
            else -> { hostRe = esc(host) + port; schemes.forEach { rules.add("$it://$host") } }
        }
        val pathRe = path.split("*").joinToString(".*") { esc(it) }
        return Pat("^$schemeRe://$hostRe$pathRe$", rules)
    }

    /** Origins the WebView should run the script for; the path is checked again in the script itself. */
    fun originRules(cs: ContentScript): Set<String> {
        val rules = LinkedHashSet<String>()
        cs.matches.mapNotNull { compile(it) }.forEach { rules.addAll(it.rules) }
        return if ("*" in rules) setOf("*") else rules
    }

    // ── The script that is injected ──────────────────────────────────────────
    fun buildScript(c: Context, ext: Ext, index: Int): String? = synchronized(lock) {
        val key = ext.id + ":" + index
        if (scriptCache.containsKey(key)) return scriptCache[key]
        val s = runCatching { build(ext, ext.scripts[index]) }.getOrNull()
        scriptCache[key] = s
        s
    }

    private fun readInside(ext: Ext, rel: String): String? {
        val f = File(ext.root, rel).canonicalFile
        if (!f.path.startsWith(ext.root.canonicalPath + File.separator) || !f.isFile || f.length() > MAX_SCRIPT) return null
        return f.readText()
    }

    private fun build(ext: Ext, cs: ContentScript): String {
        val inc = JSONArray(cs.matches.mapNotNull { compile(it)?.regex })
        val exc = JSONArray(cs.excludes.mapNotNull { compile(it)?.regex })
        val css = cs.css.mapNotNull { readInside(ext, it) }.joinToString("\n")
        val code = StringBuilder()
        for (f in cs.js) {
            val src = readInside(ext, f) ?: continue
            code.append("try{\n").append(src).append("\n}catch(__e){console.error('[NeZu extension]',__e);}\n")
        }
        return SHIM_HEAD
            .replace("__ID__", JSONObject.quote(ext.id))
            .replace("__TOKEN__", JSONObject.quote(token(ext.id)))
            .replace("__INC__", inc.toString())
            .replace("__EXC__", exc.toString())
            .replace("__ALLFRAMES__", cs.allFrames.toString())
            .replace("__RUNAT__", JSONObject.quote(cs.runAt))
            .replace("__MANIFEST__", ext.manifest.toString().replace("</", "<\\/"))
            .replace("__MSGS__", (ext.messages ?: JSONObject()).toString())
            .replace("__CSS__", JSONObject.quote(css)) +
            code + SHIM_TAIL
    }

    private const val SHIM_HEAD = """(function(){try{
var ID=__ID__,TOKEN=__TOKEN__,BR=window.NezuExt,J=JSON,INC=__INC__,EXC=__EXC__,RUN=__RUNAT__;
var href=location.href.split('#')[0];
function hit(l){for(var i=0;i<l.length;i++){try{if(new RegExp(l[i]).test(href))return true;}catch(e){}}return false;}
if(!hit(INC)||hit(EXC))return;
if(!__ALLFRAMES__&&window.top!==window)return;
var BASE='https://nezu-ext.invalid/'+ID+'/',MANIFEST=__MANIFEST__,MSGS=__MSGS__,CSS=__CSS__;
function call(m,a){try{var r=BR[m](ID,TOKEN,J.stringify(a===undefined?null:a));return r?J.parse(r):null;}catch(e){return null;}}
function wrap(fn,cb){if(typeof cb==='function'){setTimeout(function(){cb(fn());},0);return;}return new Promise(function(res){setTimeout(function(){res(fn());},0);});}
function ev(){var l=[];return{addListener:function(f){l.push(f);},removeListener:function(f){var i=l.indexOf(f);if(i>=0)l.splice(i,1);},hasListener:function(f){return l.indexOf(f)>=0;},dispatch:function(a,b){for(var i=0;i<l.length;i++){try{l[i](a,b);}catch(e){}}}};}
var changed=ev();
function fire(ch){if(ch&&Object.keys(ch).length)changed.dispatch(ch,'local');}
var S={
get:function(k,cb){return wrap(function(){return call('storageGet',k)||{};},cb);},
set:function(o,cb){return wrap(function(){fire(call('storageSet',o));},cb);},
remove:function(k,cb){return wrap(function(){fire(call('storageRemove',k));},cb);},
clear:function(cb){return wrap(function(){fire(call('storageClear',null));},cb);}
};
var rt={id:ID,getURL:function(p){return BASE+String(p).replace(/^\//,'');},getManifest:function(){return MANIFEST;},
sendMessage:function(){var cb=arguments[arguments.length-1];if(typeof cb==='function'){setTimeout(function(){cb(undefined);},0);return;}return Promise.resolve(undefined);},
connect:function(){return{postMessage:function(){},disconnect:function(){},onMessage:ev(),onDisconnect:ev()};},
onMessage:ev(),onConnect:ev(),lastError:undefined};
var chrome={runtime:rt,extension:{getURL:rt.getURL},storage:{local:S,sync:S,onChanged:changed},
i18n:{getMessage:function(n){var m=MSGS&&MSGS[n];return m&&m.message?m.message:'';},getUILanguage:function(){return navigator.language;}}};
var browser=chrome;
function go(){
if(CSS){var st=document.createElement('style');st.textContent=CSS;(document.head||document.documentElement).appendChild(st);}
"""

    private const val SHIM_TAIL = """
}
if(RUN==='document_start'){go();}
else if(RUN==='document_end'){if(document.readyState!=='loading')go();else document.addEventListener('DOMContentLoaded',go,{once:true});}
else{if(document.readyState!=='loading')setTimeout(go,0);else document.addEventListener('DOMContentLoaded',function(){setTimeout(go,0);},{once:true});}
}catch(e){}})();"""

    // ── Storage + bridge helpers ─────────────────────────────────────────────
    fun token(id: String): String = tokens.getOrPut(id) {
        val b = ByteArray(24); SecureRandom().nextBytes(b); b.joinToString("") { "%02x".format(it) }
    }

    fun tokenOk(c: Context, id: String, token: String) = tokens[id] == token && get(c, id)?.enabled == true

    private fun data(c: Context) = c.applicationContext.getSharedPreferences("nezu_ext_data", Context.MODE_PRIVATE)

    fun storageRead(c: Context, id: String): JSONObject =
        runCatching { JSONObject(data(c).getString(id, "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun storageWrite(c: Context, id: String, o: JSONObject): Boolean {
        val s = o.toString()
        if (s.length > 5_000_000) return false
        data(c).edit().putString(id, s).apply()
        return true
    }

    // ── Extension files for chrome.runtime.getURL ────────────────────────────
    fun serve(c: Context, uri: Uri): WebResourceResponse? {
        if (uri.host != HOST) return null
        fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", mapOf("Access-Control-Allow-Origin" to "*"), "".byteInputStream())
        val segs = uri.pathSegments
        if (segs.size < 2) return notFound()
        val ext = get(c, segs[0])?.takeIf { it.enabled } ?: return notFound()
        val f = runCatching { File(ext.root, segs.drop(1).joinToString("/")).canonicalFile }.getOrNull() ?: return notFound()
        if (!f.path.startsWith(ext.root.canonicalPath + File.separator) || !f.isFile) return notFound()
        val e = f.extension.lowercase()
        val mime = when (e) {
            "js", "mjs" -> "text/javascript"; "wasm" -> "application/wasm"; "json" -> "application/json"
            "css" -> "text/css"; "svg" -> "image/svg+xml"; "html", "htm" -> "text/html"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "application/octet-stream"
        }
        return WebResourceResponse(mime, null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), FileInputStream(f))
    }
}
