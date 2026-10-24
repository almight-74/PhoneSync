package com.phonesync.widgets

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast

/**
 * Capture dialog: pick a destination, type or dictate, send to Obsidian via the plugin.
 * Also the share-sheet target ("Send to Obsidian") for text from other apps.
 */
class CaptureActivity : Activity() {
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Send to Obsidian"
        val pad = (16 * resources.displayMetrics.density).toInt()
        val destinations = Store.destinations(this)

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@CaptureActivity, android.R.layout.simple_spinner_dropdown_item,
                destinations.map { it.name })
            setSelection(Store.lastDestination(this@CaptureActivity).coerceIn(0, (destinations.size - 1).coerceAtLeast(0)))
        }
        input = EditText(this).apply {
            hint = "Text"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            if (savedInstanceState == null) setText(sharedText())
        }
        val mic = Button(this).apply { this.text = "🎤"; setOnClickListener { dictate() } }
        val send = Button(this).apply {
            this.text = "Send"
            setOnClickListener {
                val d = destinations.getOrNull(spinner.selectedItemPosition) ?: return@setOnClickListener
                val body = input.text.toString().trim()
                if (body.isEmpty()) return@setOnClickListener
                Store.setLastDestination(this@CaptureActivity, spinner.selectedItemPosition)
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Store.captureUri(d, body)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@CaptureActivity, "Obsidian is not installed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(spinner)
            addView(input)
            addView(LinearLayout(this@CaptureActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(mic)
                addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        })
        input.requestFocus()
        if (savedInstanceState == null && intent.action == ACTION_VOICE) dictate()
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
