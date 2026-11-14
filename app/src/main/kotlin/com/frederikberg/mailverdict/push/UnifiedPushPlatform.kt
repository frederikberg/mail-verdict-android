package com.frederikberg.mailverdict.push

import android.content.Context
import com.frederikberg.mailverdict.kit.push.PushPlatform
import org.unifiedpush.android.connector.UnifiedPush

/** The UnifiedPush connector behind the kit's [PushPlatform]: the distributor app delivers, this app registers. */
class UnifiedPushPlatform(
    private val context: Context,
) : PushPlatform {
    override fun hasDistributor(): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    override fun register(
        vapidPublicKey: String,
        label: String,
    ) = UnifiedPush.register(context, messageForDistributor = label, vapid = vapidPublicKey)

    override fun unregister() = UnifiedPush.unregister(context)
}

/** Fixture runs have no distributor and no server to push from. */
object NoPushPlatform : PushPlatform {
    override fun hasDistributor() = false

    override fun register(
        vapidPublicKey: String,
        label: String,
    ) = Unit

    override fun unregister() = Unit
}
