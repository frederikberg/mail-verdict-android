package com.frederikberg.mailverdict.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks every model against the vendored snapshot of the server's published API contract
 * (`src/test/resources/api-contract/`, written by `tooling/sync-contract.sh`).
 */
class ContractTest {
    /** Each model beside the schema it mirrors. A model missing here is never checked. */
    private val models: Map<String, KSerializer<*>> =
        mapOf(
            "MeResponse" to MeResponse.serializer(),
            "AuthConfigResponse" to AuthConfigResponse.serializer(),
            "TagResponse" to TagResponse.serializer(),
            "AttachmentSummary" to AttachmentSummary.serializer(),
            "VerdictResponse" to VerdictResponse.serializer(),
            "MessageSummary" to MessageSummary.serializer(),
            "MessageListResponse" to MessageListResponse.serializer(),
            "MessageLocation" to MessageLocation.serializer(),
            "MessageDetail" to MessageDetail.serializer(),
            "ThreadResponse" to ThreadResponse.serializer(),
            "MessageQuoteResponse" to MessageQuoteResponse.serializer(),
            "MessageActionRequest" to MessageActionRequest.serializer(),
            "MessageActionResponse" to MessageActionResponse.serializer(),
            "FeedbackRequest" to FeedbackRequest.serializer(),
            "FeedbackResponse" to FeedbackResponse.serializer(),
            "BulkActionScope" to BulkActionScope.serializer(),
            "BulkActionRequest" to BulkActionRequest.serializer(),
            "BulkActionSource" to BulkActionSource.serializer(),
            "BulkActionResponse" to BulkActionResponse.serializer(),
            "SelectionSnapshotResponse" to SelectionSnapshotResponse.serializer(),
            "SearchResult" to SearchResult.serializer(),
            "SearchResponse" to SearchResponse.serializer(),
            "SemanticSearchHit" to SemanticSearchHit.serializer(),
            "SemanticSearchResponse" to SemanticSearchResponse.serializer(),
            "AccountResponse" to AccountResponse.serializer(),
            "SyncStatusResponse" to SyncStatusResponse.serializer(),
            "FolderResponse" to FolderResponse.serializer(),
            "FolderOrderItem" to FolderOrderItem.serializer(),
            "FolderPrefsUpdate" to FolderPrefsUpdate.serializer(),
            "FolderOrderResponse" to FolderOrderResponse.serializer(),
            "IdentityResponse" to IdentityResponse.serializer(),
            "UnifiedFolderSource" to UnifiedFolderSource.serializer(),
            "UnifiedFolderResponse" to UnifiedFolderResponse.serializer(),
            "UnifiedViewResponse" to UnifiedViewResponse.serializer(),
            "OutboxAttachmentSummary" to OutboxAttachmentSummary.serializer(),
            "OutboxResponse" to OutboxResponse.serializer(),
            "PendingSendAttachmentSummary" to PendingSendAttachmentSummary.serializer(),
            "PendingSendResponse" to PendingSendResponse.serializer(),
            "AlertResponse" to AlertResponse.serializer(),
            "AlertUnseenCountResponse" to AlertUnseenCountResponse.serializer(),
            "AlertBadgeResponse" to AlertBadgeResponse.serializer(),
            "VapidPublicKeyResponse" to VapidPublicKeyResponse.serializer(),
            "PushSubscriptionKeys" to PushSubscriptionKeys.serializer(),
            "PushSubscriptionCreate" to PushSubscriptionCreate.serializer(),
            "PushSubscriptionUpdate" to PushSubscriptionUpdate.serializer(),
            "PushSubscriptionResponse" to PushSubscriptionResponse.serializer(),
            "NativePushConfigResponse" to NativePushConfigResponse.serializer(),
        )

    /** Models the published schema cannot check: no schema of their own, or not a model at all. */
    private val unchecked: Set<String> =
        setOf("HealthResponse", "OutboxCreateRequest", "AddressList", "AddressListSerializer")

    /** Each wire enumeration beside the schema property that lists its values: `Schema.property`. */
    private val enums: Map<String, Set<String>> =
        mapOf(
            "MeResponse.role" to UserRole.known,
            "FolderResponse.kind" to FolderKind.known,
            "MessageActionRequest.action" to MessageActionKind.known,
            "BulkActionRequest.action" to BulkActionKind.known,
            "BulkActionScope.filter" to BulkScopeFilter.known,
            "PushSubscriptionResponse.transport" to PushTransport.known,
            "PushSubscriptionUpdate.muted_channels" to PushChannel.known,
        )

