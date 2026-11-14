package com.frederikberg.mailverdict.kit.stores.mailboxes

import com.frederikberg.mailverdict.contract.FolderOrderItem
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxesSupportTest {
    private fun item(
        name: String,
        specialUse: String? = null,
        visible: Boolean = true,
    ) = FolderOrderItem(folderId = "id-$name", imapName = name, specialUse = specialUse, isVisible = visible)

    private fun names(items: List<FolderOrderItem>) = items.map { it.imapName }

    @Test
    fun withoutASavedOrderTheSixRolesLeadInTheirFixedSequenceThenTheRestAlphabetically() {
        val items =
            listOf(
                item("zeta"),
                item("Trash", "trash"),
                item("alpha"),
                item("Sent", "sent"),
                item("INBOX", "inbox"),
                item("Junk", "junk"),
                item("Archive", "archive"),
                item("Drafts", "drafts"),
            )

        assertEquals(
            listOf("INBOX", "Drafts", "Sent", "Archive", "Junk", "Trash", "alpha", "zeta"),
            names(MailboxesSupport.orderFolders(items, hasCustomOrder = false)),
        )
    }

    @Test
    fun anUnrecognisedRoleSortsAfterTheNamedSix() {
        val items = listOf(item("Flagged", "flagged"), item("Trash", "trash"), item("Mine"))

        assertEquals(
            listOf("Trash", "Flagged", "Mine"),
            names(MailboxesSupport.orderFolders(items, hasCustomOrder = false)),
        )
    }

    @Test
    fun aSavedOrderIsKeptExceptThatTheInboxLeads() {
        val items = listOf(item("Work"), item("Sent", "sent"), item("INBOX", "inbox"), item("Home"))

        assertEquals(
            listOf("INBOX", "Work", "Sent", "Home"),
            names(MailboxesSupport.orderFolders(items, hasCustomOrder = true)),
        )
    }

    @Test
    fun aSavedOrderWithTheInboxFirstIsUntouched() {
        val items = listOf(item("INBOX", "inbox"), item("Work"), item("Sent", "sent"))

        assertEquals(names(items), names(MailboxesSupport.orderFolders(items, hasCustomOrder = true)))
    }

    @Test
    fun hiddenFoldersNeverShow() {
        val items = listOf(item("INBOX", "inbox"), item("Secret", visible = false))

        assertEquals(listOf("INBOX"), names(MailboxesSupport.orderFolders(items, hasCustomOrder = false)))
        assertEquals(listOf("INBOX"), names(MailboxesSupport.orderFolders(items, hasCustomOrder = true)))
    }

    @Test
    fun draftsShowTheirTotalAndEveryOtherFolderItsUnreadCount() {
        assertEquals(7, MailboxesSupport.folderBadgeCount("drafts", unreadCount = 0, totalCount = 7))
        assertEquals(2, MailboxesSupport.folderBadgeCount("inbox", unreadCount = 2, totalCount = 900))
        assertEquals(0, MailboxesSupport.folderBadgeCount(null, unreadCount = 0, totalCount = 40))
    }

    @Test
    fun theDeadOutboxBannerNamesWhatFailedAndWhere() {
        assertNull(MailboxesSupport.deadOutboxBannerText(0, listOf("Work")))
        assertEquals(
            "1 message could not be sent — check SMTP settings on Work.",
            MailboxesSupport.deadOutboxBannerText(1, listOf("Work")),
        )
        assertEquals(
            "3 messages could not be sent — check SMTP settings on Work, Home.",
            MailboxesSupport.deadOutboxBannerText(3, listOf("Work", "Home")),
        )
        assertEquals(
            "2 messages could not be sent — check SMTP settings on this account.",
            MailboxesSupport.deadOutboxBannerText(2, emptyList()),
        )
    }

    @Test
    fun accountNamesAreDistinctInFirstSeenOrderAndAnUnknownIdIsSkipped() {
        val names = mapOf("a" to "Work", "b" to "Home")

        assertEquals(
            listOf("Home", "Work"),
            MailboxesSupport.distinctAccountNames(listOf("b", "x", "a", "b"), names::get),
        )
    }

    @Test
    fun aConnectionStateIsRetryingOnlyForAnAccountThatSyncedBefore() {
        assertEquals(AccountConnectionState.Ok, AccountConnectionState.classify("active", hasFullSync = false))
        assertEquals(AccountConnectionState.Retrying, AccountConnectionState.classify("error", hasFullSync = true))
        assertEquals(
            AccountConnectionState.NeverConnected,
            AccountConnectionState.classify("error", hasFullSync = false),
        )
    }

    private fun plan(vararg events: LiveInvalidation) =
        MailboxesSupport.reloadPlan(events.toList()) { folder -> if (folder == "f-known") "acc-1" else null }

    @Test
    fun aNewMessageRefreshesOnlyItsAccountsCounts() {
        assertEquals(
            MailboxesReloadPlan(counts = true, countAccountIds = setOf("acc-2")),
            plan(LiveInvalidation.MailNew("acc-2", "f-1", "m-1")),
        )
    }

    @Test
    fun aFolderOnlyEventFindsItsAccountThroughTheFolder() {
        assertEquals(
            MailboxesReloadPlan(counts = true, countAccountIds = setOf("acc-1")),
            plan(LiveInvalidation.FolderSynced("f-known")),
        )
    }

    @Test
    fun aFolderNobodyKnowsWidensTheRefreshToEveryAccount() {
        assertEquals(
            MailboxesReloadPlan(counts = true, countsForAllAccounts = true),
            plan(LiveInvalidation.FolderSynced("f-unknown")),
        )
    }

    @Test
    fun aFlagChangeMovesNoCountButAReadChangeDoes() {
        assertTrue(plan(LiveInvalidation.MailUpdated("a", "f", null, "m", listOf("is_flagged"))).isEmpty)
        assertEquals(
            MailboxesReloadPlan(counts = true, countAccountIds = setOf("a")),
            plan(LiveInvalidation.MailUpdated("a", "f", null, "m", listOf("is_seen"))),
        )
    }

    @Test
    fun resyncAndAccountOrFolderChangesReloadEverything() {
        assertTrue(plan(LiveInvalidation.Resync).everything)
        assertTrue(plan(LiveInvalidation.AccountsChanged).everything)
        assertTrue(plan(LiveInvalidation.FoldersChanged).everything)
    }

    @Test
    fun anOutboxEventRefreshesOnlyTheBanner() {
        assertEquals(
            MailboxesReloadPlan(deadOutbox = true),
            plan(
                LiveInvalidation.OutboxUpdated(
                    com.frederikberg.mailverdict.kit.live
                        .OutboxEventPayload("o"),
                ),
            ),
        )
    }

    @Test
    fun eventsThisScreenDoesNotShowChangeNothing() {
        assertTrue(plan(LiveInvalidation.AlertsChanged, LiveInvalidation.SettingsChanged("x")).isEmpty)
    }

    @Test
    fun plansFromSuccessiveBatchesMergeIntoOne() {
        val merged =
            plan(LiveInvalidation.MailNew("a", "f", "m"))
                .merged(plan(LiveInvalidation.MailNew("b", "f", "m")))
                .merged(
                    plan(
                        LiveInvalidation.OutboxUpdated(
                            com.frederikberg.mailverdict.kit.live
                                .OutboxEventPayload("o"),
                        ),
                    ),
                )

        assertEquals(
            MailboxesReloadPlan(counts = true, countAccountIds = setOf("a", "b"), deadOutbox = true),
            merged,
        )
    }
}
