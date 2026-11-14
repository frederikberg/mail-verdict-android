package com.frederikberg.mailverdict.kit.actions

import com.frederikberg.mailverdict.contract.ContractJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Intents as one JSON file. Each record is decoded on its own and one that no longer decodes (written
 * by another version) is dropped rather than the whole queue. Written through a temporary file and an
 * atomic move, so a crash never leaves half a file.
 */
class FileIntentPersistence(
    private val file: File,
) : IntentPersistence {
    override fun load(): List<MailIntent> {
        if (!file.isFile) return emptyList()
        val array =
            runCatching { ContractJson.parseToJsonElement(file.readText()) as? JsonArray }.getOrNull()
                ?: return emptyList()
        return array.mapNotNull {
            runCatching {
                ContractJson.decodeFromJsonElement(
                    MailIntent.serializer(),
                    it,
                )
            }.getOrNull()
        }
    }

    override fun save(intents: List<MailIntent>) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(ContractJson.encodeToString(ListSerializer(MailIntent.serializer()), intents))
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }
}
