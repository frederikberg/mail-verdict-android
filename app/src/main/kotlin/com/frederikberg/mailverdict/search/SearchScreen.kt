package com.frederikberg.mailverdict.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.kit.search.SearchContext
import com.frederikberg.mailverdict.kit.search.SearchField
import com.frederikberg.mailverdict.kit.search.SearchHit
import com.frederikberg.mailverdict.kit.search.SearchMode
import com.frederikberg.mailverdict.kit.search.SearchResultsState
import com.frederikberg.mailverdict.kit.search.SearchRow
import com.frederikberg.mailverdict.kit.search.SearchRowData
import com.frederikberg.mailverdict.kit.search.SearchRowFormat
import com.frederikberg.mailverdict.kit.search.SearchState
import com.frederikberg.mailverdict.kit.search.SemanticState
import com.frederikberg.mailverdict.kit.search.TimeWindow
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

object SearchTags {
    const val FIELD = "search-field"
    const val LIST = "search-list"
    const val MODE_TEXT = "search-mode-text"
    const val MODE_SEMANTIC = "search-mode-semantic"
    const val WINDOW = "search-window"
    const val SCOPE = "search-scope"
    const val STATE = "search-state"
    const val DIVIDER = "search-divider"
    const val SEMANTIC_NOTE = "search-semantic-note"

    fun field(field: SearchField) = "search-chip:${field.wire}"

    fun row(id: String) = "search-row:$id"
}

class SearchCallbacks(
    val onBack: () -> Unit,
    val onQuery: (String) -> Unit,
    val onContext: (SearchContext) -> Unit,
    val onOpen: (SearchHit) -> Unit,
    val onLoadMore: () -> Unit,
)

/**
 * Search: the query field, the chips that narrow it, and the results. The results list is keyed by
 * message id, and its scroll state is keyed by everything that defines which list it is, so a new
 * query or chip starts at the top instead of keeping an offset into a list nobody scrolled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchState,
    initialQuery: String,
    scopeLabel: String?,
    callbacks: SearchCallbacks,
    focusOnOpen: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    // The field is authoritative while typing: it writes out on every change and never reads back.
    var field by remember { mutableStateOf(TextFieldValue(initialQuery)) }
    LaunchedEffect(Unit) { if (focusOnOpen) focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = field,
                        onValueChange = {
                            field = it
                            callbacks.onQuery(it.text)
                        },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        trailingIcon = {
                            if (field.text.isNotEmpty()) {
                                IconButton(onClick = {
                                    field = TextFieldValue("")
                                    callbacks.onQuery("")
                                }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.search_clear),
                                    )
                                }
                            }
                        },
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(SearchTags.FIELD),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Chips(state.context, scopeLabel, callbacks.onContext)
            HorizontalDivider()
            Box(Modifier.fillMaxSize()) { Results(state, callbacks) }
        }
    }
}

@Composable
private fun Chips(
    context: SearchContext,
    scopeLabel: String?,
    onContext: (SearchContext) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (scopeLabel != null) {
            FilterChip(
                selected = true,
                onClick = { onContext(context.copy(accountId = null, folderIds = null)) },
                label = { Text(stringResource(R.string.search_scope, scopeLabel)) },
                trailingIcon = {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.search_scope_clear),
                        modifier = Modifier.size(16.dp),
                    )
                },
                modifier = Modifier.testTag(SearchTags.SCOPE),
            )
        }
        FilterChip(
            selected = context.mode == SearchMode.Text,
            onClick = { onContext(context.copy(mode = SearchMode.Text)) },
            label = { Text(stringResource(R.string.search_mode_text)) },
            modifier = Modifier.testTag(SearchTags.MODE_TEXT),
        )
        FilterChip(
            selected = context.mode == SearchMode.Semantic,
            onClick = { onContext(context.copy(mode = SearchMode.Semantic)) },
            label = { Text(stringResource(R.string.search_mode_semantic)) },
            modifier = Modifier.testTag(SearchTags.MODE_SEMANTIC),
        )
        WindowChip(context.timeWindow) { onContext(context.copy(timeWindow = it)) }
        if (context.mode == SearchMode.Text) {
            SearchField.entries.forEach { field ->
                val selected = context.fields?.contains(field) == true
                FilterChip(
                    selected = selected,
                    onClick = { onContext(context.copy(fields = toggled(context.fields, field))) },
                    label = { Text(fieldLabel(field)) },
                    modifier = Modifier.testTag(SearchTags.field(field)),
                )
            }
        }
    }
}

/** No chip selected means every field; selecting the last one off returns to that. */
private fun toggled(
    current: List<SearchField>?,
    field: SearchField,
): List<SearchField>? {
    val next = if (current?.contains(field) == true) current - field else current.orEmpty() + field
    return next.ifEmpty { null }
}

