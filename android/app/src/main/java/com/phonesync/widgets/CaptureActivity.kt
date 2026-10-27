package com.phonesync.widgets

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Capture dialog: type or dictate, then tap where it goes (one big button per capture option).
 * "Run a command" options ignore the text and just run the command.
 * Also the share-sheet target ("Send to Obsidian") for text from other apps.
 */
class CaptureActivity : Activity() {
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Send to Obsidian"
        val pad = (16 * resources.displayMetrics.density).toInt()

        input = EditText(this).apply {
            hint = "Type, or tap 🎤"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            if (savedInstanceState == null) setText(sharedText())
        }
        val mic = Button(this).apply { text = "🎤 Dictate"; setOnClickListener { dictate() } }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Store.destinations(this).forEach { d ->
            buttons.addView(Button(this).apply {
                text = if (d.mode == "command") "▶ ${d.name}" else "→ ${d.name}"
                textSize = 17f
                setOnClickListener { send(d) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        buttons.addView(TextView(this).apply {
            text = "Add or change these in the Obsidian Widgets app."
            setPadding(0, pad / 2, 0, 0)
            alpha = 0.6f
        })

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@CaptureActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(input)
                addView(mic)
                addView(buttons)
            })
        })
        input.requestFocus()
        if (savedInstanceState == null && intent.action == ACTION_VOICE) dictate()
    }

    private fun send(d: Destination) {
        val body = input.text.toString().trim()
        if (body.isEmpty() && d.mode != "command") {
            Toast.makeText(this, "Type or dictate something first", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Store.captureUri(d, body)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Obsidian is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    /** Subject + text from a share intent, if any. */
    private fun sharedText(): String {
        if (intent.action != Intent.ACTION_SEND) return ""
        return listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
            .filter { it.isNotBlank() }.distinct().joinToString("\n")
    }

    private fun dictate() {
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No speech recognizer on this device", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK) return
        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        val at = input.selectionStart.coerceAtLeast(0)
        val before = input.text.substring(0, at)
        val insert = if (before.isEmpty() || before.endsWith(" ") || before.endsWith("\n")) spoken else " $spoken"
        input.text.insert(at, insert)
    }

    companion object {
        const val ACTION_VOICE = "com.phonesync.widgets.VOICE"
        private const val REQ_SPEECH = 1
    }
}
