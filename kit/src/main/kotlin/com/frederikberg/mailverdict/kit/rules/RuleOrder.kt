package com.frederikberg.mailverdict.kit.rules

/**
 * Reordering a rule list that is shown in groups. The server keeps one order for the whole document;
 * a group is a view of some of its slots, so a move inside a group permutes only that group's slots
 * and leaves every other rule exactly where it was.
 */
object RuleOrder {
    /** [order] with the slots held by [groupIds] refilled, in order, by [newGroupOrder]. */
    fun reorderWithinGroup(
        order: List<String>,
        groupIds: List<String>,
        newGroupOrder: List<String>,
    ): List<String> {
        val members = groupIds.toSet()
        val next = order.toMutableList()
        var placed = 0
        order.forEachIndexed { slot, id ->
            if (id in members) next[slot] = newGroupOrder[placed++]
        }
        return next
    }

    /** [order] after moving [id] one place up ([direction] -1) or down (1) among the group's members. */
    fun moveWithinGroup(
        order: List<String>,
        groupIds: List<String>,
        id: String,
        direction: Int,
    ): List<String> {
        val inOrder = order.filter { it in groupIds }
        val from = inOrder.indexOf(id)
        val to = from + direction
        if (from == -1 || to < 0 || to >= inOrder.size) return order
        val moved = inOrder.toMutableList()
        moved[from] = inOrder[to]
        moved[to] = inOrder[from]
        return reorderWithinGroup(order, inOrder, moved)
    }
}
