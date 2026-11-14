@file:OptIn(ExperimentalCoroutinesApi::class)

package com.frederikberg.mailverdict.kit.live

import com.frederikberg.mailverdict.contract.SseEventName
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.net.RequestFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every event name the contract lists gets a decision here, and the hub's batching is checked against a scripted stream. */
class LiveEventHubTest {
    private fun record(
        name: String,
        json: String = "{}",
    ) = SseRecord(null, name, json)

    private fun map(
        name: String,
        json: String = "{}",
    ) = LiveInvalidation.from(record(name, json))

    // The table

    @Test
    fun connectedAndResyncAreHandledBeforeAnythingElse() {
        assertEquals(LiveInvalidation.Ignored(SseEventName.Connected), map("connected"))
        assertEquals(LiveInvalidation.Resync, map("resync"))
    }

    @Test
    fun mailEventsCarryAccountFolderAndMessage() {
        assertEquals(
            LiveInvalidation.MailNew("a", "f", "m"),
            map("mail.new", """{"id":"m","account_id":"a","folder_id":"f"}"""),
        )
        assertEquals(LiveInvalidation.MailDeleted(null, null, "m"), map("mail.deleted", """{"id":"m"}"""))
    }

    /** A move names the folder it left, so a list showing only the old folder knows to refetch. */
    @Test
    fun aMailUpdateCarriesTheChangedColumnsAndTheFolderItLeft() {
        assertEquals(
            LiveInvalidation.MailUpdated(null, "new", "old", "m", listOf("folder_id", "is_seen")),
            map(
                "mail.updated",
                """{"id":"m","folder_id":"new","old_folder_id":"old","changed":["folder_id","is_seen"]}""",
            ),
        )
    }

    @Test
    fun onlyAFlagOrLabelChangeLeavesFolderCountsAlone() {
        assertFalse(LiveInvalidation.messageUpdateMovesCounts(listOf("is_flagged", "keywords")))
        assertTrue(LiveInvalidation.messageUpdateMovesCounts(listOf("is_flagged", "is_seen")))
        assertTrue(
            "an update that names nothing is assumed to matter",
            LiveInvalidation.messageUpdateMovesCounts(emptyList()),
        )
    }

    @Test
    fun theRemainingEventsMapToTheirInvalidation() {
        assertEquals(
            LiveInvalidation.VerdictIssued(null, "m", true),
            map("verdict.issued", """{"message_id":"m","is_spam":true}"""),
        )
        assertEquals(LiveInvalidation.AlertsChanged, map("alert.new"))
        assertEquals(LiveInvalidation.AlertsChanged, map("alert.dismissed"))
        assertEquals(LiveInvalidation.NotificationsChanged, map("notification.new"))
        assertEquals(LiveInvalidation.AccountsChanged, map("account.changed"))
        assertEquals(LiveInvalidation.FolderSynced("f"), map("folder.synced", """{"folder_id":"f","backfill":false}"""))
        assertEquals(LiveInvalidation.FoldersChanged, map("folder.changed"))
        assertEquals(LiveInvalidation.SettingsChanged("mail"), map("settings.changed", """{"category":"mail"}"""))
        assertEquals(LiveInvalidation.SettingsChanged(null), map("settings.changed"))
        assertEquals(LiveInvalidation.IdentitiesChanged("a"), map("identity.changed", """{"account_id":"a"}"""))
        assertEquals(
            LiveInvalidation.OrderChanged("o", "created"),
            map("order.updated", """{"order_id":"o","change":"created"}"""),
        )
    }

    @Test
    fun anOutboxRowCarriesItsStatusAndAReplyOnlyTouchesTheInvitationCard() {
        assertEquals(
            LiveInvalidation.OutboxUpdated(OutboxEventPayload("o", emptyList(), "sent", "send")),
            map("outbox.updated", """{"id":"o","changed":[],"status":"sent","kind":"send"}"""),
        )
        assertEquals(LiveInvalidation.InvitationOrEventChanged, map("outbox.updated", """{"id":"o","itip":"reply"}"""))
        assertEquals(LiveInvalidation.InvitationOrEventChanged, map("calendar.object"))
    }

