package com.example.app.data

import com.example.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CachedHome(val page: HomePage, val savedAt: Long, val fresh: Boolean)

/** Account namespace is part of the hashed key; never fall back to another account. */
class HomeDiskCache(directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val disk = BoundedDiskCache(directory, 4L * 1024 * 1024, clock)
    fun remove(owner: String) = disk.remove("home-v4-searches:$owner")
    fun read(owner: String): CachedHome? = runCatching {
        val raw = disk.read("home-v4-searches:$owner", Long.MAX_VALUE) ?: return null
        val json = JSONObject(raw.toString(Charsets.UTF_8))
        val cursor = json.optJSONObject("cursor")?.let { c ->
            val sources = c.getJSONArray("sources")
            val excluded = c.getJSONArray("excluded")
            HomeCursor((0 until sources.length()).map { index ->
                val s = sources.getJSONObject(index)
                HomeSource(FeedRequest(kind = FeedKind.valueOf(s.getString("kind")), query = s.optString("query"),
                    resourceId = s.optString("resource"), categoryId = s.optString("category")),
                    s.optString("token").takeIf(String::isNotBlank))
            }, (0 until excluded.length()).map { excluded.getString(it) }.toSet())
        }
        val savedAt = json.getLong("savedAt")
        CachedHome(HomePage(LocalLibrary.decode(json.getString("videos")).watchLater, cursor, json.optBoolean("partial")),
            savedAt, clock() - savedAt in 0 until 10 * 60_000L)
    }.getOrNull()

    fun write(owner: String, page: HomePage) {
        val cursor = page.next?.let { c -> JSONObject()
            .put("sources", JSONArray().apply { c.sources.forEach { s -> put(JSONObject()
                .put("kind", s.request.kind.name).put("query", s.request.query)
                .put("resource", s.request.resourceId).put("category", s.request.categoryId)
                .put("token", s.pageToken.orEmpty())) } })
            .put("excluded", JSONArray(c.excludedIds.toList())) }
        disk.write("home-v4-searches:$owner", JSONObject().put("savedAt", clock())
            .put("videos", LocalLibrary.encode(LibraryState(watchLater = page.items)))
            .put("partial", page.partial).put("cursor", cursor).toString().toByteArray(Charsets.UTF_8))
    }
}
