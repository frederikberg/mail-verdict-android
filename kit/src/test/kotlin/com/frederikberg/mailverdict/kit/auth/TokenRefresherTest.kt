package com.frederikberg.mailverdict.kit.auth

import com.frederikberg.mailverdict.kit.OidcFixtures
import com.frederikberg.mailverdict.kit.TestServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TokenRefresherTest {
    private val server = TestServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private class Rig(
        val refresher: TokenRefresher,
        val holder: OidcTokenHolder,
        /** `access/refresh` of every token set handed to the store, and what the holder still held at that moment. */
        val persisted: MutableList<String>,
        val heldWhenPersisted: MutableList<String>,
    )

    private fun rig(
        tokens: OidcTokens = OidcFixtures.tokens(server.baseUrl),
        reload: () -> OidcTokens? = { null },
        nowMs: () -> Long = { 0 },
    ): Rig {
        val holder = OidcTokenHolder(tokens)
        val persisted = CopyOnWriteArrayList<String>()
        val held = CopyOnWriteArrayList<String>()
        val refresher =
            TokenRefresher(
                scope = scope,
                holder = holder,
                client = OidcClient(),
                persist = {
                    persisted += "${it.accessToken}/${it.refreshToken}"
                    held += holder.tokens.accessToken
                },
                reload = reload,
                nowMs = nowMs,
            )
        return Rig(refresher, holder, persisted, held)
    }

    private fun provider(vararg token: mockwebserver3.MockResponse) {
        server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server))
        server.script(OidcFixtures.TOKEN, *token)
    }

    private fun tokenRequests() = server.requestsTo(OidcFixtures.TOKEN).size

    @Test
    fun theRotatedTokensAreStoredBeforeTheyAreUsed() =
        runBlocking {
            provider(OidcFixtures.tokenAnswer("access-2", "refresh-2"))
            val rig = rig()
            assertEquals(RefreshOutcome.Refreshed, rig.refresher.refresh(afterRejectionOf = "access-1"))
            assertEquals("access-2", rig.holder.tokens.accessToken)
            assertEquals("refresh-2", rig.holder.tokens.refreshToken)
            assertEquals(listOf("access-2/refresh-2"), rig.persisted)
            assertEquals(
                "the store was written while the old tokens were still in use",
                listOf("access-1"),
                rig.heldWhenPersisted,
            )
        }

    /** Zitadel retires a refresh token the moment it is exchanged, so twenty requests refused together must produce one exchange. */
    @Test
    fun concurrentRefusalsRenewOnce() =
        runBlocking {
            provider(OidcFixtures.tokenAnswer("access-2", "refresh-2", delayMs = 300))
            val rig = rig()
            val outcomes =
                (1..20)
                    .map {
                        async(Dispatchers.Default) { rig.refresher.refresh(afterRejectionOf = "access-1") }
                    }.awaitAll()
            assertEquals(List(20) { RefreshOutcome.Refreshed }, outcomes)
            assertEquals(1, tokenRequests())
            assertEquals(1, rig.persisted.size)
        }

    @Test
    fun aRefusalOfAnAlreadyReplacedTokenAsksNobody() =
        runBlocking {
            val rig = rig(OidcFixtures.tokens(server.baseUrl, access = "access-2"))
            assertEquals(RefreshOutcome.Refreshed, rig.refresher.refresh(afterRejectionOf = "access-1"))
            assertTrue(server.requests.isEmpty())
        }

    @Test
    fun aRefusedRefreshTokenEndsTheSessionAndIsNotAskedAgain() =
        runBlocking {
            provider(OidcFixtures.tokenRefusal())
            val rig = rig()
            assertEquals(RefreshOutcome.Rejected, rig.refresher.refresh(afterRejectionOf = "access-1"))
            assertEquals(RefreshOutcome.Rejected, rig.refresher.refresh(afterRejectionOf = "access-1"))
            assertEquals(1, tokenRequests())
            assertTrue(rig.persisted.isEmpty())
            assertEquals("access-1", rig.holder.tokens.accessToken)
        }

    @Test
    fun anOutageKeepsTheSessionAndTriesAgainNextTime() =
        runBlocking {
            provider(TestServer.status(503))
            val rig = rig()
            assertEquals(RefreshOutcome.Unavailable, rig.refresher.refresh(afterRejectionOf = "access-1"))

            provider(OidcFixtures.tokenAnswer("access-2", "refresh-2"))
            assertEquals(RefreshOutcome.Refreshed, rig.refresher.refresh(afterRejectionOf = "access-1"))
            assertEquals(2, tokenRequests())
        }

    @Test
    fun noRefreshTokenMeansTheSessionEndsWithTheAccessToken() =
        runBlocking {
            val rig = rig(OidcFixtures.tokens(server.baseUrl, refresh = null))
            assertEquals(RefreshOutcome.Rejected, rig.refresher.refresh(afterRejectionOf = "access-1"))
            assertTrue(server.requests.isEmpty())
        }

    @Test
    fun refreshIfNeededRenewsOnlyAnExpiringToken() =
        runBlocking {
            val now = 2_000_000L
            provider(OidcFixtures.tokenAnswer("access-2", "refresh-2"))

            val fresh = rig(OidcFixtures.tokens(server.baseUrl, expiresAtMs = now + 600_000), nowMs = { now })
            fresh.refresher.refreshIfNeeded()
            assertEquals("access-1", fresh.holder.tokens.accessToken)
            assertEquals(0, tokenRequests())

            val stale = rig(OidcFixtures.tokens(server.baseUrl, expiresAtMs = now + 30_000), nowMs = { now })
            stale.refresher.refreshIfNeeded()
            assertEquals("access-2", stale.holder.tokens.accessToken)

            val unstated = rig(OidcFixtures.tokens(server.baseUrl, expiresAtMs = null), nowMs = { now })
            unstated.refresher.refreshIfNeeded()
            assertEquals("access-1", unstated.holder.tokens.accessToken)
        }

    /** Another component may have renewed the stored tokens while this one slept; spending the refresh token again would be refused, since Zitadel retires it on use. */
    @Test
    fun aTokenSetRenewedElsewhereIsTakenWithoutAskingTheProvider() =
        runBlocking {
            val now = 2_000_000L
            val stored = OidcFixtures.tokens(server.baseUrl, "access-2", "refresh-2", expiresAtMs = now + 3_000_000)
            val rig = rig(reload = { stored }, nowMs = { now })
            assertEquals(RefreshOutcome.Refreshed, rig.refresher.refresh(afterRejectionOf = "access-1"))
            assertEquals(stored, rig.holder.tokens)
            assertTrue(server.requests.isEmpty())
            assertTrue(rig.persisted.isEmpty())
        }

    @Test
    fun aStoredTokenSetThatIsAlsoExpiredRefreshesWithItsNewerRefreshToken() =
        runBlocking {
            val now = 2_000_000L
            val stored = OidcFixtures.tokens(server.baseUrl, "access-2", "refresh-2", expiresAtMs = now - 10_000)
            provider(OidcFixtures.tokenAnswer("access-3", "refresh-3"))
            val rig = rig(reload = { stored }, nowMs = { now })
            rig.refresher.refresh(afterRejectionOf = "access-1")
            assertTrue(
                OidcFixtures.bodyOf(server.requestsTo(OidcFixtures.TOKEN).last()).contains("refresh_token=refresh-2"),
            )
            assertEquals("access-3", rig.holder.tokens.accessToken)
        }

    /** The exchange carries the only valid refresh token, so a caller that gives up midway must not abandon it. */
    @Test
    fun aCallerThatIsCancelledDoesNotAbandonTheExchange() =
        runBlocking {
            provider(OidcFixtures.tokenAnswer("access-2", "refresh-2", delayMs = 300))
            val rig = rig()
            val caller = launch(Dispatchers.Default) { rig.refresher.refresh(afterRejectionOf = "access-1") }
            while (server.requestsTo(OidcFixtures.TOKEN).isEmpty()) delay(10)
            caller.cancel()
            caller.join()

            while (rig.persisted.isEmpty()) delay(10)
            assertEquals(listOf("access-2/refresh-2"), rig.persisted)
            assertEquals("access-2", rig.holder.tokens.accessToken)
        }
}
