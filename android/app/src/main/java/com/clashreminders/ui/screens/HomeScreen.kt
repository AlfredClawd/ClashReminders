package com.clashreminders.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clashreminders.ui.AccountUiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    accounts: List<AccountUiModel>,
    loading: Boolean,
    onRefresh: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ClashReminders") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onRefresh) {
                Text("+") // TODO: Icon
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (loading && accounts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(accounts) { accountModel ->
                        AccountCard(accountModel)
                    }
                }
            }
        }
    }
}

@Composable
fun AccountCard(model: AccountUiModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = model.account.name ?: model.account.tag,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Clan: ${model.account.clan_tag ?: "N/A"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            
            val warStatus = model.warStatus
            if (warStatus != null) {
                if (warStatus.active_war) {
                    Text("War Status: Active", color = MaterialTheme.colorScheme.primary)
                    Text("Attacks Left: ${warStatus.attacks_left}")
                    if (warStatus.end_time != null) {
                        Text("Ends in: ${warStatus.end_time}")
                    }
                    if (warStatus.opponent != null) {
                        Text("Opponent: ${warStatus.opponent}")
                    }
                } else {
                    Text("War Status: ${warStatus.status}", color = Color.Gray)
                }
                if (warStatus.message != null) {
                    Text(warStatus.message, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("Status unknown", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
