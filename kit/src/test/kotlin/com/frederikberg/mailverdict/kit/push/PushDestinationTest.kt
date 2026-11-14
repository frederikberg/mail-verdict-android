package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.contract.MessageLocation
import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.maillist.ListScope
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.net.RequestFactory
import com.frederikberg.mailverdict.kit.reader.InMemoryRecentViewRecord
import com.frederikberg.mailverdict.kit.reader.MessagePlaceResolver
import com.frederikberg.mailverdict.kit.search.MessagePlace
import com.frederikberg.mailverdict.kit.search.place
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushDestinationTest {
    private val server = TestServer()

    @After
    fun stop() = server.close()

    private val api by lazy {
        ApiClient(
            RequestFactory(server.baseUrl, "Test phone") { AuthMode.None },
            OkHttpClient(),
        )
    }

    private fun resolver(locate: suspend (String) -> MessageLocation) =
        MessagePlaceResolver(locate, unifiedViews = { emptyList() }, recent = InMemoryRecentViewRecord())

    private fun folders() {
        server.script(
            "/api/accounts/acc/folders",
            TestServer.json(
                """[{"id":"moved-to","account_id":"acc","imap_name":"INBOX","special_use":"inbox","kind":"imap",
                |"is_visible":true,"total_count":1,"unread_count":0}]
                """.trimMargin(),
            ),
        )
    }

    @Test
    fun aMessageNotificationOpensTheMessageInTheFolderItIsInNow() =
        runBlocking {
            folders()
            val resolver = resolver { MessageLocation(it, "acc", "moved-to", "t") }
            assertEquals(
                PushDestination.Message(
                    MessagePlace(ListScope.Folder("acc", "moved-to"), "Inbox", "m1", setOf("moved-to")),
                ),
                PushTarget.Message("m1").resolve(resolver, api),
            )
        }

    @Test
    fun aMessageThatIsGoneLandsOnTheAlertList() =
        runBlocking {
            val resolver = resolver { throw ApiError.Detail("gone", 404) }
            assertEquals(PushDestination.Alerts, PushTarget.Message("m1").resolve(resolver, api))
        }

    @Test
    fun anAlertWithoutAMessageNeedsNoLookup() =
        runBlocking {
            val resolver = resolver { error("not asked") }
            assertEquals(PushDestination.Alerts, PushTarget.Alerts.resolve(resolver, api))
        }

    @Test
    fun aFolderTheServerCannotNameStillOpensUnderAPlainTitle() =
        runBlocking {
            server.script("/api/accounts/acc/folders", TestServer.jsonError(500, "boom"))
            val resolver = resolver { MessageLocation(it, "acc", "f", "t") }
            assertEquals("Mail", resolver.place(api, "m1")?.title)
        }

    @Test
    fun aMissingMessageHasNoPlace() =
        runBlocking {
            assertNull(resolver { throw ApiError.Detail("gone", 404) }.place(api, "m1"))
        }
}
