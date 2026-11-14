package com.frederikberg.mailverdict.kit.reader

import com.frederikberg.mailverdict.kit.api.getAttachment
import com.frederikberg.mailverdict.kit.cache.NoOfflineCache
import com.frederikberg.mailverdict.kit.cache.OfflineCache
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException

/** An inline image or part as served to a message page. */
class ReaderResource(
    val data: ByteArray,
    val mimeType: String,
)

/**
 * What a message page asks for beyond its document: an inline attachment, fetched with the app's
 * credential. Each one served is kept in the offline cache and served from there when the server
 * cannot be reached, so a saved conversation shows the images it showed.
 */
class ReaderResources(
    private val api: ApiClient,
    private val cache: OfflineCache = NoOfflineCache,
) {
    suspend fun load(request: ReaderRequest.Attachment): ReaderResource? {
        val key = "attachment-${request.messageId}-${request.attachmentId}"
        return try {
            val raw = api.getAttachment(request.messageId, request.attachmentId)
            val mime =
                raw.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.ifEmpty { null } ?: "application/octet-stream"
            runCatching { cache.saveResource(key, raw.data, mime) }
            ReaderResource(raw.data, mime)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val unreachable = e is ApiError.Transport || (e is ApiError.Http && e.statusCode in setOf(502, 503, 504))
            if (unreachable) cache.resource(key)?.let { ReaderResource(it.data, it.mimeType) } else null
        }
    }
}
