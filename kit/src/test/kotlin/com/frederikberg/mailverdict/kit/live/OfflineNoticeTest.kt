package com.frederikberg.mailverdict.kit.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineNoticeTest {
    private val now = 10_000_000L

    @Test
    fun onlineAndLiveSaysNothing() {
        assertNull(OfflineNotice.make(Reachability.Online, savedAtMs = null, nowMs = now))
    }

    @Test
    fun offlineAndServerUnreachableAreToldApart() {
        assertEquals("Offline", OfflineNotice.make(Reachability.Offline, null, now)?.text)
        assertEquals("Server unreachable", OfflineNotice.make(Reachability.ServerUnreachable, null, now)?.text)
    }

    @Test
    fun aSavedCopyNamesItsAge() {
        assertEquals(
            "Offline · Saved 5 min ago",
            OfflineNotice.make(Reachability.Offline, now - 5 * 60_000, now)?.text,
        )
        assertEquals(
            "Showing a saved copy · Saved 3 h ago",
            OfflineNotice.make(Reachability.Online, now - 3 * 3_600_000, now)?.text,
        )
        assertEquals("Offline · Saved just now", OfflineNotice.make(Reachability.Offline, now - 10_000, now)?.text)
        assertEquals(
            "Offline · Saved 2 d ago",
            OfflineNotice.make(Reachability.Offline, now - 49 * 3_600_000, now)?.text,
        )
    }
}
