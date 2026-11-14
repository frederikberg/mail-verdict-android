package com.frederikberg.mailverdict.search

import android.content.Context
import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.kit.search.SearchContext
import com.frederikberg.mailverdict.kit.search.SearchPersistence

/**
 * Remembers how the person likes to search: the mode, the field chips and the time window. The query
 * and the scope belong to one visit and start empty each time.
 */
class SearchPreferences(
    context: Context,
    fixtureMode: Boolean,
) : SearchPersistence {
    private val prefs =
        context.getSharedPreferences(
            if (fixtureMode) "mailverdict_search_fixture" else "mailverdict_search",
            Context.MODE_PRIVATE,
        )

    override fun load(): SearchContext? =
        prefs.getString(CONTEXT, null)?.let {
            runCatching { ContractJson.decodeFromString(SearchContext.serializer(), it) }.getOrNull()
        }

    override fun save(context: SearchContext) {
        val kept = SearchContext(mode = context.mode, fields = context.fields, timeWindow = context.timeWindow)
        prefs.edit().putString(CONTEXT, ContractJson.encodeToString(SearchContext.serializer(), kept)).apply()
    }

    private companion object {
        const val CONTEXT = "context"
    }
}
