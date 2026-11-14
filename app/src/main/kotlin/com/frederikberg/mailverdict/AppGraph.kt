package com.frederikberg.mailverdict

import android.content.Context
import android.os.Build
import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.auth.InMemoryTokenStore
import com.frederikberg.mailverdict.kit.auth.SealedFileTokenStore
import com.frederikberg.mailverdict.kit.cache.FileOfflineCache
import com.frederikberg.mailverdict.kit.fixtures.FixtureInterceptor
import com.frederikberg.mailverdict.kit.fixtures.Fixtures
import com.frederikberg.mailverdict.kit.push.PushController
import com.frederikberg.mailverdict.kit.push.PushRegistrar
import com.frederikberg.mailverdict.kit.push.PushSession
import com.frederikberg.mailverdict.kit.session.ConnectionStores
import com.frederikberg.mailverdict.kit.session.InMemoryServerAddressStore
import com.frederikberg.mailverdict.kit.session.SessionController
import com.frederikberg.mailverdict.kit.session.SessionState
import com.frederikberg.mailverdict.maillist.AndroidMailListPreferences
import com.frederikberg.mailverdict.platform.AlwaysOnline
import com.frederikberg.mailverdict.platform.AndroidConnectivity
import com.frederikberg.mailverdict.platform.AndroidKeystoreKey
import com.frederikberg.mailverdict.platform.AppPreferences
import com.frederikberg.mailverdict.push.DeepLinks
import com.frederikberg.mailverdict.push.NoPushPlatform
import com.frederikberg.mailverdict.push.PushRecordPreferences
import com.frederikberg.mailverdict.push.UnifiedPushPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    /** Actions not yet delivered, one file per server; removed on sign-out. */
    val actionsRoot: File,
    /** Notifications through the phone's UnifiedPush distributor. */
    val push: PushController,
    /** A destination a notification tap asked for, until a screen has shown it. */
    val deepLinks: DeepLinks,
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
            val actionsRoot = File(context.filesDir, "actions")
            val connectionStores =
                ConnectionStores(scope, session.state) {
                    if (!fixtureMode) {
                        FileOfflineCache(mailCacheRoot).clear()
                        actionsRoot.deleteRecursively()
                    }
                }
            val push = createPush(context, fixtureMode, session, scope)
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
                actionsRoot,
                push,
                DeepLinks(),
            )
        }

        private fun createPush(
            context: Context,
            fixtureMode: Boolean,
            session: SessionController,
            scope: CoroutineScope,
        ): PushController {
            val push =
                PushController(
                    registrar = PushRegistrar(PushRecordPreferences(context, fixtureMode)),
                    platform = if (fixtureMode) NoPushPlatform else UnifiedPushPlatform(context),
                    session = {
                        (session.state.value as? SessionState.SignedIn)?.connection?.let {
                            PushSession(
                                it.api,
                                it.serverUrl,
                            )
                        }
                    },
                    deviceLabel = "Android · ${Build.MODEL}",
                )
            if (!fixtureMode) {
                scope.launch {
                    var wasSignedIn = false
                    session.state.collect { state ->
                        when (state) {
                            is SessionState.SignedIn -> {
                                wasSignedIn = true
                                push.refresh()
                            }
                            is SessionState.SignedOut -> {
                                if (wasSignedIn) push.onSignedOut()
                                wasSignedIn = false
                            }
                        }
                    }
                }
            }
            return push
        }
    }
}
