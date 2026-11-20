package com.frederikberg.mailverdict.rules

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.frederikberg.mailverdict.accounts.AccountsTestData
import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.contract.PipelineDocumentOut
import com.frederikberg.mailverdict.contract.PipelineRevisionSummary
import com.frederikberg.mailverdict.kit.rules.RulesState
import com.frederikberg.mailverdict.kit.rules.RulesView
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class RulesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private val folders = mapOf("f-news" to "Newsletter", "f-receipts" to "Receipts")

    private fun document(enabled: Boolean = true): PipelineDocumentOut =
        ContractJson.decodeFromString(
            PipelineDocumentOut.serializer(),
            """{"revision":7,"enabled":$enabled,"stages":[
            |{"stage_id":"spam","type":"classify","name":"Spam detection","config":{},"enabled":true,"halt":false},
            |{"stage_id":"receipts","type":"match","name":"File receipts","enabled":true,"halt":false,
            | "accounts":["a1"],"config":{"when":{"any":[{"subject_contains":"receipt"},{"subject_contains":"invoice"}]},
            | "effects":[{"move":{"folder_id":"f-receipts"}},{"set_flags":{"seen":true}}]}},
            |{"stage_id":"news","type":"match","name":"Newsletters","enabled":true,"halt":true,
            | "config":{"when":{"header_exists":"List-Id"},"effects":[{"move":{"folder_id":"f-news"}}]}},
            |{"stage_id":"promos","type":"match","name":"Old promotions","enabled":false,"halt":false,
            | "config":{"when":{"sender_domain":"promo.example"},"effects":[{"expunge":{}}]}},
            |{"stage_id":"orders","type":"orders","name":"Orders and tickets","config":{},"enabled":true,"halt":false}],
            |"warnings":[{"stage_id":"news","account_id":"a1","reference":"Newsletter","ok":false,"detail":"no such folder"}]}
            """.trimMargin(),
        )

    private val accounts =
        listOf(
            AccountsTestData.row(AccountsTestData.account("a1", "Private", "🏠", spam = true)),
            AccountsTestData.row(AccountsTestData.account("a2", "Work", "💼", orders = true)),
        )

    private fun show(
        doc: PipelineDocumentOut? = document(),
        revisions: List<PipelineRevisionSummary>? = null,
        filter: String = "",
    ) {
        val state =
            RulesState(
                document = doc,
                order =
                    doc
                        ?.stages
                        ?.map {
                            it.stageId
                        }.orEmpty(),
                folderNames = folders,
                hasLoaded = true,
                revisions = revisions,
            )
        val view = RulesView.build(state, accounts.map { it.account }, filter)
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                RulesScreen(
                    state = state,
                    view = view,
                    filter = filter,
                    onFilter = { events += "filter:$it" },
                    accounts = accounts,
                    snackbar = SnackbarHostState(),
                    callbacks =
                        RulesCallbacks(
                            onBack = { events += "back" },
                            onRetry = { events += "retry" },
                            onToggle = { row, on -> events += "toggle:${row.id}:$on" },
                            onMove = { row, d -> events += "move:${row.id}:$d" },
                            onDelete = { events += "delete:${it.id}" },
                            onPause = { events += "pause" },
                            onResume = { events += "resume" },
                            onOpenHistory = { events += "history" },
                            onRestore = { events += "restore:$it" },
                            onSetSpam = { id, on -> events += "spam:$id:$on" },
                            onSetOrders = { id, on -> events += "orders:$id:$on" },
                        ),
                )
            }
        }
    }

    /** The list composes only what is on screen, so a row is scrolled to before it is looked at. */
    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(RulesTags.LIST).performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun rulesAreGroupedAndReadAsSentences() {
        show()

        compose.onNodeWithText("Spam · 1").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/rules.png")
        scrollTo(RulesTags.group("Filing"))
        compose.onNodeWithText("Filing · 3").assertIsDisplayed()
        scrollTo(RulesTags.rule("receipts"))
        compose.onNodeWithTag(RulesTags.rule("receipts")).assertExists()
        compose
            .onNodeWithText(
                "If any of: subject contains receipt, subject contains invoice → move to Receipts, mark read",
            ).assertExists()
        scrollTo(RulesTags.rule("promos"))
        compose.onNodeWithText("Newsletter” not found", substring = true).assertExists()
        compose.onNodeWithText("permanent").assertExists()
    }

    @Test
    fun theSwitchReportsTheRuleAndTheNewState() {
        show()

        scrollTo(RulesTags.toggle("receipts"))
        compose.onNodeWithTag(RulesTags.toggle("receipts")).performClick()
        scrollTo(RulesTags.toggle("promos"))
        compose.onNodeWithTag(RulesTags.toggle("promos")).performClick()

        assertEquals(listOf("toggle:receipts:false", "toggle:promos:true"), events)
    }

    @Test
    fun movingStaysInsideTheGroupAndTheEndsAreDisabled() {
        show()

        scrollTo(RulesTags.actions("receipts"))
        compose.onNodeWithTag(RulesTags.actions("receipts")).performClick()
        compose.onNodeWithTag(RulesTags.moveUp("receipts")).assertIsNotEnabled()
        compose.onNodeWithTag(RulesTags.moveDown("receipts")).performClick()

        assertEquals(listOf("move:receipts:1"), events)
    }

    @Test
    fun theSpamStepCannotBeDeleted() {
        show()

        scrollTo(RulesTags.actions("spam"))
        compose.onNodeWithTag(RulesTags.actions("spam")).performClick()
        compose.onNodeWithTag(RulesTags.moveUp("spam")).assertIsDisplayed()
        compose.onNodeWithTag(RulesTags.delete("spam")).assertDoesNotExist()
    }

    @Test
    fun deletingAsksFirstAndNamesTheRule() {
        show()

        scrollTo(RulesTags.actions("promos"))
        compose.onNodeWithTag(RulesTags.actions("promos")).performClick()
        compose.onNodeWithTag(RulesTags.delete("promos")).performClick()
        compose.onNodeWithText("Delete “Old promotions”?").assertIsDisplayed()
        assertEquals(emptyList<String>(), events)
        compose.onNodeWithTag(RulesTags.CONFIRM_DELETE).performClick()

        assertEquals(listOf("delete:promos"), events)
    }

    @Test
    fun pausingAsksFirst() {
        show()

        compose.onNodeWithTag(RulesTags.MENU).performClick()
        compose.onNodeWithTag(RulesTags.MENU_PAUSE).performClick()
        assertEquals(emptyList<String>(), events)
        compose.onNodeWithTag(RulesTags.CONFIRM_PAUSE).performClick()

        assertEquals(listOf("pause"), events)
    }

    @Test
    fun pausedRulesShowABannerWithAWayBack() {
        show(document(enabled = false))

        compose.onNodeWithTag(RulesTags.PAUSED_BANNER).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/rules-paused.png")
        compose.onNodeWithTag(RulesTags.RESUME).performClick()

        assertEquals(listOf("resume"), events)
    }

    @Test
    fun thePerAccountSwitchesReportTheAccount() {
        show()

        compose.onNodeWithTag(RulesTags.spam("a2")).performClick()
        compose.onNodeWithTag(RulesTags.orders("a1")).performClick()

        assertEquals(listOf("spam:a2:true", "orders:a1:true"), events)
    }

    @Test
    fun theFilterIsReportedAndNarrowsTheList() {
        show(filter = "promo")

        scrollTo(RulesTags.rule("promos"))
        compose.onNodeWithTag(RulesTags.rule("promos")).assertIsDisplayed()
        compose.onNodeWithTag(RulesTags.rule("receipts")).assertDoesNotExist()
        compose.onNodeWithTag(RulesTags.FILTER).performTextInput("x")
        assertEquals(1, events.size)
        assertEquals(true, events.single().startsWith("filter:") && events.single().contains("promo"))
    }

    @Test
    fun aFilterThatMatchesNothingSaysSo() {
        show(filter = "zzz")

        compose.onNodeWithTag(RulesTags.NO_MATCH).assertIsDisplayed()
    }

    @Test
    fun historyListsVersionsAndRestoringAsksFirst() {
        show(
            revisions =
                listOf(
                    PipelineRevisionSummary(7, "replaced via API", "2026-11-19T08:00:00+00:00"),
                    PipelineRevisionSummary(6, null, "2026-11-12T08:00:00+00:00"),
                ),
        )

        compose.onNodeWithTag(RulesTags.MENU).performClick()
        compose.onNodeWithTag(RulesTags.MENU_HISTORY).performClick()
        compose.onNodeWithText("Version 7").assertIsDisplayed()
        compose.onNodeWithText("current").assertIsDisplayed()
        compose.onNodeWithTag(RulesTags.restore(7)).assertDoesNotExist()
        compose.onNodeWithTag(RulesTags.restore(6)).performClick()
        compose.onNodeWithText("Restore version 6?").assertIsDisplayed()
        compose.onNodeWithTag(RulesTags.CONFIRM_RESTORE).performClick()

        assertEquals(listOf("history", "restore:6"), events)
    }

    @Test
    fun anEmptyListSaysSo() {
        show(
            ContractJson.decodeFromString(
                PipelineDocumentOut.serializer(),
                """{"revision":1,"enabled":true,"stages":[],"warnings":[]}""",
            ),
        )

        compose.onNodeWithTag(RulesTags.EMPTY).assertIsDisplayed()
    }
}
