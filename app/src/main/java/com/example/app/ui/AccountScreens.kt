package com.example.app.ui

import android.accounts.Account
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.app.*
import com.example.app.domain.*
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AccountDialog(vm: VideoViewModel, auth: Authorize, close: () -> Unit) {
    val account by vm.account.collectAsState()
    val youtubeChannel by vm.youtubeChannel.collectAsState()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text("Tài khoản YouTube") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (account == null) Text("Bạn có thể xem video mà không cần đăng nhập. Kết nối Google để xem kênh đã đăng ký, playlist và video đã thích; cấp thêm quyền khi tương tác với YouTube.")
            else {
                youtubeChannel?.let { RemoteImage(it.thumbnail, Modifier.size(64.dp)) }
                Text(youtubeChannel?.title ?: account!!.name); Text(account!!.email)
                TextButton({ auth(false) { vm.notify("Đã làm mới kết nối Google.") } }) { Text("Kết nối lại") }
                Text("Thư viện trên máy được quản lý riêng và không tự đồng bộ thành lịch sử hoặc Xem sau của YouTube.")
                TextButton({
                    busy = true
                    Identity.getAuthorizationClient(context).revokeAccess(RevokeAccessRequest.builder().setAccount(Account(account!!.email, "com.google")).setScopes(vm.grantedScopes.map(::Scope)).build())
                        .addOnSuccessListener { busy = false; vm.disconnect(); close() }
                        .addOnFailureListener { busy = false; vm.notify("Không thu hồi được quyền. Hãy thử lại hoặc dùng trang quản lý quyền Google.") }
                }, enabled = !busy) { Text("Thu hồi quyền truy cập Google") }
            }
            TextButton({ auth.switchAccount { close() } }, enabled = !busy) { Text("Đăng nhập tài khoản khác") }
            TextButton({ openExternal(context, "https://myaccount.google.com/connections") }) { Text("Quản lý quyền trên Google") }
        }
    }, confirmButton = {
        if (account == null) TextButton({ auth(false) { close() } }) { Text("Kết nối Google") }
        else TextButton({ vm.disconnect(); close() }, enabled = !busy) { Text("Đăng xuất") }
    }, dismissButton = { TextButton(close) { Text("Đóng") } })
}

@Composable
fun SubscriptionsScreen(vm: VideoViewModel, auth: Authorize, modifier: Modifier, open: (String) -> Unit) {
    val account by vm.account.collectAsState()
    val scope = rememberCoroutineScope()
    var channels by remember(account?.id) { mutableStateOf<List<Channel>>(emptyList()) }
    var next by remember(account?.id) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load(append: Boolean) {
        if (loading) return
        auth(false) {
            scope.launch {
                loading = true; error = null
                try { val page = vm.repository.subscriptions(if (append) next else null); channels = ((if (append) channels else emptyList()) + page.items).distinctBy { it.id }; next = page.nextToken }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorMessage(e) }
                finally { loading = false }
            }
        }
    }
    LaunchedEffect(account?.id) { if (account != null) load(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Kênh đã đăng ký", style = MaterialTheme.typography.headlineSmall) }
        if (account == null) item { MessageCard("Kết nối tài khoản để xem các kênh bạn đã đăng ký.", "Kết nối Google", { auth(false) {} }) }
        else {
            item { Text(account!!.email, style = MaterialTheme.typography.bodySmall); TextButton({ load(false) }) { Text("Làm mới") } }
            items(channels, key = { it.id }) { channel ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { open(channel.id) }) { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { RemoteImage(channel.thumbnail, Modifier.size(56.dp)); Text(channel.title) } }
            }
            if (!loading && error == null && channels.isEmpty()) item { MessageCard("Tài khoản chưa có kênh đăng ký.") }
            if (next != null && !loading) item { OutlinedButton({ load(true) }) { Text("Tải thêm") } }
        }
        if (loading) item { Loading() }
        error?.let { item { MessageCard(it, "Thử lại", { load(channels.isNotEmpty()) }) } }
    }
}

@Composable
fun ChannelScreen(vm: VideoViewModel, auth: Authorize, id: String, modifier: Modifier, open: (VideoResult) -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val account by vm.account.collectAsState()
    var channel by remember(id) { mutableStateOf<Channel?>(null) }
    var subscription by remember(id, account?.id) { mutableStateOf<String?>(null) }
    var subscriptionKnown by remember(id, account?.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id, account?.id) {
        try {
            channel = vm.repository.channel(id)
            if (vm.hasSession()) { subscription = vm.repository.subscription(id); subscriptionKnown = true }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = errorMessage(e) }
    }
    Column(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            channel?.let { c ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { RemoteImage(c.thumbnail, Modifier.size(64.dp)); Column { Text(c.title, style = MaterialTheme.typography.titleLarge); if (c.subscribers.isNotBlank()) Text(c.subscribers + " người đăng ký") } }
                Text(c.description, maxLines = 3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    val remove = subscriptionKnown && subscription != null
                    auth(true) {
                    scope.launch {
                        busy = true
                        try {
                            val existing = if (subscriptionKnown) subscription else vm.repository.subscription(id)
                            if (remove && existing != null) { vm.repository.unsubscribe(existing); subscription = null }
                            else subscription = existing ?: vm.repository.subscribe(id)
                            subscriptionKnown = true
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { vm.notify(errorMessage(e)) } finally { busy = false }
                    }
                } }, enabled = !busy) { Text(if (subscription != null) "Hủy đăng ký" else "Đăng ký kênh") }
                TextButton({ openExternal(context, "https://www.youtube.com/channel/$id") }) { Text("Mở YouTube") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        FeedList(state, Modifier.weight(1f), vm::more, vm::retry, open, vm.library::toggleLater)
    }
}
