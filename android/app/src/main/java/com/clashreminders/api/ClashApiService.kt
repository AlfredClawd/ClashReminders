package com.clashreminders.api

import com.clashreminders.model.*
import retrofit2.Response
import retrofit2.http.*

interface ClashApiService {

    // ====== Users ======
    @POST("api/v1/users/register")
    suspend fun registerUser(@Body user: UserRegister): Response<UserResponse>

    @PUT("api/v1/users/{user_id}/fcm")
    suspend fun updateFcmToken(
        @Path("user_id") userId: String,
        @Body data: FcmTokenUpdate
    ): Response<MessageResponse>

    // ====== Accounts ======
    @POST("api/v1/users/{user_id}/accounts")
    suspend fun addAccount(
        @Path("user_id") userId: String,
        @Body account: PlayerAccountCreate
    ): Response<PlayerAccountResponse>

    @GET("api/v1/users/{user_id}/accounts")
    suspend fun getAccounts(@Path("user_id") userId: String): Response<List<PlayerAccountResponse>>

    @DELETE("api/v1/users/{user_id}/accounts/{tag}")
    suspend fun deleteAccount(
        @Path("user_id") userId: String,
        @Path("tag") tag: String
    ): Response<MessageResponse>

    // ====== Clans ======
    @GET("api/v1/users/{user_id}/clans")
    suspend fun getClans(@Path("user_id") userId: String): Response<List<TrackedClanResponse>>

    @POST("api/v1/users/{user_id}/clans")
    suspend fun addClan(
        @Path("user_id") userId: String,
        @Body data: ClanCreate
    ): Response<TrackedClanResponse>

    @DELETE("api/v1/users/{user_id}/clans/{clan_tag}")
    suspend fun deleteClan(
        @Path("user_id") userId: String,
        @Path("clan_tag") clanTag: String
    ): Response<MessageResponse>

    // ====== Reminders ======
    @GET("api/v1/users/{user_id}/reminders")
    suspend fun getReminders(@Path("user_id") userId: String): Response<RemindersResponse>

    @PUT("api/v1/users/{user_id}/reminders")
    suspend fun updateReminders(
        @Path("user_id") userId: String,
        @Body data: RemindersUpdateRequest
    ): Response<RemindersResponse>

    @PATCH("api/v1/users/{user_id}/reminders/{event_type}")
    suspend fun toggleReminder(
        @Path("user_id") userId: String,
        @Path("event_type") eventType: String,
        @Body data: ReminderToggle
    ): Response<MessageResponse>

    @POST("api/v1/users/{user_id}/reminders/{event_type}/times")
    suspend fun addReminderTime(
        @Path("user_id") userId: String,
        @Path("event_type") eventType: String,
        @Body data: ReminderTimeCreate
    ): Response<ReminderTimeResponse>

    @DELETE("api/v1/users/{user_id}/reminders/{event_type}/times/{time_id}")
    suspend fun deleteReminderTime(
        @Path("user_id") userId: String,
        @Path("event_type") eventType: String,
        @Path("time_id") timeId: String
    ): Response<MessageResponse>

    // ====== Status (Missing Hits) ======
    @GET("api/v1/users/{user_id}/status")
    suspend fun getStatus(@Path("user_id") userId: String): Response<StatusResponse>

    @GET("api/v1/users/{user_id}/status/summary")
    suspend fun getStatusSummary(@Path("user_id") userId: String): Response<StatusSummaryResponse>
}
