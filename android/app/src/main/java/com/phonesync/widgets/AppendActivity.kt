package com.phonesync.widgets

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout

/** Small dialog: type a line, it is appended to a note through the plugin. */
class AppendActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.append)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val target = EditText(this).apply {
            hint = "Note (e.g. Inbox or @daily)"
            setText(Store.appendTarget(this@AppendActivity))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val text = EditText(this).apply {
            hint = "Text"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val asTask = CheckBox(this).apply { this.text = "As task (- [ ])" }
        val add = Button(this).apply { this.text = "Add" }

        fun submit() {
            val body = text.text.toString().trim()
            if (body.isEmpty()) return
            val note = target.text.toString().trim().ifEmpty { "@daily" }
            Store.setAppendTarget(this, note)
            val line = if (asTask.isChecked) "- [ ] $body" else body
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse(Store.bridge("append", "file" to note, "text" to line, "back" to "1")))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        add.setOnClickListener { submit() }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(target)
            addView(text)
            addView(asTask)
            addView(add)
        })
        text.requestFocus()
    }
}
