package com.frederikberg.mailverdict.kit.fixtures

/**
 * Canned answers for [FixtureInterceptor]; grows with the screens that need them. Keys carry the
 * `/api` prefix the request factory adds, because the interceptor sees the request as it goes out.
 */
object Fixtures {
    const val PRIVATE_ACCOUNT = "00000000-0000-4000-8000-0000000000a1"
    const val WORK_ACCOUNT = "00000000-0000-4000-8000-0000000000a2"

    private fun account(
        id: String,
        name: String,
        emoji: String,
    ) = """
        {"id":"$id","name":"$name","imap_host":"imap.example.com","imap_port":993,"imap_user":"$name@example.com",
         "is_active":true,"state":"active","emoji":"$emoji","folder_order":[],
         "created_at":"2026-01-01T00:00:00+00:00","updated_at":"2026-01-01T00:00:00+00:00"}
        """.trimIndent()

    private fun syncStatus(id: String) =
        """{"account_id":"$id","state":"active","last_full_sync":"2026-11-13T07:00:00+00:00"}"""

    private fun folder(
        id: String,
        imapName: String,
        specialUse: String?,
        unread: Int,
        total: Int,
    ) = """
        {"folder_id":"$id","imap_name":"$imapName","special_use":${specialUse?.let { "\"$it\"" }},
         "is_visible":true,"kind":"imap","total_count":$total,"unread_count":$unread}
        """.trimIndent()

    private val privateFolders =
        listOf(
            folder("00000000-0000-4000-8000-0000000001f6", "Receipts", null, 0, 31),
            folder("00000000-0000-4000-8000-0000000001f3", "Sent", "sent", 0, 410),
            folder("00000000-0000-4000-8000-0000000001f1", "INBOX", "inbox", 12, 1204),
            folder("00000000-0000-4000-8000-0000000001f5", "Trash", "trash", 0, 18),
            folder("00000000-0000-4000-8000-0000000001f2", "Drafts", "drafts", 0, 3),
            folder("00000000-0000-4000-8000-0000000001f4", "Archive", "archive", 0, 5230),
        )

    private val workFolders =
        listOf(
            folder("00000000-0000-4000-8000-0000000002f1", "INBOX", "inbox", 3, 88),
            folder("00000000-0000-4000-8000-0000000002f2", "Sent Items", "sent", 0, 140),
            folder("00000000-0000-4000-8000-0000000002f3", "Projects", null, 2, 64),
        )

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
            "GET /api/me" to
                """{"user_id":"00000000-0000-4000-8000-0000000000e1","email":"alex@example.com","display_name":"Alex Example","role":"user","disabled":false}""",
            "GET /api/accounts" to
                "[${account(PRIVATE_ACCOUNT, "Private", "🏠")},${account(WORK_ACCOUNT, "Work", "💼")}]",
            "GET /api/accounts/$PRIVATE_ACCOUNT/sync-status" to syncStatus(PRIVATE_ACCOUNT),
            "GET /api/accounts/$WORK_ACCOUNT/sync-status" to syncStatus(WORK_ACCOUNT),
            "GET /api/accounts/$PRIVATE_ACCOUNT/folder-order" to """{"folders":[${privateFolders.joinToString(
                ",",
            )}]}""",
            "GET /api/accounts/$WORK_ACCOUNT/folder-order" to """{"folders":[${workFolders.joinToString(",")}]}""",
            "GET /api/unified/folders" to
                """
                [{"id":"00000000-0000-4000-8000-0000000003a1","unified_name":"Inboxes","emoji":"📥",
                  "total_count":1292,"unread_count":15,
                  "folders":[
                    {"folder_id":"00000000-0000-4000-8000-0000000001f1","account_id":"$PRIVATE_ACCOUNT",
                     "account_name":"Private","account_emoji":"🏠","imap_name":"INBOX","special_use":"inbox"},
                    {"folder_id":"00000000-0000-4000-8000-0000000002f1","account_id":"$WORK_ACCOUNT",
                     "account_name":"Work","account_emoji":"💼","imap_name":"INBOX","special_use":"inbox"}]}]
                """.trimIndent(),
            "GET /api/outbox" to "[]",
        ) + MailFixtures.routes
}