@Composable
private fun fieldLabel(field: SearchField) =
    stringResource(
        when (field) {
            SearchField.Subject -> R.string.search_field_subject
            SearchField.From -> R.string.search_field_from
            SearchField.To -> R.string.search_field_to
            SearchField.Body -> R.string.search_field_body
        },
    )

@Composable
private fun windowLabel(window: TimeWindow?) =
    stringResource(
        when (window) {
            null -> R.string.search_window_any
            TimeWindow.Today -> R.string.search_window_today
            TimeWindow.SinceYesterday -> R.string.search_window_since_yesterday
            TimeWindow.Last7Days -> R.string.search_window_7_days
            TimeWindow.Last30Days -> R.string.search_window_30_days
        },
    )

@Composable
private fun WindowChip(
    window: TimeWindow?,
    onChange: (TimeWindow?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = window != null,
            onClick = { open = true },
            label = { Text(windowLabel(window)) },
            modifier = Modifier.testTag(SearchTags.WINDOW),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf<TimeWindow?>(null) + TimeWindow.entries).forEach { choice ->
                DropdownMenuItem(
                    text = { Text(windowLabel(choice)) },
                    onClick = {
                        open = false
                        onChange(choice)
                    },
                )
            }
        }
    }
}

@Composable
private fun Results(
    state: SearchState,
    callbacks: SearchCallbacks,
) {
    when (val results = state.resultsState) {
        SearchResultsState.EnterQuery -> Message(stringResource(R.string.search_enter_query))
        SearchResultsState.SelectAFolder -> Message(stringResource(R.string.search_select_folder))
        SearchResultsState.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        SearchResultsState.SearchingByMeaning -> Message(stringResource(R.string.search_by_meaning))
        is SearchResultsState.Error -> Message(stringResource(R.string.search_error, results.message))
        SearchResultsState.NoResults -> Message(stringResource(R.string.search_no_results))
        SearchResultsState.Results -> ResultList(state, callbacks)
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(SearchTags.STATE))
    }
}

@Composable
private fun ResultList(
    state: SearchState,
    callbacks: SearchCallbacks,
) {
    // Which list this is: any change to it is a new list, and a new list starts at the top.
    key(state.context) {
        val listState = rememberLazyListState()
        val rows = state.rows
        LaunchedEffect(listState, state.hasMore) {
            // Ask for the next page two screens before the end, never at the edge.
            snapshotFlow {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                // Nothing is laid out yet on the first pass, which is not "near the end".
                info.visibleItemsInfo.isNotEmpty() &&
                    last >= info.totalItemsCount - 2 * info.visibleItemsInfo.size
            }.collect { nearEnd -> if (nearEnd && state.hasMore) callbacks.onLoadMore() }
        }
        Column(Modifier.fillMaxSize()) {
            if (state.semanticState == SemanticState.Pending) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag(SearchTags.SEMANTIC_NOTE))
            }
            if (state.semanticState == SemanticState.Unavailable) {
                Text(
                    stringResource(R.string.search_semantic_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp, 8.dp).testTag(SearchTags.SEMANTIC_NOTE),
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(SearchTags.LIST)) {
                items(rows, key = { it.key }, contentType = { it::class }) { row ->
                    when (row) {
                        is SearchRow.Hit -> HitRow(row.hit) { callbacks.onOpen(row.hit) }
                        SearchRow.LessLikelyDivider ->
                            Text(
                                stringResource(R.string.search_less_likely),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp).testTag(SearchTags.DIVIDER),
                            )
                    }
                }
                if (state.isLoadingMore) {
                    item(key = "loading-more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun snippetText(data: SearchRowData) =
    buildAnnotatedString {
        data.snippet.forEach { run ->
            if (run.matched) {
                withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                ) { append(run.text) }
            } else {
                append(run.text)
            }
        }
    }

/** One fixed-shape row: a sender and date line, a subject line, a two-line snippet. */
@Composable
private fun HitRow(
    hit: SearchHit,
    onClick: () -> Unit,
) {
    val data: SearchRowData =
        remember(hit) { SearchRowFormat.row(hit, Instant.now(), ZoneId.systemDefault(), Locale.getDefault()) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
            ).padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(SearchTags.row(hit.id)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.padding(top = 6.dp).size(10.dp).background(
                if (data.isUnread) MaterialTheme.colorScheme.primary else Color.Transparent,
                CircleShape,
            ),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    data.sender,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (data.isUnread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (data.isFlagged) Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(
                    data.date,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                data.subject,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (data.isUnread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                snippetText(data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
