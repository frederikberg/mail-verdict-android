@file:OptIn(ExperimentalCoroutinesApi::class)

package com.frederikberg.mailverdict.kit.live

import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.auth.CredentialRefreshing
import com.frederikberg.mailverdict.kit.auth.OidcTokenHolder
import com.frederikberg.mailverdict.kit.auth.OidcTokens
import com.frederikberg.mailverdict.kit.auth.RefreshOutcome
import com.frederikberg.mailverdict.kit.net.RequestFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SseClientTest {
    private class Recorder : SseCallbacks {
        val records = mutableListOf<SseRecord>()
        var activity = 0
        var connected = 0
        var disconnected = 0

        override fun onRecord(record: SseRecord) {
            records += record
        }

        override fun onActivity() {
            activity++
        }

        override fun onConnected() {
            connected++
        }

        override fun onDisconnected() {
            disconnected++
        }
    }

    private class Rig(
        val client: SseClient,
        val transport: ScriptedTransport,
        val recorder: Recorder,
        val holder: OidcTokenHolder,
        val signedOut: MutableList<String>,
        /** Virtual time at which each attempt reached the transport. */
        val openedAt: MutableList<Long>,
    )

    /** Short numbers so a test reads in a few seconds of virtual time; the rules do not depend on the values. */
    private val timing =
        SseTiming(
            initialBackoffMs = 100,
            maxBackoffMs = 800,
            minAttemptIntervalMs = 50,
            healthyStreamMs = 300,
            staleTimeoutMs = 1_000,
        )

    private fun TestScope.rig(
        accountId: String? = null,
        refresher: CredentialRefreshing? = null,
        mode: (OidcTokenHolder) -> AuthMode = { AuthMode.Oidc(it.tokens) },
    ): Rig {
        val holder = OidcTokenHolder(OidcTokens("access-1", "refresh-1", null, "https://login.example.com", "native"))
        val transport = ScriptedTransport()
        val recorder = Recorder()
        val signedOut = mutableListOf<String>()
        val openedAt = mutableListOf<Long>()
        val timed =
            SseTransport { request ->
                openedAt += testScheduler.currentTime
                transport.open(request)
            }
        val client =
            SseClient(
                scope = backgroundScope,
                requests = RequestFactory("https://mail.example.com") { mode(holder) },
                transport = timed,
                callbacks = recorder,
                accountId = accountId,
                credentialRefresher = refresher,
                onAuthenticationFailure = { signedOut += "signed out" },
                timing = timing,
                clockMs = { testScheduler.currentTime },
                // No jitter, so the gaps between attempts are the backoff itself.
                jitter = { it },
            )
        return Rig(client, transport, recorder, holder, signedOut, openedAt)
    }

    private fun gaps(times: List<Long>) = times.zipWithNext { a, b -> b - a }

    @Test
    fun aRecordReachesTheCallerAndItsIdIsSentBackOnTheNextConnection() =
        runTest {
            val rig = rig()
            val first = rig.transport.next()
            val second = rig.transport.next()
            rig.client.connect()
            runCurrent()
            first.send("id: e1-7\nevent: mail.new\ndata: {\"id\":\"m\"}\n\n")
            first.end()
            advanceTimeBy(1_000)

            assertEquals(listOf(SseRecord("e1-7", "mail.new", """{"id":"m"}""")), rig.recorder.records)
            assertNull(rig.transport.requests[0].header("Last-Event-ID"))
            assertEquals("e1-7", rig.transport.requests[1].header("Last-Event-ID"))
            assertEquals("text/event-stream", rig.transport.requests[1].header("Accept"))
            assertFalse(second.closed)
        }

    /** The idle ping proves the connection alive and is nothing a caller can act on. */
    @Test
    fun theHeartbeatCountsAsActivityAndIsNotHandedToTheCaller() =
        runTest {
            val rig = rig()
            val connection = rig.transport.next()
            rig.client.connect()
            runCurrent()
            connection.send("event: ping\ndata: {}\n\n")
            connection.send("id: a1-2\nevent: mail.new\ndata: {}\n\n")
            runCurrent()

            assertEquals(listOf("mail.new"), rig.recorder.records.map { it.name })
            assertTrue(rig.recorder.activity >= 2)
        }

    /** A record cut in the middle of a chunk boundary, with CRLF line ends, still arrives whole. */
    @Test
    fun aRecordSplitAcrossChunksArrivesWhole() =
        runTest {
            val rig = rig()
            val connection = rig.transport.next()
            rig.client.connect()
            runCurrent()
            connection.send("id: a1-1\r\nevent: mail.new\r")
            connection.send("\ndata: {\"id\":\"é\"")
            connection.send("}\r\n\r\n")
            runCurrent()
            assertEquals(listOf("""{"id":"é"}"""), rig.recorder.records.map { it.data })
        }

    @Test
    fun theAccountScopeReachesTheQueryOnlyWhenOneWasConfigured() =
        runTest {
            val scoped = rig(accountId = "acct-123")
            scoped.transport.next()
            scoped.client.connect()
            runCurrent()
            assertEquals(
                "acct-123",
                scoped.transport.requests
                    .single()
                    .url
                    .queryParameter("account_id"),
            )

            val unscoped = rig()
            unscoped.transport.next()
            unscoped.client.connect()
            runCurrent()
            assertEquals(
                null,
                unscoped.transport.requests
                    .single()
                    .url.query,
            )
        }

    /** Several callers asking at once (a store reacting to a disconnect, a scene change, a retry) produce one connection between them. */
    @Test
    fun severalCallersAskingToConnectAtOnceOpenOneStream() =
        runTest {
            val rig = rig()
            rig.transport.next()
            repeat(8) { rig.client.connect() }
            advanceTimeBy(500)
            assertEquals(1, rig.client.attemptsStarted)
        }

    /** A link that accepts and drops is what a phone on a weak connection produces; the attempts must space out and slow down, never spin. */
    @Test
    fun aStreamThatDiesAtOnceIsRetriedWithGrowingGapsUpToTheCap() =
        runTest {
            val rig = rig()
            rig.transport.otherwise { ScriptedTransport.Connection().also { it.end() } }
            rig.client.connect()
            advanceTimeBy(5_000)

            val gaps = gaps(rig.openedAt)
            assertTrue("only ${rig.openedAt.size} attempts", rig.openedAt.size >= 6)
            assertTrue("gaps $gaps", gaps.all { it >= timing.minAttemptIntervalMs && it <= timing.maxBackoffMs })
            assertEquals("the gaps grow until the cap, then hold", gaps.sorted(), gaps)
            assertEquals(timing.maxBackoffMs, gaps.last())
        }

    /** Response headers alone must never reset the backoff: only traffic over a healthy interval does. */
    @Test
    fun aStreamThatCarriedTrafficForAWhileResetsTheBackoff() =
        runTest {
            val rig = rig()
            repeat(3) { rig.transport.next(ScriptedTransport.Connection().also { it.end() }) }
            val healthy = rig.transport.next()
            rig.transport.next()
            rig.client.connect()
            advanceTimeBy(1_000)
            val grown = gaps(rig.openedAt).last()
            assertTrue("the backoff had grown past its start", grown > timing.initialBackoffMs)

            advanceTimeBy(timing.healthyStreamMs + 50)
            healthy.send("event: ping\ndata: {}\n\n")
            runCurrent()
            val dropped = testScheduler.currentTime
            healthy.end()
            advanceTimeBy(1_000)

            assertEquals(timing.initialBackoffMs, rig.openedAt.last() - dropped)
        }

    @Test
    fun aStreamThatGoesSilentIsTornDownAndReopened() =
        runTest {
            val rig = rig()
            val silent = rig.transport.next()
            rig.transport.next()
            rig.client.connect()
            runCurrent()
            advanceTimeBy(timing.staleTimeoutMs - 1)
            assertEquals(1, rig.transport.connections.size)

            advanceTimeBy(1 + timing.initialBackoffMs + 10)
            assertTrue(silent.closed)
            assertEquals(2, rig.transport.connections.size)
            assertEquals(1, rig.recorder.disconnected)
        }

    @Test
    fun aPausedStreamIsClosedAndResumesFromTheLastId() =
        runTest {
            val rig = rig()
            val first = rig.transport.next()
            rig.transport.next()
            rig.client.connect()
            runCurrent()
            first.send("id: e1-3\nevent: mail.new\ndata: {}\n\n")
            runCurrent()

            rig.client.pause()
            advanceTimeBy(10_000)
            assertTrue(first.closed)
            assertEquals("a paused client opens nothing", 1, rig.transport.connections.size)

            rig.client.resume()
            advanceTimeBy(500)
            assertEquals(
                "e1-3",
                rig.transport.requests
                    .last()
                    .header("Last-Event-ID"),
            )
            assertEquals(1, rig.transport.live)
        }

    /** The network coming back reconnects now instead of after the backoff it had grown. */
    @Test
    fun retryNowCutsTheBackoffShort() =
        runTest {
            val rig = rig()
            rig.transport.next(ScriptedTransport.Connection().also { it.end() })
            rig.transport.next(ScriptedTransport.Connection().also { it.end() })
            rig.transport.next()
            rig.client.connect()
            advanceTimeBy(timing.initialBackoffMs + timing.minAttemptIntervalMs + 10)
            assertEquals(2, rig.transport.connections.size)

            // The third attempt waits out a doubled backoff; the network returns well before it ends.
            advanceTimeBy(timing.minAttemptIntervalMs)
            val before = testScheduler.currentTime
            rig.client.retryNow()
            advanceTimeBy(timing.minAttemptIntervalMs + 1)
            assertEquals(3, rig.transport.connections.size)
            assertTrue(rig.openedAt.last() - before <= timing.minAttemptIntervalMs)
        }

    /** The state a phone's scene changes drive: random connects, pauses, resumes and retries must never produce two live streams or a burst, and must end up connected. */
    @Test
    fun aFuzzedLifecycleNeverHoldsTwoStreamsOrBurstsAndEndsConnected() =
        runTest {
            val rig = rig()
            rig.transport.otherwise { ScriptedTransport.Connection() }
            val random = Random(7)
            rig.client.connect()
            repeat(400) {
                when (random.nextInt(5)) {
                    0 -> rig.client.connect()
                    1 -> rig.client.pause()
                    2 -> rig.client.resume()
                    3 -> rig.client.retryNow()
                    else -> {
                        rig.client.pause()
                        rig.client.resume()
                    }
                }
                advanceTimeBy(random.nextLong(1, 40))
            }
            val elapsed = currentTime
            rig.client.resume()
            rig.client.connect()
            advanceTimeBy(2_000)

            assertEquals("two streams were open at once", 1, rig.transport.peak)
            assertEquals(1, rig.transport.live)
            val starts = rig.client.attemptsStarted
            assertTrue("$starts attempts in ${elapsed}ms", starts <= elapsed / timing.minAttemptIntervalMs + 3)
        }

    @Test
    fun aDisconnectedClientCannotBeRevived() =
        runTest {
            val rig = rig()
            val connection = rig.transport.next()
            rig.client.connect()
            runCurrent()
            rig.client.disconnect()
            runCurrent()
            val opened = rig.transport.connections.size
            rig.client.connect()
            rig.client.resume()
            advanceTimeBy(5_000)

            assertTrue(connection.closed)
            assertEquals(opened, rig.transport.connections.size)
            assertEquals(1, rig.recorder.disconnected)
        }

    @Test
    fun aConnectClosedInTheSameTurnOpensNothing() =
        runTest {
            val rig = rig()
            rig.client.connect()
            rig.client.disconnect()
            advanceTimeBy(5_000)
            assertEquals(0, rig.transport.connections.size)
            assertEquals(0, rig.openedAt.size)
        }

    // A refused stream under a signed-in session

    private class FakeRefresher(
        private val holder: OidcTokenHolder,
        private val outcome: RefreshOutcome,
    ) : CredentialRefreshing {
        val calls = mutableListOf<String?>()

        override suspend fun refreshIfNeeded() = Unit

        override suspend fun refresh(afterRejectionOf: String?): RefreshOutcome {
            calls += afterRejectionOf
            if (outcome ==
                RefreshOutcome.Refreshed
            ) {
                holder.tokens =
                    holder.tokens.copy(accessToken = "access-2", refreshToken = "refresh-2")
            }
            return outcome
        }
    }

    private fun refusal(
        status: Int,
        body: String,
    ) = ScriptedTransport.Connection(status, body.toByteArray())

    @Test
    fun aRefusedStreamRenewsTheTokenAndReconnectsWithIt() =
        runTest {
            val holder =
                OidcTokenHolder(OidcTokens("access-1", "refresh-1", null, "https://login.example.com", "native"))
            val refresher = FakeRefresher(holder, RefreshOutcome.Refreshed)
            val rig = rig(refresher = refresher, mode = { AuthMode.Oidc(holder.tokens) })
            rig.transport.next(refusal(401, """{"detail":"not_authenticated"}"""))
            rig.transport.next()
            rig.client.connect()
            advanceTimeBy(500)

            assertEquals(listOf<String?>("access-1"), refresher.calls)
            assertEquals(
                listOf("Bearer access-1", "Bearer access-2"),
                rig.transport.requests.map { it.header("Authorization") },
            )
            assertTrue(rig.signedOut.isEmpty())
            assertEquals(1, rig.recorder.connected)
        }

    /** The stream cannot tell the proxy's 403 from the app's by status, so the body decides; and a refusal of the renewed token is the end of the session, not another renewal. */
    @Test
    fun aRefusalOfTheRenewedTokenSignsOutInsteadOfRenewingAgain() =
        runTest {
            val holder =
                OidcTokenHolder(OidcTokens("access-1", "refresh-1", null, "https://login.example.com", "native"))
            val refresher = FakeRefresher(holder, RefreshOutcome.Refreshed)
            val rig = rig(refresher = refresher, mode = { AuthMode.Oidc(holder.tokens) })
            rig.transport.otherwise { refusal(403, "Forbidden") }
            rig.client.connect()
            advanceTimeBy(500)

            assertEquals("one renewal, then the session is over", 1, refresher.calls.size)
            assertEquals(1, rig.signedOut.size)
            assertEquals(2, rig.transport.connections.size)
        }

    @Test
    fun aRejectedRenewalSignsOut() =
        runTest {
            val holder =
                OidcTokenHolder(OidcTokens("access-1", "refresh-1", null, "https://login.example.com", "native"))
            val rig = rig(refresher = FakeRefresher(holder, RefreshOutcome.Rejected))
            rig.transport.otherwise { refusal(401, """{"detail":"not_authenticated"}""") }
            rig.client.connect()
            advanceTimeBy(5_000)
            assertEquals("reported once, and no retry follows", 1, rig.signedOut.size)
            assertEquals(1, rig.transport.connections.size)
        }

    @Test
    fun anUnreachableProviderKeepsTheSessionAndRetries() =
        runTest {
            val holder =
                OidcTokenHolder(OidcTokens("access-1", "refresh-1", null, "https://login.example.com", "native"))
            val rig = rig(refresher = FakeRefresher(holder, RefreshOutcome.Unavailable))
            rig.transport.next(refusal(401, "{}"))
            rig.transport.next()
            rig.client.connect()
            advanceTimeBy(500)
            assertTrue(rig.signedOut.isEmpty())
            assertEquals(1, rig.recorder.connected)
        }

    @Test
    fun aDisabledUserSignsOutWithoutAnyRenewal() =
        runTest {
            val holder =
                OidcTokenHolder(OidcTokens("access-1", "refresh-1", null, "https://login.example.com", "native"))
            val refresher = FakeRefresher(holder, RefreshOutcome.Refreshed)
            val rig = rig(refresher = refresher)
            rig.transport.otherwise { refusal(403, """{"detail":"user_disabled"}""") }
            rig.client.connect()
            advanceTimeBy(5_000)
            assertTrue(refresher.calls.isEmpty())
            assertEquals(1, rig.signedOut.size)
            assertEquals(1, rig.transport.connections.size)
        }

    /** A 403 with a detail is the app refusing a request, not the proxy refusing a credential: nothing renews, nothing signs out. */
    @Test
    fun anAppRefusalIsNotACredentialRefusal() =
        runTest {
            val holder =
                OidcTokenHolder(OidcTokens("access-1", "refresh-1", null, "https://login.example.com", "native"))
            val refresher = FakeRefresher(holder, RefreshOutcome.Refreshed)
            val rig = rig(refresher = refresher)
            rig.transport.next(refusal(403, """{"detail":"admin_required"}"""))
            rig.transport.next()
            rig.client.connect()
            advanceTimeBy(500)
            assertTrue(refresher.calls.isEmpty())
            assertTrue(rig.signedOut.isEmpty())
            assertEquals(1, rig.recorder.connected)
        }

    @Test
    fun aRefusedStreamWithNothingToRenewItWithSignsOut() =
        runTest {
            val rig = rig(mode = { AuthMode.Bearer("pasted") })
            rig.transport.otherwise { refusal(401, "") }
            rig.client.connect()
            advanceTimeBy(5_000)
            assertEquals(1, rig.signedOut.size)
            assertEquals(1, rig.transport.connections.size)
        }

    @Test
    fun jitterStaysWithinItsFractionOfTheDelay() {
        val timing = SseTiming()
        val random = Random(1)
        repeat(500) {
            val delay = timing.jittered(2_000, random)
            val spread = 2_000 * timing.jitterFraction
            assertTrue(delay >= 2_000 - spread && delay <= 2_000 + spread)
        }
    }
}
