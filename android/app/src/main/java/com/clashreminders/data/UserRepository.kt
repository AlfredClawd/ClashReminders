package com.clashreminders.data

import android.content.Context
import android.content.SharedPreferences
import com.clashreminders.api.ClashApiService
import com.clashreminders.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UserRepository(private val context: Context, private val apiService: ClashApiService) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("clash_reminders_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_USER_ID = "user_id"
    }

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun saveUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }

    fun isLoggedIn(): Boolean = getUserId() != null

    // ====== USER ======

    suspend fun registerUser(fcmToken: String? = null): UserResponse? = withContext(Dispatchers.IO) {
        try {
            val response = apiService.registerUser(UserRegister(fcm_token = fcmToken))
            if (response.isSuccessful) {
                val user = response.body()
                user?.let { saveUserId(it.id) }
                user
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun updateFcmToken(token: String): Boolean = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext false
        try {
            val response = apiService.updateFcmToken(userId, FcmTokenUpdate(token))
            response.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ====== ACCOUNTS ======

    suspend fun addAccount(tag: String): PlayerAccountResponse? = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext null
        try {
            val response = apiService.addAccount(userId, PlayerAccountCreate(tag = tag))
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getAccounts(): List<PlayerAccountResponse> = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext emptyList()
        try {
            val response = apiService.getAccounts(userId)
            if (response.isSuccessful) response.body() ?: emptyList() else emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun deleteAccount(tag: String): Boolean = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext false
        try {
            val response = apiService.deleteAccount(userId, tag)
            response.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ====== CLANS ======

    suspend fun getClans(): List<TrackedClanResponse> = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext emptyList()
        try {
            val response = apiService.getClans(userId)
            if (response.isSuccessful) response.body() ?: emptyList() else emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun addClan(clanTag: String): TrackedClanResponse? = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext null
        try {
            val response = apiService.addClan(userId, ClanCreate(clan_tag = clanTag))
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun deleteClan(clanTag: String): Boolean = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext false
        try {
            val response = apiService.deleteClan(userId, clanTag)
            response.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ====== REMINDERS ======

    suspend fun getReminders(): RemindersResponse? = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext null
        try {
            val response = apiService.getReminders(userId)
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun updateReminders(request: RemindersUpdateRequest): RemindersResponse? = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext null
        try {
            val response = apiService.updateReminders(userId, request)
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun toggleReminder(eventType: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext false
        try {
            val response = apiService.toggleReminder(userId, eventType, ReminderToggle(enabled))
            response.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun addReminderTime(eventType: String, minutesBefore: Int, label: String? = null): ReminderTimeResponse? =
        withContext(Dispatchers.IO) {
            val userId = getUserId() ?: return@withContext null
            try {
                val response = apiService.addReminderTime(
                    userId, eventType, ReminderTimeCreate(minutesBefore, label)
                )
                if (response.isSuccessful) response.body() else null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    suspend fun deleteReminderTime(eventType: String, timeId: String): Boolean = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext false
        try {
            val response = apiService.deleteReminderTime(userId, eventType, timeId)
            response.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ====== STATUS (MISSING HITS) ======

    suspend fun getStatus(): StatusResponse? = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext null
        try {
            val response = apiService.getStatus(userId)
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getStatusSummary(): StatusSummaryResponse? = withContext(Dispatchers.IO) {
        val userId = getUserId() ?: return@withContext null
        try {
            val response = apiService.getStatusSummary(userId)
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
