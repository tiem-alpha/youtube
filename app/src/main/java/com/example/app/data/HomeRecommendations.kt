package com.example.app.data

import com.example.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class HomeSource(val request: FeedRequest, val pageToken: String? = null)
data class HomeCursor(val sources: List<HomeSource>, val excludedIds: Set<String>)
data class HomePage(val items: List<VideoResult>, val next: HomeCursor?, val partial: Boolean)

/** A small, reproducible mix of interests; this is not YouTube's private Home feed. */
object HomeRecommendations {
    fun plan(history: List<SavedVideo>, liked: List<VideoResult>, subscriptions: List<Channel>): HomeCursor {
        val recent = history.sortedByDescending { it.updatedAt }.take(60)
        val seeds = recent.map { it.video } + liked.take(25)
        fun ranked(selector: (VideoResult) -> String): List<String> = seeds
            .mapIndexed { index, video -> selector(video) to (seeds.size - index) }
            .filter { it.first.isNotBlank() }.groupBy({ it.first }, { it.second })
            .entries.sortedByDescending { it.value.sum() }.map { it.key }
        val topics = seeds.map { it.title.substringBefore('|').substringBefore(" - ")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().split(Regex("\\s+"))
            .take(8).joinToString(" ") }
            .filter { it.split(' ').size >= 2 && it != "Video YouTube" }.distinct().take(2)
            .map { FeedRequest(FeedKind.Search, query = it) }
        val categories = ranked { it.categoryId }.take(2 - topics.size)
            .map { FeedRequest(FeedKind.Search, categoryId = it) }
        val recentChannels = ranked { it.channelId }.take(1)
        // Reserve subscription slots; heavily watched channels must not crowd them out.
        val subscribed = subscriptions.map { it.id }.filter { it.isNotBlank() && it !in recentChannels }
            .distinct().take(2)
        val channels = (recentChannels + subscribed).map { FeedRequest(FeedKind.Channel, resourceId = it) }
        val sources = (topics + categories + channels + FeedRequest()).distinct()
        return HomeCursor(sources.map(::HomeSource), history.map { it.video.id }.toSet() + liked.map { it.id })
    }

    suspend fun load(cursor: HomeCursor, fetch: suspend (FeedRequest, String?) -> Page<VideoResult>): HomePage = coroutineScope {
        val results = cursor.sources.map { source -> async {
            try { Result.success(fetch(source.request, source.pageToken)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure<Page<VideoResult>>(e) }
        } }.map { it.await() }
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        val lists = results.map { it.getOrNull()?.items.orEmpty() }
        val videos = buildList {
            for (index in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
                lists.forEach { list -> list.getOrNull(index)?.let { add(it) } }
            }
        }.distinctBy { it.id }.filterNot { it.id in cursor.excludedIds }
        val remaining = cursor.sources.mapIndexedNotNull { index, source ->
            if (results[index].isFailure) source
            else results[index].getOrNull()?.nextToken?.let { source.copy(pageToken = it) }
        }
        HomePage(videos, remaining.takeIf { it.isNotEmpty() }?.let {
            HomeCursor(it, cursor.excludedIds + videos.map { video -> video.id })
        }, results.any { it.isFailure })
    }
}
