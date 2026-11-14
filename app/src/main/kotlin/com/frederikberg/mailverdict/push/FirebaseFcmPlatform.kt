package com.frederikberg.mailverdict.push

import android.content.Context
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.push.FcmPlatform
import com.frederikberg.mailverdict.kit.push.LegacyPushCleanup
import com.frederikberg.mailverdict.kit.push.PushRegistrar
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.unifiedpush.android.connector.UnifiedPush
import timber.log.Timber
import kotlin.coroutines.resume

/** What decides, at start, whether notifications come through Google's push service. */
object FcmAvailability {
    /** Whether this build carries a Firebase project configuration; a build without one compiles and runs without it. */
    fun configured(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    /** Whether Google Play services are installed, enabled and current on this phone. */
    fun playServices(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
}

/**
 * Google's push service behind the kit's [FcmPlatform]. Automatic start-up of the service is off in the
 * manifest, so a phone that never turns notifications on never contacts Google; the first request for a
 * token switches it on, and dropping the token switches it off again.
 */
class FirebaseFcmPlatform : FcmPlatform {
    override suspend fun token(): String? =
        try {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = true
            messaging.token.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "no push token from Google Play services")
            null
        }

    override fun forgetToken() {
        runCatching {
            val messaging = FirebaseMessaging.getInstance()
            messaging.deleteToken()
            messaging.isAutoInitEnabled = false
        }.onFailure { Timber.w(it, "push token could not be dropped") }
    }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val failure = task.exception
            if (failure != null) {
                continuation.resumeWith(Result.failure(failure))
            } else {
                continuation.resume(task.result)
            }
        }
    }

/** An earlier version registered the phone through a helper app; moving to Google's push service removes that. */
class UnifiedPushLegacyCleanup(
    private val context: Context,
    private val registrar: PushRegistrar,
) : LegacyPushCleanup {
    override suspend fun removeLegacy(api: ApiClient): Boolean {
        if (registrar.record() == null) return false
        UnifiedPush.unregister(context)
        registrar.unregister(api)
        return true
    }
}
