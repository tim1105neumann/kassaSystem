package at.heuriger.kassa.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import at.heuriger.kassa.R
import at.heuriger.kassa.data.CommandKind
import at.heuriger.kassa.data.ConnectionState
import at.heuriger.kassa.data.PendingCommandSnapshot
import at.heuriger.kassa.ui.components.KassaFilledButton
import at.heuriger.kassa.ui.components.KassaFootnote
import at.heuriger.kassa.ui.components.KassaFormGroup
import at.heuriger.kassa.ui.components.KassaLabeledRow
import at.heuriger.kassa.ui.components.KassaRowDivider
import at.heuriger.kassa.ui.components.KassaSectionHeader
import at.heuriger.kassa.ui.components.KassaSheetHeader
import at.heuriger.kassa.ui.components.KassaTextField
import at.heuriger.kassa.ui.components.KassaTintedButton
import at.heuriger.kassa.ui.theme.KassaTheme
import at.heuriger.kassa.wire.BusinessDay
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Einstellungen. Spiegel von `SettingsView.swift`.
 *
 * Zustandslos wie die uebrigen Bildschirme — alles kommt herein, alles geht als
 * Aufruf hinaus. Nur die drei Eingabefelder halten lokalen Zustand, und zwar aus
 * demselben Grund wie in der Anmeldung: jeder Tastendruck waere sonst ein
 * DataStore-Schreibvorgang, und eine halb getippte Server-Adresse hat in den
 * Einstellungen nichts verloren. Uebernommen wird beim Bestaetigen auf der
 * Tastatur — das Pendant zu SwiftUIs `.onSubmit`.
 */
