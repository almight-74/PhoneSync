package com.phonesync.widgets

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Floating editor over the home screen for a widget's note. Starts from the last snapshot;
 * Save sends the whole note back with the snapshot's hash so the plugin can detect newer edits.
 */
class EditorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_KEY) ?: return finish()
        val snap = Store.snapshot(this, key)
        val original = snap?.takeIf { it.has("text") }?.optString("text")
        if (snap == null || original == null) {
            Toast.makeText(this, "Open this note in Obsidian once (or update the plugin) to edit it here", Toast.LENGTH_LONG).show()
            snap?.optString("open")?.takeIf { it.isNotBlank() }?.let { view(it) }
            return finish()
        }
        title = snap.optString("title", key)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP
            minLines = 8
            if (savedInstanceState == null) {
                setText(original)
                setSelection(text.length)
            }
        }
        val save = Button(this).apply {
            text = "Save"
            setOnClickListener {
                val edited = editor.text.toString()
                if (edited == original) return@setOnClickListener finish()
                view(Store.bridge("replace", "file" to key, "text" to edited, "base" to snap.optString("hash"), "back" to "1"))
                finish()
            }
        }
        val cancel = Button(this).apply { text = "Cancel"; setOnClickListener { finish() } }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(this@EditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(save, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        })
        editor.requestFocus()
    }

    private fun view(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Obsidian is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_KEY = "key"
    }
}
