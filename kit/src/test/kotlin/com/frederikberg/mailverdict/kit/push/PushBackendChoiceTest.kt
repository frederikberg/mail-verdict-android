package com.frederikberg.mailverdict.kit.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushBackendChoiceTest {
    @Test
    fun googlePushNeedsBothTheProjectConfigurationAndPlayServices() {
        assertEquals(PushBackend.Fcm, PushBackendChoice.decide(firebaseConfigured = true, playServicesAvailable = true))
    }

    @Test
    fun aBuildWithoutTheConfigurationUsesTheHelperAppPath() {
        assertEquals(
            PushBackend.UnifiedPush,
            PushBackendChoice.decide(firebaseConfigured = false, playServicesAvailable = true),
        )
    }

    @Test
    fun aPhoneWithoutPlayServicesUsesTheHelperAppPath() {
        assertEquals(
            PushBackend.UnifiedPush,
            PushBackendChoice.decide(firebaseConfigured = true, playServicesAvailable = false),
        )
        assertEquals(
            PushBackend.UnifiedPush,
            PushBackendChoice.decide(firebaseConfigured = false, playServicesAvailable = false),
        )
    }
}
