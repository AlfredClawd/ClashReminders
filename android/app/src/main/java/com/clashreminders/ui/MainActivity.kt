package com.clashreminders.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.clashreminders.ui.screens.*

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "Status", Icons.Default.Home)
    data object Accounts : Screen("accounts", "Accounts", Icons.Default.Person)
    data object Clans : Screen("clans", "Clans", Icons.Default.Star)
    data object Reminders : Screen("reminders", "Reminder", Icons.Default.Notifications)
}

val bottomNavItems = listOf(Screen.Home, Screen.Accounts, Screen.Clans, Screen.Reminders)

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val reminderViewModel: ReminderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ClashRemindersApp(viewModel, reminderViewModel)
                }
            }
        }
    }
}

@Composable
fun ClashRemindersApp(viewModel: MainViewModel, reminderViewModel: ReminderViewModel) {
    val isOnboarded by viewModel.isOnboarded.collectAsState()

    if (isOnboarded) {
        MainScaffold(viewModel, reminderViewModel)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(viewModel: MainViewModel, reminderViewModel: ReminderViewModel) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = { BottomNavBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(viewModel = viewModel)
            }
            composable(Screen.Accounts.route) {
                AccountManagementScreen(viewModel = viewModel)
            }
            composable(Screen.Clans.route) {
                ClanManagementScreen(viewModel = viewModel)
            }
            composable(Screen.Reminders.route) {
                ReminderSettingsScreen(viewModel = reminderViewModel)
            }
        }
    }
}

@Composable
fun BottomNavBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar {
        bottomNavItems.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = screen.label) },
                label = { Text(screen.label) },
                selected = currentRoute == screen.route,
                onClick = {
                    if (currentRoute != screen.route) {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    }
}
