package com.frederikberg.mailverdict.maillist

import android.content.Context
import com.frederikberg.mailverdict.kit.actions.RecentMoveTargets
import com.frederikberg.mailverdict.kit.maillist.MailListPreferences
import com.frederikberg.mailverdict.kit.reader.InMemoryRecentViewRecord
import com.frederikberg.mailverdict.kit.reader.RecentViewRecord
import com.frederikberg.mailverdict.kit.reader.UnifiedViewRef
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the mail list remembers across launches: whether rows are grouped by conversation, and which
 * kind of list the person looked at last, so a message opened from a notification lands in the same
 * kind of list. A fixture run gets its own file so canned data never leaks into the real settings.
 */
class AndroidMailListPreferences(
    context: Context,
    fixtureMode: Boolean,
) : MailListPreferences,
    RecentViewRecord,
    RecentMoveTargets {
    private val prefs = context.getSharedPreferences(if (fixtureMode) FIXTURE_FILE else FILE, Context.MODE_PRIVATE)

    override fun threaded(): Boolean = prefs.getBoolean(THREADED, true)

    override fun setThreaded(threaded: Boolean) {
        prefs.edit().putBoolean(THREADED, threaded).apply()
    }

    override fun lastViewWasUnified(): Boolean = prefs.getBoolean(LAST_UNIFIED, false)

    override fun recordFolderView() {
        prefs.edit().putBoolean(LAST_UNIFIED, false).apply()
    }

    override fun recentUnifiedViews(): List<UnifiedViewRef> =
        runCatching {
            val array = JSONArray(prefs.getString(RECENT, "[]"))
            (0 until array.length()).map {
                array.getJSONObject(it).let { o ->
                    UnifiedViewRef(o.getString("id"), o.getString("name"))
                }
            }
        }.getOrDefault(emptyList())

    @Synchronized
    override fun recordUnifiedView(view: UnifiedViewRef) {
        val recent =
            (
                listOf(
                    view,
                ) + recentUnifiedViews().filter { it.id != view.id }
            ).take(InMemoryRecentViewRecord.MAX_RECENT)
        val array = JSONArray()
        recent.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs
            .edit()
            .putBoolean(LAST_UNIFIED, true)
            .putString(RECENT, array.toString())
            .apply()
    }

    override fun recentIds(accountId: String): List<String> =
        runCatching {
            val array = JSONObject(prefs.getString(MOVES, "{}")).optJSONArray(accountId) ?: return emptyList()
            (0 until array.length()).map(array::getString)
        }.getOrDefault(emptyList())

    @Synchronized
    override fun record(
        accountId: String,
        folderId: String,
    ) {
        val all = runCatching { JSONObject(prefs.getString(MOVES, "{}")) }.getOrDefault(JSONObject())
        val recent = (listOf(folderId) + recentIds(accountId).filter { it != folderId }).take(RecentMoveTargets.MAX)
        all.put(accountId, JSONArray(recent))
        prefs.edit().putString(MOVES, all.toString()).apply()
    }

    private companion object {
        const val MOVES = "recent_move_targets"

        const val FILE = "mailverdict_maillist"
        const val FIXTURE_FILE = "mailverdict_maillist_fixture"
        const val THREADED = "threaded"
        const val LAST_UNIFIED = "last_view_unified"
        const val RECENT = "recent_unified_views"
    }
}
