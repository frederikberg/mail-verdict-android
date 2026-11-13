@file:OptIn(ExperimentalCoroutinesApi::class)

package com.frederikberg.mailverdict.kit.live

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityTest {
    @Test
    fun noPathMeansOfflineWhateverTheStreamSays() {
        for (stream in ConnectionState.entries) {
            assertEquals(Reachability.Offline, Reachability.of(pathAvailable = false, stream = stream))
        }
    }

    @Test
    fun aPathWithoutALiveStreamMeansTheServerIsTheProblem() {
        assertEquals(Reachability.Online, Reachability.of(true, ConnectionState.Connected))
        assertEquals(Reachability.ServerUnreachable, Reachability.of(true, ConnectionState.Reconnecting))
        assertEquals(
            "a stream never started or closed on sign-out is not 'the server is down'",
            Reachability.Offline,
            Reachability.of(true, ConnectionState.Disconnected),
        )
    }

    private class FakeConnectivity(
        override var isOnline: Boolean = true,
    ) : Connectivity {
        var listener: ((Boolean) -> Unit)? = null

        override fun observe(listener: (online: Boolean) -> Unit) {
            this.listener = listener
        }

        fun change(online: Boolean) {
            isOnline = online
            listener?.invoke(online)
        }
    }

    /** A handover between Wi-Fi and cellular reports a brief loss; showing "offline" for it would flash a banner for nothing. */
    @Test
    fun aBriefLossOfThePathNeverShows() =
        runTest {
            val connectivity = FakeConnectivity()
            val status = NetworkStatus(backgroundScope, connectivity, offlineGraceMs = 2_000)
            connectivity.change(false)
            advanceTimeBy(1_999)
            assertTrue(status.pathAvailable.value)
            connectivity.change(true)
            advanceTimeBy(5_000)
            assertTrue(status.pathAvailable.value)
        }

    @Test
    fun aLossThatLastsShowsAndARecoveryShowsAtOnce() =
        runTest {
            val connectivity = FakeConnectivity()
            val status = NetworkStatus(backgroundScope, connectivity, offlineGraceMs = 2_000)
            connectivity.change(false)
            advanceTimeBy(2_001)
            assertFalse(status.pathAvailable.value)
            connectivity.change(true)
            runCurrent()
            assertTrue(status.pathAvailable.value)
        }
}
