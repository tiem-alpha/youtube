package com.example.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.app.*
import com.example.app.domain.*
import com.example.app.data.YouTubeApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun needsCommentAuthorization(error: Exception, vm: VideoViewModel): Boolean =
    error is YouTubeApiException && !vm.hasSession(true) &&
        (error.status == 401 || error.reason in setOf("notConfigured", "insufficientPermissions", "forbidden"))

@Composable
private fun CommentAuthorizationCard(auth: Authorize, reload: () -> Unit) {
    MessageCard(
        "Cần cấp thêm quyền YouTube để đọc bình luận bằng tài khoản. Google gộp quyền này với quyền xem, sửa và xóa video, đánh giá, bình luận và phụ đề.",
        "Cấp quyền YouTube", { auth(true, reload) }
    )
}

@Composable
fun WatchScreen(vm: VideoViewModel, auth: Authorize, initial: VideoResult, modifier: Modifier, channel: (String) -> Unit, nextVideo: VideoResult?, open: (VideoResult) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by vm.account.collectAsState()
    val youtubeChannel by vm.youtubeChannel.collectAsState()
    val library by vm.library.state.collectAsState()
    var video by remember(initial.id) { mutableStateOf(initial) }
    var videoChannel by remember(initial.id) { mutableStateOf<Channel?>(null) }
    var comments by remember(initial.id) { mutableStateOf<List<VideoComment>>(emptyList()) }
    var next by remember(initial.id) { mutableStateOf<String?>(null) }
    var commentError by remember(initial.id) { mutableStateOf<String?>(null) }
    var commentAuthorizationRequired by remember(initial.id) { mutableStateOf(false) }
    var commentsLoading by remember(initial.id) { mutableStateOf(false) }
    var detailError by remember(initial.id) { mutableStateOf<String?>(null) }
    var rating by remember(initial.id, account?.id) { mutableStateOf("none") }
    var busy by remember { mutableStateOf(false) }
    var draft by remember(initial.id) { mutableStateOf("") }
    var addPlaylist by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var autoplay by remember { mutableStateOf(vm.settings.getBoolean("autoplay", false)) }
    var related by remember(initial.id) { mutableStateOf<List<VideoResult>>(emptyList()) }
    var relatedLoading by remember(initial.id) { mutableStateOf(false) }
    var relatedError by remember(initial.id) { mutableStateOf<String?>(null) }
    var relatedRetry by remember(initial.id) { mutableIntStateOf(0) }
    var replyTo by remember(initial.id) { mutableStateOf<VideoComment?>(null) }
    var commentsOnly by remember(initial.id) { mutableStateOf(false) }
    BackHandler(commentsOnly) { commentsOnly = false }
    val contentState = remember(commentsOnly) { LazyListState() }
    suspend fun fetchComments(append: Boolean) {
        if (commentsLoading) return
        commentsLoading = true; commentError = null; commentAuthorizationRequired = false
        try { val page = vm.repository.comments(initial.id, if (append) next else null); comments = ((if (append) comments else emptyList()) + page.items).distinctBy { it.id }; next = page.nextToken }
        catch (e: CancellationException) { throw e } catch (e: Exception) {
            commentError = errorMessage(e)
            commentAuthorizationRequired = needsCommentAuthorization(e, vm)
        }
        finally { commentsLoading = false }
    }
    fun loadComments(append: Boolean) { scope.launch { fetchComments(append) } }
    fun rate(value: String) {
        val target = if (rating == value) "none" else value
        auth(true) { scope.launch {
            busy = true
            try { vm.repository.rate(video.id, target); rating = target }
            catch (e: CancellationException) { throw e } catch (e: Exception) { vm.notify(errorMessage(e)) } finally { busy = false }
        } }
    }
    LaunchedEffect(initial.id, account?.id) {
        try { video = vm.repository.video(initial.id); vm.recordVideo(video); detailError = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { detailError = errorMessage(e) }
        if (vm.hasSession()) try { rating = vm.repository.rating(initial.id) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    LaunchedEffect(initial.id, account?.id) { fetchComments(false) }
    LaunchedEffect(video.channelId) {
        videoChannel = null
        if (video.channelId.isNotBlank()) try { videoChannel = vm.repository.channel(video.channelId) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    LaunchedEffect(video.title, account?.id, relatedRetry) {
        if (video.title.isBlank() || video.title == "Video YouTube") return@LaunchedEffect
        relatedLoading = true; relatedError = null
        try {
            related = vm.repository.feed(FeedRequest(FeedKind.Search, query = video.title.take(100))).items.filterNot { it.id == video.id }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { relatedError = errorMessage(e) }
        finally { relatedLoading = false }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!commentsOnly, { commentsOnly = false }, { Text("Thông tin") })
            FilterChip(commentsOnly, { commentsOnly = true }, { Text("Bình luận") })
        }
        LazyColumn(Modifier.weight(1f), state = contentState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!commentsOnly) {
                item { Text(video.title, style = MaterialTheme.typography.titleLarge) }
                item { TextButton({ channel(video.channelId) }, enabled = video.channelId.isNotBlank(), contentPadding = PaddingValues(0.dp)) {
                    if (videoChannel?.thumbnail != null) RemoteImage(videoChannel?.thumbnail, Modifier.size(40.dp).clip(CircleShape))
                    else Icon(Icons.Default.AccountCircle, "Avatar kênh", Modifier.size(40.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(videoChannel?.title ?: video.channel, style = MaterialTheme.typography.titleSmall)
                } }
                if (nextVideo != null) item { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton({ open(nextVideo) }, Modifier.weight(1f)) { Text("Video tiếp theo", maxLines = 1) }
                    Text("Tự phát"); Switch(autoplay, { autoplay = it; vm.settings.edit().putBoolean("autoplay", it).apply() })
                } }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(rating == "like", { rate("like") }, { Text("Thích") }, enabled = !busy)
                    FilterChip(rating == "dislike", { rate("dislike") }, { Text("Không thích") }, enabled = !busy)
                    FilterChip(library.watchLater.any { it.id == video.id }, { vm.library.toggleLater(video) }, { Text("Xem sau · máy") })
                    AssistChip({ vm.hideVideo(video) }, { Text("Ẩn video") })
                    AssistChip({ addPlaylist = true }, { Text("Lưu playlist") })
                    AssistChip({ shareVideo(context, video.id) }, { Text("Chia sẻ") })
                    AssistChip({ openExternal(context, "https://www.youtube.com/watch?v=${video.id}") }, { Text("Mở YouTube") })
                } }
                if (video.views.isNotBlank() || video.publishedAt.isNotBlank()) item { Text(listOf(video.views.takeIf(String::isNotBlank)?.plus(" lượt xem"), video.publishedAt.take(10).takeIf(String::isNotBlank)).filterNotNull().joinToString(" · ")) }
                if (video.description.isNotBlank()) item { Text(video.description, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis); TextButton({ expanded = !expanded }) { Text(if (expanded) "Thu gọn" else "Xem mô tả") } }
                detailError?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Bình luận", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton({ commentsOnly = !commentsOnly }) { Text(if (commentsOnly) "Thu gọn" else "Xem thêm bình luận") }
            } }
            if (commentsOnly) item {
                if (account == null) TextButton({ auth(false) {} }) { Text("Kết nối Google để bình luận") }
                else Column {
                    Text("Đăng bằng ${youtubeChannel?.title ?: account!!.email}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text("Viết bình luận công khai") }, maxLines = 5)
                    TextButton({ auth(true) { scope.launch {
                        busy = true
                        try { vm.repository.postComment(video.id, draft.trim()); draft = ""; vm.notify("Đã gửi bình luận. YouTube có thể giữ lại để kiểm duyệt."); loadComments(false) }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { vm.notify(errorMessage(e)) } finally { busy = false }
                    } } }, enabled = draft.isNotBlank() && !busy) { Text("Đăng bình luận") }
                }
            }
            items(if (commentsOnly) comments else comments.take(1), key = { it.id }) { comment ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(comment.author + " · " + comment.publishedAt.take(10), style = MaterialTheme.typography.labelLarge)
                    Text(comment.text, maxLines = if (commentsOnly) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    if (commentsOnly) {
                        Text("${comment.likes} lượt thích", style = MaterialTheme.typography.labelSmall)
                        TextButton({ replyTo = comment }) { Text(if (comment.replies > 0) "${comment.replies} phản hồi" else "Trả lời") }
                    }
                    HorizontalDivider()
                }
            }
            if (commentsLoading) item { Loading() }
            if (!commentsOnly && commentError != null) item { Text("Chưa tải được bình luận. Bấm Xem thêm bình luận để thử lại.", style = MaterialTheme.typography.bodySmall) }
            if (commentsOnly) commentError?.let { item {
                if (commentAuthorizationRequired) CommentAuthorizationCard(auth) { loadComments(false) }
                else MessageCard(it, "Thử lại", { loadComments(comments.isNotEmpty()) })
            } }
            if (!commentsLoading && commentError == null && comments.isEmpty()) item { Text("Chưa có bình luận.") }
            if (commentsOnly && next != null && !commentsLoading) item { TextButton({ loadComments(true) }) { Text("Thêm bình luận") } }
            if (!commentsOnly) {
            item {
                Text("Video cùng chủ đề", style = MaterialTheme.typography.titleMedium)
                Text("Kết quả tìm kiếm theo tiêu đề video", style = MaterialTheme.typography.bodySmall)
            }
            if (relatedLoading) item { Loading() }
            relatedError?.let { item { MessageCard(it, "Thử lại", { relatedRetry++ }) } }
            if (!relatedLoading && relatedError == null && related.isEmpty()) item { Text("Chưa có video cùng chủ đề.") }
            items(related.filterNot { it.id in library.hiddenIds }, key = { "related_" + it.id }) { v -> VideoCard(v, { open(v) }, hide = { vm.hideVideo(v) }) { vm.library.toggleLater(v) } }
            }
        }
    }
    if (addPlaylist) AddToPlaylistDialog(vm, auth, video) { addPlaylist = false }
    replyTo?.let { comment -> RepliesDialog(vm, auth, comment) { replyTo = null } }
}

@Composable
private fun RepliesDialog(vm: VideoViewModel, auth: Authorize, parent: VideoComment, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val account by vm.account.collectAsState()
    val youtubeChannel by vm.youtubeChannel.collectAsState()
    var replies by remember { mutableStateOf<List<VideoComment>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var authorizationRequired by remember { mutableStateOf(false) }
    fun load(append: Boolean) { scope.launch {
        busy = true; error = null; authorizationRequired = false
        try { val page = vm.repository.replies(parent.id, if (append) next else null); replies = ((if (append) replies else emptyList()) + page.items).distinctBy { it.id }; next = page.nextToken }
        catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = errorMessage(e); authorizationRequired = needsCommentAuthorization(e, vm)
        } finally { busy = false }
    } }
    LaunchedEffect(parent.id) { load(false) }
    AlertDialog(onDismissRequest = close, title = { Text("Phản hồi") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(parent.author, style = MaterialTheme.typography.labelLarge); Text(parent.text); HorizontalDivider() }
            items(replies, key = { it.id }) { reply -> Column { Text(reply.author, style = MaterialTheme.typography.labelLarge); Text(reply.text) } }
            error?.let { item {
                if (authorizationRequired) CommentAuthorizationCard(auth) { load(false) }
                else MessageCard(it, "Thử lại", { load(replies.isNotEmpty()) })
            } }
            if (next != null && !busy) item { TextButton({ load(true) }) { Text("Tải thêm") } }
            if (busy) item { Loading() }
            item { account?.let { Text("Đăng bằng ${youtubeChannel?.title ?: it.email}", style = MaterialTheme.typography.bodySmall) }; OutlinedTextField(draft, { draft = it }, label = { Text("Viết phản hồi công khai") }) }
            item { TextButton({ auth(true) { scope.launch {
                busy = true
                try { vm.repository.reply(parent.id, draft.trim()); draft = ""; vm.notify("Đã gửi phản hồi."); load(false) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorMessage(e) } finally { busy = false }
            } } }, enabled = draft.isNotBlank() && !busy) { Text("Gửi phản hồi") } }
        }
    }, confirmButton = { TextButton(close) { Text("Đóng") } })
}

