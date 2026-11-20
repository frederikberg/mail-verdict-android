package com.frederikberg.mailverdict.accounts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.frederikberg.mailverdict.kit.accounts.AccountsState
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
class AccountsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(state: AccountsState) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                AccountsScreen(
                    state,
                    AccountsCallbacks(
                        onBack = { events += "back" },
                        onAdd = { events += "add" },
                        onOpen = { events += "open:${it.id}" },
                        onRetry = { events += "retry" },
                    ),
                )
            }
        }
    }

    private val loaded =
        AccountsState(
            rows =
                listOf(
                    AccountsTestData.row(),
                    AccountsTestData.row(
                        AccountsTestData.account("a2", "Work", "💼", state = "error", stateError = "login failed"),
                        lastSync = null,
                    ),
                ),
            hasLoaded = true,
        )

    @Test
    fun everyAccountIsListedWithItsStateAndOpensOnTap() {
        show(loaded)

        compose.onNodeWithText("Private").assertIsDisplayed()
        compose.onNodeWithText("Active").assertIsDisplayed()
        compose.onNodeWithText("Error").assertIsDisplayed()
        compose.onNodeWithText("Never synced").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/accounts-list.png")
        compose.onNodeWithTag(AccountsTags.row("a2")).performClick()
        assertEquals(listOf("open:a2"), events)
    }

    @Test
    fun theAddButtonOpensTheAddForm() {
        show(loaded)
        compose.onNodeWithTag(AccountsTags.ADD).performClick()
        assertEquals(listOf("add"), events)
    }

    @Test
    fun withNoAccountTheScreenInvitesAddingOne() {
        show(AccountsState(hasLoaded = true))

        compose.onNodeWithTag(AccountsTags.EMPTY).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/accounts-empty.png")
        compose.onNodeWithText("Add account").performClick()
        assertEquals(listOf("add"), events)
    }

    @Test
    fun aFailedReadSaysWhyAndOffersTryingAgainWhileKeepingTheRows() {
        show(loaded.copy(errorMessage = "database is down"))

        compose.onNodeWithTag(AccountsTags.ERROR).assertIsDisplayed()
        compose.onNodeWithText("Private").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry"), events)
    }
}
