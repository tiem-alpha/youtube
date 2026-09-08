package com.example.app

import com.example.app.data.*
import com.example.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.Page as ExtractorPage
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.net.URLDecoder
import java.util.Base64

class NewPipeIntegrationTest {
    @Test fun publicDiscoveryNeverReadsGoogleCredentials() = runBlocking {
        val calls = mutableListOf<FeedRequest>()
        val source = object : PublicVideoSource {
            override suspend fun feed(request: FeedRequest, page: String?): Page<VideoResult> {
                calls += request
                assertEquals("np-page", page)
                return Page(listOf(VideoResult("abcdefghijk", "Video", "Channel", null)), "np-next")
            }
            override suspend fun channel(id: String) = Channel(id, "Channel", null)
            override suspend fun video(id: String) = error("Unused")
        }
        val repo = YouTubeDataRepository(token = { error("Public discovery read OAuth") },
            apiKey = { error("Public discovery read API key") }, publicSource = source)
        for (kind in listOf(FeedKind.Search, FeedKind.Shorts, FeedKind.Channel, FeedKind.Home)) {
            val result = repo.feed(FeedRequest(kind), "np-page")
            assertEquals("np-next", result.nextToken)
        }
        assertEquals(4, calls.size)
        assertEquals("UC1", repo.channel("UC1").id)
    }

    @Test fun providerFailureDoesNotFallBackToQuotaLimitedSearch() = runBlocking {
        for (failure in listOf(PublicBrowseException("blocked"), CancellationException("cancelled"))) {
            val source = object : PublicVideoSource {
                override suspend fun feed(request: FeedRequest, page: String?): Page<VideoResult> = throw failure
                override suspend fun channel(id: String): Channel = error("Unused")
                override suspend fun video(id: String): VideoResult = error("Unused")
            }
            val repo = YouTubeDataRepository(token = { error("Unexpected fallback") }, publicSource = source)
            try { repo.feed(FeedRequest(FeedKind.Search, "test")); fail("Expected failure") }
            catch (e: Exception) { assertSame(failure, e) }
        }
    }

    @Test fun continuationSurvivesDiskSerializationAndRejectsDifferentFeeds() {
        val request = FeedRequest(FeedKind.Channel, resourceId = "UC1")
        val page = ExtractorPage("https://www.youtube.com/youtubei/v1/browse", "token+1",
            listOf("Channel", "https://www.youtube.com/channel/UC1", "VERIFIED"),
            mapOf("test" to "value"), "{\"continuation\":\"a+b\"}".toByteArray())
        val token = NewPipePageCodec.encode(request, page)!!
        val decoded = NewPipePageCodec.decode(request, token)
        assertEquals(page.url, decoded.url); assertEquals(page.id, decoded.id)
        assertEquals(page.ids, decoded.ids); assertEquals(page.cookies, decoded.cookies)
        assertArrayEquals(page.body, decoded.body)
        for (invalid in listOf("google-page-token", token.replace("www.youtube.com", "example.com"))) {
            try { NewPipePageCodec.decode(request, invalid); fail("Expected invalid cursor") }
            catch (_: PublicBrowseException) { }
        }
        try { NewPipePageCodec.decode(request.copy(resourceId = "UC2"), token); fail("Wrong feed accepted") }
        catch (_: PublicBrowseException) { }
    }

    @Test fun searchFiltersEncodeExistingControlsAndNewPipeVideoDefault() {
        assertEquals("EgIQAfABAQ%3D%3D", NewPipeSearchFilters.encode(FeedRequest()))
        val encoded = NewPipeSearchFilters.encode(FeedRequest(order = "date", duration = "short", liveOnly = true))
        assertArrayEquals(byteArrayOf(8, 2, 18, 6, 16, 1, 24, 1, 64, 1, 0xf0.toByte(), 1, 1),
            Base64.getUrlDecoder().decode(URLDecoder.decode(encoded, "UTF-8")))
    }

    @Test fun streamItemsMapMetadataWithoutInventingUnknownCounts() {
        val item = StreamInfoItem(0, "https://www.youtube.com/watch?v=abcdefghijk", "Âm nhạc", StreamType.VIDEO_STREAM)
        item.uploaderName = "Tác giả"; item.uploaderUrl = "https://www.youtube.com/channel/UC1"
        item.duration = 125
        val video = NewPipePublicSource.mapVideo(item)!!
        assertEquals("abcdefghijk", video.id); assertEquals("UC1", video.channelId)
        assertEquals("PT125S", video.duration); assertEquals("", video.views)
        assertFalse(video.live)
        assertFalse(video.isShort)
        item.setShortFormContent(true)
        assertTrue(NewPipePublicSource.mapVideo(item)!!.isShort)
    }

    @Test fun daily429IsNotReportedAsBriefRateLimit() {
        val error = YouTubeJson.error(429, """{"error":{"errors":[{"reason":"rateLimitExceeded"}],"details":[{"metadata":{"quota_unit":"1/d/{project}"}}]}}""")
        assertTrue(error.message!!.contains("hạn mức hôm nay"))
        assertFalse(error.message!!.contains("một lát"))
    }
}
