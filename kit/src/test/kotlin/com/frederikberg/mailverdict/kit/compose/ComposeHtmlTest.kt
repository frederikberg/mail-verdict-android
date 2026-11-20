package com.frederikberg.mailverdict.kit.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposeHtmlTest {
    private val quote =
        ComposeQuote("<p>Sure, what time?</p>", "---------- Forwarded message ----------\nFrom: Alice <a@x.test>")

    @Test
    fun eachLineIsAParagraphAndAnEmptyLineKeepsItsPlace() {
        assertEquals(
            "<p>Hi Ada,</p><p><br></p><p>Fine &amp; &lt;done&gt;.</p>",
            ComposeHtml.fromText("Hi Ada,\n\nFine & <done>."),
        )
        assertNull(ComposeHtml.fromText("  \n "))
    }

    @Test
    fun theQuoteWrapperIsTheWebEditorsMarkup() {
        assertEquals(
            "<div data-quoted-message=\"true\" class=\"gmail_quote\">" +
                "<div class=\"gmail_attr\">---------- Forwarded message ----------" +
                "<br>From: Alice &lt;a@x.test&gt;</div>" +
                "<blockquote type=\"cite\" class=\"gmail_quote\" " +
                "style=\"margin:0 0 0 .8ex;border-left:1px #ccc solid;" +
                "padding-left:1ex\"><p>Sure, what time?</p></blockquote></div>",
            ComposeHtml.quoteHtml(quote),
        )
        assertNull(ComposeHtml.body(null, null))
        assertEquals("<p>a</p>", ComposeHtml.body("<p>a</p>", null))
    }

    @Test
    fun aSavedBodySplitsBackIntoTextAndTheQuoteItCarried() {
        val saved = ComposeHtml.body(ComposeHtml.fromText("Fine by me.\n\nAlex"), quote)!!
        val (authored, split) = ComposeHtml.split(saved)
        assertEquals(quote, split)
        assertEquals("Fine by me.\n\nAlex", ComposeHtml.toText(authored))
    }

    @Test
    fun theSanitizedWrapperWithoutItsMarkerIsStillFound() {
        val (authored, split) =
            ComposeHtml.split(
                "<p>Top</p><div class=\"gmail_quote\"><div class=\"gmail_attr\">On a day, B wrote:</div>" +
                    "<blockquote class=\"gmail_quote\"><p>inner</p></blockquote></div>",
            )
        assertEquals("<p>Top</p>", authored)
        assertEquals(ComposeQuote("<p>inner</p>", "On a day, B wrote:"), split)
        assertEquals("<p>x</p>" to null, ComposeHtml.split("<p>x</p>"))
    }

    @Test
    fun htmlReadsAsTextTheWayABrowserLaysItOut() {
        assertEquals("one\ntwo\nthree", ComposeHtml.toText("<p>one<br>two</p><div>three<br></div>"))
        assertEquals("a b", ComposeHtml.toText("<p>a\n   b</p>"))
        assertEquals("item\nnext", ComposeHtml.toText("<ul><li>item</li><li>next</li></ul>"))
        assertEquals("line 1\n  line 2", ComposeHtml.toText("<pre>line 1\n  line 2</pre>"))
        assertEquals("", ComposeHtml.toText(""))
    }
}
