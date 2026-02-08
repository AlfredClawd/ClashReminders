package com.clashreminders.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.clashreminders.api.RetrofitClient
import com.clashreminders.data.UserRepository

/**
 * WorkManager worker that fetches status summary from backend and updates widget data.
 */
class WidgetUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val repository = UserRepository(applicationContext, RetrofitClient.apiService)

            if (!repository.isLoggedIn()) {
                return Result.success()
            }

            val summary = repository.getStatusSummary()
            if (summary != null) {
                WidgetDataStore.save(applicationContext, summary)
                MissingHitsWidgetProvider.updateAllWidgets(applicationContext)
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