    private val schemas: JsonObject =
        resource("api-contract/openapi.json")
            .jsonObject
            .getValue("components")
            .jsonObject
            .getValue("schemas")
            .jsonObject

    @Test
    fun everyModelFieldExistsInItsSchema() {
        for ((schemaName, serializer) in models) {
            val declared = properties(schemaName)
            val unknown = serializer.descriptor.elementNames() - declared
            assertTrue("$schemaName declares fields the schema does not have: $unknown", unknown.isEmpty())
        }
    }

    @Test
    fun everyRequiredPropertyIsModelled() {
        for ((schemaName, serializer) in models) {
            val missing = required(schemaName) - serializer.descriptor.elementNames()
            assertTrue("$schemaName does not model required properties: $missing", missing.isEmpty())
        }
    }

    /** A server omitting every field its schema calls optional must still decode: the crash direction. */
    @Test
    fun theMinimalInstanceOfEverySchemaDecodes() {
        for ((schemaName, serializer) in models) {
            val minimal = minimalInstance(schemaName)
            try {
                ContractJson.decodeFromJsonElement(serializer, minimal)
            } catch (e: Exception) {
                throw AssertionError("$schemaName failed to decode its own minimal instance $minimal", e)
            }
        }
    }

    @Test
    fun everyEnumKnowsExactlyTheValuesTheSchemaLists() {
        assertTrue("the enum map is empty, so nothing was checked", enums.isNotEmpty())
        for ((path, known) in enums) {
            val (schemaName, property) = path.split(".")
            assertEquals(path, schemaEnum(schemaName, property), known)
        }
    }

