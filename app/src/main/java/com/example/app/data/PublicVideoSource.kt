package com.example.app.data

import com.example.app.domain.*

/** Public discovery has no access to the Google account's credentials. */
interface PublicVideoSource {
    suspend fun feed(request: FeedRequest, page: String? = null): Page<VideoResult>
    suspend fun channel(id: String): Channel
    suspend fun video(id: String): VideoResult
}

class PublicBrowseException(message: String, cause: Throwable? = null) : Exception(message, cause)
