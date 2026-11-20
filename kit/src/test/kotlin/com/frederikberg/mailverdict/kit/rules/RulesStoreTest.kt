package com.frederikberg.mailverdict.kit.rules

import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesStoreTest {
    private val server = TestServer()

    @After
    fun stop() = server.close()

    private val store =
        RulesStore(ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, OkHttpClient()))

    private fun stage(
        id: String,
        type: String = "match",
        whenJson: String = """{"subject_contains":"$id"}""",
        effects: String = """[{"trash":{}}]""",
        enabled: Boolean = true,
        accounts: String = "null",
    ) = """{"stage_id":"$id","type":"$type","name":"Rule $id","config":{"when":$whenJson,"effects":$effects},
        |"enabled":$enabled,"halt":false,"accounts":$accounts}
        """.trimMargin()

    private fun document(
        revision: Int,
        vararg stages: String,
        enabled: Boolean = true,
        warnings: String = "[]",
    ) = """{"revision":$revision,"enabled":$enabled,"stages":[${stages.joinToString(",")}],"warnings":$warnings}"""

    private fun serve(doc: String) {
        server.script("/api/pipeline", TestServer.json(doc))
        server.script("/api/pipeline/health", TestServer.json("[]"))
    }

    private fun bodyOf(
        path: String,
        method: String,
    ) = Json
        .parseToJsonElement(
            server
                .requestsTo(path)
                .last { it.method == method }
                .body!!
                .utf8(),
        ).jsonObject

    @Test
    fun loadReadsTheDocumentItsHealthAndTheFolderNames() =
        runBlocking {
            serve(document(4, stage("a"), stage("b")))
            server.script(
                "/api/accounts/acc/folders",
                TestServer.json(
                    """[{"id":"f1","account_id":"acc","imap_name":"INBOX","special_use":"inbox"}]""",
                ),
            )
            store.load(listOf("acc"))

            val state = store.state.value
            assertEquals(listOf("a", "b"), state.order)
            assertEquals(mapOf("f1" to "Inbox"), state.folderNames)
            assertTrue(state.hasLoaded)
        }

    @Test
    fun aFolderListThatCannotBeReadOnlyLeavesTheSentencesWithoutNames() =
        runBlocking {
            serve(document(1, stage("a")))
            store.load(listOf("acc"))

            assertTrue(store.state.value.hasLoaded)
            assertTrue(
                store.state.value.folderNames
                    .isEmpty(),
            )
        }

    @Test
    fun switchingARuleOffPatchesOnlyItsFlagAndShowsTheServersDocument() =
        runBlocking {
            serve(document(4, stage("a"), stage("b")))
            store.load(emptyList())
            server.script(
                "/api/pipeline/stages/a",
                TestServer.json(document(5, stage("a", enabled = false), stage("b"))),
            )
            val outcome = store.setEnabled("a", false)

            assertEquals(WriteOutcome.Done, outcome)
            assertEquals(mapOf("enabled" to JsonPrimitive(false)), bodyOf("/api/pipeline/stages/a", "PATCH"))
            assertFalse(
                store.state.value.document!!
                    .stages
                    .first()
                    .enabled,
            )
            assertEquals(
                5,
                store.state.value.document!!
                    .revision,
            )
        }

    @Test
    fun movingARuleSendsTheWholeNewOrderWithTheRevisionItWasReadAt() =
        runBlocking {
            serve(document(4, stage("a"), stage("b"), stage("c")))
            store.load(emptyList())
            server.script(
                "/api/pipeline/stages/reorder",
                TestServer.json(document(5, stage("b"), stage("a"), stage("c"))),
            )
            store.move("b", -1, listOf("a", "b", "c"))

            val body = bodyOf("/api/pipeline/stages/reorder", "POST")
            assertEquals(listOf("b", "a", "c"), body.getValue("order").jsonArray.map { (it as JsonPrimitive).content })
            assertEquals(JsonPrimitive(4), body["base_revision"])
            assertEquals(listOf("b", "a", "c"), store.state.value.order)
        }

    @Test
    fun aReorderTheServerRefusesPutsTheServersOrderBack() =
        runBlocking {
            serve(document(4, stage("a"), stage("b")))
            store.load(emptyList())
            server.script("/api/pipeline/stages/reorder", TestServer.jsonError(400, "unknown stage"))
            val outcome = store.move("b", -1, listOf("a", "b"))

            assertEquals(WriteOutcome.Failed("unknown stage", 400), outcome)
            assertEquals(listOf("a", "b"), store.state.value.order)
        }

    @Test
    fun aStaleRevisionTellsThePersonAndReadsTheListAgain() =
        runBlocking {
            serve(document(4, stage("a"), stage("b")))
            store.load(emptyList())
            server.script("/api/pipeline/stages/reorder", TestServer.jsonError(409, "base_revision 4 is stale"))
            serve(document(6, stage("b"), stage("a"), stage("c")))
            val outcome = store.move("b", -1, listOf("a", "b"))

            assertTrue((outcome as WriteOutcome.Failed).isConflict)
            assertEquals("Your rules changed meanwhile — reloaded.", store.state.value.notice)
            assertEquals(
                6,
                store.state.value.document!!
                    .revision,
            )
            assertEquals(listOf("b", "a", "c"), store.state.value.order)
        }

    @Test
    fun deletingNamesTheRevisionInTheQuery() =
        runBlocking {
            serve(document(4, stage("a"), stage("b")))
            store.load(emptyList())
            server.script("/api/pipeline/stages/a", TestServer.json(document(5, stage("b"))))
            store.delete("a")

            val delete = server.requestsTo("/api/pipeline/stages/a").single { it.method == "DELETE" }
            assertEquals("4", delete.url.queryParameter("base_revision"))
            assertEquals(listOf("b"), store.state.value.order)
        }

    @Test
    fun pausingSendsEveryStageBackBecauseTheRouteReadsAMissingListAsEmpty() =
        runBlocking {
            serve(document(4, stage("a", accounts = """["acc"]"""), stage("b", enabled = false)))
            store.load(emptyList())
            server.script("/api/pipeline", TestServer.json(document(5, stage("a"), stage("b"), enabled = false)))
            store.setPaused(true)

            val body = bodyOf("/api/pipeline", "PUT")
            assertEquals(JsonPrimitive(false), body["enabled"])
            assertEquals(JsonPrimitive(4), body["base_revision"])
            val stages = body.getValue("stages") as JsonArray
            assertEquals(
                listOf("a", "b"),
                stages.map {
                    it.jsonObject.getValue("stage_id").let { s ->
                        (s as JsonPrimitive).content
                    }
                },
            )
            assertEquals("""["acc"]""", stages[0].jsonObject.getValue("accounts").toString())
            assertEquals(JsonPrimitive(false), stages[1].jsonObject["enabled"])
            assertTrue(stages[0].jsonObject.containsKey("config"))
            assertFalse(
                store.state.value.document!!
                    .enabled,
            )
        }

    @Test
    fun historyListsRevisionsAndRestoringOneShowsItsDocument() =
        runBlocking {
            serve(document(4, stage("a")))
            store.load(emptyList())
            server.script(
                "/api/pipeline/revisions",
                TestServer.json(
                    """[{"revision":4,"note":"replaced via API","created_at":"2026-11-19T08:00:00+00:00"},{"revision":3,"note":null,"created_at":"2026-11-18T08:00:00+00:00"}]""",
                ),
            )
            store.loadRevisions()
            assertEquals(
                listOf(4, 3),
                store.state.value.revisions!!
                    .map { it.revision },
            )

            server.script("/api/pipeline/revisions/3/restore", TestServer.json(document(5, stage("old"))))
            assertEquals(WriteOutcome.Done, store.restore(3))
            assertEquals(listOf("old"), store.state.value.order)
            assertEquals("POST", server.requestsTo("/api/pipeline/revisions/3/restore").single().method)
        }

    private val unresolved =
        """[{"stage_id":"a","account_id":"acc","reference":"Receipts","ok":false,""" +
            """"detail":"no such folder"}]"""

    private fun account(
        id: String,
        name: String,
        emoji: String? = null,
    ) = AccountResponse(
        id = id,
        name = name,
        imapHost = "h",
        imapPort = 993,
        imapUser = "u",
        emoji = emoji,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun theViewGroupsRulesNamesTheirScopeAndAttachesWarnings() =
        runBlocking {
            serve(
                document(
                    4,
                    stage("spam", type = "classify"),
                    stage("a", accounts = """["acc"]"""),
                    stage("b", effects = """[{"webhook":{"name":"hook"}}]"""),
                    stage("c", accounts = """["acc","acc2","acc3"]"""),
                    warnings = unresolved,
                ),
            )
            server.script(
                "/api/pipeline/health",
                TestServer.json(
                    """[{"stage_id":"a","account_id":"acc","reference":"Receipts","ok":false,"detail":"no such folder"}]""",
                ),
            )
            store.load(emptyList())

            val view = RulesView.build(store.state.value, listOf(account("acc", "Private", "🏠")))!!

            assertEquals(listOf(RuleGroup.Spam, RuleGroup.Filing, RuleGroup.Forwarding), view.groups.map { it.group })
            val filing = view.groups[1].rows
            assertEquals("🏠 Private", filing[0].scope)
            assertEquals("3 accounts", filing[1].scope)
            assertEquals("Private: “Receipts” not found — no such folder", filing[0].warnings.single().text)
            assertFalse(filing[0].canMoveUp)
            assertTrue(filing[0].canMoveDown)
            assertFalse(
                "the spam step cannot be deleted",
                view.groups[0]
                    .rows
                    .single()
                    .canDelete,
            )
            assertTrue(filing[0].canDelete)
            assertEquals(listOf("a", "c"), filing[0].groupMembers)
        }

    @Test
    fun aFilterNarrowsTheListAndTurnsOffMovingBecauseNeighboursAreHidden() =
        runBlocking {
            serve(document(4, stage("alpha"), stage("beta")))
            store.load(emptyList())

            val view = RulesView.build(store.state.value, emptyList(), filter = "ALP")!!

            val rows = view.groups.single().rows
            assertEquals(listOf("alpha"), rows.map { it.id })
            assertEquals(2, view.groups.single().total)
            assertFalse(rows.single().canMoveDown)
            assertNotNull(view)
            assertTrue(RulesView.build(store.state.value, emptyList(), filter = "zzz")!!.hasMatches.not())
        }

    @Test
    fun theViewIsPausedWhenTheDocumentIs() =
        runBlocking {
            serve(document(4, stage("a"), enabled = false))
            store.load(emptyList())

            assertTrue(RulesView.build(store.state.value, emptyList())!!.paused)
        }
}
