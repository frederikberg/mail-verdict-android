package com.frederikberg.mailverdict.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.frederikberg.mailverdict.kit.settings.SettingField
import com.frederikberg.mailverdict.kit.settings.UserSettingsState
import com.frederikberg.mailverdict.kit.settings.UserSettingsStore
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
class UserSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val writes = mutableListOf<Pair<String, JsonElement>>()

    private val state =
        UserSettingsState(
            values =
                mapOf(
                    "mail" to
                        buildJsonObject {
                            put("mark_read_on_file_to_archive_or_junk", true)
                            put("show_folder_totals", false)
                            put("bell_badge_counts_new_mail", true)
                            put("notify_wait_seconds", 30)
                        },
                    "outbox" to buildJsonObject { put("undo_send_seconds", 10) },
                    "orders" to
                        buildJsonObject {
                            put("language", "en")
                            put("auto_close_days", 14)
                        },
                ),
            hasLoaded = true,
        )

    private fun show(state: UserSettingsState = this.state) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                UserSettingsScreen(
                    state,
                    UserSettingsStore.sections(state),
                    SnackbarHostState(),
                    UserSettingsCallbacks(
                        onBack = {},
                        onRetry = { writes += "retry" to JsonPrimitive(true) },
                        onSet = { field: SettingField, value -> writes += field.spec.id to value },
                    ),
                )
            }
        }
    }

    @Test
    fun theSectionsAreWordedAsOnTheWeb() {
        show()

        compose.onNodeWithText("Reading and filing").assertIsDisplayed()
        compose.onNodeWithText("Mark as read when filed").assertIsDisplayed()
        compose.onNodeWithText("Undo send").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("10 seconds").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/user-settings.png")
    }

    @Test
    fun aSwitchWritesTheNewValueAtOnce() {
        show()

        compose.onNodeWithTag(UserSettingsTags.field("mail.show_folder_totals")).performClick()

        assertEquals(listOf("mail.show_folder_totals" to JsonPrimitive(true)), writes)
    }

    @Test
    fun aChoiceWritesTheNumberItStands() {
        show()

        compose.onNodeWithTag(UserSettingsTags.field("outbox.undo_send_seconds")).performScrollTo().performClick()
        compose.onNodeWithTag(UserSettingsTags.option("outbox.undo_send_seconds", 20)).performClick()

        assertEquals(listOf("outbox.undo_send_seconds" to JsonPrimitive(20)), writes)
    }

    @Test
    fun aNumberIsWrittenWhenTheFieldLosesFocus() {
        show()

        val days = compose.onNodeWithTag(UserSettingsTags.field("orders.auto_close_days")).performScrollTo()
        days.performClick()
        days.performTextClearance()
        days.performTextInput("30")
        assertEquals(emptyList<Pair<String, JsonElement>>(), writes)
        compose.onNodeWithTag(UserSettingsTags.field("orders.language")).performScrollTo().performClick()

        assertEquals(listOf("orders.auto_close_days" to JsonPrimitive(30)), writes)
    }

    @Test
    fun nothingReturnedMeansTheScreenSaysSo() {
        show(UserSettingsState(hasLoaded = true))

        compose.onNodeWithTag(UserSettingsTags.EMPTY).assertIsDisplayed()
    }

    @Test
    fun aFailedReadOffersTryingAgain() {
        show(UserSettingsState(errorMessage = "database is down"))

        compose.onNodeWithTag(UserSettingsTags.ERROR).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, writes.size)
    }
}
