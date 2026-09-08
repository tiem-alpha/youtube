package com.example.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app.data.LocalLibrary
import com.example.app.domain.VideoResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryPersistenceTest {
    @Test fun queuedVideosAddedDuringPlaybackAdvanceAndPersistCompletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "queue_test_" + System.nanoTime()
        try {
            val library = LocalLibrary(context, name)
            val first = VideoResult("bbbbbbbbbbb", "B", "", null)
            val second = VideoResult("ccccccccccc", "C", "", null)
            library.toggleLater(first)
            library.toggleLater(second)
            assertEquals(first, library.finishQueuedVideo("aaaaaaaaaaa"))
            assertEquals(second, library.finishQueuedVideo(first.id))
            val restored = LocalLibrary(context, name)
            assertEquals(listOf(second), restored.state.value.watchLater)
            assertNull(restored.finishQueuedVideo(second.id))
            assertTrue(LocalLibrary(context, name).state.value.watchLater.isEmpty())
        } finally { context.getSharedPreferences(name, 0).edit().clear().commit() }
    }
    @Test fun historyWatchLaterAndPlaylistsSurviveReopening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "library_test_" + System.nanoTime()
        try {
            val first = LocalLibrary(context, name)
            val video = VideoResult("abcdefghijk", "Kiểm thử", "Kênh", null)
            first.record(video, 42); first.toggleLater(video); first.createPlaylist("Nhạc")
            val id = first.state.value.playlists.single().id
            first.add(id, video); first.add(id, video)
            val restored = LocalLibrary(context, name)
            assertEquals(42, restored.state.value.history.single().positionSeconds)
            assertEquals(video, restored.state.value.watchLater.single())
            assertEquals(1, restored.state.value.playlists.single().videos.size)
            restored.remove(id, video.id); restored.toggleLater(video); restored.removeHistory(video.id)
            assertTrue(LocalLibrary(context, name).state.value.watchLater.isEmpty())
            assertTrue(LocalLibrary(context, name).state.value.history.isEmpty())
            assertTrue(LocalLibrary(context, name).state.value.playlists.single().videos.isEmpty())
        } finally { context.getSharedPreferences(name, 0).edit().clear().commit() }
    }
}
