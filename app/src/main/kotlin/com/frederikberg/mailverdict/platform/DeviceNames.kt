package com.frederikberg.mailverdict.platform

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.frederikberg.mailverdict.kit.net.DeviceName

/** The name this phone shows in the server's list of signed-in devices. */
object DeviceNames {
    /** The name the owner gave the phone in its settings, else the manufacturer and model. */
    fun of(context: Context): String {
        val chosen = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        return DeviceName.sanitize(chosen?.takeIf { it.isNotBlank() } ?: model())
    }

    private fun model(): String =
        if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) {
            Build.MODEL
        } else {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        }
}