@Composable
fun SettingsScreen(
    serverUrl: String,
    deviceName: String,
    isLoggedIn: Boolean,
    connection: ConnectionState,
    lastSyncAt: Instant?,
    lastErrorMessage: String?,
    commands: List<PendingCommandSnapshot>,
    openPendingCount: Int,
    isLoggingIn: Boolean,
    loginError: String?,
    isSyncing: Boolean,
    onServerUrlSubmit: (String) -> Unit,
    onDeviceNameSubmit: (String) -> Unit,
    onSyncNow: () -> Unit,
    onLogin: (String) -> Unit,
    onDiscardCommand: (UUID) -> Unit,
    onLogout: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KassaTheme.colors
    val focus = LocalFocusManager.current
    var showLogoutConfirmation by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.groupedBackground),
    ) {
        KassaSheetHeader(
            title = stringResource(R.string.title_settings),
            doneLabel = stringResource(R.string.action_done),
            onDone = onDone,
        )

        // Kein `verticalArrangement`, sondern Abstand am Kopf jedes Abschnitts:
        // die Befehle der Warteschlange sind einzelne Eintraege und muessen
        // trotzdem eine zusammenhaengende Karte ergeben. Ein Listenabstand haette
        // sie auseinandergerissen.
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        ) {
            item(key = "server") {
                ServerSection(
                    serverUrl = serverUrl,
                    deviceName = deviceName,
                    onServerUrlSubmit = {
                        onServerUrlSubmit(it)
                        focus.clearFocus()
                    },
                    onDeviceNameSubmit = {
                        onDeviceNameSubmit(it)
                        focus.clearFocus()
                    },
                )
            }

            if (isLoggedIn) {
                item(key = "connection") {
                    Column {
                        SectionSpacer()
                        ConnectionSection(
                            connection = connection,
                            lastSyncAt = lastSyncAt,
                            lastErrorMessage = lastErrorMessage,
                            isSyncing = isSyncing,
                            onSyncNow = onSyncNow,
                        )
                    }
                }
            } else {
                item(key = "login") {
                    Column {
                        SectionSpacer()
                        LoginSection(
                            isLoggingIn = isLoggingIn,
                            loginError = loginError,
                            onLogin = {
                                focus.clearFocus()
                                onLogin(it)
                            },
                        )
                    }
                }
            }

            item(key = "queue-header") {
                Column {
                    SectionSpacer()
                    KassaSectionHeader(stringResource(R.string.settings_section_queue))
                }
            }

            if (commands.isEmpty()) {
                item(key = "queue-empty") {
                    KassaFormGroup {
                        Text(
                            text = stringResource(R.string.settings_queue_empty),
                            style = KassaTheme.typography.body,
                            color = colors.secondaryLabel,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            } else {
                // Jeder Befehl als eigener Eintrag: die Wischgeste braucht ein
                // Element, das die Liste einzeln entfernen kann.
                itemsIndexed(
                    items = commands,
                    key = { _, command -> command.id },
                ) { index, command ->
                    CommandRow(
                        command = command,
                        isFirst = index == 0,
                        isLast = index == commands.lastIndex,
                        onDiscard = { onDiscardCommand(command.id) },
                    )
                }
            }

            item(key = "queue-footnote") {
                KassaFootnote(stringResource(R.string.settings_queue_footnote))
            }

            if (isLoggedIn) {
                item(key = "logout") {
                    Column {
                        SectionSpacer()
                        LogoutSection(
                            openPendingCount = openPendingCount,
                            onRequestLogout = { showLogoutConfirmation = true },
                        )
                    }
                }
            }

            item(key = "legal") {
                Column {
                    SectionSpacer()
                    KassaFootnote(stringResource(R.string.settings_legal))
                }
            }
        }
    }

    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            title = { Text(stringResource(R.string.settings_logout_title)) },
            text = { Text(stringResource(R.string.settings_logout_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirmation = false
                    onLogout()
                }) {
                    Text(
                        text = stringResource(R.string.settings_logout),
                        color = colors.red,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// MARK: - Abschnitte

@Composable
private fun ServerSection(
    serverUrl: String,
    deviceName: String,
    onServerUrlSubmit: (String) -> Unit,
    onDeviceNameSubmit: (String) -> Unit,
) {
    var urlText by rememberSaveable(serverUrl) { mutableStateOf(serverUrl) }
    var nameText by rememberSaveable(deviceName) { mutableStateOf(deviceName) }

    Section(R.string.login_section_server) {
        KassaTextField(
            value = urlText,
            onValueChange = { urlText = it },
            placeholder = stringResource(R.string.login_server_placeholder),
            label = stringResource(R.string.login_section_server),
            containerColor = Color.Transparent,
            // Wie in der Anmeldung: keine Autokorrektur und keine
            // Grossschreibung, sonst wird aus „kassa" ein „Kassa" und der Host
            // stimmt nicht mehr.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onServerUrlSubmit(urlText.trim()) }),
        )
        KassaRowDivider()
        KassaTextField(
            value = nameText,
            onValueChange = { nameText = it },
            placeholder = stringResource(R.string.login_device_name),
            label = stringResource(R.string.login_device_name),
            containerColor = Color.Transparent,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDeviceNameSubmit(nameText.trim()) }),
        )
    }
}

@Composable
private fun ConnectionSection(
    connection: ConnectionState,
    lastSyncAt: Instant?,
    lastErrorMessage: String?,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
) {
    val colors = KassaTheme.colors
    val status = statusText(connection)

    Section(R.string.settings_section_connection) {
        KassaLabeledRow(
            label = stringResource(R.string.settings_status),
            spokenValue = status,
        ) {
            Text(text = status, style = KassaTheme.typography.body, color = colors.secondaryLabel)
        }

        if (lastSyncAt != null) {
            KassaRowDivider()
            val formatted = formatTime(lastSyncAt)
            KassaLabeledRow(
                label = stringResource(R.string.settings_last_sync),
                spokenValue = formatted,
            ) {
                Text(
                    text = formatted,
                    style = KassaTheme.typography.body,
                    color = colors.secondaryLabel,
                )
            }
        }

        if (lastErrorMessage != null) {
            KassaRowDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    text = lastErrorMessage,
                    style = KassaTheme.typography.caption,
                    color = colors.red,
                )
            }
        }

        KassaRowDivider()
        KassaTintedButton(
            text = stringResource(R.string.settings_sync_now),
            enabled = !isSyncing,
            minHeight = KassaTheme.dimens.minTouchTarget,
            contentDescription = stringResource(R.string.settings_sync_now),
            onClick = onSyncNow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            content = if (!isSyncing) null else {
                {
                    CircularProgressIndicator(
                        color = colors.accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
        )
    }
}

@Composable
private fun LoginSection(
    isLoggingIn: Boolean,
    loginError: String?,
    onLogin: (String) -> Unit,
) {
    val colors = KassaTheme.colors
    // Bewusst kein `rememberSaveable`: ein Passwort gehoert nicht in den
    // gespeicherten Zustand.
    var password by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(R.string.login_section_credentials) {
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
                keyboardActions = KeyboardActions(
                    onDone = { if (password.isNotEmpty()) onLogin(password) },
                ),
                visualTransformation = PasswordVisualTransformation(),
            )
        }

        KassaFilledButton(
            text = stringResource(R.string.login_submit),
            enabled = password.isNotEmpty() && !isLoggingIn,
            onClick = { onLogin(password) },
            modifier = Modifier.padding(horizontal = KassaTheme.dimens.screenPadding),
        )

        if (loginError != null) {
            Text(
                text = loginError,
                style = KassaTheme.typography.subheadline,
                color = colors.red,
                modifier = Modifier.padding(horizontal = KassaTheme.dimens.screenPadding),
            )
        }
    }
}

/**
 * Ein Befehl aus der Warteschlange, nach links wischbar.
 *
 * Die Karte wird hier Zeile fuer Zeile gerundet statt als Gruppe: unter der
 * Wischflaeche muss dieselbe Form liegen wie darueber, sonst blitzt beim Wischen
 * eine eckige Kante durch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandRow(
    command: PendingCommandSnapshot,
    isFirst: Boolean,
    isLast: Boolean,
    onDiscard: () -> Unit,
) {
    val colors = KassaTheme.colors
    val kind = kindLabel(command.kind)
    val shape = RoundedCornerShape(
        topStart = if (isFirst) 12.dp else 0.dp,
        topEnd = if (isFirst) 12.dp else 0.dp,
        bottomStart = if (isLast) 12.dp else 0.dp,
        bottomEnd = if (isLast) 12.dp else 0.dp,
    )

    val dismissState = rememberSwipeToDismissBoxState()

    // Auf `currentValue` reagieren statt den Wechsel in `confirmValueChange` zu
    // bestaetigen: das Verwerfen soll erst laufen, wenn die Karte wirklich
    // draussen ist, und der Rueckruf ist ausserdem abgekuendigt.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onDiscard()
    }

    Box(modifier = Modifier.padding(horizontal = KassaTheme.dimens.screenPadding)) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            backgroundContent = {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.red, shape)
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = colors.onAccent,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_queue_discard),
                        style = KassaTheme.typography.headline,
                        color = colors.onAccent,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            },
        ) {
            Column(modifier = Modifier.background(colors.surface, shape)) {
                if (!isFirst) KassaRowDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = kind,
                            style = KassaTheme.typography.headline,
                            color = colors.label,
                            modifier = Modifier.weight(1f),
                        )
                        if (command.failedPermanently) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = colors.red,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Text(
                        text = formatDateTime(command.createdAt),
                        style = KassaTheme.typography.caption,
                        color = colors.secondaryLabel,
                    )
                    if (command.lastError != null) {
                        Text(
                            text = stringResource(
                                R.string.settings_queue_failure,
                                command.attemptCount,
                                command.lastError,
                            ),
                            style = KassaTheme.typography.caption,
                            color = colors.red,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogoutSection(openPendingCount: Int, onRequestLogout: () -> Unit) {
    val colors = KassaTheme.colors
    val blocked = openPendingCount > 0

    Column {
        KassaTintedButton(
            text = stringResource(R.string.settings_logout),
            // Abmelden loescht den lokalen Speicher. Solange Buchungen noch
            // nicht am Server sind, waere das echter Datenverlust — deshalb
            // gesperrt statt nur mit Warnhinweis versehen.
            enabled = !blocked,
            minHeight = KassaTheme.dimens.buttonHeight,
            onClick = onRequestLogout,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KassaTheme.dimens.screenPadding),
            content = {
                Text(
                    text = stringResource(R.string.settings_logout),
                    style = KassaTheme.typography.headline,
                    color = colors.red,
                )
            },
        )
        KassaFootnote(
            if (blocked) {
                stringResource(R.string.settings_logout_blocked, pendingPhrase(openPendingCount))
            } else {
                stringResource(R.string.settings_logout_footnote)
            }
        )
    }
}

/** Der Abstand zwischen zwei Abschnitten. */
@Composable
private fun SectionSpacer() {
    Spacer(modifier = Modifier.height(24.dp))
}

/** Ueberschrift plus Karte — dieselbe Bauform wie im Tagesabschluss. */
@Composable
private fun Section(@StringRes titleRes: Int, content: @Composable ColumnScope.() -> Unit) {
    Column {
        KassaSectionHeader(stringResource(titleRes))
        KassaFormGroup(content = content)
    }
}

// MARK: - Texte

/** „eine Buchung ist" / „3 Buchungen sind" — damit der Satz im Fusstext stimmt. */
@Composable
private fun pendingPhrase(count: Int): String = if (count == 1) {
    stringResource(R.string.settings_logout_pending_one)
} else {
    stringResource(R.string.settings_logout_pending_many, count)
}

@Composable
private fun statusText(connection: ConnectionState): String = when (connection) {
    ConnectionState.CONNECTED -> stringResource(R.string.settings_status_connected)
    ConnectionState.SYNCING -> stringResource(R.string.settings_status_syncing)
    ConnectionState.OFFLINE -> stringResource(R.string.settings_status_offline)
    ConnectionState.NEEDS_LOGIN -> stringResource(R.string.settings_status_needs_login)
}

@Composable
private fun kindLabel(kind: CommandKind): String = when (kind) {
    CommandKind.ADD_LINES -> stringResource(R.string.settings_queue_add_lines)
    CommandKind.VOID_LINE -> stringResource(R.string.settings_queue_void_line)
    CommandKind.SETTLE -> stringResource(R.string.settings_queue_settle)
}

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.GERMAN)

private val DATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm:ss", Locale.GERMAN)

private fun formatTime(instant: Instant): String =
    instant.atZone(BusinessDay.TIME_ZONE).format(TIME_FORMAT)

private fun formatDateTime(instant: Instant): String =
    instant.atZone(BusinessDay.TIME_ZONE).format(DATE_TIME_FORMAT)

// MARK: - Vorschau

private fun previewCommands(): List<PendingCommandSnapshot> = listOf(
    PendingCommandSnapshot(
        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        kind = CommandKind.ADD_LINES,
        payload = "{}",
        createdAt = Instant.parse("2026-09-13T16:02:00Z"),
        attemptCount = 0,
    ),
    PendingCommandSnapshot(
        id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        kind = CommandKind.SETTLE,
        payload = "{}",
        createdAt = Instant.parse("2026-09-13T16:04:00Z"),
        attemptCount = 4,
        lastError = "Wurde bereits von iPad Schank kassiert.",
        failedPermanently = true,
    ),
)

@Composable
private fun PreviewSettings(
    isLoggedIn: Boolean = true,
    commands: List<PendingCommandSnapshot> = emptyList(),
    openPendingCount: Int = 0,
) {
    SettingsScreen(
        serverUrl = "http://10.0.2.2:8080",
        deviceName = "Android-Kassa",
        isLoggedIn = isLoggedIn,
        connection = if (isLoggedIn) ConnectionState.CONNECTED else ConnectionState.NEEDS_LOGIN,
        lastSyncAt = Instant.parse("2026-09-13T16:10:00Z"),
        lastErrorMessage = null,
        commands = commands,
        openPendingCount = openPendingCount,
        isLoggingIn = false,
        loginError = null,
        isSyncing = false,
        onServerUrlSubmit = {},
        onDeviceNameSubmit = {},
        onSyncNow = {},
        onLogin = {},
        onDiscardCommand = {},
        onLogout = {},
        onDone = {},
    )
}

@PreviewLightDark
@Composable
private fun SettingsConnectedPreview() {
    KassaTheme { PreviewSettings() }
}

@Preview(name = "Einstellungen mit Warteschlange", showBackground = true)
@Composable
private fun SettingsQueuePreview() {
    KassaTheme {
        PreviewSettings(commands = previewCommands(), openPendingCount = 1)
    }
}

@Preview(name = "Einstellungen abgemeldet", showBackground = true)
@Composable
private fun SettingsLoggedOutPreview() {
    KassaTheme { PreviewSettings(isLoggedIn = false) }
}

@Preview(name = "Einstellungen grosse Schrift", showBackground = true, fontScale = 1.5f)
@Composable
private fun SettingsLargeFontPreview() {
    KassaTheme { PreviewSettings(commands = previewCommands(), openPendingCount = 1) }
}
