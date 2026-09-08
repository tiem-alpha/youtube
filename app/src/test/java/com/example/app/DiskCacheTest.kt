package com.example.app

import com.example.app.data.*
import com.example.app.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun homeCacheIsIsolatedByAccountAndRestoresIndependentCursors() {
        var now = 1_000_000L
        val folder = temporary.newFolder()
        val cache = HomeDiskCache(folder) { now }
        val page = HomePage(listOf(VideoResult("video", "Title", "Channel", null)),
            HomeCursor(listOf(HomeSource(FeedRequest(FeedKind.Search, "Kotlin"), "next-topic"),
                HomeSource(FeedRequest(FeedKind.Channel, resourceId = "channel"), "next-channel")), setOf("watched", "video")), false)
        cache.write("account:a", page)
        assertNull(cache.read("account:b"))
        assertNull(cache.read("guest"))
        assertEquals(page, HomeDiskCache(folder) { now }.read("account:a")!!.page)
        assertTrue(cache.read("account:a")!!.fresh)
        now += 11 * 60_000L
        assertFalse(cache.read("account:a")!!.fresh)
        now += 24 * 60 * 60_000L
        assertEquals(page, HomeDiskCache(folder) { now }.read("account:a")!!.page)
    }

    @Test fun entriesSurviveRecreationExpireAndCanBeReplaced() {
        var now = 1_000_000L
        val folder = temporary.newFolder()
        val cache = BoundedDiskCache(folder, 100) { now }
        cache.write("a", "first".toByteArray())
        assertEquals("first", BoundedDiskCache(folder, 100) { now }.read("a", 1000)!!.decodeToString())
        cache.write("a", "replacement".toByteArray())
        assertEquals("replacement", cache.read("a", 1000)!!.decodeToString())
        now += 1001
        assertNull(cache.read("a", 1000))
    }

    @Test fun evictsOldestToStayWithinBudgetAndRejectsOversizedEntries() {
        var now = 1_000_000L
        val folder = temporary.newFolder()
        val cache = BoundedDiskCache(folder, 8) { now }
        cache.write("old", ByteArray(5))
        now += 1000
        cache.write("new", ByteArray(5))
        assertNull(cache.read("old", 5000))
        assertNotNull(cache.read("new", 5000))
        cache.write("too big", ByteArray(9))
        assertNull(cache.read("too big", 5000))
        assertTrue(folder.listFiles()!!.sumOf { it.length() } <= 8)
    }

    @Test fun feedKeepsOrderPaginationAndFreshnessWithoutCachingAccountData() {
        var now = 1_000_000L
        val cache = FeedDiskCache(temporary.newFolder()) { now }
        val request = FeedRequest(FeedKind.Search, "music")
        val page = Page(listOf(VideoResult("bbbbbbbbbbb", "B", "Channel", null), VideoResult("aaaaaaaaaaa", "A", "Channel", null)), "next")
        cache.write(request, page)
        assertEquals(page, cache.read(request)!!.page)
        assertTrue(cache.read(request)!!.fresh)
        now += 11 * 60_000L
        assertFalse(cache.read(request)!!.fresh)
        listOf(FeedRequest(FeedKind.Liked), FeedRequest(FeedKind.Playlist, resourceId = "private"), request.copy(liveOnly = true)).forEach {
            cache.write(it, page)
            assertNull(cache.read(it))
        }
        now += 24 * 60 * 60_000L
        assertNull(cache.read(request))
    }
}
