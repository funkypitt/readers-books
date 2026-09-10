package com.freedomfighter.readersbooks.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import com.freedomfighter.readersbooks.R

/** Shared by the standard widgets: the app's two colours, the launch intents, the refresh. */
object WidgetUi {
    /** (background, foreground, dim) following the app's theme setting. */
    fun colors(context: Context): Triple<Int, Int, Int> {
        val theme = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("theme", "DARK")
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (theme) { "LIGHT" -> false; "SYSTEM" -> systemDark; else -> true }
        val bg = if (dark) Color.BLACK else Color.WHITE
        val fg = if (dark) Color.WHITE else Color.BLACK
        val dim = if (dark) Color.argb(140, 255, 255, 255) else Color.argb(140, 0, 0, 0)
        return Triple(bg, fg, dim)
    }

    fun paint(views: RemoteViews, context: Context, ids: IntArray, dimIds: IntArray = intArrayOf()) {
        val (bg, fg, dim) = colors(context)
        views.setInt(R.id.widget_root, "setBackgroundColor", bg)
        ids.forEach { views.setTextColor(it, fg) }
        dimIds.forEach { views.setTextColor(it, dim) }
    }

    fun activity(context: Context, intent: Intent, code: Int = 0): PendingIntent =
        PendingIntent.getActivity(context, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun broadcast(context: Context, intent: Intent, code: Int = 0, mutable: Boolean = false): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0) else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(context, code, intent, flags)
    }

    /** Ask every widget of the given providers to redraw (after a change in the app). */
    fun refresh(context: Context, vararg providers: Class<*>) {
        val mgr = AppWidgetManager.getInstance(context)
        for (p in providers) {
            val ids = mgr.getAppWidgetIds(ComponentName(context, p))
            if (ids.isEmpty()) continue
            context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(ComponentName(context, p)).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }
    }
}
