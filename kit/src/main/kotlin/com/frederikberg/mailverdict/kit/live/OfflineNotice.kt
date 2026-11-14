package com.frederikberg.mailverdict.kit.live

/** A short line saying a screen is not live, for the capsule it shows while offline. */
data class OfflineNotice(
    val text: String,
) {
    companion object {
        /**
         * [savedAtMs] is when the content on screen was saved, when it came from the offline cache
         * rather than the server. Null when there is nothing worth saying: online and live.
         */
        fun make(
            reachability: Reachability,
            savedAtMs: Long? = null,
            nowMs: Long = System.currentTimeMillis(),
        ): OfflineNotice? {
            val saved = savedAtMs?.let { "Saved ${relativeAgo(nowMs - it)}" }
            return when (reachability) {
                Reachability.Offline -> OfflineNotice(listOfNotNull("Offline", saved).joinToString(" · "))
                Reachability.ServerUnreachable ->
                    OfflineNotice(listOfNotNull("Server unreachable", saved).joinToString(" · "))
                Reachability.Online -> saved?.let { OfflineNotice("Showing a saved copy · $it") }
            }
        }

        internal fun relativeAgo(elapsedMs: Long): String {
            val minutes = (elapsedMs / 60_000).coerceAtLeast(0)
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 24 * 60 -> "${minutes / 60} h ago"
                else -> "${minutes / (24 * 60)} d ago"
            }
        }
    }
}
