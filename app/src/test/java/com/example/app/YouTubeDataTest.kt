package com.example.app

import com.example.app.data.*
import com.example.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeDataTest {
    @Test fun recognizesSupportedYouTubeLinks() {
        listOf("dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ?t=30", "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123", "https://m.youtube.com/shorts/dQw4w9WgXcQ", "https://www.youtube.com/live/dQw4w9WgXcQ").forEach { assertEquals(it, "dQw4w9WgXcQ", YouTubeLinks.videoId(it)) }
    }
    @Test fun rejectsLookalikeHostsAndScriptInjection() {
        listOf("https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ", "https://evil.example/youtu.be/dQw4w9WgXcQ", "javascript:alert(1)", "https://youtu.be/short", "';alert(1);//", "https://www.youtube.com/playlist?list=PL1", "file:///dQw4w9WgXcQ").forEach { assertNull(it, YouTubeLinks.videoId(it)) }
    }
    @Test fun parsesSearchAndMissingThumbnails() {
        val video = YouTubeJson.video(JSONObject("""{"id":{"videoId":"abcdefghijk"},"snippet":{"title":"A &amp; B","channelTitle":"Test","channelId":"UC1"}}"""))!!
        assertEquals("abcdefghijk", video.id); assertEquals("UC1", video.channelId); assertNull(video.thumbnailUrl)
        assertNull(YouTubeJson.video(JSONObject("""{"id":{"channelId":"UC1"},"snippet":{}}""")))
    }
    @Test fun parsesPlaylistVideoOwnerInsteadOfPlaylistOwner() {
        val video = YouTubeJson.video(JSONObject("""{"id":"playlistItem1","snippet":{"resourceId":{"videoId":"abcdefghijk"},"channelId":"playlistOwner","videoOwnerChannelId":"videoOwner","videoOwnerChannelTitle":"Author","title":"Video"}}"""))!!
        assertEquals("abcdefghijk", video.id); assertEquals("playlistItem1", video.playlistItemId); assertEquals("videoOwner", video.channelId); assertEquals("Author", video.channel)
    }
    @Test fun parsesVideoStatisticsAndLiveStatus() {
        val video = YouTubeJson.video(JSONObject("""{"id":"abcdefghijk","snippet":{"title":"Live","liveBroadcastContent":"live","thumbnails":{"medium":{"url":"https://example.test/image"}}},"statistics":{"viewCount":"123"},"contentDetails":{"duration":"PT2M"}}"""))!!
        assertTrue(video.live); assertEquals("123", video.views); assertEquals("PT2M", video.duration)
    }
    @Test fun errorsDoNotExposeProviderBodyOrCredentials() {
        val error = YouTubeJson.error(403, """{"error":{"message":"secret-token-value","errors":[{"reason":"quotaExceeded"}]}}""")
        assertEquals("quotaExceeded", error.reason); assertFalse(error.message!!.contains("secret-token-value"))
        assertTrue(YouTubeJson.error(401, "not json").message!!.contains("hết hạn"))
    }
    @Test fun libraryRoundTripPreservesUnicodePositionAndPlaylistOrder() {
        val first = VideoResult("abcdefghijk", "Âm nhạc Việt Nam", "Tác giả", null, "UC1")
        val second = VideoResult("12345678901", "Video 2", "Channel", "https://example.test/image")
        val state = LibraryState(listOf(SavedVideo(first, 75, 12345)), listOf(second), listOf(LocalPlaylist("p1", "Danh sách", listOf(first, second))), listOf("học Kotlin"))
        assertEquals(state, LocalLibrary.decode(LocalLibrary.encode(state)))
    }
    @Test fun corruptLocalDataDoesNotCrashStartup() {
        assertEquals(LibraryState(), LocalLibrary.decode("broken json"))
        assertEquals(LibraryState(), LocalLibrary.decode(null))
    }
}
