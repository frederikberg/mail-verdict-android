package com.frederikberg.mailverdict.accounts

import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.contract.IdentityResponse
import com.frederikberg.mailverdict.contract.SyncStatusResponse
import com.frederikberg.mailverdict.kit.accounts.AccountRow

/** Invented accounts for the screens' tests. */
object AccountsTestData {
    fun account(
        id: String = "a1",
        name: String = "Private",
        emoji: String? = "🏠",
        state: String = "active",
        stateError: String? = null,
        spam: Boolean = false,
        orders: Boolean = false,
        trashDays: Int? = null,
        glacier: Boolean = false,
        glacierDays: Int? = null,
    ) = AccountResponse(
        id = id,
        name = name,
        imapHost = "imap.example.com",
        imapPort = 993,
        imapUser = "alex@example.com",
        smtpHost = "smtp.example.com",
        smtpPort = 587,
        smtpUser = "alex@example.com",
        state = state,
        stateError = stateError,
        emoji = emoji,
        spamEnabled = spam,
        ordersEnabled = orders,
        trashRetentionDays = trashDays,
        glacierEnabled = glacier,
        glacierAutoDays = glacierDays,
        createdAt = "2026-01-01T00:00:00+00:00",
        updatedAt = "2026-01-01T00:00:00+00:00",
    )

    fun row(
        account: AccountResponse = account(),
        lastSync: String? = "2026-11-13T07:00:00+00:00",
        tier: String? = "qresync",
    ) = AccountRow(
        account,
        SyncStatusResponse(
            accountId = account.id,
            state = account.state,
            syncTier = tier,
            lastIncrSync = lastSync,
            lastFullSync = lastSync,
        ),
    )

    fun identity(
        id: String,
        address: String,
        name: String?,
        default: Boolean,
    ) = IdentityResponse(id, "a1", address, name, default, "2026-01-01T00:00:00+00:00")
}
