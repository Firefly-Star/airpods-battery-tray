package com.example.airpodsbattery

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BatteryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        render(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, BatteryWidgetProvider::class.java))
            if (ids.isEmpty()) return
            render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val state = BatteryStore.load(context)

            val title = buildString {
                append(state.modelName ?: "AirPods")
                state.lastSeenAt?.let {
                    append("  ")
                    append(SimpleDateFormat("HH:mm", Locale.US).format(Date(it)))
                }
            }

            val values = if (state.hasAnyReading) {
                buildString {
                    append("L ").append(state.left?.let { "$it%" } ?: "--")
                    append("  R ").append(state.right?.let { "$it%" } ?: "--")
                    append("  Case ").append(state.case?.let { "$it%" } ?: "--")
                }
            } else {
                "暂无广播"
            }

            val views = RemoteViews(context.packageName, R.layout.battery_widget).apply {
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_values, values)
            }

            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
