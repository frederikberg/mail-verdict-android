package com.frederikberg.mailverdict.kit.compose

enum class RecipientField(
    val label: String,
) {
    To("To"),
    Cc("Cc"),
    Bcc("Bcc"),
}

object ComposeRecipients {
    data class Commit(
        val recipients: List<String>,
        val invalid: List<String>,
    )

    /**
     * Turns typed or pasted text into recipients. Valid entries join [existing], skipping any already
     * there; invalid ones come back so the field keeps them in view, never silently accepted or dropped.
     */
    fun commit(
        raw: String,
        existing: List<String>,
    ): Commit {
        val recipients = existing.toMutableList()
        val present = existing.mapTo(HashSet()) { it.lowercase() }
        val invalid = mutableListOf<String>()
        for (entry in ComposeAddresses.parseList(raw)) {
            val candidate = ComposeAddresses.normalize(entry)
            if (!ComposeAddresses.isValid(candidate)) {
                invalid += entry
            } else if (present.add(candidate.lowercase())) {
                recipients += candidate
            }
        }
        return Commit(recipients, invalid)
    }

    fun invalidNote(invalid: List<String>): String? =
        if (invalid.isEmpty()) null else "Not a valid email address: ${invalid.joinToString(", ")}"
}