    /** A new name in the contract fails here until someone decides what it means; ignoring it must be a visible choice. */
    @Test
    fun everyNameTheContractListsHasADecision() {
        for (name in SseEventName.all) {
            assertNotEquals("${name.raw} has no entry in the table", LiveInvalidation.Unknown(name.raw), map(name.raw))
        }
        assertEquals(LiveInvalidation.Unknown("some.future.event"), map("some.future.event"))
    }

    @Test
    fun aMalformedBodyStillClassifiesTheEvent() {
        assertEquals(LiveInvalidation.MailNew(null, null, null), map("mail.new", "not json"))
        assertEquals(LiveInvalidation.MailNew(null, null, null), map("mail.new", """["array"]"""))
    }

    // The hub

    private class Rig(
        val hub: LiveEventHub,
        val transport: ScriptedTransport,
        val batches: MutableList<List<LiveEvent>>,
    )

    /** Short numbers so a test reads in a few seconds of virtual time; the rules do not depend on the values. */
    private val timing = SseTiming(initialBackoffMs = 100, maxBackoffMs = 800, minAttemptIntervalMs = 50)

    private fun TestScope.rig(): Rig {
        val transport = ScriptedTransport()
        val batches = mutableListOf<List<LiveEvent>>()
        val hub =
            LiveEventHub(backgroundScope, nowMs = { currentTime }) { callbacks ->
                SseClient(
                    scope = backgroundScope as CoroutineScope,
                    requests = RequestFactory("https://mail.example.com", "Test phone") { AuthMode.None },
                    transport = transport,
                    callbacks = callbacks,
                    timing = timing,
                    clockMs = { currentTime },
                    jitter = { it },
                )
            }
        hub.events.onEach { batches += it }.launchIn(backgroundScope)
        runCurrent()
        return Rig(hub, transport, batches)
    }

    private fun mail(id: String) = "event: mail.new\ndata: {\"id\":\"$id\"}\n\n"

    /** Several mail events arriving faster than the window reach a store as one batch, not one delivery each. */
    @Test
    fun aBurstOfMailEventsIsDeliveredAsOneBatch() =
        runTest {
            val rig = rig()
            val stream = rig.transport.next()
            rig.hub.connect()
            runCurrent()
            stream.send(mail("1") + mail("2") + mail("3"))
            runCurrent()
            assertTrue("held until the window ends", rig.batches.isEmpty())

            advanceTimeBy(LiveEventHub.COALESCE_MS)
            runCurrent()
            assertEquals(1, rig.batches.size)
            assertEquals(
                listOf("1", "2", "3"),
                rig.batches.single().map {
                    (it.invalidation as LiveInvalidation.MailNew).messageId
                },
            )
        }

    /** The hold delays delivery, so each event keeps the moment its own record arrived: that is what tells a store whether its re-read began before the change. */
    @Test
    fun eachEventKeepsTheMomentItsRecordArrived() =
        runTest {
            val rig = rig()
            val stream = rig.transport.next()
            rig.hub.connect()
            runCurrent()
            stream.send(mail("1"))
            runCurrent()
            val first = currentTime
            advanceTimeBy(200)
            stream.send(mail("2"))
            advanceTimeBy(LiveEventHub.COALESCE_MS)
            runCurrent()

            assertEquals(listOf(first, first + 200), rig.batches.single().map { it.arrivedAtMs })
        }

    @Test
    fun otherEventsAreDeliveredAtOnceAndAheadOfAHeldBurst() =
        runTest {
            val rig = rig()
            val stream = rig.transport.next()
            rig.hub.connect()
            runCurrent()
            stream.send(mail("1") + "event: account.changed\ndata: {}\n\n")
            runCurrent()

            assertEquals(
                listOf(listOf(LiveInvalidation.AccountsChanged)),
                rig.batches.map { batch ->
                    batch.map {
                        it.invalidation
                    }
                },
            )
        }

