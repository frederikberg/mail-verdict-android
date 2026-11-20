package com.frederikberg.mailverdict.kit.rules

import com.frederikberg.mailverdict.contract.StageOut
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** A piece of a sentence; [strong] marks what the rule is about. */
data class Part(
    val text: String,
    val strong: Boolean = false,
)

/** One rule as a sentence: what it matches, what it does, and the two facts worth a badge. */
data class RuleSummary(
    /** The condition, such as "sender is a@b.c and subject contains sale". */
    val whenParts: List<Part>,
    /** What happens, such as "move to Newsletter, mark read"; empty for the built-in steps. */
    val thenParts: List<Part>,
    /** A matching mail ends the list for it. */
    val stop: Boolean,
    /** The rule removes mail from the server for good. */
    val permanent: Boolean,
) {
    /** The rule as plain text, for filtering and accessible names. */
    val plain: String
        get() {
            val condition = whenParts.joinToString("") { it.text }
            val action = thenParts.joinToString("") { it.text }
            return if (action.isEmpty()) condition else "$condition → $action"
        }
}

/** What a rule is listed under. */
enum class RuleGroup(
    val label: String,
) {
    Spam("Spam"),
    Filing("Filing"),
    Forwarding("Forwarding"),
    Orders("Orders & tickets"),
    Other("Other"),
}

/** Looks up a folder's display name by id; null when unknown. */
typealias FolderNameLookup = (String) -> String?

/**
 * One sentence per rule, and the group a rule is listed under.
 *
 * The vocabulary is the closed set the server evaluates. A key this file does not know is shown by
 * its own name, never dropped, so a rule written by hand or by a newer server still reads as something.
 */
object RuleSummaries {
    const val CLASSIFY_SENTENCE =
        "Reads every incoming mail with the administrator's model and decides whether it is spam"
    const val ORDERS_SENTENCE =
        "Bundles orders and tickets found in mail into the Orders list, for accounts with that switched on"

    private const val MAX_WHEN_CHARS = 90
    private const val KIB = 1024
    private const val MIB = 1024 * 1024

    private val specialUseLabel =
        mapOf(
            "inbox" to "Inbox",
            "junk" to "Junk",
            "trash" to "Trash",
            "archive" to "Archive",
            "sent" to "Sent",
            "drafts" to "Drafts",
        )

    fun describe(
        stage: StageOut,
        folderName: FolderNameLookup = { null },
    ): RuleSummary =
        when (stage.type) {
            "classify" -> RuleSummary(listOf(t(CLASSIFY_SENTENCE)), emptyList(), stage.halt, false)
            "orders" -> RuleSummary(listOf(t(ORDERS_SENTENCE)), emptyList(), stage.halt, false)
            "match" -> {
                val effects = effectsOf(stage).flatMap { describeEffect(it, folderName) }
                RuleSummary(
                    whenParts = describeWhen(stage.config["when"]),
                    thenParts = if (effects.isEmpty()) listOf(t("do nothing")) else joinParts(effects, ", "),
                    stop = stage.halt,
                    permanent = hasEffect(stage, "expunge"),
                )
            }
            else -> RuleSummary(listOf(t("A “${stage.type}” step")), emptyList(), stage.halt, false)
        }

    fun group(stage: StageOut): RuleGroup =
        when {
            stage.type == "classify" -> RuleGroup.Spam
            stage.type == "orders" -> RuleGroup.Orders
            stage.type != "match" -> RuleGroup.Other
            hasEffect(stage, "webhook") -> RuleGroup.Forwarding
            usesCondition(stage.config["when"], "verdict_is") -> RuleGroup.Spam
            else -> RuleGroup.Filing
        }

    /** A stage the rule editor does not cover: the built-in spam and orders steps. */
    fun isFixed(stage: StageOut): Boolean = stage.type == "classify" || stage.type == "orders"

