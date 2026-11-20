package com.frederikberg.mailverdict.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `PUT /settings/{category}`: the keys named are merged into the caller's values. A category's
 * answer is an untyped object, so it has no model beyond [JsonObject].
 */
@Serializable
data class SettingsUpdateRequest(
    val data: JsonObject,
)

/** Who owns a setting's value, and what kind of value it is; both are the server's own words. */
@Serializable
data class SettingSchemaEntry(
    /** `user` when every person has their own value, `admin` when the instance has one. */
    val scope: String,
    /** `boolean`, `number`, `string`, `array`, `object`, or `any` for a nullable key. */
    val type: String,
)
