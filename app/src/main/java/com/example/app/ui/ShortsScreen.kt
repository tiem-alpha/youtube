package com.example.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.app.*
import com.example.app.domain.VideoResult

@Composable
fun ShortsScreen(vm: VideoViewModel, modifier: Modifier, open: (VideoResult) -> Unit) {
    val state by vm.state.collectAsState()
    val library by vm.library.state.collectAsState()
    val videos = state.videos.filterNot { it.id in library.hiddenIds }
    val context = LocalContext.current
    val pager = rememberPagerState { videos.size }
    LaunchedEffect(pager.settledPage, videos.size) { if (state.videos.isNotEmpty() && pager.settledPage >= videos.lastIndex - 2) vm.more() }
    Column(modifier) {
        Text("Video ngắn từ YouTube · vuốt lên để chuyển", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
        // Data API has a duration filter, not a canonical Shorts feed. Label honestly.
        if (videos.isEmpty()) {
            if (state.loading) Loading()
            state.message?.let { MessageCard(it, "Thử lại", vm::retry) }
            if (!state.loading && state.message == null) MessageCard("Chưa tìm thấy video ngắn.")
        } else VerticalPager(pager, Modifier.weight(1f), key = { videos[it].id }) { index ->
            val video = videos[index]
            Column(Modifier.fillMaxSize()) {
                if (index == pager.settledPage) {
                    key(video.id) { YouTubePlayer(video.id, 0, Modifier.fillMaxWidth().weight(1f), { vm.recordVideo(video, it) }) }
                } else RemoteImage(video.thumbnailUrl, Modifier.fillMaxWidth().weight(1f))
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(video.title, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                    Text(video.channel, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton({ open(video) }) { Text("Chi tiết") }
                        TextButton({ vm.library.toggleLater(video); vm.notify("Đã cập nhật Xem sau trên máy.") }) { Text("Xem sau") }
                        TextButton({ vm.hideVideo(video) }) { Text("Ẩn video") }
                        TextButton({ shareVideo(context, video.id) }) { Text("Chia sẻ") }
                    }
                    if (index == videos.lastIndex && state.message != null) TextButton(vm::retry) { Text("Thử tải thêm") }
                }
            }
        }
    }
}
