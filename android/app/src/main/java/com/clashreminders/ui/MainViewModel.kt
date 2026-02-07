package com.clashreminders.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clashreminders.api.RetrofitClient
import com.clashreminders.data.UserRepository
import com.clashreminders.model.PlayerAccountResponse
import com.clashreminders.model.WarCheckResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AccountUiModel(
    val account: PlayerAccountResponse,
    val warStatus: WarCheckResponse? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val apiService = RetrofitClient.apiService
    private val repository = UserRepository(application.applicationContext, apiService)

    // Onboarding State
    private val _tagInput = MutableStateFlow("")
    val tagInput: StateFlow<String> = _tagInput.asStateFlow()

    private val _onboardingLoading = MutableStateFlow(false)
    val onboardingLoading: StateFlow<Boolean> = _onboardingLoading.asStateFlow()

    private val _onboardingError = MutableStateFlow<String?>(null)
    val onboardingError: StateFlow<String?> = _onboardingError.asStateFlow()

    private val _isOnboarded = MutableStateFlow(repository.isLoggedIn())
    val isOnboarded: StateFlow<Boolean> = _isOnboarded.asStateFlow()

    // Home State
    private val _accounts = MutableStateFlow<List<AccountUiModel>>(emptyList())
    val accounts: StateFlow<List<AccountUiModel>> = _accounts.asStateFlow()

    private val _homeLoading = MutableStateFlow(false)
    val homeLoading: StateFlow<Boolean> = _homeLoading.asStateFlow()

    init {
        if (isOnboarded.value) {
            refreshAccounts()
        }
    }

    fun onTagChange(newTag: String) {
        _tagInput.value = newTag
    }

    fun register() {
        if (_tagInput.value.isBlank()) {
            _onboardingError.value = "Please enter a valid tag"
            return
        }

        viewModelScope.launch {
            _onboardingLoading.value = true
            _onboardingError.value = null

            try {
                // Step 1: Register User
                val user = repository.registerUser()
                if (user != null) {
                    // Step 2: Add Account
                    val account = repository.addAccount(_tagInput.value)
                    if (account != null) {
                        _isOnboarded.value = true
                        refreshAccounts()
                    } else {
                        _onboardingError.value = "Failed to add account. Please check the tag."
                    }
                } else {
                    _onboardingError.value = "Registration failed. Please try again."
                }
            } catch (e: Exception) {
                _onboardingError.value = "Error: ${e.message}"
            } finally {
                _onboardingLoading.value = false
            }
        }
    }

    fun refreshAccounts() {
        viewModelScope.launch {
            _homeLoading.value = true
            try {
                val fetchedAccounts = repository.getAccounts()
                val uiModels = fetchedAccounts.map { account ->
                    val warStatus = repository.getWarStatus(account.tag)
                    AccountUiModel(account, warStatus)
                }
                _accounts.value = uiModels
            } catch (e: Exception) {
                // Handle error
            } finally {
                _homeLoading.value = false
            }
        }
    }
}