    /** Whether a condition tree anywhere uses the given leaf key. */
    fun usesCondition(
        condition: JsonElement?,
        key: String,
    ): Boolean {
        val obj = condition as? JsonObject ?: return false
        return when {
            obj["all"] is JsonArray -> (obj["all"] as JsonArray).any { usesCondition(it, key) }
            obj["any"] is JsonArray -> (obj["any"] as JsonArray).any { usesCondition(it, key) }
            "not" in obj -> usesCondition(obj["not"], key)
            else -> key in obj
        }
    }

    private fun t(text: String) = Part(text)

    private fun s(text: String) = Part(text, strong = true)

    private fun plain(parts: List<Part>) = parts.joinToString("") { it.text }

    private fun joinParts(
        groups: List<List<Part>>,
        separator: String,
    ): List<Part> =
        groups.flatMapIndexed { index, group -> if (index > 0) listOf(t(separator)) + group else group }

    private fun formatBytes(bytes: Double): String =
        when {
            bytes >= MIB -> {
                val mb = bytes / MIB
                if (mb == Math.floor(mb)) "${mb.toLong()} MB" else "${"%.1f".format(java.util.Locale.ROOT, mb)} MB"
            }
            bytes >= KIB -> "${Math.round(bytes / KIB)} KB"
            else -> "${bytes.toLong()} B"
        }

    /** The text of a leaf value the way a person would read it: a string as itself, anything else as its JSON. */
    private fun textOf(value: JsonElement?): String =
        when {
            value is JsonPrimitive && value.isString -> value.content
            value == null -> "null"
            else -> value.toString()
        }

    private fun field(
        value: JsonElement?,
        name: String,
    ): String = ((value as? JsonObject)?.get(name)?.takeIf { it !is JsonNull })?.let(::textOf).orEmpty()

    private fun leafCondition(
        key: String,
        value: JsonElement?,
    ): List<Part> {
        val text = textOf(value)
        return when (key) {
            "sender_match" ->
                if ("@" in text) listOf(t("sender is "), s(text)) else listOf(t("sender domain "), s(text))
            "sender_domain" -> listOf(t("sender domain "), s(text))
            "subject_contains" -> listOf(t("subject contains "), s(text))
            "subject_matches" -> listOf(t("subject matches "), s("/$text/"))
            "body_contains" -> listOf(t("body contains "), s(text))
            "body_matches" -> listOf(t("body matches "), s("/$text/"))
            "header_match" ->
                listOf(
                    t("header "),
                    s(field(value, "field")),
                    t(" matches "),
                    s("/${field(value, "pattern")}/"),
                )
            "header_exists" -> listOf(t("header "), s(text), t(" exists"))
            "size_gt" -> listOf(t("larger than "), s(sizeText(value, text)))
            "size_lt" -> listOf(t("smaller than "), s(sizeText(value, text)))
            "has_attachment" ->
                if (value is JsonPrimitive && value.isString && value.content.isNotEmpty()) {
                    listOf(t("has a "), s(value.content), t(" attachment"))
                } else {
                    listOf(t("has an attachment"))
                }
            "folder_is" -> listOf(t("in "), s(text))
            "tag_is" -> listOf(t("tagged "), s(text))
            "verdict_is" -> listOf(t("classified as "), s(if (text == "spam") "spam" else "not spam"))
            else -> listOf(s(key))
        }
    }

    private fun sizeText(
        value: JsonElement?,
        fallback: String,
    ): String = (value as? JsonPrimitive)?.doubleOrNull?.let(::formatBytes) ?: fallback

    private fun leafCount(condition: JsonElement?): Int {
        val obj = condition as? JsonObject ?: return 0
        return when {
            obj["all"] is JsonArray -> (obj["all"] as JsonArray).sumOf { leafCount(it) }
            obj["any"] is JsonArray -> (obj["any"] as JsonArray).sumOf { leafCount(it) }
            "not" in obj -> leafCount(obj["not"])
            else -> if (obj.isEmpty()) 0 else 1
        }
    }

