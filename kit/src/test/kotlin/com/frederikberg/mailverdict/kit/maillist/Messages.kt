package com.frederikberg.mailverdict.kit.maillist

import com.frederikberg.mailverdict.contract.AddressList
import com.frederikberg.mailverdict.contract.MessageSummary

/** Row ids and times that sort the way the server's `received_at DESC, id DESC` does: a higher [n] is newer. */
fun message(
    n: Int,
    seen: Boolean = true,
    folder: String = "f1",
    thread: String = "t$n",
    subject: String? = "Subject $n",
    snippet: String? = "Snippet $n",
    from: String? = "Sender $n <s$n@example.com>",
    receivedAt: String? = String.format("2026-11-01T%02d:%02d:00+00:00", n / 60, n % 60),
) = MessageSummary(
    id = "00000000-0000-4000-8000-" + String.format("%012d", n),
    accountId = "a1",
    folderId = folder,
    threadId = thread,
    subject = subject,
    fromAddr = from,
    toAddrs = AddressList(listOf("me@example.com")),
    receivedAt = receivedAt,
    isSeen = seen,
    snippet = snippet,
    mirroredAt = "2026-11-01T00:00:00+00:00",
    hasAttachments = false,
)

fun id(n: Int) = message(n).id

/** Newest first, the order a list holds. */
fun rows(range: IntProgression) = range.map { message(it) }
