package com.example.app

import com.example.app.data.YouTubeApiException
import com.example.app.data.YouTubeDataRepository
import com.example.app.domain.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.LinkedBlockingQueue

class YouTubeRepositoryTest {
    data class Captured(val method: String, val path: String, val query: Map<String, String>, val authorization: String?, val body: String)
    private val requests = LinkedBlockingQueue<Captured>()
    private val responses = LinkedBlockingQueue<Pair<Int, String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate {
                val pair = it.split('=', limit = 2); URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
            requests.add(Captured(exchange.requestMethod, exchange.requestURI.path, query, exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.bufferedReader().readText()))
            val (code, body) = responses.poll() ?: (200 to "{}")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
            if (code != 204) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }
    private val url get() = "http://127.0.0.1:${server.address.port}/"
    @After fun stop() { server.stop(0) }

    @Test fun publicSearchEncodesQueryAndPreservesPagination() = runBlocking {
        responses.add(200 to """{"items":[{"id":{"videoId":"abcdefghijk"},"snippet":{"title":"Hello"}}],"nextPageToken":"page+2"}""")
        val repo = YouTubeDataRepository(apiKey = { "test-key" }, baseUrl = url)
        val page = repo.feed(FeedRequest(FeedKind.Search, "âm nhạc & kotlin"), "page/1")
        assertEquals("page+2", page.nextToken); assertEquals("abcdefghijk", page.items.single().id)
        val request = requests.remove()
        assertEquals("GET", request.method); assertEquals("/search", request.path)
        assertEquals("âm nhạc & kotlin", request.query["q"]); assertEquals("page/1", request.query["pageToken"])
        assertEquals("test-key", request.query["key"]); assertNull(request.authorization)
    }
    @Test fun publicBrowsingUsesSameCredentialsBeforeAndAfterLogin() = runBlocking {
        var session: String? = null
        val repo = YouTubeDataRepository(token = { session }, apiKey = { "app-key" }, baseUrl = url)
        for (value in listOf(null, "account-token", "expired-token", null)) {
            session = value
            for (kind in listOf(FeedKind.Home, FeedKind.Search, FeedKind.Shorts, FeedKind.Channel)) {
                repo.feed(FeedRequest(kind, query = "music", resourceId = "channel-id"))
                val request = requests.remove()
                assertEquals("app-key", request.query["key"])
                assertNull(request.authorization)
            }
        }
    }
    @Test fun publicMetadataUsesAppKeyEvenWithAnExpiredSession() = runBlocking {
        responses.add(200 to """{"items":[{"id":"abcdefghijk","snippet":{"title":"Video"}}]}""")
        responses.add(200 to """{"items":[{"id":"channel-id","snippet":{"title":"Channel"}}]}""")
        val repo = YouTubeDataRepository(token = { "expired" }, apiKey = { "app-key" }, baseUrl = url)
        repo.video("abcdefghijk"); repo.channel("channel-id")
        repeat(2) { val request = requests.remove(); assertNull(request.authorization); assertEquals("app-key", request.query["key"]) }
    }
    @Test fun personalLibraryRequiresLoginEvenWithAppKey() = runBlocking {
        val repo = YouTubeDataRepository(apiKey = { "app-key" }, baseUrl = url)
        for (kind in listOf(FeedKind.Liked, FeedKind.Playlist)) {
            try { repo.feed(FeedRequest(kind, resourceId = "private-playlist")); fail("Expected login required") }
            catch (e: YouTubeApiException) { assertEquals("loginRequired", e.reason) }
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun personalPlaylistKeepsBearerWhenAppKeyExists() = runBlocking {
        YouTubeDataRepository(token = { "account-token" }, apiKey = { "app-key" }, baseUrl = url)
            .feed(FeedRequest(FeedKind.Playlist, resourceId = "private-playlist"))
        val request = requests.remove()
        assertEquals("Bearer account-token", request.authorization); assertFalse(request.query.containsKey("key"))
    }
    @Test fun myChannelUsesYouTubeIdentityAndAllowsAccountWithoutChannel() = runBlocking {
        responses.add(200 to """{"items":[{"id":"UC-IoT","snippet":{"title":"IoT","thumbnails":{"high":{"url":"https://example.com/channel.png"}}}}]}""")
        responses.add(200 to """{"items":[]}""")
        val repo = YouTubeDataRepository(token = { "account-token" }, apiKey = { "app-key" }, baseUrl = url)
        val channel = repo.myChannel()!!
        assertEquals("IoT", channel.title); assertEquals("UC-IoT", channel.id)
        assertEquals("https://example.com/channel.png", channel.thumbnail)
        val request = requests.remove()
        assertEquals("true", request.query["mine"]); assertEquals("Bearer account-token", request.authorization)
        assertNull(repo.myChannel())
    }
    @Test fun publicRequestFailureDoesNotInvalidateGoogleSession() = runBlocking {
        responses.add(401 to "{}")
        var invalidated = false
        val repo = YouTubeDataRepository(token = { "account-token" }, apiKey = { "app-key" }, onUnauthorized = { invalidated = true }, baseUrl = url)
        try { repo.feed(FeedRequest()); fail("Expected API failure") }
        catch (e: YouTubeApiException) { assertEquals(401, e.status) }
        assertFalse(invalidated)
    }
    @Test fun accountMutationUsesBearerAndNoApiKeyInUrl() = runBlocking {
        responses.add(204 to "")
        val repo = YouTubeDataRepository(token = { "test-token" }, apiKey = { "unused-key" }, baseUrl = url)
        repo.rate("abcdefghijk", "like")
        val request = requests.remove()
        assertEquals("POST", request.method); assertEquals("/videos/rate", request.path)
        assertEquals("Bearer test-token", request.authorization); assertFalse(request.query.containsKey("key"))
        assertEquals("like", request.query["rating"])
    }
    @Test fun commentsCanBeReadWithoutLoginAndUseCommentIdForReplies() = runBlocking {
        responses.add(200 to """{"items":[{"id":"thread-id","snippet":{"totalReplyCount":3,"topLevelComment":{"id":"comment-id","snippet":{"authorDisplayName":"Viewer","textDisplay":"Hello","likeCount":7,"publishedAt":"2026-09-05T00:00:00Z"}}}}],"nextPageToken":"next-page"}""")
        val repo = YouTubeDataRepository(apiKey = { "test-key" }, baseUrl = url)
        val page = repo.comments("abcdefghijk", "page-1")
        val comment = page.items.single()
        assertEquals("comment-id", comment.id); assertEquals("Hello", comment.text)
        assertEquals(3, comment.replies); assertEquals(7, comment.likes)
        assertEquals("next-page", page.nextToken)
        val request = requests.remove()
        assertEquals("/commentThreads", request.path); assertNull(request.authorization)
        assertEquals("abcdefghijk", request.query["videoId"])
        assertEquals("plainText", request.query["textFormat"]); assertEquals("page-1", request.query["pageToken"])
    }
    @Test fun disabledCommentsAreReportedInsteadOfAnEmptyList() = runBlocking {
        responses.add(403 to """{"error":{"errors":[{"reason":"commentsDisabled"}]}}""")
        try {
            YouTubeDataRepository(apiKey = { "test-key" }, baseUrl = url).comments("abcdefghijk")
            fail("Expected disabled comments error")
        } catch (e: YouTubeApiException) { assertEquals("commentsDisabled", e.reason) }
    }
    @Test fun publicCommentsAndRepliesPreferApiKeyOverReadOnlyToken() = runBlocking {
        val repo = YouTubeDataRepository(token = { "read-only-token" }, apiKey = { "test-key" }, baseUrl = url)
        repo.comments("abcdefghijk")
        repo.replies("comment-id")
        repeat(2) {
            val request = requests.remove()
            assertEquals("test-key", request.query["key"])
            assertNull(request.authorization)
        }
    }
    @Test fun commentsWithoutApiKeyReportMissingOAuthPermission() = runBlocking {
        responses.add(403 to """{"error":{"errors":[{"reason":"insufficientPermissions"}]}}""")
        val repo = YouTubeDataRepository(token = { "read-only-token" }, apiKey = { "" }, baseUrl = url)
        try { repo.comments("abcdefghijk"); fail("Expected missing permission") }
        catch (e: YouTubeApiException) { assertEquals("insufficientPermissions", e.reason) }
        assertEquals("Bearer read-only-token", requests.remove().authorization)
    }
    @Test fun missingSessionNeverSendsWriteRequest() = runBlocking {
        val repo = YouTubeDataRepository(apiKey = { "test-key" }, baseUrl = url)
        try { repo.rate("abcdefghijk", "like"); fail("Expected authentication failure") }
        catch (e: YouTubeApiException) { assertEquals(401, e.status) }
        assertTrue(requests.isEmpty())
    }
    @Test fun unauthorizedResponseInvalidatesSession() = runBlocking {
        responses.add(401 to """{"error":{"message":"private raw error"}}""")
        var invalidated = false
        val repo = YouTubeDataRepository(token = { "expired" }, onUnauthorized = { invalidated = true }, baseUrl = url)
        try { repo.subscriptions(); fail("Expected expired token") }
        catch (e: YouTubeApiException) { assertEquals(401, e.status); assertFalse(e.message!!.contains("private raw error")) }
        assertTrue(invalidated)
    }
    @Test fun renamingPlaylistPreservesExistingDescriptionAndLanguage() = runBlocking {
        responses.add(200 to """{"items":[{"snippet":{"title":"Old","description":"Keep this","defaultLanguage":"vi"}}]}""")
        responses.add(200 to "{}")
        YouTubeDataRepository(token = { "test" }, baseUrl = url).renamePlaylist("PL1", "New")
        requests.remove()
        val write = requests.remove()
        val snippet = org.json.JSONObject(write.body).getJSONObject("snippet")
        assertEquals("PUT", write.method); assertEquals("New", snippet.getString("title"))
        assertEquals("Keep this", snippet.getString("description")); assertEquals("vi", snippet.getString("defaultLanguage"))
    }

    @Test fun topicRecommendationsUseCategorySearchAndParseMetadata() = runBlocking {
        responses.add(200 to """{"items":[{"id":"abcdefghijk","snippet":{"title":"Technology","categoryId":"28"}}]}""")
        val page = YouTubeDataRepository(apiKey = { "app-key" }, baseUrl = url).feed(FeedRequest(FeedKind.Search, categoryId = "28"), "next")
        val request = requests.remove()
        assertEquals("/search", request.path); assertEquals("video", request.query["type"])
        assertEquals("28", request.query["videoCategoryId"]); assertEquals("next", request.query["pageToken"])
        assertEquals("28", page.items.single().categoryId)
    }

    @Test fun accountCollectionsUseOAuthAndPreserveNextPage() = runBlocking {
        responses.add(200 to """{"items":[{"id":"PL-private","snippet":{"title":"Bộ sưu tập"},"contentDetails":{"itemCount":12}}],"nextPageToken":"page2"}""")
        val page = YouTubeDataRepository(token = { "account" }, apiKey = { "app-key" }, baseUrl = url).playlists("page1")
        assertEquals("Bộ sưu tập", page.items.single().title); assertEquals(12, page.items.single().count)
        assertEquals("page2", page.nextToken)
        val request = requests.remove()
        assertEquals("true", request.query["mine"]); assertEquals("page1", request.query["pageToken"])
        assertEquals("Bearer account", request.authorization); assertNull(request.query["key"])
    }
}
