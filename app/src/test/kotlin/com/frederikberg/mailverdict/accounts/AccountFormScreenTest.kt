package com.frederikberg.mailverdict.accounts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.frederikberg.mailverdict.contract.AccountCreateRequest
import com.frederikberg.mailverdict.contract.AccountUpdateRequest
import com.frederikberg.mailverdict.kit.accounts.AccountFormInput
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class AccountFormScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var created: AccountCreateRequest? = null
    private var updated: AccountUpdateRequest? = null
    private val opened = mutableListOf<String>()

    private fun show(
        editing: Boolean = false,
        initial: AccountFormInput = AccountFormInput(),
        serverError: String? = null,
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            opened += uri
                        }
                    },
            ) {
                MailVerdictTheme(dynamicColor = false) {
                    AccountFormScreen(
                        editing = editing,
                        initial = initial,
                        submitting = false,
                        serverError = serverError,
                        onBack = {},
                        onCreate = { created = it },
                        onUpdate = { updated = it },
                    )
                }
            }
        }
    }

    private fun type(
        tag: String,
        text: String,
    ) = compose.onNodeWithTag(tag).performScrollTo().performTextInput(text)

    @Test
    fun gmailIsFilledInFromThePresetAndOnlyAnAddressAndAnAppPasswordAreAsked() {
        show()

        compose.onNodeWithTag(AccountFormTags.preset("gmail")).performClick()
        compose.onNodeWithTag(AccountFormTags.IMAP_HOST).assertDoesNotExist()
        type(AccountFormTags.NAME, "Private")
        type(AccountFormTags.EMAIL, "alex@gmail.com")
        type(AccountFormTags.APP_PASSWORD, "abcd efgh ijkl mnop")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/account-form-gmail.png")
        compose.onNodeWithTag(AccountFormTags.SUBMIT).performScrollTo().performClick()

        val request = created!!
        assertEquals("imap.gmail.com", request.imapHost)
        assertEquals("alex@gmail.com", request.imapUser)
        assertEquals("smtp.gmail.com", request.smtpHost)
        assertEquals(465, request.smtpPort)
        assertEquals("abcd efgh ijkl mnop", request.smtpPassword)
    }

    @Test
    fun theAppPasswordPageOpensInTheBrowser() {
        show()
        compose.onNodeWithTag(AccountFormTags.preset("icloud")).performClick()

        compose.onNodeWithTag(AccountFormTags.APP_PASSWORD_LINK).performScrollTo().performClick()

        assertEquals(listOf("https://support.apple.com/en-us/102654"), opened)
    }

    @Test
    fun icloudSignsInToImapWithTheNameBeforeTheAt() {
        show()
        compose.onNodeWithTag(AccountFormTags.preset("icloud")).performClick()
        type(AccountFormTags.NAME, "Cloud")
        type(AccountFormTags.EMAIL, "alex@icloud.com")
        type(AccountFormTags.APP_PASSWORD, "xxxx")
        compose.onNodeWithTag(AccountFormTags.SUBMIT).performScrollTo().performClick()

        assertEquals("alex", created!!.imapUser)
        assertEquals("alex@icloud.com", created!!.smtpUser)
        assertEquals(587, created!!.smtpPort)
    }

    @Test
    fun anEmptyPresetFormIsRefusedInPlaceAndSendsNothing() {
        show()
        compose.onNodeWithTag(AccountFormTags.preset("gmail")).performClick()
        compose.onNodeWithTag(AccountFormTags.SUBMIT).performScrollTo().performClick()

        assertNull(created)
        compose.onNodeWithTag(AccountFormTags.NAME).performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTextCount("Required") >= 2)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun anotherServerAsksForTheConnectionItself() {
        show()
        compose.onNodeWithTag(AccountFormTags.OTHER).performClick()
        type(AccountFormTags.NAME, "Work")
        type(AccountFormTags.IMAP_HOST, "imap.example.com")
        type(AccountFormTags.IMAP_USER, "alex")
        type(AccountFormTags.IMAP_PASSWORD, "pw")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/account-form-other.png")
        compose.onNodeWithTag(AccountFormTags.SUBMIT).performScrollTo().performClick()

        val request = created!!
        assertEquals("imap.example.com", request.imapHost)
        assertEquals(993, request.imapPort)
        assertEquals("alex", request.imapUser)
        assertEquals("pw", request.imapPassword)
    }

    @Test
    fun theServersRefusalStaysUnderTheButton() {
        show(serverError = "imap_host must not be empty")

        compose.onNodeWithTag(AccountFormTags.ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("imap_host must not be empty").assertIsDisplayed()
    }

    @Test
    fun anEditKeepsTheImapIdentityFixedAndClearsABlankedRetention() {
        show(
            editing = true,
            initial =
                AccountFormInput(
                    name = "Private",
                    imapHost = "imap.example.com",
                    imapUser = "alex@example.com",
                    trashRetentionDays = "30",
                ),
        )

        compose.onNodeWithTag(AccountFormTags.IMAP_HOST).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(AccountFormTags.IMAP_USER).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(AccountFormTags.TRASH).performScrollTo().performTextClearance()
        compose.onNodeWithTag(AccountFormTags.SUBMIT).performScrollTo().performClick()

        assertEquals(JsonNull, updated!!.trashRetentionDays)
        assertNull(created)
    }
}
