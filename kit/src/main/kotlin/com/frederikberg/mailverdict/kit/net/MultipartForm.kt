package com.frederikberg.mailverdict.kit.net

import kotlinx.serialization.DeserializationStrategy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

/** One file of a multipart form: [name] is the form field, [filename] what the server stores it as. */
class FormFile(
    val name: String,
    val filename: String,
    val contentType: String?,
    val data: ByteArray,
)

/** A `multipart/form-data` body, built whole so a retried request sends the same bytes under the same boundary. */
class MultipartForm private constructor(
    val bytes: ByteArray,
    val contentType: String,
) {
    companion object {
        private val octetStream = "application/octet-stream".toMediaType()

        fun build(
            fields: List<Pair<String, String>>,
            files: List<FormFile>,
        ): MultipartForm {
            val body =
                MultipartBody
                    .Builder()
                    .setType(MultipartBody.FORM)
                    .apply {
                        fields.forEach { (name, value) -> addFormDataPart(name, value) }
                        files.forEach {
                            val type = it.contentType?.toMediaTypeOrNull() ?: octetStream
                            addFormDataPart(it.name, it.filename, it.data.toRequestBody(type))
                        }
                    }.build()
            val buffer = Buffer().also(body::writeTo)
            return MultipartForm(buffer.readByteArray(), body.contentType().toString())
        }
    }
}

/** Sends [form] and decodes the answer like [ApiClient.send]: same status rules, token renewal and sign-out hook. */
suspend fun <T> ApiClient.sendForm(
    strategy: DeserializationStrategy<T>,
    path: String,
    form: MultipartForm,
    method: String = "POST",
): T = send(strategy, path, method = method, body = form.bytes, contentType = form.contentType)
