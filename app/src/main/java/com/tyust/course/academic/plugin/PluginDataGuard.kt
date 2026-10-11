package com.tyust.course.academic.plugin

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Conservative plugin-wide label: persists across accounts, updates and process death.
 * No attempt to infer whether encoded script-controlled bytes contain personal data. */
class PluginDataGuard(app: Context, private val pkg: PluginPackage) {
    private val prefs = app.getSharedPreferences("plugin-data-security", Context.MODE_PRIVATE)
    private val id = pkg.manifest.id
    private val generation = prefs.getLong("generation:$id", 0L)
    private val identity = PluginSiteConsent.owner(pkg, "all-retained-accounts", "disclosure", null)
    private fun origin(url: HttpUrl) = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
    fun sensitive(): Boolean = prefs.getBoolean("label:$id", false) ||
        pkg.manifest.sharesAcademicSession || pkg.manifest.permissions.any { it in setOf("academic.read", "academic.session") }
    fun mark() = synchronized(lock) { check(prefs.edit().putBoolean("label:$id", true).commit()) }
    fun inherit(other: PluginDataGuard) { if (other.sensitive()) mark() }
    private fun key(origin: String): String {
        val rule = pkg.manifest.json.optJSONArray("dataDisclosure")?.let(PluginJson::objects)?.singleOrNull { it.optString("origin") == origin }
        return "grant-v2:$id:$identity:" + PluginJson.sha256(PluginJson.canonical(rule ?: JSONObject().put("origin", origin)).toByteArray())
    }
    fun declaration(url: HttpUrl): JSONObject? = pkg.manifest.json.optJSONArray("dataDisclosure")?.let(PluginJson::objects)
        ?.singleOrNull { it.optString("origin") == origin(url) &&
            "academic" in PluginJson.strings(it.optJSONArray("categories") ?: JSONArray()) }
    fun requireCurrent() { if (prefs.getLong("generation:$id", 0L) != generation) denied() }
    fun allowed(url: HttpUrl): Boolean = !sensitive() || declaration(url) != null && prefs.getBoolean(key(origin(url)), false)
    fun authorize(url: HttpUrl) = synchronized(lock) {
        if (prefs.getLong("generation:$id", 0L) != generation || declaration(url) == null) denied()
        check(prefs.edit().putBoolean(key(origin(url)), true).commit())
    }
    fun track(operation: PluginOperation) = synchronized(lock) {
        if (prefs.getLong("generation:$id", 0L) != generation) denied()
        active.getOrPut(id) { mutableSetOf() }.add(operation)
    }
    fun untrack(operation: PluginOperation) = synchronized(lock) { active[id]?.remove(operation); Unit }
    fun requireNetwork(url: HttpUrl) {
        requireCurrent()
        if (!allowed(url)) { audit("blocked", origin(url)); denied() }
        if (sensitive()) audit("disclosure", origin(url))
    }
    fun status(): JSONObject = JSONObject().put("localData", sensitive()).put("destinations", JSONArray(
        pkg.manifest.json.optJSONArray("dataDisclosure")?.let(PluginJson::objects).orEmpty().map { rule ->
            val origin = rule.optString("origin")
            JSONObject().put("origin", origin).put("purpose", rule.optString("purpose"))
                .put("authorized", prefs.getBoolean(key(origin), false))
        })).put("events", JSONArray(prefs.getString("events:$id", "[]")))
    fun revoke() = synchronized(lock) {
        val edit = prefs.edit()
        prefs.all.keys.filter { (it.startsWith("grant:$id:") || it.startsWith("grant-v2:$id:")) }.forEach(edit::remove)
        edit.putLong("generation:$id", prefs.getLong("generation:$id", 0L) + 1)
        check(edit.commit()) // The data label deliberately survives revocation.
        active.remove(id)?.forEach(PluginOperation::close)
    }
    private fun audit(event: String, origin: String) = synchronized(lock) {
        val old = JSONArray(prefs.getString("events:$id", "[]"))
        val rows = (0 until old.length()).toList().takeLast(19).map { old.get(it) }
        check(prefs.edit().putString("events:$id", JSONArray(rows).put(JSONObject()
            .put("event", event).put("origin", origin).put("at", System.currentTimeMillis())).toString()).commit())
    }
    companion object {
        private val lock = Any()
        private val active = mutableMapOf<String, MutableSet<PluginOperation>>()
        fun revoke(app: Context, id: String) = synchronized(lock) {
            val prefs = app.getSharedPreferences("plugin-data-security", Context.MODE_PRIVATE)
            val edit = prefs.edit()
            prefs.all.keys.filter { (it.startsWith("grant:$id:") || it.startsWith("grant-v2:$id:")) }.forEach(edit::remove)
            check(edit.putLong("generation:$id", prefs.getLong("generation:$id", 0L) + 1).commit())
            active.remove(id)?.forEach(PluginOperation::close)
        }
        private fun denied(): Nothing = throw PluginException(PluginErrorCode.PERMISSION_DENIED, "此插件已接触个人数据；请先声明并单独授权接收网站和用途")
    }
}
