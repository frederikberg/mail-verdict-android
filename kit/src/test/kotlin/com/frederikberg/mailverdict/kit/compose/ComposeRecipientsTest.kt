package com.frederikberg.mailverdict.kit.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposeRecipientsTest {
    @Test
    fun typedTextBecomesRecipientsAndWhatIsNotAnAddressStaysBehind() {
        val result =
            ComposeRecipients.commit(
                "Ada <ada@example.com>; nobody, grace@example.com",
                listOf("Grace@example.com"),
            )
        assertEquals(listOf("Grace@example.com", "ada@example.com"), result.recipients)
        assertEquals(listOf("nobody"), result.invalid)
        assertEquals("Not a valid email address: nobody", ComposeRecipients.invalidNote(result.invalid))
        assertNull(ComposeRecipients.invalidNote(emptyList()))
    }

    @Test
    fun aMailtoLinkFillsTheFieldsItNames() {
        val link =
            MailtoLink.parse(
                "mailto:ada@example.com,grace%40example.com?cc=b@x.test&subject=Hi%20there&body=Line+1%0ALine",
            )!!
        assertEquals(listOf("ada@example.com", "grace@example.com"), link.to)
        assertEquals(listOf("b@x.test"), link.cc)
        assertEquals("Hi there", link.subject)
        assertEquals("Line+1\nLine", link.body)
        assertNull(MailtoLink.parse("https://example.com"))
        assertNull(MailtoLink.parse("mailto:"))
        assertEquals("50%", MailtoLink.parse("mailto:a@x.test?subject=50%")!!.subject)
    }

    @Test
    fun theFromChoicesFollowTheWebsRules() {
        val accounts =
            listOf(
                account("a1", "me@one.test"),
                account("a2", "me@two.test"),
            )
        val identities =
            listOf(
                identity("i1", "a1", "me@one.test", default = false),
                identity("i2", "a1", "alias@one.test", default = true),
            )
        val every = IdentityRules.pickable(accounts, identities)
        assertEquals(listOf("i1", "i2", "account-default:a2"), every.map { it.key })
        assertNull(every.last().identityId)
        assertEquals("i2", IdentityRules.resolve(every, listOf("a1"))?.key)
        assertEquals("account-default:a2", IdentityRules.resolve(every, listOf(null, "a2"))?.key)
        assertEquals("i1", IdentityRules.matchIdentity(listOf("Someone <x@y.test>", "ME@one.test"), identities))
        assertEquals("Name <me@one.test>", every.first().copy(displayName = "Name").label)
    }

    private fun account(
        id: String,
        user: String,
    ) = com.frederikberg.mailverdict.contract.AccountResponse(
        id = id,
        name = id,
        imapHost = "imap.test",
        imapPort = 993,
        imapUser = user,
        state = "active",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private fun identity(
        id: String,
        account: String,
        address: String,
        default: Boolean,
    ) = com.frederikberg.mailverdict.contract.IdentityResponse(
        id,
        account,
        address,
        null,
        default,
        "2026-01-01T00:00:00Z",
    )
}
