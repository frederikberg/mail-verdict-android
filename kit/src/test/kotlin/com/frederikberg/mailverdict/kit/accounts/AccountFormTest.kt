package com.frederikberg.mailverdict.kit.accounts

import com.frederikberg.mailverdict.contract.AccountCreateRequest
import com.frederikberg.mailverdict.contract.AccountUpdateRequest
import com.frederikberg.mailverdict.contract.ContractJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountFormTest {
    private fun created(input: AccountFormInput): AccountCreateRequest =
        (AccountForm.buildCreate(input) as AccountFormResult.Valid).request

    private fun problems(result: AccountFormResult<*>): Map<AccountFormField, AccountFormProblem> =
        (result as AccountFormResult.Invalid).problems

    private fun body(request: AccountCreateRequest): JsonObject =
        Json.parseToJsonElement(ContractJson.encodeToString(AccountCreateRequest.serializer(), request)).jsonObject

    private fun body(request: AccountUpdateRequest): JsonObject =
        Json.parseToJsonElement(ContractJson.encodeToString(AccountUpdateRequest.serializer(), request)).jsonObject

    @Test
    fun gmailSignsInWithTheFullAddressAndTheSameAppPasswordEverywhere() {
        val request =
            created(
                AccountFormInput(
                    name = "Private",
                    presetId = "gmail",
                    email = "  alex@gmail.com ",
                    appPassword = "abcd efgh ijkl mnop",
                ),
            )

        assertEquals("imap.gmail.com", request.imapHost)
        assertEquals(993, request.imapPort)
        assertEquals("alex@gmail.com", request.imapUser)
        assertEquals("abcd efgh ijkl mnop", request.imapPassword)
        assertEquals("smtp.gmail.com", request.smtpHost)
        assertEquals(465, request.smtpPort)
        assertEquals("alex@gmail.com", request.smtpUser)
        assertEquals("abcd efgh ijkl mnop", request.smtpPassword)
    }

    @Test
    fun icloudSignsInToImapWithTheNameBeforeTheAtAndToSmtpWithTheFullAddress() {
        val request =
            created(
                AccountFormInput(
                    name = "Cloud",
                    presetId = "icloud",
                    email = "alex@icloud.com",
                    appPassword = "xxxx-xxxx-xxxx-xxxx",
                ),
            )

        assertEquals("imap.mail.me.com", request.imapHost)
        assertEquals(993, request.imapPort)
        assertEquals("alex", request.imapUser)
        assertEquals("smtp.mail.me.com", request.smtpHost)
        assertEquals(587, request.smtpPort)
        assertEquals("alex@icloud.com", request.smtpUser)
    }

    @Test
    fun theBodyNamesThePortAndBothSwitchesEvenAtTheirDefaults() {
        val json =
            body(created(AccountFormInput(name = "Private", presetId = "gmail", email = "a@b.c", appPassword = "pw")))

        assertEquals(JsonPrimitive(993), json["imap_port"])
        assertEquals(JsonPrimitive(false), json["spam_enabled"])
        assertEquals(JsonPrimitive(false), json["orders_enabled"])
        assertFalse("nothing is sent for a retention that was left off", "trash_retention_days" in json)
    }

    @Test
    fun aPresetNeedsAnAddressThatLooksLikeOneAndAPassword() {
        val result =
            AccountForm.buildCreate(AccountFormInput(name = "X", presetId = "gmail", email = "alex", appPassword = ""))

        assertEquals(
            mapOf(
                AccountFormField.Email to AccountFormProblem.Invalid,
                AccountFormField.AppPassword to AccountFormProblem.Missing,
            ),
            problems(result),
        )
    }

    @Test
    fun anotherServerTakesItsConnectionFromTheForm() {
        val request =
            created(
                AccountFormInput(
                    name = "Work",
                    imapHost = "imap.example.com",
                    imapPort = "143",
                    imapUser = "alex",
                    imapPassword = "pw",
                    smtpHost = "smtp.example.com",
                    smtpPort = "587",
                    smtpUser = "alex",
                    smtpPassword = "pw2",
                    spamEnabled = true,
                    trashRetentionDays = "30",
                ),
            )

        assertEquals("imap.example.com", request.imapHost)
        assertEquals(143, request.imapPort)
        assertEquals("pw2", request.smtpPassword)
        assertEquals(587, request.smtpPort)
        assertTrue(request.spamEnabled)
        assertEquals(30, request.trashRetentionDays)
        assertEquals(null, request.junkRetentionDays)
    }

    @Test
    fun anotherServerNeedsAHostAnAccountNameAndAUsablePort() {
        val result = AccountForm.buildCreate(AccountFormInput(imapPort = "99999", smtpPort = "abc"))

        assertEquals(
            setOf(
                AccountFormField.Name,
                AccountFormField.ImapHost,
                AccountFormField.ImapUser,
                AccountFormField.ImapPort,
                AccountFormField.SmtpPort,
            ),
            problems(result).keys,
        )
    }

    @Test
    fun aRetentionBelowOneDayIsRefused() {
        val result =
            AccountForm.buildCreate(
                AccountFormInput(
                    name = "X",
                    imapHost = "h",
                    imapUser = "u",
                    trashRetentionDays = "0",
                    junkRetentionDays = "-3",
                ),
            )

        assertEquals(
            setOf(AccountFormField.TrashRetention, AccountFormField.JunkRetention),
            problems(result).keys,
        )
    }

    @Test
    fun anEditClearsABlankRetentionWithAnExplicitNullAndSetsAFilledOne() {
        val request =
            (
                AccountForm.buildUpdate(
                    AccountFormInput(name = "Private", trashRetentionDays = "", junkRetentionDays = "14"),
                ) as AccountFormResult.Valid
            ).request
        val json = body(request)

        assertEquals(JsonNull, json["trash_retention_days"])
        assertEquals(JsonPrimitive(14), json["junk_retention_days"])
        assertEquals(JsonNull, json["glacier_auto_days"])
    }

    @Test
    fun anEditLeavesAlonePasswordsAndServerFieldsLeftBlank() {
        val request =
            (AccountForm.buildUpdate(AccountFormInput(name = "Private")) as AccountFormResult.Valid).request
        val json = body(request)

        for (key in listOf("imap_password", "smtp_password", "smtp_host", "smtp_port", "smtp_user", "imap_host")) {
            assertFalse("$key must not be sent", key in json)
        }
    }

    @Test
    fun anEditNeverCarriesTheInsertOnlyImapIdentity() {
        val json =
            body(
                AccountForm
                    .buildUpdate(
                        AccountFormInput(name = "Private"),
                    ).let { (it as AccountFormResult.Valid).request },
            )

        assertFalse("imap_host" in json || "imap_port" in json || "imap_user" in json)
    }
}
