package com.frederikberg.mailverdict

import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.kit.fixtures.FixtureInterceptor
import com.frederikberg.mailverdict.kit.fixtures.Fixtures
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Constructor wiring for everything the screens share. Nothing is injected by a framework: a
 * dependency is a property here, built in [create], and passed down by hand.
 */
class AppGraph private constructor(
    /** True when requests are answered from canned data instead of a server. */
    val fixtureMode: Boolean,
    val json: Json,
    val httpClient: OkHttpClient,
) {
    companion object {
        fun create(fixtureMode: Boolean): AppGraph {
            val client =
                OkHttpClient
                    .Builder()
                    .apply { if (fixtureMode) addInterceptor(FixtureInterceptor(Fixtures.routes)) }
                    .build()
            Timber.i("app graph created, fixtureMode=%s", fixtureMode)
            return AppGraph(fixtureMode = fixtureMode, json = ContractJson, httpClient = client)
        }
    }
}
