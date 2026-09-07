package com.example.app.ui

import android.accounts.Account
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

typealias Authorize = (Boolean, () -> Unit) -> Unit

@Composable
fun rememberYouTubeAuthorization(vm: VideoViewModel): Authorize {
    val context = LocalContext.current
    val client = remember(context) { Identity.getAuthorizationClient(context) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var requested by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    fun fail(error: Exception) {
        busy = false; pending = null
        val code = (error as? ApiException)?.statusCode
        vm.notify(when (code) {
            10 -> "Chưa cấu hình đăng nhập Google: đăng ký OAuth Android với package com.example.app và SHA-1 của bản cài."
            7 -> "Không kết nối được Google. Hãy kiểm tra mạng."
            16, 12501 -> "Bạn đã hủy đăng nhập hoặc cấp quyền."
            else -> "Không kết nối được tài khoản Google" + (code?.let { " (mã $it)." } ?: ".")
        })
    }
    fun accept(result: AuthorizationResult) {
        val token = result.accessToken
        val scopes = result.grantedScopes.toSet()
        if (token.isNullOrBlank() || !scopes.containsAll(requested)) {
            busy = false; pending = null; vm.notify("Chưa được cấp đủ quyền. Hãy kết nối lại và chọn quyền cần thiết."); return
        }
        val action = pending; pending = null; busy = false
        vm.connect(token, scopes) { action?.invoke() }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try { accept(client.getAuthorizationResultFromIntent(result.data)) } catch (e: Exception) { fail(e) }
    }
    fun authorize(write: Boolean, interactive: Boolean, action: () -> Unit) {
        if (vm.hasSession(write)) action()
        else if (!busy) {
            busy = true; pending = action
            val scopes = setOf(VideoViewModel.READ_SCOPE, "https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/userinfo.profile") + if (write) setOf(VideoViewModel.WRITE_SCOPE) else emptySet()
            requested = scopes
            val builder = AuthorizationRequest.builder().setRequestedScopes(scopes.map(::Scope))
            vm.account.value?.email?.takeIf(String::isNotBlank)?.let { builder.setAccount(Account(it, "com.google")) }
            client.authorize(builder.build()).addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    if (!interactive) { busy = false; pending = null }
                    else {
                        val intent = result.pendingIntent
                        if (intent == null) fail(IllegalStateException())
                        else launcher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
                    }
                } else accept(result)
            }.addOnFailureListener { fail(it) }
        }
    }
    val recovery = rememberRecoveryTrigger()
    LaunchedEffect(recovery) { if (vm.settings.getBoolean("reconnect", false)) authorize(false, false) {} }
    return { write, action -> authorize(write, true, action) }
}
