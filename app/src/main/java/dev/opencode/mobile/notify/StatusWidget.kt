package dev.opencode.mobile.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import dev.opencode.mobile.MainActivity
import dev.opencode.mobile.R

class StatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, StatusWidget.build(context))
    }
}

/** RemoteViews-based widget (no extra dependency). State is cached in SharedPreferences so the
 *  widget renders without a network call; the app pushes updates as sessions change. */
object StatusWidget {
    private const val PREFS = "opencode_widget"
    private const val KEY_TITLE = "title"
    private const val KEY_STATUS = "status"
    private const val KEY_SNIPPET = "snippet"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun build(context: Context): RemoteViews {
        val prefs = prefs(context)
        val views = RemoteViews(context.packageName, R.layout.widget_status)
        views.setTextViewText(R.id.widget_title, prefs.getString(KEY_TITLE, null) ?: "opencode")
        views.setTextViewText(R.id.widget_status, prefs.getString(KEY_STATUS, null) ?: "idle")
        views.setTextViewText(R.id.widget_snippet, prefs.getString(KEY_SNIPPET, null) ?: "No active session")
        val busy = prefs.getString(KEY_STATUS, null) == "busy"
        views.setInt(R.id.widget_dot, "setBackgroundColor", if (busy) Color.parseColor("#D29922") else Color.parseColor("#3FB950"))
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pending)
        return views
    }

    fun update(context: Context, title: String, status: String, snippet: String) {
        prefs(context).edit()
            .putString(KEY_TITLE, title.ifBlank { "opencode" })
            .putString(KEY_STATUS, status)
            .putString(KEY_SNIPPET, snippet)
            .apply()
        refresh(context)
    }

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, StatusWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val views = build(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }
}
