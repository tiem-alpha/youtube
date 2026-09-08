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
fun YouTubePlayer(videoId: String, startSeconds: Int, modifier: Modifier = Modifier, onProgress: (Int) -> Unit = {}, onEnded: () -> Unit = {}, backgroundPlayback: Boolean = false, title: String = "Video YouTube", onMinimize: (() -> Unit)? = null, onDrag: (Float) -> Unit = {}, onExpand: (() -> Unit)? = null, playbackRate: Float = 1f, loop: Boolean = false) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val progress by rememberUpdatedState(onProgress)
    val ended by rememberUpdatedState(onEnded)
    val currentTitle by rememberUpdatedState(title)
    val currentLoop by rememberUpdatedState(loop)
    var lastHeartbeat by remember(videoId) { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var expectsPlayback by remember(videoId) { mutableStateOf(true) }
    var playerState by remember(videoId) { mutableIntStateOf(-1) }
    var automaticRetries by remember(videoId) { mutableIntStateOf(0) }
    var attempt by remember(videoId) { mutableIntStateOf(0) }
    var resumeSeconds by remember(videoId) { mutableIntStateOf(startSeconds) }
    var loading by remember(videoId) { mutableStateOf(true) }
    var buffering by remember(videoId) { mutableStateOf(false) }
    var retryable by remember(videoId) { mutableStateOf(true) }
    val recovery = rememberRecoveryTrigger()
    val bridge = remember(videoId, backgroundPlayback, attempt) { WebPlaybackBridge(context, backgroundPlayback) }
    var error by remember(videoId) { mutableStateOf<String?>(null) }
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
    val gestureGuard = remember(videoId, attempt, backgroundPlayback) { PlayerGestureGuard() }
    val webView = remember(videoId, attempt, backgroundPlayback) {
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
                @JavascriptInterface fun position(seconds: Int) { post { if (!disposed) { resumeSeconds = seconds.coerceAtLeast(0); progress(resumeSeconds) } } }
                @JavascriptInterface fun finished() { post { if (!disposed) {
                    if (currentLoop) evaluateJavascript("player.seekTo(0,true);player.playVideo();", null) else ended()
                } } }
                @JavascriptInterface fun playback(state: Int, seconds: Int, duration: Int) { post {
                    if (disposed) return@post
                    playerState = state
                    lastHeartbeat = android.os.SystemClock.elapsedRealtime()
                    expectsPlayback = state in listOf(-1, 1, 3)
                    buffering = state == 3
                    if (state == 1) { error = null; retryable = false }
                    loading = error == null && (state == 3 || state == -1)
                    if (state == 3) retryable = true
                    bridge.update(state, seconds, currentTitle, duration) { error = "Không khởi động được phát nền. Mở lại màn hình video rồi bấm Phát." }
                } }
                @JavascriptInterface fun failed(code: Int) { post { if (disposed) return@post; expectsPlayback = false; loading = false; retryable = code == 5; error = when (code) {
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
                        if (disposed) return
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
                            if (!disposed) evaluateJavascript("if(player&&player.playVideo)player.playVideo();", null)
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
            val id = YouTubeLinks.videoId(videoId)
            if (id != null) {
                val origin = "https://${context.packageName}"
                loadDataWithBaseURL(origin + "/", """
                    <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="strict-origin-when-cross-origin">
                    <style>html,body{margin:0;padding:0;width:100%;height:100vh;background:#000;overflow:hidden}#player{position:fixed;inset:0;display:block;width:100%;height:100%;border:0}</style></head>
                    <body><div id="player"></div><script src="https://www.youtube.com/iframe_api"></script><script>
                    var player; function reportPlayback(){if(player&&player.getPlayerState&&player.getCurrentTime)Companion.playback(player.getPlayerState(),Math.floor(player.getCurrentTime()),Math.floor(player.getDuration()||0));}
                    function onYouTubeIframeAPIReady(){player=new YT.Player('player',{width:'100%',height:'100%',videoId:'$id',playerVars:{controls:1,fs:1,playsinline:1,autoplay:1,start:${resumeSeconds.coerceAtLeast(0)},origin:'$origin'},events:{
                    onReady:function(e){e.target.setPlaybackRate($playbackRate);e.target.playVideo();},onStateChange:function(e){reportPlayback();if(e.data===0)Companion.finished();},onError:function(e){Companion.playback(2,0,0);Companion.failed(e.data);}}});}
                    setInterval(function(){reportPlayback();if(player&&player.getPlayerState&&player.getPlayerState()===1)Companion.position(Math.floor(player.getCurrentTime()));},5000);
                    </script></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
        }
    }
    LaunchedEffect(recovery) {
        if (recovery > 0 && retryable && (error != null || buffering)) {
            automaticRetries = 0; error = null; loading = true; buffering = false; attempt++
        }
    }
    LaunchedEffect(webView, playbackRate) {
        webView.evaluateJavascript("if(player&&player.setPlaybackRate)player.setPlaybackRate($playbackRate);", null)
    }
    LaunchedEffect(webView, loading) {
        val since = android.os.SystemClock.elapsedRealtime()
        while (true) {
            kotlinx.coroutines.delay(2_000)
            val now = android.os.SystemClock.elapsedRealtime()
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
        bridge.attach(webView)
        lastHeartbeat = android.os.SystemClock.elapsedRealtime()
        bridge.update(3, resumeSeconds, currentTitle) { error = "Không khởi động được phát nền. Bấm Thử lại khi mở ứng dụng." }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && !backgroundPlayback) { webView.evaluateJavascript("if(player&&player.pauseVideo)player.pauseVideo();", null); webView.onPause() }
            if (event == Lifecycle.Event.ON_RESUME) {
                webView.onResume()
                webView.evaluateJavascript("reportPlayback();", null)
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
