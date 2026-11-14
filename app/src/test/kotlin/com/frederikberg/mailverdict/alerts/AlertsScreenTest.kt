package com.frederikberg.mailverdict.alerts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.frederikberg.mailverdict.contract.AlertResponse
import com.frederikberg.mailverdict.kit.alerts.AlertsState
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import com.github.takahirom.roborazzi.captureRoboImage
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
