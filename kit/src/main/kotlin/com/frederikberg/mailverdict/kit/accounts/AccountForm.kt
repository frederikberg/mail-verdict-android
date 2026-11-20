package com.frederikberg.mailverdict.kit.accounts

import com.frederikberg.mailverdict.contract.AccountCreateRequest
import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.contract.AccountUpdateRequest

/** The fields a form can reject. */
enum class AccountFormField {
    Name,
    Email,
    AppPassword,
    ImapHost,
    ImapPort,
    ImapUser,
    SmtpPort,
    TrashRetention,
    JunkRetention,
    GlacierAutoDays,
}

/** Why a field was rejected: nothing in it, or something that is not a usable value. */
enum class AccountFormProblem {
    Missing,
    Invalid,
}

/** What building a request from the form came to. */
sealed interface AccountFormResult<out T> {
    data class Valid<T>(
        val request: T,
    ) : AccountFormResult<T>

    data class Invalid(
        val problems: Map<AccountFormField, AccountFormProblem>,
    ) : AccountFormResult<Nothing>
}

/**
 * Everything the add and edit forms hold, as the text fields hold it. Parsing and validation happen
 * once, in [AccountForm], not per keystroke. [presetId] null is "Other server".
 */
data class AccountFormInput(
    val name: String = "",
    val presetId: String? = null,
    val email: String = "",
    val appPassword: String = "",
    val imapHost: String = "",
    val imapPort: String = AccountCreateRequest.DEFAULT_IMAP_PORT.toString(),
    val imapUser: String = "",
    val imapPassword: String = "",
    val smtpHost: String = "",
    val smtpPort: String = "",
    val smtpUser: String = "",
    val smtpPassword: String = "",
    val spamEnabled: Boolean = false,
    val ordersEnabled: Boolean = false,
    /** Empty is off. */
    val trashRetentionDays: String = "",
    val junkRetentionDays: String = "",
    /** Only read when editing: a glacier needs the account to exist first. */
    val glacierEnabled: Boolean = false,
    val glacierAutoDays: String = "",
) {
    val preset: AccountPreset? get() = AccountPresets.byId(presetId)

    companion object {
        /** The form of an edit: the IMAP identity is shown but never sent back, it is insert-only. */
        fun of(account: AccountResponse) =
            AccountFormInput(
                name = account.name,
                imapHost = account.imapHost,
                imapPort = account.imapPort.toString(),
                imapUser = account.imapUser,
                smtpHost = account.smtpHost.orEmpty(),
                smtpPort = account.smtpPort?.toString().orEmpty(),
                smtpUser = account.smtpUser.orEmpty(),
                spamEnabled = account.spamEnabled,
                ordersEnabled = account.ordersEnabled,
                trashRetentionDays = account.trashRetentionDays?.toString().orEmpty(),
                junkRetentionDays = account.junkRetentionDays?.toString().orEmpty(),
                glacierEnabled = account.glacierEnabled,
                glacierAutoDays = account.glacierAutoDays?.toString().orEmpty(),
            )
    }
}

/** Whether [value] has something on both sides of an `@` and no space: enough to catch a typo, not a validator. */
internal fun looksLikeAddress(value: String): Boolean {
    val at = value.indexOf('@')
    return at > 0 && at < value.length - 1 && ' ' !in value
}

