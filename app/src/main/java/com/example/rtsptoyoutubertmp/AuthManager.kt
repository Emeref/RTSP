package com.example.rtsptoyoutubertmp

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class AuthManager(
    private val context: Context,
    private val tokenManager: TokenManager,
    private val tokenRefreshService: TokenRefreshService,
    private val tokenExchangeService: TokenExchangeService
) {

    companion object {
        private const val TAG = "AuthManager"
        // Replace with your actual Web Client ID from Google Cloud Console
        // In a real app, this might be in BuildConfig or a resource file.
        private const val WEB_CLIENT_ID = "YOUR_WEB_CLIENT_ID_HERE"
        const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"
    }

    suspend fun signIn(activity: Activity): AuthResult = withContext(Dispatchers.Main) {
        val credentialManager = CredentialManager.create(context)

        // 1. Configure Google ID Option
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false) // Allow new users to sign up
            .setServerClientId(WEB_CLIENT_ID)
            .setNonce(generateSecureRandomNonce())
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        try {
            val result = credentialManager.getCredential(
                request = request,
                context = activity,
            )
            handleSignIn(result)
        } catch (e: Exception) {
            when (e) {
                is NoCredentialException -> {
                    Log.d(TAG, "No credentials available", e)
                    AuthResult.NoCredential
                }
                is GetCredentialException -> {
                    Log.e(TAG, "Credential error", e)
                    AuthResult.Error(e.message ?: "Unknown credential error")
                }
                else -> {
                    Log.e(TAG, "Unexpected error during sign-in", e)
                    AuthResult.Error(e.message ?: "Unexpected error")
                }
            }
        }
    }

    /**
     * Requesting YouTube access requires a full OAuth 2.0 flow to get a refresh_token.
     * This requires the Client ID from Google Cloud Console and a server-side 
     * exchange of the Authorization Code for the actual tokens.
     */
    suspend fun signInForYouTube(activity: Activity): AuthResult = withContext(Dispatchers.Main) {
        val credentialManager = CredentialManager.create(context)

        val googleSignInOption = GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID)
            .setNonce(generateSecureRandomNonce())
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleSignInOption)
            .build()

        try {
            val result = credentialManager.getCredential(
                request = request,
                context = activity,
            )
            handleSignIn(result)
        } catch (e: Exception) {
            Log.e(TAG, "YouTube sign-in failed", e)
            AuthResult.Error(e.message ?: "YouTube sign-in failed")
        }
    }

    private suspend fun handleSignIn(result: GetCredentialResponse): AuthResult {
        val credential = result.credential

        // 1. Handle Google ID Token (Standard Sign-In)
        if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                Log.d(TAG, "Successfully received ID Token: $idToken")
                
                // In a real app, you'd send idToken to backend. 
                // For now, we simulate successful token exchange.
                tokenManager.saveTokens("mock_access_token", "mock_refresh_token", 3600L)
                return AuthResult.Success
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing Google ID token", e)
                return AuthResult.Error("Token parsing failed")
            }
        }

        // 2. Handle Custom Credential (YouTube Sign-In / Auth Code)
        if (credential.data.containsKey("com.google.android.libraries.identity.googleid.SERVER_AUTH_CODE")) {
            val serverAuthCode = credential.data.getString("com.google.android.libraries.identity.googleid.SERVER_AUTH_CODE")
            if (!serverAuthCode.isNullOrEmpty()) {
                return try {
                    Log.d(TAG, "Successfully received serverAuthCode: $serverAuthCode")
                    val tokenResponse = tokenExchangeService.exchangeCodeForTokens(serverAuthCode)
                    tokenManager.saveTokens(
                        accessToken = tokenResponse.accessToken,
                        refreshToken = tokenResponse.refreshToken,
                        expiresInSeconds = tokenResponse.expiresIn
                    )
                    Log.d(TAG, "Successfully exchanged code for tokens")
                    AuthResult.Success
                } catch (e: Exception) {
                    Log.e(TAG, "Token exchange failed", e)
                    AuthResult.Error("Failed to exchange authorization code")
                }
            } else {
                Log.w(TAG, "Received CustomCredential but no serverAuthCode found")
                AuthResult.Error("No server auth code found in credential data")
            }
        }

        Log.e(TAG, "Unexpected credential type: ${credential.type}")
        return AuthResult.Error("Unexpected credential type")
    }

    suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = tokenManager.getRefreshToken()
        if (refreshToken.isNullOrEmpty()) return@withContext false

        try {
            // Note: In a real app, you'd use your Web Client ID and potentially a client secret.
            val response = tokenRefreshService.refreshToken(
                clientId = WEB_CLIENT_ID,
                clientSecret = null, // Use client secret if available (usually for server-side)
                refreshToken = refreshToken
            )
            
            tokenManager.saveTokens(
                accessToken = response.accessToken,
                refreshToken = response.refreshToken ?: refreshToken,
                expiresInSeconds = response.expiresIn
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            false
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val credentialManager = CredentialManager.create(context)
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            tokenManager.clearTokens()
            Log.d(TAG, "User signed out")
        } catch (e: Exception) {
            Log.e(TAG, "Error during sign-out", e)
        }
    }

    private fun generateSecureRandomNonce(byteLength: Int = 32): String {
        val randomBytes = ByteArray(byteLength)
        SecureRandom().nextBytes(randomBytes)
        return Base64.encodeToString(randomBytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    sealed class AuthResult {
        object Success : AuthResult()
        object NoCredential : AuthResult()
        data class Error(val message: String) : AuthResult()
    }
}
