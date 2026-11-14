package com.frederikberg.mailverdict.kit.push

/** What only the phone can do for Google's push service: hand out this install's token, and drop it. */
interface FcmPlatform {
    /** This install's push token, or null when Google's service cannot provide one right now. */
    suspend fun token(): String?

    /** Invalidates the current token, so a ticket issued for it can never deliver again. Fire and forget. */
    fun forgetToken()
}

/** Fixture runs and builds without Google's project configuration have no token to give. */
object NoFcm : FcmPlatform {
    override suspend fun token(): String? = null

    override fun forgetToken() = Unit
}

/** Removes what an earlier version registered through a helper app, so one phone never holds two subscriptions. */
interface LegacyPushCleanup {
    /** True when a helper-app registration existed and has been removed. */
    suspend fun removeLegacy(api: com.frederikberg.mailverdict.kit.net.ApiClient): Boolean
}