    /** Every `@Serializable` type of this module is either checked above or named as unchecked. */
    @Test
    fun noModelIsLeftUnregistered() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("no sources found from ${File(".").absolutePath}", sources.isNotEmpty())
        val declared =
            sources
                .flatMap { file ->
                    Regex(
                        """@Serializable(?:\([^)]*\))?\s+(?:@JvmInline\s+)?(?:data |value |sealed )?(?:class|object|interface)\s+(\w+)""",
                    ).findAll(file.readText())
                        .map { it.groupValues[1] }
                        .toList()
                }.toSet()
        val enumClasses =
            setOf(
                "UserRole",
                "FolderKind",
                "MessageActionKind",
                "BulkActionKind",
                "BulkScopeFilter",
                "PushTransport",
                "PushChannel",
                "SseEventName",
            )
        val unregistered = declared - models.keys - unchecked - enumClasses
        assertTrue("serializable types missing from ContractTest: $unregistered", unregistered.isEmpty())
        assertTrue("the scan found nothing", declared.size > 40)
    }

    @Test
    fun sseEventNamesMatchTheSnapshotBothWays() {
        val published = resource("api-contract/sse-events.json").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(published, SseEventName.known)
        assertEquals(SseEventName.all.size, SseEventName.known.size)
    }

    @Test
    fun aPayloadFromANewerServerStillDecodes() {
        val payload =
            """
            {"id":"1","account_id":"2","folder_id":"3","thread_id":"4","added_by_a_newer_server":true}
            """.trimIndent()

        assertEquals(
            MessageLocation(id = "1", accountId = "2", folderId = "3", threadId = "4"),
            ContractJson.decodeFromString(MessageLocation.serializer(), payload),
        )
    }

    @Test
    fun anEnumValueOutsideTheKnownSetIsKeptNotRejected() {
        val me =
            ContractJson.decodeFromString(
                MeResponse.serializer(),
                """{"user_id":"u","email":null,"display_name":"A","role":"auditor","disabled":false}""",
            )
        assertEquals("auditor", me.role.raw)
        assertFalse(me.role.isKnown)
        assertFalse(me.isAdmin)
    }

    @Test
    fun addressHeadersDecodeFromEveryShapeTheServerStores() {
        fun addresses(json: String) =
            ContractJson
                .decodeFromString(MessageSummary.serializer(), minimalSummary(json))
                .toAddrs
                ?.addresses

        assertEquals(listOf("a@x.org"), addresses(""""to_addrs":"a@x.org""""))
        assertEquals(listOf("a@x.org", "b@x.org"), addresses(""""to_addrs":["a@x.org","b@x.org"]"""))
        assertEquals(null, addresses(""""to_addrs":null"""))
        assertEquals(emptyList<String>(), addresses(""""to_addrs":{"unexpected":1}"""))
    }

    @Test
    fun aNullOrAbsentValueFallsBackToTheFieldDefault() {
        val row =
            ContractJson.decodeFromString(
                MessageSummary.serializer(),
                minimalSummary(""""is_seen":null,"thread_count":null"""),
            )
        assertFalse(row.isSeen)
        assertEquals(null, row.threadCount)
    }

    @Test
    fun aRequestOmitsWhatItDoesNotSet() {
        val body =
            ContractJson.encodeToString(
                MessageActionRequest.serializer(),
                MessageActionRequest(action = MessageActionKind.Archive, idempotencyKey = "k"),
            )
        assertEquals("""{"action":"archive","idempotency_key":"k"}""", body)
    }

    private fun minimalSummary(extra: String) =
        """{"id":"1","account_id":"2","folder_id":"3","thread_id":"4","mirrored_at":"2026-01-01T00:00:00+00:00",""" +
            """"has_attachments":false,$extra}"""

    private fun properties(schemaName: String): Set<String> = schema(schemaName).getValue("properties").jsonObject.keys

    private fun required(schemaName: String): Set<String> =
        (schema(schemaName)["required"] as? JsonArray)
            ?.map { it.jsonPrimitive.content }
            ?.toSet()
            .orEmpty()

    private fun isNullable(property: JsonObject): Boolean =
        (property["anyOf"] as? JsonArray).orEmpty().any {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "null"
        }

    /** The values of an enum property, whether it sits directly on the property, in an array's items or in an anyOf. */
    private fun schemaEnum(
        schemaName: String,
        property: String,
    ): Set<String> {
        fun collect(node: JsonElement): Set<String> {
            if (node !is JsonObject) return emptySet()
            val own = (node["enum"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty().toSet()
            val nested =
                listOfNotNull(node["items"]).flatMap { collect(it) } +
                    (node["anyOf"] as? JsonArray).orEmpty().flatMap { collect(it) }
            return own + nested
        }
        val values = collect(schema(schemaName).getValue("properties").jsonObject.getValue(property))
        assertTrue("$schemaName.$property lists no enum values", values.isNotEmpty())
        return values
    }

    /** Required properties only, nullable ones null, the way a server that sends the bare minimum answers. */
    private fun minimalInstance(schemaName: String): JsonObject {
        val schema = schema(schemaName)
        val props = schema["properties"]?.jsonObject.orEmpty()
        return buildJsonObject {
            for (key in required(schemaName)) {
                val property = props[key]?.jsonObject ?: continue
                put(key, if (isNullable(property)) JsonNull else minimalValue(property))
            }
        }
    }

    private fun minimalValue(property: JsonObject): JsonElement {
        property["\$ref"]?.jsonPrimitive?.content?.let { return minimalInstance(it.substringAfterLast('/')) }
        (property["anyOf"] as? JsonArray)?.let { options ->
            val chosen =
                options.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull != "null" } ?: options.first()
            return minimalValue(chosen.jsonObject)
        }
        (property["enum"] as? JsonArray)?.firstOrNull()?.let { return it }
        return when (property["type"]?.jsonPrimitive?.contentOrNull) {
            "string" ->
                when (property["format"]?.jsonPrimitive?.contentOrNull) {
                    "uuid" -> JsonPrimitive("00000000-0000-0000-0000-000000000000")
                    "date-time" -> JsonPrimitive("2026-01-01T00:00:00+00:00")
                    else -> JsonPrimitive("x")
                }
            "integer", "number" -> JsonPrimitive(0)
            "boolean" -> JsonPrimitive(false)
            "array" -> JsonArray(emptyList())
            "object" -> JsonObject(emptyMap())
            else -> JsonNull
        }
    }

    private fun schema(name: String): JsonObject =
        checkNotNull(schemas[name]) {
            "the snapshot has no schema named $name"
        }.jsonObject

    private fun SerialDescriptor.elementNames(): Set<String> = (0 until elementsCount).map(::getElementName).toSet()

    private fun resource(path: String): JsonElement =
        Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader.getResource(path)) { "missing test resource $path" }.readText(),
        )
}
