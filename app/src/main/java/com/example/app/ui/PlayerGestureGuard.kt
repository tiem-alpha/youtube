package com.example.app.ui

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Observe the embedded document itself: the host page cannot inspect a cross-origin iframe. */
class PlayerGestureGuard {
    // Unknown/unsupported WebViews leave touches with the player. The header can still minimize.
    var popupOpen = true
        private set

    fun install(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val origins = setOf("https://www.youtube.com", "https://www.youtube-nocookie.com")
        WebViewCompat.addWebMessageListener(webView, "PlayerGestures", origins) { view, message, _, isMainFrame, _ ->
            if (!isMainFrame) {
                when (message.data) {
                    "blocked" -> popupOpen = true
                    "clear" -> popupOpen = false
                }
                if (popupOpen || message.data == "claim") {
                    (view.parent as? HoldToMinimizeLayout)?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, origins)
    }

    companion object {
        // Inspect semantics first, plus YouTube's popup containers; never change their contents.
        val SCRIPT = """
            (function() {
                if (!location.pathname.startsWith('/embed/')) return;
                var last = null, scheduled = false;
                var popupSelector = '[role="menu"],[role="dialog"],[role="listbox"],.ytp-popup,.ytp-settings-menu,.ytp-panel';
                function visible(node) {
                    return node.getClientRects().length > 0 && getComputedStyle(node).visibility !== 'hidden';
                }
                function blocked() {
                    var player = document.querySelector('#movie_player');
                    if (!player) return true;
                    return Array.from(player.querySelectorAll(popupSelector + ',[aria-expanded="true"]')).some(visible);
                }
                function report() {
                    scheduled = false;
                    var value = blocked() ? 'blocked' : 'clear';
                    if (last !== value) { last = value; PlayerGestures.postMessage(value); }
                }
                function schedule() {
                    if (!scheduled) { scheduled = true; requestAnimationFrame(report); }
                }
                new MutationObserver(schedule).observe(document, {subtree:true, childList:true,
                    attributes:true, attributeFilter:['class','style','hidden','aria-hidden','aria-expanded']});
                document.addEventListener('DOMContentLoaded', report);
                document.addEventListener('transitionend', schedule, true);
                document.addEventListener('touchstart', function(event) {
                    var target = event.target instanceof Element ? event.target : null;
                    var claim = blocked() || (target && target.closest(popupSelector + ',button,a,input,select,textarea,[role="button"],[role="slider"],.ytp-chrome-controls'));
                    for (var node = target; !claim && node && node !== document.body; node = node.parentElement) {
                        var overflow = getComputedStyle(node).overflowY;
                        claim = (overflow === 'auto' || overflow === 'scroll') && node.scrollHeight > node.clientHeight;
                    }
                    if (claim) PlayerGestures.postMessage('claim');
                    report();
                }, {capture:true, passive:true});
                report();
            })();
        """.trimIndent()
    }
}
