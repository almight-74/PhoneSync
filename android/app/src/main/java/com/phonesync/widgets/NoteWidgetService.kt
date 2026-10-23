package com.phonesync.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import org.json.JSONObject

class NoteWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))

    private class Factory(private val c: Context, private val id: Int) : RemoteViewsFactory {
        private var rows = JSONArray()
        private var open: String? = null

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            val snap = Store.noteKey(c, id)?.let { Store.snapshot(c, it) }
            rows = snap?.optJSONArray("rows") ?: JSONArray()
            open = snap?.optString("open")
        }

        override fun getCount() = rows.length()
        override fun getViewTypeCount() = 3
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            val row: JSONObject = rows.optJSONObject(position) ?: JSONObject()
            val text = row.optString("s")
            if (row.optString("k") == "t") {
                val done = row.optBoolean("done")
                return RemoteViews(c.packageName, R.layout.row_task).apply {
                    setTextViewText(R.id.box, if (done) "☑" else "☐")
                    setTextViewText(R.id.text, text)
                    setTextColor(R.id.text, c.getColor(if (done) R.color.muted else R.color.text))
                    setInt(R.id.text, "setPaintFlags",
                        Paint.ANTI_ALIAS_FLAG or (if (done) Paint.STRIKE_THRU_TEXT_FLAG else 0))
                    setOnClickFillInIntent(R.id.row, Intent().putExtra(BridgeActivity.EXTRA_URI,
                        Store.bridge("toggle", "file" to row.optString("f"), "line" to row.optInt("l").toString(),
                            "match" to text, "back" to "1")))
                }
            }
            val layout = if (row.optString("k") == "h") R.layout.row_heading else R.layout.row_text
            return RemoteViews(c.packageName, layout).apply {
                setTextViewText(R.id.text, text)
                open?.let { setOnClickFillInIntent(R.id.text, Intent().putExtra(BridgeActivity.EXTRA_URI, it)) }
            }
        }
    }
}
