package com.frederikberg.mailverdict.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.frederikberg.mailverdict.kit.reader.ReaderNavigation
import com.frederikberg.mailverdict.kit.reader.ReaderPage
import com.frederikberg.mailverdict.kit.reader.ReaderRequest
import com.frederikberg.mailverdict.kit.reader.ReaderRequestPolicy
import com.frederikberg.mailverdict.kit.reader.ReaderResource
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

/** What a message page needs from the app around it. */
class MessageWebContext(
    val serverUrl: String,
    /** Fetches an inline attachment with the app's credential. */
    val loadResource: suspend (ReaderRequest.Attachment) -> ReaderResource?,
    val onNavigate: (ReaderNavigation) -> Unit,
)

/**
 * One message body in a web view with page script off. Every request the page makes goes through
 * [ReaderRequestPolicy]: inline attachments are fetched by the app with its credential, remote
 * content is answered empty unless the person asked for images (and then only images), and a tapped
 * link leaves through [MessageWebContext.onNavigate] instead of navigating the page.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MessageWebView(
    page: ReaderPage,
    remoteImages: Boolean,
    context: MessageWebContext,
    modifier: Modifier = Modifier,
) {
    val client = remember { MessageWebViewClient() }
    client.context = context
    client.remoteImages = remoteImages
    AndroidView(
        modifier = modifier,
        factory = { android ->
            WebView(android).apply {
                layoutParams =
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                isVerticalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    @Suppress("DEPRECATION")
                    databaseEnabled = false
                    setSupportMultipleWindows(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                webViewClient = client
            }
        },
        update = { view ->
            val key = page.html to remoteImages
            if (view.tag != key) {
                view.tag = key
                view.setBackgroundColor(Color.parseColor(page.canvas.background))
                view.loadDataWithBaseURL(context.serverUrl, page.html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

private class MessageWebViewClient : WebViewClient() {
    lateinit var context: MessageWebContext
    var remoteImages: Boolean = false

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? =
        when (
            val decision =
                ReaderRequestPolicy.decide(
                    request.url.toString(),
                    context.serverUrl,
                    remoteImages,
                    request.requestHeaders["Accept"],
                )
        ) {
            // This runs on the web view's own network thread, so waiting here blocks no UI.
            is ReaderRequest.Attachment ->
                runBlocking { context.loadResource(decision) }
                    ?.let { WebResourceResponse(it.mimeType, null, ByteArrayInputStream(it.data)) }
                    ?: blocked()
            ReaderRequest.Network, ReaderRequest.Local -> null
            ReaderRequest.Block -> blocked()
        }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url.toString()
        // A link to a place in the message itself: the page scrolls to it, nothing leaves.
        if (request.url.fragment != null &&
            url.substringBefore('#').trimEnd('/') == context.serverUrl.trimEnd('/')
        ) {
            return false
        }
        context.onNavigate(ReaderNavigation.classify(url, request.hasGesture()))
        return true
    }

    private fun blocked() =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
}
