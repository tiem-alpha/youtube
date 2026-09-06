package com.example.app.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.provider.OpenableColumns
import android.util.Rational
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.app.MessageCard

@Composable
fun LocalScreen(vm: VideoViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val manager = vm.playback
    val state by manager.state.collectAsState()
    val timer by manager.sleepTimerSeconds.collectAsState()
    var source by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotifications() { if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: "File media"
            manager.playAuthorizedUri(uri); askNotifications()
        }
    }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Media trên máy", style = MaterialTheme.typography.headlineSmall); Text("Phát video và âm thanh của bạn, không chèn quảng cáo.") }
        item { Button({ picker.launch(arrayOf("video/*", "audio/*")) }) { Text("Chọn file video / âm thanh") } }
        item { OutlinedTextField(source, { source = it }, Modifier.fillMaxWidth(), label = { Text("Liên kết HTTPS trực tiếp đến media") }, singleLine = true) }
        item { Button({ manager.playAuthorizedUrl(source); name = "Media trực tuyến"; askNotifications() }, enabled = source.isNotBlank()) { Text("Phát liên kết") } }
        if (name.isNotBlank()) item { Text(name) }
        item { if (!fullscreen) LocalVideoSurface(manager, Modifier.fillMaxWidth().height(230.dp)) }
        state.error?.let { item { MessageCard(it) } }
        if (state.isReady) {
            item {
                Text(formatTime(state.positionMs) + " / " + formatTime(state.durationMs))
                Slider(state.positionMs.coerceIn(0, state.durationMs.coerceAtLeast(1)).toFloat(), { manager.seekTo(it.toLong()) }, valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = state.durationMs > 0)
                Row { Button(manager::toggle) { Text(if (state.isPlaying) "Tạm dừng" else "Phát") }; TextButton(manager::stop) { Text("Dừng") }; TextButton({ fullscreen = true }) { Text("Toàn màn hình") } }
            }
            item { Row(Modifier.horizontalScroll(rememberScrollState())) { listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { rate -> TextButton({ manager.speed(rate) }) { Text("${rate}x") } } } }
            if (Build.VERSION.SDK_INT >= 26 && context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) item {
                TextButton({ runCatching { context.activity()?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }.onFailure { vm.notify("Thiết bị không cho phép cửa sổ nổi lúc này.") } }, enabled = state.isPlaying) { Text("Cửa sổ nổi (PiP)") }
            }
        }
        item { HorizontalDivider(); Text(if (timer > 0) "Tự dừng sau ${formatTime(timer * 1000L)}" else "Hẹn giờ tắt media") }
        item { Row(Modifier.horizontalScroll(rememberScrollState())) { listOf(30, 60, 90, 120).forEach { minutes -> TextButton({ manager.startSleepTimer(minutes) }, enabled = state.isPlaying) { Text("$minutes phút") } }; if (timer > 0) TextButton(manager::cancelSleepTimer) { Text("Hủy") } } }
    }
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
            Column { TextButton({ fullscreen = false }) { Text("Đóng toàn màn hình") }; LocalVideoSurface(manager, Modifier.fillMaxWidth().weight(1f)) }
        }
    }
}
fun formatTime(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(seconds / 60, seconds % 60) }
