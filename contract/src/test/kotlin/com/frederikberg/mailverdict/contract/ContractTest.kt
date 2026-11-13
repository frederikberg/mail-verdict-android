package com.frederikberg.mailverdict.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks every model against the vendored snapshot of the server's published API contract
 * (`src/test/resources/api-contract/`, written by `tooling/sync-contract.sh`).
 */
class ContractTest {
    /** Each model beside the schema it mirrors. A model missing here is never checked. */
    private val models: Map<String, KSerializer<*>> =
        mapOf(
            "MessageLocation" to MessageLocation.serializer(),
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

    @Test
    fun aSnapshotPayloadDecodes() {
        val payload =
            """
            {"id":"1","account_id":"2","folder_id":"3","thread_id":"4","added_by_a_newer_server":true}
            """.trimIndent()

        assertEquals(
            MessageLocation(id = "1", accountId = "2", folderId = "3", threadId = "4"),
            ContractJson.decodeFromString(MessageLocation.serializer(), payload),
        )
    }

    private fun properties(schemaName: String): Set<String> =
        schema(schemaName).getValue("properties").jsonObject.keys

    private fun required(schemaName: String): Set<String> =
        (schema(schemaName)["required"] as? JsonArray)
            ?.map { it.jsonPrimitive.content }
            ?.toSet()
            .orEmpty()

    private fun schema(name: String): JsonObject =
        checkNotNull(schemas[name]) { "the snapshot has no schema named $name" }.jsonObject

    private fun SerialDescriptor.elementNames(): Set<String> = (0 until elementsCount).map(::getElementName).toSet()

    private fun resource(path: String) =
        Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader.getResource(path)) { "missing test resource $path" }.readText(),
        )
}
