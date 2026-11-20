package com.frederikberg.mailverdict

import android.content.Context
import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.auth.InMemoryTokenStore
import com.frederikberg.mailverdict.kit.auth.OidcTokens
import com.frederikberg.mailverdict.kit.auth.SealedFileTokenStore
import com.frederikberg.mailverdict.kit.cache.FileOfflineCache
import com.frederikberg.mailverdict.kit.fixtures.FixtureInterceptor
import com.frederikberg.mailverdict.kit.fixtures.Fixtures
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.push.NativePushController
import com.frederikberg.mailverdict.kit.push.PushBackend
import com.frederikberg.mailverdict.kit.push.PushBackendChoice
import com.frederikberg.mailverdict.kit.push.PushControl
import com.frederikberg.mailverdict.kit.push.PushController
import com.frederikberg.mailverdict.kit.push.PushRegistrar
import com.frederikberg.mailverdict.kit.push.PushRelayClient
import com.frederikberg.mailverdict.kit.push.PushSession
import com.frederikberg.mailverdict.kit.push.SealedFileNativePushRecordStore
import com.frederikberg.mailverdict.kit.push.withdrawOnAlertChanges
import com.frederikberg.mailverdict.kit.push.withdrawStaleNotifications
import com.frederikberg.mailverdict.kit.session.ConnectionStores
import com.frederikberg.mailverdict.kit.session.InMemoryServerAddressStore
import com.frederikberg.mailverdict.kit.session.SessionController
import com.frederikberg.mailverdict.kit.session.SessionState
import com.frederikberg.mailverdict.maillist.AndroidMailListPreferences
import com.frederikberg.mailverdict.platform.AlwaysOnline
import com.frederikberg.mailverdict.platform.AndroidConnectivity
import com.frederikberg.mailverdict.platform.AndroidKeystoreKey
import com.frederikberg.mailverdict.platform.AppPreferences
import com.frederikberg.mailverdict.platform.DeviceNames
import com.frederikberg.mailverdict.push.DeepLinks
import com.frederikberg.mailverdict.push.FcmAvailability
import com.frederikberg.mailverdict.push.FirebaseFcmPlatform
import com.frederikberg.mailverdict.push.NoPushPlatform
import com.frederikberg.mailverdict.push.PushNotifier
import com.frederikberg.mailverdict.push.PushRecordPreferences
import com.frederikberg.mailverdict.push.UnifiedPushLegacyCleanup
import com.frederikberg.mailverdict.push.UnifiedPushPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicReference

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
    /** Unsent messages kept against the process ending, one directory per server; removed on sign-out. */
    val composeRoot: File,
    /** The canned server of a fixture run, which also records what was sent to it; null otherwise. */
    val fixtures: FixtureInterceptor?,
    /** Whichever way notifications reach this phone, as the screens and the alert list see it. */
    val push: PushControl,
    val pushBackend: PushBackend,
    /** The helper-app path; null when this phone uses Google's push service. */
    val unifiedPush: PushController?,
    /** Google's push service through the relay; null when this phone uses a helper app. */
    val nativePush: NativePushController?,
    /** A destination a notification tap asked for, until a screen has shown it. */
    val deepLinks: DeepLinks,
    private val ownNotifier: PushNotifier,
) {
    /** Withdraws the notifications of alerts the server says were read or dismissed elsewhere. */
    suspend fun readSync(notifier: PushNotifier) {
        val connection = (session.state.value as? SessionState.SignedIn)?.connection ?: return
        try {
            withdrawStaleNotifications(connection.api, notifier)
        } catch (e: ApiError) {
            Timber.w("read-sync lookup failed: %s", e.userMessage)
        }
    }

    /** Coming to the front: mail read elsewhere while the app was away leaves no banner behind. */
    fun readSyncOnForeground() {
        appScope.launch { readSync(ownNotifier) }
    }

    /**
     * While signed in, the stream's alert changes and resyncs withdraw stale banners, so mail read on
     * the web or inside the app takes its notification with it. A phone on the helper-app path gets
     * this too, but only while the app is open: the server withdraws through Google's service alone.
     */
    private fun withdrawWhileSignedIn() {
        appScope.launch {
            session.state.collectLatest { state ->
                val connection = (state as? SessionState.SignedIn)?.connection ?: return@collectLatest
                withdrawOnAlertChanges(
                    connection.liveEventHub.events,
                    onFailure = { Timber.w("read-sync lookup failed: %s", it.userMessage) },
                ) { withdrawStaleNotifications(connection.api, ownNotifier) }
            }
        }
    }

    companion object {
        /** The fixture session's server; nothing listens there, the interceptor answers first. */
        private const val FIXTURE_SERVER = "https://fixture.invalid"

        private val FIXTURE_TOKENS = OidcTokens(accessToken = "fixture", issuer = FIXTURE_SERVER, clientId = "fixture")

        fun create(
            context: Context,
            fixtureMode: Boolean,
        ): AppGraph {
            val fixtures = if (fixtureMode) FixtureInterceptor(Fixtures.routes) else null
            val client =
                OkHttpClient
                    .Builder()
                    .apply { fixtures?.let(::addInterceptor) }
                    .build()
            val preferences = AppPreferences(context, fixtureMode)
            val deviceName = DeviceNames.of(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // The push control needs the session and the session's sign-out needs the push control.
            val pushControl = AtomicReference<PushControl?>(null)
            val deviceCleanup: () -> suspend (ApiClient) -> Unit = {
                pushControl.get()?.ownSubscriptionRemoval() ?: { _ -> }
            }
            val session =
                if (fixtureMode) {
                    SessionController(
                        scope,
                        client,
                        InMemoryTokenStore(AuthMode.Oidc(FIXTURE_TOKENS)),
                        InMemoryServerAddressStore(FIXTURE_SERVER),
                        AlwaysOnline,
                        deviceName,
                        deviceCleanup,
                        liveUpdates = false,
                        revokesAtProvider = false,
                    )
                } else {
                    SessionController(
                        scope,
                        client,
                        SealedFileTokenStore(File(context.filesDir, "auth.bin"), AndroidKeystoreKey::get),
                        preferences,
                        AndroidConnectivity(context),
                        deviceName,
                        deviceCleanup,
                    )
                }
            session.restore()
            val mailCacheRoot = File(context.cacheDir, "mail")
            val actionsRoot = File(context.filesDir, "actions")
            val composeRoot = File(context.filesDir, "compose")
            val connectionStores =
                ConnectionStores(scope, session.state) {
                    if (!fixtureMode) {
                        FileOfflineCache(mailCacheRoot).clear()
                        actionsRoot.deleteRecursively()
                        composeRoot.deleteRecursively()
                    }
                }
            val wiring = createPush(context, fixtureMode, client, session, scope)
            pushControl.set(wiring.control)
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
                composeRoot,
                fixtures,
                wiring.control,
                wiring.backend,
                wiring.unifiedPush,
                wiring.native,
                DeepLinks(),
                PushNotifier(context.applicationContext),
            ).also { if (!fixtureMode) it.withdrawWhileSignedIn() }
        }

        /** The notification path chosen for this start, with the objects of the one that was. */
        private class PushWiring(
            val backend: PushBackend,
            val unifiedPush: PushController?,
            val native: NativePushController?,
        ) {
            val control: PushControl = native ?: unifiedPush!!
        }

        private fun createPush(
            context: Context,
            fixtureMode: Boolean,
            client: OkHttpClient,
            session: SessionController,
            scope: CoroutineScope,
        ): PushWiring {
            val backend =
                if (fixtureMode) {
                    PushBackend.UnifiedPush
                } else {
                    PushBackendChoice.decide(FcmAvailability.configured(context), FcmAvailability.playServices(context))
                }
            val currentSession = {
                (session.state.value as? SessionState.SignedIn)?.connection?.let { PushSession(it.api, it.serverUrl) }
            }
            val label = "Android · ${DeviceNames.of(context)}"
            val helperRecords = PushRegistrar(PushRecordPreferences(context, fixtureMode))
            val wiring =
                when (backend) {
                    PushBackend.Fcm ->
                        PushWiring(
                            backend,
                            null,
                            NativePushController(
                                relay = PushRelayClient(client),
                                fcm = FirebaseFcmPlatform(),
                                records =
                                    SealedFileNativePushRecordStore(
                                        File(context.filesDir, "push-native.bin"),
                                        AndroidKeystoreKey::get,
                                    ),
                                legacy = UnifiedPushLegacyCleanup(context, helperRecords),
                                session = currentSession,
                                deviceLabel = label,
                            ),
                        )
                    PushBackend.UnifiedPush ->
                        PushWiring(
                            backend,
                            PushController(
                                registrar = helperRecords,
                                platform = if (fixtureMode) NoPushPlatform else UnifiedPushPlatform(context),
                                session = currentSession,
                                deviceLabel = label,
                            ),
                            null,
                        )
                }
            if (!fixtureMode) {
                scope.launch {
                    var wasSignedIn = false
                    session.state.collect { state ->
                        when (state) {
                            is SessionState.SignedIn -> {
                                wasSignedIn = true
                                wiring.control.refresh()
                            }
                            is SessionState.SignedOut -> {
                                if (wasSignedIn) wiring.control.onSignedOut()
                                wasSignedIn = false
                            }
                        }
                    }
                }
            }
            return wiring
        }
    }
}
