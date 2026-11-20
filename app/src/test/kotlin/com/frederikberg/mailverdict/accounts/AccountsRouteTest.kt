package com.frederikberg.mailverdict.accounts

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.frederikberg.mailverdict.kit.accounts.AccountsStore
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** A route with its real store, talking to a server that records what it is sent. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class AccountsRouteTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Sent(
        val method: String,
        val path: String,
        val body: String,
    )

    private val sent = CopyOnWriteArrayList<Sent>()
    private var postAnswer: Pair<Int, String> =
        201 to
            """{"id":"a9","name":"Private","imap_host":"imap.gmail.com","imap_port":993,"imap_user":"alex@gmail.com",
            |"created_at":"2026-01-01T00:00:00+00:00","updated_at":"2026-01-01T00:00:00+00:00"}
            """.trimMargin()

    private val server =
        Interceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { Buffer().also { buffer -> it.writeTo(buffer) }.readUtf8() }.orEmpty()
            sent += Sent(request.method, request.url.encodedPath, body)
            val (code, text) = if (request.method == "POST") postAnswer else 200 to "[]"
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("x")
                .body(text.toResponseBody("application/json".toMediaType()))
                .build()
        }

    private val store =
        AccountsStore(
            ApiClient(
                RequestFactory("https://mail.example.com", "Test phone") { AuthMode.None },
                OkHttpClient.Builder().addInterceptor(server).build(),
            ),
            scope,
        )

    @After
    fun stop() = scope.cancel()

    private fun fillGmail() {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                AddAccountRoute(store) {
                    sent +=
                        Sent("BACK", "", "")
                }
            }
        }
        compose.onNodeWithTag(AccountFormTags.preset("gmail")).performClick()
        compose.onNodeWithTag(AccountFormTags.NAME).performScrollTo().performTextInput("Private")
        compose.onNodeWithTag(AccountFormTags.EMAIL).performScrollTo().performTextInput("alex@gmail.com")
        compose.onNodeWithTag(AccountFormTags.APP_PASSWORD).performScrollTo().performTextInput("abcd efgh ijkl mnop")
        compose.onNodeWithTag(AccountFormTags.SUBMIT).performScrollTo().performClick()
    }

    @Test
    fun theGmailPresetPostsWhatTheServerTakesAndTheFormClosesOnSuccess() {
        fillGmail()

        compose.waitUntil(10_000) { sent.any { it.method == "BACK" } }
        val post = sent.single { it.method == "POST" }
        assertEquals("/api/accounts", post.path)
        val body = Json.parseToJsonElement(post.body).jsonObject
        assertEquals(JsonPrimitive("imap.gmail.com"), body["imap_host"])
        assertEquals(JsonPrimitive(993), body["imap_port"])
        assertEquals(JsonPrimitive("alex@gmail.com"), body["imap_user"])
        assertEquals(JsonPrimitive("abcd efgh ijkl mnop"), body["imap_password"])
        assertEquals(JsonPrimitive("smtp.gmail.com"), body["smtp_host"])
        assertEquals(JsonPrimitive(465), body["smtp_port"])
        assertEquals(JsonPrimitive("alex@gmail.com"), body["smtp_user"])
        assertEquals(JsonPrimitive("abcd efgh ijkl mnop"), body["smtp_password"])
    }

    @Test
    fun aRefusalStaysOnTheFormWithTheServersWords() {
        postAnswer = 422 to """{"detail":"could not connect to imap.gmail.com"}"""
        fillGmail()

        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("could not connect to imap.gmail.com"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(false, sent.any { it.method == "BACK" })
    }
}
