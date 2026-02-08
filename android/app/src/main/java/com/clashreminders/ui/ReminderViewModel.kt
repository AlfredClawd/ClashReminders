package com.clashreminders.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clashreminders.api.RetrofitClient
import com.clashreminders.data.UserRepository
import com.clashreminders.model.ReminderConfigResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReminderViewModel(application: Application) : AndroidViewModel(application) {
    private val apiService = RetrofitClient.apiService
    private val repository = UserRepository(application.applicationContext, apiService)

    private val _reminders = MutableStateFlow<List<ReminderConfigResponse>>(emptyList())
    val reminders: StateFlow<List<ReminderConfigResponse>> = _reminders.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadReminders()
    }

    fun loadReminders() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val response = repository.getReminders()
                if (response != null) {
                    _reminders.value = response.reminders
                } else {
                    _error.value = "Erinnerungen konnten nicht geladen werden"
                }
            } catch (e: Exception) {
                _error.value = "Fehler: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun toggleReminder(eventType: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                val success = repository.toggleReminder(eventType, enabled)
                if (success) loadReminders()
            } catch (e: Exception) {
                _error.value = "Fehler: ${e.message}"
            }
        }
    }

    fun addReminderTime(eventType: String, minutesBefore: Int) {
        viewModelScope.launch {
            try {
                val result = repository.addReminderTime(eventType, minutesBefore)
                if (result != null) loadReminders()
                else _error.value = "Zeit konnte nicht hinzugefügt werden"
            } catch (e: Exception) {
                _error.value = "Fehler: ${e.message}"
            }
        }
    }

    fun deleteReminderTime(eventType: String, timeId: String) {
        viewModelScope.launch {
            try {
                val success = repository.deleteReminderTime(eventType, timeId)
                if (success) loadReminders()
            } catch (e: Exception) {
                _error.value = "Fehler: ${e.message}"
            }
        }
    }
}
