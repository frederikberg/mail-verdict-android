package com.frederikberg.mailverdict.kit.fixtures

/**
 * Canned mail for the list and the reader: one inbox of mixed shapes (a conversation, a flagged
 * newsletter, spam, a message with no subject, one with absurdly long text, an old one) and each
 * message's conversation. Keys carry the `/api` prefix like the rest of [Fixtures].
 */
object MailFixtures {
    const val INBOX = "00000000-0000-4000-8000-0000000001f1"
    const val SENT = "00000000-0000-4000-8000-0000000001f3"

    private fun id(n: Int) = "00000000-0000-4000-8000-0000000010" + n.toString().padStart(2, '0')

    private fun thread(n: Int) = "00000000-0000-4000-8000-0000000020" + n.toString().padStart(2, '0')

    private fun quote(text: String) =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun nullable(text: String?) = text?.let(::quote) ?: "null"

    private class Mail(
        val n: Int,
        val thread: Int = n,
        val from: String,
        val subject: String?,
        val snippet: String?,
        val receivedAt: String,
        val seen: Boolean = true,
        val flagged: Boolean = false,
        val answered: Boolean = false,
        val spam: Boolean? = null,
        val attachments: Boolean = false,
        val threadCount: Int? = null,
        val unreadInThread: Int? = null,
        val text: String? = null,
        val html: String? = null,
        val blockedImages: Boolean = false,
    ) {
        val summary: String get() =
            """
            {"id":"${id(
                n,
            )}","account_id":"${Fixtures.PRIVATE_ACCOUNT}","folder_id":"$INBOX","thread_id":"${thread(thread)}",
             "subject":${nullable(subject)},"from_addr":${quote(from)},"to_addrs":["alex@example.com"],
             "received_at":"$receivedAt","is_seen":$seen,"is_flagged":$flagged,"is_answered":$answered,
             "snippet":${nullable(
                snippet,
            )},"thread_count":${threadCount ?: "null"},"unread_in_thread":${unreadInThread ?: "null"},
             "mirrored_at":"$receivedAt","has_attachments":$attachments,"verdict_is_spam":${spam ?: "null"}}
            """.trimIndent()

        val detail: String get() =
            """
            {"id":"${id(
                n,
            )}","account_id":"${Fixtures.PRIVATE_ACCOUNT}","folder_id":"$INBOX","thread_id":"${thread(thread)}",
             "subject":${nullable(subject)},"from_addr":${quote(from)},"to_addrs":["Alex Example <alex@example.com>"],
             "received_at":"$receivedAt","created_at":"$receivedAt","is_seen":$seen,"is_flagged":$flagged,
             "snippet":${nullable(snippet)},"body_text":${nullable(text)},"body_html":${nullable(html)},
             "has_blocked_images":$blockedImages,
             "attachments":${if (attachments) {
                """[{"id":"00000000-0000-4000-8000-0000000030${n.toString().padStart(
                    2,
                    '0',
                )}","filename":"floor-plan.pdf","content_type":"application/pdf","size_bytes":482113}]"""
            } else {
                "[]"
            }}}
            """.trimIndent()
    }

    private val longText = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(800)

    private val mails =
        listOf(
            Mail(
                1,
                thread = 1,
                from = "Ada Lovelace <ada@example.com>",
                subject = "Quarterly planning",
                snippet =
                    "I put the three options into the shared document. " +
                        "The second one needs your sign-off before Friday.",
                receivedAt = "2026-11-14T08:12:00+00:00",
                seen = false,
                attachments = true,
                threadCount = 3,
                unreadInThread = 2,
                text =
                    "Hi Alex,\n\nI put the three options into the shared document: https://example.com/plan.\n" +
                        "The second one needs your sign-off before Friday.\n\nAda",
            ),
            Mail(
                2,
                from = "The Weekly Letter <news@letter.example.net>",
                subject =
                    "Ten things we learned about mail this week, and why the seventh one " +
                        "will surprise you if you read it to the end",
                snippet =
                    "Open in browser. Our newsletter this week is a long one, with pictures and a table of contents.",
                receivedAt = "2026-11-14T06:30:00+00:00",
                seen = false,
                flagged = true,
                text = null,
                html =
                    "<h2>Ten things</h2><p>Our newsletter this week is a long one.</p>" +
                        "<img src=\"https://cdn.example.net/hero.png\" alt=\"hero\">",
                blockedImages = true,
            ),
            Mail(
                3,
                from = "Prize Desk <win@spam.example.org>",
                subject = "You won a prize!!!",
                snippet = "Claim now",
                receivedAt = "2026-11-13T19:45:00+00:00",
                spam = true,
                text = "Claim now.",
            ),
            Mail(
                4,
                from = "noreply@example.com",
                subject = null,
                snippet = null,
                receivedAt = "2026-11-12T10:00:00+00:00",
            ),
            Mail(
                5,
                from = "Grace Hopper <grace@example.com>",
                subject = "S".repeat(600),
                snippet = longText.take(2000),
                receivedAt = "2026-11-11T09:05:00+00:00",
                seen = false,
                text = longText,
            ),
            Mail(
                6,
                from = "Linus <linus@example.com>",
                subject = "Re: kernel parameters",
                snippet = "That did it, thanks. The machine boots in half the time now.",
                receivedAt = "2026-11-09T21:00:00+00:00",
                answered = true,
                text = "That did it, thanks. The machine boots in half the time now.",
            ),
            Mail(
                7,
                from = "Barbara Liskov <barbara@example.com>",
                subject = "Last year's notes",
                snippet = "Attaching them again in case they went missing.",
                receivedAt = "2025-03-02T12:00:00+00:00",
                text = "Attaching them again in case they went missing.",
            ),
        )

    /** Every message's conversation: message 1 belongs to one of three, the rest are alone. */
    private val conversation1 =
        listOf(
            Mail(
                8,
                thread = 1,
                from = "Alex Example <alex@example.com>",
                subject = "Quarterly planning",
                snippet = "Can you draft the options?",
                receivedAt = "2026-11-12T09:00:00+00:00",
                text = "Can you draft the options?",
            ),
            Mail(
                9,
                thread = 1,
                from = "Ada Lovelace <ada@example.com>",
                subject = "Re: Quarterly planning",
                snippet = "First draft is in.",
                receivedAt = "2026-11-13T15:00:00+00:00",
                seen = false,
                text = "First draft is in.",
            ),
            mails[0],
        )

    val routes: Map<String, String> =
        buildMap {
            put(
                "GET /api/accounts/${Fixtures.PRIVATE_ACCOUNT}/messages",
                """{"messages":[${mails.joinToString(
                    ",",
                ) { it.summary }}],"has_more":false,"as_of":"2026-11-14T09:00:00+00:00"}""",
            )
            put(
                "GET /api/accounts/${Fixtures.PRIVATE_ACCOUNT}/folders",
                """[{"id":"$INBOX","account_id":"${Fixtures.PRIVATE_ACCOUNT}","imap_name":"INBOX","special_use":"inbox",
                    "total_count":1204,"unread_count":12}]""",
            )
            put(
                "GET /api/unified/mails",
                """{"messages":[${mails.joinToString(",") { it.summary }}],"has_more":false}""",
            )
            mails.drop(1).forEach { put("GET /api/messages/${id(it.n)}/thread", """{"messages":[${it.detail}]}""") }
            put(
                "GET /api/messages/${id(1)}/thread",
                """{"messages":[${conversation1.joinToString(",") { it.detail }}]}""",
            )
            put(
                "POST /api/messages/${id(1)}/action",
                """{"message_id":"${id(1)}","action":"mark_read","success":true}""",
            )
        }
}
