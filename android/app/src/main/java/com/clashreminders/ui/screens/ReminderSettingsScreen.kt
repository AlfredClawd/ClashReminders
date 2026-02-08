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
import com.clashreminders.model.ReminderConfigResponse
import com.clashreminders.model.ReminderTimeResponse
import com.clashreminders.ui.ReminderViewModel

private fun eventTypeLabel(type: String): String = when (type) {
    "cw" -> "⚔️ Clan War"
    "cwl" -> "🏆 CWL"
    "raid" -> "🏰 Raid Weekend"
    else -> type.uppercase()
}

private fun formatMinutes(minutes: Int): String {
    if (minutes >= 1440) {
        val d = minutes / 1440
        return "${d}d"
    }
    if (minutes >= 60) {
        val h = minutes / 60
        val m = minutes % 60
        return if (m > 0) "${h}h ${m}m" else "${h}h"
    }
    return "${minutes}m"
}

// Common presets in minutes
private val timePresets = listOf(
    15 to "15 Minuten",
    30 to "30 Minuten",
    60 to "1 Stunde",
    120 to "2 Stunden",
    240 to "4 Stunden",
    480 to "8 Stunden",
    720 to "12 Stunden",
    1440 to "1 Tag",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettingsScreen(viewModel: ReminderViewModel) {
    val reminders by viewModel.reminders.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Erinnerungen") })
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (loading && reminders.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
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
                            "Konfiguriere, wann du vor Eventende benachrichtigt werden möchtest.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    items(reminders, key = { it.id }) { config ->
                        ReminderConfigCard(
                            config = config,
                            onToggle = { enabled -> viewModel.toggleReminder(config.event_type, enabled) },
                            onAddTime = { minutes -> viewModel.addReminderTime(config.event_type, minutes) },
                            onDeleteTime = { timeId -> viewModel.deleteReminderTime(config.event_type, timeId) }
                        )
                    }

                    // Add missing event types if not present
                    val existingTypes = reminders.map { it.event_type }.toSet()
                    val missingTypes = listOf("cw", "cwl", "raid").filter { it !in existingTypes }
                    if (missingTypes.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Fehlende Kategorien werden beim nächsten Polling automatisch erstellt.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderConfigCard(
    config: ReminderConfigResponse,
    onToggle: (Boolean) -> Unit,
    onAddTime: (Int) -> Unit,
    onDeleteTime: (String) -> Unit
) {
    var showAddSheet by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    eventTypeLabel(config.event_type),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { onToggle(it) }
                )
            }

            if (config.enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Reminder times
                if (config.times.isEmpty()) {
                    Text(
                        "Keine Erinnerungszeiten konfiguriert",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    config.times.forEach { time ->
                        ReminderTimeRow(
                            time = time,
                            onDelete = { onDeleteTime(time.id) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Add time button
                OutlinedButton(
                    onClick = { showAddSheet = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Zeit hinzufügen")
                }
            }
        }
    }

    // Add Time Dialog
    if (showAddSheet) {
        AddReminderTimeDialog(
            eventType = config.event_type,
            existingMinutes = config.times.map { it.minutes_before_end }.toSet(),
            onDismiss = { showAddSheet = false },
            onConfirm = { minutes ->
                onAddTime(minutes)
                showAddSheet = false
            }
        )
    }
}

@Composable
private fun ReminderTimeRow(
    time: ReminderTimeResponse,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔔", modifier = Modifier.padding(end = 8.dp))
            Column {
                Text(
                    time.label ?: formatMinutes(time.minutes_before_end),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${time.minutes_before_end} Minuten vor Ende",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Entfernen",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun AddReminderTimeDialog(
    eventType: String,
    existingMinutes: Set<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val availablePresets = timePresets.filter { it.first !in existingMinutes }
    var customMinutes by remember { mutableStateOf("") }
    var useCustom by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Erinnerungszeit hinzufügen") },
        text = {
            Column {
                Text("Wähle einen vordefinierten Wert:", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))

                availablePresets.forEach { (minutes, label) ->
                    TextButton(
                        onClick = { onConfirm(minutes) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }

                if (availablePresets.isEmpty()) {
                    Text(
                        "Alle Standard-Zeiten bereits konfiguriert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Text("Oder eigenen Wert (Minuten):", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.filter { c -> c.isDigit() } },
                        label = { Text("Minuten") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val mins = customMinutes.toIntOrNull()
                            if (mins != null && mins > 0) {
                                onConfirm(mins)
                            }
                        },
                        enabled = customMinutes.isNotBlank() && (customMinutes.toIntOrNull() ?: 0) > 0
                    ) {
                        Text("OK")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}
