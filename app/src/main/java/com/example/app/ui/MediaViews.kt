package com.example.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import com.example.app.domain.YouTubeLinks
import com.example.app.playback.PlaybackManager
import com.example.app.playback.BackgroundPlaybackWebView
import com.example.app.playback.BackgroundPlaybackLayout
import com.example.app.playback.WebPlaybackBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.ByteArrayOutputStream
import com.example.app.data.BoundedDiskCache

private val imageCache = object : android.util.LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}
private var thumbnailDiskCache: BoundedDiskCache? = null
@Synchronized private fun thumbnails(context: Context): BoundedDiskCache = thumbnailDiskCache
    ?: BoundedDiskCache(File(context.applicationContext.cacheDir, "thumbnails"), 48L * 1024 * 1024).also { thumbnailDiskCache = it }
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier) {
    val disk = thumbnails(LocalContext.current)
    val bitmap by produceState<ImageBitmap?>(imageCache.get(url.orEmpty()), url) {
        value = imageCache.get(url.orEmpty())
        if (value == null && url?.startsWith("https://") == true) value = withContext(Dispatchers.IO) {
            runCatching {
                disk.read(url, 7 * 24 * 60 * 60_000L)?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.let { cached ->
                        imageCache.put(url, cached)
                        return@withContext cached
                    }
                }
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10000; connection.readTimeout = 10000
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 4 * 1024 * 1024) throw java.io.IOException("Thumbnail too large")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also {
                        imageCache.put(url, it); disk.write(url, bytes)
                    }
                } finally { connection.disconnect() }
            }.getOrNull()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.PlayArrow, null)
    }
}

fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }
fun openExternal(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { android.widget.Toast.makeText(context, "Không tìm thấy ứng dụng để mở liên kết.", android.widget.Toast.LENGTH_SHORT).show() }
}
fun shareVideo(context: Context, id: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$id"), "Chia sẻ video"))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayer(videoId: String, startSeconds: Int, modifier: Modifier = Modifier, onProgress: (Int) -> Unit = {}, onEnded: () -> Unit = {}, backgroundPlayback: Boolean = false, title: String = "Video YouTube", onMinimize: (() -> Unit)? = null, onDrag: (Float) -> Unit = {}, onExpand: (() -> Unit)? = null, playbackRate: Float = 1f, loop: Boolean = false, nextVideo: () -> Pair<String, String>? = { null }) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val progress by rememberUpdatedState(onProgress)
    val ended by rememberUpdatedState(onEnded)
    val next by rememberUpdatedState(nextVideo)
    var loadedId by remember { mutableStateOf(videoId) }
    var loadedTitle by remember { mutableStateOf(title) }
    val currentLoop by rememberUpdatedState(loop)
    val currentRate by rememberUpdatedState(playbackRate)
    var audioMode by remember { mutableStateOf(false) }
    var lastHeartbeat by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var lastProgressAt by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var lastPosition by remember { mutableIntStateOf(-1) }
    var expectsPlayback by remember { mutableStateOf(true) }
    var playerState by remember { mutableIntStateOf(-1) }
    var automaticRetries by remember { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }
    var resumeSeconds by remember { mutableIntStateOf(startSeconds) }
    var loading by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    var retryable by remember { mutableStateOf(true) }
    val recovery = rememberRecoveryTrigger()
    val bridge = remember(backgroundPlayback, attempt) { WebPlaybackBridge(context, backgroundPlayback) }
    var error by remember { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf<Dialog?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var restoreFullscreenPlayback by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun exitFullscreen(resumePlayback: Boolean = true) {
        val restore = restoreFullscreenPlayback.takeIf { resumePlayback && playerState in listOf(1, 3) }
        fullscreen?.dismiss(); fullscreen = null
        customCallback?.onCustomViewHidden(); customCallback = null
        restoreFullscreenPlayback = null
        restore?.invoke()
    }
    val gestureGuard = remember(attempt, backgroundPlayback) { PlayerGestureGuard() }
    val webView = remember(attempt, backgroundPlayback) {
        BackgroundPlaybackWebView(context, backgroundPlayback).apply {
            // A wrap-content WebView can give percentage-height HTML a zero-height viewport.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            gestureGuard.install(this)
            addJavascriptInterface(object {
                @JavascriptInterface fun position(seconds: Int) { post { if (!disposed && !audioMode) { resumeSeconds = seconds.coerceAtLeast(0); progress(resumeSeconds) } } }
                @JavascriptInterface fun finished() { post { if (!disposed && !audioMode) {
                    if (currentLoop) evaluateJavascript("player.seekTo(0,true);player.playVideo();", null) else {
                        ended()
                        next()?.let { target ->
                            YouTubeLinks.videoId(target.first)?.let { id ->
                                loadedId = id; loadedTitle = target.second
                                resumeSeconds = 0; automaticRetries = 0; error = null; retryable = true; expectsPlayback = true
                                lastProgressAt = android.os.SystemClock.elapsedRealtime(); lastPosition = -1
                                loading = true; buffering = true
                                bridge.update(3, 0, loadedTitle) {}
                                onResume()
                                evaluateJavascript("loadRequestedVideo('$id',0);", null)
                            }
                        }
                    }
                } } }
                @JavascriptInterface fun playback(state: Int, seconds: Int, duration: Int) { post {
                    if (disposed || audioMode) return@post
                    if (seconds != lastPosition || state !in listOf(-1, 1, 3)) {
                        lastProgressAt = android.os.SystemClock.elapsedRealtime()
                        if (seconds > lastPosition && state == 1) automaticRetries = 0
                        lastPosition = seconds
                    }
                    playerState = state
                    lastHeartbeat = android.os.SystemClock.elapsedRealtime()
                    expectsPlayback = state in listOf(-1, 1, 3)
                    buffering = state == 3
                    if (state == 1) { error = null; retryable = false }
                    loading = error == null && (state == 3 || state == -1)
                    if (state == 3) retryable = true
                    bridge.update(state, seconds, loadedTitle, duration) { error = "Không khởi động được phát nền. Mở lại màn hình video rồi bấm Phát." }
                } }
                @JavascriptInterface fun failed(code: Int) { post { if (disposed || audioMode) return@post; expectsPlayback = false; loading = false; retryable = code == 5; error = when (code) {
                    101, 150 -> "Chủ sở hữu không cho phép phát nhúng. Bạn có thể mở video trên YouTube."
                    100 -> "Video không tồn tại hoặc ở chế độ riêng tư."
                    else -> "YouTube không phát được video (mã $code). Thử lại hoặc mở trên YouTube."
                } } }
            }, "Companion")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    if (request.url.scheme == "https") openExternal(context, request.url.toString())
                    return true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, webError: WebResourceError) {
                    if (request.isForMainFrame) {
                        if (disposed || audioMode) return
                        loading = false; retryable = true
                        error = "Không tải được trình phát. Kiểm tra mạng và thử lại."
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullscreen != null) { callback.onCustomViewHidden(); return }
                    val wasPlaying = playerState in listOf(1, 3)
                    // Chromium briefly hides the document while transferring it between views.
                    // Restore only playback that was active before this fullscreen transition.
                    val restore = {
                        post {
                            if (!disposed && !audioMode) evaluateJavascript("if(player&&player.playVideo)player.playVideo();", null)
                        }
                        Unit
                    }
                    restoreFullscreenPlayback = restore
                    customCallback = callback
                    fullscreen = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                        setContentView(BackgroundPlaybackLayout(context, backgroundPlayback).apply {
                            addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        })
                        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        setOnCancelListener { exitFullscreen() }; show()
                    }
                    if (wasPlaying) restore()
                }
                override fun onHideCustomView() { exitFullscreen() }
            }
            YouTubeLinks.videoId(loadedId)?.let { id ->
                loadYouTubeDocument(id, resumeSeconds * 1000L, playbackRate, expectsPlayback)
            }
        }
    }
    LaunchedEffect(webView, videoId, title, audioMode) {
        if (audioMode) return@LaunchedEffect
        if (loadedId != videoId) {
            YouTubeLinks.videoId(videoId)?.let { id ->
                loadedId = id; loadedTitle = title
                resumeSeconds = startSeconds; automaticRetries = 0; error = null; retryable = true; expectsPlayback = true; loading = true
                webView.onResume()
                webView.evaluateJavascript("loadRequestedVideo('$id',${startSeconds.coerceAtLeast(0)});", null)
            }
        } else loadedTitle = title
    }
    LaunchedEffect(recovery) {
        if (!audioMode && recovery > 0 && retryable && (error != null || buffering)) {
            automaticRetries = 0; error = null; loading = true; buffering = false; attempt++
        }
    }
    LaunchedEffect(webView, playbackRate) {
        if (audioMode) return@LaunchedEffect
        webView.evaluateJavascript("if(player&&player.setPlaybackRate)player.setPlaybackRate($playbackRate);", null)
    }
    LaunchedEffect(webView, loading) {
        val since = android.os.SystemClock.elapsedRealtime()
        while (true) {
            kotlinx.coroutines.delay(2_000)
            val now = android.os.SystemClock.elapsedRealtime()
            if (audioMode) continue
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && backgroundPlayback) continue
            if ((loading && now - since >= 20_000) || (expectsPlayback && now - lastHeartbeat >= 20_000)) {
                if (automaticRetries < 2 && (retryable || expectsPlayback)) {
                    automaticRetries++; error = null; loading = true; buffering = false
                    lastHeartbeat = now; attempt++
                } else {
                    expectsPlayback = false; loading = false; retryable = true
                    error = "Tải video quá lâu. Kiểm tra mạng rồi thử lại."
                }
                break
            }
        }
    }
    BackHandler(fullscreen != null) { exitFullscreen() }
    DisposableEffect(webView, owner) {
        fun screenInteractive() = context.getSystemService(android.os.PowerManager::class.java).isInteractive
        fun restoreVideo(state: com.example.app.playback.AudioSnapshot) {
            if (webView.disposed) return
            loadedId = state.videoId; loadedTitle = state.title
            resumeSeconds = (state.positionMs / 1000).toInt()
            progress(resumeSeconds)
            audioMode = false; automaticRetries = 0; error = null
            expectsPlayback = state.playWhenReady && !state.ended
            loading = expectsPlayback; buffering = false; retryable = true
            lastHeartbeat = android.os.SystemClock.elapsedRealtime(); lastProgressAt = lastHeartbeat
            webView.onResume(); webView.dispatchWindowVisibilityChanged(View.VISIBLE)
            webView.loadYouTubeDocument(state.videoId, state.positionMs, currentRate, expectsPlayback)
            bridge.update(if (expectsPlayback) 3 else 2, resumeSeconds, loadedTitle) {}
        }
        fun requestAudio() {
            if (audioMode || webView.disposed || screenInteractive()) return
            audioMode = true
            exitFullscreen(resumePlayback = false)
            // Read the actual clock before unloading the iframe. Pausing alone can leave it buffering video.
            var capturedOnce = false
            fun completeCapture(raw: String?) {
                if (capturedOnce || webView.disposed || !audioMode) return
                capturedOnce = true
                if (screenInteractive() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    audioMode = false
                    return
                }
                val captured = runCatching { org.json.JSONObject(raw ?: "{}") }.getOrNull()
                val positionMs = (if (captured?.isNull("position") == false) captured.optLong("position") else resumeSeconds * 1000L).coerceAtLeast(0)
                val state = if (captured?.isNull("state") == false) captured.optInt("state") else playerState
                val play = state in listOf(-1, 1, 3)
                resumeSeconds = (positionMs / 1000).toInt(); progress(resumeSeconds)
                webView.stopLoading()
                webView.loadDataWithBaseURL(null, "<html><body style='background:black'></body></html>", "text/html", "UTF-8", null)
                webView.onPause()
                if (!bridge.startAudio(loadedId, loadedTitle, positionMs, play, currentRate)) {
                    expectsPlayback = false; loading = false; buffering = false
                    error = "Không khởi động được âm thanh nền. Mở app rồi thử lại."
                }
            }
            webView.evaluateJavascript("""
                (function(){return {position:typeof player!=='undefined'&&player&&player.getCurrentTime?Math.round(player.getCurrentTime()*1000):null,
                state:typeof player!=='undefined'&&player&&player.getPlayerState?player.getPlayerState():null};})()
            """.trimIndent(), ::completeCapture)
            // A stalled renderer must not block the switch; use the most recent native clock.
            webView.postDelayed({ completeCapture(null) }, 1000)
        }
        // Service callbacks run without a Compose frame, including while the Activity is stopped.
        // Replacing the WebView through recomposition can otherwise wait until the app opens.
        bridge.attach(webView, onAudioRequested = ::requestAudio,
            onAudioState = { state ->
                if (audioMode && state.videoId == loadedId) {
                    resumeSeconds = (state.positionMs / 1000).toInt(); progress(resumeSeconds)
                    expectsPlayback = state.playWhenReady && !state.ended && state.error == null
                    loading = state.buffering && expectsPlayback; buffering = loading
                    error = state.error; retryable = state.error != null
                    playerState = when { state.ended -> 0; loading -> 3; expectsPlayback -> 1; else -> 2 }
                    lastHeartbeat = android.os.SystemClock.elapsedRealtime()
                }
            }, onAudioEnded = { state ->
                if (audioMode && state.videoId == loadedId) {
                    val target = if (currentLoop) loadedId to loadedTitle else { ended(); next() }
                    target?.let { (id, name) ->
                        if (YouTubeLinks.videoId(id) != null) {
                            loadedId = id; loadedTitle = name; resumeSeconds = 0
                            bridge.startAudio(id, name, 0, true, currentRate)
                        }
                    }
                }
            }, onAudioReturn = ::restoreVideo, onScreenOn = {
                if (screenInteractive() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) bridge.returnToVideo()
            }) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (!audioMode && expectsPlayback && now - lastProgressAt >= 30_000 &&
                !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lastProgressAt = now
                if (automaticRetries < 2) {
                    automaticRetries++
                    val id = YouTubeLinks.videoId(loadedId)
                    if (id != null) webView.evaluateJavascript(
                        "if(playerReady){loadRequestedVideo('$id',${resumeSeconds.coerceAtLeast(0)});player.playVideo();}", null)
                } else {
                    expectsPlayback = false; loading = false; retryable = true
                    error = "Tải video quá lâu. Kiểm tra mạng rồi thử lại."
                    webView.evaluateJavascript("if(player&&player.pauseVideo)player.pauseVideo();", null)
                    bridge.update(2, resumeSeconds, loadedTitle) {}
                }
            }
        }
        lastHeartbeat = android.os.SystemClock.elapsedRealtime()
        bridge.update(3, resumeSeconds, loadedTitle) { error = "Không khởi động được phát nền. Bấm Thử lại khi mở ứng dụng." }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && !backgroundPlayback) { webView.evaluateJavascript("if(player&&player.pauseVideo)player.pauseVideo();", null); webView.onPause() }
            if (event == Lifecycle.Event.ON_RESUME) {
                if (audioMode && screenInteractive()) bridge.returnToVideo()
                else if (!audioMode) {
                    webView.onResume()
                    webView.evaluateJavascript("if(typeof reportPlayback==='function')reportPlayback();", null)
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); bridge.close(); exitFullscreen(resumePlayback = false); webView.stopLoading(); webView.removeJavascriptInterface("Companion"); webView.destroy() }
    }
    Column(modifier) {
        key(webView) { AndroidView(factory = { HoldToMinimizeLayout(it).apply { addView(webView) } },
            update = { it.dragEnabled = onMinimize != null && fullscreen == null; it.dragBlocked = { gestureGuard.popupOpen }; it.onMinimize = { onMinimize?.invoke() }; it.onDrag = onDrag; it.onExpand = onExpand },
            modifier = Modifier.fillMaxWidth().weight(1f)) }
        if (loading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message ->
            androidx.compose.material3.TextButton({ automaticRetries = 0; expectsPlayback = true; error = null; loading = true; retryable = true; attempt++ }) {
                Text("$message · Thử lại", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun LocalVideoSurface(manager: PlaybackManager, modifier: Modifier = Modifier) {
    val player by manager.player.collectAsState()
    AndroidView(factory = { PlayerView(it).apply { setShowSubtitleButton(true); setShowNextButton(false); setShowPreviousButton(false) } },
        update = { it.player = player }, onRelease = { it.player = null }, modifier = modifier.background(Color.Black))
}
