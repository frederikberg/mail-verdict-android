package com.frederikberg.mailverdict.kit.fixtures

/** Canned answers for the account, rules, settings and sign-in screens: invented people, servers and rules. */
object AccountFixtures {
    private const val RECEIPTS = MailFixtures.INBOX
    private const val NEWSLETTER = MailFixtures.SENT

    private fun signIn(
        id: String,
        kind: String,
        name: String?,
        current: Boolean = false,
        revokedAt: String? = null,
    ) = """{"id":"$id","user_id":"00000000-0000-4000-8000-0000000000e1","kind":"$kind","name":${name?.let {
        "\"$it\""
    }},
        |"subject":"sub-$id","first_seen_at":"2026-10-02T08:00:00+00:00","last_seen_at":"2026-11-19T18:30:00+00:00",
        |"revoked_at":${revokedAt?.let { "\"$it\"" }},"current":$current}
        """.trimMargin()

    private fun stage(
        id: String,
        name: String,
        type: String,
        config: String,
        enabled: Boolean = true,
        halt: Boolean = false,
        accounts: String = "null",
    ) = """{"stage_id":"$id","type":"$type","name":"$name","config":$config,"enabled":$enabled,"halt":$halt,
        |"accounts":$accounts}
        """.trimMargin()

    private val stages =
        listOf(
            stage("spam", "Spam detection", "classify", "{}"),
            stage(
                "receipts",
                "File receipts",
                "match",
                """{"when":{"any":[{"subject_contains":"receipt"},{"subject_contains":"invoice"}]},
                |"effects":[{"move":{"folder_id":"$RECEIPTS"}},{"set_flags":{"seen":true}}]}
                """.trimMargin(),
                accounts = "[\"${Fixtures.PRIVATE_ACCOUNT}\"]",
            ),
            stage(
                "newsletters",
                "Newsletters",
                "match",
                """{"when":{"header_exists":"List-Id"},"effects":[{"move":{"folder_id":"$NEWSLETTER"}}]}""",
                halt = true,
            ),
            stage(
                "old-promos",
                "Old promotions",
                "match",
                """{"when":{"sender_domain":"promo.example"},"effects":[{"expunge":{}}]}""",
                enabled = false,
            ),
            stage("orders", "Orders and tickets", "orders", "{}"),
        )

    private val pipeline =
        """{"revision":7,"enabled":true,"stages":[${stages.joinToString(",")}],
        |"warnings":[{"stage_id":"newsletters","account_id":"${Fixtures.PRIVATE_ACCOUNT}","reference":"Newsletter",
        |"ok":false,"detail":"no such folder"}]}
        """.trimMargin()

    val routes: Map<String, String> =
        mapOf(
            "GET /api/me/sign-ins" to
                "[${signIn("s1", "phone", "Pixel 8", current = true)},${signIn("s2", "phone", "Tablet")}," +
                "${signIn("s3", "browser", null)},${signIn("s4", "machine", "Backup script")}," +
                "${signIn("s5", "phone", "Old phone", revokedAt = "2026-11-01T10:00:00+00:00")}]",
            "GET /api/pipeline" to pipeline,
            "GET /api/pipeline/health" to
                """[{"stage_id":"newsletters","account_id":"${Fixtures.PRIVATE_ACCOUNT}","reference":"Newsletter","ok":false,"detail":"no such folder"}]""",
            "GET /api/pipeline/revisions" to
                """[{"revision":7,"note":"replaced via API","created_at":"2026-11-19T08:00:00+00:00"},
                |{"revision":6,"note":null,"created_at":"2026-11-12T08:00:00+00:00"}]
                """.trimMargin(),
            "GET /api/settings/mail" to
                """{"mark_read_on_file_to_archive_or_junk":true,"show_folder_totals":false,
                |"bell_badge_counts_new_mail":true,"notify_wait_seconds":30,"account_order":[]}
                """.trimMargin(),
            "GET /api/settings/outbox" to """{"undo_send_seconds":10}""",
            "GET /api/settings/orders" to """{"language":"en","auto_close_days":14,"auto_close_grace_days":7}""",
            "GET /api/settings/schema" to
                """{"mail":{"mark_read_on_file_to_archive_or_junk":{"scope":"user","type":"boolean"},
                |"show_folder_totals":{"scope":"user","type":"boolean"},
                |"bell_badge_counts_new_mail":{"scope":"user","type":"boolean"},
                |"notify_wait_seconds":{"scope":"user","type":"number"},
                |"account_order":{"scope":"user","type":"array"}},
                |"outbox":{"undo_send_seconds":{"scope":"user","type":"number"}},
                |"orders":{"language":{"scope":"user","type":"string"},
                |"auto_close_days":{"scope":"user","type":"number"},
                |"auto_close_grace_days":{"scope":"user","type":"number"}}}
                """.trimMargin(),
        )
}
