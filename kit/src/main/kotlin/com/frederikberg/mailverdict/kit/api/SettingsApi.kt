package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.SettingSchemaEntry
import com.frederikberg.mailverdict.contract.SettingsUpdateRequest
import com.frederikberg.mailverdict.kit.net.ApiClient
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

/**
 * One settings category as the caller sees it: the caller's own values of the keys that are theirs,
 * plus every instance-wide key when the caller is an administrator. Untyped, so a key the app has
 * never heard of still arrives.
 */
suspend fun ApiClient.getSettings(category: String): JsonObject = send(JsonObject.serializer(), "/settings/$category")

/** Merges [values] into the caller's settings of [category] and answers the category as it reads afterwards. */
suspend fun ApiClient.updateSettings(
    category: String,
    values: JsonObject,
): JsonObject =
    send(
        JsonObject.serializer(),
        "/settings/$category",
        method = "PUT",
        body = encode(SettingsUpdateRequest.serializer(), SettingsUpdateRequest(values)),
    )

/** Every category's keys with their scope and value type. */
suspend fun ApiClient.getSettingsSchema(): Map<String, Map<String, SettingSchemaEntry>> =
    send(
        MapSerializer(String.serializer(), MapSerializer(String.serializer(), SettingSchemaEntry.serializer())),
        "/settings/schema",
    )
