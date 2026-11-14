package com.frederikberg.mailverdict.kit.stores

import com.frederikberg.mailverdict.kit.OidcFixtures
import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeStoreTest {
    private val server = TestServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store =
        MeStore(ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, OkHttpClient()), scope)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    @Test
    fun nobodyIsAnAdminUntilTheServerHasSaidSo() {
        assertFalse(store.isAdmin)
        assertNull(store.me.value)
    }

    @Test
    fun anAdminIsKnownOnceLoaded() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))

        runBlocking { store.load() }

        assertTrue(store.isAdmin)
        assertEquals("Owner", store.me.value?.displayName)
    }

    @Test
    fun aFailedReloadKeepsTheLastAnswerSoAnAdminKeepsTheirScreens() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
        runBlocking { store.load() }
        server.script("/api/me", TestServer.jsonError(500, "boom"))

        runBlocking { store.load() }

        assertTrue(store.isAdmin)
        assertEquals("boom", store.errorMessage.value)
    }

    @Test
    fun clearingForgetsWhoWasSignedIn() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
        runBlocking { store.load() }

        store.clear()

        assertFalse(store.isAdmin)
        assertNull(store.me.value)
    }

    @Test
    fun aResyncOrASettingsChangeReloadsAndOtherEventsDoNot() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
        val events = MutableSharedFlow<List<LiveEvent>>()
        store.follow(events)

        runBlocking {
            events.subscriptionCount.first { it > 0 }
            events.emit(listOf(LiveEvent(LiveInvalidation.AlertsChanged, 0)))
            delay(100)
            assertTrue(server.requestsTo("/api/me").isEmpty())

            events.emit(listOf(LiveEvent(LiveInvalidation.SettingsChanged("mail"), 0)))
            withTimeout(5_000) { while (store.me.value == null) delay(10) }
        }

        assertNotNull(store.me.value)
    }
}
