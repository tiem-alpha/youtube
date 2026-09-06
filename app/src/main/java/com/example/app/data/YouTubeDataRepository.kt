package com.example.app.data

import com.example.app.BuildConfig
import com.example.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class YouTubeApiException(val status: Int, val reason: String, message: String) : Exception(message)

object YouTubeJson {
    fun thumbnail(snippet: JSONObject): String? {
        val thumbs = snippet.optJSONObject("thumbnails") ?: return null
        return listOf("high", "medium", "default").firstNotNullOfOrNull { thumbs.optJSONObject(it)?.optString("url")?.takeIf(String::isNotBlank) }
    }
    fun video(item: JSONObject): VideoResult? {
        val snippet = item.optJSONObject("snippet") ?: return null
        val idObject = item.optJSONObject("id")
        val resource = snippet.optJSONObject("resourceId")
        val id = idObject?.optString("videoId") ?: resource?.optString("videoId") ?: item.optString("id")
        if (id.isBlank()) return null
        return VideoResult(id, snippet.optString("title"), snippet.optString("videoOwnerChannelTitle", snippet.optString("channelTitle")), thumbnail(snippet),
            snippet.optString("videoOwnerChannelId", snippet.optString("channelId")), snippet.optString("description"), snippet.optString("publishedAt"),
            item.optJSONObject("contentDetails")?.optString("duration").orEmpty(), item.optJSONObject("statistics")?.optString("viewCount").orEmpty(),
            snippet.optString("liveBroadcastContent") == "live", if (resource != null) item.optString("id") else "", snippet.optString("categoryId"))
    }
    fun error(status: Int, body: String): YouTubeApiException {
        val reason = runCatching { JSONObject(body).optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason") }.getOrNull().orEmpty()
        val message = when {
            status == 401 -> "Phiên đăng nhập đã hết hạn. Hãy kết nối lại tài khoản."
            reason in listOf("quotaExceeded", "dailyLimitExceeded") -> "YouTube API đã hết hạn mức hôm nay. Vui lòng thử lại sau."
            reason == "commentsDisabled" -> "Video này đã tắt bình luận."
            reason == "accessNotConfigured" -> "Cần bật YouTube Data API v3 trong Google Cloud."
            reason == "insufficientPermissions" -> "Tài khoản chưa cấp đủ quyền YouTube cho thao tác này."
            reason in listOf("keyInvalid", "ipRefererBlocked", "forbidden") || status == 403 -> "Không có quyền thực hiện thao tác. Kiểm tra quyền tài khoản và cấu hình API."
            status == 404 -> "Nội dung không còn tồn tại hoặc không thể truy cập."
            status == 429 -> "Quá nhiều yêu cầu. Hãy đợi một lát rồi thử lại."
            else -> "YouTube không xử lý được yêu cầu (HTTP $status)."
        }
        return YouTubeApiException(status, reason, message)
    }
}

class YouTubeDataRepository(private val token: () -> String? = { null }, private val apiKey: () -> String = { BuildConfig.YOUTUBE_API_KEY }, private val onUnauthorized: () -> Unit = {}, private val baseUrl: String = "https://www.googleapis.com/youtube/v3/") : VideoRepository {
    private suspend fun request(path: String, params: Map<String, String> = emptyMap(), method: String = "GET", body: JSONObject? = null, authenticated: Boolean = false, preferApiKey: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val accessToken = if (preferApiKey && !authenticated && apiKey().isNotBlank()) null else token()
        if (authenticated && accessToken.isNullOrBlank()) throw YouTubeApiException(401, "loginRequired", "Hãy đăng nhập để sử dụng tính năng này.")
        if (accessToken.isNullOrBlank() && apiKey().isBlank()) throw YouTubeApiException(0, "notConfigured", "Bản ứng dụng chưa được cấu hình dữ liệu YouTube công khai. Bạn vẫn có thể mở liên kết để xem video mà không cần đăng nhập.")
        val query = (params.filterValues(String::isNotBlank) + if (accessToken.isNullOrBlank()) mapOf("key" to apiKey()) else emptyMap())
            .entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        val connection = URL(baseUrl + path + "?" + query).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 20000; connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer " + accessToken)
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401 && !accessToken.isNullOrBlank()) onUnauthorized()
            if (code !in 200..299) throw YouTubeJson.error(code, response)
            if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally { connection.disconnect() }
    }

    override suspend fun search(query: String): SearchResult = try { SearchResult.Success(feed(FeedRequest(FeedKind.Search, query)).items) }
        catch (e: YouTubeApiException) { if (e.reason == "notConfigured") SearchResult.ProviderNotConfigured else SearchResult.Failure(e.message.orEmpty()) }

