package com.example.rtsptoyoutubertmp

import android.app.Application
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class App : Application() {
    lateinit var authManager: AuthManager
        private set
    lateinit var tokenManager: TokenManager
        private set
    lateinit var youtubeApiService: YouTubeApiService
        private set
    lateinit var youtubeServiceManager: YouTubeServiceManager
        private set

    override fun onCreate() {
        super.onCreate()
        
        tokenManager = TokenManager(this)
        
        val retrofit = Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        val tokenRefreshService = retrofit.create(TokenRefreshService::class.java)
        youtubeApiService = retrofit.create(YouTubeApiService::class.java)
        authManager = AuthManager(this, tokenManager, tokenRefreshService)
        youtubeServiceManager = YouTubeServiceManager(youtubeApiService, tokenManager)
    }
}
