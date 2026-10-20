package com.example.rtsptoyoutubertmp

import kotlinx.coroutines.delay

interface TokenExchangeService {
    suspend fun exchangeCodeForTokens(authCode: String): TokenResponse
}

class MockTokenExchangeService : TokenExchangeService {
    override suspend fun exchangeCodeForTokens(authCode: String): TokenResponse {
        // Simulate network delay
        delay(1000)
        
        // Return dummy tokens based on the authCode
        return TokenResponse(
            accessToken = "mock_access_token_${authCode.hashCode()}",
            expiresIn = 3600L,
            refreshToken = "mock_refresh_token_${authCode.hashCode()}",
            scope = "https://www.googleapis.com/auth/youtube",
            tokenType = "Bearer",
            idToken = null
        )
    }
}
