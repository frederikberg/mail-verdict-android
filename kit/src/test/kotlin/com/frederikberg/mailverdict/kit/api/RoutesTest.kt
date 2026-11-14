package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.BulkActionKind
import com.frederikberg.mailverdict.contract.BulkActionRequest
import com.frederikberg.mailverdict.contract.MessageActionKind
import com.frederikberg.mailverdict.contract.MessageActionRequest
import com.frederikberg.mailverdict.contract.OutboxCreateRequest
import com.frederikberg.mailverdict.contract.PushChannel
import com.frederikberg.mailverdict.contract.PushSubscriptionCreate
import com.frederikberg.mailverdict.contract.PushSubscriptionKeys
import com.frederikberg.mailverdict.contract.PushSubscriptionUpdate
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.net.RequestFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Every route the client names exists in the server's published contract, with a method it accepts,
 * query parameters it declares, and a body whose fields its schema lists. The calls fail at the
 * network on purpose: the request is what is under test.
 */
class RoutesTest {
    private val sent = mutableListOf<Request>()

    private val api =
        ApiClient(
            RequestFactory("https://mail.example.com", "Test phone") { AuthMode.None },
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    sent += chain.request()
                    throw IOException("recorded")
                }.build(),
        )

    private val contract: JsonObject =
        File("../contract/src/test/resources/api-contract/openapi.json")
            .also {
                assertTrue("run from the kit module: ${it.absolutePath}", it.exists())
            }.readText()
            .let { Json.parseToJsonElement(it).jsonObject }

    private val paths = contract.getValue("paths").jsonObject
    private val schemas =
        contract
            .getValue("components")
            .jsonObject
            .getValue("schemas")
            .jsonObject

    private val id = "00000000-0000-0000-0000-000000000001"

    private fun call(block: suspend () -> Unit) {
        runBlocking { runCatching { block() }.exceptionOrNull() }.also { assertTrue("$it", it is ApiError.Transport) }
    }

    private fun template(request: Request): JsonObject? {
        val path =
            "/" +
                request.url.encodedPathSegments
                    .drop(1)
                    .joinToString("/")
        return paths.entries
            .firstOrNull { (template, _) ->
                Regex(template.replace(Regex("""\{[^}]+}"""), "[^/]+").let { "^$it$" }).matches(path)
            }?.value
            ?.jsonObject
    }

    @Test
    fun everyRouteTheClientCallsIsInTheContract() {
        call { api.getMe() }
        call { api.getAuthConfig() }
        call { api.getHealth() }
        call { api.listAccounts() }
        call { api.getSyncStatus(id) }
        call { api.listFolders(id) }
        call { api.getFolderOrder(id) }
        call { api.listUnifiedFolders() }
        call { api.listIdentities(id) }
        call { api.listMessages(id, folderId = id, isSeen = false, before = id, after = id, around = id, limit = 50) }
        call { api.listUnifiedMessages("Inboxes", isSeen = true, before = id, after = id, around = id, limit = 50) }
        call { api.triggerSync(id) }
        call { api.getMessage(id, loadImages = true) }
        call { api.getThread(id, loadImages = true) }
        call { api.getQuote(id) }
        call { api.getAttachment(id, id) }
        call {
            api.performAction(
                id,
                MessageActionRequest(MessageActionKind.Move, targetFolderId = id, expectedFolderId = id),
            )
        }
        call { api.performBulkAction(id, BulkActionRequest(BulkActionKind.Archive, ids = listOf(id))) }
        call { api.sendSpamFeedback(id, id, isSpam = true) }
        call {
            api.search(
                "a+b",
                id,
                listOf(id, id),
                listOf("subject"),
                id,
                50,
                "2026-01-01T00:00:00Z",
                "2026-02-01T00:00:00Z",
                false,
            )
        }
        call { api.semanticSearch("invoice", id, listOf(id), "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z") }
        call { api.listAlerts(20, unseenOnly = true) }
        call { api.getUnseenCount() }
        call { api.getAlertBadge(id) }
        call { api.dismissAlert(id) }
        call { api.dismissAllAlerts(listOf("mail")) }
        call { api.getVapidPublicKey() }
        call { api.getNativePushConfig() }
        call { api.listPushSubscriptions() }
        call {
            api.createPushSubscription(
                PushSubscriptionCreate("https://push.example.com/x", PushSubscriptionKeys("a", "p"), "phone"),
            )
        }
        call {
            api.updatePushSubscription(
                id,
                PushSubscriptionUpdate(mutedChannels = listOf(PushChannel.Mail), remindersEnabled = false),
            )
        }
        call { api.deletePushSubscription(id) }
        call { api.sendTestPush(id) }
        call { api.createOutbox(OutboxCreateRequest(accountId = id, kind = "send", to = listOf("a@example.com"))) }
        call { api.listOutbox(id, "queued") }
        call { api.listPendingSends(id) }
        call { api.cancelPendingSend(id) }

        assertTrue("the client sent ${sent.size} requests", sent.size >= 30)
        for (request in sent) {
            val label = "${request.method} ${request.url.encodedPath}?${request.url.query}"
            val operations = template(request) ?: throw AssertionError("$label is not a route of the contract")
            val operation =
                operations[request.method.lowercase()]?.jsonObject
                    ?: throw AssertionError("$label: the route has no such method")

            val declared = parameters(operation, "query")
            val sentNames = request.url.queryParameterNames
            assertTrue(
                "$label sends undeclared query parameters ${sentNames - declared.keys}",
                declared.keys.containsAll(sentNames),
            )
            val missing = declared.filterValues { it }.keys - sentNames
            assertTrue("$label omits required query parameters $missing", missing.isEmpty())

            val bodySchema = bodySchemaName(operation)
            val body = request.body
            if (bodySchema != null) {
                assertTrue("$label sends no body", body != null && body.contentLength() != 0L)
                val buffer = okio.Buffer().also { body!!.writeTo(it) }
                val fields = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.keys
                val properties =
                    schemas
                        .getValue(bodySchema)
                        .jsonObject
                        .getValue("properties")
                        .jsonObject.keys
                assertTrue(
                    "$label sends fields the schema $bodySchema does not list: ${fields - properties}",
                    properties.containsAll(fields),
                )
            }
        }
    }

    /** Name to "required" for the parameters of one location. */
    private fun parameters(
        operation: JsonObject,
        location: String,
    ): Map<String, Boolean> =
        (operation["parameters"] as? JsonArray)
            .orEmpty()
            .map { it.jsonObject }
            .filter { it.getValue("in").jsonPrimitive.content == location }
            .associate {
                it
                    .getValue(
                        "name",
                    ).jsonPrimitive.content to (it["required"]?.jsonPrimitive?.content == "true")
            }

    private fun bodySchemaName(operation: JsonObject): String? {
        val schema: JsonElement =
            operation["requestBody"]
                ?.jsonObject
                ?.get(
                    "content",
                )?.jsonObject
                ?.get("application/json")
                ?.jsonObject
                ?.get("schema")
                ?: return null
        return schema.jsonObject["\$ref"]
            ?.jsonPrimitive
            ?.content
            ?.substringAfterLast('/')
    }
}
