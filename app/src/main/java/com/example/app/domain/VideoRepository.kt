package com.example.app.domain

data class VideoResult(val id: String, val title: String, val channel: String, val thumbnailUrl: String?)

sealed interface SearchResult {
    data class Success(val videos: List<VideoResult>) : SearchResult
    data object ProviderNotConfigured : SearchResult
    data class Failure(val message: String) : SearchResult
}

interface VideoRepository { suspend fun search(query: String): SearchResult }
