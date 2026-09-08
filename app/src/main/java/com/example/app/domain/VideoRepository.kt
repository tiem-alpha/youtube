package com.example.app.domain

data class VideoResult(
    val id: String, val title: String, val channel: String, val thumbnailUrl: String?,
    val channelId: String = "", val description: String = "", val publishedAt: String = "",
    val duration: String = "", val views: String = "", val live: Boolean = false,
    val playlistItemId: String = "", val categoryId: String = "", val isShort: Boolean = false
)
data class Page<T>(val items: List<T>, val nextToken: String? = null)
data class Channel(val id: String, val title: String, val thumbnail: String?, val description: String = "", val subscribers: String = "", val uploads: String = "", val subscriptionId: String = "")
data class VideoPlaylist(val id: String, val title: String, val count: Int = 0, val thumbnail: String? = null)
data class VideoComment(val id: String, val author: String, val text: String, val likes: Int, val publishedAt: String, val replies: Int = 0)
data class GoogleProfile(val id: String, val email: String, val name: String, val picture: String?)
enum class FeedKind { Home, Search, Shorts, Channel, Playlist, Liked }
data class FeedRequest(val kind: FeedKind = FeedKind.Home, val query: String = "", val resourceId: String = "", val order: String = "relevance", val duration: String = "any", val liveOnly: Boolean = false, val categoryId: String = "")
// Playlist routes currently display playlists from the connected account's library.
val FeedRequest.requiresAccount: Boolean get() = kind in setOf(FeedKind.Liked, FeedKind.Playlist)

sealed interface SearchResult {
    data class Success(val videos: List<VideoResult>) : SearchResult
    data object ProviderNotConfigured : SearchResult
    data class Failure(val message: String) : SearchResult
}
interface VideoRepository { suspend fun search(query: String): SearchResult }

object YouTubeLinks {
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")
    fun videoId(input: String): String? {
        val text = input.trim()
        if (idPattern.matches(text)) return text
        return runCatching {
            val uri = java.net.URI(text)
            if (uri.scheme !in listOf("http", "https")) return null
            val host = uri.host?.lowercase() ?: return null
            val segments = uri.path.orEmpty().trim('/').split('/')
            val id = when (host) {
                "youtu.be" -> segments.firstOrNull()
                "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" ->
                    if (segments.firstOrNull() in listOf("shorts", "embed", "live")) segments.getOrNull(1)
                    else if (uri.path == "/watch") uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.substringAfter("v=")
                    else null
                else -> null
            }
            id?.takeIf(idPattern::matches)
        }.getOrNull()
    }
}