@Composable
private fun AddToPlaylistDialog(vm: VideoViewModel, auth: Authorize, video: VideoResult, close: () -> Unit) {
    val library by vm.library.state.collectAsState()
    var remote by remember { mutableStateOf<List<VideoPlaylist>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() { auth(false) { scope.launch {
        busy = true
        try { val page = vm.repository.playlists(next); remote = (remote + page.items).distinctBy { it.id }; next = page.nextToken }
        catch (e: CancellationException) { throw e } catch (e: Exception) { vm.notify(errorMessage(e)) } finally { busy = false }
    } } }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Lưu vào playlist") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Trên thiết bị") }
            items(library.playlists, key = { it.id }) { p -> TextButton({ vm.library.add(p.id, video); close() }) { Text(p.title) } }
            item { OutlinedTextField(title, { title = it }, label = { Text("Tên playlist mới trên máy") }) }
            item { TextButton({ vm.library.createPlaylist(title); val p = vm.library.state.value.playlists.last(); vm.library.add(p.id, video); close() }, enabled = title.isNotBlank()) { Text("Tạo và lưu trên máy") } }
            item { HorizontalDivider(); TextButton({ load() }, enabled = !busy && (remote.isEmpty() || next != null)) { Text(if (remote.isEmpty()) "Tải playlist YouTube" else "Tải thêm playlist YouTube") } }
            items(remote, key = { it.id }) { p -> TextButton({ auth(true) { scope.launch {
                busy = true
                try { vm.repository.addToPlaylist(p.id, video.id); vm.notify("Đã lưu vào ${p.title} trên YouTube."); close() }
                catch (e: CancellationException) { throw e } catch (e: Exception) { vm.notify(errorMessage(e)) } finally { busy = false }
            } } }, enabled = !busy) { Text(p.title) } }
            if (busy) item { Loading() }
        }
    }, confirmButton = { TextButton(close, enabled = !busy) { Text("Đóng") } })
}
