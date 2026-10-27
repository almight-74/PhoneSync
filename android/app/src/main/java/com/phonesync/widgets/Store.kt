package com.phonesync.widgets

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A saved capture target. `target` is a note path (append/prepend), a folder (new) or a command id (command). */
data class Destination(
    val name: String,
    val mode: String,          // append | prepend | new | command
    val target: String,
    val heading: String = "",
    val template: String = "{{text}}",
    val fileName: String = "{{date}} {{time}}",
)

/** Lists sent by the plugin for the pickers. */
data class Catalog(val commands: List<Pair<String, String>>, val folders: List<String>, val notes: List<String>, val received: Long)

/** Everything the app persists: note snapshots from the plugin and per-widget settings. */
object Store {
    const val APPEND = "append" // button target: open the capture dialog
    const val VOICE = "voice"   // button target: capture dialog with dictation started

    val MODES = listOf("append", "prepend", "new", "command")
    val MODE_LABELS = listOf("Add to end of note", "Add to top of note", "New note in folder", "Run a command")
    const val DAILY = "@daily"

    /** Default buttons for a new Actions widget: label to target. */
    val DEFAULT_ACTIONS = listOf(
        "＋ New" to "file-explorer:new-file",
        "Daily" to bridge("open", "file" to "@daily"),
        "✏️ Capture" to APPEND,
        "🎤 Voice" to VOICE,
    )

    val DEFAULT_DESTINATIONS = listOf(
        Destination("Daily note", "append", "@daily"),
        Destination("Inbox (new note)", "new", "Inbox"),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    /** Builds obsidian://widget-bridge?do=...&k=v (handled by the Widget Bridge plugin). */
    fun bridge(action: String, vararg params: Pair<String, String>): String {
        val b = Uri.Builder().scheme("obsidian").authority("widget-bridge").appendQueryParameter("do", action)
        params.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        return b.build().toString()
    }

    /**
     * A button target is a URI, the APPEND/VOICE marker, or a bare Obsidian command id.
     * Commands leave Obsidian open (no back=1) so things like audio recorders keep running.
     */
    fun targetUri(target: String): String = when {
        // Buttons saved by v0.2.0 used ?action=, which Obsidian overwrites; rewrite to ?do=.
        target.startsWith("obsidian://widget-bridge?action=") ->
            target.replaceFirst("obsidian://widget-bridge?action=", "obsidian://widget-bridge?do=")
        target.contains("://") -> target
        else -> bridge("command", "id" to target)
    }

    /** Human-readable name for a button target. */
    fun describe(c: Context, target: String): String = when {
        target == APPEND -> "Capture"
        target == VOICE -> "Voice note"
        target.isBlank() -> "Nothing"
        target.startsWith("obsidian://widget-bridge?do=open") ->
            "Open " + (Uri.parse(target).getQueryParameter("file") ?: "note")
        target.contains("://") -> target
        else -> commandName(c, target)
    }

    // ---------- catalog ----------

    fun saveCatalog(c: Context, json: String) {
        JSONObject(json) // validate before storing
        prefs(c).edit().putString("catalog", json).putLong("catalogAt", System.currentTimeMillis()).apply()
    }

    fun catalog(c: Context): Catalog {
        val o = prefs(c).getString("catalog", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return Catalog(emptyList(), emptyList(), emptyList(), 0)
        fun strings(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val cmds = o.optJSONArray("commands")?.let { a ->
            (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
        } ?: emptyList()
        return Catalog(cmds, strings("folders"), strings("notes"), prefs(c).getLong("catalogAt", 0))
    }

    fun commandName(c: Context, id: String): String =
        catalog(c).commands.firstOrNull { it.first == id }?.second ?: id

    // ---------- snapshots ----------

    fun saveSnapshot(c: Context, json: String) {
        val key = JSONObject(json).getString("key")
        val keys = prefs(c).getStringSet("keys", emptySet())!! + key
        prefs(c).edit().putString("snap:$key", json).putStringSet("keys", keys)
            .putLong("recv:$key", System.currentTimeMillis()).apply()
    }

    /** When the app last received [key] from Obsidian (0 = never). */
    fun received(c: Context, key: String): Long = prefs(c).getLong("recv:$key", 0)

    fun snapshot(c: Context, key: String): JSONObject? =
        prefs(c).getString("snap:$key", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun keys(c: Context): List<String> = prefs(c).getStringSet("keys", emptySet())!!.sorted()

    fun forgetKey(c: Context, key: String) {
        val keys = prefs(c).getStringSet("keys", emptySet())!! - key
        prefs(c).edit().putStringSet("keys", keys).remove("snap:$key").remove("recv:$key").apply()
    }

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

    // ---------- capture ----------

    fun destinations(c: Context): List<Destination> {
        val raw = prefs(c).getString("destinations", null) ?: return DEFAULT_DESTINATIONS
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Destination(o.optString("name"), o.optString("mode", "append"), o.optString("target"),
                o.optString("heading"), o.optString("template", "{{text}}"), o.optString("fileName", "{{date}} {{time}}"))
        }
    }

    fun setDestinations(c: Context, list: List<Destination>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("mode", it.mode).put("target", it.target)
                .put("heading", it.heading).put("template", it.template).put("fileName", it.fileName))
        }
        prefs(c).edit().putString("destinations", arr.toString()).apply()
    }

    /** Fills {{text}}, {{title}}, {{date}}, {{time}}, {{datetime}}. */
    fun expand(template: String, text: String, now: Date = Date()): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val time = SimpleDateFormat("HH:mm", Locale.US).format(now)
        val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: ""
        // User text goes in last so tokens typed inside it stay literal.
        return template.ifBlank { "{{text}}" }
            .replace("{{datetime}}", "$date $time").replace("{{date}}", date).replace("{{time}}", time)
            .replace("{{title}}", title).replace("{{text}}", text)
    }

    /** The obsidian:// URI that delivers [text] to [d] through the plugin's capture action. */
    fun captureUri(d: Destination, text: String): String {
        if (d.mode == "command") return bridge("command", "id" to d.target)
        val now = Date()
        val body = expand(d.template, text, now)
        return if (d.mode == "new") {
            bridge("capture", "mode" to "new", "folder" to d.target,
                "name" to expand(d.fileName.ifBlank { "{{date}} {{time}}" }, text, now), "text" to body, "back" to "1")
        } else {
            bridge("capture", "mode" to d.mode, "file" to d.target.ifBlank { "@daily" },
                "heading" to d.heading, "text" to body, "back" to "1")
        }
    }
}