    private fun describeCondition(condition: JsonElement?): List<Part> {
        val obj = condition as? JsonObject
        if (obj == null || obj.isEmpty()) return listOf(t("every mail"))
        val all = obj["all"] as? JsonArray
        val any = obj["any"] as? JsonArray
        return when {
            all != null ->
                if (all.isEmpty()) listOf(t("every mail")) else joinParts(all.map { describeCondition(it) }, " and ")
            any != null -> {
                val items = any.map { describeCondition(it) }
                val shown = items.take(2)
                val rest = items.size - shown.size
                listOf(t("any of: ")) + joinParts(shown, ", ") + if (rest > 0) listOf(t(" (+$rest)")) else emptyList()
            }
            "not" in obj -> listOf(t("not ")) + describeCondition(obj["not"])
            else -> obj.keys.first().let { leafCondition(it, obj[it]) }
        }
    }

    /** The condition sentence, collapsed to a count when it would not fit a line. */
    private fun describeWhen(condition: JsonElement?): List<Part> {
        val parts = describeCondition(condition)
        if (plain(parts).length <= MAX_WHEN_CHARS) return parts
        val n = leafCount(condition)
        val anyTop = (condition as? JsonObject)?.get("any") is JsonArray
        return listOf(t(if (anyTop) "any of $n conditions" else "$n conditions"))
    }

    private fun describeMove(
        value: JsonObject,
        folderName: FolderNameLookup,
    ): List<Part> {
        fun string(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val name =
            string("folder_id")?.let(folderName)
                ?: string("folder_name")?.takeIf { it.isNotEmpty() }
                ?: string("special_use")?.let { specialUseLabel[it] ?: it }
        return if (name != null) listOf(t("move to "), s(name)) else listOf(t("move to a folder"))
    }

    private fun describeEffect(
        effect: JsonElement,
        folderName: FolderNameLookup,
    ): List<List<Part>> {
        val obj = effect as? JsonObject ?: return emptyList()
        val kind = obj.keys.firstOrNull() ?: return emptyList()
        val value = obj[kind] as? JsonObject ?: JsonObject(emptyMap())
        return when (kind) {
            "move" -> listOf(describeMove(value, folderName))
            "trash" -> listOf(listOf(t("move to trash")))
            "expunge" -> listOf(listOf(s("delete permanently")))
            "set_flags" ->
                buildList {
                    when (value.bool("seen")) {
                        true -> add(listOf(t("mark read")))
                        false -> add(listOf(t("mark unread")))
                        null -> Unit
                    }
                    when (value.bool("flagged")) {
                        true -> add(listOf(t("star")))
                        false -> add(listOf(t("remove star")))
                        null -> Unit
                    }
                    if (value.bool("answered") == true) add(listOf(t("mark answered")))
                    if (value.bool("deleted") == true) add(listOf(t("flag as deleted")))
                }
            "keywords", "tag" -> {
                val noun = if (kind == "tag") "tag" else "keyword"
                value.strings("add").map { listOf(t("add $noun "), s(it)) } +
                    value.strings("remove").map { listOf(t("remove $noun "), s(it)) }
            }
            "notify" -> listOf(listOf(t("notify")))
            "webhook" -> {
                val name = value["name"]?.takeIf { it !is JsonNull }?.let(::textOf) ?: "a webhook"
                listOf(listOf(t("send to "), s(name)))
            }
            "record_verdict" -> listOf(listOf(t("record verdict")))
            "enqueue_order" -> listOf(listOf(t("queue for orders")))
            else -> listOf(listOf(s(kind)))
        }
    }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: it.toString() }.orEmpty()

    private fun effectsOf(stage: StageOut): List<JsonElement> = (stage.config["effects"] as? JsonArray).orEmpty()

    private fun hasEffect(
        stage: StageOut,
        kind: String,
    ): Boolean = effectsOf(stage).any { it is JsonObject && kind in it }
}
