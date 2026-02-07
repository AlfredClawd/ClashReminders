package com.clashreminders.model

data class UserRegister(
    val fcm_token: String? = null
)

data class UserResponse(
    val id: String,
    val created_at: String,
    val fcm_token: String?
)

data class PlayerAccountCreate(
    val tag: String
)

data class PlayerAccountResponse(
    val tag: String,
    val name: String?,
    val clan_tag: String?,
    val game_type: String,
    val user_id: String
)

data class WarCheckResponse(
    val tag: String,
    val status: String,
    val active_war: Boolean,
    val attacks_left: Int,
    val end_time: String?,
    val opponent: String?,
    val message: String?
)
