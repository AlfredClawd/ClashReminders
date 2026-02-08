package com.clashreminders.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.clashreminders.R
import com.clashreminders.model.StatusSummaryItem

class WidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return WidgetRemoteViewsFactory(applicationContext)
    }
}

class WidgetRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<StatusSummaryItem> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        items = WidgetDataStore.getItems(context)
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_list_item)

        if (position < items.size) {
            val item = items[position]

            views.setTextViewText(R.id.item_event_label, item.event_label)
            views.setTextViewText(R.id.item_account_display, item.account_display)
            views.setTextViewText(
                R.id.item_time_remaining,
                item.end_time_formatted ?: ""
            )
            views.setTextViewText(
                R.id.item_attacks_remaining,
                "${item.attacks_remaining} Angriff(e) übrig"
            )

            // Color based on urgency — simplified for RemoteViews
            // (cannot easily parse ISO time in RemoteViews, use server-provided formatted string)
        }

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
