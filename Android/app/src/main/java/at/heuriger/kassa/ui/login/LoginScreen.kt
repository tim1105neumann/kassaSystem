package at.heuriger.kassa.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewLightDark
import at.heuriger.kassa.R
import at.heuriger.kassa.ui.components.KassaFilledButton
import at.heuriger.kassa.ui.components.KassaSectionHeader
import at.heuriger.kassa.ui.components.KassaTextField
import at.heuriger.kassa.ui.theme.KassaTheme

/**
 * Anmeldung. Spiegel von `LoginView.swift`.
 *
 * **Abweichung von der Vorlage, bewusst.** iOS bindet die Felder direkt an
 * `AppSettings` und schreibt bei jedem Tastendruck durch. Hier liegen sie in
 * lokalem Zustand und werden erst beim Absenden gespeichert: jeder Tastendruck
 * waere sonst ein DataStore-Schreibvorgang, und ein halb getippter Server-Name
 * hat in den Einstellungen nichts verloren. Gespeichert wird vor dem
 * Netzaufruf, weil [at.heuriger.kassa.sync.SessionController.login] die Adresse
 * aus den Einstellungen liest und nicht als Parameter nimmt.
 */
@Composable
fun LoginScreen(
    initialServerUrl: String,
    initialDeviceName: String,
    isLoggingIn: Boolean,
    loginError: String?,
    onSubmit: (serverUrl: String, deviceName: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens

    // `rememberSaveable`: Feldinhalte ueberleben einen Prozesstod im
    // Hintergrund. Das Passwort ist bewusst *nicht* dabei.
    var serverUrl by rememberSaveable(initialServerUrl) { mutableStateOf(initialServerUrl) }
    var deviceName by rememberSaveable(initialDeviceName) { mutableStateOf(initialDeviceName) }
    var password by rememberSaveable { mutableStateOf("") }

    val canSubmit = password.isNotEmpty() && !isLoggingIn

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.groupedBackground)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column {
            KassaSectionHeader(stringResource(R.string.login_section_server))
            FormGroup {
                KassaTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    placeholder = stringResource(R.string.login_server_placeholder),
                    label = stringResource(R.string.login_section_server),
                    containerColor = Color.Transparent,
                    // URL-Tastatur, keine Autokorrektur, keine Grossschreibung —
                    // sonst macht die Tastatur aus „kassa" ein „Kassa" und der
                    // Host stimmt nicht mehr.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next,
                    ),
                )
                FormDivider()
                KassaTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    placeholder = stringResource(R.string.login_device_name),
                    label = stringResource(R.string.login_device_name),
                    containerColor = Color.Transparent,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KassaSectionHeader(stringResource(R.string.login_section_credentials))
            FormGroup {
                KassaTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(R.string.login_password),
                    label = stringResource(R.string.login_password),
                    containerColor = Color.Transparent,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            KassaFilledButton(
                text = stringResource(R.string.login_submit),
                enabled = canSubmit,
                onClick = { onSubmit(serverUrl.trim(), deviceName.trim(), password) },
                modifier = Modifier.padding(horizontal = dimens.screenPadding),
            )
        }

        if (loginError != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = colors.red,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = loginError,
                    style = KassaTheme.typography.subheadline,
                    color = colors.red,
                )
            }
        }
    }
}

/** Eine Formulargruppe: eine gerundete Karte, in der die Zeilen zusammenstehen. */
@Composable
private fun FormGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = KassaTheme.dimens.screenPadding)
            .fillMaxWidth()
            .background(
                KassaTheme.colors.surface,
                RoundedCornerShape(KassaTheme.dimens.controlRadius),
            ),
    ) { content() }
}

/** Trennlinie zwischen zwei Zeilen, links eingerueckt wie auf iOS. */
@Composable
private fun FormDivider() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .height(KassaTheme.dimens.hairline)
            .background(KassaTheme.colors.separator),
    )
}

// MARK: - Vorschau

@PreviewLightDark
@Composable
private fun LoginScreenPreview() {
    KassaTheme {
        LoginScreen(
            initialServerUrl = "",
            initialDeviceName = "Android-Kassa",
            isLoggingIn = false,
            loginError = null,
            onSubmit = { _, _, _ -> },
        )
    }
}

@Preview(name = "Anmeldung mit Fehler", showBackground = true)
@Composable
private fun LoginScreenErrorPreview() {
    KassaTheme {
        LoginScreen(
            initialServerUrl = "http://10.0.2.2:8080",
            initialDeviceName = "Android-Kassa",
            isLoggingIn = false,
            loginError = "Passwort falsch.",
            onSubmit = { _, _, _ -> },
        )
    }
}

@Preview(name = "Anmeldung grosse Schrift", showBackground = true, fontScale = 1.5f)
@Composable
private fun LoginScreenLargeFontPreview() {
    KassaTheme {
        LoginScreen(
            initialServerUrl = "http://10.0.2.2:8080",
            initialDeviceName = "Android-Kassa",
            isLoggingIn = true,
            loginError = null,
            onSubmit = { _, _, _ -> },
        )
    }
}
