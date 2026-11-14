package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.kit.net.awaitRaw
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.OffsetDateTime

/** The push relay refused or could not be reached; [message] is in words for the person. */
class PushRelayException(
    message: String,
) : Exception(message)

/** What a relay issued for one device: an opaque ticket the server presents to deliver, and when it stops working. */
class RelayTicket(
    val ticket: String,
    val expiresAtMs: Long,
)

/**
 * The phone's side of the push relay's registration (`POST /v1/register`): it hands over the platform's
 * push token and gets back a sealed, expiring ticket, which goes to the user's own server together with
 * a content key the relay never sees.
 */
class PushRelayClient(
    httpClient: OkHttpClient,
) {
    private val http = httpClient.newBuilder().followRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class RegisterRequest(
        val app: String,
        val platform: String,
        val token: String,
    )

    @Serializable
    private class RegisterResponse(
        val ticket: String,
        @SerialName("expires_at") val expiresAt: String,
    )

    suspend fun register(
        relayUrl: String,
        app: String,
        platform: String,
        token: String,
    ): RelayTicket {
        val base =
            relayUrl.trimEnd('/').toHttpUrlOrNull()?.takeIf { it.scheme == "https" || it.scheme == "http" }
                ?: throw PushRelayException("The push relay's address is not a valid URL.")
        val request =
            Request
                .Builder()
                .url(base.newBuilder().addPathSegments("v1/register").build())
                .header("Accept", "application/json")
                .post(
                    json
                        .encodeToString(RegisterRequest(app, platform, token))
                        .toRequestBody("application/json".toMediaType()),
                ).build()
        val response =
            try {
                http.newCall(request).awaitRaw()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw PushRelayException("The push relay could not be reached.")
            }
        return when (response.code) {
            in 200..299 ->
                try {
                    val decoded = json.decodeFromString<RegisterResponse>(response.body.decodeToString())
                    RelayTicket(decoded.ticket, OffsetDateTime.parse(decoded.expiresAt).toInstant().toEpochMilli())
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    throw PushRelayException("The push relay's answer could not be read.")
                }
            429 -> throw PushRelayException("The push relay is busy. Try again in a moment.")
            400 ->
                throw PushRelayException(
                    "The push relay refused this phone: ${response.body.decodeToString().take(
                        MAX_REFUSAL_CHARS,
                    ).trim()}",
                )
            else -> throw PushRelayException("The push relay answered HTTP ${response.code}.")
        }
    }

    private companion object {
        const val MAX_REFUSAL_CHARS = 200
    }
}
