package com.example.app

import com.example.app.data.*
import com.example.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeRecommendationsTest {
    @Test fun hiddenVideosSurviveRestartAndOldLibrariesRemainCompatible() {
        val state = LibraryState(hiddenIds = setOf("hidden", "another"))
        assertEquals(state.hiddenIds, LocalLibrary.decode(LocalLibrary.encode(state)).hiddenIds)
        assertTrue(LocalLibrary.decode("{}").hiddenIds.isEmpty())
    }

    @Test fun refreshContinuesWithNewVideosAndNeverReturnsHiddenOnLaterPages() = runBlocking {
        val first = HomeRecommendations.load(HomeCursor(listOf(HomeSource(FeedRequest())), setOf("hidden"))) { _, _ ->
            Page(listOf(video("a"), video("hidden")), "next")
        }
        val refreshed = HomeRecommendations.load(first.next!!) { _, token ->
            assertEquals("next", token)
            Page(listOf(video("a"), video("hidden"), video("b")))
        }
        assertEquals(listOf("b"), refreshed.items.map { it.id })
    }

    private fun video(id: String, category: String = "", channel: String = "") =
        VideoResult(id, id, channel, null, channelId = channel, categoryId = category)

    @Test fun recentRepeatedTopicsAndChannelsDriveHome() {
        val history = listOf(
            SavedVideo(video("a", "27", "education"), updatedAt = 3),
            SavedVideo(video("b", "27", "education"), updatedAt = 2),
            SavedVideo(video("c", "10", "music"), updatedAt = 1))
        val plan = HomeRecommendations.plan(history, emptyList(), emptyList())
        assertFalse(plan.sources.any { it.request.kind == FeedKind.Search })
        assertEquals("education", plan.sources.first { it.request.kind == FeedKind.Channel }.request.resourceId)
        assertEquals(setOf("a", "b", "c"), plan.excludedIds)
    }

    @Test fun accountLikesAndSubscriptionsWorkWithoutLocalHistory() {
        val plan = HomeRecommendations.plan(emptyList(), listOf(video("liked", "28", "tech")),
            listOf(Channel("subscribed", "Channel", null)))
        assertEquals(listOf("tech", "subscribed"), plan.sources.filter { it.request.kind == FeedKind.Channel }.map { it.request.resourceId })
    }

    @Test fun noSignalsFallsBackToPopularAndIgnoresBlankMetadata() {
        val plan = HomeRecommendations.plan(listOf(SavedVideo(video("link"))), emptyList(), emptyList())
        assertEquals(listOf(HomeSource(FeedRequest())), plan.sources)
    }

    @Test fun explicitTopicsAndSubscriptionsHaveReservedSlots() {
        val history = (1..60).map { SavedVideo(video("$it", "27", "watched$it")
            .copy(title = "Learn Kotlin | Lesson $it"), updatedAt = it.toLong()) }
        val plan = HomeRecommendations.plan(history, emptyList(), listOf(Channel("subscription", "Learning", null)),
            listOf("Kotlin", "Android"))
        assertEquals(listOf("Kotlin", "Android"), plan.sources.filter { it.request.kind == FeedKind.Search }.map { it.request.query })
        assertTrue(plan.sources.any { it.request.resourceId == "subscription" })
        assertTrue(plan.sources.any { it.request.resourceId == "watched60" })
        assertTrue(plan.sources.size <= 6)
        assertFalse(plan.sources.any { it.request.kind == FeedKind.Home })
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

    @Test fun searchOnlyProfileDoesNotFallBackToTrending() {
        val plan = HomeRecommendations.plan(emptyList(), emptyList(), emptyList(), listOf("Kotlin", "Android"))
        assertEquals(listOf("Kotlin", "Android"), plan.sources.map { it.request.query })
        assertTrue(plan.sources.all { it.request.kind == FeedKind.Search })
    }

    @Test fun subscriptionOrderIsStableAndFrequentlyWatchedSubscriptionsComeFirst() {
        val history = (1..10).map { SavedVideo(video("v$it", channel = "z"), updatedAt = it.toLong()) }
        val subscriptions = listOf(Channel("a", "A", null), Channel("z", "Z", null), Channel("b", "B", null))
        val first = HomeRecommendations.plan(history, emptyList(), subscriptions)
        assertEquals("z", first.sources.first().request.resourceId)
        assertEquals(first, HomeRecommendations.plan(history, emptyList(), subscriptions.reversed()))
        assertFalse(first.sources.any { it.request.kind == FeedKind.Home })
    }

    @Test fun comedyTitlesDoNotBecomeDramaSearchesOrCrowdOutEnglish() {
        val history = listOf(
            SavedVideo(video("new", channel = "tuna").copy(title = "Nh? T?i C? Con Ch? N?ng Nghi?p | H?n Nh?n C? G? Vui #4"), updatedAt = 100),
            SavedVideo(video("older", channel = "tuna").copy(title = "Ch? T?i D??ng T?nh V?i Mai Th?y | H?n Nh?n C? G? Vui #5"), updatedAt = 90))
        val plan = HomeRecommendations.plan(history, emptyList(), listOf(Channel("tuna", "Monsieur Tuna", null)),
            listOf("conan", "English conversation", "CONAN"))
        assertEquals(listOf("conan", "English conversation"), plan.sources.filter { it.request.kind == FeedKind.Search }.map { it.request.query })
        assertEquals("tuna", plan.sources.first().request.resourceId)
        assertEquals(3, plan.sources.size)
    }

    @Test fun latestWatchedChannelIsKeptAlongsideFrequentChannel() {
        val history = listOf(SavedVideo(video("new", channel = "space"), updatedAt = 100)) +
            (1..10).map { SavedVideo(video("v$it", channel = "learning"), updatedAt = it.toLong()) }
        val plan = HomeRecommendations.plan(history, emptyList(), emptyList(), listOf("Android"))
        assertEquals(listOf("space", "learning"), plan.sources.filter { it.request.kind == FeedKind.Channel }.map { it.request.resourceId })
    }

    @Test fun legacySearchMigrationKeepsBothInterestsWithoutCrossingAccounts() {
        assertTrue(LocalLibrary.canImportLegacySearches("a", setOf("a")))
        assertTrue(LocalLibrary.canImportLegacySearches(null, emptySet()))
        assertFalse(LocalLibrary.canImportLegacySearches(null, setOf("a")))
        assertFalse(LocalLibrary.canImportLegacySearches("b", setOf("a")))
        assertFalse(LocalLibrary.canImportLegacySearches("a", setOf("a", "b")))
        val merged = LocalLibrary.mergeSearches(listOf("English conversation"), listOf("conan", "English b1", "CONAN", " "))
        assertEquals(listOf("English conversation", "conan", "English b1"), merged)
        val restored = LocalLibrary.decode(LocalLibrary.encode(LibraryState(searches = merged)))
        assertEquals(merged, restored.searches)
        val plan = HomeRecommendations.plan(emptyList(), emptyList(), emptyList(), restored.searches)
        assertEquals(merged, plan.sources.map { it.request.query })
    }

    @Test fun shortsAreExcludedOnInitialAndContinuationPagesButBriefRegularVideosRemain() = runBlocking {
        val cursor = HomeCursor(listOf(HomeSource(FeedRequest(FeedKind.Search, "Kotlin"))), emptySet())
        val first = HomeRecommendations.load(cursor) { _, _ ->
            Page(listOf(video("short").copy(isShort = true), video("tag").copy(title = "Demo #shorts"),
                video("regular").copy(duration = "PT45S")), "next")
        }
        assertEquals(listOf("regular"), first.items.map { it.id })
        val next = HomeRecommendations.load(first.next!!) { request, token ->
            assertEquals("Kotlin", request.query)
            assertEquals("next", token)
            Page(listOf(video("another-short").copy(isShort = true), video("long")))
        }
        assertEquals(listOf("long"), next.items.map { it.id })
        val restored = LocalLibrary.decode(LocalLibrary.encode(LibraryState(watchLater = listOf(video("s").copy(isShort = true)))))
        assertTrue(restored.watchLater.single().isShort)
        assertFalse(HomeRecommendations.eligible(restored.watchLater.single()))
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
