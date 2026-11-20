package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.api.OutboxCreateResult
import com.frederikberg.mailverdict.kit.api.createOutbox
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What `POST /outbox` receives: the web's body for a reply, and the multipart form once there are files. */
class OutboxRequestTest {
    private val server = TestServer()
    private val api = ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, OkHttpClient())

    @After
    fun close() = server.close()

    private val sent =
        """{"id":"o1","account_id":"a1","kind":"send","status":"pending","created_at":"2026-11-14T09:00:00Z","updated_at":"2026-11-14T09:00:00Z"}"""

    private fun input(attachments: List<ComposeAttachment> = emptyList()) =
        SubmissionInput(
            accountId = "a1",
            kind = "send",
            to = listOf("ada@example.com"),
            cc = listOf("grace@example.com"),
            bcc = emptyList(),
            subject = "Re: Plan",
            body = "Option two.\n",
            authoredHtml = null,
            quote = ComposeQuote("<p>Which one?</p>", "On Fri, 14 Nov 2026, 08:12, Ada wrote:"),
            quotedText = "\n\nOn Fri, 14 Nov 2026, 08:12, Ada wrote:\n> Which one?",
            inReplyTo = "<m1@example.com>",
            references = listOf("<m1@example.com>"),
            identityId = "i1",
            replacesMessageId = null,
            attachments = attachments,
            idempotencyKey = "k1",
        )

    @Test
    fun aReplyIsTheJsonBodyTheWebSends() {
        server.script("/api/outbox", TestServer.json(sent, 201))
        val (request, uploads) = ComposeSubmission.build(input())
        val result = runBlocking { api.createOutbox(request, uploads) }

        assertTrue(result is OutboxCreateResult.Sent)
        val recorded = server.requestsTo("/api/outbox").single()
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals(JsonPrimitive("send"), body["kind"])
        assertEquals(
            JsonPrimitive("Option two.\n\nOn Fri, 14 Nov 2026, 08:12, Ada wrote:\n> Which one?"),
            body["body_text"],
        )
        assertEquals(
            JsonPrimitive(
                "<p>Option two.</p>" + ComposeHtml.quoteHtml(input().quote!!),
            ),
            body["body_html"],
        )
        assertEquals(JsonPrimitive("i1"), body["identity_id"])
        assertEquals(JsonPrimitive("<m1@example.com>"), body["in_reply_to"])
        assertEquals(listOf("grace@example.com"), body["cc"]!!.jsonArray.map { (it as JsonPrimitive).content })
        assertEquals(JsonPrimitive("k1"), body["idempotency_key"])
    }

    @Test
    fun filesGoAsAMultipartFormBesideTheSameJson() {
        server.script(
            "/api/outbox",
            TestServer.json(
                """{"id":"p1","account_id":"a1","send_after":"2026-11-14T09:00:10Z","created_at":"2026-11-14T09:00:00Z"}""",
                201,
            ),
        )
        val file = ComposeAttachment("floor plan.pdf", "application/pdf", "%PDF-1.4".toByteArray())
        val (request, uploads) = ComposeSubmission.build(input(listOf(file)))
        val result = runBlocking { api.createOutbox(request, uploads) }

        assertTrue(result is OutboxCreateResult.Pending)
        val recorded = server.requestsTo("/api/outbox").single()
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary="))
        val body = recorded.body!!.utf8()
        assertTrue(body, "Content-Disposition: form-data; name=\"data\"" in body)
        assertTrue(body, "Content-Disposition: form-data; name=\"attachments\"; filename=\"floor plan.pdf\"" in body)
        assertTrue(body, "Content-Type: application/pdf" in body)
        assertTrue(body, "%PDF-1.4" in body)
        val json = body.substringAfter("name=\"data\"").substringAfter("\r\n\r\n").substringBefore("\r\n--")
        assertEquals(JsonPrimitive("Re: Plan"), (Json.parseToJsonElement(json) as JsonObject)["subject"])
    }

    @Test
    fun aDraftCarriesWhatItReplaces() {
        server.script("/api/outbox", TestServer.json(sent, 201))
        val draft =
            input().copy(
                kind = "draft",
                replacesMessageId = "d1",
                authoredHtml = "<p><strong>Kept</strong></p>",
            )
        val (request, _) = ComposeSubmission.build(draft)
        runBlocking { api.createOutbox(request) }
        val body =
            Json
                .parseToJsonElement(
                    server
                        .requestsTo("/api/outbox")
                        .single()
                        .body!!
                        .utf8(),
                ).jsonObject
        assertEquals(JsonPrimitive("draft"), body["kind"])
        assertEquals(JsonPrimitive("d1"), body["replaces_message_id"])
        assertTrue(body["body_html"].toString().startsWith("\"<p><strong>Kept</strong></p><div"))
    }
}
