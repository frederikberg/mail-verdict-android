package com.frederikberg.mailverdict.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.kit.settings.SettingControl
import com.frederikberg.mailverdict.kit.settings.SettingField
import com.frederikberg.mailverdict.kit.settings.SettingsSection
import com.frederikberg.mailverdict.kit.settings.UserSettingsState
import com.frederikberg.mailverdict.ui.FormTextField
import com.frederikberg.mailverdict.ui.SectionTitle
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

object UserSettingsTags {
    const val ERROR = "usersettings-error"
    const val EMPTY = "usersettings-empty"

    fun field(id: String) = "setting:$id"

    fun option(id: String, value: Int) = "setting-option:$id:$value"
}

class UserSettingsCallbacks(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onSet: (SettingField, JsonElement) -> Unit,
)

/**
 * The person's own settings, grouped as on the web. Each change is written at once: a switch and a
 * choice on the tap, a number or a text when the field loses focus or the keyboard says done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsScreen(
    state: UserSettingsState,
    sections: List<SettingsSection>,
    snackbar: SnackbarHostState,
    callbacks: UserSettingsCallbacks,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usersettings_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            state.errorMessage?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag(UserSettingsTags.ERROR),
                ) {
                    Text(
                        stringResource(R.string.usersettings_error, it),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = callbacks.onRetry) { Text(stringResource(R.string.usersettings_retry)) }
                }
            }
            if (state.isLoading && sections.isEmpty()) CircularProgressIndicator()
            if (state.hasLoaded && sections.isEmpty()) {
                Text(stringResource(R.string.usersettings_empty), modifier = Modifier.testTag(UserSettingsTags.EMPTY))
            }
            sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(section.title)
                    section.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    section.fields.forEach { field -> FieldRow(field, callbacks.onSet) }
                }
            }
        }
    }
}

@Composable
private fun FieldRow(
    field: SettingField,
    onSet: (SettingField, JsonElement) -> Unit,
) {
    val spec = field.spec
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val control = spec.control) {
            SettingControl.Switch ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(spec.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = field.asBoolean,
                        onCheckedChange = { onSet(field, JsonPrimitive(it)) },
                        modifier = Modifier.testTag(UserSettingsTags.field(spec.id)),
                    )
                }
            is SettingControl.Select -> {
                Text(spec.label, style = MaterialTheme.typography.bodyLarge)
                OptionPicker(field, control) { onSet(field, JsonPrimitive(it)) }
            }
            is SettingControl.Number -> {
                CommittedField(
                    field = field,
                    stored = field.asInt?.toString().orEmpty(),
                    keyboardType = KeyboardType.Number,
                    suffix = control.suffix,
                ) { text ->
                    val number = text.trim().toIntOrNull()
                    if (number != null && (control.min == null || number >= control.min!!)) {
                        onSet(field, JsonPrimitive(number))
                    }
                }
            }
            is SettingControl.Text -> {
                CommittedField(
                    field = field,
                    stored = field.asText,
                    keyboardType = KeyboardType.Text,
                    suffix = null,
                ) { text ->
                    onSet(field, if (control.nullable && text.isEmpty()) JsonNull else JsonPrimitive(text))
                }
            }
        }
        spec.help?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OptionPicker(
    field: SettingField,
    control: SettingControl.Select,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = field.asInt
    // A stored value the list does not name stays selectable as itself.
    val label = control.options.firstOrNull { it.value == current }?.label ?: current?.toString().orEmpty()
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag(UserSettingsTags.field(field.spec.id))) {
            Text(label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            control.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        open = false
                        onPick(option.value)
                    },
                    modifier = Modifier.testTag(UserSettingsTags.option(field.spec.id, option.value)),
                )
            }
        }
    }
}

/**
 * A text box that keeps what is typed while it has focus and writes it when focus leaves. The stored
 * value replaces the text only when it changes from outside, never under the cursor.
 */
@Composable
private fun CommittedField(
    field: SettingField,
    stored: String,
    keyboardType: KeyboardType,
    suffix: String?,
    onCommit: (String) -> Unit,
) {
    var text by remember(field.spec.id, stored) { mutableStateOf(stored) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormTextField(
            value = text,
            onValueChange = { text = it },
            label = field.spec.label,
            tag = UserSettingsTags.field(field.spec.id),
            keyboardType = keyboardType,
            onDone = { focusManager.clearFocus() },
            modifier =
                Modifier
                    .weight(1f)
                    .onFocusChanged {
                        if (focused && !it.isFocused && text != stored) onCommit(text)
                        focused = it.isFocused
                    },
        )
        suffix?.let { Text(if (it == "days") stringResource(R.string.usersettings_suffix_days) else it) }
    }
}
