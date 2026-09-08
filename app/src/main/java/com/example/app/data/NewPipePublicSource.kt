package com.example.app.data

import android.util.Log
import com.example.app.BuildConfig
import com.example.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.net.URI

class NewPipePublicSource : PublicVideoSource {
    private val service get() = ServiceList.YouTube

    override suspend fun feed(request: FeedRequest, page: String?): Page<VideoResult> {
        require(!request.requiresAccount)
        val filters = if (request.kind == FeedKind.Shorts) request.copy(duration = "short") else request
        return extract(if (request.kind in setOf(FeedKind.Search, FeedKind.Shorts)) NewPipeSearchFilters.encode(filters) else null) {
            val extractor: ListExtractor<*> = when (request.kind) {
                FeedKind.Channel -> service.getChannelTabExtractorFromId("channel/${request.resourceId}", ChannelTabs.VIDEOS)
                FeedKind.Home -> service.kioskList.defaultKioskExtractor
                else -> service.getSearchExtractor(searchQuery(request), listOf("videos"), "")
            }
            val next = page?.let { NewPipePageCodec.decode(request, it) }
            val result = if (next == null) {
                extractor.fetchPage()
                extractor.initialPage
            } else extractor.getPage(next)
            if (result.items.isEmpty() && result.errors.isNotEmpty()) throw result.errors.first()
            val videos = result.items.filterIsInstance<StreamInfoItem>().mapNotNull(::mapVideo)
            Page(videos, NewPipePageCodec.encode(request, result.nextPage))
        }
    }

    override suspend fun channel(id: String): Channel = extract {
        val extractor = service.getChannelExtractor("https://www.youtube.com/channel/$id")
        extractor.fetchPage()
        Channel(extractor.id, extractor.name, extractor.avatars.maxByOrNull { it.height }?.url,
            extractor.description, extractor.subscriberCount.takeIf { it >= 0 }?.toString().orEmpty())
    }

    // Metadata for pasted links without running the stream/signature extraction pipeline.
    // Existing playback continues through the app's managed YouTube web player.
    override suspend fun video(id: String): VideoResult =
        feed(FeedRequest(FeedKind.Search, query = id)).items.firstOrNull { it.id == id }
            ?: throw PublicBrowseException("Không tìm thấy video công khai này.")

    private suspend fun <T> extract(params: String? = null, block: () -> T): T = permits.withPermit {
        try {
            runInterruptible(Dispatchers.IO) {
                runtime.withSearchParams(params, block)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: PublicBrowseException) { throw e }
        catch (e: Exception) {
            if (BuildConfig.DEBUG) runCatching { Log.w("NewPipe", "Extraction failed: ${e.javaClass.simpleName}") }
            val message = when (e) {
                is ReCaptchaException -> "YouTube đang yêu cầu xác minh hoặc tạm giới hạn kết nối. Hãy thử lại sau."
                is ContentNotAvailableException -> "Nội dung không còn tồn tại hoặc không thể truy cập."
                is IOException -> "Không thể kết nối YouTube. Kiểm tra mạng và thử lại."
                is ExtractionException -> "Chưa đọc được dữ liệu YouTube. Hãy thử lại hoặc cập nhật ứng dụng."
                else -> "Không tải được dữ liệu YouTube. Hãy thử lại."
            }
            throw PublicBrowseException(message, e)
        }
    }

    companion object {
        private val permits = Semaphore(2)
        private val runtime by lazy {
            NewPipeDownloader().also { NewPipe.init(it, Localization("vi", "VN"), ContentCountry("VN")) }
        }
        internal fun mapVideo(item: StreamInfoItem): VideoResult? {
            val id = YouTubeLinks.videoId(item.url) ?: return null
            val channelId = runCatching { URI(item.uploaderUrl).path.substringAfter("/channel/", "") }.getOrDefault("")
            return VideoResult(id, item.name, item.uploaderName.orEmpty(), item.thumbnails.maxByOrNull { it.height }?.url,
                channelId = channelId, description = item.shortDescription.orEmpty(),
                publishedAt = item.uploadDate?.instant?.toString().orEmpty(),
                duration = item.duration.takeIf { it >= 0 }?.let { "PT${it}S" }.orEmpty(),
                views = item.viewCount.takeIf { it >= 0 }?.toString().orEmpty(),
                live = item.streamType in setOf(StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM),
                isShort = item.isShortFormContent || item.url.contains("/shorts/"))
        }
        internal fun searchQuery(request: FeedRequest): String {
            if (request.query.isNotBlank()) return request.query
            if (request.kind == FeedKind.Shorts) return "#shorts"
            return when (request.categoryId) {
                "10" -> "âm nhạc"
                "20" -> "gaming"
                "25" -> "tin tức"
                "27" -> "học tập"
                "28" -> "khoa học công nghệ"
                "17" -> "thể thao"
                else -> "video Việt Nam"
            }
        }
    }
}
