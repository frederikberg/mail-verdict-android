package com.frederikberg.mailverdict.kit.rules

import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.contract.PipelineDocumentOut
import com.frederikberg.mailverdict.contract.PipelineHealthEntryOut
import com.frederikberg.mailverdict.contract.PipelineRevisionSummary
import com.frederikberg.mailverdict.contract.PipelineWriteRequest
import com.frederikberg.mailverdict.contract.StageOut
import com.frederikberg.mailverdict.contract.StageReorderRequest
import com.frederikberg.mailverdict.contract.StageUpdateRequest
import com.frederikberg.mailverdict.kit.api.deleteStage
import com.frederikberg.mailverdict.kit.api.getPipeline
import com.frederikberg.mailverdict.kit.api.getPipelineHealth
import com.frederikberg.mailverdict.kit.api.listFolders
import com.frederikberg.mailverdict.kit.api.listPipelineRevisions
import com.frederikberg.mailverdict.kit.api.reorderStages
import com.frederikberg.mailverdict.kit.api.replacePipeline
import com.frederikberg.mailverdict.kit.api.restorePipelineRevision
import com.frederikberg.mailverdict.kit.api.updateStage
import com.frederikberg.mailverdict.kit.format.folderDisplayName
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import com.frederikberg.mailverdict.kit.net.writeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.jsonObject

/** What the rules screen reads from the server, before it is arranged for display (see [RulesView]). */
data class RulesState(
    val document: PipelineDocumentOut? = null,
    /** Unresolved folder references from the health route, which is fresher than the document's own list. */
    val health: List<PipelineHealthEntryOut>? = null,
    /** Folder display names by id, for the sentences. */
    val folderNames: Map<String, String> = emptyMap(),
    /** The order the stages are shown in: the server's, or the one a reorder is being saved with. */
    val order: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
    /** Something that happened to the person's list that they should be told, such as a reload after a clash. */
    val notice: String? = null,
    val revisions: List<PipelineRevisionSummary>? = null,
)

/**
 * The person's rule list. Every change goes to the server first and the screen shows what the server
 * answered with; a reorder is the one exception, shown at once and put back if it fails.
 */
class RulesStore(
    private val api: ApiClient,
) {
    private val _state = MutableStateFlow(RulesState())
    val state: StateFlow<RulesState> = _state.asStateFlow()

    /** The accounts the last read named, so a reload after a clash looks up the same folders. */
    private var accountIds: List<String> = emptyList()

    /** Reads the document, its health and the folder names of [accountIds]. Keeps what is on screen when the read fails, and says so. */
    suspend fun load(accountIds: List<String> = this.accountIds) {
        this.accountIds = accountIds
        _state.update { it.copy(isLoading = !it.hasLoaded, errorMessage = null) }
        try {
            val (document, health, names) =
                coroutineScope {
                    val document = async { api.getPipeline() }
                    val health = async { optional { api.getPipelineHealth() } }
                    val names = async { folderNames(accountIds) }
                    Triple(document.await(), health.await(), names.await())
                }
            _state.update {
                it.copy(
                    document = document,
                    health = health,
                    folderNames = names,
                    order = document.stages.map(StageOut::stageId),
                    isLoading = false,
                    hasLoaded = true,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _state.update { it.copy(isLoading = false, errorMessage = e.userMessage) }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    suspend fun setEnabled(
        stageId: String,
        enabled: Boolean,
    ): WriteOutcome = writeAnd { api.updateStage(stageId, StageUpdateRequest(enabled = enabled)) }

    /** Moves a rule one place up (-1) or down (1) within its group; the other groups keep their places. */
    suspend fun move(
        stageId: String,
        direction: Int,
        members: List<String>,
    ): WriteOutcome {
        val current = _state.value
        val document = current.document ?: return WriteOutcome.Done
        val next = RuleOrder.moveWithinGroup(current.order, members, stageId, direction)
        if (next == current.order) return WriteOutcome.Done
        _state.update { it.copy(order = next) }
        val outcome =
            writeOutcome {
                apply(api.reorderStages(StageReorderRequest(next, document.revision)))
            }
        if (outcome is WriteOutcome.Failed) onRefusedWrite(outcome)
        return outcome
    }

    suspend fun delete(stageId: String): WriteOutcome {
        val revision = _state.value.document?.revision
        val outcome = writeOutcome { apply(api.deleteStage(stageId, revision)) }
        if (outcome is WriteOutcome.Failed && outcome.isConflict) onRefusedWrite(outcome)
        return outcome
    }

    /**
     * Pauses or resumes every rule. The route replaces the whole document and reads an omitted stage
     * list as an empty one, so the stages go back exactly as they were read.
     */
    suspend fun setPaused(paused: Boolean): WriteOutcome {
        val document = _state.value.document ?: return WriteOutcome.Done
        val outcome =
            writeOutcome {
                apply(
                    api.replacePipeline(
                        PipelineWriteRequest(
                            enabled = !paused,
                            stages =
                                document.stages.map {
                                    ContractJson.encodeToJsonElement(StageOut.serializer(), it).jsonObject
                                },
                            baseRevision = document.revision,
                        ),
                    ),
                )
            }
        if (outcome is WriteOutcome.Failed) onRefusedWrite(outcome)
        return outcome
    }

    suspend fun loadRevisions(): WriteOutcome =
        writeOutcome { _state.update { it.copy(revisions = api.listPipelineRevisions()) } }

    suspend fun restore(revision: Int): WriteOutcome {
        val outcome = writeOutcome { apply(api.restorePipelineRevision(revision)) }
        if (outcome.isDone) loadRevisions()
        return outcome
    }

    private suspend fun writeAnd(call: suspend () -> PipelineDocumentOut): WriteOutcome = writeOutcome { apply(call()) }

    /** The server's answer to a write is the list as it now is, warnings included; the older health read is dropped. */
    private fun apply(document: PipelineDocumentOut) {
        _state.update {
            it.copy(document = document, health = null, order = document.stages.map(StageOut::stageId))
        }
    }

    /** A refused reorder shows the server's order again; a clash says the list changed and reads it again. */
    private suspend fun onRefusedWrite(outcome: WriteOutcome.Failed) {
        val ids =
            _state.value.document
                ?.stages
                ?.map(StageOut::stageId)
                .orEmpty()
        _state.update { it.copy(order = ids) }
        if (outcome.isConflict) {
            _state.update { it.copy(notice = "Your rules changed meanwhile — reloaded.") }
            load()
        }
    }

    private suspend fun folderNames(accountIds: List<String>): Map<String, String> =
        coroutineScope {
            accountIds
                .map { id -> async { optional { api.listFolders(id) }.orEmpty() } }
                .awaitAll()
                .flatten()
                .associate { it.id to folderDisplayName(it.imapName, it.displayName, it.specialUse) }
        }

    private suspend fun <T> optional(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            null
        }
}
