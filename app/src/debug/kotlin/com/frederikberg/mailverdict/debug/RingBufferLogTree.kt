package com.frederikberg.mailverdict.debug

import android.util.Log
import timber.log.Timber

/** Keeps the last [capacity] log lines in memory so the debug server can serve them. */
class RingBufferLogTree(
    private val capacity: Int = 500,
) : Timber.Tree() {
    private val lines = ArrayDeque<Entry>()

    class Entry(
        val priority: Int,
        val tag: String?,
        val message: String,
    )

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        synchronized(lines) {
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(Entry(priority, tag, if (t == null) message else "$message\n${Log.getStackTraceString(t)}"))
        }
    }

    /** Entries at or above [minPriority] (an `android.util.Log` constant), oldest first. */
    fun entries(minPriority: Int): List<Entry> = synchronized(lines) { lines.filter { it.priority >= minPriority } }
}
