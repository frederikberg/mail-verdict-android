package com.frederikberg.mailverdict.debug

import android.util.Log
import com.frederikberg.mailverdict.AppGraph
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Loopback HTTP server for driving a debug build from `adb forward`:
 * - `/health` answers while the process is alive
 * - `/state` is the app's current wiring
 * - `/logs?level=WARN` is the in-memory log, filtered to that level and above
 *
 * Handlers run on the server's own worker thread. A route that touches Compose state must hop to
 * the main looper first.
 */
class DebugServer(
    private val graph: AppGraph,
    private val logs: RingBufferLogTree,
    private val versionName: String,
    port: Int,
) : NanoHTTPD("127.0.0.1", port) {
    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET) return json(Response.Status.METHOD_NOT_ALLOWED, error("GET only"))
        return when (session.uri) {
            "/health" -> json(Response.Status.OK, buildJsonObject { put("status", "ok") })
            "/state" ->
                json(
                    Response.Status.OK,
                    buildJsonObject {
                        put("fixtureMode", graph.fixtureMode)
                        put("versionName", versionName)
                    },
                )
            "/logs" -> logs(session.parameters["level"]?.firstOrNull())
            else -> json(Response.Status.NOT_FOUND, error("no route ${session.uri}"))
        }
    }

    private fun logs(level: String?): Response {
        val priority = if (level == null) Log.VERBOSE else PRIORITIES[level.uppercase()]
        if (priority == null) return json(Response.Status.BAD_REQUEST, error("unknown level $level"))
        val entries =
            logs.entries(priority).map {
                buildJsonObject {
                    put("level", PRIORITIES.entries.first { (_, p) -> p == it.priority }.key)
                    put("tag", it.tag)
                    put("message", it.message)
                }
            }
        return json(Response.Status.OK, JsonArray(entries))
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }

    private fun json(
        status: Response.IStatus,
        body: kotlinx.serialization.json.JsonElement,
    ): Response = newFixedLengthResponse(status, "application/json", body.toString())

    private companion object {
        val PRIORITIES =
            linkedMapOf(
                "VERBOSE" to Log.VERBOSE,
                "DEBUG" to Log.DEBUG,
                "INFO" to Log.INFO,
                "WARN" to Log.WARN,
                "ERROR" to Log.ERROR,
            )
    }
}
