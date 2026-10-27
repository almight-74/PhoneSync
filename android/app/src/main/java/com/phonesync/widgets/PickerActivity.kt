package com.phonesync.widgets

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Searchable list of commands, folders or notes from the plugin's catalog.
 * Returns EXTRA_VALUE (id/path) and EXTRA_LABEL (display name).
 */
class PickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: NOTE
        val catalog = Store.catalog(this)
        // (value, label)
        val all: List<Pair<String, String>> = when (mode) {
            COMMAND -> catalog.commands.map { it.first to it.second }
            FOLDER -> listOf("" to "/ (vault root)") + catalog.folders.map { it to it }
            else -> listOf(Store.DAILY to "Today's daily note") + catalog.notes.map { it to it.removeSuffix(".md") }
        }
        title = when (mode) { COMMAND -> "Choose a command"; FOLDER -> "Choose a folder"; else -> "Choose a note" }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val search = EditText(this).apply { hint = "Search" }
        val list = ListView(this)
        var shown = all
        fun render(q: String) {
            shown = if (q.isBlank()) all else all.filter { it.second.contains(q, ignoreCase = true) || it.first.contains(q, ignoreCase = true) }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.second })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render(s?.toString() ?: "")
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val (value, label) = shown[pos]
            setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, value).putExtra(EXTRA_LABEL, label))
            finish()
        }

        root.addView(search)
        if (catalog.received == 0L || (mode == COMMAND && catalog.commands.isEmpty())) {
            root.addView(TextView(this).apply {
                text = "The lists come from Obsidian. Tap below; Obsidian opens for a moment and sends them. Then come back here."
                setPadding(0, pad / 2, 0, pad / 2)
            })
            root.addView(Button(this).apply {
                text = "Get lists from Obsidian"
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Store.bridge("catalog", "back" to "1")))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
            })
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render("")
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_VALUE = "value"
        const val EXTRA_LABEL = "label"
        const val NOTE = "note"
        const val FOLDER = "folder"
        const val COMMAND = "command"
    }
}
