package com.frederikberg.mailverdict.alerts

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.frederikberg.mailverdict.contract.AlertResponse
import com.frederikberg.mailverdict.contract.NotificationResponse
import com.frederikberg.mailverdict.kit.alerts.AlertsState
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class AlertsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun alert(
        id: String,
        kind: String,
        title: String,
        body: String,
    ) = AlertResponse(
        id = id,
        kind = kind,
        title = title,
        body = body,
        messageId = if (kind == "mail") "m-$id" else null,
        createdAt = "2026-03-03T08:15:00+00:00",
    )

    private val state =
        AlertsState(
            mailAlerts =
                listOf(
                    alert("1", "mail", "Ada Lovelace", "Invoice for March"),
                    alert("2", "mail", "Grace Hopper", "Compiler notes"),
                ),
            systemAlerts =
                listOf(
                    alert("3", "outbox_stalled", "Message not sent", "To bob@example.com: connection refused"),
                ),
            hasLoaded = true,
        )

    private fun show(
        state: AlertsState,
        banner: Boolean = false,
    ) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                AlertsScreen(
                    state,
                    banner,
                    AlertsCallbacks(
                        onBack = { events += "back" },
                        onOpen = { events += "open:${it.id}" },
                        onDismiss = { events += "dismiss:${it.id}" },
                        onDismissAll = { events += "dismiss-all:${if (it) "system" else "mail"}" },
                        onAcknowledge = { events += "ack:${it.id}" },
                        onOpenPushSettings = { events += "push" },
                    ),
                )
            }
        }
    }

    @Test
    fun mailAlertsAreListedFirstAndOpenOnTap() {
        show(state)
        compose.onNodeWithText("Mail (2)").assertIsDisplayed()
        compose.onNodeWithText("Invoice for March").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/alerts-mail.png")
        compose.onNodeWithTag(AlertsTags.row("2")).performClick()
        assertEquals(listOf("open:2"), events)
    }

    @Test
    fun theSystemTabListsEverythingThatIsNotMail() {
        show(state)
        compose.onNodeWithTag(AlertsTags.TAB_SYSTEM).performClick()
        compose.onNodeWithText("Message not sent").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/alerts-system.png")
        compose.onNodeWithTag(AlertsTags.DISMISS_ALL).performClick()
        assertEquals(listOf("dismiss-all:system"), events)
    }

    private fun notification(
        id: Int,
        action: String,
        reverted: Boolean = false,
    ) = NotificationResponse(
        id = id,
        accountId = "acc-$id",
        action = action,
        error = "550 mailbox full",
        detail = JsonObject(mapOf("subject" to JsonPrimitive("Quarterly report"))),
        revertedAt = if (reverted) "2026-03-03T09:00:00+00:00" else null,
        createdAt = "2026-03-03T08:15:00+00:00",
    )

    @Test
    fun theSystemTabListsWriteFailuresWithTheWebsWording() {
        show(
            state.copy(
                notifications = listOf(notification(7, "move"), notification(8, "send", reverted = true)),
                accountNames = mapOf("acc-7" to "Private", "acc-8" to "Work"),
            ),
        )
        compose.onNodeWithText("System (3)").assertIsDisplayed()
        compose.onNodeWithTag(AlertsTags.TAB_SYSTEM).performClick()
        compose.onNodeWithText("Moving a message failed").assertIsDisplayed()
        compose.onNodeWithText("Sending a message failed").assertIsDisplayed()
        compose.onAllNodesWithText("“Quarterly report”").assertCountEquals(2)
        compose.onAllNodesWithText("550 mailbox full").assertCountEquals(2)
        compose
            .onAllNodesWithText("Our value is still shown as applied — the server never got it.")
            .assertCountEquals(1)
        compose.onNodeWithText("Private").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/alerts-system-notifications.png")
        compose.onNodeWithTag(AlertsTags.acknowledge(7)).performClick()
        assertEquals(listOf("ack:7"), events)
    }

    @Test
    fun aWriteFailureAloneKeepsDismissAllAndTheTabCount() {
        show(AlertsState(notifications = listOf(notification(7, "flag_add")), hasLoaded = true))
        compose.onNodeWithText("System (1)").assertIsDisplayed()
        compose.onNodeWithTag(AlertsTags.TAB_SYSTEM).performClick()
        compose.onNodeWithText("Setting a flag failed").assertIsDisplayed()
        compose.onNodeWithTag(AlertsTags.DISMISS_ALL).performClick()
        assertEquals(listOf("dismiss-all:system"), events)
    }

    @Test
    fun anActionTheAppHasNoLabelForShowsAsTheServerNamedIt() {
        show(AlertsState(notifications = listOf(notification(7, "label_set")), hasLoaded = true))
        compose.onNodeWithTag(AlertsTags.TAB_SYSTEM).performClick()
        compose.onNodeWithText("label_set failed").assertIsDisplayed()
    }

    @Test
    fun dismissingOneAlertReportsThatAlert() {
        show(state)
        compose.onNodeWithTag(AlertsTags.dismiss("1")).performClick()
        assertEquals(listOf("dismiss:1"), events)
    }

    @Test
    fun anEmptyListSaysSoAndOffersNoDismissAll() {
        show(AlertsState(hasLoaded = true))
        compose.onNodeWithTag(AlertsTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(AlertsTags.DISMISS_ALL).assertDoesNotExist()
    }

    @Test
    fun theBannerOffersNotificationsWhileTheyAreOff() {
        show(state, banner = true)
        compose.onNodeWithTag(AlertsTags.PUSH_BANNER).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/alerts-push-banner.png")
        compose.onNodeWithText("Set up").performClick()
        assertEquals(listOf("push"), events)
    }

    @Test
    fun noBannerWhenNotificationsAreOn() {
        show(state, banner = false)
        compose.onNodeWithTag(AlertsTags.PUSH_BANNER).assertDoesNotExist()
    }

    @Test
    fun theBellShowsTheServersCountAndCapsIt() {
        compose.setContent { MailVerdictTheme(dynamicColor = false) { AlertBell(3) { events += "bell" } } }
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithTag(AlertsTags.BELL).performClick()
        assertEquals(listOf("bell"), events)
    }

    @Test
    fun aBellWithNothingToSayShowsNoNumber() {
        compose.setContent { MailVerdictTheme(dynamicColor = false) { AlertBell(0) {} } }
        compose.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun aHugeCountShowsNinetyNinePlus() {
        compose.setContent { MailVerdictTheme(dynamicColor = false) { AlertBell(250) {} } }
        compose.onNodeWithText("99+").assertIsDisplayed()
    }
}
