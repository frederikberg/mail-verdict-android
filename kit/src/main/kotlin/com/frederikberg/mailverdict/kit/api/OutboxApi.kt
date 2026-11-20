package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.contract.OutboxCreateRequest
import com.frederikberg.mailverdict.contract.OutboxResponse
import com.frederikberg.mailverdict.contract.PendingSendResponse
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RawBody
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

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

/** One file sent with a message. */
class OutboxUpload(
    val filename: String,
    val contentType: String?,
    val data: ByteArray,
)

/**
 * Sends or saves a message: plain JSON without attachments, otherwise the multipart form the route
 * also takes, a `data` field holding the same JSON and one `attachments` file field per upload.
 */
suspend fun ApiClient.createOutbox(
    request: OutboxCreateRequest,
    attachments: List<OutboxUpload> = emptyList(),
): OutboxCreateResult {
    val json = encode(OutboxCreateRequest.serializer(), request)
    if (attachments.isEmpty()) {
        return send(OutboxCreateResultSerializer, "/outbox", method = "POST", body = json)
    }
    val form = OutboxForm.build(json, attachments)
    return send(
        OutboxCreateResultSerializer,
        "/outbox",
        method = "POST",
        body = form.bytes,
        contentType = form.contentType,
    )
}

/** The multipart body of [createOutbox], built whole so a retried request is the same bytes. */
internal object OutboxForm {
    class Built(
        val bytes: ByteArray,
        val contentType: String,
    )

    private val octetStream = "application/octet-stream".toMediaType()

    fun build(
        json: ByteArray,
        attachments: List<OutboxUpload>,
    ): Built {
        val body =
            MultipartBody
                .Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("data", json.decodeToString())
                .apply {
                    attachments.forEach {
                        val type = it.contentType?.toMediaTypeOrNull() ?: octetStream
                        addFormDataPart("attachments", it.filename, it.data.toRequestBody(type))
                    }
                }.build()
        val buffer = Buffer().also(body::writeTo)
        return Built(buffer.readByteArray(), body.contentType().toString())
    }
}

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

/** A staged send's file; still served after the send is cancelled, which is when Undo asks for it. */
suspend fun ApiClient.getPendingSendAttachment(
    pendingSendId: String,
    attachmentId: String,
): RawBody = sendRaw("/outbox/pending/$pendingSendId/attachments/$attachmentId")
