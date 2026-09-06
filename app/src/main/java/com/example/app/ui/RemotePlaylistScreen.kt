package com.example.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.app.*
import com.example.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun RemotePlaylistScreen(vm: VideoViewModel, auth: Authorize, id: String, title: String, modifier: Modifier, open: (VideoResult) -> Unit, renamed: (String) -> Unit, deleted: () -> Unit) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<VideoResult?>(null) }
    fun mutate(action: suspend () -> Unit) { auth(true) { scope.launch {
        busy = true
        try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { vm.notify(errorMessage(e)) } finally { busy = false }
    } } }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row { TextButton({ rename = true }, enabled = !busy) { Text("Đổi tên") }; TextButton({ delete = true }, enabled = !busy) { Text("Xóa playlist YouTube") } } }
        items(state.videos, key = { it.playlistItemId.ifBlank { it.id } }) { video -> Column {
            VideoCard(video, { open(video) }) { vm.library.toggleLater(video) }
            TextButton({ remove = video }, enabled = !busy && video.playlistItemId.isNotBlank()) { Text("Bỏ khỏi playlist YouTube") }
        } }
        if (state.loading || busy) item { Loading() }
        state.message?.let { item { MessageCard(it, "Thử lại", vm::retry) } }
        if (state.videos.isEmpty() && !state.loading && state.message == null) item { MessageCard("Playlist chưa có video.") }
        if (!state.loading && state.nextToken != null) item { TextButton(vm::more) { Text("Tải thêm") } }
    }
    if (rename) {
        var value by remember { mutableStateOf(title) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Đổi tên playlist YouTube") }, text = { OutlinedTextField(value, { value = it }, label = { Text("Tên mới") }) }, confirmButton = {
            TextButton({ mutate { vm.repository.renamePlaylist(id, value.trim()); renamed(value.trim()); rename = false } }, enabled = value.isNotBlank() && !busy) { Text("Lưu") }
        }, dismissButton = { TextButton({ rename = false }, enabled = !busy) { Text("Hủy") } })
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Xóa playlist trên YouTube?") }, text = { Text("Playlist «$title» sẽ bị xóa khỏi tài khoản YouTube. Thao tác này không thể hoàn tác.") }, confirmButton = {
        TextButton({ mutate { vm.repository.deletePlaylist(id); delete = false; deleted() } }, enabled = !busy) { Text("Xóa trên YouTube") }
    }, dismissButton = { TextButton({ delete = false }, enabled = !busy) { Text("Hủy") } })
    remove?.let { video -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Bỏ video khỏi playlist?") }, text = { Text(video.title) }, confirmButton = {
        TextButton({ mutate { vm.repository.removeFromPlaylist(video.playlistItemId); remove = null; vm.load(FeedRequest(FeedKind.Playlist, resourceId = id)) } }, enabled = !busy) { Text("Bỏ video") }
    }, dismissButton = { TextButton({ remove = null }, enabled = !busy) { Text("Hủy") } }) }
}
