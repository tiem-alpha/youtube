package com.example.app.data

import android.content.Context
import com.example.app.domain.VideoResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedVideo(val video: VideoResult, val positionSeconds: Int = 0, val updatedAt: Long = System.currentTimeMillis())
data class LocalPlaylist(val id: String, val title: String, val videos: List<VideoResult> = emptyList())
data class LibraryState(val history: List<SavedVideo> = emptyList(), val watchLater: List<VideoResult> = emptyList(), val playlists: List<LocalPlaylist> = emptyList(), val searches: List<String> = emptyList())

/** Device library is deliberately separate from YouTube account data. No OAuth tokens are stored here. */
class LocalLibrary(context: Context, preferencesName: String = "library") {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(decode(prefs.getString("state", null)))
    val state = _state.asStateFlow()
    private fun save(state: LibraryState) { _state.value = state; prefs.edit().putString("state", encode(state)).apply() }
    fun record(video: VideoResult, seconds: Int = _state.value.history.firstOrNull { it.video.id == video.id }?.positionSeconds ?: 0) {
        save(_state.value.copy(history = (listOf(SavedVideo(video, seconds.coerceAtLeast(0))) + _state.value.history.filterNot { it.video.id == video.id }).take(300)))
    }
    fun toggleLater(video: VideoResult) {
        val old = _state.value.watchLater
        save(_state.value.copy(watchLater = if (old.any { it.id == video.id }) old.filterNot { it.id == video.id } else listOf(video) + old))
    }
    fun createPlaylist(title: String) { if (title.isNotBlank()) save(_state.value.copy(playlists = _state.value.playlists + LocalPlaylist(UUID.randomUUID().toString(), title.trim()))) }
    fun add(playlistId: String, video: VideoResult) { save(_state.value.copy(playlists = _state.value.playlists.map { if (it.id == playlistId) it.copy(videos = (it.videos + video).distinctBy(VideoResult::id)) else it })) }
    fun remove(playlistId: String, videoId: String) { save(_state.value.copy(playlists = _state.value.playlists.map { if (it.id == playlistId) it.copy(videos = it.videos.filterNot { v -> v.id == videoId }) else it })) }
    fun deletePlaylist(id: String) { save(_state.value.copy(playlists = _state.value.playlists.filterNot { it.id == id })) }
    fun removeHistory(id: String) { save(_state.value.copy(history = _state.value.history.filterNot { it.video.id == id })) }
    fun clearHistory() { save(_state.value.copy(history = emptyList())) }
    fun search(query: String) { if (query.isNotBlank()) save(_state.value.copy(searches = (listOf(query.trim()) + _state.value.searches.filterNot { it == query.trim() }).take(20))) }
    fun clearSearches() { save(_state.value.copy(searches = emptyList())) }
    fun clear() { save(LibraryState()) }

    companion object {
        private fun videoJson(v: VideoResult) = JSONObject().put("id", v.id).put("title", v.title).put("channel", v.channel).put("thumbnail", v.thumbnailUrl).put("channelId", v.channelId).put("description", v.description).put("publishedAt", v.publishedAt).put("duration", v.duration).put("views", v.views).put("live", v.live).put("categoryId", v.categoryId)
        private fun video(j: JSONObject) = VideoResult(j.getString("id"), j.optString("title"), j.optString("channel"), j.optString("thumbnail").takeIf { it.isNotBlank() && it != "null" }, j.optString("channelId"), j.optString("description"), j.optString("publishedAt"), j.optString("duration"), j.optString("views"), j.optBoolean("live"), categoryId = j.optString("categoryId"))
        private fun array(videos: List<VideoResult>) = JSONArray().apply { videos.forEach { put(videoJson(it)) } }
        private fun videos(array: JSONArray?) = objects(array).mapNotNull { runCatching { video(it) }.getOrNull() }
        private fun objects(array: JSONArray?) = if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
        fun encode(state: LibraryState): String = JSONObject()
            .put("history", JSONArray().apply { state.history.forEach { put(JSONObject().put("video", videoJson(it.video)).put("position", it.positionSeconds).put("updated", it.updatedAt)) } })
            .put("watchLater", array(state.watchLater))
            .put("playlists", JSONArray().apply { state.playlists.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("videos", array(it.videos))) } })
            .put("searches", JSONArray(state.searches)).toString()
        fun decode(raw: String?): LibraryState = runCatching {
            val j = JSONObject(raw ?: "{}")
            LibraryState(objects(j.optJSONArray("history")).mapNotNull { runCatching { SavedVideo(video(it.getJSONObject("video")), it.optInt("position").coerceAtLeast(0), it.optLong("updated")) }.getOrNull() },
                videos(j.optJSONArray("watchLater")), objects(j.optJSONArray("playlists")).mapNotNull { runCatching { LocalPlaylist(it.getString("id"), it.getString("title"), videos(it.optJSONArray("videos"))) }.getOrNull() },
                j.optJSONArray("searches")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank) } ?: emptyList())
        }.getOrDefault(LibraryState())
    }
}
