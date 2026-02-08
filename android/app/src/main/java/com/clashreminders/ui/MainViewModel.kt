package com.clashreminders.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clashreminders.api.RetrofitClient
import com.clashreminders.data.UserRepository
import com.clashreminders.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val apiService = RetrofitClient.apiService
    val repository = UserRepository(application.applicationContext, apiService)

    // ====== ONBOARDING STATE ======
    private val _tagInput = MutableStateFlow("")
    val tagInput: StateFlow<String> = _tagInput.asStateFlow()

    private val _onboardingLoading = MutableStateFlow(false)
    val onboardingLoading: StateFlow<Boolean> = _onboardingLoading.asStateFlow()

    private val _onboardingError = MutableStateFlow<String?>(null)
    val onboardingError: StateFlow<String?> = _onboardingError.asStateFlow()

    private val _isOnboarded = MutableStateFlow(repository.isLoggedIn())
    val isOnboarded: StateFlow<Boolean> = _isOnboarded.asStateFlow()

    // ====== STATUS/MISSING HITS ======
    private val _statusResponse = MutableStateFlow<StatusResponse?>(null)
    val statusResponse: StateFlow<StatusResponse?> = _statusResponse.asStateFlow()

    private val _statusLoading = MutableStateFlow(false)
    val statusLoading: StateFlow<Boolean> = _statusLoading.asStateFlow()

    private val _statusError = MutableStateFlow<String?>(null)
    val statusError: StateFlow<String?> = _statusError.asStateFlow()

    // ====== ACCOUNTS ======
    private val _accounts = MutableStateFlow<List<PlayerAccountResponse>>(emptyList())
    val accounts: StateFlow<List<PlayerAccountResponse>> = _accounts.asStateFlow()

    private val _accountsLoading = MutableStateFlow(false)
    val accountsLoading: StateFlow<Boolean> = _accountsLoading.asStateFlow()

    private val _accountError = MutableStateFlow<String?>(null)
    val accountError: StateFlow<String?> = _accountError.asStateFlow()

    // ====== CLANS ======
    private val _clans = MutableStateFlow<List<TrackedClanResponse>>(emptyList())
    val clans: StateFlow<List<TrackedClanResponse>> = _clans.asStateFlow()

    private val _clansLoading = MutableStateFlow(false)
    val clansLoading: StateFlow<Boolean> = _clansLoading.asStateFlow()

    private val _clanError = MutableStateFlow<String?>(null)
    val clanError: StateFlow<String?> = _clanError.asStateFlow()

    // ====== REMINDERS ======
    private val _reminders = MutableStateFlow<List<ReminderConfigResponse>>(emptyList())
    val reminders: StateFlow<List<ReminderConfigResponse>> = _reminders.asStateFlow()

    private val _remindersLoading = MutableStateFlow(false)
    val remindersLoading: StateFlow<Boolean> = _remindersLoading.asStateFlow()

    // ====== AUTO-REFRESH ======
    private var autoRefreshJob: Job? = null
    private val AUTO_REFRESH_INTERVAL_MS = 60_000L

    init {
        if (_isOnboarded.value) {
            startAutoRefresh()
            loadAccounts()
            loadClans()
            loadReminders()
        }
    }

    // ====== ONBOARDING ======

    fun onTagChange(newTag: String) {
        _tagInput.value = newTag
    }

    fun register() {
        if (_tagInput.value.isBlank()) {
            _onboardingError.value = "Bitte gib einen gültigen Tag ein"
            return
        }

        viewModelScope.launch {
            _onboardingLoading.value = true
            _onboardingError.value = null

            try {
                val user = repository.registerUser()
                if (user != null) {
                    val account = repository.addAccount(_tagInput.value)
                    if (account != null) {
                        _isOnboarded.value = true
                        startAutoRefresh()
                        loadAccounts()
                        loadClans()
                        loadReminders()
                    } else {
                        _onboardingError.value = "Account konnte nicht hinzugefügt werden. Tag prüfen."
                    }
                } else {
                    _onboardingError.value = "Registrierung fehlgeschlagen. Bitte erneut versuchen."
                }
            } catch (e: Exception) {
                _onboardingError.value = "Fehler: ${e.message}"
            } finally {
                _onboardingLoading.value = false
            }
        }
    }

    // ====== AUTO-REFRESH (MISSING HITS) ======

    fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                refreshStatus()
                delay(AUTO_REFRESH_INTERVAL_MS)
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _statusLoading.value = true
            _statusError.value = null
            try {
                val response = repository.getStatus()
                if (response != null) {
                    _statusResponse.value = response
                } else {
                    _statusError.value = "Status konnte nicht geladen werden"
                }
            } catch (e: Exception) {
                _statusError.value = "Fehler: ${e.message}"
            } finally {
                _statusLoading.value = false
            }
        }
    }

    // ====== ACCOUNTS ======

    fun loadAccounts() {
        viewModelScope.launch {
            _accountsLoading.value = true
            _accountError.value = null
            try {
                _accounts.value = repository.getAccounts()
            } catch (e: Exception) {
                _accountError.value = "Fehler: ${e.message}"
            } finally {
                _accountsLoading.value = false
            }
        }
    }

    fun addAccount(tag: String) {
        viewModelScope.launch {
            _accountsLoading.value = true
            _accountError.value = null
            try {
                val result = repository.addAccount(tag)
                if (result != null) {
                    loadAccounts()
                } else {
                    _accountError.value = "Account konnte nicht hinzugefügt werden"
                }
            } catch (e: Exception) {
                _accountError.value = "Fehler: ${e.message}"
            } finally {
                _accountsLoading.value = false
            }
        }
    }

    fun deleteAccount(tag: String) {
        viewModelScope.launch {
            _accountsLoading.value = true
            try {
                repository.deleteAccount(tag)
                loadAccounts()
            } catch (e: Exception) {
                _accountError.value = "Fehler: ${e.message}"
            } finally {
                _accountsLoading.value = false
            }
        }
    }

    // ====== CLANS ======

    fun loadClans() {
        viewModelScope.launch {
            _clansLoading.value = true
            _clanError.value = null
            try {
                _clans.value = repository.getClans()
            } catch (e: Exception) {
                _clanError.value = "Fehler: ${e.message}"
            } finally {
                _clansLoading.value = false
            }
        }
    }

    fun addClan(tag: String) {
        viewModelScope.launch {
            _clansLoading.value = true
            _clanError.value = null
            try {
                val result = repository.addClan(tag)
                if (result != null) {
                    loadClans()
                } else {
                    _clanError.value = "Clan konnte nicht hinzugefügt werden"
                }
            } catch (e: Exception) {
                _clanError.value = "Fehler: ${e.message}"
            } finally {
                _clansLoading.value = false
            }
        }
    }

    fun deleteClan(clanTag: String) {
        viewModelScope.launch {
            _clansLoading.value = true
            try {
                repository.deleteClan(clanTag)
                loadClans()
            } catch (e: Exception) {
                _clanError.value = "Fehler: ${e.message}"
            } finally {
                _clansLoading.value = false
            }
        }
    }

    // ====== REMINDERS ======

    fun loadReminders() {
        viewModelScope.launch {
            _remindersLoading.value = true
            try {
                val response = repository.getReminders()
                if (response != null) {
                    _reminders.value = response.reminders
                }
            } catch (e: Exception) {
                // silent
            } finally {
                _remindersLoading.value = false
            }
        }
    }

    fun toggleReminder(eventType: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.toggleReminder(eventType, enabled)
                loadReminders()
            } catch (e: Exception) {
                // silent
            }
        }
    }

    fun addReminderTime(eventType: String, minutesBefore: Int) {
        viewModelScope.launch {
            try {
                repository.addReminderTime(eventType, minutesBefore)
                loadReminders()
            } catch (e: Exception) {
                // silent
            }
        }
    }

    fun deleteReminderTime(eventType: String, timeId: String) {
        viewModelScope.launch {
            try {
                repository.deleteReminderTime(eventType, timeId)
                loadReminders()
            } catch (e: Exception) {
                // silent
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoRefresh()
    }
}
