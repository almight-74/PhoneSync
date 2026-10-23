package com.phonesync.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

class ActionsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(c, mgr, it) }
    }

    override fun onDeleted(c: Context, ids: IntArray) {
        ids.forEach { Store.forget(c, it) }
    }

    companion object {
        private val BUTTONS = intArrayOf(R.id.a0, R.id.a1, R.id.a2, R.id.a3)

        fun update(c: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(c.packageName, R.layout.widget_actions)
            val actions = Store.actions(c, id)
            BUTTONS.forEachIndexed { i, button ->
                val (label, target) = actions.getOrNull(i) ?: ("" to "")
                v.setTextViewText(button, label)
                if (target.isNotBlank()) {
                    v.setOnClickPendingIntent(button, NoteWidgetProvider.tap(c, id * 10 + 3 + i, target))
                }
            }
            mgr.updateAppWidget(id, v)
        }
    }
}
