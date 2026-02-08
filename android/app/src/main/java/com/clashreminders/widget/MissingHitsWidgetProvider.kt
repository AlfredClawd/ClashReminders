package com.clashreminders.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.work.*
import com.clashreminders.R
import java.util.concurrent.TimeUnit

class MissingHitsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
        // Schedule periodic updates via WorkManager
        scheduleWidgetUpdate(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleWidgetUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val ACTION_REFRESH = "com.clashreminders.widget.ACTION_REFRESH"
        private const val WORK_NAME = "widget_update_work"

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val totalMissing = WidgetDataStore.getTotalMissing(context)
            val lastUpdated = WidgetDataStore.getLastUpdated(context)
            val items = WidgetDataStore.getItems(context)

            val views = RemoteViews(context.packageName, R.layout.widget_missing_hits)

            // Set total count
            views.setTextViewText(R.id.widget_total_count, totalMissing.toString())

            // Last updated time
            if (lastUpdated.isNotBlank()) {
                val timeOnly = lastUpdated.substringAfter("T").take(5)
                views.setTextViewText(R.id.widget_last_updated, "Aktualisiert: $timeOnly")
            }

            // Set up ListView with RemoteViewsService
            val intent = Intent(context, WidgetRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list_view, intent)

            // Empty view
            if (items.isEmpty()) {
                views.setViewVisibility(R.id.widget_list_view, View.GONE)
                views.setViewVisibility(R.id.widget_empty_view, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_list_view, View.VISIBLE)
                views.setViewVisibility(R.id.widget_empty_view, View.GONE)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list_view)
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, MissingHitsWidgetProvider::class.java)
            )
            for (id in widgetIds) {
                updateWidget(context, appWidgetManager, id)
            }
        }

        fun scheduleWidgetUpdate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                15, TimeUnit.MINUTES  // minimum for periodic
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            // Trigger one-time update
            val workRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
