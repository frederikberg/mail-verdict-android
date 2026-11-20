package com.frederikberg.mailverdict.kit.settings

import com.frederikberg.mailverdict.contract.SettingSchemaEntry
import com.frederikberg.mailverdict.kit.api.getSettings
import com.frederikberg.mailverdict.kit.api.getSettingsSchema
import com.frederikberg.mailverdict.kit.api.updateSettings
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import com.frederikberg.mailverdict.kit.net.writeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** What the server holds for this person, per category, as last read. */
data class UserSettingsState(
    val values: Map<String, JsonObject> = emptyMap(),
    val schema: Map<String, Map<String, SettingSchemaEntry>> = emptyMap(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
)

/** One setting with its current value. */
data class SettingField(
    val spec: SettingSpec,
    val value: JsonElement,
) {
    val asBoolean: Boolean get() = (value as? JsonPrimitive)?.booleanOrNull == true
    val asInt: Int? get() = (value as? JsonPrimitive)?.doubleOrNull?.toInt()
    val asText: String get() = (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}

data class SettingsSection(
    val title: String,
    val description: String?,
    val fields: List<SettingField>,
)

/**
 * The person's own settings (reading and filing, notifications, sending, orders). Each change is
 * written the moment it is made, shown at once and put back if the server refuses it; there is no
 * Save step. The answer to a write replaces the category, so what shows is what the server holds.
 */
class UserSettingsStore(
    private val api: ApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(UserSettingsState())
    val state: StateFlow<UserSettingsState> = _state.asStateFlow()

    /** Writes go out one at a time, in the order they were made. */
    private val writes = Mutex()

    /** Reads every category. A category the server refuses is simply absent; the read fails only when none came. */
    suspend fun load() {
        _state.update { it.copy(isLoading = !it.hasLoaded, errorMessage = null) }
        try {
            val (read, schema) =
                coroutineScope {
                    val categories =
                        UserSettingsCatalog.categories.map { category ->
                            async { category to attempt { api.getSettings(category) } }
                        }
                    val schema = async { attempt { api.getSettingsSchema() }.getOrNull() }
                    categories.map { it.await() } to schema.await()
                }
            val values = read.mapNotNull { (category, result) -> result.getOrNull()?.let { category to it } }.toMap()
            val failure = read.firstNotNullOfOrNull { it.second.exceptionOrNull() }
            _state.update {
                it.copy(
                    values = values,
                    schema = schema ?: it.schema,
                    isLoading = false,
                    hasLoaded = values.isNotEmpty() || it.hasLoaded,
                    errorMessage = if (values.isEmpty()) (failure as? ApiError)?.userMessage else null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    /** Writes one setting. [value] shows at once; a refusal puts the old value back. */
    suspend fun set(
        category: String,
        key: String,
        value: JsonElement,
    ): WriteOutcome =
        writes.withLock {
            val before = _state.value.values[category]
            val shown = JsonObject((before ?: JsonObject(emptyMap())) + (key to value))
            _state.update { it.copy(values = it.values + (category to shown)) }
            val outcome =
                writeOutcome {
                    val answer = api.updateSettings(category, buildJsonObject { put(key, value) })
                    _state.update { it.copy(values = it.values + (category to answer)) }
                }
            if (outcome is WriteOutcome.Failed && before != null) {
                _state.update { it.copy(values = it.values + (category to before)) }
            }
            outcome
        }

    /** `settings.changed` for one of these categories, or a resync, reads them again. */
    fun follow(events: Flow<List<LiveEvent>>) {
        scope.launch {
            events.collect { batch ->
                val stale =
                    batch.any {
                        val invalidation = it.invalidation
                        invalidation is LiveInvalidation.Resync ||
                            (
                                invalidation is LiveInvalidation.SettingsChanged &&
                                    (
                                        invalidation.category == null ||
                                            invalidation.category in UserSettingsCatalog.categories
                                    )
                            )
                    }
                if (stale && _state.value.hasLoaded && !writes.isLocked) load()
            }
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            Result.failure(e)
        }

    companion object {
        /**
         * The screen's sections from what was read: each named field the server returned for this
         * person, then the keys the server tags as the person's own that no field names. A section with
         * nothing to show is left out.
         */
        fun sections(state: UserSettingsState): List<SettingsSection> {
            val declared =
                UserSettingsCatalog.sections
                    .flatMap { it.fields }
                    .map { it.id }
                    .toSet()
            return UserSettingsCatalog.sections.mapNotNull { section ->
                val named =
                    section.fields.mapNotNull { spec ->
                        state.values[spec.category]?.get(spec.key)?.let { SettingField(spec, it) }
                    }
                val plain =
                    section.undeclaredFrom.flatMap { category ->
                        val values = state.values[category] ?: return@flatMap emptyList()
                        values.keys
                            .filter { key ->
                                "$category.$key" !in declared &&
                                    "$category.$key" !in UserSettingsCatalog.skipped &&
                                    state.schema[category]?.get(key)?.scope == "user"
                            }.mapNotNull { key -> plainField(category, key, values.getValue(key)) }
                    }
                (named + plain).takeIf { it.isNotEmpty() }?.let {
                    SettingsSection(
                        section.title,
                        section.description,
                        it,
                    )
                }
            }
        }

        /** A control sniffed from a value's type; null for a block of structure, which only the web edits. */
        private fun plainField(
            category: String,
            key: String,
            value: JsonElement,
        ): SettingField? {
            val primitive = value as? JsonPrimitive ?: return null
            val control =
                when {
                    primitive.booleanOrNull != null && !primitive.isString -> SettingControl.Switch
                    !primitive.isString && primitive.doubleOrNull != null -> SettingControl.Number()
                    else -> SettingControl.Text()
                }
            return SettingField(SettingSpec(category, key, UserSettingsCatalog.humanize(key), null, control), value)
        }
    }
}
