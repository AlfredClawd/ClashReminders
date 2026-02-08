package com.clashreminders.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashreminders.model.EventSnapshotResponse
import com.clashreminders.ui.MainViewModel

// Color coding for urgency
private val UrgentRed = Color(0xFFE53935)
private val WarningOrange = Color(0xFFFF9800)
private val SafeGreen = Color(0xFF4CAF50)
private val DoneColor = Color(0xFF757575)

private fun urgencyColor(remainingSeconds: Int, attacksRemaining: Int): Color {
    if (attacksRemaining <= 0) return DoneColor
    return when {
        remainingSeconds < 3600 -> UrgentRed          // < 1h
        remainingSeconds < 14400 -> WarningOrange      // < 4h
        else -> SafeGreen
    }
}

private fun eventEmoji(eventType: String): String = when (eventType) {
    "cw" -> "⚔️"
    "cwl" -> "🏆"
    "raid" -> "🏰"
    else -> "📋"
}

private fun eventLabel(eventType: String, subtype: String?): String {
    val base = when (eventType) {
        "cw" -> "Clan War"
        "cwl" -> "CWL"
        "raid" -> "Raid Weekend"
        else -> eventType.uppercase()
    }
    if (subtype != null) {
        val day = subtype.replace("day_", "")
        return "$base Tag $day"
    }
    return base
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val statusResponse by viewModel.statusResponse.collectAsState()
    val statusLoading by viewModel.statusLoading.collectAsState()
    val statusError by viewModel.statusError.collectAsState()

    val events = statusResponse?.events ?: emptyList()
    val eventsWithMissing = events.filter { it.attacks_remaining > 0 }
    val eventsComplete = events.filter { it.attacks_remaining <= 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Missing Hits") },
                actions = {
                    TextButton(onClick = { viewModel.refreshStatus() }) {
                        Text("🔄", fontSize = 20.sp)
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (statusLoading && events.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (statusError != null && events.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(statusError ?: "", color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.refreshStatus() }) {
                            Text("Erneut versuchen")
                        }
                    }
                }
            } else if (events.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Keine aktiven Events", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Füge Accounts und Clans hinzu,\num Missing Hits zu tracken.",
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
                    // Summary header
                    item {
                        SummaryHeader(eventsWithMissing.size, statusResponse?.last_polled)
                    }

                    // Missing hits section
                    if (eventsWithMissing.isNotEmpty()) {
                        item {
                            Text(
                                "Ausstehende Angriffe",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(eventsWithMissing, key = { it.id }) { event ->
                            EventCard(event)
                        }
                    }

                    // Completed section
                    if (eventsComplete.isNotEmpty()) {
                        item {
                            Text(
                                "Abgeschlossen",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }
                        items(eventsComplete, key = { it.id }) { event ->
                            EventCard(event)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryHeader(missingCount: Int, lastPolled: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (missingCount > 0) UrgentRed.copy(alpha = 0.1f)
            else SafeGreen.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "$missingCount ausstehend",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (missingCount > 0) UrgentRed else SafeGreen
                )
                if (lastPolled != null) {
                    Text(
                        "Zuletzt: ${lastPolled.substringAfter("T").take(5)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                if (missingCount > 0) "⚠️" else "✅",
                fontSize = 32.sp
            )
        }
    }
}

@Composable
private fun EventCard(event: EventSnapshotResponse) {
    val color = urgencyColor(event.time_remaining_seconds, event.attacks_remaining)
    val progress = if (event.attacks_max > 0) {
        event.attacks_used.toFloat() / event.attacks_max.toFloat()
    } else 0f
    val isDone = event.attacks_remaining <= 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDone) 1.dp else 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDone) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row: event type + time remaining
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(eventEmoji(event.event_type), fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        eventLabel(event.event_type, event.event_subtype),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (!isDone && event.time_remaining_formatted != null) {
                    Text(
                        event.time_remaining_formatted,
                        color = color,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Account info
            Text(
                "${event.account_name ?: "Unknown"} (${event.account_tag})",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            // Clan info
            Text(
                "${event.clan_name ?: "Unknown"} (${event.clan_tag})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Opponent info (for wars)
            if (event.opponent_name != null) {
                Text(
                    "vs. ${event.opponent_name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Custom progress bar
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = progress)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isDone) SafeGreen else color)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Attack count
                Text(
                    "${event.attacks_used}/${event.attacks_max}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }

            // Remaining attacks badge
            if (!isDone) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = color.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "${event.attacks_remaining} Angriff(e) übrig",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
