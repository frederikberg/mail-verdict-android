package com.frederikberg.mailverdict.kit.rules

import com.frederikberg.mailverdict.contract.StageOut
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSummaryTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun match(
        whenJson: String,
        effectsJson: String,
        halt: Boolean = false,
    ) = StageOut(
        stageId = "r",
        type = "match",
        name = "r",
        config =
            buildJsonObject {
                put("when", json(whenJson))
                put("effects", json(effectsJson))
            },
        enabled = true,
        halt = halt,
    )

    private fun text(
        stage: StageOut,
        folders: Map<String, String> = emptyMap(),
    ) = RuleSummaries.describe(stage, folders::get).plain

    @Test
    fun everyConditionKeyReadsAsAPhrase() {
        val cases =
            listOf(
                """{"sender_match":"a@b.c"}""" to "sender is a@b.c",
                """{"sender_match":"b.c"}""" to "sender domain b.c",
                """{"sender_domain":"b.c"}""" to "sender domain b.c",
                """{"subject_contains":"sale"}""" to "subject contains sale",
                """{"subject_matches":"^x"}""" to "subject matches /^x/",
                """{"body_contains":"hi"}""" to "body contains hi",
                """{"body_matches":"y${'$'}"}""" to "body matches /y${'$'}/",
                """{"header_match":{"field":"From","pattern":"bob"}}""" to "header From matches /bob/",
                """{"header_exists":"List-Id"}""" to "header List-Id exists",
                """{"size_gt":${2 * 1024 * 1024}}""" to "larger than 2 MB",
                """{"size_lt":${300 * 1024}}""" to "smaller than 300 KB",
                """{"has_attachment":true}""" to "has an attachment",
                """{"has_attachment":"pdf"}""" to "has a pdf attachment",
                """{"folder_is":"INBOX"}""" to "in INBOX",
                """{"tag_is":"work"}""" to "tagged work",
                """{"verdict_is":"spam"}""" to "classified as spam",
                """{"verdict_is":"not-spam"}""" to "classified as not spam",
            )
        for ((condition, expected) in cases) {
            assertEquals(condition, "$expected → move to trash", text(match(condition, """[{"trash":{}}]""")))
        }
    }

    @Test
    fun aSizeKeepsOneDecimalWhenItIsNotWhole() {
        assertEquals(
            "larger than 1.5 MB → do nothing",
            text(match("""{"size_gt":${3 * 512 * 1024}}""", "[]")),
        )
        assertEquals("smaller than 12 B → do nothing", text(match("""{"size_lt":12}""", "[]")))
    }

    @Test
    fun compositesJoinCollapseAndNegate() {
        val a = """{"subject_contains":"a"}"""
        val b = """{"subject_contains":"b"}"""
        val c = """{"subject_contains":"c"}"""
        assertEquals(
            "subject contains a and subject contains b → do nothing",
            text(match("""{"all":[$a,$b]}""", "[]")),
        )
        assertEquals(
            "any of: subject contains a, subject contains b (+1) → do nothing",
            text(match("""{"any":[$a,$b,$c]}""", "[]")),
        )
        assertEquals("not subject contains a → do nothing", text(match("""{"not":$a}""", "[]")))
        assertEquals("every mail → do nothing", text(match("{}", "[]")))
    }

    @Test
    fun aLongConditionCollapsesToACount() {
        val long =
            (0 until 6).joinToString(
                ",",
            ) { """{"subject_contains":"a-considerably-longer-phrase-number-$it"}""" }

        assertEquals("6 conditions → do nothing", text(match("""{"all":[$long]}""", "[]")))
        assertEquals("any of 6 conditions → do nothing", text(match("""{"any":[$long]}""", "[]")))
    }

    @Test
    fun effectsResolveFoldersAndReadAsVerbs() {
        val folders = mapOf("id-1" to "Newsletter")
        assertEquals(
            "every mail → move to Newsletter, mark read, star",
            text(
                match("{}", """[{"move":{"folder_id":"id-1"}},{"set_flags":{"seen":true,"flagged":true}}]"""),
                folders,
            ),
        )
        assertEquals("every mail → move to Receipts", text(match("{}", """[{"move":{"folder_name":"Receipts"}}]""")))
        assertEquals("every mail → move to Junk", text(match("{}", """[{"move":{"special_use":"junk"}}]""")))
        assertEquals("every mail → move to a folder", text(match("{}", """[{"move":{}}]""")))
        assertEquals(
            "every mail → add keyword k, add tag t, remove tag old, notify, send to canteen, record verdict, queue for orders",
            text(
                match(
                    "{}",
                    """[{"keywords":{"add":["k"]}},{"tag":{"add":["t"],"remove":["old"]}},{"notify":{"text":"x"}},
                    {"webhook":{"name":"canteen"}},{"record_verdict":{"is_spam":true}},{"enqueue_order":{}}]""",
                ),
            ),
        )
    }

    @Test
    fun aRuleThatDeletesForGoodSaysSoAndIsFlaggedPermanent() {
        val rule = match("""{"sender_domain":"spam.example"}""", """[{"expunge":{}}]""")

        assertTrue(RuleSummaries.describe(rule).permanent)
        assertTrue(text(rule).contains("delete permanently"))
        assertFalse(RuleSummaries.describe(match("{}", """[{"trash":{}}]""")).permanent)
    }

    @Test
    fun anUnknownKeyIsShownByNameNotDropped() {
        assertEquals("moon_phase → teleport", text(match("""{"moon_phase":"full"}""", """[{"teleport":{}}]""")))
    }

    @Test
    fun builtInStepsCarryTheirOwnSentences() {
        val classify = match("{}", "[]").copy(type = "classify", config = JsonObject(emptyMap()))

        assertEquals(RuleSummaries.CLASSIFY_SENTENCE, RuleSummaries.describe(classify).plain)
        assertTrue(RuleSummaries.describe(classify.copy(halt = true)).stop)
        assertEquals(RuleSummaries.ORDERS_SENTENCE, RuleSummaries.describe(classify.copy(type = "orders")).plain)
        assertEquals("A “future” step", RuleSummaries.describe(classify.copy(type = "future")).plain)
    }

    @Test
    fun groupsFollowWhatARuleDoes() {
        fun group(stage: StageOut) = RuleSummaries.group(stage)
        val base = match("{}", "[]")
        assertEquals(RuleGroup.Spam, group(base.copy(type = "classify")))
        assertEquals(RuleGroup.Orders, group(base.copy(type = "orders")))
        assertEquals(
            RuleGroup.Spam,
            group(match("""{"all":[{"verdict_is":"spam"},{"has_attachment":true}]}""", "[]")),
        )
        assertEquals(RuleGroup.Spam, group(match("""{"not":{"verdict_is":"spam"}}""", "[]")))
        assertEquals(
            RuleGroup.Forwarding,
            group(match("""{"subject_contains":"x"}""", """[{"webhook":{"name":"w"}}]""")),
        )
        assertEquals(RuleGroup.Filing, group(match("""{"subject_contains":"x"}""", """[{"trash":{}}]""")))
        assertEquals(RuleGroup.Other, group(base.copy(type = "future")))
    }

    @Test
    fun usesConditionWalksNestedTrees() {
        assertTrue(RuleSummaries.usesCondition(json("""{"all":[{"any":[{"not":{"tag_is":"a"}}]}]}"""), "tag_is"))
        assertFalse(RuleSummaries.usesCondition(json("""{"all":[{"tag_is":"a"}]}"""), "folder_is"))
    }

    @Test
    fun theBuiltInStepsAreTheOnesTheEditorDoesNotCover() {
        val base = match("{}", "[]")
        assertTrue(RuleSummaries.isFixed(base.copy(type = "classify")))
        assertTrue(RuleSummaries.isFixed(base.copy(type = "orders")))
        assertFalse(RuleSummaries.isFixed(base))
        assertEquals(0, (base.config["effects"] as JsonArray).size)
        assertTrue(base.config.jsonObject.isNotEmpty())
    }
}
