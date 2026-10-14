package com.example.rtsptoyoutubertmp

import android.util.Log
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class YouTubeServiceManager(
    private val apiService: YouTubeApiService,
    private val tokenManager: TokenManager
) {
    companion object {
        private const val TAG = "YouTubeServiceManager"
        private const val MAX_RETRIES = 3
        private val RETRY_DELAY = 2000.milliseconds
    }

    suspend fun findActiveBroadcastId(): String? {
        return try {
            val token = tokenManager.getAccessToken() ?: return null

            val response = apiService.listLiveBroadcasts("Bearer $token")
            val activeBroadcast = response.items.firstOrNull { 
                it.status?.complete == false 
            }
            activeBroadcast?.id
        } catch (e: Exception) {
            Log.e(TAG, "Error finding active broadcast", e)
            null
        }
    }

    suspend fun completeBroadcast(broadcastId: String): Boolean {
        var retryCount = 0
        var success = false

        while (retryCount < MAX_RETRIES && !success) {
            try {
                val token = tokenManager.getAccessToken() ?: return false

                val update = BroadcastStatusUpdate(
                    id = broadcastId,
                    status = BroadcastStatus(complete = true)
                )
                
                apiService.updateBroadcastStatus("Bearer $token", update)
                success = true
                Log.d(TAG, "Successfully completed broadcast $broadcastId")
            } catch (e: Exception) {
                retryCount++
                Log.e(TAG, "Error completing broadcast $broadcastId (Attempt $retryCount/$MAX_RETRIES): ${e.message}")
                if (retryCount < MAX_RETRIES) {
                    delay(RETRY_DELAY * retryCount)
                } else {
                    Log.e(TAG, "Max retries reached for completing broadcast $broadcastId")
                }
            }
        }
        return success
    }
}
