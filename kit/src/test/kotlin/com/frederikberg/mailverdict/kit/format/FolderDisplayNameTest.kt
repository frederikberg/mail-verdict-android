package com.frederikberg.mailverdict.kit.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderDisplayNameTest {
    @Test
    fun anExplicitNameWins() {
        assertEquals("Mine", folderDisplayName("INBOX", "Mine", "inbox"))
    }

    @Test
    fun aRoleGetsItsLabelSoAServersRawNameNeverShows() {
        assertEquals("Inbox", folderDisplayName("INBOX", null, "inbox"))
        assertEquals("Trash", folderDisplayName("Gelöschte Elemente", "", "trash"))
        assertEquals("Junk", folderDisplayName("Spam", null, "junk"))
    }

    @Test
    fun anythingElseKeepsTheServerName() {
        assertEquals("Projects/2026", folderDisplayName("Projects/2026", null, null))
        assertEquals("Flagged", folderDisplayName("Flagged", null, "flagged"))
    }
}
