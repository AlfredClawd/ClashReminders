package com.clashreminders.data

import android.content.Context
import android.content.SharedPreferences
import com.clashreminders.api.ClashApiService
import com.clashreminders.model.PlayerAccountCreate
import com.clashreminders.model.PlayerAccountResponse
import com.clashreminders.model.UserRegister
import com.clashreminders.model.UserResponse
import com.clashreminders.model.WarCheckResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UserRepository(private val context: Context, private val apiService: ClashApiService) {

    private val prefs: SharedPreferences = context.getSharedPreferences("clash_reminders_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_USER_ID = "user_id"
    }

    fun getUserId(): String? {
        return prefs.getString(KEY_USER_ID, null)
    }

    fun saveUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }
    
    fun isLoggedIn(): Boolean {
        return getUserId() != null
    }

    suspend fun registerUser(): UserResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.registerUser(UserRegister(fcm_token = null)) // TODO: Add FCM token
                if (response.isSuccessful) {
                    val user = response.body()
                    user?.let { saveUserId(it.id) }
                    user
                } else {
                    null // Handle error
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun addAccount(tag: String): PlayerAccountResponse? {
        val userId = getUserId() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.addAccount(userId, PlayerAccountCreate(tag = tag))
                if (response.isSuccessful) {
                    response.body()
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
    
    suspend fun getAccounts(): List<PlayerAccountResponse> {
        val userId = getUserId() ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getAccounts(userId)
                if (response.isSuccessful) {
                    response.body() ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    suspend fun getWarStatus(tag: String): WarCheckResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getWarStatus(tag)
                if (response.isSuccessful) {
                    response.body()
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
