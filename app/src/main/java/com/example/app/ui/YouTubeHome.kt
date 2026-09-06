package com.example.app.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.app.domain.VideoResult
import com.example.app.domain.YouTubeLinks

/** Render YouTube's own feed without scraping, ranking, or removing its contents. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeHome(visible: Boolean, modifier: Modifier, open: (VideoResult) -> Unit) {
    val context = LocalContext.current
    val currentOpen by rememberUpdatedState(open)
    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Reuse HTTP resources while honoring YouTube's expiry/revalidation headers.
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(object {
                @JavascriptInterface fun openVideo(url: String) {
                    val id = YouTubeLinks.videoId(url) ?: return
                    post { currentOpen(VideoResult(id, "Video YouTube", "YouTube", "https://i.ytimg.com/vi/$id/hqdefault.jpg")) }
                }
            }, "VideoNavigation")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // YouTube also navigates through client-side routes, which do not call shouldOverrideUrlLoading.
                    view.evaluateJavascript("""
                        if (!window.companionNavigation) {
                            window.companionNavigation = true;
                            document.addEventListener('click', function(e) {
                                var link = e.target.closest && e.target.closest('a[href]');
                                if (!link) return;
                                var url = new URL(link.href, location.href);
                                if ((url.hostname === 'youtube.com' || url.hostname.endsWith('.youtube.com')) &&
                                    ((url.pathname === '/watch' && url.searchParams.has('v')) || url.pathname.startsWith('/shorts/'))) {
                                    e.preventDefault(); e.stopImmediatePropagation(); VideoNavigation.openVideo(url.href);
                                }
                            }, true);
                        }
                    """.trimIndent(), null)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val url = request.url
                    val id = YouTubeLinks.videoId(url.toString())
                    if (id != null) {
                        currentOpen(VideoResult(id, "Video YouTube", "YouTube", "https://i.ytimg.com/vi/$id/hqdefault.jpg"))
                        return true
                    }
                    val host = url.host.orEmpty()
                    if (url.scheme == "https" && (host == "youtube.com" || host.endsWith(".youtube.com"))) return false
                    openExternal(context, url.toString())
                    return true
                }
            }
            loadUrl("https://m.youtube.com/")
        }
    }
    DisposableEffect(web) {
        onDispose { web.stopLoading(); web.removeJavascriptInterface("VideoNavigation"); web.destroy() }
    }
    LaunchedEffect(visible) {
        if (visible) web.onResume() else {
            web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause();});", null)
            web.onPause()
        }
    }
    if (visible) AndroidView(factory = {
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web
    }, modifier = modifier)
}
