package com.example.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.BuildConfig
import com.example.app.data.*
import com.example.app.domain.*
import com.example.app.playback.PlaybackManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SearchUiState(val request: FeedRequest = FeedRequest(), val videos: List<VideoResult> = emptyList(), val loading: Boolean = false, val message: String? = null, val nextToken: String? = null,
    val homeCursor: HomeCursor? = null, val heading: String = "Gợi ý cho bạn", val explanation: String? = null)
data class AccountLibraryState(val playlists: List<VideoPlaylist> = emptyList(), val nextToken: String? = null,
    val loading: Boolean = false, val loaded: Boolean = false, val error: String? = null)

class VideoViewModel(application: Application) : AndroidViewModel(application) {
    val playback = PlaybackManager(application)
    val settings = application.getSharedPreferences("settings", 0)
    val library = LocalLibrary(application)
    private var accessToken: String? = null
    private var tokenExpiresAt = 0L
    var grantedScopes: Set<String> = emptySet(); private set
    val repository = YouTubeDataRepository({ accessToken?.takeIf { System.currentTimeMillis() < tokenExpiresAt } }, { settings.getString("apiKey", null)?.takeIf(String::isNotBlank) ?: BuildConfig.YOUTUBE_API_KEY }, { accessToken = null; tokenExpiresAt = 0 }, publicSource = NewPipePublicSource())
    private val _account = MutableStateFlow<GoogleProfile?>(runCatching {
        if (!settings.getBoolean("reconnect", false)) null else
            org.json.JSONObject(settings.getString("accountProfile", "")!!).let {
                GoogleProfile(it.getString("id"), it.getString("email"), it.getString("name"), it.optString("picture").takeIf(String::isNotBlank))
            }
    }.getOrNull())
    val account = _account.asStateFlow()
    private val _youtubeChannel = MutableStateFlow<Channel?>(null)
    val youtubeChannel = _youtubeChannel.asStateFlow()
    private val _state = MutableStateFlow(SearchUiState())
    val state = _state.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private var feedJob: Job? = null
    private val feedCache = LinkedHashMap<FeedRequest, SearchUiState>()
    private val feedCachedAt = mutableMapOf<FeedRequest, Long>()
    private val diskFeeds = FeedDiskCache(File(application.cacheDir, "feeds"))
    private val diskHome = HomeDiskCache(File(application.filesDir, "home"))
    private var recommendationHistory = LocalLibrary(application, "recommendation_history_${_account.value?.id ?: "guest"}")
    private fun homeOwner() = _account.value?.id?.let { "account:$it" } ?: "guest"
    fun recordVideo(video: VideoResult, seconds: Int? = null) {
        if (recommendationHistory.state.value.history.firstOrNull()?.video?.id != video.id) {
            feedCachedAt.remove(FeedRequest())
        }
        library.record(video, seconds ?: library.state.value.history.firstOrNull { it.video.id == video.id }?.positionSeconds ?: 0)
        recommendationHistory.record(video, seconds ?: 0)
    }
    fun removeHistory(videoId: String? = null) {
        if (videoId == null) library.clearHistory() else library.removeHistory(videoId)
        val owners = settings.getStringSet("recommendationOwners", emptySet()).orEmpty() + "guest"
        owners.forEach { owner ->
            val history = LocalLibrary(getApplication(), "recommendation_history_$owner")
            if (videoId == null) { history.clearHistory(); history.clearSearches() } else history.removeHistory(videoId)
            diskHome.remove(if (owner == "guest") "guest" else "account:$owner")
            HomeDiskCache(File(getApplication<Application>().cacheDir, "home"))
                .remove(if (owner == "guest") "guest" else "account:$owner")
        }
        recommendationHistory = LocalLibrary(getApplication(), "recommendation_history_${_account.value?.id ?: "guest"}")
        if (_state.value.request.kind == FeedKind.Home) feedJob?.cancel()
        feedCache.remove(FeedRequest()); feedCachedAt.remove(FeedRequest())
        if (_state.value.request.kind == FeedKind.Home) _state.value = SearchUiState()
    }
    fun clearLocalData() { removeHistory(); library.clear() }
    private var sessionGeneration = 0
    private var accountDataGeneration = 0
    private var libraryJob: Job? = null
    private val _accountLibrary = MutableStateFlow(AccountLibraryState())
    val accountLibrary = _accountLibrary.asStateFlow()
    private var likedSeeds: List<VideoResult> = emptyList()
    private var subscriptionSeeds: List<Channel> = emptyList()
    private var signalsLoadedAt = 0L

    private fun migrateLegacySearches() {
        if (settings.getBoolean("homeSearchesMigrated", false)) return
        val owners = settings.getStringSet("recommendationOwners", emptySet()).orEmpty()
        if (!LocalLibrary.canImportLegacySearches(_account.value?.id, owners)) return
        recommendationHistory.importSearches(library.state.value.searches)
        settings.edit().putBoolean("homeSearchesMigrated", true).apply()
        diskHome.remove(homeOwner())
    }

