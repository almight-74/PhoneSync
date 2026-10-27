package com.phonesync.widgets

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Everything is set up here, with pickers instead of typing:
 *  - launched from the app icon: notes in widgets, capture options, lists status
 *  - launched for a Note widget: pick its note
 *  - launched for an Actions widget: pick what each of the 4 buttons does
 */
class ConfigActivity : Activity() {
    private val id by lazy {
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    }
    private lateinit var root: LinearLayout
    private var onPicked: ((value: String, label: String) -> Unit)? = null
    private var returningFromPicker = false
    private var resumedBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val pad = (16 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onResume() {
        super.onResume()
        // Lists or notes may have arrived from Obsidian while we were away; widget setup screens keep
        // their unsaved choices, and a picker result was already applied in onActivityResult.
        if (returningFromPicker) { returningFromPicker = false; return }
        if (resumedBefore && id == AppWidgetManager.INVALID_APPWIDGET_ID) render()
        resumedBefore = true
    }

    private fun render() {
        root.removeAllViews()
        when (AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className) {
            NoteWidgetProvider::class.java.name -> noteSetup()
            ActionsWidgetProvider::class.java.name -> actionsSetup()
            else -> home()
        }
    }

    // ---------- widget setup ----------

    private fun noteSetup() {
        title = "Which note?"
        val current = Store.noteKey(this, id)
        Store.keys(this).forEach { key ->
            button((if (key == current) "✓ " else "") + noteLabel(key)) { chooseNote(key) }
        }
        button("＋ Another note…") {
            pick(PickerActivity.NOTE) { value, _ -> pin(value); chooseNote(value) }
        }
    }

    private fun chooseNote(key: String) {
        Store.setNoteKey(this, id, key)
        NoteWidgetProvider.update(this, AppWidgetManager.getInstance(this), id)
        done()
    }

    private fun actionsSetup() {
        title = "Buttons"
        text("Tap a button to choose what it does. You can rename it too.")
        val actions = Store.actions(this, id).toMutableList()
        while (actions.size < 4) actions += "" to ""
        val labels = actions.mapIndexed { i, (label, target) ->
            val name = EditText(this).apply { hint = "Name"; setText(label) }
            val what = Button(this)
            fun show() { what.text = "Does: " + Store.describe(this, actions[i].second) }
            show()
            what.setOnClickListener {
                chooseTarget { newTarget, suggested ->
                    actions[i] = name.text.toString() to newTarget
                    if (name.text.isBlank() || name.text.toString() == label) name.setText(suggested)
                    show()
                }
            }
            heading("Button ${i + 1}")
            root.addView(name)
            root.addView(what)
            name
        }
        button("Save") {
            Store.setActions(this, id, actions.mapIndexed { i, (_, t) -> labels[i].text.toString().trim() to t })
            ActionsWidgetProvider.update(this, AppWidgetManager.getInstance(this), id)
            done()
        }
    }

    /** Asks what a button should do; returns (target, suggested label). */
    private fun chooseTarget(onChosen: (String, String) -> Unit) {
        val kinds = arrayOf("Run an Obsidian command…", "Open a note…", "Capture (type or dictate)", "Voice note")
        AlertDialog.Builder(this).setTitle("What should it do?").setItems(kinds) { _, which ->
            when (which) {
                0 -> pick(PickerActivity.COMMAND) { v, l -> onChosen(v, l.substringAfter(": ").take(14)) }
                1 -> pick(PickerActivity.NOTE) { v, l -> onChosen(Store.bridge("open", "file" to v), l.substringAfterLast('/').take(14)) }
                2 -> onChosen(Store.APPEND, "✏️ Capture")
                3 -> onChosen(Store.VOICE, "🎤 Voice")
            }
        }.show()
    }

    // ---------- app home ----------

    private fun home() {
        title = getString(R.string.app_name)

        heading("Notes in widgets")
        val keys = Store.keys(this)
        if (keys.isEmpty()) text("None yet.")
        keys.forEach { key ->
            row(noteLabel(key), "Remove") {
                view(Store.bridge("unpin", "file" to key, "back" to "1"))
                Store.forgetKey(this, key)
                render()
            }
        }
        button("＋ Show a note in a widget") {
            pick(PickerActivity.NOTE) { value, label ->
                pin(value)
                Toast.makeText(this, "$label will appear when Obsidian sends it", Toast.LENGTH_LONG).show()
            }
        }

        heading("Capture options")
        text("These are the buttons you see when you capture or share to Obsidian.")
        val destinations = Store.destinations(this).toMutableList()
        destinations.forEachIndexed { i, d ->
            button(d.name + "  ·  " + summary(d)) { editDestination(destinations, i) }
        }
        button("＋ Add capture option") {
            destinations += Destination("New option", "append", Store.DAILY)
            editDestination(destinations, destinations.lastIndex)
        }

        heading("Lists from Obsidian")
        val cat = Store.catalog(this)
        text(if (cat.received == 0L) "Not received yet."
        else "${cat.commands.size} commands, ${cat.folders.size} folders, ${cat.notes.size} notes · " +
            DateUtils.getRelativeTimeSpanString(cat.received))
        button("Refresh lists") { view(Store.bridge("catalog", "back" to "1")) }

        heading("Try it")
        button("Open capture") { startActivity(Intent(this, CaptureActivity::class.java)) }
        small("Tip: tap the text of a note widget to edit it here; tap its title to open it in Obsidian.")
    }

    private fun summary(d: Destination): String = when (d.mode) {
        "new" -> "new note in ${d.target.ifBlank { "/" }}"
        "command" -> "runs ${Store.commandName(this, d.target)}"
        "prepend" -> "top of ${noteName(d.target)}"
        else -> "end of ${noteName(d.target)}"
    }

    private fun editDestination(list: MutableList<Destination>, i: Int) {
        var d = list[i]
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val name = EditText(this).apply { hint = "Name"; setText(d.name) }
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(this@ConfigActivity, android.R.layout.simple_spinner_dropdown_item, Store.MODE_LABELS)
            setSelection(Store.MODES.indexOf(d.mode).coerceAtLeast(0))
        }
        val target = Button(this)
        fun showTarget() {
            target.text = when (Store.MODES[type.selectedItemPosition]) {
                "new" -> "Folder: ${d.target.ifBlank { "/" }}"
                "command" -> "Command: ${if (d.target.isBlank()) "choose…" else Store.commandName(this, d.target)}"
                else -> "Note: ${noteName(d.target)}"
            }
        }
        type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val mode = Store.MODES[pos]
                if (mode != d.mode) d = d.copy(mode = mode, target = if (mode == "new") "Inbox" else if (mode == "command") "" else Store.DAILY)
                showTarget()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        target.setOnClickListener {
            val mode = when (Store.MODES[type.selectedItemPosition]) {
                "new" -> PickerActivity.FOLDER; "command" -> PickerActivity.COMMAND; else -> PickerActivity.NOTE
            }
            pick(mode) { value, label ->
                d = d.copy(target = value)
                if (name.text.isBlank() || name.text.toString() == "New option") name.setText(label.substringAfterLast('/'))
                showTarget()
            }
        }
        val more = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val headingField = EditText(this).apply { hint = "Under heading (optional)"; setText(d.heading) }
        val fileName = EditText(this).apply { hint = "New note name, e.g. {{date}} {{time}}"; setText(d.fileName) }
        val template = EditText(this).apply { hint = "Template, e.g. - [ ] {{text}}"; setText(d.template) }
        more.addView(TextView(this).apply { text = "Tokens: {{text}} {{title}} {{date}} {{time}}"; alpha = 0.6f })
        listOf(headingField, fileName, template).forEach { more.addView(it) }
        val moreToggle = Button(this).apply {
            text = "More…"
            setOnClickListener { more.visibility = View.VISIBLE; visibility = View.GONE }
        }
        listOf(name, type, target, moreToggle, more).forEach { form.addView(it) }
        showTarget()

        AlertDialog.Builder(this)
            .setTitle("Capture option")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                list[i] = d.copy(name = name.text.toString().trim().ifEmpty { "Option" },
                    heading = headingField.text.toString().trim(), fileName = fileName.text.toString().trim(),
                    template = template.text.toString())
                Store.setDestinations(this, list)
                render()
            }
            .setNeutralButton("Delete") { _, _ ->
                list.removeAt(i)
                Store.setDestinations(this, list)
                render()
            }
            .setNegativeButton("Cancel") { _, _ -> render() }
            .show()
    }

    // ---------- helpers ----------

    private fun pin(note: String) = view(Store.bridge("pin", "file" to note, "back" to "1"))

    private fun noteLabel(key: String): String {
        val title = Store.snapshot(this, key)?.optString("title")?.takeIf { it.isNotBlank() } ?: noteName(key)
        val t = Store.received(this, key)
        return if (t == 0L) title else "$title  ·  ${DateUtils.getRelativeTimeSpanString(t)}"
    }

    private fun noteName(path: String) =
        if (path == Store.DAILY || path.isBlank()) "today's daily note" else path.substringAfterLast('/').removeSuffix(".md")

    private fun pick(mode: String, cb: (String, String) -> Unit) {
        onPicked = cb
        returningFromPicker = true
        startActivityForResult(Intent(this, PickerActivity::class.java).putExtra(PickerActivity.EXTRA_MODE, mode), REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val cb = onPicked
        onPicked = null
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || cb == null) return
        cb(data.getStringExtra(PickerActivity.EXTRA_VALUE) ?: return, data.getStringExtra(PickerActivity.EXTRA_LABEL) ?: "")
    }

    private fun view(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Obsidian is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun heading(s: String) = root.addView(TextView(this).apply {
        text = s
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 32, 0, 8)
    })

    private fun text(s: String) = root.addView(TextView(this).apply { text = s; setPadding(0, 8, 0, 8) })

    private fun small(s: String) = root.addView(TextView(this).apply { text = s; alpha = 0.6f; setPadding(0, 16, 0, 0) })

    private fun button(label: String, onClick: () -> Unit) =
        root.addView(Button(this).apply { text = label; setAllCaps(false); setOnClickListener { onClick() } })

    private fun row(label: String, action: String, onClick: () -> Unit) = root.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(this@ConfigActivity).apply { text = label }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(this@ConfigActivity).apply { text = action; setAllCaps(false); setOnClickListener { onClick() } })
    })

    private fun done() {
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }

    companion object {
        private const val REQ_PICK = 7

        fun intentFor(c: Context, id: Int): Intent = Intent(c, ConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
