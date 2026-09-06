package com.example.app.data

import com.example.app.domain.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

data class CachedFeed(val page: Page<VideoResult>, val fresh: Boolean, val savedAt: Long)

class FeedDiskCache(directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val disk = BoundedDiskCache(directory, 4L * 1024 * 1024, clock)
    private fun key(request: FeedRequest) = "feed-v1:" + JSONArray(listOf(request.kind.name, request.query,
        request.resourceId, request.order, request.duration, request.liveOnly, request.categoryId)).toString()
    fun read(request: FeedRequest): CachedFeed? {
        if (request.requiresAccount || request.liveOnly || request.kind == FeedKind.Home) return null
        return runCatching {
            val raw = disk.read(key(request), 24 * 60 * 60_000L) ?: return null
            val json = JSONObject(raw.toString(Charsets.UTF_8))
            val videos = LocalLibrary.decode(json.getString("library")).watchLater
            CachedFeed(Page(videos, json.optString("next").takeIf { it.isNotBlank() }),
                clock() - json.getLong("savedAt") in 0 until 10 * 60_000L, json.getLong("savedAt"))
        }.getOrNull()
    }
    fun write(request: FeedRequest, page: Page<VideoResult>) {
        if (request.requiresAccount || request.liveOnly || request.kind == FeedKind.Home) return
        disk.write(key(request), JSONObject().put("savedAt", clock())
            .put("library", LocalLibrary.encode(LibraryState(watchLater = page.items)))
            .put("next", page.nextToken.orEmpty()).toString().toByteArray(Charsets.UTF_8))
    }
}
