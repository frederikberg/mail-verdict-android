package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.contract.OutboxCreateRequest
import com.frederikberg.mailverdict.contract.OutboxResponse
import com.frederikberg.mailverdict.contract.PendingSendResponse
import com.frederikberg.mailverdict.kit.net.ApiClient
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What `POST /outbox` answers: a plain send or draft becomes an [OutboxResponse] at once, a send held
 * inside its undo window comes back as a [PendingSendResponse] instead. The two differ only by
 * content, and `send_after` is the field only the pending one has.
 */
sealed interface OutboxCreateResult {
    data class Sent(
        val row: OutboxResponse,
    ) : OutboxCreateResult

    data class Pending(
        val send: PendingSendResponse,
    ) : OutboxCreateResult
}

private object OutboxCreateResultSerializer : DeserializationStrategy<OutboxCreateResult> {
    override val descriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): OutboxCreateResult {
        val element = JsonElement.serializer().deserialize(decoder)
        return if (element is JsonObject && "send_after" in element) {
            OutboxCreateResult.Pending(ContractJson.decodeFromJsonElement(PendingSendResponse.serializer(), element))
        } else {
            OutboxCreateResult.Sent(ContractJson.decodeFromJsonElement(OutboxResponse.serializer(), element))
        }
    }
}

/** Sends or saves a message as JSON. Attachments need the multipart form, which this client does not yet offer. */
suspend fun ApiClient.createOutbox(request: OutboxCreateRequest): OutboxCreateResult =
    send(
        OutboxCreateResultSerializer,
        "/outbox",
        method = "POST",
        body = encode(OutboxCreateRequest.serializer(), request),
    )

suspend fun ApiClient.listOutbox(
    accountId: String? = null,
    status: String? = null,
): List<OutboxResponse> =
    send(
        ListSerializer(OutboxResponse.serializer()),
        "/outbox",
        query =
            query(
                "account_id" to accountId,
                "status" to status,
            ),
    )

suspend fun ApiClient.listPendingSends(accountId: String? = null): List<PendingSendResponse> =
    send(ListSerializer(PendingSendResponse.serializer()), "/outbox/pending", query = query("account_id" to accountId))

/** Takes back a send still inside its undo window. */
suspend fun ApiClient.cancelPendingSend(
    pendingSendId: String,
) = sendNoContent("/outbox/pending/$pendingSendId/cancel", "POST")
