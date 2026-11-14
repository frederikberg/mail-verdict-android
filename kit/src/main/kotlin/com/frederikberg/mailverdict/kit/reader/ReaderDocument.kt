package com.frederikberg.mailverdict.kit.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Which canvas a message body renders on. */
enum class Canvas(
    val background: String,
    val foreground: String,
) {
    Light("#ffffff", "#18181b"),
    Dark("#09090b", "#e4e4e7"),
}

/** The page one message body is drawn in. */
data class ReaderPage(
    val html: String,
    val canvas: Canvas,
)

/**
 * Builds the document a message body is shown in. The server has already sanitized the HTML, so this
 * adds only what presentation needs: a canvas chosen for the theme, a stylesheet that keeps images and
 * tables inside the screen, and the first reply quote folded behind a toggle that needs no script.
 */
object ReaderDocument {
    private val darkModeMedia = Regex("""@media[^{]*prefers-color-scheme\s*:\s*dark""", RegexOption.IGNORE_CASE)
    private val colorSchemeDark = Regex("""color-scheme\s*:[^;"'}]*\bdark\b""", RegexOption.IGNORE_CASE)
    private val colorDeclaration =
        Regex("""(?:^|[;{"'\s])(?:color|background|background-color)\s*:""", RegexOption.IGNORE_CASE)
    private val legacyColorAttribute =
        Regex("""\s(?:bgcolor|color|text|link|vlink|alink)\s*=""", RegexOption.IGNORE_CASE)

    /**
     * In the dark theme a message that declares its own dark-mode support opens dark; otherwise one
     * with no colour declaration anywhere opens dark too, as there is nothing in it for a dark canvas
     * to clash with. A message declaring any colour stays light: a dark canvas is never guaranteed to
     * match the background such a template assumed, so the safer failure is the merely unsurprising one.
     */
    fun pickCanvas(
        html: String?,
        darkTheme: Boolean,
    ): Canvas {
        if (!darkTheme) return Canvas.Light
        if (html.isNullOrEmpty()) return Canvas.Dark
        if (darkModeMedia.containsMatchIn(html) || colorSchemeDark.containsMatchIn(html)) return Canvas.Dark
        val declaresColor = colorDeclaration.containsMatchIn(html) || legacyColorAttribute.containsMatchIn(html)
        return if (declaresColor) Canvas.Light else Canvas.Dark
    }

    fun page(
        bodyHtml: String,
        darkTheme: Boolean,
        canvas: Canvas = pickCanvas(bodyHtml, darkTheme),
    ): ReaderPage {
        val body = collapseReplyQuote(bodyHtml)
        val html =
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<style>${css(canvas)}</style></head><body>$body</body></html>"
        return ReaderPage(html, canvas)
    }

    /** Class names mail clients give a reply's quoted original (Gmail, Thunderbird, Yahoo, ProtonMail); never a generic "quote", which an editorial pull-quote carries. */
    private val replyQuoteClass =
        Regex("""\b(?:gmail_quote|moz-cite-prefix|yahoo_quoted|protonmail_quote)\b""", RegexOption.IGNORE_CASE)
    private const val ANCESTOR_DEPTH = 2

    /** Wraps the first reply quote in a collapsed `<details>`; every other part of the body is left exactly as the server sent it. */
    fun collapseReplyQuote(html: String): String {
        if (!html.contains("blockquote", ignoreCase = true)) return html
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        val quote =
            document.body().select("blockquote").firstOrNull { isReplyQuote(it, document.body()) } ?: return html
        quote.before(
            "<details class=\"mv-quote\"><summary class=\"mv-quote-toggle\">&bull;&bull;&bull;</summary></details>",
        )
        val details = quote.previousElementSibling() ?: return html
        details.appendChild(quote)
        return document.body().html()
    }

    private fun isReplyQuote(
        blockquote: Element,
        root: Element,
    ): Boolean {
        if (blockquote.attr("type").equals("cite", ignoreCase = true)) return true
        var node: Element? = blockquote
        var depth = 0
        while (node != null && node !== root && depth <= ANCESTOR_DEPTH) {
            if (replyQuoteClass.containsMatchIn(node.className())) return true
            node = node.parent()
            depth++
        }
        return false
    }

    fun css(canvas: Canvas): String {
        val dark = canvas == Canvas.Dark
        return """
            html, body { margin: 0; padding: 0; }
            body {
              padding: 0 16px;
              font: 16px/1.55 sans-serif;
              word-wrap: break-word;
              overflow-wrap: break-word;
              color: ${canvas.foreground};
              background: ${canvas.background};
            }
            img { max-width: 100%; height: auto; }
            a { color: ${if (dark) "#60a5fa" else "#2563eb"}; }
            table { max-width: 100%; border-collapse: collapse; }
            td, th { padding: 4px 8px; }
            blockquote {
              border-left: 3px solid ${if (dark) "#3f3f46" else "#d4d4d8"};
              margin: 0.5em 0; padding: 0.25em 1em;
              color: ${if (dark) "#a1a1aa" else "#71717a"};
            }
            pre {
              background: ${if (dark) "#18181b" else "#f4f4f5"};
              padding: 8px 12px; border-radius: 4px; overflow-x: auto; font-size: 13px;
            }
            hr { border: none; border-top: 1px solid ${if (dark) "#27272a" else "#e4e4e7"}; margin: 1em 0; }
            details.mv-quote > summary {
              list-style: none; display: inline-block; padding: 0.1em 0.9em; border-radius: 999px;
              background: ${if (dark) "#3f3f46" else "#e4e4e7"}; color: ${if (dark) "#e4e4e7" else "#3f3f46"};
              margin: 0.4em 0;
            }
            details.mv-quote > summary::-webkit-details-marker { display: none; }
            """.trimIndent()
    }
}
