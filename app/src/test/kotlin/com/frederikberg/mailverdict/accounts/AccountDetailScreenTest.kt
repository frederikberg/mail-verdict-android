package com.frederikberg.mailverdict.accounts

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.frederikberg.mailverdict.kit.accounts.IdentitiesState
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
class AccountDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private val identities =
        IdentitiesState(
            listOf(
                AccountsTestData.identity("i1", "alex@example.com", "Alex Example", true),
                AccountsTestData.identity("i2", "billing@example.com", null, false),
            ),
            hasLoaded = true,
        )

    private fun show(
        row: com.frederikberg.mailverdict.kit.accounts.AccountRow? =
            AccountsTestData.row(
                AccountsTestData.account(trashDays = 30, spam = true, glacier = true, glacierDays = 90),
            ),
        identities: IdentitiesState = this.identities,
    ) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                AccountDetailScreen(
                    row,
                    identities,
                    SnackbarHostState(),
                    AccountDetailCallbacks(
                        onBack = { events += "back" },
                        onEdit = { events += "edit" },
                        onSyncNow = { events += "sync" },
                        onSetActive = { events += "active:$it" },
                        onSetSpam = { events += "spam:$it" },
                        onSetOrders = { events += "orders:$it" },
                        onSetEmoji = { events += "emoji:$it" },
                        onDelete = { events += "delete" },
                        onAddIdentity = { name, address, done ->
                            events += "add:$name:$address"
                            done()
                        },
                        onMakeDefault = { events += "default:${it.id}" },
                        onRemoveIdentity = { events += "remove:${it.id}" },
                    ),
                )
            }
        }
    }

    @Test
    fun theAccountShowsItsServerItsSettingsAndItsIdentities() {
        show()

        compose.onNodeWithText("imap.example.com:993").assertIsDisplayed()
        compose.onNodeWithText("30 days").assertIsDisplayed()
        compose.onNodeWithText("On, sweeps after 90 days").assertIsDisplayed()
        compose.onNodeWithText("Change detection: qresync").assertIsDisplayed()
        compose.onNodeWithText("Alex Example").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("billing@example.com").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/account-detail.png")
    }

    @Test
    fun syncEditAndTheSwitchesReportWhatWasAsked() {
        show()

        compose.onNodeWithTag(AccountDetailTags.SYNC_NOW).performClick()
        compose.onNodeWithTag(AccountDetailTags.EDIT).performClick()
        compose.onNodeWithTag(AccountDetailTags.ACTIVE).performClick()
        compose.onNodeWithTag(AccountDetailTags.SPAM).performClick()
        compose.onNodeWithTag(AccountDetailTags.ORDERS).performClick()

        assertEquals(listOf("sync", "edit", "active:false", "spam:false", "orders:true"), events)
    }

    @Test
    fun theDefaultIdentityCannotBeMadeDefaultAgainButAnotherCan() {
        show()

        compose.onNodeWithTag(AccountDetailTags.identityDefault("i1")).assertDoesNotExist()
        compose.onNodeWithTag(AccountDetailTags.identityDefault("i2")).performScrollTo().performClick()
        compose.onNodeWithTag(AccountDetailTags.identityRemove("i2")).performScrollTo().performClick()

        assertEquals(listOf("default:i2", "remove:i2"), events)
    }

    @Test
    fun anIdentityIsAddedFromNameAndAddressAndTheFieldsEmptyAfterwards() {
        show()

        compose.onNodeWithTag(AccountDetailTags.IDENTITY_ADD).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(AccountDetailTags.IDENTITY_NAME).performScrollTo().performTextInput("Alex Work")
        compose.onNodeWithTag(AccountDetailTags.IDENTITY_ADDRESS).performScrollTo().performTextInput("work@example.com")
        compose
            .onNodeWithTag(AccountDetailTags.IDENTITY_ADD)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertEquals(listOf("add:Alex Work:work@example.com"), events)
        compose.onNodeWithTag(AccountDetailTags.IDENTITY_ADD).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun deletingAsksFirstAndNamesTheAccount() {
        show()

        compose.onNodeWithTag(AccountDetailTags.DELETE).performScrollTo().performClick()
        compose.onNodeWithText("Delete “Private”?").assertIsDisplayed()
        assertEquals(emptyList<String>(), events)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag(AccountDetailTags.DELETE).performScrollTo().performClick()
        compose.onNodeWithTag(AccountDetailTags.CONFIRM_DELETE).performClick()

        assertEquals(listOf("delete"), events)
    }

    @Test
    fun theEmojiIsPickedFromADialogOrRemoved() {
        show()

        compose.onNodeWithTag(AccountDetailTags.EMOJI).performClick()
        compose.onNodeWithTag(AccountDetailTags.emojiChoice("💼")).performClick()
        compose.onNodeWithTag(AccountDetailTags.EMOJI).performClick()
        compose.onNodeWithTag(AccountDetailTags.EMOJI_REMOVE).performClick()

        assertEquals(listOf("emoji:💼", "emoji:null"), events)
    }

    @Test
    fun anErrorStateShowsItsReasonAndAMissingAccountSaysSo() {
        show(
            AccountsTestData.row(
                AccountsTestData.account(state = "error", stateError = "login failed"),
                lastSync = null,
            ),
        )
        compose.onNodeWithText("login failed").assertIsDisplayed()
    }

    @Test
    fun anAccountThatIsNoLongerThereSaysSo() {
        show(row = null)

        compose.onNodeWithTag(AccountDetailTags.NOT_FOUND).assertIsDisplayed()
    }
}
