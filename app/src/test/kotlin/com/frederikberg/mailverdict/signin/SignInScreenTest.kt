package com.frederikberg.mailverdict.signin

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.frederikberg.mailverdict.kit.session.Rejection
import com.frederikberg.mailverdict.kit.session.SignInStatus
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
class SignInScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(state: SignInUiState) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                SignInScreen(
                    state = state,
                    onServerUrl = { calls += "url:$it" },
                    onSignIn = { calls += "sign-in" },
                    onCancel = { calls += "cancel" },
                )
            }
        }
    }

    private fun state(
        serverUrl: String = "https://mail.example.com",
        status: SignInStatus = SignInStatus.Idle,
        rejection: Rejection? = null,
    ) = SignInUiState(serverUrl, status, rejection)

    @Test
    fun theFirstLaunchAsksForTheServerAndOffersSignIn() {
        show(state(serverUrl = ""))

        compose.onNodeWithTag(SignInTags.SERVER).assertExists()
        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsNotEnabled()
        compose.onNodeWithText("Sign in with Zitadel").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/signin-first-launch.png")
    }

    @Test
    fun anAddressEnablesSignInAndTappingItAsks() {
        show(state())

        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsEnabled().performClick()

        assertEquals(listOf("sign-in"), calls)
    }

    @Test
    fun whileTheBrowserIsOpenTheScreenWaitsAndOffersToCancel() {
        show(state(status = SignInStatus.WaitingForBrowser))

        compose.onNodeWithText("Waiting for the browser…").assertExists()
        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun whileTheCodeIsTradedThereIsNothingToCancel() {
        show(state(status = SignInStatus.Exchanging))

        compose.onNodeWithText("Finishing sign-in…").assertExists()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsNotEnabled()
    }

    @Test
    fun aFailureIsShownInPlace() {
        show(state(status = SignInStatus.Failed("This server has no single sign-in.")))

        compose.onNodeWithText("This server has no single sign-in.").assertExists()
        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsEnabled()
    }

    @Test
    fun aRefusedCredentialSaysSoInsteadOfReadingAsAFirstLaunch() {
        show(state(rejection = Rejection("token_expired")))

        compose.onNodeWithText("Your sign-in was refused. Sign in again.").assertExists()
        compose.onNodeWithText("token_expired").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/signin-rejected.png")
    }

    @Test
    fun anHttpAddressIsWarnedAbout() {
        show(state(serverUrl = "http://10.0.0.5:8000"))

        compose.onNodeWithText("This address is http, not https", substring = true).assertExists()
    }

    @Test
    fun anHttpsAddressIsNot() {
        show(state())

        compose.onNodeWithText("This address is http, not https", substring = true).assertDoesNotExist()
    }

    @Test
    fun aRevokedDeviceIsToldItWasSignedOut() {
        show(state(rejection = Rejection("device_revoked")))

        compose.onNodeWithText("This device was signed out", substring = true).assertExists()
        compose.onNodeWithText("Your sign-in was refused. Sign in again.").assertDoesNotExist()
        compose.onNodeWithText("device_revoked").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/signin-device-revoked.png")
    }
}
