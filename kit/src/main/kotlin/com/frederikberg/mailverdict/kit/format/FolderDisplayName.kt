package com.frederikberg.mailverdict.kit.format

/**
 * The name a folder renders with: an explicit display name first, then a role label for a recognised
 * special-use folder, so a server's raw "INBOX" or "Gelöschte Elemente" never leaks into the
 * interface, then the raw server name.
 */
fun folderDisplayName(
    imapName: String,
    displayName: String?,
    specialUse: String?,
): String {
    if (!displayName.isNullOrEmpty()) return displayName
    return when (specialUse) {
        "inbox" -> "Inbox"
        "drafts" -> "Drafts"
        "sent" -> "Sent"
        "archive" -> "Archive"
        "junk" -> "Junk"
        "trash" -> "Trash"
        else -> imapName
    }
}
