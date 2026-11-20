package com.frederikberg.mailverdict.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.contract.AccountCreateRequest
import com.frederikberg.mailverdict.contract.AccountUpdateRequest
import com.frederikberg.mailverdict.kit.accounts.AccountForm
import com.frederikberg.mailverdict.kit.accounts.AccountFormField
import com.frederikberg.mailverdict.kit.accounts.AccountFormInput
import com.frederikberg.mailverdict.kit.accounts.AccountFormProblem
import com.frederikberg.mailverdict.kit.accounts.AccountFormResult
import com.frederikberg.mailverdict.kit.accounts.AccountPreset
import com.frederikberg.mailverdict.kit.accounts.AccountPresets
import com.frederikberg.mailverdict.ui.FormTextField

object AccountFormTags {
    const val NAME = "account-form-name"
    const val OTHER = "account-form-other"
    const val EMAIL = "account-form-email"
    const val APP_PASSWORD = "account-form-app-password"
    const val IMAP_HOST = "account-form-imap-host"
    const val IMAP_PORT = "account-form-imap-port"
    const val IMAP_USER = "account-form-imap-user"
    const val IMAP_PASSWORD = "account-form-imap-password"
    const val SMTP_HOST = "account-form-smtp-host"
    const val SMTP_PORT = "account-form-smtp-port"
    const val SMTP_USER = "account-form-smtp-user"
    const val SMTP_PASSWORD = "account-form-smtp-password"
    const val SPAM = "account-form-spam"
    const val ORDERS = "account-form-orders"
    const val TRASH = "account-form-trash"
    const val JUNK = "account-form-junk"
    const val GLACIER = "account-form-glacier"
    const val GLACIER_DAYS = "account-form-glacier-days"
    const val SUBMIT = "account-form-submit"
    const val ERROR = "account-form-error"
    const val APP_PASSWORD_LINK = "account-form-app-password-link"

    fun preset(id: String) = "account-form-preset:$id"
}

/**
 * The form keeps its text across rotation but never a password: those are typed again after the
 * process was recreated rather than written into saved state.
 */
private val InputSaver: Saver<AccountFormInput, Any> =
    Saver(
        save = {
            listOf(
                it.name,
                it.presetId.orEmpty(),
                it.email,
                it.imapHost,
                it.imapPort,
                it.imapUser,
                it.smtpHost,
                it.smtpPort,
                it.smtpUser,
                it.spamEnabled,
                it.ordersEnabled,
                it.trashRetentionDays,
                it.junkRetentionDays,
                it.glacierEnabled,
                it.glacierAutoDays,
            )
        },
        restore = {
            @Suppress("UNCHECKED_CAST")
            val v = it as List<Any>
            AccountFormInput(
                name = v[0] as String,
                presetId = (v[1] as String).ifEmpty { null },
                email = v[2] as String,
                imapHost = v[3] as String,
                imapPort = v[4] as String,
                imapUser = v[5] as String,
                smtpHost = v[6] as String,
                smtpPort = v[7] as String,
                smtpUser = v[8] as String,
                spamEnabled = v[9] as Boolean,
                ordersEnabled = v[10] as Boolean,
                trashRetentionDays = v[11] as String,
                junkRetentionDays = v[12] as String,
                glacierEnabled = v[13] as Boolean,
                glacierAutoDays = v[14] as String,
            )
        },
    )

