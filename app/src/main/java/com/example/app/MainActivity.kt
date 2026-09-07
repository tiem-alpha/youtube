package com.example.app

import android.speech.RecognizerIntent
import android.os.SystemClock
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import com.example.app.playback.WebPlaybackService
import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import org.json.JSONArray
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.app.domain.*
import com.example.app.ui.*
import com.example.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<String?>(null)
    private var inPip by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); incoming = if (savedInstanceState == null) extractLink(intent) else null
        setContent { VideoApp(incoming, inPip, consumed = { incoming = null }) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming = extractLink(intent) }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig); inPip = isInPictureInPictureMode
    }
    private fun extractLink(intent: Intent?): String? = intent?.dataString ?: intent?.getStringExtra(Intent.EXTRA_TEXT)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoApp(incoming: String?, inPip: Boolean, consumed: () -> Unit, vm: VideoViewModel = viewModel()) {
    var dark by remember { mutableStateOf(vm.settings.getBoolean("dark", false)) }
    AppTheme(darkTheme = dark, dynamicColor = false) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val state by vm.state.collectAsState()
        val library by vm.library.state.collectAsState()
        val account by vm.account.collectAsState()
        val youtubeChannel by vm.youtubeChannel.collectAsState()
        val notice by vm.notice.collectAsState()
        val auth = rememberYouTubeAuthorization(vm)
        val recovery = rememberRecoveryTrigger()
        LaunchedEffect(recovery) { vm.recoverFeed() }
        var route by rememberSaveable { mutableStateOf("home") }
        var backStack by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        val screenStates = rememberSaveableStateHolder()
        var activeVideo by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<VideoResult?, String>(
            save = { listOf(it?.id.orEmpty()) },
            restore = { saved -> saved.first().takeIf { it.isNotBlank() }?.let { id ->
                vm.library.state.value.history.firstOrNull { it.video.id == id }?.video ?: VideoResult(id, "Video YouTube", "YouTube", null)
            } }
        )) { mutableStateOf<VideoResult?>(null) }
        var showSearch by rememberSaveable { mutableStateOf(false) }
        var showTimer by remember { mutableStateOf(false) }
        val timerDeadline by WebPlaybackService.sleepDeadline.collectAsState()
        var remaining by remember { mutableStateOf(0L) }
        LaunchedEffect(timerDeadline) {
            while (timerDeadline > 0) {
                remaining = ((timerDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
                delay(1000)
            }
            remaining = 0
        }
        var selectedId by rememberSaveable { mutableStateOf("") }
        var channelId by rememberSaveable { mutableStateOf("") }
        var playlistId by rememberSaveable { mutableStateOf("") }
        var playlistTitle by rememberSaveable { mutableStateOf("") }
        var remotePlaylist by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var showLink by rememberSaveable { mutableStateOf(false) }
        var showAccount by rememberSaveable { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        fun push() {
            val r = vm.state.value.request
            val snapshot = JSONArray(listOf(route, selectedId, channelId, playlistId, playlistTitle, remotePlaylist, r.kind.name, r.query, r.resourceId, r.order, r.duration, r.liveOnly)).toString()
            backStack = ArrayList((backStack + snapshot).takeLast(20))
        }
        fun openVideo(video: VideoResult) {
            if (route != "watch") push()
            vm.recordVideo(video); vm.playback.pause(); activeVideo = video; selectedId = video.id; route = "watch"
        }
        fun openChannel(id: String) {
            push()
            channelId = id; route = "channel"; vm.load(FeedRequest(FeedKind.Channel, resourceId = id))
        }
        fun navigate(destination: String) {
            backStack = arrayListOf()
            route = destination
            when (destination) {
                "home" -> vm.load(FeedRequest())
                "shorts" -> { activeVideo = null; vm.playback.pause(); vm.load(FeedRequest(FeedKind.Shorts)) }
            }
        }
        fun back() {
            val raw = backStack.lastOrNull()
            if (raw == null) navigate("home") else {
                val saved = JSONArray(raw)
                backStack = ArrayList(backStack.dropLast(1))
                route = saved.getString(0).let { if (it == "watch" && activeVideo == null) "home" else it }; channelId = saved.getString(2)
                playlistId = saved.getString(3); playlistTitle = saved.getString(4); remotePlaylist = saved.getBoolean(5)
                vm.restore(FeedRequest(FeedKind.valueOf(saved.getString(6)), saved.getString(7), saved.getString(8), saved.getString(9), saved.getString(10), saved.getBoolean(11)))
            }
        }
        fun submitSearch(term: String) {
            query = term
            if (route == "watch") back()
            route = "home"
            showSearch = false
            vm.search(term)
        }
        val voiceSearch = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::submitSearch)
            }
        }
        BackHandler(route != "home") { back() }
        LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.notify(null) } }
        LaunchedEffect(incoming) {
            incoming?.let { input ->
                val id = YouTubeLinks.videoId(input)
                if (id == null) vm.notify("Liên kết YouTube không hợp lệ.")
                else openVideo(VideoResult(id, "Video YouTube", "YouTube", "https://i.ytimg.com/vi/$id/hqdefault.jpg"))
                consumed()
            }
        }
        if (inPip) LocalVideoSurface(vm.playback, Modifier.fillMaxSize()) else Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(title = { if (showSearch) OutlinedTextField(query, { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("Tìm trên YouTube") },
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submitSearch(query) }))
                    else Text(when(route) { "watch" -> "Đang xem"; "channel" -> "Kênh YouTube"; "playlist" -> playlistTitle; "settings" -> "Cài đặt"; else -> "Video Companion" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { if (route in listOf("watch", "channel", "playlist", "settings")) IconButton({ back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } },
                    actions = {
                        if (showSearch) {
                            IconButton({
                                try { voiceSearch.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói nội dung muốn tìm")) }
                                catch (_: android.content.ActivityNotFoundException) { vm.notify("Thiết bị chưa có dịch vụ nhận dạng giọng nói.") }
                            }) { Icon(Icons.Default.Mic, "Tìm bằng giọng nói") }
                            IconButton({ showSearch = false }) { Icon(Icons.Default.Close, "Đóng tìm kiếm") }
                        } else {
                        IconButton({ showSearch = true }) { Icon(Icons.Default.Search, "Tìm kiếm") }
                        IconButton({ showLink = true }) { Icon(Icons.Default.Link, "Mở liên kết YouTube") }
                        IconButton({ showAccount = true }) { if (youtubeChannel?.thumbnail != null || account?.picture != null) RemoteImage(youtubeChannel?.thumbnail ?: account?.picture, Modifier.size(30.dp)) else Icon(Icons.Default.AccountCircle, "Tài khoản") }
                        IconButton({ if (route != "settings") { push(); route = "settings" } }) { Icon(Icons.Default.Settings, "Cài đặt") }
                        }
                    })
            },
            bottomBar = {
                if (route !in listOf("watch", "channel", "playlist")) NavigationBar {
                    listOf(Triple("home", "Trang chủ", Icons.Default.Home), Triple("shorts", "Video ngắn", Icons.Default.SmartDisplay),
                        Triple("subscriptions", "Đăng ký", Icons.Default.Subscriptions), Triple("library", "Thư viện", Icons.Default.VideoLibrary),
                        Triple("local", "Trên máy", Icons.Default.Folder)).forEach { (id, title, icon) ->
                        NavigationBarItem(route == id, { navigate(id) }, { Icon(icon, title) }, label = { Text(title, maxLines = 1) })
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
            val modifier = Modifier.fillMaxSize().padding(
                top = if (activeVideo != null && route == "watch") 284.dp else 0.dp,
                bottom = if (activeVideo != null && route != "watch") 90.dp else 0.dp)
            screenStates.SaveableStateProvider(route) {
            when (route) {
                "home" -> Column(modifier) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.request.kind == FeedKind.Home) "Dành cho bạn" else state.request.query, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        TextButton({ vm.load(state.request, refresh = true) }, enabled = !state.loading) { Text("Làm mới") }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Tất cả" to "", "Âm nhạc" to "âm nhạc", "Trò chơi" to "gaming", "Học tập" to "học tập", "Tin tức" to "tin tức").forEach { (title, term) ->
                            FilterChip(state.request.query == term, { query = term; vm.search(term) }, { Text(title) })
                        }
                        FilterChip(state.request.liveOnly, { vm.search(query.ifBlank { "trực tiếp" }, live = !state.request.liveOnly) }, { Text("Trực tiếp") })
                    }
                    if (query.isNotBlank()) Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(state.request.order == "date", { vm.search(query, if (state.request.order == "date") "relevance" else "date", state.request.duration) }, { Text("Mới nhất") })
                        FilterChip(state.request.duration == "short", { vm.search(query, state.request.order, if (state.request.duration == "short") "any" else "short") }, { Text("Dưới 4 phút") })
                    }
                    if (state.videos.isEmpty() && !state.loading && library.searches.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        library.searches.take(5).forEach { term -> TextButton({ query = term; vm.search(term) }) { Text(term) } }
                    }
                    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                        isRefreshing = state.loading,
                        onRefresh = { if (!state.loading) vm.load(state.request, refresh = true) },
                        modifier = Modifier.weight(1f)
                    ) {
                        FeedList(state, Modifier.fillMaxSize(), vm::more, vm::retry, ::openVideo, vm::toggleLater)
                    }
                }
                "shorts" -> ShortsScreen(vm, modifier, ::openVideo)
                "subscriptions" -> SubscriptionsScreen(vm, auth, modifier, ::openChannel)
                "library" -> LibraryScreen(vm, auth, modifier, ::openVideo) { id, title, remote ->
                    push()
                    playlistId = id; playlistTitle = title; remotePlaylist = remote; route = "playlist"
                    if (remote) auth(false) { vm.load(FeedRequest(FeedKind.Playlist, resourceId = id)) }
                }
                "playlist" -> if (remotePlaylist) RemotePlaylistScreen(vm, auth, playlistId, playlistTitle, modifier, ::openVideo, { playlistTitle = it }, ::back)
                    else LocalPlaylistScreen(vm, playlistId, modifier, ::openVideo, ::back)
                "channel" -> ChannelScreen(vm, auth, channelId, modifier, ::openVideo)
                "watch" -> {
                    val video = library.history.firstOrNull { it.video.id == selectedId }?.video ?: VideoResult(selectedId, "Video YouTube", "YouTube", null)
                    val index = state.videos.indexOfFirst { it.id == selectedId }
                    key(selectedId) { WatchScreen(vm, auth, video, modifier, ::openChannel, if (index >= 0) state.videos.getOrNull(index + 1) else null, ::openVideo) }
                }
                "local" -> { LaunchedEffect(Unit) { activeVideo = null }; LocalScreen(vm, modifier) }
                "settings" -> SettingsScreen(vm, dark, { dark = it; vm.settings.edit().putBoolean("dark", it).apply() }, modifier) { showAccount = true }
            }
            }
            activeVideo?.let { initial ->
                val video = library.history.firstOrNull { it.video.id == initial.id }?.video ?: initial
                val expanded = route == "watch"
                var playerDrag by remember(initial.id, expanded) { mutableFloatStateOf(0f) }
                val threshold = with(LocalDensity.current) { 48.dp.toPx() }
                Surface(Modifier.align(if (expanded) Alignment.TopCenter else Alignment.BottomCenter).offset { androidx.compose.ui.unit.IntOffset(0, playerDrag.toInt()) }.fillMaxWidth(),
                    tonalElevation = 3.dp, shadowElevation = 4.dp) {
                    androidx.compose.ui.layout.Layout(content = {
                        Row(Modifier.fillMaxWidth().height(if (expanded) 48.dp else 90.dp)
                            .clickable(enabled = !expanded) { push(); route = "watch" }.pointerInput(expanded) {
                            var drag = 0f
                            detectVerticalDragGestures(onDragStart = { drag = 0f },
                                onVerticalDrag = { change, amount -> change.consume(); drag += amount },
                                onDragEnd = { if (expanded && drag > threshold) back() else if (!expanded && drag < -threshold) { push(); route = "watch" } })
                        }, verticalAlignment = Alignment.CenterVertically) {
                            if (expanded) IconButton({ back() }) {
                                Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, if (expanded) "Thu nhỏ video" else "Mở rộng video")
                            }
                            Text(video.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            if (expanded && remaining > 0) Text("${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall)
                            IconButton({ vm.toggleLater(video) }) {
                                Icon(if (library.watchLater.any { it.id == video.id }) Icons.Default.Bookmark else Icons.Default.BookmarkAdd,
                                    if (library.watchLater.any { it.id == video.id }) "Bỏ khỏi xem sau" else "Thêm vào hàng đợi xem sau")
                            }
                            if (expanded) IconButton({ showTimer = true }) { Icon(Icons.Default.Settings, "Cài đặt video") }
                            IconButton({ activeVideo = null; WebPlaybackService.setSleepTimer(context, 0); if (expanded) back() }) { Icon(Icons.Default.Close, "Đóng video") }
                        }
                        key(video.id) {
                            val start = remember { library.history.firstOrNull { it.video.id == video.id }?.positionSeconds ?: 0 }
                            YouTubePlayer(video.id, start, Modifier.fillMaxWidth().height(if (expanded) 236.dp else 90.dp),
                                onProgress = { vm.recordVideo(video, it) },
                                onEnded = {
                                    vm.recordVideo(video, 0)
                                    val index = state.videos.indexOfFirst { it.id == video.id }
                                    if (vm.settings.getBoolean("autoplay", false) && index >= 0) state.videos.getOrNull(index + 1)?.let(::openVideo)
                                }, backgroundPlayback = true, title = video.title,
                                onMinimize = if (expanded) ({ back() }) else null,
                                onDrag = { playerDrag = it },
                                onExpand = if (!expanded) ({ push(); route = "watch" }) else null)
                        }
                    }) { children, constraints ->
                        val width = constraints.maxWidth
                        val videoWidth = if (expanded) width else minOf(160.dp.roundToPx(), width / 2)
                        val header = children[0].measure(androidx.compose.ui.unit.Constraints.fixed(
                            if (expanded) width else width - videoWidth, (if (expanded) 48.dp else 90.dp).roundToPx()))
                        val player = children[1].measure(androidx.compose.ui.unit.Constraints.fixed(
                            videoWidth, (if (expanded) 236.dp else 90.dp).roundToPx()))
                        layout(width, if (expanded) header.height + player.height else player.height) {
                            header.placeRelative(if (expanded) 0 else videoWidth, 0)
                            player.placeRelative(0, if (expanded) header.height else 0)
                        }
                    }
                }
            }
            }
        }
        if (showTimer) AlertDialog(onDismissRequest = { showTimer = false }, title = { Text("Cài đặt video") },
            text = { Column {
                Text("Hẹn giờ tắt · Duration", style = MaterialTheme.typography.titleMedium)
                Text(if (remaining > 0) "Còn ${remaining / 60} phút ${remaining % 60} giây" else "Chọn thời gian tự dừng phát")
                listOf(30, 60, 90, 120).forEach { minutes ->
                    TextButton({ WebPlaybackService.setSleepTimer(context, minutes); showTimer = false }) { Text("$minutes phút") }
                }
            } }, confirmButton = { TextButton({ showTimer = false }) { Text("Đóng") } },
            dismissButton = { TextButton({ WebPlaybackService.setSleepTimer(context, 0); showTimer = false }) { Text("Hủy hẹn giờ") } })
        if (showLink) {
            var link by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { showLink = false }, title = { Text("Mở video YouTube") },
                text = { OutlinedTextField(link, { link = it }, label = { Text("Liên kết hoặc ID video") }, supportingText = { Text("Hỗ trợ youtube.com, youtu.be và Shorts.") }) },
                confirmButton = { TextButton({
                    val id = YouTubeLinks.videoId(link)
                    if (id == null) vm.notify("Liên kết YouTube không hợp lệ.")
                    else { showLink = false; openVideo(VideoResult(id, "Video YouTube", "YouTube", "https://i.ytimg.com/vi/$id/hqdefault.jpg")) }
                }) { Text("Mở") } }, dismissButton = { TextButton({ showLink = false }) { Text("Hủy") } })
        }
        if (showAccount) AccountDialog(vm, auth) { showAccount = false }
    }
}

