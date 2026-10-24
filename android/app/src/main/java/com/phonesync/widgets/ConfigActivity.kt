package com.phonesync.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

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
            "(e.g. daily-notes, switcher:open), any URI (obsidian://...), \"append\" for capture or \"voice\" for dictation.")
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
        heading("Notes received from Obsidian")
        val keys = Store.keys(this)
        text(if (keys.isEmpty()) "None yet. In Obsidian run \"Widget Bridge: Show current note in a widget\"."
        else keys.joinToString("\n") { key ->
            val t = Store.received(this, key)
            "• $key — " + if (t == 0L) "never" else DateUtils.getRelativeTimeSpanString(t).toString()
        })

        heading("Capture destinations")
        text("Mode: append / prepend add to a note (path or @daily, optional heading); new creates a note " +
            "in a folder. Tokens for template and file name: {{text}} {{title}} {{date}} {{time}} {{datetime}}")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        val editors = mutableListOf<DestinationEditor>()
        fun add(d: Destination) {
            val e = DestinationEditor(d)
            editors += e
            list.addView(e.view)
            e.onDelete = { editors.remove(e); list.removeView(e.view) }
        }
        Store.destinations(this).forEach(::add)
        button("+ Add destination") { add(Destination("New destination", "append", "@daily")) }
        button("Save destinations") {
            Store.setDestinations(this, editors.map { it.value() }.filter { it.name.isNotBlank() })
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        button("Open capture") { startActivity(Intent(this, CaptureActivity::class.java)) }

        heading("Routines / Tasker URIs")
        text("""
            obsidian://widget-bridge?do=command&id=<command-id>
            obsidian://widget-bridge?do=capture&mode=append&file=@daily&text=Hello
            obsidian://widget-bridge?do=capture&mode=new&folder=Inbox&name=Idea&text=Hello
            obsidian://widget-bridge?do=open&file=@daily
            obsidian://widget-bridge?do=push
            Add &back=1 to return to the home screen afterwards.
        """.trimIndent())
    }

    /** Field block for one capture destination. */
    private inner class DestinationEditor(d: Destination) {
        var onDelete: () -> Unit = {}
        private val name = field("Name", d.name)
        private val mode = Spinner(this@ConfigActivity).apply {
            adapter = ArrayAdapter(this@ConfigActivity, android.R.layout.simple_spinner_dropdown_item, Store.MODES)
            setSelection(Store.MODES.indexOf(d.mode).coerceAtLeast(0))
        }
        private val target = field("Note (append/prepend) or folder (new)", d.target)
        private val heading = field("Heading (optional)", d.heading)
        private val fileName = field("File name for new notes", d.fileName)
        private val template = field("Template", d.template)
        val view = LinearLayout(this@ConfigActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 24)
            listOf(name, mode, target, heading, fileName, template).forEach { addView(it) }
            addView(Button(this@ConfigActivity).apply { text = "Delete"; setOnClickListener { onDelete() } })
        }

        fun value() = Destination(name.text.toString().trim(), Store.MODES[mode.selectedItemPosition],
            target.text.toString().trim(), heading.text.toString().trim(), template.text.toString(),
            fileName.text.toString().trim())

        private fun field(hint: String, value: String) = EditText(this@ConfigActivity).apply {
            this.hint = hint
            setText(value)
        }
    }

    private fun heading(s: String) = root.addView(TextView(this).apply {
        text = s
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 32, 0, 8)
    })

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
