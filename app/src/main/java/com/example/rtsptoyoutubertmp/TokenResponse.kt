package com.example.rtsptoyoutubertmp

import com.google.gson.annotations.SerializedName

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("scope") val scope: String?,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("id_token") val idToken: String?
)

data class TokenRequest(
    @SerializedName("client_id") val clientId: String,
    @SerializedName("client_secret") val clientSecret: String?,
    @SerializedName("grant_type") val grantType: String = "refresh_token",
    @SerializedName("refresh_token") val refreshToken: String
)