@Composable
fun FeedList(state: SearchUiState, modifier: Modifier, more: () -> Unit, retry: () -> Unit, open: (VideoResult) -> Unit, later: (VideoResult) -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (state.request.kind == FeedKind.Home) item {
            Text(state.heading, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)
            state.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (state.loading && state.videos.isNotEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(state.videos, key = { it.playlistItemId.ifBlank { it.id } }) { video -> VideoCard(video, { open(video) }) { later(video) } }
        if (state.loading) item { Loading() }
        state.message?.let { message -> item { MessageCard(message, "Thử lại", retry) } }
        if (!state.loading && state.message == null && state.videos.isEmpty()) item { MessageCard("Chưa có video phù hợp.") }
        if (!state.loading && state.nextToken != null) item { OutlinedButton(more, Modifier.fillMaxWidth()) { Text("Tải thêm") } }
    }
}
@Composable
fun VideoCard(video: VideoResult, open: () -> Unit, later: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().clickable(onClick = open), shape = RoundedCornerShape(0.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        RemoteImage(video.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9))
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(androidx.core.text.HtmlCompat.fromHtml(video.title, 0).toString(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(video.channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val detail = listOfNotNull(video.views.takeIf(String::isNotBlank)?.let { "$it lượt xem" }, video.publishedAt.take(10).takeIf(String::isNotBlank), if (video.live) "TRỰC TIẾP" else null).joinToString(" · ")
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.labelSmall)
            }
            if (later != null) IconButton(later) { Icon(Icons.Default.BookmarkAdd, "Thêm hoặc bỏ xem sau trên máy") }
        }
    }
}
@Composable fun Loading() { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
@Composable fun MessageCard(message: String, action: String? = null, onAction: () -> Unit = {}) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(message); if (action != null) TextButton(onAction) { Text(action) } } }
}
@Composable
private fun SettingsScreen(vm: VideoViewModel, dark: Boolean, changeDark: (Boolean) -> Unit, modifier: Modifier, account: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var key by remember { mutableStateOf(vm.settings.getString("apiKey", "").orEmpty()) }
    var clear by remember { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Trải nghiệm", style = MaterialTheme.typography.titleLarge) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Giao diện tối", Modifier.weight(1f)); Switch(dark, changeDark) } }
        item { OutlinedButton(account, Modifier.fillMaxWidth()) { Text("Quản lý tài khoản Google / YouTube") } }
        item { Text("Kết nối dữ liệu", style = MaterialTheme.typography.titleLarge) }
        item { Text("Có thể duyệt bằng tài khoản Google hoặc API key của bạn. Mở liên kết YouTube trực tiếp không cần API key.") }
        item { OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("YouTube Data API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }
        item { Button({ vm.saveApiKey(key); vm.notify("Đã lưu cấu hình kết nối.") }) { Text("Lưu kết nối") } }
        item { Text("Dữ liệu trên thiết bị", style = MaterialTheme.typography.titleLarge) }
        item { OutlinedButton({ clear = true }) { Text("Xóa lịch sử, xem sau và playlist trên máy") } }
        item { Text("Ứng dụng không chèn quảng cáo riêng. Video YouTube dùng trình phát của YouTube và có thể chứa quảng cáo. File trên máy và URL media riêng không bị chèn quảng cáo.") }
        item { TextButton({ openExternal(context, "https://www.youtube.com/t/terms") }) { Text("Điều khoản YouTube") } }
        item { TextButton({ openExternal(context, "https://policies.google.com/privacy") }) { Text("Quyền riêng tư của Google") } }
        item { Text("Lịch sử, xem sau, playlist và tìm kiếm trên máy được lưu cục bộ. Trình phát, ảnh và API gửi yêu cầu trực tiếp đến Google/YouTube. Token truy cập chỉ giữ trong bộ nhớ ứng dụng; không lưu mật khẩu. Các thao tác thích, đăng ký và đăng bình luận chỉ gửi khi bạn chọn.") }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Xóa dữ liệu trên máy?") }, text = { Text("Lịch sử, xem sau, playlist và tìm kiếm đã lưu trên thiết bị sẽ bị xóa. Dữ liệu trên tài khoản YouTube không bị ảnh hưởng.") }, confirmButton = { TextButton({ vm.clearLocalData(); clear = false }) { Text("Xóa") } }, dismissButton = { TextButton({ clear = false }) { Text("Hủy") } })
}
