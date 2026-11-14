package com.frederikberg.mailverdict.push

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.frederikberg.mailverdict.contract.PushChannel
import com.frederikberg.mailverdict.contract.PushSubscriptionResponse
import com.frederikberg.mailverdict.contract.PushTransport
import com.frederikberg.mailverdict.kit.push.PushStatus
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
class PushSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun registered(muted: List<PushChannel> = emptyList()) =
        PushStatus.Registered(
            PushSubscriptionResponse(
                id = "s1",
                transport = PushTransport.WebPush,
                label = "Android · Pixel 6",
                mutedChannels = muted,
                remindersEnabled = true,
                createdAt = "2026-11-14T08:00:00Z",
            ),
        )

    private fun show(
        status: PushStatus,
        systemAllowed: Boolean = true,
        notice: String? = null,
    ) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                PushSettingsScreen(
                    status,
                    systemAllowed,
                    notice,
                    PushSettingsCallbacks(
                        onBack = { events += "back" },
                        onEnable = { events += "enable" },
                        onDisable = { events += "disable" },
                        onSendTest = { events += "test" },
                        onGetDistributor = { events += "get" },
                        onChooseDistributor = { events += "choose" },
                        onAllowSystem = { events += "allow" },
                        onMuted = { channel, muted -> events += "muted:${channel.raw}:$muted" },
                    ),
                )
            }
        }
    }

    @Test
    fun withoutAHelperAppTheFirstStepIsToGetOne() {
        show(PushStatus.NoDistributor)
        compose.onNodeWithTag(PushTags.GET_DISTRIBUTOR).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/push-no-distributor.png")
        compose.onNodeWithTag(PushTags.GET_DISTRIBUTOR).performClick()
        assertEquals(listOf("get"), events)
    }

    @Test
    fun withAHelperAppButNotificationsOffTheOnlyActionIsToTurnThemOn() {
        show(PushStatus.Off)
        compose.onNodeWithTag(PushTags.ENABLE).assertIsDisplayed()
        compose.onNodeWithTag(PushTags.GET_DISTRIBUTOR).assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/push-off.png")
        compose.onNodeWithTag(PushTags.ENABLE).performClick()
        assertEquals(listOf("enable"), events)
    }

    @Test
    fun aServerThatCannotPushOffersNothingToPress() {
        show(PushStatus.ServerUnavailable)
        compose.onNodeWithTag(PushTags.ENABLE).assertDoesNotExist()
        compose.onNodeWithTag(PushTags.STATUS).assertIsDisplayed()
    }

    @Test
    fun aFailedSetupShowsTheReasonAndOffersAnotherTry() {
        show(PushStatus.Failed("The helper could not connect."))
        compose.onNodeWithTag(PushTags.ENABLE).assertIsDisplayed()
        compose.onNodeWithTag(PushTags.STATUS).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/push-failed.png")
    }

    @Test
    fun whenOnTheChannelSwitchesReflectWhatIsMuted() {
        show(registered(muted = listOf(PushChannel.System)))
        compose.onNodeWithTag(PushTags.channel(PushChannel.Mail)).assertIsOn()
        compose.onNodeWithTag(PushTags.channel(PushChannel.System)).assertIsOff()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/push-registered.png")
        compose.onNodeWithTag(PushTags.channel(PushChannel.Mail)).performClick()
        compose.onNodeWithTag(PushTags.channel(PushChannel.System)).performClick()
        assertEquals(listOf("muted:mail:true", "muted:system:false"), events)
    }

    @Test
    fun whenOnTestAndTurnOffAreOffered() {
        show(registered())
        compose.onNodeWithTag(PushTags.TEST).performClick()
        compose.onNodeWithTag(PushTags.DISABLE).performClick()
        assertEquals(listOf("test", "disable"), events)
    }

    @Test
    fun androidBlockingNotificationsIsSaidAndOffersTheSystemPrompt() {
        show(registered(), systemAllowed = false)
        compose.onNodeWithTag(PushTags.ALLOW_SYSTEM).assertIsDisplayed()
        compose.onNodeWithTag(PushTags.ALLOW_SYSTEM).performClick()
        assertEquals(listOf("allow"), events)
    }

    @Test
    fun noSystemPromptWhenAndroidAllowsThem() {
        show(registered(), systemAllowed = true)
        compose.onNodeWithTag(PushTags.ALLOW_SYSTEM).assertDoesNotExist()
    }
}
