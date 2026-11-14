package com.frederikberg.mailverdict.kit.maillist

/** What changed between two versions of a list's rows, as far as the reader's place in them is concerned. */
object RowChanges {
    /**
     * How many rows new to the list now sit above [anchorId] (the row at the top of the viewport
     * before the change): the "N new messages" count once they have been compensated in above the
     * viewport. Zero when the anchor itself left, which a restore rather than a correction handles.
     */
    fun newRowsAbove(
        oldIds: List<String>,
        newIds: List<String>,
        anchorId: String?,
    ): Int {
        if (anchorId == null) return 0
        val anchorIndex = newIds.indexOf(anchorId)
        if (anchorIndex <= 0) return 0
        val old = oldIds.toHashSet()
        return newIds.subList(0, anchorIndex).count { it !in old }
    }
}