/** Turns what the person typed into the request the server takes. */
object AccountForm {
    fun buildCreate(input: AccountFormInput): AccountFormResult<AccountCreateRequest> {
        val problems = linkedMapOf<AccountFormField, AccountFormProblem>()
        val name = required(input.name, AccountFormField.Name, problems)
        val trash = optionalDays(input.trashRetentionDays, AccountFormField.TrashRetention, problems)
        val junk = optionalDays(input.junkRetentionDays, AccountFormField.JunkRetention, problems)
        val preset = input.preset
        val request =
            if (preset != null) {
                val address = input.email.trim()
                if (address.isEmpty()) {
                    problems[AccountFormField.Email] = AccountFormProblem.Missing
                } else if (!looksLikeAddress(address)) {
                    problems[AccountFormField.Email] = AccountFormProblem.Invalid
                }
                if (input.appPassword.isEmpty()) problems[AccountFormField.AppPassword] = AccountFormProblem.Missing
                AccountCreateRequest(
                    name = name,
                    imapHost = preset.imapHost,
                    imapPort = preset.imapPort,
                    imapUser = preset.imapUserRule.userFor(address),
                    imapPassword = input.appPassword,
                    smtpHost = preset.smtpHost,
                    smtpPort = preset.smtpPort,
                    smtpUser = address,
                    smtpPassword = input.appPassword,
                    spamEnabled = input.spamEnabled,
                    ordersEnabled = input.ordersEnabled,
                    trashRetentionDays = trash,
                    junkRetentionDays = junk,
                )
            } else {
                val host = required(input.imapHost, AccountFormField.ImapHost, problems)
                val user = required(input.imapUser, AccountFormField.ImapUser, problems)
                val port = port(input.imapPort, AccountFormField.ImapPort, problems, required = true)
                val smtpPort = port(input.smtpPort, AccountFormField.SmtpPort, problems, required = false)
                AccountCreateRequest(
                    name = name,
                    imapHost = host,
                    imapPort = port ?: AccountCreateRequest.DEFAULT_IMAP_PORT,
                    imapUser = user,
                    imapPassword = blankToNull(input.imapPassword),
                    smtpHost = blankToNull(input.smtpHost),
                    smtpPort = smtpPort,
                    smtpUser = blankToNull(input.smtpUser),
                    smtpPassword = blankToNull(input.smtpPassword),
                    spamEnabled = input.spamEnabled,
                    ordersEnabled = input.ordersEnabled,
                    trashRetentionDays = trash,
                    junkRetentionDays = junk,
                )
            }
        return if (problems.isEmpty()) AccountFormResult.Valid(request) else AccountFormResult.Invalid(problems)
    }

    /**
     * The three day counts are always sent: a blank one clears the setting, which the server reads only
     * from an explicit null. A blank password or SMTP field is left out, so it stays as it is.
     */
    fun buildUpdate(input: AccountFormInput): AccountFormResult<AccountUpdateRequest> {
        val problems = linkedMapOf<AccountFormField, AccountFormProblem>()
        val name = required(input.name, AccountFormField.Name, problems)
        val smtpPort = port(input.smtpPort, AccountFormField.SmtpPort, problems, required = false)
        val trash = optionalDays(input.trashRetentionDays, AccountFormField.TrashRetention, problems)
        val junk = optionalDays(input.junkRetentionDays, AccountFormField.JunkRetention, problems)
        val glacier = optionalDays(input.glacierAutoDays, AccountFormField.GlacierAutoDays, problems)
        if (problems.isNotEmpty()) return AccountFormResult.Invalid(problems)
        return AccountFormResult.Valid(
            AccountUpdateRequest(
                name = name,
                imapPassword = blankToNull(input.imapPassword),
                smtpHost = blankToNull(input.smtpHost),
                smtpPort = smtpPort,
                smtpUser = blankToNull(input.smtpUser),
                smtpPassword = blankToNull(input.smtpPassword),
                spamEnabled = input.spamEnabled,
                ordersEnabled = input.ordersEnabled,
                trashRetentionDays = AccountUpdateRequest.days(trash),
                junkRetentionDays = AccountUpdateRequest.days(junk),
                glacierEnabled = input.glacierEnabled,
                glacierAutoDays = AccountUpdateRequest.days(glacier),
            ),
        )
    }

    private fun blankToNull(value: String): String? = value.trim().ifEmpty { null }

    private fun required(
        value: String,
        field: AccountFormField,
        problems: MutableMap<AccountFormField, AccountFormProblem>,
    ): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) problems[field] = AccountFormProblem.Missing
        return trimmed
    }

    private fun port(
        value: String,
        field: AccountFormField,
        problems: MutableMap<AccountFormField, AccountFormProblem>,
        required: Boolean,
    ): Int? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            if (required) problems[field] = AccountFormProblem.Missing
            return null
        }
        val port = trimmed.toIntOrNull()
        if (port == null || port !in 1..MAX_PORT) {
            problems[field] = AccountFormProblem.Invalid
            return null
        }
        return port
    }

    /** Blank is off; below one the server's own sweep would clear a folder at once or never run. */
    private fun optionalDays(
        value: String,
        field: AccountFormField,
        problems: MutableMap<AccountFormField, AccountFormProblem>,
    ): Int? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val days = trimmed.toIntOrNull()
        if (days == null || days < 1) {
            problems[field] = AccountFormProblem.Invalid
            return null
        }
        return days
    }

    private const val MAX_PORT = 65535
}
