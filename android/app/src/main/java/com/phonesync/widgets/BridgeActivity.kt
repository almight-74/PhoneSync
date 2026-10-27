package com.phonesync.widgets

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible hop between widgets and Obsidian.
 *  - obsidianwidget://snapshot?d=<json>  from the plugin: store it and refresh widgets
 *  - obsidianwidget://home               from the plugin: go back to the home screen
 *  - obsidianwidget://catalog?d=<json>   from the plugin: store command/folder/note lists
 *  - EXTRA_URI                            from a widget tap: open that obsidian:// URI, or
 *                                         "edit:<note>" to open the home-screen editor
 */
class BridgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent.data
        when {
            data?.scheme == "obsidianwidget" && data.host == "snapshot" -> {
                data.getQueryParameter("d")?.let {
                    runCatching { Store.saveSnapshot(this, it) }
                    NoteWidgetProvider.refreshAll(this)
                }
            }
            data?.scheme == "obsidianwidget" && data.host == "catalog" ->
                data.getQueryParameter("d")?.let { runCatching { Store.saveCatalog(this, it) } }
            data?.scheme == "obsidianwidget" && data.host == "home" ->
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> intent.getStringExtra(EXTRA_URI)?.takeIf { it.isNotBlank() }?.let { open(it) }
        }
        finish()
    }

    private fun open(target: String) {
        if (target.startsWith(EDIT)) {
            startActivity(Intent(this, EditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EditorActivity.EXTRA_KEY, target.removePrefix(EDIT)))
            return
        }
        if (target == Store.APPEND || target == Store.VOICE) {
            startActivity(Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setAction(if (target == Store.VOICE) CaptureActivity.ACTION_VOICE else Intent.ACTION_MAIN))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Store.targetUri(target)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Obsidian is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EDIT = "edit:"
    }
}
