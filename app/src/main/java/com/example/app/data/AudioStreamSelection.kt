package com.example.app.data

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod

data class ResolvedAudio(val url: String, val mimeType: String?)

/** Only accept standalone audio resources, never a mixed video manifest or video stream. */
internal fun selectAudioStream(streams: List<AudioStream>): ResolvedAudio {
    val selected = streams.filter {
        it.isUrl && it.content.startsWith("https://") &&
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
    }.sortedWith(compareBy<AudioStream> {
        when (it.audioTrackType) { AudioTrackType.ORIGINAL -> 0; null -> 1; else -> 2 }
    }.thenBy { if (it.averageBitrate in 1..160) 0 else 1 }
        .thenBy { kotlin.math.abs(it.averageBitrate - 128) }).firstOrNull()
        ?: throw PublicBrowseException("Video này chưa có luồng âm thanh riêng được hỗ trợ. Mở app để tiếp tục xem.")
    return ResolvedAudio(selected.content, selected.format?.mimeType)
}
