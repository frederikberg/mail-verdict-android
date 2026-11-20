package com.frederikberg.mailverdict.kit.accounts

/** How a provider derives the name IMAP signs in with from the person's address. */
enum class ImapUserRule {
    /** The whole address. */
    FullAddress,

    /** The part before the `@`. */
    BeforeAt,

    ;

    fun userFor(address: String): String =
        when (this) {
            FullAddress -> address
            BeforeAt -> address.substringBefore('@')
        }
}

/**
 * A provider the add-account form fills in: its servers, how the sign-in name follows from the
 * address, and where the provider's own pages explain the app password it requires. The same
 * app password signs in to IMAP and SMTP.
 */
data class AccountPreset(
    val id: String,
    val label: String,
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    val imapUserRule: ImapUserRule,
    /** What has to be switched on before the provider offers app passwords. */
    val prerequisite: String,
    val appPasswordLinkLabel: String,
    val appPasswordLink: String,
    /** One line each, shown under the preset. */
    val notes: List<String>,
)

object AccountPresets {
    val gmail =
        AccountPreset(
            id = "gmail",
            label = "Gmail",
            imapHost = "imap.gmail.com",
            imapPort = 993,
            smtpHost = "smtp.gmail.com",
            smtpPort = 465,
            imapUserRule = ImapUserRule.FullAddress,
            prerequisite = "Turn on 2-Step Verification for the Google Account first.",
            appPasswordLinkLabel = "How Google app passwords work",
            appPasswordLink = "https://support.google.com/accounts/answer/185833",
            notes =
                listOf(
                    "Sign in with a 16-digit app password, not the account password.",
                    "Google Workspace (work or school) accounts cannot be connected: Google allows only OAuth for " +
                        "them, which MailVerdict does not support.",
                    "Gmail's label folders hold copies of mail that is also in the inbox, so the AI skips them; " +
                        "All Mail is treated as the archive. Each folder's switch is under Folder Order & Visibility.",
                    "Google caps how much IMAP can download per day, so a large mailbox can take a few days to " +
                        "sync completely.",
                ),
        )

    val icloud =
        AccountPreset(
            id = "icloud",
            label = "iCloud",
            imapHost = "imap.mail.me.com",
            imapPort = 993,
            smtpHost = "smtp.mail.me.com",
            smtpPort = 587,
            imapUserRule = ImapUserRule.BeforeAt,
            prerequisite = "Two-factor authentication must be on for the Apple Account.",
            appPasswordLinkLabel = "How Apple app-specific passwords work",
            appPasswordLink = "https://support.apple.com/en-us/102654",
            notes =
                listOf(
                    "Sign in with an app-specific password, not the Apple Account password.",
                    "Signs in to IMAP with the part before the @ and to SMTP with the full address, as Apple " +
                        "documents. If the account stays in error, add it again under Other server with the full " +
                        "address as the IMAP user.",
                    "Changing the Apple Account password revokes every app-specific password; the account then " +
                        "needs a new one.",
                ),
        )

    val all: List<AccountPreset> = listOf(gmail, icloud)

    fun byId(id: String?): AccountPreset? = all.firstOrNull { it.id == id }
}
