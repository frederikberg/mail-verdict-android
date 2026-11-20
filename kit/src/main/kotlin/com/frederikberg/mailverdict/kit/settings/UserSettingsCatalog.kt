package com.frederikberg.mailverdict.kit.settings

/** One choice of a closed set; [value] is what the server stores. */
data class SettingOption(
    val value: Int,
    val label: String,
)

/** The control a setting is drawn with. */
sealed interface SettingControl {
    data object Switch : SettingControl

    data class Select(
        val options: List<SettingOption>,
    ) : SettingControl

    data class Number(
        val min: Int? = null,
        val suffix: String? = null,
    ) : SettingControl

    /** [nullable]: an empty box stores null. */
    data class Text(
        val nullable: Boolean = false,
    ) : SettingControl
}

/** A setting the screen knows how to word: where it lives on the server and how it is drawn. */
data class SettingSpec(
    val category: String,
    val key: String,
    val label: String,
    val help: String? = null,
    val control: SettingControl,
) {
    val id: String get() = "$category.$key"
}

/** A group of settings under one heading. */
data class SettingsSectionSpec(
    val title: String,
    val description: String? = null,
    val fields: List<SettingSpec>,
    /** Categories whose user-scoped keys no field names show here as plain controls. */
    val undeclaredFrom: Set<String> = emptySet(),
)

/**
 * The settings every person has their own value of, as the web's Mail and Notifications pages
 * word them. A key the server returns for this person that no field names is shown too, as a plain
 * control, so a setting added on the server is not hidden until the app learns its wording.
 */
object UserSettingsCatalog {
    const val MAIL = "mail"
    const val OUTBOX = "outbox"
    const val ORDERS = "orders"

    /** The categories read for the screen. */
    val categories: List<String> = listOf(MAIL, OUTBOX, ORDERS)

    private val notifyWaitOptions =
        listOf(
            SettingOption(0, "Immediately"),
            SettingOption(30, "30 seconds"),
            SettingOption(120, "2 minutes"),
            SettingOption(300, "5 minutes"),
        )

    private val undoSendOptions =
        listOf(
            SettingOption(0, "Off"),
            SettingOption(5, "5 seconds"),
            SettingOption(10, "10 seconds"),
            SettingOption(20, "20 seconds"),
            SettingOption(30, "30 seconds"),
        )

    val sections: List<SettingsSectionSpec> =
        listOf(
            SettingsSectionSpec(
                title = "Reading and filing",
                fields =
                    listOf(
                        SettingSpec(
                            MAIL,
                            "mark_read_on_file_to_archive_or_junk",
                            "Mark as read when filed",
                            "Archiving a message, or filing it as spam, marks it read as it moves. Mail another " +
                                "client puts in Archive is marked read too.",
                            SettingControl.Switch,
                        ),
                        SettingSpec(
                            MAIL,
                            "show_folder_totals",
                            "Show total message counts",
                            "Folders and unified views show how many messages they hold, dimmed beside the unread " +
                                "count. Applies on every device you sign in on.",
                            SettingControl.Switch,
                        ),
                    ),
                undeclaredFrom = setOf(MAIL),
            ),
            SettingsSectionSpec(
                title = "Notifications",
                description = "How the bell counts and when a new-mail alert goes out.",
                fields =
                    listOf(
                        SettingSpec(
                            MAIL,
                            "bell_badge_counts_new_mail",
                            "Count new mail on the bell",
                            "Off, the badge counts system notifications only; the bell's own list still shows " +
                                "every alert.",
                            SettingControl.Switch,
                        ),
                        SettingSpec(
                            MAIL,
                            "notify_wait_seconds",
                            "Wait before notifying",
                            "A new-mail alert waits this long for your rules to refile the message, so it names " +
                                "the folder the mail ends up in.",
                            SettingControl.Select(notifyWaitOptions),
                        ),
                    ),
            ),
            SettingsSectionSpec(
                title = "Sending",
                fields =
                    listOf(
                        SettingSpec(
                            OUTBOX,
                            "undo_send_seconds",
                            "Undo send",
                            "How long a sent message waits before it leaves, so Undo can still take it back.",
                            SettingControl.Select(undoSendOptions),
                        ),
                    ),
                undeclaredFrom = setOf(OUTBOX),
            ),
            SettingsSectionSpec(
                title = "Orders & tickets",
                description = "How mail that belongs to an order, ticket or booking is summarised and closed.",
                fields =
                    listOf(
                        SettingSpec(
                            ORDERS,
                            "language",
                            "Language of titles and summaries",
                            null,
                            SettingControl.Text(),
                        ),
                        SettingSpec(
                            ORDERS,
                            "auto_close_days",
                            "Close an open order after",
                            "Days after its last mail when no end date was found. 0 never closes one automatically.",
                            SettingControl.Number(min = 0, suffix = "days"),
                        ),
                        SettingSpec(
                            ORDERS,
                            "auto_close_grace_days",
                            "Grace after an expected end",
                            "Days after the later of the expected end and the last mail.",
                            SettingControl.Number(min = 0, suffix = "days"),
                        ),
                    ),
                undeclaredFrom = setOf(ORDERS),
            ),
        )

    /**
     * Keys other screens write (the account order, the unified views' folder order) or that only the
     * web edits (the orders filter is a block of JSON), left out of the plain controls.
     */
    val skipped: Set<String> = setOf("mail.account_order", "mail.unified_folder_order", "orders.filter")

    /** A key's name made readable: `auto_close_days` becomes "Auto close days". */
    fun humanize(key: String): String = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
