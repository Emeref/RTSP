package com.example.rtsptoyoutubertmp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage implementation using EncryptedSharedPreferences.
 */
class TokenManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRATION_TIME = "expiration_time"
    }

    fun saveTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        sharedPreferences.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (refreshToken != null) {
                putString(KEY_REFRESH_TOKEN, refreshToken)
            } else {
                remove(KEY_REFRESH_TOKEN)
            }
            putLong(KEY_EXPIRATION_TIME, System.currentTimeMillis() + (expiresInSeconds * 1000))
            apply()
        }
    }

    fun getAccessToken(): String? = sharedPreferences.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = sharedPreferences.getString(KEY_REFRESH_TOKEN, null)

    fun getExpirationTime(): Long = sharedPreferences.getLong(KEY_EXPIRATION_TIME, 0L)

    fun isTokenExpired(): Boolean {
        return System.currentTimeMillis() >= getExpirationTime()
    }

    fun clearTokens() {
        sharedPreferences.edit().clear().apply()
    }
}
