package com.clashreminders.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clashreminders.model.TrackedClanResponse
import com.clashreminders.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClanManagementScreen(viewModel: MainViewModel) {
    val clans by viewModel.clans.collectAsState()
    val loading by viewModel.clansLoading.collectAsState()
    val error by viewModel.clanError.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<TrackedClanResponse?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Clans verwalten") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Clan hinzufügen")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (loading && clans.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (clans.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Keine Clans getrackt", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tippe auf + um einen Clan zum Tracking hinzuzufügen.\n" +
                            "Nur Accounts in getrackten Clans werden überwacht.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (error != null) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Text(
                                    error ?: "",
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            "${clans.size} Clan(s) getrackt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    items(clans, key = { it.clan_tag }) { clan ->
                        ClanListItem(
                            clan = clan,
                            onDelete = { showDeleteDialog = clan }
                        )
                    }
                }
            }
        }
    }

    // Add Clan Dialog
    if (showAddDialog) {
        AddClanDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { tag ->
                viewModel.addClan(tag)
                showAddDialog = false
            }
        )
    }

    // Delete Confirmation
    showDeleteDialog?.let { clan ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Clan entfernen?") },
            text = {
                Text(
                    "Möchtest du ${clan.clan_name ?: clan.clan_tag} wirklich entfernen? " +
                    "Event-Daten für diesen Clan werden gelöscht."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteClan(clan.clan_tag)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Entfernen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

@Composable
private fun ClanListItem(
    clan: TrackedClanResponse,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    clan.clan_name ?: "Unbekannt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    clan.clan_tag,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Entfernen",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddClanDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var tagInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clan hinzufügen") },
        text = {
            Column {
                Text(
                    "Gib den Clan-Tag ein (z.B. #2PP)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    label = { Text("Clan-Tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(tagInput) },
                enabled = tagInput.isNotBlank()
            ) {
                Text("Hinzufügen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}
