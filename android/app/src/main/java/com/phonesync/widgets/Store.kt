package com.phonesync.widgets

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Everything the app persists: note snapshots from the plugin and per-widget settings. */
object Store {
    const val APPEND = "append"

    /** Default buttons for a new Actions widget: label to target. */
    val DEFAULT_ACTIONS = listOf(
        "＋ New" to "file-explorer:new-file",
        "Daily" to bridge("open", "file" to "@daily"),
        "Find" to "switcher:open",
        "Append" to APPEND,
    )

    private fun prefs(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    /** Builds obsidian://widget-bridge?action=...&k=v (handled by the Widget Bridge plugin). */
    fun bridge(action: String, vararg params: Pair<String, String>): String {
        val b = Uri.Builder().scheme("obsidian").authority("widget-bridge").appendQueryParameter("action", action)
        params.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        return b.build().toString()
    }

    /** A button target is a URI, the APPEND marker, or a bare Obsidian command id. */
    fun targetUri(target: String): String =
        if (target.contains("://")) target else bridge("command", "id" to target, "back" to "1")

    // ---------- snapshots ----------

    fun saveSnapshot(c: Context, json: String) {
        val key = JSONObject(json).getString("key")
        val keys = prefs(c).getStringSet("keys", emptySet())!! + key
        prefs(c).edit().putString("snap:$key", json).putStringSet("keys", keys).apply()
    }

    fun snapshot(c: Context, key: String): JSONObject? =
        prefs(c).getString("snap:$key", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun keys(c: Context): List<String> = prefs(c).getStringSet("keys", emptySet())!!.sorted()

    // ---------- per-widget settings ----------

    fun noteKey(c: Context, id: Int): String? = prefs(c).getString("w:$id:note", null)

    fun setNoteKey(c: Context, id: Int, key: String) = prefs(c).edit().putString("w:$id:note", key).apply()

    fun actions(c: Context, id: Int): List<Pair<String, String>> {
        val raw = prefs(c).getString("w:$id:actions", null) ?: return DEFAULT_ACTIONS
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONArray(it).let { a -> a.getString(0) to a.getString(1) } }
    }

    fun setActions(c: Context, id: Int, actions: List<Pair<String, String>>) {
        val arr = JSONArray()
        actions.forEach { (label, target) -> arr.put(JSONArray().put(label).put(target)) }
        prefs(c).edit().putString("w:$id:actions", arr.toString()).apply()
    }

    fun forget(c: Context, id: Int) = prefs(c).edit().remove("w:$id:note").remove("w:$id:actions").apply()

    // ---------- quick append ----------

    fun appendTarget(c: Context): String = prefs(c).getString("appendTarget", "@daily")!!

    fun setAppendTarget(c: Context, v: String) = prefs(c).edit().putString("appendTarget", v).apply()
}
