package com.example.app.data

import com.example.app.domain.FeedRequest
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64

/** YouTube's search params protobuf. NewPipe 0.26.5 only exposes the content-type filter.
 * Apply the existing app's sort/duration/live controls to the initial search request;
 * subsequent requests use YouTube's continuation unchanged.
 */
internal object NewPipeSearchFilters {
    fun encode(request: FeedRequest): String {
        val filters = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x10, 0x01)) // video
            when (request.duration) {
                "short" -> write(byteArrayOf(0x18, 0x01))
                "long" -> write(byteArrayOf(0x18, 0x02))
                "medium" -> write(byteArrayOf(0x18, 0x03))
            }
            if (request.liveOnly) write(byteArrayOf(0x40, 0x01))
        }.toByteArray()
        val params = ByteArrayOutputStream().apply {
            when (request.order) {
                "date" -> write(byteArrayOf(0x08, 0x02))
                "viewCount" -> write(byteArrayOf(0x08, 0x03))
                "rating" -> write(byteArrayOf(0x08, 0x01))
            }
            write(0x12); write(filters.size); write(filters)
            write(byteArrayOf(0xf0.toByte(), 0x01, 0x01))
        }.toByteArray()
        return URLEncoder.encode(Base64.getUrlEncoder().encodeToString(params), "UTF-8")
    }
}
