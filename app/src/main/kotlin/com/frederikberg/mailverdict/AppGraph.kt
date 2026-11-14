package com.frederikberg.mailverdict

import android.content.Context
import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.auth.InMemoryTokenStore
import com.frederikberg.mailverdict.kit.auth.SealedFileTokenStore
import com.frederikberg.mailverdict.kit.cache.FileOfflineCache
import com.frederikberg.mailverdict.kit.fixtures.FixtureInterceptor
import com.frederikberg.mailverdict.kit.fixtures.Fixtures
import com.frederikberg.mailverdict.kit.session.ConnectionStores
import com.frederikberg.mailverdict.kit.session.InMemoryServerAddressStore
import com.frederikberg.mailverdict.kit.session.SessionController
import com.frederikberg.mailverdict.maillist.AndroidMailListPreferences
import com.frederikberg.mailverdict.platform.AlwaysOnline
import com.frederikberg.mailverdict.platform.AndroidConnectivity
import com.frederikberg.mailverdict.platform.AndroidKeystoreKey
import com.frederikberg.mailverdict.platform.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File

/**
 * Constructor wiring for everything the screens share. Nothing is injected by a framework: a
 * dependency is a property here, built in [create], and passed down by hand.
 */
class AppGraph private constructor(
    /** True when requests are answered from canned data instead of a server. */
    val fixtureMode: Boolean,
    val json: Json,
    val httpClient: OkHttpClient,
    /** Outlives every screen: the live stream, token renewal and the browser leg of sign-in run here. */
    val appScope: CoroutineScope,
    val preferences: AppPreferences,
    val session: SessionController,
    /** Stores kept for as long as one sign-in lasts. */
    val connectionStores: ConnectionStores,
    val mailPreferences: AndroidMailListPreferences,
    /** Everything mail kept on the device lives under here and is removed on sign-out. */
    val mailCacheRoot: File,
) {
    companion object {
        /** The fixture session's server; nothing listens there, the interceptor answers first. */
        private const val FIXTURE_SERVER = "https://fixture.invalid"

        fun create(
            context: Context,
            fixtureMode: Boolean,
        ): AppGraph {
            val client =
                OkHttpClient
                    .Builder()
                    .apply { if (fixtureMode) addInterceptor(FixtureInterceptor(Fixtures.routes)) }
                    .build()
            val preferences = AppPreferences(context, fixtureMode)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session =
                if (fixtureMode) {
                    SessionController(
                        scope,
                        client,
                        InMemoryTokenStore(AuthMode.Bearer("fixture")),
                        InMemoryServerAddressStore(FIXTURE_SERVER),
                        AlwaysOnline,
                        liveUpdates = false,
                    )
                } else {
                    SessionController(
                        scope,
                        client,
                        SealedFileTokenStore(File(context.filesDir, "auth.bin"), AndroidKeystoreKey::get),
                        preferences,
                        AndroidConnectivity(context),
                    )
                }
            session.restore()
            val mailCacheRoot = File(context.cacheDir, "mail")
            val connectionStores =
                ConnectionStores(scope, session.state) { if (!fixtureMode) FileOfflineCache(mailCacheRoot).clear() }
            Timber.i("app graph created, fixtureMode=%s", fixtureMode)
            return AppGraph(
                fixtureMode,
                ContractJson,
                client,
                scope,
                preferences,
                session,
                connectionStores,
                AndroidMailListPreferences(context, fixtureMode),
                mailCacheRoot,
            )
        }
    }
}
