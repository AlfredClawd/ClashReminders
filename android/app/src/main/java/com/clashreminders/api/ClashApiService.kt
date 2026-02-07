package com.clashreminders.api

import com.clashreminders.model.PlayerAccountCreate
import com.clashreminders.model.PlayerAccountResponse
import com.clashreminders.model.UserRegister
import com.clashreminders.model.UserResponse
import com.clashreminders.model.WarCheckResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.GET

interface ClashApiService {
    @POST("api/v1/users/register")
    suspend fun registerUser(@Body user: UserRegister): Response<UserResponse>

    @POST("api/v1/users/{user_id}/accounts")
    suspend fun addAccount(
        @Path("user_id") userId: String,
        @Body account: PlayerAccountCreate
    ): Response<PlayerAccountResponse>

    @GET("api/v1/users/{user_id}/accounts")
    suspend fun getAccounts(@Path("user_id") userId: String): Response<List<PlayerAccountResponse>>

    @GET("api/v1/accounts/{tag}/war_status")
    suspend fun getWarStatus(@Path("tag") tag: String): Response<WarCheckResponse>
}
