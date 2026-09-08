package com.example.app

import com.example.app.data.LocalLibrary
import com.example.app.domain.VideoResult
import org.junit.Assert.*
import org.junit.Test

class WatchQueueTest {
    private val a = VideoResult("aaaaaaaaaaa", "A", "", null)
    private val b = VideoResult("bbbbbbbbbbb", "B", "", null)
    private val c = VideoResult("ccccccccccc", "C", "", null)

    @Test fun currentVideoOutsideQueueAdvancesInInsertionOrderAndStopsWhenEmpty() {
        var queue = listOf(c, b)
        queue = LocalLibrary.remainingWatchQueue(queue, a.id)
        assertEquals(b, queue.lastOrNull())
        queue = LocalLibrary.remainingWatchQueue(queue, b.id)
        assertEquals(c, queue.lastOrNull())
        queue = LocalLibrary.remainingWatchQueue(queue, c.id)
        assertNull(queue.lastOrNull())
    }

    @Test fun completedVideoIsSkippedEvenWhenAddedAfterOtherQueuedVideos() {
        val queue = LocalLibrary.remainingWatchQueue(listOf(a, c, b), a.id)
        assertEquals(listOf(c, b), queue)
        assertEquals(b, queue.lastOrNull())
    }
}
