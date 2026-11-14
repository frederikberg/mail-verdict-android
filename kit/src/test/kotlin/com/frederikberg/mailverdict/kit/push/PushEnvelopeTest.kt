package com.frederikberg.mailverdict.kit.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/** Opens the server's own test vector (`push_envelope_v1.json`), so the two implementations are held to the same bytes. */
class PushEnvelopeTest {
    private val vector =
        Json
            .parseToJsonElement(javaClass.getResource("/push_envelope_v1.json")!!.readText())
            .jsonObject
            .mapValues { it.value.jsonPrimitive.content }

    private val key = Base64.getDecoder().decode(vector.getValue("content_key_base64"))
    private val installation = vector.getValue("installation_id")
    private val blob = vector.getValue("blob")

    @Test
    fun theServersTestVectorOpensToItsPlaintext() {
        assertEquals(vector.getValue("plaintext"), PushEnvelope.open(blob, key, installation).decodeToString())
    }

    @Test
    fun theInstallationIdIsCaseInsensitive() {
        PushEnvelope.open(blob, key, installation.uppercase())
    }

    @Test
    fun aBlobSealedForAnotherInstallationDoesNotOpen() {
        assertThrows(PushEnvelopeException::class.java) {
            PushEnvelope.open(blob, key, "00000000-0000-0000-0000-000000000000")
        }
    }

    @Test
    fun anotherKeyDoesNotOpenIt() {
        assertThrows(PushEnvelopeException::class.java) { PushEnvelope.open(blob, ByteArray(32), installation) }
    }

    @Test
    fun anAlteredBlobDoesNotOpen() {
        val raw = Base64.getDecoder().decode(blob)
        raw[raw.size - 1] = (raw.last().toInt() xor 1).toByte()

        assertThrows(PushEnvelopeException::class.java) {
            PushEnvelope.open(Base64.getEncoder().encodeToString(raw), key, installation)
        }
    }

    @Test
    fun garbageAndOtherVersionsAreRefused() {
        assertThrows(PushEnvelopeException::class.java) { PushEnvelope.open("not base64 !!", key, installation) }
        assertThrows(PushEnvelopeException::class.java) { PushEnvelope.open("AAAA", key, installation) }
        val raw = Base64.getDecoder().decode(blob)
        raw[0] = 2
        assertThrows(PushEnvelopeException::class.java) {
            PushEnvelope.open(Base64.getEncoder().encodeToString(raw), key, installation)
        }
    }

    @Test
    fun theAlertInsideBecomesANotification() {
        val alert = NativeAlert.parse(PushEnvelope.open(blob, key, installation))
        val payload = alert.toPayload()

        assertEquals("Grüße aus Aachen", payload.title)
        assertEquals("Anna Schmidt <anna@example.com>", payload.body)
        assertEquals("8d1e6c52-4b0a-4f3e-a2c9-5e7f10b2d3a4", payload.tag)
        assertEquals("c4a2f0e8-9d7b-4c5a-8e3f-1b2c3d4e5f60", payload.messageId)
        assertEquals(listOf("19a8b7c6-d5e4-4f3a-8b2c-1d0e9f8a7b6c"), alert.resolved)
    }

    @Test
    fun plaintextThatIsNotAnAlertIsRefused() {
        assertThrows(PushEnvelopeException::class.java) { NativeAlert.parse("[]".toByteArray()) }
    }
}
