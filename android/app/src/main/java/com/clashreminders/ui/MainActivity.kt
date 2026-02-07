package com.clashreminders.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.clashreminders.ui.screens.HomeScreen
import com.clashreminders.ui.screens.OnboardingScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ClashRemindersApp(viewModel)
                }
            }
        }
    }
}

@Composable
fun ClashRemindersApp(viewModel: MainViewModel) {
    val isOnboarded by viewModel.isOnboarded.collectAsState()
    
    if (isOnboarded) {
        val accounts by viewModel.accounts.collectAsState()
        val loading by viewModel.homeLoading.collectAsState()
        
        HomeScreen(
            accounts = accounts,
            loading = loading,
            onRefresh = { viewModel.refreshAccounts() }
        )
    } else {
        val tagInput by viewModel.tagInput.collectAsState()
        val loading by viewModel.onboardingLoading.collectAsState()
        val error by viewModel.onboardingError.collectAsState()
        
        OnboardingScreen(
            tagInput = tagInput,
            onTagChange = { viewModel.onTagChange(it) },
            onRegister = { viewModel.register() },
            loading = loading,
            error = error
        )
    }
}