    suspend fun feed(feed: FeedRequest, page: String? = null): Page<VideoResult> {
        val params = mutableMapOf("part" to "snippet", "maxResults" to "25", "pageToken" to page.orEmpty())
        val path = when (feed.kind) {
            FeedKind.Home -> { params["part"] = "snippet,contentDetails,statistics"; params["chart"] = "mostPopular"; params["regionCode"] = "VN"; params["videoCategoryId"] = feed.categoryId; "videos" }
            FeedKind.Liked -> { params["part"] = "snippet,contentDetails,statistics"; params["myRating"] = "like"; "videos" }
            FeedKind.Playlist -> { params["playlistId"] = feed.resourceId; "playlistItems" }
            else -> {
                params["type"] = "video"; params["q"] = feed.query; params["order"] = feed.order
                params["videoCategoryId"] = feed.categoryId
                params["videoDuration"] = if (feed.kind == FeedKind.Shorts) "short" else feed.duration
                if (feed.kind == FeedKind.Shorts && feed.query.isBlank()) params["q"] = "#shorts"
                if (feed.kind == FeedKind.Channel) { params["channelId"] = feed.resourceId; params["order"] = "date" }
                if (feed.liveOnly) params["eventType"] = "live"
                "search"
            }
        }
        val json = request(path, params, authenticated = feed.requiresAccount, preferApiKey = !feed.requiresAccount)
        return Page(json.items().mapNotNull(YouTubeJson::video), json.next())
    }
    suspend fun video(id: String): VideoResult = request("videos", mapOf("part" to "snippet,contentDetails,statistics", "id" to id), preferApiKey = true).items().firstOrNull()?.let(YouTubeJson::video)
        ?: throw YouTubeApiException(404, "notFound", "Video không tồn tại hoặc ở chế độ riêng tư.")

    suspend fun channel(id: String): Channel = request("channels", mapOf("part" to "snippet,statistics,contentDetails", "id" to id), preferApiKey = true).items().firstOrNull()?.let {
        val s = it.getJSONObject("snippet")
        Channel(it.getString("id"), s.optString("title"), YouTubeJson.thumbnail(s), s.optString("description"), it.optJSONObject("statistics")?.optString("subscriberCount").orEmpty(), it.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("uploads").orEmpty())
    } ?: throw YouTubeApiException(404, "notFound", "Không tìm thấy kênh.")

    suspend fun myChannel(): Channel? = request("channels", mapOf("part" to "snippet", "mine" to "true"), authenticated = true).items().firstOrNull()?.let {
        val snippet = it.getJSONObject("snippet")
        Channel(it.getString("id"), snippet.optString("title"), YouTubeJson.thumbnail(snippet), snippet.optString("description"))
    }

