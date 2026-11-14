package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.contract.PushChannel
import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NativePushControllerTest {
    private val server = TestServer()
    private val records = InMemoryNativePushRecordStore()
    private val fcm = FakeFcm()
    private val legacy = FakeLegacy()
    private var now = 1_000_000L

    private class FakeFcm : FcmPlatform {
        var token: String? = "fcm-token-1"
        var forgotten = 0

        override suspend fun token() = token

        override fun forgetToken() {
            forgotten++
        }
    }

    private class FakeLegacy : LegacyPushCleanup {
        var present = false

        override suspend fun removeLegacy(api: ApiClient): Boolean = present.also { present = false }
    }

    @After
    fun stop() = server.close()

    private val api by lazy {
        ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, OkHttpClient())
    }

    private fun controller() =
        NativePushController(
            PushRelayClient(OkHttpClient()),
            fcm,
            records,
            legacy,
            { PushSession(api, server.baseUrl) },
            "Pixel 6",
            nowMs = { now },
        )

    private fun subscription(
        id: String,
        muted: String = "[]",
    ) = """{"id":"$id","transport":"native","label":"Pixel 6","muted_channels":$muted,
        |"reminders_enabled":true,"alert_folder_ids":null,"created_at":"2026-11-14T08:00:00Z"}
        """.trimMargin()

    private fun serveNative(
        available: Boolean = true,
        expiresAt: String = "2027-02-01T10:00:00Z",
    ) {
        val urls = if (available) """["${server.baseUrl}"]""" else "[]"
        server.script(
            "/api/alerts/native-push",
            TestServer.json(
                """{"available":$available,"relay_urls":$urls,"reason":${if (available) "null" else "\"off\""}}""",
            ),
        )
        server.script(
            "/v1/register",
            TestServer.json("""{"ticket":"ticket-1","ticket_id":"0123456789abcdef","expires_at":"$expiresAt"}"""),
        )
        server.script("/api/alerts/subscriptions/native", TestServer.json(subscription("s1"), 201))
    }

    private fun bodyOf(path: String): JsonObject =
        Json
            .parseToJsonElement(
                server
                    .requestsTo(path)
                    .last()
                    .body!!
                    .utf8(),
            ).jsonObject

    private fun record(
        token: String = "fcm-token-1",
        expiresAtMs: Long = now + 60L * 24 * 3600 * 1000,
        subscriptionId: String = "s1",
    ) = NativePushRecord(
        server.baseUrl,
        "11111111-2222-3333-4444-555555555555",
        Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
        subscriptionId,
        server.baseUrl,
        token,
        expiresAtMs,
    )

    @Test
    fun turningOnTakesTheTokenToTheRelayAndTheTicketAndAFreshKeyToTheServer() =
        runBlocking {
            serveNative()
            val controller = controller()

            controller.enable()

            val relayBody = bodyOf("/v1/register")
            assertEquals("mailverdict", relayBody.getValue("app").jsonPrimitive.content)
            assertEquals("fcm", relayBody.getValue("platform").jsonPrimitive.content)
            assertEquals("fcm-token-1", relayBody.getValue("token").jsonPrimitive.content)
            val body = bodyOf("/api/alerts/subscriptions/native")
            assertEquals("ticket-1", body.getValue("ticket").jsonPrimitive.content)
            assertEquals(server.baseUrl, body.getValue("relay_url").jsonPrimitive.content)
            assertEquals("Pixel 6", body.getValue("label").jsonPrimitive.content)
            assertEquals(32, Base64.getDecoder().decode(body.getValue("content_key").jsonPrimitive.content).size)
            assertEquals(
                36,
                body
                    .getValue("installation_id")
                    .jsonPrimitive.content.length,
            )
            assertTrue(controller.status.value is PushStatus.Registered)
            val stored = records.read()!!
            assertEquals("s1", stored.subscriptionId)
            assertEquals(body.getValue("content_key").jsonPrimitive.content, stored.contentKey)
            assertEquals(1_801_476_000_000L, stored.ticketExpiresAtMs)
            assertEquals("s1", controller.subscriptionId)
        }

    @Test
    fun aServerThatCannotSendNativePushIsNeverAskedForAnything() =
        runBlocking {
            serveNative(available = false)
            val controller = controller()

            controller.enable()

            assertEquals(PushStatus.ServerUnavailable, controller.status.value)
            assertTrue(server.requestsTo("/v1/register").isEmpty())
            assertNull(records.read())
        }

    @Test
    fun aRelayThatRefusesLeavesTheServerUntouchedAndSaysWhy() =
        runBlocking {
            serveNative()
            server.script(
                "/v1/register",
                MockResponse
                    .Builder()
                    .code(400)
                    .body("platform fcm is not available for this app")
                    .build(),
            )
            val controller = controller()

            controller.enable()

            val failed = controller.status.value as PushStatus.Failed
            assertTrue(failed.message, "not available for this app" in failed.message)
            assertTrue(server.requestsTo("/api/alerts/subscriptions/native").isEmpty())
            assertNull(records.read())
        }

    @Test
    fun withoutAPushTokenNothingIsRegistered() =
        runBlocking {
            serveNative()
            fcm.token = null
            val controller = controller()

            controller.enable()

            assertEquals(PushStatus.Failed(NativePushController.NO_TOKEN_MESSAGE), controller.status.value)
            assertTrue(server.requestsTo("/v1/register").isEmpty())
        }

    @Test
    fun aServerRefusalIsShownAsTheFailure() =
        runBlocking {
            serveNative()
            server.script("/api/alerts/subscriptions/native", TestServer.jsonError(400, "relay_url is not allowed"))
            val controller = controller()

            controller.enable()

            assertEquals(PushStatus.Failed("relay_url is not allowed"), controller.status.value)
            assertNull(records.read())
        }

    @Test
    fun aFreshPhoneWithAServerThatCanSendIsOffered() =
        runBlocking {
            serveNative()
            val controller = controller()

            controller.refresh()

            assertEquals(PushStatus.Off, controller.status.value)
            assertTrue(server.requestsTo("/v1/register").isEmpty())
        }

    @Test
    fun aRegistrationTheServerStillListsIsLeftAloneWhileCurrent() =
        runBlocking {
            serveNative()
            records.write(record())
            server.script("/api/alerts/subscriptions", TestServer.json("[${subscription("s1")}]"))
            val controller = controller()

            controller.refresh()

            assertTrue(controller.status.value is PushStatus.Registered)
            assertTrue(server.requestsTo("/v1/register").isEmpty())
        }

    @Test
    fun aNewPushTokenRegistersAgainUnderTheSameInstallationAndKey() =
        runBlocking {
            serveNative()
            val before = record(token = "old-token")
            records.write(before)
            server.script("/api/alerts/subscriptions", TestServer.json("[${subscription("s1")}]"))
            val controller = controller()

            controller.refresh()

            assertEquals("fcm-token-1", bodyOf("/v1/register").getValue("token").jsonPrimitive.content)
            val body = bodyOf("/api/alerts/subscriptions/native")
            assertEquals(before.installationId, body.getValue("installation_id").jsonPrimitive.content)
            assertEquals(before.contentKey, body.getValue("content_key").jsonPrimitive.content)
            assertEquals("fcm-token-1", records.read()?.pushToken)
        }

    @Test
    fun aTicketNearingItsExpiryIsRenewed() =
        runBlocking {
            serveNative()
            records.write(record(expiresAtMs = now + 5L * 24 * 3600 * 1000))
            server.script("/api/alerts/subscriptions", TestServer.json("[${subscription("s1")}]"))

            controller().refresh()

            assertEquals(1, server.requestsTo("/v1/register").size)
            assertEquals(1_801_476_000_000L, records.read()?.ticketExpiresAtMs)
        }

    @Test
    fun aSubscriptionTheServerDroppedWhileTheAppWasAwayIsRegisteredAgain() =
        runBlocking {
            serveNative()
            records.write(record(subscriptionId = "dropped"))
            server.script("/api/alerts/subscriptions", TestServer.json("[${subscription("someone-else")}]"))
            val controller = controller()

            controller.refresh()

            assertEquals("s1", records.read()?.subscriptionId)
            assertTrue(controller.status.value is PushStatus.Registered)
        }

    @Test
    fun whenARenewalFailsTheWorkingRegistrationStaysAndTheReasonIsANotice() =
        runBlocking {
            serveNative()
            records.write(record(token = "old-token"))
            server.script("/api/alerts/subscriptions", TestServer.json("[${subscription("s1")}]"))
            server.script("/v1/register", TestServer.status(429))
            val controller = controller()

            controller.refresh()

            assertTrue(controller.status.value is PushStatus.Registered)
            assertNotNull(controller.notice.value)
            assertEquals("old-token", records.read()?.pushToken)
        }

    @Test
    fun aTokenChangeRegistersAgainOnlyWhenThereIsARegistration() =
        runBlocking {
            serveNative()
            val controller = controller()
            controller.onTokenChanged()
            assertTrue(server.requestsTo("/v1/register").isEmpty())

            records.write(record(token = "old-token"))
            controller.onTokenChanged()

            assertEquals(1, server.requestsTo("/v1/register").size)
            assertEquals("fcm-token-1", records.read()?.pushToken)
        }

    @Test
    fun aHelperAppRegistrationFromBeforeIsRemovedAndNotificationsStayOn() =
        runBlocking {
            serveNative()
            legacy.present = true
            val controller = controller()

            controller.refresh()

            assertTrue(controller.status.value is PushStatus.Registered)
            assertEquals("s1", records.read()?.subscriptionId)
        }

    @Test
    fun turningOffDeletesTheSubscriptionAndDropsTheToken() =
        runBlocking {
            records.write(record())
            server.script("/api/alerts/subscriptions/s1", TestServer.status(204))
            val controller = controller()

            controller.disable()

            assertEquals("DELETE", server.requestsTo("/api/alerts/subscriptions/s1").single().method)
            assertNull(records.read())
            assertEquals(1, fcm.forgotten)
            assertEquals(PushStatus.Off, controller.status.value)
        }

    @Test
    fun turningOffWorksWhenTheServerCannotBeReached() =
        runBlocking {
            records.write(record())
            server.script("/api/alerts/subscriptions/s1", TestServer.status(503))
            val controller = controller()

            controller.disable()

            assertNull(records.read())
            assertEquals(PushStatus.Off, controller.status.value)
        }

    @Test
    fun signingOutForgetsTheKeyAndTheToken() {
        records.write(record())
        val controller = controller()

        controller.onSignedOut()

        assertNull(records.read())
        assertEquals(1, fcm.forgotten)
        assertEquals(PushStatus.Loading, controller.status.value)
    }

    @Test
    fun mutingAChannelGoesThroughTheSharedSubscriptionCall() =
        runBlocking {
            serveNative()
            val controller = controller()
            controller.enable()
            server.script("/api/alerts/subscriptions/s1", TestServer.json(subscription("s1", """["system"]""")))

            controller.setMuted(PushChannel.System, true)

            assertEquals(
                listOf("system"),
                bodyOf(
                    "/api/alerts/subscriptions/s1",
                ).getValue("muted_channels").jsonArray.map { it.jsonPrimitive.content },
            )
        }

    // What arrives

    private val vector =
        Json
            .parseToJsonElement(javaClass.getResource("/push_envelope_v1.json")!!.readText())
            .jsonObject
            .mapValues { it.value.jsonPrimitive.content }

    private fun registerVectorDevice(installationId: String = vector.getValue("installation_id")) {
        records.write(
            record().copy(
                installationId = installationId,
                contentKey = vector.getValue("content_key_base64"),
            ),
        )
    }

    @Test
    fun anAlertThatOpensIsShownAndNamesWhatToWithdraw() {
        registerVectorDevice()

        val receipt = controller().receive("alert", vector.getValue("blob")) as PushReceipt.Show

        assertEquals("Grüße aus Aachen", receipt.payload.title)
        assertEquals("8d1e6c52-4b0a-4f3e-a2c9-5e7f10b2d3a4", receipt.payload.tag)
        assertEquals(listOf("19a8b7c6-d5e4-4f3a-8b2c-1d0e9f8a7b6c"), receipt.resolved)
    }

    @Test
    fun anAlertThatDoesNotOpenStillSaysSomethingArrived() {
        registerVectorDevice(installationId = "00000000-0000-0000-0000-000000000000")

        val receipt = controller().receive("alert", vector.getValue("blob")) as PushReceipt.Show

        assertEquals(PushPayload(), receipt.payload)
    }

    @Test
    fun aPushForAPhoneThatIsNotRegisteredIsIgnored() {
        assertEquals(PushReceipt.Ignore, controller().receive("alert", vector.getValue("blob")))
        assertEquals(PushReceipt.Ignore, controller().receive("background", null))
    }

    @Test
    fun aBackgroundPushAsksForTheReadSyncAndAnUnknownKindIsIgnored() {
        registerVectorDevice()

        assertEquals(PushReceipt.ReadSync, controller().receive("background", null))
        assertEquals(PushReceipt.Ignore, controller().receive("something-new", null))
        assertEquals(PushReceipt.Ignore, controller().receive(null, null))
    }

    @Test
    fun anApiErrorOnRefreshIsReportedNotThrown() =
        runBlocking {
            records.write(record())
            server.script("/api/alerts/subscriptions", TestServer.jsonError(500, "boom"))
            val controller = controller()

            controller.refresh()

            assertEquals(PushStatus.LoadFailed("boom"), controller.status.value)
        }
}
