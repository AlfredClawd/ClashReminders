package com.clashreminders.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clashreminders.R
import com.clashreminders.api.RetrofitClient
import com.clashreminders.data.UserRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ClashFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "ClashFCM"
        private const val CHANNEL_ID = "clash_reminders"
        private const val CHANNEL_NAME = "Erinnerungen"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")

        // Send to backend
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = UserRepository(applicationContext, RetrofitClient.apiService)
                if (repository.isLoggedIn()) {
                    repository.updateFcmToken(token)
                    Log.d(TAG, "FCM token updated on backend")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update FCM token", e)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "Message received: ${message.data}")

        val title = message.data["title"] ?: message.notification?.title ?: "ClashReminders"
        val body = message.data["body"] ?: message.notification?.body ?: ""
        val eventType = message.data["event_type"] ?: ""

        showNotification(title, body, eventType)
    }

    private fun showNotification(title: String, body: String, eventType: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel (idempotent on Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Erinnerungen für fehlende Angriffe"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
