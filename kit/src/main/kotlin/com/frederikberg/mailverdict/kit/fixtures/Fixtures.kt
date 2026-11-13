package com.frederikberg.mailverdict.kit.fixtures

/** Canned answers for [FixtureInterceptor]; grows with the screens that need them. */
object Fixtures {
    val routes: Map<String, String> =
        mapOf(
            "GET /health" to """{"status":"ok"}""",
            "GET /messages/00000000-0000-4000-8000-000000000001/location" to
                """
                {"id":"00000000-0000-4000-8000-000000000001",
                 "account_id":"00000000-0000-4000-8000-0000000000a1",
                 "folder_id":"00000000-0000-4000-8000-0000000000f1",
                 "thread_id":"00000000-0000-4000-8000-0000000000c1"}
                """.trimIndent(),
        )
}
