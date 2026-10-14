package com.example.rtsptoyoutubertmp

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Query

interface YouTubeApiService {
    @GET("channels")
    suspend fun getMyChannel(
        @Header("Authorization") authHeader: String,
        @Query("part") part: String = "snippet,contentDetails,statistics"
    ): YouTubeResponse

    @GET("liveBroadcasts")
    suspend fun listLiveBroadcasts(
        @Header("Authorization") authHeader: String,
        @Query("part") part: String = "snippet,status",
        @Query("mine") mine: Boolean = true
    ): YouTubeResponse

    @PUT("liveBroadcasts?part=status")
    suspend fun updateBroadcastStatus(
        @Header("Authorization") authHeader: String,
        @Body statusUpdate: BroadcastStatusUpdate
    ): YouTubeResponse
}
