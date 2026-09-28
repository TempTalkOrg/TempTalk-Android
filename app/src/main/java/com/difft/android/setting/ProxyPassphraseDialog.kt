package com.difft.android.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.compose.input.ClearMode
import com.difft.android.base.ui.compose.input.DifftClearableTextField
import com.difft.android.base.ui.compose.input.DifftInputSurface
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.MessageDialog

/** Test handle for the in-flight indicator; the glyph carries no text of its own. */
const val PROXY_PASSPHRASE_IN_FLIGHT_TEST_TAG = "proxy_passphrase_in_flight"

/**
 * Collects the out-of-band passphrase used to decrypt an encrypted proxy link.
 *
 * Declarative on purpose: [isSaving] and [isError] must keep updating while the dialog is open, and
 * `ComposeDialogManager.showMessageDialog`'s imperative form would snapshot them once. Confirming
 * does not close the dialog — the host closes it when the ViewModel reports the outcome.
 *
 * The passphrase itself is held in plain `remember`, not `rememberSaveable`: it must not reach the
 * saved-instance bundle.
 */
@Composable
fun ProxyPassphraseDialog(
    isVisible: Boolean,
    isError: Boolean,
    isSaving: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember(isVisible) { mutableStateOf("") }
    val canConfirm = passphrase.isNotBlank() && !isSaving

    MessageDialog(
        isVisible = isVisible,
        title = stringResource(R.string.proxy_passphrase_title),
        confirmText = stringResource(R.string.proxy_passphrase_confirm),
        cancelText = stringResource(R.string.proxy_passphrase_cancel),
        cancelable = !isSaving,
        cancelEnabled = !isSaving,
        confirmEnabled = canConfirm,
        onConfirm = { onConfirm(passphrase) },
        onCancel = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column {
            Text(
                text = stringResource(R.string.proxy_passphrase_desc),
                style = DifftTheme.typography.bodyMedium,
                color = DifftTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            DifftClearableTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                onClear = { passphrase = "" },
                hint = stringResource(R.string.proxy_passphrase_hint),
                clearMode = ClearMode.WhileEditing,
                enabled = !isSaving,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                onImeAction = { if (canConfirm) onConfirm(passphrase) },
                surface = DifftInputSurface.Popup,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isError) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.proxy_passphrase_wrong),
                    style = DifftSettingsDefaults.CaptionTextStyle,
                    color = DifftTheme.colors.textError,
                )
            }
            if (isSaving) {
                Spacer(Modifier.height(8.dp))
                // In-flight feedback lives in the content, not the confirm button: the global wait
                // dialog is attached to the Activity's content view and would render beneath this
                // dialog's own window.
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(14.dp)
                        .testTag(PROXY_PASSPHRASE_IN_FLIGHT_TEST_TAG),
                    strokeWidth = 2.dp,
                    color = DifftTheme.colors.primary,
                )
            }
        }
    }
}
