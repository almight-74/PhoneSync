package com.phonesync.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.RemoteViews
import java.text.DateFormat

class NoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(c, mgr, it) }
    }

    override fun onDeleted(c: Context, ids: IntArray) {
        ids.forEach { Store.forget(c, it) }
    }

    companion object {
        fun refreshAll(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            mgr.getAppWidgetIds(ComponentName(c, NoteWidgetProvider::class.java)).forEach { update(c, mgr, it) }
        }

        fun update(c: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(c.packageName, R.layout.widget_note)
            val key = Store.noteKey(c, id)
            val snap = key?.let { Store.snapshot(c, it) }

            v.setTextViewText(R.id.title, snap?.optString("title") ?: key ?: "Tap to pick a note")
            v.setTextViewText(R.id.updated, snap?.optLong("updated")?.let {
                DateUtils.formatSameDayTime(it, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT)
            } ?: "")

            val titleTarget = snap?.optString("open")
            v.setOnClickPendingIntent(R.id.title,
                if (titleTarget != null) tap(c, id * 10, titleTarget)
                else PendingIntent.getActivity(c, id * 10, ConfigActivity.intentFor(c, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            v.setOnClickPendingIntent(R.id.refresh, tap(c, id * 10 + 1, Store.bridge("push", "back" to "1")))

            val svc = Intent(c, NoteWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)) // one factory per widget
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.list, svc)
            v.setEmptyView(R.id.list, R.id.empty)
            // Rows fill in EXTRA_URI; the template must be mutable for that, so it targets our own activity.
            v.setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(c, id * 10 + 2,
                Intent(c, BridgeActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))

            mgr.updateAppWidget(id, v)
            mgr.notifyAppWidgetViewDataChanged(id, R.id.list)
        }

        /** Immutable tap that opens [target] through [BridgeActivity]. */
        fun tap(c: Context, requestCode: Int, target: String): PendingIntent =
            PendingIntent.getActivity(c, requestCode,
                Intent(c, BridgeActivity::class.java).putExtra(BridgeActivity.EXTRA_URI, target),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
