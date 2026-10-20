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
    private val tokenRefreshService: TokenRefreshService
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

    private fun handleSignIn(result: GetCredentialResponse): AuthResult {
        val credential = result.credential

        return if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                val serverAuthCode = credential.data.getString("com.google.android.libraries.identity.googleid.APPLICATION_CREDENTIAL_TYPE") 
                // Note: In a real implementation, the serverAuthCode is part of the data returned.

                Log.d(TAG, "Successfully received ID Token: $idToken")

                // TODO: In a real app, send the `serverAuthCode` to your backend.
                // Your backend exchanges it for a real Access Token and Refresh Token.
                
                // FOR NOW: We simulate the result of a successful exchange for testing purposes.
                // In a real app, this data comes from your backend response.
                tokenManager.saveTokens(
                    accessToken = "access_token_from_backend",
                    refreshToken = "refresh_token_from_backend",
                    expiresInSeconds = 3600
                )

                AuthResult.Success
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing Google ID token", e)
                AuthResult.Error("Token parsing failed")
            }
        } else {
            Log.e(TAG, "Unexpected credential type: ${credential.type}")
            AuthResult.Error("Unexpected credential type")
        }
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
