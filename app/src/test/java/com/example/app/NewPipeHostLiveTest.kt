package com.example.app

import com.example.app.data.NewPipePublicSource
import com.example.app.data.YouTubeDataRepository
import com.example.app.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Opt in with NEWPIPE_LIVE_TESTS=true. No Google account, API key, or account mutations. */
class NewPipeHostLiveTest {
    @Before fun optIn() { assumeTrue(System.getenv("NEWPIPE_LIVE_TESTS") == "true") }
    private fun repository() = YouTubeDataRepository(token = { error("Public browse accessed OAuth") },
        apiKey = { error("Public browse accessed API key") }, publicSource = NewPipePublicSource())

    @Test fun searchAndChannelPaginationWithoutGoogleApi() = runBlocking<Unit> {
        withTimeout(90_000) {
            val repo = repository()
            val request = FeedRequest(FeedKind.Search, "conan")
            val first = repo.feed(request)
            assertTrue("Search is empty", first.items.isNotEmpty())
            assertNotNull("Search has no continuation", first.nextToken)
            val more = repo.feed(request, first.nextToken)
            assertTrue("No new results on page two", more.items.any { v -> first.items.none { it.id == v.id } })
            val channelId = first.items.first { it.channelId.isNotBlank() }.channelId
            assertTrue(repo.channel(channelId).title.isNotBlank())
            val channelRequest = FeedRequest(FeedKind.Channel, resourceId = channelId)
            val videos = repo.feed(channelRequest)
            assertTrue("Channel is empty", videos.items.isNotEmpty())
            assertTrue("Expected channel $channelId, got ${videos.items.map { it.channelId }.distinct()}", videos.items.all { it.channelId == channelId })
            videos.nextToken?.let {
                assertTrue("Channel page two is empty", repo.feed(channelRequest, it).items.isNotEmpty())
            }
        }
    }

    @Test fun publicHomeShortsAndFilters() = runBlocking {
        withTimeout(90_000) {
            val repo = repository()
            assertTrue("Home is empty", repo.feed(FeedRequest()).items.isNotEmpty())
            assertTrue("Shorts is empty", repo.feed(FeedRequest(FeedKind.Shorts)).items.isNotEmpty())
            val short = repo.feed(FeedRequest(FeedKind.Search, "music", duration = "short"))
            assertTrue(short.items.isNotEmpty())
            assertTrue("Short duration filter was not applied: ${short.items.map { it.duration }}", short.items.all {
                it.duration.isNotBlank() && java.time.Duration.parse(it.duration).seconds < 240
            })
            val live = repo.feed(FeedRequest(FeedKind.Search, "news", liveOnly = true))
            assertTrue("Live search is empty", live.items.isNotEmpty())
            assertTrue("Live filter was not applied", live.items.all { it.live })
            assertTrue(repo.feed(FeedRequest(FeedKind.Search, "kotlin", order = "date")).items.isNotEmpty())
        }
    }
}
