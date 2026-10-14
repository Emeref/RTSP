package com.example.rtsptoyoutubertmp

import com.google.gson.annotations.SerializedName

data class YouTubeResponse(
    @SerializedName("kind") val kind: String,
    @SerializedName("items") val items: List<YouTubeItem>
)

data class YouTubeItem(
    val id: String,
    val snippet: YouTubeSnippet,
    val status: YouTubeStatus? = null
)

data class YouTubeSnippet(
    val title: String,
    val description: String?,
    val thumbnails: YouTubeThumbnails? = null
)

data class YouTubeThumbnails(
    val default: ThumbnailDetails?
)

data class ThumbnailDetails(
    val url: String
)

data class BroadcastStatusUpdate(
    val id: String,
    val status: BroadcastStatus
)

data class BroadcastStatus(
    val complete: Boolean
)

data class YouTubeStatus(
    val complete: Boolean = false
)
