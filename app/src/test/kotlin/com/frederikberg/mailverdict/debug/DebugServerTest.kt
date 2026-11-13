package com.frederikberg.mailverdict.debug

import android.util.Log
import com.frederikberg.mailverdict.AppGraph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class DebugServerTest {
    private val logs = RingBufferLogTree(capacity = 3)
    private lateinit var server: DebugServer

    @Before
    fun start() {
        server = DebugServer(AppGraph.create(fixtureMode = true), logs, versionName = "9.9.9", port = 0)
        server.start()
    }

    @After
    fun stop() = server.stop()

    private fun get(path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        return code to stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun healthAnswers() {
        assertEquals(200 to """{"status":"ok"}""", get("/health"))
    }

    @Test
    fun stateReportsTheWiring() {
        val state = Json.parseToJsonElement(get("/state").second).jsonObject

        assertEquals(true, state.getValue("fixtureMode").jsonPrimitive.boolean)
        assertEquals("9.9.9", state.getValue("versionName").jsonPrimitive.content)
    }

    @Test
    fun logsKeepOnlyTheNewestLines() {
        logs.log(Log.INFO, "one")
        logs.log(Log.WARN, "two")
        logs.log(Log.ERROR, "three")
        logs.log(Log.INFO, "four")

        assertEquals(listOf("two", "three", "four"), messages("/logs"))
    }

    @Test
    fun logsFilterByLevelAndAbove() {
        logs.log(Log.INFO, "info")
        logs.log(Log.WARN, "warn")
        logs.log(Log.ERROR, "error")

        assertEquals(listOf("warn", "error"), messages("/logs?level=warn"))
    }

    @Test
    fun anUnknownLevelOrRouteIsRefused() {
        assertEquals(400, get("/logs?level=loud").first)
        assertEquals(404, get("/nope").first)
    }

    private fun messages(path: String): List<String> =
        Json
            .parseToJsonElement(
                get(path).second,
            ).jsonArray
            .map { (it as JsonObject).getValue("message").jsonPrimitive.content }
}
