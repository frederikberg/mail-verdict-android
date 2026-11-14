package com.frederikberg.mailverdict.push

import android.content.Context
import com.frederikberg.mailverdict.kit.push.PushBackend
import com.frederikberg.mailverdict.kit.push.PushBackendChoice
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Robolectric has no Google Play services, which is the phone without them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FcmAvailabilityTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** The default Firebase app is process-wide state; every case starts with none. */
    @Before
    fun noFirebaseApp() = FirebaseApp.clearInstancesForTest()

    @Test
    fun aBuildWithoutTheFirebaseConfigurationIsNotConfigured() {
        assertFalse(FcmAvailability.configured(context))
    }

    @Test
    fun aBuildWithTheConfigurationIsConfigured() {
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions
                .Builder()
                .setApplicationId("1:1:android:abc")
                .setApiKey("not-a-key")
                .setProjectId("test-project")
                .build(),
        )

        assertTrue(FcmAvailability.configured(context))
    }

    @Test
    fun aPhoneWithoutPlayServicesFallsBackToTheHelperAppEvenWhenConfigured() {
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions
                .Builder()
                .setApplicationId("1:1:android:abc")
                .setApiKey("not-a-key")
                .setProjectId("test-project")
                .build(),
        )

        assertFalse(FcmAvailability.playServices(context))
        assertEquals(
            PushBackend.UnifiedPush,
            PushBackendChoice.decide(FcmAvailability.configured(context), FcmAvailability.playServices(context)),
        )
    }
}
