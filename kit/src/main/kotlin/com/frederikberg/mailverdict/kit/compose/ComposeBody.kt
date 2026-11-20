package com.frederikberg.mailverdict.kit.compose

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * A reply or forward's quoted original. It sits under the authored text exactly as the server
 * returned it and is never parsed into the editor, so nothing the editor cannot show is flattened on
 * the way out.
 */
@Serializable
data class ComposeQuote(
    val html: String,
    /** "On <date>, <name> wrote:" for a reply; the multi-line forwarded-message header for a forward. */
    val attribution: String,
)

/**
 * The composer's body as outgoing HTML and back. The editor is plain text: each line becomes one
 * paragraph, the shape the web editor writes for a line of its own, followed by the quote wrapper
 * the web's quoted-message node writes, attribute for attribute, so a draft saved here reopens there
 * with its quote intact and the other way round.
 */
object ComposeHtml {
    /** Gmail's quote-bar declaration, the one every client recognises. */
    private const val QUOTE_BAR_STYLE = "margin:0 0 0 .8ex;border-left:1px #ccc solid;padding-left:1ex"

    /**
     * Matches both shapes a wrapper comes back in: the composer's own marker, and the bare
     * `div.gmail_quote` the server's sanitizer leaves when it drops unknown data attributes.
     */
    private const val WRAPPER = "div[data-quoted-message=true], div.gmail_quote"

    private val blockTags =
        setOf(
            "p",
            "div",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "li",
            "blockquote",
            "pre",
            "tr",
            "ul",
            "ol",
            "section",
            "article",
            "header",
            "footer",
            "table",
            "dl",
            "dt",
            "dd",
            "figure",
            "address",
        )

    /** `body_html` for a submission, or null when there is neither text nor a quote. */
    fun body(
        authoredHtml: String?,
        quote: ComposeQuote?,
    ): String? {
        if (authoredHtml.isNullOrEmpty() && quote == null) return null
        return authoredHtml.orEmpty() + (quote?.let(::quoteHtml).orEmpty())
    }

    /** Plain text as paragraphs; an empty line needs a `<br>` to occupy the line it stands for. Trailing blank lines are dropped, as in `body_text`. */
    fun fromText(text: String): String? {
        if (text.isBlank()) return null
        return text.trimEnd().split("\n").joinToString("") { line ->
            if (line.isEmpty()) "<p><br></p>" else "<p>${escape(line)}</p>"
        }
    }

    fun quoteHtml(quote: ComposeQuote): String {
        val attribution = quote.attribution.split("\n").joinToString("<br>", transform = ::escape)
        return "<div data-quoted-message=\"true\" class=\"gmail_quote\">" +
            "<div class=\"gmail_attr\">$attribution</div>" +
            "<blockquote type=\"cite\" class=\"gmail_quote\" style=\"$QUOTE_BAR_STYLE\">${quote.html}</blockquote>" +
            "</div>"
    }

    /**
     * Splits the quote wrapper out of a saved body: a reopened draft or a cancelled send carries it
     * inside `body_html`, and the composer shows it as the separate card it was. The first match in
     * document order is read, so a wrapper nested inside quoted content is never the one taken.
     */
    fun split(html: String): Pair<String, ComposeQuote?> {
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        val wrapper = document.body().selectFirst(WRAPPER) ?: return html to null
        val attribution = wrapper.selectFirst(".gmail_attr")?.let(::multilineText).orEmpty()
        val quoted = wrapper.selectFirst("blockquote")?.html().orEmpty()
        wrapper.remove()
        return document.body().html() to ComposeQuote(quoted, attribution)
    }

    /** HTML read as the editor's plain text: a block is a line, `<br>` a line break, whitespace collapsed as a browser would. */
    fun toText(html: String): String {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var hasContent = false

        var preformatted = false

        fun endLine(keepEmpty: Boolean) {
            val line = if (preformatted) current.toString().trimEnd() else current.toString().trim()
            if (hasContent || current.isNotEmpty() || keepEmpty) lines += line
            current.clear()
            hasContent = false
        }

        fun walk(
            node: Node,
            pre: Boolean,
        ) {
            when (node) {
                is TextNode -> {
                    preformatted = pre
                    val text = if (pre) node.wholeText else node.wholeText.replace(Regex("\\s+"), " ")
                    if (pre && '\n' in text) {
                        val parts = text.split("\n")
                        parts.forEachIndexed { i, part ->
                            current.append(part)
                            if (i < parts.lastIndex) endLine(keepEmpty = true)
                        }
                    } else if (text.isNotBlank() || current.isNotEmpty()) {
                        current.append(text)
                    }
                    if (text.isNotBlank()) hasContent = true
                }
                is Element -> {
                    val tag = node.tagName().lowercase()
                    if (tag == "script" || tag == "style" || tag == "head") return
                    if (tag == "br") {
                        // A break closing a block renders nothing, unless the block holds nothing else.
                        val closesBlock = node.nextSibling() == null && node.parent()?.tagName() in blockTags
                        if (!closesBlock || !hasContent) endLine(keepEmpty = true)
                        return
                    }
                    val block = tag in blockTags
                    if (block && (hasContent || current.isNotEmpty())) endLine(keepEmpty = false)
                    node.childNodes().forEach { walk(it, pre || tag == "pre") }
                    if (block) endLine(keepEmpty = false)
                }
            }
        }
        Jsoup
            .parseBodyFragment(html)
            .body()
            .childNodes()
            .forEach { walk(it, false) }
        endLine(keepEmpty = false)
        return lines.joinToString("\n").trimEnd('\n')
    }

    private fun multilineText(element: Element): String =
        element.childNodes().joinToString("") { node ->
            when {
                node is TextNode -> node.wholeText
                node is Element && node.tagName().equals("br", ignoreCase = true) -> "\n"
                else -> ""
            }
        }

    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
