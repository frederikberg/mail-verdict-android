package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushControllerTest {
    private val server = TestServer()
    private val records = InMemoryPushRecordStore()
    private val platform = FakePlatform()
    private var signedIn = true

    private class FakePlatform : PushPlatform {
        var distributor = true
        val registered = mutableListOf<Pair<String, String>>()
        var unregisterCalls = 0

        override fun hasDistributor() = distributor

        override fun register(
            vapidPublicKey: String,
            label: String,
        ) {
            registered += vapidPublicKey to label
        }

        override fun unregister() {
            unregisterCalls++
        }
    }

    @After
    fun stop() = server.close()

    private val api by lazy {
        ApiClient(
            RequestFactory(server.baseUrl, "Test phone") { AuthMode.None },
            OkHttpClient(),
        )
    }

    private fun controller(timeoutMs: Long = 30_000) =
        PushController(
            PushRegistrar(records),
            platform,
            { if (signedIn) PushSession(api, server.baseUrl) else null },
            "Android · Pixel 6",
            timeoutMs,
        )

    private val endpoint = PushEndpointInfo("http://10.0.2.2:2586/upAbC123?up=1", "BPubKeyBase64Url", "AuthSecret")

    private fun subscription(
        id: String,
        muted: String = "[]",
    ) = """{"id":"$id","transport":"webpush","label":"Android · Pixel 6","muted_channels":$muted,
        |"reminders_enabled":true,"alert_folder_ids":null,"created_at":"2026-11-14T08:00:00Z"}
        """.trimMargin()

    private fun vapid(available: Boolean = true) =
        TestServer.json("""{"available":$available,"public_key":${if (available) "\"BVapidKey\"" else "null"}}""")

    @Test
    fun theRegistrationBodyIsAPlainWebPushSubscriptionOfTheDistributorsEndpoint() {
        server.script("/api/alerts/subscriptions", TestServer.json(subscription("s1"), 201))
        runBlocking { PushRegistrar(records).register(api, server.baseUrl, endpoint, "Android · Pixel 6") }
        val body =
            Json
                .parseToJsonElement(
                    server
                        .requestsTo("/api/alerts/subscriptions")
                        .single()
                        .body!!
                        .utf8(),
                ).jsonObject
        assertEquals("http://10.0.2.2:2586/upAbC123?up=1", body.getValue("endpoint").jsonPrimitive.content)
        assertEquals(
            "BPubKeyBase64Url",
            body
                .getValue("keys")
                .jsonObject
                .getValue("p256dh")
                .jsonPrimitive.content,
        )
        assertEquals(
            "AuthSecret",
            body
                .getValue("keys")
                .jsonObject
                .getValue("auth")
                .jsonPrimitive.content,
        )
        assertEquals("Android · Pixel 6", body.getValue("label").jsonPrimitive.content)
        assertEquals(setOf("endpoint", "keys", "label"), body.keys)
        assertEquals("s1", records.read()?.subscriptionId)
    }

    @Test
    fun turningOnAsksTheDistributorWithTheServersVapidKeyAndEndsWhenItsEndpointArrives() =
        runBlocking {
            server.script("/api/alerts/vapid-public-key", vapid())
            server.script("/api/alerts/subscriptions", TestServer.json(subscription("s1"), 201))
            val controller = controller(timeoutMs = 20_000)
            val enabling = launch { controller.enable() }
            withTimeout(5_000) { while (platform.registered.isEmpty()) delay(10) }
            assertEquals(listOf("BVapidKey" to "Android · Pixel 6"), platform.registered)
            assertEquals(PushStatus.Registering, controller.status.value)
            controller.onNewEndpoint(endpoint)
            withTimeout(5_000) { enabling.join() }
            assertTrue(controller.status.value is PushStatus.Registered)
        }

    @Test
    fun aDistributorThatNeverAnswersEndsInAFailureInsteadOfSettingUpForever() =
        runBlocking {
            server.script("/api/alerts/vapid-public-key", vapid())
            val controller = controller(timeoutMs = 150)
            controller.enable()
            assertEquals(PushStatus.Failed(PushController.REGISTRATION_TIMEOUT_MESSAGE), controller.status.value)
        }

    @Test
    fun aServerWithoutAnEncryptionKeyCannotSendPushAndTheStateSaysSo() =
        runBlocking {
            server.script("/api/alerts/vapid-public-key", vapid(available = false))
            val controller = controller()
            controller.enable()
            assertTrue(platform.registered.isEmpty())
            assertEquals(PushStatus.ServerUnavailable, controller.status.value)
        }

    @Test
    fun withoutADistributorTheOnlyNextStepIsToInstallOne() =
        runBlocking {
            platform.distributor = false
            server.script("/api/alerts/subscriptions", TestServer.json("[]"))
            val controller = controller()
            controller.refresh()
            assertEquals(PushStatus.NoDistributor, controller.status.value)
            controller.enable()
            assertTrue(platform.registered.isEmpty())
        }

    @Test
    fun theEndpointTheDistributorDeliversBecomesTheRegisteredSubscription() =
        runBlocking {
            server.script("/api/alerts/subscriptions", TestServer.json(subscription("s1"), 201))
            val controller = controller()
            controller.onNewEndpoint(endpoint)
            val status = controller.status.value as PushStatus.Registered
            assertEquals("s1", status.subscription.id)
            assertEquals("s1", controller.subscriptionId)
        }

    @Test
    fun aChangedEndpointReplacesTheOldSubscriptionOnTheServer() =
        runBlocking {
            records.write(PushRecord(server.baseUrl, "old", "http://old"))
            server.script("/api/alerts/subscriptions", TestServer.json(subscription("new"), 201))
            server.script("/api/alerts/subscriptions/old", MockResponse.Builder().code(204).build())
            controller().onNewEndpoint(endpoint)
            assertEquals("DELETE", server.requestsTo("/api/alerts/subscriptions/old").single().method)
            assertEquals("new", records.read()?.subscriptionId)
        }

    @Test
    fun aSubscriptionTheServerNoLongerListsIsForgottenAndOffered() =
        runBlocking {
            records.write(PushRecord(server.baseUrl, "gone", endpoint.url))
            server.script("/api/alerts/subscriptions", TestServer.json("[${subscription("someone-else")}]"))
            server.script("/api/alerts/vapid-public-key", vapid())
            val controller = controller()
            controller.refresh()
            assertEquals(PushStatus.Off, controller.status.value)
            assertNull(records.read())
        }

    @Test
    fun aRegisteredPhoneReassertsItsRegistrationOnRefresh() =
        runBlocking {
            records.write(PushRecord(server.baseUrl, "s1", endpoint.url))
            server.script("/api/alerts/subscriptions", TestServer.json("[${subscription("s1")}]"))
            server.script("/api/alerts/vapid-public-key", vapid())
            val controller = controller()
            controller.refresh()
            assertTrue(controller.status.value is PushStatus.Registered)
            assertEquals(1, platform.registered.size)
        }

    @Test
    fun mutingAChannelSendsTheWholeMutedList() =
        runBlocking {
            server.script("/api/alerts/subscriptions", TestServer.json(subscription("s1"), 201))
            val controller = controller()
            controller.onNewEndpoint(endpoint)
            server.script("/api/alerts/subscriptions/s1", TestServer.json(subscription("s1", """["system"]""")))
            controller.setMuted(com.frederikberg.mailverdict.contract.PushChannel.System, true)
            val body =
                Json
                    .parseToJsonElement(
                        server
                            .requestsTo("/api/alerts/subscriptions/s1")
                            .single()
                            .body!!
                            .utf8(),
                    ).jsonObject
            assertEquals(listOf("system"), body.getValue("muted_channels").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                listOf(
                    "system",
                ),
                (controller.status.value as PushStatus.Registered).subscription.mutedChannels.map {
                    it.raw
                },
            )
        }

    @Test
    fun turningOffStopsListeningAndDeletesTheServersCopy() =
        runBlocking {
            records.write(PushRecord(server.baseUrl, "s1", endpoint.url))
            server.script("/api/alerts/subscriptions/s1", MockResponse.Builder().code(204).build())
            val controller = controller()
            controller.disable()
            assertEquals(1, platform.unregisterCalls)
            assertEquals("DELETE", server.requestsTo("/api/alerts/subscriptions/s1").single().method)
            assertNull(records.read())
            assertEquals(PushStatus.Off, controller.status.value)
        }

    @Test
    fun signingOutStopsListeningAndForgetsWithoutTouchingTheServer() =
        runBlocking {
            records.write(PushRecord(server.baseUrl, "s1", endpoint.url))
            val controller = controller()
            controller.onSignedOut()
            assertEquals(1, platform.unregisterCalls)
            assertNull(records.read())
            assertTrue(server.requests.isEmpty())
        }

    @Test
    fun aTestPushTheServerReportsGoneTurnsTheStateOff() =
        runBlocking {
            server.script("/api/alerts/subscriptions", TestServer.json(subscription("s1"), 201))
            val controller = controller()
            controller.onNewEndpoint(endpoint)
            server.script("/api/alerts/subscriptions/s1/test", TestServer.jsonError(410, "gone"))
            controller.sendTest()
            assertEquals(PushStatus.Off, controller.status.value)
            assertEquals("gone", controller.notice.value)
        }
}