    @Test
    fun theConnectionStateFollowsTheStream() =
        runTest {
            val rig = rig()
            assertEquals(ConnectionState.Disconnected, rig.hub.connectionState.value)
            val stream = rig.transport.next()
            rig.hub.connect()
            assertEquals(ConnectionState.Reconnecting, rig.hub.connectionState.value)
            runCurrent()
            assertEquals(ConnectionState.Connected, rig.hub.connectionState.value)

            stream.end()
            runCurrent()
            assertEquals(
                "a lost stream retries, it is never 'disconnected'",
                ConnectionState.Reconnecting,
                rig.hub.connectionState.value,
            )

            rig.hub.disconnect()
            runCurrent()
            assertEquals(ConnectionState.Disconnected, rig.hub.connectionState.value)
        }

    /** A reconnect that finishes within the grace period never surfaces, so "Connecting…" means the connection is genuinely struggling. */
    @Test
    fun aShortReconnectNeverReachesTheScreen() =
        runTest {
            val rig = rig()
            val first = rig.transport.next()
            rig.transport.next()
            rig.hub.connect()
            runCurrent()
            assertEquals(ConnectionState.Connected, rig.hub.visibleConnectionState.value)

            first.end()
            runCurrent()
            assertEquals(ConnectionState.Reconnecting, rig.hub.connectionState.value)
            assertEquals(ConnectionState.Connected, rig.hub.visibleConnectionState.value)
            advanceTimeBy(timing.initialBackoffMs + 10)
            assertEquals(
                "back before the grace period ended",
                ConnectionState.Connected,
                rig.hub.visibleConnectionState.value,
            )
        }

    @Test
    fun aLongOutageSurfacesAfterTheGracePeriod() =
        runTest {
            val rig = rig()
            rig.transport.next().end()
            rig.transport.unreachable = true
            rig.hub.connect()
            runCurrent()
            assertEquals(ConnectionState.Connected, rig.hub.visibleConnectionState.value)
            advanceTimeBy(LiveEventHub.RECONNECTING_GRACE_MS + 10)
            assertEquals(ConnectionState.Reconnecting, rig.hub.visibleConnectionState.value)
        }

    @Test
    fun theNetworkReturningReachesEveryStoreAndReconnectsAtOnce() =
        runTest {
            val rig = rig()
            var restored = 0
            rig.hub.pathRestored
                .onEach { restored++ }
                .launchIn(backgroundScope)
            rig.transport.next().end()
            rig.transport.next()
            rig.hub.connect()
            runCurrent()
            val opened = rig.transport.connections.size

            rig.hub.networkPathRestored()
            advanceTimeBy(timing.minAttemptIntervalMs + 1)
            assertEquals(1, restored)
            assertTrue(
                "sooner than the backoff would have allowed",
                timing.minAttemptIntervalMs + 1 < timing.initialBackoffMs,
            )
            assertEquals("a stream waiting out its backoff goes now", opened + 1, rig.transport.connections.size)
        }

    @Test
    fun aHubPausedInTheBackgroundHoldsNoStreamAndResumesWithoutNoise() =
        runTest {
            val rig = rig()
            val stream = rig.transport.next()
            rig.transport.next()
            rig.hub.connect()
            runCurrent()

            rig.hub.pause()
            runCurrent()
            assertTrue(stream.closed)
            assertEquals(ConnectionState.Reconnecting, rig.hub.connectionState.value)
            assertEquals(
                "nothing is on screen, so nothing is surfaced",
                ConnectionState.Connected,
                rig.hub.visibleConnectionState.value,
            )

            rig.hub.resume()
            advanceTimeBy(timing.minAttemptIntervalMs + 1)
            assertEquals(ConnectionState.Connected, rig.hub.connectionState.value)
        }
}
