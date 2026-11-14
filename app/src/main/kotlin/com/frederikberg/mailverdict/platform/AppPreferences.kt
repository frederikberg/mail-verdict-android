package com.frederikberg.mailverdict.platform

import android.content.Context
import android.content.SharedPreferences
import com.frederikberg.mailverdict.kit.session.ServerAddressStore
import com.frederikberg.mailverdict.kit.stores.mailboxes.CollapseStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which colour scheme the app uses; [System] follows the device. */
enum class ThemeChoice { System, Light, Dark }

/**
 * Everything the app remembers that is not a credential: the server address, which overview sections
 * are collapsed, the theme. A fixture run gets its own file so canned data never leaks into the real
 * settings.
 */
class AppPreferences(
    context: Context,
    fixtureMode: Boolean,
) : ServerAddressStore,
    CollapseStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(if (fixtureMode) FIXTURE_FILE else FILE, Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(readTheme())
    val theme: StateFlow<ThemeChoice> = _theme.asStateFlow()

    fun setTheme(choice: ThemeChoice) {
        prefs.edit().putString(THEME, choice.name).apply()
        _theme.value = choice
    }

    private fun readTheme(): ThemeChoice =
        ThemeChoice.entries.firstOrNull { it.name == prefs.getString(THEME, null) } ?: ThemeChoice.System

    override fun read(): String? = prefs.getString(SERVER, null)

    override fun write(address: String) {
        prefs.edit().putString(SERVER, address).apply()
    }

    override fun collapsedKeys(): Set<String> = prefs.getStringSet(COLLAPSED, emptySet()).orEmpty().toSet()

    @Synchronized
    override fun setCollapsed(
        key: String,
        collapsed: Boolean,
    ) {
        val keys = collapsedKeys().toMutableSet()
        if (collapsed) keys += key else keys -= key
        prefs.edit().putStringSet(COLLAPSED, keys).apply()
    }

    private companion object {
        const val FILE = "mailverdict"
        const val FIXTURE_FILE = "mailverdict_fixture"
        const val SERVER = "server_url"
        const val COLLAPSED = "mailboxes_collapsed"
        const val THEME = "theme"
    }
}
