package com.frederikberg.mailverdict.push

import android.content.Context
import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.kit.push.PushRecord
import com.frederikberg.mailverdict.kit.push.PushRecordStore

/** This phone's push subscription as the server last confirmed it, kept in app-private preferences. */
class PushRecordPreferences(
    context: Context,
    fixtureMode: Boolean,
) : PushRecordStore {
    private val prefs =
        context.getSharedPreferences(
            if (fixtureMode) "mailverdict_push_fixture" else "mailverdict_push",
            Context.MODE_PRIVATE,
        )

    override fun read(): PushRecord? =
        prefs.getString(RECORD, null)?.let {
            runCatching { ContractJson.decodeFromString(PushRecord.serializer(), it) }.getOrNull()
        }

    override fun write(record: PushRecord) {
        prefs.edit().putString(RECORD, ContractJson.encodeToString(PushRecord.serializer(), record)).apply()
    }

    override fun clear() {
        prefs.edit().remove(RECORD).apply()
    }

    private companion object {
        const val RECORD = "record"
    }
}
