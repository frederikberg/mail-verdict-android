package com.frederikberg.mailverdict.kit.reader

import java.net.URI

/** What the reader does with one request a message page makes. */
sealed interface ReaderRequest {
    /** An inline part of a message, fetched with the app's credential, which a web view cannot attach to a subresource itself. */
    data class Attachment(
        val messageId: String,
        val attachmentId: String,
    ) : ReaderRequest

    /** Let the web view fetch it itself: a remote image the person chose to load. */
    data object Network : ReaderRequest

    /** Answered empty: nothing reaches the network or the API. */
    data object Block : ReaderRequest

    /** Left to the web view because it needs no network: `data:` images and the page itself. */
    data object Local : ReaderRequest
}

/**
 * The single decision about what a message page may load. Everything is blocked except the
 * attachment paths the server rewrites inline images to, inline `data:` images, and, once the person
 * chose to show remote images, images from anywhere (and only images). The server already strips
 * remote images for senders not on the allow list, so this is defence in depth: it is what stops a
 * stylesheet font, a CSS `url()` the server missed or a tracking pixel from reaching the network.
 */
object ReaderRequestPolicy {
    /** The same-origin path the server rewrites a `cid:` image to. */
    private val attachmentPath =
        Regex("^/api/messages/([0-9a-fA-F-]{36})/attachments/([0-9a-fA-F-]{36})$")

    /**
     * [serverUrl] is the server the app is signed in to; [acceptHeader] the request's `Accept`, whose
     * `image/` prefix is how a web view says it wants an image, since the request carries no resource type.
     */
    fun decide(
        url: String,
        serverUrl: String,
        remoteImagesAllowed: Boolean,
        acceptHeader: String?,
    ): ReaderRequest {
        val target = runCatching { URI(url) }.getOrNull() ?: return ReaderRequest.Block
        val scheme = target.scheme?.lowercase() ?: return ReaderRequest.Block
        return when (scheme) {
            "data", "about" -> ReaderRequest.Local
            "http", "https" -> decideWeb(target, serverUrl, remoteImagesAllowed, acceptHeader)
            else -> ReaderRequest.Block
        }
    }

    private fun decideWeb(
        target: URI,
        serverUrl: String,
        remoteImagesAllowed: Boolean,
        acceptHeader: String?,
    ): ReaderRequest {
        if (sameOrigin(target, serverUrl)) {
            val match = attachmentPath.matchEntire(target.rawPath.orEmpty())
            // Any other path of the server, the API included, would carry the credential for the message's benefit.
            return match?.let { ReaderRequest.Attachment(it.groupValues[1].lowercase(), it.groupValues[2].lowercase()) }
                ?: ReaderRequest.Block
        }
        val wantsImage = acceptHeader?.trimStart()?.startsWith("image/", ignoreCase = true) == true
        return if (remoteImagesAllowed && wantsImage) ReaderRequest.Network else ReaderRequest.Block
    }

    private fun sameOrigin(
        target: URI,
        serverUrl: String,
    ): Boolean {
        val server = runCatching { URI(serverUrl.trim()) }.getOrNull() ?: return false
        return target.scheme.equals(server.scheme, ignoreCase = true) &&
            target.host.equals(server.host, ignoreCase = true) &&
            port(target) == port(server)
    }

    private fun port(uri: URI): Int =
        when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
}

/** What a tapped link in a message means. Page script is off, so the page only ever navigates. */
sealed interface ReaderNavigation {
    data class Web(
        val url: String,
    ) : ReaderNavigation

    data class Mailto(
        val url: String,
    ) : ReaderNavigation

    data class Phone(
        val url: String,
    ) : ReaderNavigation

    /** An in-page `#fragment`, typically a newsletter's own table of contents. */
    data class Anchor(
        val fragment: String,
    ) : ReaderNavigation

    data object Ignore : ReaderNavigation

    companion object {
        /** A navigation the page started by itself (a redirect, a script-less refresh) is never followed. */
        fun classify(
            url: String,
            isUserAction: Boolean,
        ): ReaderNavigation {
            if (!isUserAction) return Ignore
            val uri = runCatching { URI(url) }.getOrNull() ?: return Ignore
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> Web(url)
                "mailto" -> Mailto(url)
                "tel", "sms" -> Phone(url)
                "about" -> uri.fragment?.takeIf { it.isNotEmpty() }?.let(::Anchor) ?: Ignore
                else -> Ignore
            }
        }
    }
}
