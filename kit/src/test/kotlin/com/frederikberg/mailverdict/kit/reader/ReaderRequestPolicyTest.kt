package com.frederikberg.mailverdict.kit.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderRequestPolicyTest {
    private val server = "https://mail.example.com"
    private val message = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"
    private val attachment = "1b1b1b1b-1b1b-4b1b-8b1b-1b1b1b1b1b1b"

    private fun decide(
        url: String,
        images: Boolean = false,
        accept: String? = null,
        serverUrl: String = server,
    ) = ReaderRequestPolicy.decide(url, serverUrl, images, accept)

    @Test
    fun anInlinePartOfTheServerIsFetchedWithTheCredentialWhateverTheCaseOfItsIds() {
        val expected = ReaderRequest.Attachment(message, attachment)
        assertEquals(expected, decide("$server/api/messages/$message/attachments/$attachment"))
        assertEquals(
            expected,
            decide(
                "https://MAIL.example.com:443/api/messages/${message.uppercase()}/attachments/${attachment.uppercase()}",
            ),
        )
    }

    @Test
    fun noOtherPathOfTheServerIsEverReachedForAMessage() {
        assertEquals(ReaderRequest.Block, decide("$server/api/me"))
        assertEquals(ReaderRequest.Block, decide("$server/api/messages/$message/attachments/$attachment/../../me"))
        assertEquals(
            ReaderRequest.Block,
            decide("$server/api/messages/$message/action", accept = "image/png", images = true),
        )
        assertEquals(ReaderRequest.Block, decide("$server/"))
    }

    @Test
    fun anotherServerOrPortOrSchemeIsNotTheSameOrigin() {
        val path = "/api/messages/$message/attachments/$attachment"
        assertEquals(ReaderRequest.Block, decide("https://evil.example.com$path"))
        assertEquals(ReaderRequest.Block, decide("https://mail.example.com:8443$path"))
        assertEquals(ReaderRequest.Block, decide("http://mail.example.com$path"))
        assertEquals(ReaderRequest.Block, decide("https://mail.example.com.evil.net$path"))
    }

    @Test
    fun aServerOnANonDefaultPortMatchesOnlyThatPort() {
        val path = "/api/messages/$message/attachments/$attachment"
        assertEquals(
            ReaderRequest.Attachment(message, attachment),
            decide("http://10.0.2.2:8000$path", serverUrl = "http://10.0.2.2:8000"),
        )
        assertEquals(ReaderRequest.Block, decide("http://10.0.2.2:9000$path", serverUrl = "http://10.0.2.2:8000"))
    }

    @Test
    fun remoteContentIsBlockedUntilThePersonAsksForImagesAndThenOnlyImagesPass() {
        val pixel = "https://tracker.example.net/p.gif"
        assertEquals(ReaderRequest.Block, decide(pixel, images = false, accept = "image/webp,image/*"))
        assertEquals(ReaderRequest.Network, decide(pixel, images = true, accept = "image/webp,image/*"))
        assertEquals(ReaderRequest.Block, decide("https://cdn.example.net/app.js", images = true, accept = "*/*"))
        assertEquals(
            ReaderRequest.Block,
            decide("https://cdn.example.net/font.woff2", images = true, accept = "application/font-woff2"),
        )
        assertEquals(
            ReaderRequest.Block,
            decide("https://cdn.example.net/style.css", images = true, accept = "text/css,*/*;q=0.1"),
        )
        assertEquals(ReaderRequest.Block, decide(pixel, images = true, accept = null))
    }

    @Test
    fun inlineDataImagesNeedNoNetworkAndEveryOtherSchemeIsBlocked() {
        assertEquals(ReaderRequest.Local, decide("data:image/png;base64,AAAA"))
        assertEquals(ReaderRequest.Local, decide("about:blank"))
        assertEquals(ReaderRequest.Block, decide("cid:logo@example.com"))
        assertEquals(ReaderRequest.Block, decide("file:///etc/hosts"))
        assertEquals(ReaderRequest.Block, decide("content://com.android.contacts/contacts"))
        assertEquals(ReaderRequest.Block, decide("javascript:alert(1)"))
        assertEquals(ReaderRequest.Block, decide("not a url"))
    }

    @Test
    fun linksOpenOnlyWhenThePersonTappedThem() {
        assertEquals(
            ReaderNavigation.Web("https://example.com/a"),
            ReaderNavigation.classify("https://example.com/a", isUserAction = true),
        )
        assertEquals(ReaderNavigation.Ignore, ReaderNavigation.classify("https://example.com/a", isUserAction = false))
        assertEquals(
            ReaderNavigation.Mailto("mailto:a@example.com"),
            ReaderNavigation.classify("mailto:a@example.com", true),
        )
        assertEquals(ReaderNavigation.Phone("tel:+4912345"), ReaderNavigation.classify("tel:+4912345", true))
        assertEquals(ReaderNavigation.Anchor("section-2"), ReaderNavigation.classify("about:blank#section-2", true))
        assertEquals(ReaderNavigation.Ignore, ReaderNavigation.classify("intent://scan/#Intent;end", true))
        assertEquals(ReaderNavigation.Ignore, ReaderNavigation.classify("javascript:alert(1)", true))
    }
}
