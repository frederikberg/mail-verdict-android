package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.contract.PushChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushPayloadTest {
    private fun parse(json: String) = PushPayload.parse(json.toByteArray())

    @Test
    fun aMailAlertNamesItsMessageAndIsTheMailChannel() {
        val payload =
            parse(
                """{"title":"Ada","body":"Invoice","url":"/?message=7f3c1e0a-0000-4000-8000-000000000001","tag":"alert-1"}""",
            )
        assertEquals("7f3c1e0a-0000-4000-8000-000000000001", payload.messageId)
        assertEquals(PushChannel.Mail, payload.channel)
        assertEquals(PushTarget.Message("7f3c1e0a-0000-4000-8000-000000000001"), payload.target)
        assertEquals("alert-1", payload.tag)
    }

    @Test
    fun anAlertWithoutAMessageIsTheSystemChannelAndOpensTheAlertList() {
        val payload = parse("""{"title":"Send stuck","body":"x","url":null,"tag":"a"}""")
        assertNull(payload.messageId)
        assertEquals(PushChannel.System, payload.channel)
        assertEquals(PushTarget.Alerts, payload.target)
    }

    @Test
    fun theMessageIdIsFoundAmongOtherQueryParameters() {
        assertEquals("abc", PushPayload.messageIdOf("/?folder=f&message=abc&x=1"))
        assertNull(PushPayload.messageIdOf("/?message="))
        assertNull(PushPayload.messageIdOf("/inbox"))
        assertNull(PushPayload.messageIdOf("not a uri with spaces"))
        assertNull(PushPayload.messageIdOf(null))
    }

    @Test
    fun contentThatIsNotTheExpectedJsonStillBecomesANotification() {
        val payload = parse("plain text, not json")
        assertNull(payload.title)
        assertEquals(PushTarget.Alerts, payload.target)
        assertEquals(PushTarget.Alerts, parse("""{"title":[1]}""").target)
        assertEquals("T", parse("""{"title":"T","extra":1}""").title)
    }
}
