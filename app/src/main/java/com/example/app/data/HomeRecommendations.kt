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
    fun plan(history: List<SavedVideo>, liked: List<VideoResult>, subscriptions: List<Channel>, searches: List<String> = emptyList()): HomeCursor {
        val recent = history.sortedByDescending { it.updatedAt }.take(60)
        val seeds = recent.map { it.video } + liked.take(25)
        fun ranked(selector: (VideoResult) -> String): List<String> = seeds
            .mapIndexed { index, video -> selector(video) to (seeds.size - index) }
            .filter { it.first.isNotBlank() }.groupBy({ it.first }, { it.second })
            .entries.sortedByDescending { it.value.sum() }.map { it.key }
        // A video title is not a topic: a comedy title about a family must not
        // become a global search for family dramas. Use explicit searches for
        // discovery and the actual watched channels for recent viewing interests.
        val topics = searches.map(String::trim).filter(String::isNotBlank)
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(3)
            .map { FeedRequest(FeedKind.Search, query = it) }
        val channelRanks = ranked { it.channelId }
        val recentChannels = (recent.take(1).map { it.video.channelId } + channelRanks)
            .filter(String::isNotBlank).distinct().take(2)
        val subscribed = subscriptions.map { it.id }.filter(String::isNotBlank).distinct()
            .sortedWith(compareBy<String> { channelRanks.indexOf(it).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE }
                .thenBy { it }).take(2)
        val preferred = subscribed.filter { it in channelRanks }
        val channelIds = (preferred + recentChannels + subscribed).distinct().take(3)
        fun channel(id: String) = FeedRequest(FeedKind.Channel, resourceId = id)
        // Explicit searches have reserved slots, ahead of unwatched subscriptions.
        val sources = (preferred.map(::channel) + topics + channelIds.filterNot { it in preferred }.map(::channel))
            .ifEmpty { ranked { it.categoryId }.take(2).map { FeedRequest(FeedKind.Search, categoryId = it) } }
            .ifEmpty { listOf(FeedRequest()) }
        return HomeCursor(sources.map(::HomeSource), history.map { it.video.id }.toSet() + liked.map { it.id })
    }

    fun eligible(video: VideoResult): Boolean = !video.isShort &&
        !Regex("(?i)(?:^|\\s)#shorts?\\b").containsMatchIn(video.title)

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
        }.distinctBy { it.id }.filter { eligible(it) && it.id !in cursor.excludedIds }
        val remaining = cursor.sources.mapIndexedNotNull { index, source ->
            if (results[index].isFailure) source
            else results[index].getOrNull()?.nextToken?.let { source.copy(pageToken = it) }
        }
        HomePage(videos, remaining.takeIf { it.isNotEmpty() }?.let {
            HomeCursor(it, cursor.excludedIds + videos.map { video -> video.id })
        }, results.any { it.isFailure })
    }
}
