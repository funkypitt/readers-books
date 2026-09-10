package com.freedomfighter.readersbooks.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.freedomfighter.readersbooks.App
import com.freedomfighter.readersbooks.R

/** A standard home-screen widget for any launcher: the book being read; tap carries on at its page. */
object BookWidgets {
    private const val PKG = "com.freedomfighter.readersbooks"

    fun render(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_line)
        WidgetUi.paint(views, context, intArrayOf(R.id.widget_title), intArrayOf(R.id.widget_sub))
        views.setViewVisibility(R.id.widget_plus, View.GONE)
        val book = (context.applicationContext as App).library.books.value.maxByOrNull { maxOf(it.opened, it.added) }
        if (book == null) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.open_book).removePrefix("+ "))
            views.setTextViewText(R.id.widget_sub, context.getString(R.string.shelf))
            views.setOnClickPendingIntent(R.id.widget_root, WidgetUi.activity(context, Intent(Intent.ACTION_MAIN).setClassName(PKG, "$PKG.MainActivity"), 1))
        } else {
            views.setTextViewText(R.id.widget_title, book.title)
            views.setTextViewText(R.id.widget_sub, context.getString(R.string.widget_caption) + (if (book.opened > 0L) " · ${book.progress}%" else ""))
            views.setOnClickPendingIntent(R.id.widget_root, WidgetUi.activity(context, Intent(Intent.ACTION_VIEW, Uri.parse("content://$PKG/books/${book.id}")).setClassName(PKG, "$PKG.MainActivity"), 1))
        }
        mgr.updateAppWidget(id, views)
    }

    fun refresh(context: Context) = WidgetUi.refresh(context, LineWidget::class.java)
}

class LineWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { BookWidgets.render(context, mgr, it) } }
}
