package com.example.app.ui

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.app.domain.YouTubeLinks

/** Capture end-screen links inside the cross-origin player before YouTube opens a new window. */
internal fun installPlayerVideoNavigation(webView: WebView, open: (String) -> Unit) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
        !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
    val origins = setOf("https://www.youtube.com", "https://www.youtube-nocookie.com")
    WebViewCompat.addWebMessageListener(webView, "PlayerVideoNavigation", origins) { _, message, _, _, _ ->
        message.data?.let(YouTubeLinks::videoId)?.let(open)
    }
    WebViewCompat.addDocumentStartJavaScript(webView, """
        (function() {
            if (!location.pathname.startsWith('/embed/')) return;
            document.addEventListener('click', function(event) {
                var link = event.target instanceof Element ? event.target.closest('a[href]') : null;
                if (!link) return;
                var url;
                try { url = new URL(link.href, location.href); } catch (_) { return; }
                if (url.protocol !== 'https:' && url.protocol !== 'http:') return;
                var id = null;
                if (url.hostname === 'youtu.be') id = url.pathname.split('/')[1];
                else if (['youtube.com','www.youtube.com','m.youtube.com','music.youtube.com'].includes(url.hostname)) {
                    if (url.pathname === '/watch') id = url.searchParams.get('v');
                    else if (/^\/(shorts|live|embed)\//.test(url.pathname)) id = url.pathname.split('/')[2];
                }
                if (!id || !/^[A-Za-z0-9_-]{11}${'$'}/.test(id)) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                PlayerVideoNavigation.postMessage(id);
            }, true);
        })();
    """.trimIndent(), origins)
}
