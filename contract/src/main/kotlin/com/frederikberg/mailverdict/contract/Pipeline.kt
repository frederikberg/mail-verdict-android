@file:OptIn(ExperimentalSerializationApi::class)

package com.frederikberg.mailverdict.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One step of the caller's rule list as stored in a pipeline revision. */
@Serializable
data class StageOut(
    @SerialName("stage_id") val stageId: String,
    /** `match` for a rule, `classify` for spam detection, `orders` for orders and tickets; anything else is a step this app does not know. */
    val type: String,
    val name: String,
    val config: JsonObject,
    val enabled: Boolean,
    /** A mail this stage matches goes through no stage after it. */
    val halt: Boolean,
    /** The accounts the stage applies to; null is every account, an empty list none. */
    val accounts: List<String>? = null,
)

/** One folder reference of one stage, resolved against one account. */
@Serializable
data class PipelineHealthEntryOut(
    @SerialName("stage_id") val stageId: String,
    @SerialName("account_id") val accountId: String,
    val reference: String,
    val ok: Boolean,
    val detail: String? = null,
)

/** The caller's rule list with its revision; [enabled] false pauses every rule. */
@Serializable
data class PipelineDocumentOut(
    val revision: Int,
    val enabled: Boolean,
    val stages: List<StageOut>,
    val warnings: List<PipelineHealthEntryOut>,
)

/**
 * `PUT /pipeline`: replaces the whole document, so [stages] is always sent in full: the server reads an
 * omitted list as an empty one and would drop every rule. [baseRevision] makes the write optimistic: a
 * document changed meanwhile answers 409 instead of being overwritten.
 */
@Serializable
data class PipelineWriteRequest(
    @EncodeDefault val enabled: Boolean = true,
    @EncodeDefault val stages: List<JsonObject> = emptyList(),
    @SerialName("base_revision") val baseRevision: Int? = null,
)

/** `POST /pipeline/stages/reorder`: every stage id, in the new order. */
@Serializable
data class StageReorderRequest(
    val order: List<String>,
    @SerialName("base_revision") val baseRevision: Int? = null,
)

/** `PATCH /pipeline/stages/{id}`; a field left out stays as it is. */
@Serializable
data class StageUpdateRequest(
    val name: String? = null,
    val enabled: Boolean? = null,
    val halt: Boolean? = null,
    val accounts: List<String>? = null,
    val config: JsonObject? = null,
    @SerialName("base_revision") val baseRevision: Int? = null,
)

/** One entry of the revision history. */
@Serializable
data class PipelineRevisionSummary(
    val revision: Int,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
)
