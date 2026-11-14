package com.frederikberg.mailverdict.kit.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDocumentTest {
    @Test
    fun theLightThemeIsAlwaysALightCanvas() {
        assertEquals(Canvas.Light, ReaderDocument.pickCanvas("<p>plain</p>", darkTheme = false))
        assertEquals(Canvas.Light, ReaderDocument.pickCanvas("<p style=\"color: #fff\">x</p>", darkTheme = false))
    }

    @Test
    fun inTheDarkThemeAMessageWithNoColoursOpensDark() {
        assertEquals(Canvas.Dark, ReaderDocument.pickCanvas("<p>plain <b>text</b></p>", darkTheme = true))
        assertEquals(Canvas.Dark, ReaderDocument.pickCanvas(null, darkTheme = true))
    }

    @Test
    fun aMessageThatDeclaresAnyColourStaysLight() {
        assertEquals(Canvas.Light, ReaderDocument.pickCanvas("<p style=\"color:#333\">x</p>", true))
        assertEquals(
            Canvas.Light,
            ReaderDocument.pickCanvas("<style>td{background-color:#eee}</style><td>x</td>", true),
        )
        assertEquals(
            Canvas.Light,
            ReaderDocument.pickCanvas("<table bgcolor=\"#ffffff\"><tr><td>x</td></tr></table>", true),
        )
    }

    @Test
    fun aMessageThatSaysItSupportsDarkModeOpensDarkWhateverItDeclares() {
        val html =
            "<style>@media (prefers-color-scheme: dark) { body { background: #000 } } p { color: #111 }</style><p>x</p>"
        assertEquals(Canvas.Dark, ReaderDocument.pickCanvas(html, true))
        assertEquals(
            Canvas.Dark,
            ReaderDocument.pickCanvas("<meta name=\"color-scheme\" style=\"color-scheme: light dark\">", true),
        )
    }

    @Test
    fun theFirstReplyQuoteIsFoldedAndNothingElseChanges() {
        val html =
            "<p>Thanks!</p><blockquote type=\"cite\"><p>Original</p></blockquote><p>-- <b>Ada</b></p>" +
                "<blockquote type=\"cite\">second</blockquote>"
        val folded = ReaderDocument.collapseReplyQuote(html)
        assertTrue(folded, folded.contains("<details class=\"mv-quote\">"))
        assertEquals("only one quote is wrapped", 1, Regex("<details").findAll(folded).count())
        assertTrue(folded.indexOf("<p>Thanks!</p>") < folded.indexOf("<details"))
        assertTrue("the quote sits inside the toggle", folded.indexOf("<details") < folded.indexOf("Original"))
        assertTrue(folded.contains("<p>-- <b>Ada</b></p>"))
    }

    @Test
    fun aQuoteIsRecognisedByTheClassMailClientsGiveIt() {
        val gmail = "<div class=\"gmail_quote\"><blockquote>old</blockquote></div>"
        assertTrue(ReaderDocument.collapseReplyQuote(gmail).contains("mv-quote"))
    }

    @Test
    fun anEditorialPullQuoteIsLeftVisible() {
        val html = "<blockquote>Words that are the article's own</blockquote>"
        assertEquals(html, ReaderDocument.collapseReplyQuote(html))
    }

    @Test
    fun theWholePageCarriesTheCanvasAndNoScript() {
        val page = ReaderDocument.page("<p>hi</p>", darkTheme = true)
        assertEquals(Canvas.Dark, page.canvas)
        assertTrue(page.html.contains("background: #09090b"))
        assertTrue(page.html.contains("<p>hi</p>"))
        assertFalse(page.html.contains("<script", ignoreCase = true))
    }

    @Test
    fun linksInPlainTextAreFoundBeforeLayoutAndStopBeforeTheSentenceEnds() {
        val body = PlainText.prepare("See https://example.com/a?b=1, or (https://example.org/x). Done.")
        assertEquals(listOf("https://example.com/a?b=1", "https://example.org/x"), body.links.map { it.url })
        body.links.forEach { assertEquals(it.url, body.text.substring(it.start, it.end)) }
    }

    @Test
    fun aHugePlainTextBodyIsCutAndSaysSo() {
        val body = PlainText.prepare("x".repeat(PlainText.MAX_CHARS + 5))
        assertEquals(PlainText.MAX_CHARS, body.text.length)
        assertTrue(body.truncated)
        assertFalse(PlainText.prepare("short").truncated)
    }
}
