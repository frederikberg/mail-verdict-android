package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.contract.AddressList
import com.frederikberg.mailverdict.contract.MessageDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

/** The web's `reply.test.ts`, case for case, then forward and the headers. */
class ComposeReplyTest {
    private fun message(
        from: String? = "Alice <alice@example.com>",
        to: List<String> = listOf("me@example.com"),
        cc: List<String>? = null,
        replyTo: String? = null,
        subject: String? = "Lunch tomorrow?",
        references: List<String>? = null,
        messageId: String? = "<msg-1@example.com>",
    ) = MessageDetail(
        id = "msg-1",
        accountId = "acct-1",
        folderId = "folder-1",
        threadId = "thread-1",
        messageId = messageId,
        subject = subject,
        fromAddr = from,
        toAddrs = AddressList(to),
        ccAddrs = cc?.let(::AddressList),
        replyTo = replyTo,
        references = references,
        receivedAt = "2026-09-10T12:00:00Z",
        createdAt = "2026-09-10T12:00:00Z",
        bodyText = "Sure, what time?",
    )

    private fun reply(
        source: MessageDetail,
        own: List<String> = listOf("me@example.com"),
        mode: ReplyMode = ReplyMode.Reply,
    ) = ComposeReply.reply(source, own, mode, ZoneOffset.UTC)

    @Test
    fun withNoReplyToAReplyGoesToFrom() {
        assertEquals(listOf("alice@example.com"), reply(message()).to)
    }

    @Test
    fun aReplyToHeaderOverridesFrom() {
        assertEquals(listOf("support@example.com"), reply(message(replyTo = "Alice Support <support@example.com>")).to)
    }

    @Test
    fun everyAddressInAMultiAddressReplyToIsTargeted() {
        val draft = reply(message(replyTo = "Bob <bob@example.com>, Carol <carol@example.com>"))
        assertEquals(listOf("bob@example.com", "carol@example.com"), draft.to)
    }

    @Test
    fun aCommaInsideAQuotedDisplayNameDoesNotSplitTheEntry() {
        val draft = reply(message(replyTo = "\"Doe, Jane\" <jane@example.com>, Bob <bob@example.com>"))
        assertEquals(listOf("jane@example.com", "bob@example.com"), draft.to)
    }

    @Test
    fun replyAllAddsTheOriginalToAndCcMinusTheOwnAddressAndTheNewTo() {
        val draft =
            reply(
                message(
                    replyTo = "Bob <bob@example.com>",
                    to = listOf("me@example.com", "Bob <bob@example.com>"),
                    cc = listOf("Carol <carol@example.com>"),
                ),
                mode = ReplyMode.ReplyAll,
            )
        assertEquals(listOf("bob@example.com"), draft.to)
        assertEquals(listOf("carol@example.com"), draft.cc)
    }

    @Test
    fun replyAllExcludesEveryIdentityAddressOfTheAccountNotOnlyItsLoginAddress() {
        val draft =
            reply(
                message(
                    to = listOf("me@example.com", "team-alias@example.com"),
                    cc = listOf("Carol <carol@example.com>"),
                ),
                own = listOf("me@example.com", "team-alias@example.com"),
                mode = ReplyMode.ReplyAll,
            )
        assertEquals(listOf("carol@example.com"), draft.cc)
    }

    @Test
    fun aReplyToAMessageSentFromAnOwnAliasGoesToItsOriginalTo() {
        val draft =
            reply(
                message(
                    from = "Me <team-alias@example.com>",
                    to = listOf("Bob <bob@example.com>"),
                    cc = listOf("Carol <carol@example.com>"),
                ),
                own = listOf("me@example.com", "team-alias@example.com"),
            )
        assertEquals(listOf("bob@example.com"), draft.to)
        assertEquals(emptyList<String>(), draft.cc)
    }

    @Test
    fun replyAllToAnOwnSentMessageKeepsToAndCcMinusOwnAddresses() {
        val draft =
            reply(
                message(
                    from = "me@example.com",
                    to = listOf("Bob <bob@example.com>", "me@example.com"),
                    cc = listOf("Carol <carol@example.com>", "team-alias@example.com"),
                ),
                own = listOf("me@example.com", "team-alias@example.com"),
                mode = ReplyMode.ReplyAll,
            )
        assertEquals(listOf("bob@example.com"), draft.to)
        assertEquals(listOf("carol@example.com"), draft.cc)
    }

    @Test
    fun aMessageSentOnlyToOneselfIsAnsweredToItsSender() {
        assertEquals(
            listOf("me@example.com"),
            reply(message(from = "me@example.com", to = listOf("me@example.com"))).to,
        )
    }

    @Test
    fun theSubjectGainsReOnceAndThreadingHeadersNameTheOriginal() {
        val draft = reply(message(references = listOf("<root@example.com>")))
        assertEquals("Re: Lunch tomorrow?", draft.subject)
        assertEquals("RE: Lunch tomorrow?", reply(message(subject = "RE: Lunch tomorrow?")).subject)
        assertEquals("Re: (no subject)", reply(message(subject = null)).subject)
        assertEquals("<msg-1@example.com>", draft.inReplyTo)
        assertEquals(listOf("<root@example.com>", "<msg-1@example.com>"), draft.references)
        assertNull(reply(message(messageId = null)).references)
    }

    @Test
    fun theQuoteIsTheAttributionAndTheOriginalPrefixedLineByLine() {
        val draft = reply(message())
        assertEquals("On Thu, 10 Sep 2026, 12:00, Alice wrote:", draft.attribution)
        assertEquals("\n\nOn Thu, 10 Sep 2026, 12:00, Alice wrote:\n> Sure, what time?", draft.quotedText)
    }

    @Test
    fun aForwardPrefixesFwdOnceAndCarriesTheOriginalsHeaderBlock() {
        val forward = ComposeReply.forward(message(), ZoneOffset.UTC)
        assertEquals("Fwd: Lunch tomorrow?", forward.subject)
        assertEquals("Fw: x", ComposeReply.forward(message(subject = "Fw: x"), ZoneOffset.UTC).subject)
        assertEquals(
            listOf(
                "---------- Forwarded message ----------",
                "From: Alice <alice@example.com>",
                "Date: Thu, 10 Sep 2026, 12:00",
                "Subject: Lunch tomorrow?",
                "To: me@example.com",
            ).joinToString("\n"),
            forward.attribution,
        )
        assertEquals("\n\n${forward.attribution}\n> Sure, what time?", forward.quotedText)
    }

    @Test
    fun aDraftsPlainQuoteIsWhatFollowsItsAttribution() {
        val body = "Fine by me.\n\nOn Thu, 10 Sep 2026, 12:00, Alice wrote:\n> Sure, what time?"
        assertEquals(
            "\n\nOn Thu, 10 Sep 2026, 12:00, Alice wrote:\n> Sure, what time?",
            ComposeReply.draftQuotedText(body, "On Thu, 10 Sep 2026, 12:00, Alice wrote:"),
        )
        assertEquals("", ComposeReply.draftQuotedText(body, "On another day, someone wrote:"))
    }
}