    init { migrateLegacySearches(); load(FeedRequest()) }
    fun hasSession(write: Boolean = false) = accessToken != null && System.currentTimeMillis() < tokenExpiresAt && (!write || WRITE_SCOPE in grantedScopes)
    fun connect(token: String, scopes: Set<String>, onConnected: () -> Unit) {
        val generation = ++sessionGeneration
        viewModelScope.launch {
            try {
                val profile = repository.profile(token)
                if (generation != sessionGeneration) return@launch
                accessToken = token; tokenExpiresAt = System.currentTimeMillis() + 50 * 60 * 1000
                val accountChanged = _account.value?.id != profile.id
                grantedScopes = scopes; _account.value = profile; _youtubeChannel.value = null
                recommendationHistory = LocalLibrary(getApplication(), "recommendation_history_${profile.id}")
                settings.edit().putStringSet("recommendationOwners", settings.getStringSet("recommendationOwners", emptySet()).orEmpty() + profile.id).apply()
                migrateLegacySearches()
                if (accountChanged) {
                    resetAccountData()
                }
                settings.edit().putBoolean("reconnect", true).putString("accountProfile", org.json.JSONObject()
                    .put("id", profile.id).put("email", profile.email).put("name", profile.name)
                    .put("picture", profile.picture.orEmpty()).toString()).apply()
                if (_state.value.request.requiresAccount || (accountChanged && _state.value.request.kind == FeedKind.Home)) {
                    feedJob?.cancel()
                    _state.value = SearchUiState(request = _state.value.request)
                    load(_state.value.request)
                } else if (_state.value.videos.isEmpty()) {
                    load(_state.value.request)
                }
                loadAccountLibrary()
                onConnected()
                try {
                    val channel = repository.myChannel()
                    if (generation == sessionGeneration) _youtubeChannel.value = channel
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* A Google account can exist without a YouTube channel. */ }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notify(errorMessage(e)) }
        }
    }
    fun disconnect() {
        sessionGeneration++; accessToken = null; tokenExpiresAt = 0; grantedScopes = emptySet(); _account.value = null; _youtubeChannel.value = null
        resetAccountData()
        recommendationHistory = LocalLibrary(getApplication(), "recommendation_history_guest")
        settings.edit().putBoolean("reconnect", false).remove("accountProfile").apply()
        if (_state.value.request.requiresAccount || _state.value.request.kind == FeedKind.Home) {
            feedJob?.cancel()
            _state.value = SearchUiState()
            load(FeedRequest())
        }
    }
    private fun resetAccountData() {
        accountDataGeneration++
        feedCache.clear(); feedCachedAt.clear()
        libraryJob?.cancel(); _accountLibrary.value = AccountLibraryState()
        likedSeeds = emptyList(); subscriptionSeeds = emptyList(); signalsLoadedAt = 0
    }
    fun loadAccountLibrary(append: Boolean = false, refresh: Boolean = false) {
        val current = _accountLibrary.value
        if (_account.value == null || current.loading || (append && current.nextToken == null)) return
        if (!append && !refresh && current.loaded) return
        val generation = accountDataGeneration
        _accountLibrary.value = current.copy(loading = true, error = null)
        libraryJob = viewModelScope.launch {
            try {
                val page = repository.playlists(if (append) current.nextToken else null)
                if (generation != accountDataGeneration) return@launch
                _accountLibrary.value = AccountLibraryState(
                    ((if (append) current.playlists else emptyList()) + page.items).distinctBy { it.id },
                    page.nextToken, loaded = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == accountDataGeneration) _accountLibrary.value = current.copy(loading = false, error = errorMessage(e))
            }
        }
    }
    fun toggleLater(video: VideoResult) {
        library.toggleLater(video)
        notify(if (library.state.value.watchLater.any { it.id == video.id }) "Đã thêm vào hàng đợi xem sau trong Thư viện." else "Đã bỏ khỏi xem sau.")
    }
    fun hideVideo(video: VideoResult) {
        library.hide(video.id)
        if (_state.value.request.kind == FeedKind.Home) {
            _state.value = _state.value.copy(videos = _state.value.videos.filterNot { it.id == video.id })
        }
        feedCache.remove(FeedRequest()); feedCachedAt.remove(FeedRequest())
        notify("Đã ẩn video khỏi gợi ý.")
    }
    fun recoverFeed() {
        if (_state.value.loading || (_state.value.request.kind == FeedKind.Home && _state.value.videos.isNotEmpty())) return
        load(_state.value.request, refresh = _state.value.message != null)
    }
    fun notify(message: String?) { _notice.value = message }
    fun load(request: FeedRequest, refresh: Boolean = false) {
        feedJob?.cancel()
        cacheCurrentFeed()
        val previous = _state.value.takeIf { it.request == request && it.videos.isNotEmpty() }
        val cached = previous ?: feedCache[request]
        val fresh = System.currentTimeMillis() - (feedCachedAt[request] ?: 0) in 0 until 10 * 60_000L
        _state.value = cached?.copy(loading = true, message = null) ?: SearchUiState(request = request, loading = true)
        if (!refresh && cached != null && (request.kind == FeedKind.Home || (cached.message == null && fresh)) && !request.liveOnly && !request.requiresAccount) { _state.value = cached.copy(loading = false); return }
        feedJob = viewModelScope.launch {
            if (request.kind == FeedKind.Home) {
                // Refresh advances the existing interest sources without rotating topics.
                if (cached == null && !refresh) {
                    val owner = homeOwner()
                    val saved = withContext(Dispatchers.IO) {
                        diskHome.read(owner) ?: HomeDiskCache(File(getApplication<Application>().cacheDir, "home"))
                            .read(owner)?.also {
                                diskHome.write(owner, it.page)
                                HomeDiskCache(File(getApplication<Application>().cacheDir, "home")).remove(owner)
                            }
                    }
                    if (saved != null) {
                        _state.value = homeState(request, saved.page)
                        feedCachedAt[request] = saved.savedAt
                        return@launch
                    }
                }
                _state.value = _state.value.copy(loading = true)
                fetch(false, refresh)
                return@launch
            }
            if (cached == null && !refresh) {
                val saved = withContext(Dispatchers.IO) { diskFeeds.read(request) }
                if (saved != null) {
                    _state.value = SearchUiState(request = request, videos = saved.page.items, nextToken = saved.page.nextToken)
                    feedCachedAt[request] = saved.savedAt
                    if (saved.fresh) return@launch
                }
            }
            _state.value = _state.value.copy(loading = true)
            fetch(false)
        }
    }
    private fun cacheCurrentFeed() {
        val current = _state.value
        if (current.videos.isNotEmpty() && !current.loading) {
            feedCache[current.request] = current
            if (feedCache.size > 8) {
                val oldest = feedCache.keys.first()
                feedCache.remove(oldest); feedCachedAt.remove(oldest)
            }
        }
    }
    fun restore(request: FeedRequest) {
        if (_state.value.request == request) return
        feedJob?.cancel(); cacheCurrentFeed()
        val saved = feedCache[request]
        if (saved != null) _state.value = saved else load(request)
    }
    fun retry() {
        if (_state.value.loading) return
        if (_state.value.request.kind == FeedKind.Home) { load(_state.value.request, refresh = true); return }
        _state.value = _state.value.copy(loading = true, message = null)
        feedJob = viewModelScope.launch { fetch(_state.value.videos.isNotEmpty() && _state.value.nextToken != null) }
    }
    fun more() {
        if (_state.value.loading || _state.value.nextToken == null) return
        _state.value = _state.value.copy(loading = true, message = null)
        feedJob = viewModelScope.launch { fetch(true) }
    }
    private suspend fun fetch(append: Boolean, refreshHome: Boolean = false) {
        val current = _state.value
        try {
            // Give silent Google restoration time to finish before issuing token-only requests.
            if (current.request.requiresAccount && settings.getBoolean("reconnect", false) && !hasSession() &&
                settings.getString("apiKey", null).isNullOrBlank() && BuildConfig.YOUTUBE_API_KEY.isBlank()) {
                kotlinx.coroutines.withTimeoutOrNull(8_000) {
                    while (!hasSession()) kotlinx.coroutines.delay(100)
                }
                if (!hasSession()) {
                    _state.value = current.copy(loading = false,
                        message = "Chưa khôi phục được kết nối Google. Kiểm tra mạng hoặc kết nối lại trong Tài khoản.")
                    return
                }
            }
            if (current.request.kind == FeedKind.Home) {
                kotlinx.coroutines.withTimeout(30_000) { fetchHome(current, append, refreshHome) }
                return
            }
            val page = kotlinx.coroutines.withTimeout(30_000) { repository.feed(current.request, if (append) current.nextToken else null) }
            _state.value = current.copy(videos = ((if (append) current.videos else emptyList()) + page.items).distinctBy { it.playlistItemId.ifBlank { it.id } }, nextToken = page.nextToken, loading = false)
            feedCachedAt[current.request] = System.currentTimeMillis()
            val saved = _state.value
            withContext(Dispatchers.IO) { diskFeeds.write(current.request, Page(saved.videos, saved.nextToken)) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            _state.value = current.copy(loading = false, message = "Tải video quá lâu. Hãy thử lại.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.value = current.copy(loading = false, message = errorMessage(e)) }
    }
    private fun homeState(request: FeedRequest, page: HomePage) = SearchUiState(request = request,
        videos = page.items.filter { HomeRecommendations.eligible(it) && it.id !in library.state.value.hiddenIds }, homeCursor = page.next, nextToken = if (page.next != null) "home-more" else null,
        heading = if (_account.value != null) "Gợi ý cho ${_account.value!!.name}" else "Gợi ý cho bạn",
        explanation = if (_account.value != null) "Từ video đã xem trong app, video đã thích và kênh đăng ký."
            else "Dựa trên video đã xem trong app. Kết nối Google để thêm gợi ý từ kênh đăng ký.",
        message = if (page.partial) "Một số nguồn chưa tải được. Bạn có thể thử lại." else null)

    private suspend fun fetchHome(current: SearchUiState, append: Boolean, refresh: Boolean) {
        val owner = homeOwner()
        var signalsFailed = _account.value != null && !hasSession()
        if (!append && hasSession() && (refresh || System.currentTimeMillis() - signalsLoadedAt > 10 * 60_000L)) {
            try { likedSeeds = repository.feed(FeedRequest(FeedKind.Liked)).items }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { signalsFailed = true }
            try {
                val channels = mutableListOf<Channel>()
                var token: String? = null
                do {
                    val page = repository.subscriptions(token)
                    channels += page.items; token = page.nextToken
                } while (token != null && channels.size < 200)
                subscriptionSeeds = channels.distinctBy { it.id }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { signalsFailed = true }
            if (!signalsFailed) signalsLoadedAt = System.currentTimeMillis()
        }
        val history = recommendationHistory.state.value.history
        val base = if (append) current.homeCursor ?: return
            else if (refresh) current.homeCursor ?: HomeRecommendations.plan(history, likedSeeds, subscriptionSeeds, recommendationHistory.state.value.searches)
            else HomeRecommendations.plan(history, likedSeeds, subscriptionSeeds, recommendationHistory.state.value.searches)
        val cursor = base.copy(excludedIds = base.excludedIds + history.map { it.video.id } + library.state.value.hiddenIds +
            (if (refresh) current.videos.map { it.id } else emptyList()))
        var page = HomeRecommendations.load(cursor) { request, token -> repository.feed(request, token) }
        // Skip fully excluded pages, bounded to avoid draining API quota on a failed source.
        var skipped = 0
        while (page.items.isEmpty() && page.next != null && !page.partial && skipped++ < 3) {
            page = HomeRecommendations.load(page.next!!) { request, token -> repository.feed(request, token) }
        }
        if (page.items.isEmpty() && page.partial && current.videos.isNotEmpty()) {
            _state.value = current.copy(loading = false, message = "Chưa tải được gợi ý mới. Đã giữ danh sách hiện tại; hãy thử lại.")
            return
        }
        val combined = page.copy(items = ((if (append) current.videos else emptyList()) + page.items).distinctBy { it.id })
        _state.value = homeState(current.request, combined).let {
            if (signalsFailed) it.copy(message = "Chưa tải đủ dữ liệu tài khoản. Hãy thử lại hoặc kết nối lại Google.") else it
        }
        feedCachedAt[current.request] = System.currentTimeMillis()
        withContext(Dispatchers.IO) { diskHome.write(owner, combined.copy(partial = combined.partial || signalsFailed)) }
    }
    fun search(query: String, order: String = "relevance", duration: String = "any", live: Boolean = false) {
        library.search(query)
        if (query.isNotBlank()) {
            recommendationHistory.search(query)
            // Search signals take effect on the next explicit Home refresh.
        }
        load(FeedRequest(if (query.isBlank()) FeedKind.Home else FeedKind.Search, query.trim(), order = order, duration = duration, liveOnly = live))
    }
    fun saveApiKey(key: String) { settings.edit().putString("apiKey", key.trim()).apply(); load(_state.value.request, refresh = true) }
    override fun onCleared() { playback.release() }
    companion object {
        const val READ_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
        const val WRITE_SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl"
    }
}
fun errorMessage(error: Exception): String = when (error) {
    is PublicBrowseException -> error.message.orEmpty()
    is YouTubeApiException -> error.message.orEmpty()
    is java.io.IOException -> "Không thể kết nối. Kiểm tra mạng và thử lại."
    else -> "Không thể hoàn tất thao tác. Vui lòng thử lại."
}
data class SleepTimer(val remainingSeconds: Int = 0) {
    val active: Boolean get() = remainingSeconds > 0
    fun start(minutes: Int) = copy(remainingSeconds = minutes.coerceIn(1, 1440) * 60)
    fun tick() = copy(remainingSeconds = (remainingSeconds - 1).coerceAtLeast(0))
    fun cancel() = copy(remainingSeconds = 0)
}
