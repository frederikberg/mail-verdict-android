package com.frederikberg.mailverdict.signins

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.contract.SignInResponse
import com.frederikberg.mailverdict.kit.signins.SignInsState
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
class SignInsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun signIn(
        id: String,
        kind: String,
        name: String?,
        current: Boolean = false,
        revoked: Boolean = false,
    ): SignInResponse =
        ContractJson.decodeFromString(
            SignInResponse.serializer(),
            """{"id":"$id","user_id":"u1","kind":"$kind","name":${name?.let { "\"$it\"" }},"subject":"sub-$id",
            |"first_seen_at":"2026-10-02T08:00:00+00:00","last_seen_at":"2026-11-19T18:30:00+00:00",
            |"revoked_at":${if (revoked) "\"2026-11-19T19:00:00+00:00\"" else "null"},"current":$current}
            """.trimMargin(),
        )

    private val state =
        SignInsState(
            signIns =
                listOf(
                    signIn("s1", "phone", "Pixel 8", current = true),
                    signIn("s2", "phone", "Tablet"),
                    signIn("s3", "browser", null),
                    signIn("s4", "phone", "Old phone", revoked = true),
                ),
            hasLoaded = true,
        )

    private fun show(state: SignInsState = this.state) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                SignInsScreen(
                    state,
                    SnackbarHostState(),
                    SignInsCallbacks(
                        onBack = { events += "back" },
                        onRetry = { events += "retry" },
                        onRevoke = { events += "revoke:${it.id}" },
                    ),
                )
            }
        }
    }

    @Test
    fun everySignInIsListedWithTheCurrentOneMarked() {
        show()

        compose.onNodeWithText("Pixel 8").assertIsDisplayed()
        compose.onNodeWithTag(SignInsTags.current("s1")).assertIsDisplayed()
        compose.onNodeWithTag(SignInsTags.current("s2")).assertDoesNotExist()
        compose.onNodeWithText("Web browsers").assertIsDisplayed()
        compose.onNodeWithText("signed out").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/signins.png")
    }

    @Test
    fun aSignedOutRowOffersNoSignOut() {
        show()

        compose.onNodeWithTag(SignInsTags.signOut("s4")).assertDoesNotExist()
        compose.onNodeWithTag(SignInsTags.signOut("s2")).assertIsDisplayed()
    }

    @Test
    fun signingOutAnotherAsksFirstAndSaysWhatItDoes() {
        show()

        compose.onNodeWithTag(SignInsTags.signOut("s2")).performClick()
        compose.onNodeWithText("Sign out Tablet?").assertIsDisplayed()
        compose
            .onNodeWithText("This phone is signed out. Signing in again on it starts a new sign-in.")
            .assertIsDisplayed()
        assertEquals(emptyList<String>(), events)
        compose.onNodeWithTag(SignInsTags.CONFIRM).performClick()

        assertEquals(listOf("revoke:s2"), events)
    }

    @Test
    fun signingOutTheCurrentOneWarnsItEndsRightAway() {
        show()

        compose.onNodeWithTag(SignInsTags.signOut("s1")).performClick()
        compose.onNodeWithText("That includes this one, right away.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(SignInsTags.CONFIRM).performClick()

        assertEquals(listOf("revoke:s1"), events)
    }

    @Test
    fun cancellingTheConfirmationRevokesNothing() {
        show()

        compose.onNodeWithTag(SignInsTags.signOut("s2")).performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun anEmptyListAndAFailedReadSaySo() {
        show(SignInsState(hasLoaded = true, errorMessage = "database is down"))

        compose.onNodeWithTag(SignInsTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(SignInsTags.ERROR).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry"), events)
    }
}
