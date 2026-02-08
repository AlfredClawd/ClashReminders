package com.clashreminders.widget

import android.content.Context
import android.content.SharedPreferences
import com.clashreminders.model.StatusSummaryItem
import com.clashreminders.model.StatusSummaryResponse
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists widget data to SharedPreferences for access by the RemoteViewsService.
 */
object WidgetDataStore {

    private const val PREFS_NAME = "widget_data"
    private const val KEY_ITEMS = "items_json"
    private const val KEY_TOTAL = "total_missing"
    private const val KEY_LAST_UPDATED = "last_updated"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()

    fun save(context: Context, summary: StatusSummaryResponse) {
        val json = gson.toJson(summary.items)
        prefs(context).edit()
            .putString(KEY_ITEMS, json)
            .putInt(KEY_TOTAL, summary.total_missing)
            .putString(KEY_LAST_UPDATED, summary.last_polled ?: "")
            .apply()
    }

    fun getItems(context: Context): List<StatusSummaryItem> {
        val json = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<StatusSummaryItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getTotalMissing(context: Context): Int = prefs(context).getInt(KEY_TOTAL, 0)

    fun getLastUpdated(context: Context): String =
        prefs(context).getString(KEY_LAST_UPDATED, "") ?: ""
}
