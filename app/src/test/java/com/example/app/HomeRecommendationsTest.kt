package com.example.app

import com.example.app.data.*
import com.example.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeRecommendationsTest {
    private fun video(id: String, category: String = "", channel: String = "") =
        VideoResult(id, id, channel, null, channelId = channel, categoryId = category)

    @Test fun recentRepeatedTopicsAndChannelsDriveHome() {
        val history = listOf(
            SavedVideo(video("a", "27", "education"), updatedAt = 3),
            SavedVideo(video("b", "27", "education"), updatedAt = 2),
            SavedVideo(video("c", "10", "music"), updatedAt = 1))
        val plan = HomeRecommendations.plan(history, emptyList(), emptyList())
        assertEquals(listOf("27", "10"), plan.sources.take(2).map { it.request.categoryId })
        assertEquals("education", plan.sources.first { it.request.kind == FeedKind.Channel }.request.resourceId)
        assertEquals(setOf("a", "b", "c"), plan.excludedIds)
    }

    @Test fun accountLikesAndSubscriptionsWorkWithoutLocalHistory() {
        val plan = HomeRecommendations.plan(emptyList(), listOf(video("liked", "28", "tech")),
            listOf(Channel("subscribed", "Channel", null)))
        assertEquals("28", plan.sources.first().request.categoryId)
        assertEquals(listOf("tech", "subscribed"), plan.sources.filter { it.request.kind == FeedKind.Channel }.map { it.request.resourceId })
    }

    @Test fun noSignalsFallsBackToPopularAndIgnoresBlankMetadata() {
        val plan = HomeRecommendations.plan(listOf(SavedVideo(video("link"))), emptyList(), emptyList())
        assertEquals(listOf(HomeSource(FeedRequest())), plan.sources)
    }

    @Test fun topicsDiscoverOtherChannelsAndSubscriptionsHaveReservedSlots() {
        val history = (1..60).map { SavedVideo(video("$it", "27", "watched$it")
            .copy(title = "Học lập trình Kotlin | Bài $it"), updatedAt = it.toLong()) }
        val plan = HomeRecommendations.plan(history, emptyList(), listOf(Channel("subscription", "Learning", null)))
        assertTrue(plan.sources.any { it.request.query == "Học lập trình Kotlin" && it.request.resourceId.isEmpty() })
        assertTrue(plan.sources.any { it.request.resourceId == "subscription" })
        assertTrue(plan.sources.any { it.request.resourceId == "watched60" })
        assertTrue(plan.sources.size <= 6)
        assertEquals(FeedRequest(), plan.sources.last().request)
    }

    @Test fun mixesSourcesFiltersWatchedAndKeepsIndependentPagination() = runBlocking {
        val plan = HomeCursor(listOf(HomeSource(FeedRequest(categoryId = "10")),
            HomeSource(FeedRequest(FeedKind.Channel, resourceId = "tech"))), setOf("watched"))
        val page = HomeRecommendations.load(plan) { request, _ ->
            if (request.kind == FeedKind.Home) Page(listOf(video("watched"), video("a"), video("shared")), "music-next")
            else Page(listOf(video("b"), video("shared")))
        }
        assertEquals(listOf("b", "a", "shared"), page.items.map { it.id })
        assertEquals("music-next", page.next!!.sources.single().pageToken)
        val last = HomeRecommendations.load(page.next!!) { _, token ->
            assertEquals("music-next", token)
            Page(listOf(video("a"), video("new")))
        }
        assertEquals(listOf("new"), last.items.map { it.id }); assertNull(last.next)
    }

    @Test fun partialFailureRetainsOnlyFailedAndUnfinishedSources() = runBlocking {
        val bad = HomeSource(FeedRequest(categoryId = "27"))
        val good = HomeSource(FeedRequest(categoryId = "10"))
        val page = HomeRecommendations.load(HomeCursor(listOf(bad, good), emptySet())) { request, _ ->
            if (request.categoryId == "27") throw java.io.IOException()
            Page(listOf(video("music")))
        }
        assertTrue(page.partial); assertEquals(listOf(bad), page.next!!.sources)
        assertEquals("music", page.items.single().id)
    }

    @Test fun allFailedSourcesReportFailure() = runBlocking {
        try {
            HomeRecommendations.load(HomeRecommendations.plan(emptyList(), emptyList(), emptyList())) { _, _ -> throw java.io.IOException() }
            fail("Expected failure")
        } catch (_: java.io.IOException) { }
    }

    @Test fun cancellationIsNotConvertedIntoFallback() = runBlocking {
        try {
            HomeRecommendations.load(HomeRecommendations.plan(emptyList(), emptyList(), emptyList())) { _, _ -> throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun categorySurvivesDeviceStorageAndOldRecordsStillLoad() {
        val saved = LibraryState(history = listOf(SavedVideo(video("a", "28", "tech"))))
        assertEquals("28", LocalLibrary.decode(LocalLibrary.encode(saved)).history.single().video.categoryId)
        val old = """{"history":[{"video":{"id":"old","title":"Old"}}]}"""
        assertEquals("", LocalLibrary.decode(old).history.single().video.categoryId)
    }
}