    suspend fun subscriptions(page: String? = null): Page<Channel> {
        val json = request("subscriptions", mapOf("part" to "snippet", "mine" to "true", "maxResults" to "50", "order" to "alphabetical", "pageToken" to page.orEmpty()), authenticated = true)
        return Page(json.items().map { val s = it.getJSONObject("snippet"); Channel(s.getJSONObject("resourceId").getString("channelId"), s.optString("title"), YouTubeJson.thumbnail(s), subscriptionId = it.getString("id")) }, json.next())
    }
    suspend fun subscription(channelId: String): String? = request("subscriptions", mapOf("part" to "id", "mine" to "true", "forChannelId" to channelId), authenticated = true).items().firstOrNull()?.optString("id")
    suspend fun subscribe(channelId: String): String = request("subscriptions", mapOf("part" to "snippet"), "POST", JSONObject().put("snippet", JSONObject().put("resourceId", JSONObject().put("kind", "youtube#channel").put("channelId", channelId))), true).getString("id")
    suspend fun unsubscribe(id: String) { request("subscriptions", mapOf("id" to id), "DELETE", authenticated = true) }
    suspend fun rating(videoId: String): String = request("videos/getRating", mapOf("id" to videoId), authenticated = true).items().firstOrNull()?.optString("rating") ?: "none"
    suspend fun rate(videoId: String, rating: String) { require(rating in listOf("like", "dislike", "none")); request("videos/rate", mapOf("id" to videoId, "rating" to rating), "POST", authenticated = true) }
    suspend fun playlists(page: String? = null): Page<VideoPlaylist> {
        val json = request("playlists", mapOf("part" to "snippet,contentDetails", "mine" to "true", "maxResults" to "50", "pageToken" to page.orEmpty()), authenticated = true)
        return Page(json.items().map { val s = it.getJSONObject("snippet"); VideoPlaylist(it.getString("id"), s.optString("title"), it.optJSONObject("contentDetails")?.optInt("itemCount") ?: 0, YouTubeJson.thumbnail(s)) }, json.next())
    }
    suspend fun createPlaylist(title: String, privacy: String): VideoPlaylist {
        require(title.isNotBlank()); require(privacy in listOf("private", "unlisted", "public"))
        val json = request("playlists", mapOf("part" to "snippet,status"), "POST", JSONObject().put("snippet", JSONObject().put("title", title)).put("status", JSONObject().put("privacyStatus", privacy)), true)
        return VideoPlaylist(json.getString("id"), title)
    }
    suspend fun addToPlaylist(playlistId: String, videoId: String) {
        request("playlistItems", mapOf("part" to "snippet"), "POST", JSONObject().put("snippet", JSONObject().put("playlistId", playlistId).put("resourceId", JSONObject().put("kind", "youtube#video").put("videoId", videoId))), true)
    }
    suspend fun removeFromPlaylist(itemId: String) { request("playlistItems", mapOf("id" to itemId), "DELETE", authenticated = true) }
    suspend fun renamePlaylist(id: String, title: String) {
        require(title.isNotBlank())
        val snippet = request("playlists", mapOf("part" to "snippet", "id" to id), authenticated = true).items().first().getJSONObject("snippet")
        val editable = JSONObject().put("title", title).put("description", snippet.optString("description"))
        snippet.optString("defaultLanguage").takeIf(String::isNotBlank)?.let { editable.put("defaultLanguage", it) }
        request("playlists", mapOf("part" to "snippet"), "PUT", JSONObject().put("id", id).put("snippet", editable), true)
    }
    suspend fun deletePlaylist(id: String) { request("playlists", mapOf("id" to id), "DELETE", authenticated = true) }
    suspend fun replies(parentId: String, page: String? = null): Page<VideoComment> {
        val json = request("comments", mapOf("part" to "snippet", "parentId" to parentId, "textFormat" to "plainText", "maxResults" to "50", "pageToken" to page.orEmpty()), preferApiKey = true)
        return Page(json.items().map { val s = it.getJSONObject("snippet"); VideoComment(it.getString("id"), s.optString("authorDisplayName"), s.optString("textDisplay"), s.optInt("likeCount"), s.optString("publishedAt")) }, json.next())
    }
    suspend fun reply(parentId: String, text: String) {
        require(text.isNotBlank())
        request("comments", mapOf("part" to "snippet"), "POST", JSONObject().put("snippet", JSONObject().put("parentId", parentId).put("textOriginal", text)), true)
    }
    suspend fun comments(videoId: String, page: String? = null): Page<VideoComment> {
        val json = request("commentThreads", mapOf("part" to "snippet", "videoId" to videoId, "textFormat" to "plainText", "maxResults" to "25", "order" to "relevance", "pageToken" to page.orEmpty()), preferApiKey = true)
        return Page(json.items().map { val thread = it.getJSONObject("snippet"); val comment = thread.getJSONObject("topLevelComment"); val s = comment.getJSONObject("snippet"); VideoComment(comment.getString("id"), s.optString("authorDisplayName"), s.optString("textDisplay"), s.optInt("likeCount"), s.optString("publishedAt"), thread.optInt("totalReplyCount")) }, json.next())
    }
    suspend fun postComment(videoId: String, text: String) {
        require(text.isNotBlank())
        request("commentThreads", mapOf("part" to "snippet"), "POST", JSONObject().put("snippet", JSONObject().put("videoId", videoId).put("topLevelComment", JSONObject().put("snippet", JSONObject().put("textOriginal", text)))), true)
    }
    suspend fun profile(accessToken: String): GoogleProfile = withContext(Dispatchers.IO) {
        val c = URL("https://www.googleapis.com/oauth2/v3/userinfo").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 15000; c.setRequestProperty("Authorization", "Bearer " + accessToken)
            if (c.responseCode != 200) throw YouTubeApiException(c.responseCode, "profile", "Không lấy được thông tin tài khoản. Vui lòng kết nối lại.")
            val p = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            GoogleProfile(p.getString("sub"), p.optString("email"), p.optString("name", p.optString("email")), p.optString("picture").takeIf(String::isNotBlank))
        } finally { c.disconnect() }
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
private fun JSONObject.items(): List<JSONObject> = optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull(a::optJSONObject) } ?: emptyList()
private fun JSONObject.next(): String? = optString("nextPageToken").takeIf(String::isNotBlank)
