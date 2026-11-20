package com.frederikberg.mailverdict.kit.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RuleOrderTest {
    private val order = listOf("classify", "a", "x", "b", "y", "c")
    private val group = listOf("a", "b", "c")

    @Test
    fun aMoveInsideAGroupLeavesOtherRulesInTheirSlots() {
        assertEquals(
            listOf("classify", "c", "x", "a", "y", "b"),
            RuleOrder.reorderWithinGroup(order, group, listOf("c", "a", "b")),
        )
    }

    @Test
    fun movingOnePlaceSwapsWithTheGroupsNeighbourNotTheListsOne() {
        assertEquals(listOf("classify", "b", "x", "a", "y", "c"), RuleOrder.moveWithinGroup(order, group, "b", -1))
        assertEquals(listOf("classify", "a", "x", "c", "y", "b"), RuleOrder.moveWithinGroup(order, group, "b", 1))
    }

    @Test
    fun movingPastEitherEndChangesNothing() {
        assertSame(order, RuleOrder.moveWithinGroup(order, group, "a", -1))
        assertSame(order, RuleOrder.moveWithinGroup(order, group, "c", 1))
        assertSame(order, RuleOrder.moveWithinGroup(order, group, "x", 1))
    }
}
