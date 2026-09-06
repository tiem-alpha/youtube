package com.example.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.app.*
import com.example.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(vm: VideoViewModel, auth: Authorize, modifier: Modifier, open: (VideoResult) -> Unit, playlist: (String, String, Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val library by vm.library.state.collectAsState()
    val account by vm.account.collectAsState()
    val channel by vm.youtubeChannel.collectAsState()
    val accountLibrary by vm.accountLibrary.collectAsState()
    val feed by vm.state.collectAsState()
    var section by rememberSaveable { mutableStateOf("YouTube") }
    var create by remember { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf(false) }
    val remote = accountLibrary.playlists
    val next = accountLibrary.nextToken
    val error = accountLibrary.error
    val loading = accountLibrary.loading
    val scope = rememberCoroutineScope()
    fun load(append: Boolean) {
        if (!loading) auth(false) { vm.loadAccountLibrary(append, refresh = !append) }
    }
    LaunchedEffect(account?.id, section) {
        if (account != null && section == "YouTube") vm.loadAccountLibrary(refresh = true)
        if (account != null && section == "Đã thích") vm.load(FeedRequest(FeedKind.Liked))
    }
    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("YouTube", "Đã thích", "Lịch sử", "Xem sau", "Playlist trên máy").forEach { FilterChip(section == it, { section = it }, { Text(if (it == "YouTube") "Bộ sưu tập tài khoản" else it) }) }
        }
        if (section == "Đã thích" && account != null) FeedList(
            if (feed.request.kind == FeedKind.Liked) feed else SearchUiState(FeedRequest(FeedKind.Liked), loading = true),
            Modifier.weight(1f), { auth(false) { vm.more() } }, { auth(false) { vm.retry() } }, open, vm.library::toggleLater)
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when(section) {
                "Đã thích" -> item { MessageCard("Kết nối Google để xem video đã thích trên YouTube.", "Kết nối Google", { auth(false) {} }) }
                "Lịch sử" -> {
                    item { Text("Lịch sử xem trong ứng dụng", style = MaterialTheme.typography.titleLarge) }
                    if (library.history.isEmpty()) item { MessageCard("Các video đã mở sẽ xuất hiện ở đây.") }
                    else item { TextButton({ clearHistory = true }) { Text("Xóa lịch sử") } }
                    items(library.history, key = { it.video.id }) { saved -> Column {
                        VideoCard(saved.video, { open(saved.video) }) { vm.library.toggleLater(saved.video) }
                        Row { TextButton({ open(saved.video) }) { Text("Tiếp tục từ ${formatTime(saved.positionSeconds * 1000L)}") }; TextButton({ vm.removeHistory(saved.video.id) }) { Text("Xóa") } }
                    } }
                }
                "Xem sau" -> {
                    item { Text("Xem sau trên thiết bị", style = MaterialTheme.typography.titleLarge) }
                    if (library.watchLater.isEmpty()) item { MessageCard("Bấm biểu tượng lưu ở video để thêm vào đây.") }
                    items(library.watchLater, key = { it.id }) { video -> Column { VideoCard(video, { open(video) }); TextButton({ vm.library.toggleLater(video) }) { Text("Bỏ khỏi xem sau") } } }
                }
                "Playlist trên máy" -> {
                    item { Button({ create = true }) { Text("Tạo playlist trên máy") } }
                    items(library.playlists, key = { it.id }) { p -> OutlinedCard(Modifier.fillMaxWidth().clickable { playlist(p.id, p.title, false) }) { Column(Modifier.padding(16.dp)) { Text(p.title); Text("${p.videos.size} video") } } }
                }
                "YouTube" -> {
                    if (account == null) item { MessageCard("Đăng nhập để xem playlist trên tài khoản YouTube.", "Kết nối Google", { auth(false) {} }) }
                    else {
                        item {
                            Text("Bộ sưu tập của ${channel?.title ?: account!!.name}", style = MaterialTheme.typography.titleLarge)
                            Text(account!!.email)
                            TextButton({ section = "Đã thích" }) { Text("Video đã thích") }
                            Row { TextButton({ load(false) }, enabled = !loading) { Text("Làm mới") }; TextButton({ create = true }) { Text("Tạo playlist YouTube") } }
                            Text("Playlist do tài khoản tạo. Lịch sử và Xem sau trong các tab bên cạnh được lưu trên thiết bị.", style = MaterialTheme.typography.bodySmall)
                            TextButton({ openExternal(context, "https://www.youtube.com/feed/playlists") }) { Text("Xem cả playlist đã lưu trên YouTube") }
                        }
                        items(remote, key = { it.id }) { p -> OutlinedCard(Modifier.fillMaxWidth().clickable { playlist(p.id, p.title, true) }) { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { RemoteImage(p.thumbnail, Modifier.size(80.dp, 50.dp)); Column { Text(p.title); Text("${p.count} video") } } } }
                        if (accountLibrary.loaded && !loading && remote.isEmpty() && error == null) item { MessageCard("Chưa có playlist do tài khoản này tạo.") }
                        if (next != null && !loading) item { OutlinedButton({ load(true) }) { Text("Tải thêm") } }
                    }
                    if (loading) item { Loading() }
                    error?.let { item { MessageCard(it, "Thử lại", { load(next != null && remote.isNotEmpty()) }) } }
                }
            }
        }
    }
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("Xóa lịch sử trên máy?") }, confirmButton = { TextButton({ vm.removeHistory(); clearHistory = false }) { Text("Xóa") } }, dismissButton = { TextButton({ clearHistory = false }) { Text("Hủy") } })
    if (create) {
        var title by remember { mutableStateOf("") }
        var privacy by remember { mutableStateOf("private") }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!saving) create = false }, title = { Text(if (section == "YouTube") "Tạo playlist YouTube" else "Tạo playlist trên máy") }, text = { Column {
            OutlinedTextField(title, { title = it }, label = { Text("Tên playlist") })
            if (section == "YouTube") listOf("private" to "Riêng tư", "unlisted" to "Không công khai", "public" to "Công khai").forEach { (value, label) -> FilterChip(privacy == value, { privacy = value }, { Text(label) }) }
        } }, confirmButton = { TextButton({
            if (section == "YouTube") auth(true) { scope.launch {
                saving = true
                try { vm.repository.createPlaylist(title.trim(), privacy); create = false; load(false) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { vm.notify(errorMessage(e)) } finally { saving = false }
            } } else { vm.library.createPlaylist(title); create = false }
        }, enabled = title.isNotBlank() && !saving) { Text("Tạo") } }, dismissButton = { TextButton({ create = false }, enabled = !saving) { Text("Hủy") } })
    }
}

@Composable
fun LocalPlaylistScreen(vm: VideoViewModel, id: String, modifier: Modifier, open: (VideoResult) -> Unit, deleted: () -> Unit) {
    val library by vm.library.state.collectAsState()
    val playlist = library.playlists.firstOrNull { it.id == id }
    var confirm by remember { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton({ confirm = true }) { Text("Xóa playlist trên máy") } }
        if (playlist?.videos.isNullOrEmpty()) item { MessageCard("Playlist chưa có video. Mở video và chọn Lưu playlist.") }
        items(playlist?.videos.orEmpty(), key = { it.id }) { video -> Column { VideoCard(video, { open(video) }); TextButton({ vm.library.remove(id, video.id) }) { Text("Bỏ khỏi playlist") } } }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Xóa playlist?") }, text = { Text("Playlist này sẽ bị xóa khỏi thiết bị.") }, confirmButton = { TextButton({ vm.library.deletePlaylist(id); confirm = false; deleted() }) { Text("Xóa") } }, dismissButton = { TextButton({ confirm = false }) { Text("Hủy") } })
}
