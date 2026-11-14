package com.frederikberg.mailverdict.kit.maillist

import org.junit.Assert.assertEquals
import org.junit.Test

class RowChangesTest {
    private val old = listOf("e", "d", "c", "b", "a")

    @Test
    fun rowsNewToTheListAboveTheAnchorAreCounted() {
        assertEquals(2, RowChanges.newRowsAbove(old, listOf("g", "f") + old, anchorId = "d"))
    }

    @Test
    fun aRowThatMerelyMovedIsNotNew() {
        assertEquals(0, RowChanges.newRowsAbove(old, listOf("d", "e", "c", "b", "a"), anchorId = "c"))
    }

    @Test
    fun rowsBelowTheAnchorAndAnAnchorThatLeftCountNothing() {
        assertEquals(0, RowChanges.newRowsAbove(old, old + "z", anchorId = "e"))
        assertEquals(0, RowChanges.newRowsAbove(old, listOf("g") + old.filter { it != "c" }, anchorId = "c"))
        assertEquals(0, RowChanges.newRowsAbove(old, old, anchorId = null))
    }
}
