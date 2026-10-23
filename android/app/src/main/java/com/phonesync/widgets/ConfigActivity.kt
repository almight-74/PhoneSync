package com.phonesync.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** Widget setup (note picker / button editor). Opened from the launcher it shows setup help. */
class ConfigActivity : Activity() {
    private val id by lazy {
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    }
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val pad = (16 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(root) })

        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className
        when (provider) {
            NoteWidgetProvider::class.java.name -> noteSetup()
            ActionsWidgetProvider::class.java.name -> actionsSetup()
            else -> help()
        }
    }

    private fun noteSetup() {
        title = "Pick a note"
        val keys = Store.keys(this)
        text(if (keys.isEmpty())
            "No notes received yet. In Obsidian, open a note and run \"Widget Bridge: Show current note in a widget\", then come back."
        else "Notes shared from Obsidian:")
        val group = RadioGroup(this)
        val current = Store.noteKey(this, id)
        keys.forEachIndexed { i, key ->
            group.addView(RadioButton(this).apply {
                this.id = i + 1
                text = Store.snapshot(this@ConfigActivity, key)?.optString("title")?.let { "$it  ($key)" } ?: key
                isChecked = key == current
            })
        }
        root.addView(group)
        text("Or type a path pinned in the plugin settings (e.g. Tasks/Today.md or @daily):")
        val manual = EditText(this).apply { setText(current?.takeIf { it !in keys } ?: "") }
        root.addView(manual)
        button("Save") {
            val key = manual.text.toString().trim().ifEmpty { keys.getOrNull(group.checkedRadioButtonId - 1) }
                ?: return@button
            Store.setNoteKey(this, id, key)
            NoteWidgetProvider.update(this, AppWidgetManager.getInstance(this), id)
            done()
        }
    }

    private fun actionsSetup() {
        title = "Buttons"
        text("Each button: a label and a target. Target can be an Obsidian command id " +
            "(e.g. daily-notes, switcher:open), any URI (obsidian://...), or \"append\" for Quick append.")
        val fields = Store.actions(this, id).let { saved ->
            (0 until 4).map { i ->
                val (label, target) = saved.getOrNull(i) ?: ("" to "")
                val l = EditText(this).apply { hint = "Label ${i + 1}"; setText(label) }
                val t = EditText(this).apply { hint = "Target ${i + 1}"; setText(target) }
                root.addView(l); root.addView(t)
                l to t
            }
        }
        button("Save") {
            Store.setActions(this, id, fields.map { (l, t) -> l.text.toString().trim() to t.text.toString().trim() })
            ActionsWidgetProvider.update(this, AppWidgetManager.getInstance(this), id)
            done()
        }
    }

    private fun help() {
        title = getString(R.string.app_name)
        text("""
            Setup
            1. Install the Widget Bridge plugin in Obsidian (BRAT → almight-74/PhoneSync).
            2. In Obsidian, open a note → command "Widget Bridge: Show current note in a widget".
            3. Long-press the home screen → Widgets → Obsidian Widgets.

            Routines / Tasker / any app can use these URIs:
            obsidian://widget-bridge?action=command&id=<command-id>
            obsidian://widget-bridge?action=append&file=Inbox&text=Hello
            obsidian://widget-bridge?action=open&file=@daily
            obsidian://widget-bridge?action=toggle&file=Tasks.md&line=3
            Add &back=1 to return to the home screen afterwards.
        """.trimIndent())
        button("Quick append") { startActivity(Intent(this, AppendActivity::class.java)) }
    }

    private fun text(s: String) = root.addView(TextView(this).apply { text = s; setPadding(0, 16, 0, 16) })

    private fun button(label: String, onClick: () -> Unit) =
        root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })

    private fun done() {
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }

    companion object {
        fun intentFor(c: Context, id: Int): Intent = Intent(c, ConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