/**
 * Adding an account (a provider preset, or any server) and editing one. Nothing is sent until the form
 * is valid; what the server refuses comes back as [serverError] under the button, with the text kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountFormScreen(
    editing: Boolean,
    initial: AccountFormInput,
    submitting: Boolean,
    serverError: String?,
    onBack: () -> Unit,
    onCreate: (AccountCreateRequest) -> Unit,
    onUpdate: (AccountUpdateRequest) -> Unit,
) {
    var input by rememberSaveable(stateSaver = InputSaver) { mutableStateOf(initial) }
    var problems by remember { mutableStateOf(emptyMap<AccountFormField, AccountFormProblem>()) }
    val preset = input.preset

    fun submit() {
        if (editing) {
            when (val result = AccountForm.buildUpdate(input)) {
                is AccountFormResult.Valid -> onUpdate(result.request)
                is AccountFormResult.Invalid -> problems = result.problems
            }
        } else {
            when (val result = AccountForm.buildCreate(input)) {
                is AccountFormResult.Valid -> onCreate(result.request)
                is AccountFormResult.Invalid -> problems = result.problems
            }
        }
    }

    @Composable
    fun problemText(field: AccountFormField): String? =
        when (problems[field]) {
            null -> null
            AccountFormProblem.Missing -> stringResource(R.string.account_form_missing)
            AccountFormProblem.Invalid ->
                when (field) {
                    AccountFormField.Email -> stringResource(R.string.account_form_invalid_email)
                    AccountFormField.ImapPort, AccountFormField.SmtpPort ->
                        stringResource(
                            R.string.account_form_invalid_port,
                        )
                    else -> stringResource(R.string.account_form_invalid_days)
                }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (editing) R.string.account_form_edit_title else R.string.account_form_add_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FormTextField(
                input.name,
                { input = input.copy(name = it) },
                stringResource(R.string.account_form_name),
                AccountFormTags.NAME,
                error = problemText(AccountFormField.Name),
            )
            if (!editing) {
                ProviderChoice(input.presetId) { input = input.copy(presetId = it) }
            }
            if (preset != null && !editing) {
                PresetCard(preset)
                FormTextField(
                    input.email,
                    { input = input.copy(email = it) },
                    stringResource(R.string.account_form_email),
                    AccountFormTags.EMAIL,
                    error = problemText(AccountFormField.Email),
                    keyboardType = KeyboardType.Email,
                )
                FormTextField(
                    input.appPassword,
                    { input = input.copy(appPassword = it) },
                    stringResource(R.string.account_form_app_password),
                    AccountFormTags.APP_PASSWORD,
                    error = problemText(AccountFormField.AppPassword),
                    secret = true,
                )
            } else {
                ServerFields(editing, input, { problemText(it) }) { input = it }
            }
            SwitchLine(R.string.account_form_spam, input.spamEnabled, AccountFormTags.SPAM) {
                input = input.copy(spamEnabled = it)
            }
            SwitchLine(R.string.account_form_orders, input.ordersEnabled, AccountFormTags.ORDERS) {
                input = input.copy(ordersEnabled = it)
            }
            FormTextField(
                input.trashRetentionDays,
                { input = input.copy(trashRetentionDays = it) },
                stringResource(R.string.account_form_trash_retention),
                AccountFormTags.TRASH,
                error = problemText(AccountFormField.TrashRetention),
                supporting = stringResource(R.string.account_form_retention_off),
                keyboardType = KeyboardType.Number,
            )
            FormTextField(
                input.junkRetentionDays,
                { input = input.copy(junkRetentionDays = it) },
                stringResource(R.string.account_form_junk_retention),
                AccountFormTags.JUNK,
                error = problemText(AccountFormField.JunkRetention),
                supporting = stringResource(R.string.account_form_retention_off),
                keyboardType = KeyboardType.Number,
            )
            if (editing) {
                SwitchLine(R.string.account_form_glacier, input.glacierEnabled, AccountFormTags.GLACIER) {
                    input = input.copy(glacierEnabled = it)
                }
                Text(
                    stringResource(R.string.account_form_glacier_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FormTextField(
                    input.glacierAutoDays,
                    { input = input.copy(glacierAutoDays = it) },
                    stringResource(R.string.account_form_glacier_days),
                    AccountFormTags.GLACIER_DAYS,
                    error = problemText(AccountFormField.GlacierAutoDays),
                    supporting = stringResource(R.string.account_form_glacier_days_off),
                    keyboardType = KeyboardType.Number,
                )
            }
            serverError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(AccountFormTags.ERROR),
                )
            }
            Button(
                onClick = ::submit,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth().testTag(AccountFormTags.SUBMIT),
            ) {
                if (submitting) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                }
                Text(stringResource(if (editing) R.string.account_form_save else R.string.account_form_create))
            }
        }
    }
}

@Composable
private fun ProviderChoice(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.account_form_provider), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountPresets.all.forEach { preset ->
                FilterChip(
                    selected = selected == preset.id,
                    onClick = { onSelect(preset.id) },
                    label = { Text(preset.label) },
                    modifier = Modifier.testTag(AccountFormTags.preset(preset.id)),
                )
            }
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.account_form_other_server)) },
                modifier = Modifier.testTag(AccountFormTags.OTHER),
            )
        }
    }
}

@Composable
private fun PresetCard(preset: AccountPreset) {
    val links = LocalUriHandler.current
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.account_form_preset_intro, preset.label, preset.prerequisite),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = { links.openUri(preset.appPasswordLink) },
                modifier = Modifier.testTag(AccountFormTags.APP_PASSWORD_LINK),
            ) { Text(preset.appPasswordLinkLabel) }
            preset.notes.forEach { note ->
                Text("• $note", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(
                    R.string.account_form_preset_servers,
                    preset.imapHost,
                    preset.imapPort,
                    preset.smtpHost,
                    preset.smtpPort,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ServerFields(
    editing: Boolean,
    input: AccountFormInput,
    problem: @Composable (AccountFormField) -> String?,
    onChange: (AccountFormInput) -> Unit,
) {
    if (editing) {
        Text(
            stringResource(R.string.account_form_imap_fixed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FormTextField(
        input.imapHost,
        { onChange(input.copy(imapHost = it)) },
        stringResource(R.string.account_form_imap_host),
        AccountFormTags.IMAP_HOST,
        error = problem(AccountFormField.ImapHost),
        enabled = !editing,
        keyboardType = KeyboardType.Uri,
    )
    FormTextField(
        input.imapPort,
        { onChange(input.copy(imapPort = it)) },
        stringResource(R.string.account_form_imap_port),
        AccountFormTags.IMAP_PORT,
        error = problem(AccountFormField.ImapPort),
        enabled = !editing,
        keyboardType = KeyboardType.Number,
    )
    FormTextField(
        input.imapUser,
        { onChange(input.copy(imapUser = it)) },
        stringResource(R.string.account_form_imap_user),
        AccountFormTags.IMAP_USER,
        error = problem(AccountFormField.ImapUser),
        enabled = !editing,
        keyboardType = KeyboardType.Email,
    )
    FormTextField(
        input.imapPassword,
        { onChange(input.copy(imapPassword = it)) },
        stringResource(R.string.account_form_imap_password),
        AccountFormTags.IMAP_PASSWORD,
        supporting = if (editing) stringResource(R.string.account_form_unchanged) else null,
        secret = true,
    )
    FormTextField(
        input.smtpHost,
        { onChange(input.copy(smtpHost = it)) },
        stringResource(R.string.account_form_smtp_host),
        AccountFormTags.SMTP_HOST,
        keyboardType = KeyboardType.Uri,
    )
    FormTextField(
        input.smtpPort,
        { onChange(input.copy(smtpPort = it)) },
        stringResource(R.string.account_form_smtp_port),
        AccountFormTags.SMTP_PORT,
        error = problem(AccountFormField.SmtpPort),
        keyboardType = KeyboardType.Number,
    )
    FormTextField(
        input.smtpUser,
        { onChange(input.copy(smtpUser = it)) },
        stringResource(R.string.account_form_smtp_user),
        AccountFormTags.SMTP_USER,
        keyboardType = KeyboardType.Email,
    )
    FormTextField(
        input.smtpPassword,
        { onChange(input.copy(smtpPassword = it)) },
        stringResource(R.string.account_form_smtp_password),
        AccountFormTags.SMTP_PASSWORD,
        supporting = if (editing) stringResource(R.string.account_form_unchanged) else null,
        secret = true,
    )
}

@Composable
internal fun SwitchLine(
    label: Int,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}
