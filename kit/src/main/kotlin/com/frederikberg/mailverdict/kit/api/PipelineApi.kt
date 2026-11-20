package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.PipelineDocumentOut
import com.frederikberg.mailverdict.contract.PipelineHealthEntryOut
import com.frederikberg.mailverdict.contract.PipelineRevisionSummary
import com.frederikberg.mailverdict.contract.PipelineWriteRequest
import com.frederikberg.mailverdict.contract.StageReorderRequest
import com.frederikberg.mailverdict.contract.StageUpdateRequest
import com.frederikberg.mailverdict.kit.net.ApiClient
import kotlinx.serialization.builtins.ListSerializer

/** The caller's rule list, with the folder-resolution warnings folded in. */
suspend fun ApiClient.getPipeline(): PipelineDocumentOut = send(PipelineDocumentOut.serializer(), "/pipeline")

/** Replaces the whole document; a stale [PipelineWriteRequest.baseRevision] answers 409. */
suspend fun ApiClient.replacePipeline(request: PipelineWriteRequest): PipelineDocumentOut =
    send(
        PipelineDocumentOut.serializer(),
        "/pipeline",
        method = "PUT",
        body = encode(PipelineWriteRequest.serializer(), request),
    )

/** Folder references that do not resolve, per stage and account. */
suspend fun ApiClient.getPipelineHealth(): List<PipelineHealthEntryOut> =
    send(ListSerializer(PipelineHealthEntryOut.serializer()), "/pipeline/health")

suspend fun ApiClient.updateStage(
    stageId: String,
    request: StageUpdateRequest,
): PipelineDocumentOut =
    send(
        PipelineDocumentOut.serializer(),
        "/pipeline/stages/$stageId",
        method = "PATCH",
        body = encode(StageUpdateRequest.serializer(), request),
    )

suspend fun ApiClient.deleteStage(
    stageId: String,
    baseRevision: Int? = null,
): PipelineDocumentOut =
    send(
        PipelineDocumentOut.serializer(),
        "/pipeline/stages/$stageId",
        method = "DELETE",
        query = query("base_revision" to baseRevision),
    )

suspend fun ApiClient.reorderStages(request: StageReorderRequest): PipelineDocumentOut =
    send(
        PipelineDocumentOut.serializer(),
        "/pipeline/stages/reorder",
        method = "POST",
        body = encode(StageReorderRequest.serializer(), request),
    )

/** The revision history, newest first. */
suspend fun ApiClient.listPipelineRevisions(): List<PipelineRevisionSummary> =
    send(ListSerializer(PipelineRevisionSummary.serializer()), "/pipeline/revisions")

suspend fun ApiClient.restorePipelineRevision(revision: Int): PipelineDocumentOut =
    send(PipelineDocumentOut.serializer(), "/pipeline/revisions/$revision/restore", method = "POST")
