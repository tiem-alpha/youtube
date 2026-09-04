package com.example.app.data

import com.example.app.BuildConfig
import com.example.app.domain.SearchResult
import com.example.app.domain.VideoRepository
import com.example.app.domain.VideoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** Official YouTube Data API search adapter. Configure YOUTUBE_API_KEY in local.properties. */
class YouTubeDataRepository : VideoRepository {
    override suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        if (BuildConfig.YOUTUBE_API_KEY.isBlank()) return@withContext SearchResult.ProviderNotConfigured
        try {
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            val endpoint = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=25&q=$encoded&key=${BuildConfig.YOUTUBE_API_KEY}"
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.inputStream.bufferedReader().use { reader ->
                val items = JSONObject(reader.readText()).getJSONArray("items")
                val videos = buildList {
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        val snippet = item.getJSONObject("snippet")
                        val thumbs = snippet.getJSONObject("thumbnails")
                        add(VideoResult(item.getJSONObject("id").getString("videoId"), snippet.getString("title"), snippet.getString("channelTitle"), thumbs.optJSONObject("medium")?.optString("url")))
                    }
                }
                SearchResult.Success(videos)
            }
        } catch (error: Exception) {
            SearchResult.Failure(error.message ?: "Unable to search YouTube right now.")
        }
    }
}
