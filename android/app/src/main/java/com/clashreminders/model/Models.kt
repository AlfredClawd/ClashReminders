package com.clashreminders.model

// ============ USER ============

data class UserRegister(
    val fcm_token: String? = null
)

data class UserResponse(
    val id: String,
    val created_at: String,
    val notification_enabled: Boolean = true,
    val fcm_token: String?
)

data class FcmTokenUpdate(
    val fcm_token: String
)

// ============ ACCOUNTS ============

data class PlayerAccountCreate(
    val tag: String
)

data class PlayerAccountResponse(
    val id: String,
    val tag: String,
    val name: String?,
    val user_id: String,
    val current_clan_tag: String?,
    val current_clan_name: String?,
    val last_synced_at: String?,
    val display_name: String?
)

// ============ CLANS ============

data class ClanCreate(
    val clan_tag: String
)

data class TrackedClanResponse(
    val id: String,
    val clan_tag: String,
    val clan_name: String?,
    val user_id: String,
    val created_at: String?
)

// ============ REMINDERS ============

data class ReminderTimeCreate(
    val minutes_before_end: Int,
    val label: String? = null
)

data class ReminderTimeResponse(
    val id: String,
    val minutes_before_end: Int,
    val label: String?,
    val enabled: Boolean
)

data class ReminderConfigResponse(
    val id: String,
    val event_type: String,
    val enabled: Boolean,
    val times: List<ReminderTimeResponse>
)

data class ReminderConfigUpdate(
    val event_type: String,
    val enabled: Boolean = true,
    val times: List<ReminderTimeCreate> = emptyList()
)

data class RemindersUpdateRequest(
    val reminders: List<ReminderConfigUpdate>
)

data class RemindersResponse(
    val reminders: List<ReminderConfigResponse>
)

data class ReminderToggle(
    val enabled: Boolean
)

// ============ EVENT STATUS (MISSING HITS) ============

data class EventSnapshotResponse(
    val id: String,
    val account_tag: String,
    val account_name: String?,
    val clan_tag: String,
    val clan_name: String?,
    val event_type: String,
    val event_subtype: String?,
    val state: String?,
    val attacks_used: Int,
    val attacks_max: Int,
    val attacks_remaining: Int,
    val end_time: String?,
    val start_time: String?,
    val time_remaining_seconds: Int,
    val time_remaining_formatted: String?,
    val opponent_name: String?,
    val opponent_tag: String?,
    val war_size: Int?
)

data class StatusResponse(
    val last_polled: String?,
    val events: List<EventSnapshotResponse>
)

data class StatusSummaryItem(
    val account_display: String,
    val clan_display: String,
    val event_label: String,
    val attacks_remaining: Int,
    val end_time_formatted: String?,
    val end_time_iso: String?
)

data class StatusSummaryResponse(
    val last_polled: String?,
    val total_missing: Int,
    val by_event_type: Map<String, EventTypeCount>,
    val items: List<StatusSummaryItem>
)

data class EventTypeCount(
    val count: Int,
    val accounts: Int
)

// ============ GENERIC ============

data class MessageResponse(
    val message: String
)
