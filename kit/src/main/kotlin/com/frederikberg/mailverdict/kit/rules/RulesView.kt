package com.frederikberg.mailverdict.kit.rules

import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.contract.StageOut

/** A folder reference of a rule that no longer resolves in one account. */
data class RuleWarning(
    val account: String,
    val reference: String,
    val detail: String?,
) {
    val text: String get() = "$account: “$reference” not found" + (detail?.let { " — $it" } ?: "")
}

/** One rule as the list shows it. */
data class RuleRow(
    val stage: StageOut,
    val summary: RuleSummary,
    /** Where the rule applies: "All accounts", account names, or a count. */
    val scope: String,
    val warnings: List<RuleWarning>,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    /** Every rule can be deleted except the built-in spam step. */
    val canDelete: Boolean,
    /** The ids of the rules this one can be moved among, in order. */
    val groupMembers: List<String>,
) {
    val id: String get() = stage.stageId
}

data class RuleGroupView(
    val group: RuleGroup,
    val rows: List<RuleRow>,
    /** How many rules the group holds before any filter. */
    val total: Int,
)

/** The rules screen's list: every group that holds a rule, each rule as a sentence. */
data class RulesView(
    val paused: Boolean,
    val revision: Int,
    val groups: List<RuleGroupView>,
    val ruleCount: Int,
) {
    val hasMatches: Boolean get() = groups.any { it.rows.isNotEmpty() }

    companion object {
        /**
         * Arranges the server's document for display: in the shown order, grouped by what a rule does,
         * each with its sentence, scope and warnings, narrowed to [filter] (matched against the name and
         * the sentence). A filtered list cannot be reordered, as the row's neighbours are hidden.
         */
        fun build(
            state: RulesState,
            accounts: List<AccountResponse>,
            filter: String = "",
        ): RulesView? {
            val document = state.document ?: return null
            val byId = document.stages.associateBy { it.stageId }
            val accountById = accounts.associateBy { it.id }
            val folderName: FolderNameLookup = { state.folderNames[it] }
            val needle = filter.trim().lowercase()

            val warnings =
                (state.health ?: document.warnings).filter { !it.ok }.groupBy({ it.stageId }) {
                    RuleWarning(accountById[it.accountId]?.name ?: "An account", it.reference, it.detail)
                }

            val byGroup = state.order.mapNotNull(byId::get).groupBy(RuleSummaries::group)
            val groups =
                RuleGroup.entries.mapNotNull { group ->
                    val stages = byGroup[group].orEmpty()
                    if (stages.isEmpty()) return@mapNotNull null
                    val members = stages.filterNot(RuleSummaries::isFixed).map { it.stageId }
                    val rows =
                        stages
                            .map { stage ->
                                val index = members.indexOf(stage.stageId)
                                RuleRow(
                                    stage = stage,
                                    summary = RuleSummaries.describe(stage, folderName),
                                    scope = scopeLabel(stage, accountById),
                                    warnings = warnings[stage.stageId].orEmpty(),
                                    canMoveUp = needle.isEmpty() && index > 0,
                                    canMoveDown = needle.isEmpty() && index != -1 && index < members.size - 1,
                                    canDelete = stage.type != "classify",
                                    groupMembers = members,
                                )
                            }.filter {
                                needle.isEmpty() ||
                                    "${it.stage.name} ${it.summary.plain}".lowercase().contains(needle)
                            }
                    RuleGroupView(group, rows, stages.size)
                }
            return RulesView(!document.enabled, document.revision, groups, document.stages.size)
        }

        private fun scopeLabel(
            stage: StageOut,
            accounts: Map<String, AccountResponse>,
        ): String {
            val ids = stage.accounts ?: return "All accounts"
            if (ids.isEmpty()) return "No accounts"
            val names =
                ids.map { id ->
                    accounts[id]?.let { account -> (account.emoji?.let { "$it " }.orEmpty()) + account.name }
                        ?: "Unknown account"
                }
            return if (names.size <= 2) names.joinToString(", ") else "${names.size} accounts"
        }
    }
}
