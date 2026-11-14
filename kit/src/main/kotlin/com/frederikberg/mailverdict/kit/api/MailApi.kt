package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.contract.BulkActionRequest
import com.frederikberg.mailverdict.contract.BulkActionResponse
import com.frederikberg.mailverdict.contract.FeedbackRequest
import com.frederikberg.mailverdict.contract.FeedbackResponse
import com.frederikberg.mailverdict.contract.FolderOrderResponse
import com.frederikberg.mailverdict.contract.FolderResponse
import com.frederikberg.mailverdict.contract.IdentityResponse
import com.frederikberg.mailverdict.contract.MessageActionRequest
import com.frederikberg.mailverdict.contract.MessageActionResponse
import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.contract.MessageListResponse
import com.frederikberg.mailverdict.contract.MessageQuoteResponse
import com.frederikberg.mailverdict.contract.SyncStatusResponse
import com.frederikberg.mailverdict.contract.ThreadResponse
import com.frederikberg.mailverdict.contract.UnifiedFolderResponse
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RawBody
import kotlinx.serialization.builtins.ListSerializer

suspend fun ApiClient.listAccounts(): List<AccountResponse> =
    send(
        ListSerializer(AccountResponse.serializer()),
        "/accounts",
    )

suspend fun ApiClient.getSyncStatus(accountId: String): SyncStatusResponse =
    send(SyncStatusResponse.serializer(), "/accounts/$accountId/sync-status")

suspend fun ApiClient.listFolders(accountId: String): List<FolderResponse> =
    send(ListSerializer(FolderResponse.serializer()), "/accounts/$accountId/folders")

/** An account's folders with counts, ordered as the person saved them (alphabetical when nothing was saved). */
suspend fun ApiClient.getFolderOrder(accountId: String): FolderOrderResponse =
    send(FolderOrderResponse.serializer(), "/accounts/$accountId/folder-order")

suspend fun ApiClient.listUnifiedFolders(): List<UnifiedFolderResponse> =
    send(ListSerializer(UnifiedFolderResponse.serializer()), "/unified/folders")

suspend fun ApiClient.listIdentities(accountId: String? = null): List<IdentityResponse> =
    send(ListSerializer(IdentityResponse.serializer()), "/identities", query = query("account_id" to accountId))

/** One page of an account's mail. [before] is the cursor of the previous page's end; [isSeen] filters to read or unread. */
suspend fun ApiClient.listMessages(
    accountId: String,
    folderId: String? = null,
    threaded: Boolean = true,
    isSeen: Boolean? = null,
    before: String? = null,
    after: String? = null,
    around: String? = null,
    limit: Int? = null,
): MessageListResponse =
    send(
        MessageListResponse.serializer(),
        "/accounts/$accountId/messages",
        query =
            query(
                "folder_id" to folderId,
                "threaded" to threaded,
                "is_seen" to isSeen,
                "before" to before,
                "after" to after,
                "around" to around,
                "limit" to limit,
            ),
    )

/** One page of a unified view, addressed by its name. */
suspend fun ApiClient.listUnifiedMessages(
    folderName: String,
    threaded: Boolean = true,
    isSeen: Boolean? = null,
    before: String? = null,
    limit: Int? = null,
): MessageListResponse =
    send(
        MessageListResponse.serializer(),
        "/unified/mails",
        query =
            query(
                "folder_name" to folderName,
                "threaded" to threaded,
                "is_seen" to isSeen,
                "before" to before,
                "limit" to limit,
            ),
    )

suspend fun ApiClient.getMessage(
    messageId: String,
    loadImages: Boolean = false,
): MessageDetail = send(MessageDetail.serializer(), "/messages/$messageId", query = query("load_images" to loadImages))

/** The whole conversation the message belongs to. */
suspend fun ApiClient.getThread(
    messageId: String,
    loadImages: Boolean = false,
): ThreadResponse =
    send(
        ThreadResponse.serializer(),
        "/messages/$messageId/thread",
        query =
            query(
                "load_images" to loadImages,
            ),
    )

suspend fun ApiClient.getQuote(messageId: String): MessageQuoteResponse =
    send(MessageQuoteResponse.serializer(), "/messages/$messageId/quote")

suspend fun ApiClient.getAttachment(
    messageId: String,
    attachmentId: String,
): RawBody = sendRaw("/messages/$messageId/attachments/$attachmentId")

suspend fun ApiClient.performAction(
    messageId: String,
    request: MessageActionRequest,
): MessageActionResponse =
    send(
        MessageActionResponse.serializer(),
        "/messages/$messageId/action",
        method = "POST",
        body = encode(MessageActionRequest.serializer(), request),
    )

suspend fun ApiClient.performBulkAction(
    accountId: String,
    request: BulkActionRequest,
): BulkActionResponse =
    send(
        BulkActionResponse.serializer(),
        "/accounts/$accountId/messages/bulk-action",
        method = "POST",
        body = encode(BulkActionRequest.serializer(), request),
    )

/** Tells the spam filter it was right or wrong about a message. */
suspend fun ApiClient.sendSpamFeedback(
    accountId: String,
    messageId: String,
    isSpam: Boolean,
): FeedbackResponse =
    send(
        FeedbackResponse.serializer(),
        "/mails/$messageId/feedback",
        method = "POST",
        query = query("account_id" to accountId),
        body = encode(FeedbackRequest.serializer(), FeedbackRequest(isSpam)),
    )
