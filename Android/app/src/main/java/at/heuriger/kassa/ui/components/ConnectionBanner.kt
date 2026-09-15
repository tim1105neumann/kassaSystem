package at.heuriger.kassa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import at.heuriger.kassa.R
import at.heuriger.kassa.data.ConnectionState
import at.heuriger.kassa.ui.theme.KassaTheme

/**
 * Verbindungsstatus im Klartext. Spiegel von `ConnectionBanner.swift`.
 *
 * Der Leitsatz der Vorlage gilt hier genauso: **der Status muss ehrlich sein.**
 * Offline heisst nicht „gleich wieder da", sondern dass der angezeigte Stand
 * nicht der geteilte ist — und genau das steht dann auch da. Ein Kellner, der
 * einem zweiten Geraet vertraut, das die Buchung nie gesehen hat, kassiert
 * falsch.
 *
 * Zustandslos und mit allen Werten als Parameter: so ist jeder der fuenf
 * Zustaende in der Vorschau nebeneinander zu sehen, ohne Store und ohne Netz.
 */
@Composable
fun ConnectionBanner(
    connection: ConnectionState,
    openCount: Int,
    failedCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens

    val title = bannerTitle(connection, openCount)
    val detail = bannerDetail(connection, openCount, failedCount)
    val background = bannerColor(connection, openCount, colors.green, colors.orange, colors.blue, colors.red, colors.gray)

    // Ein Banner ist eine Aussage, keine Liste von Bruchstuecken: TalkBack soll
    // „Verbunden. 3 Buchungen ausstehend" am Stueck vorlesen und nicht Symbol,
    // Titel und Detail als drei Halte.
    val spoken = if (detail != null) "$title. $detail" else title

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(dimens.cornerRadius))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = bannerIcon(connection),
            contentDescription = null,
            tint = colors.onAccent,
            modifier = Modifier.size(28.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = KassaTheme.typography.headline,
                color = colors.onAccent,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = KassaTheme.typography.subheadline,
                    // Deckend, nicht abgeschwaecht: 90 % Weiss auf der
                    // Bannerfarbe liegen bei rund 4,1:1 und damit unter AA.
                    // Die Abstufung zum Titel traegt jetzt allein die Schrift.
                    color = colors.onAccent,
                )
            }
        }
    }
}

/**
 * „Verbunden" waere gelogen, solange noch etwas in der Warteschlange liegt —
 * die Verbindung steht, die Buchung ist aber noch nicht beim Server. Deshalb
 * traegt derselbe Zustand zwei verschiedene Titel.
 */
@Composable
private fun bannerTitle(connection: ConnectionState, openCount: Int): String = when (connection) {
    ConnectionState.CONNECTED ->
        if (openCount > 0) stringResource(R.string.banner_transmitting)
        else stringResource(R.string.banner_connected)

    ConnectionState.SYNCING -> stringResource(R.string.banner_syncing)
    ConnectionState.OFFLINE -> stringResource(R.string.banner_offline)
    ConnectionState.NEEDS_LOGIN -> stringResource(R.string.banner_needs_login)
}

@Composable
private fun bannerDetail(
    connection: ConnectionState,
    openCount: Int,
    failedCount: Int,
): String? {
    val parts = buildList {
        if (openCount > 0) add(stringResource(R.string.banner_pending_count, openCount))
        if (failedCount > 0) add(stringResource(R.string.banner_failed_count, failedCount))
        if (connection == ConnectionState.OFFLINE) add(stringResource(R.string.banner_offline_detail))
        if (connection == ConnectionState.NEEDS_LOGIN) add(stringResource(R.string.banner_needs_login_detail))
    }
    return parts.ifEmpty { null }?.joinToString(stringResource(R.string.banner_detail_separator))
}

private fun bannerIcon(connection: ConnectionState): ImageVector = when (connection) {
    ConnectionState.CONNECTED -> Icons.Filled.CloudDone
    ConnectionState.SYNCING -> Icons.Filled.Sync
    ConnectionState.OFFLINE -> Icons.Filled.CloudOff
    ConnectionState.NEEDS_LOGIN -> Icons.Filled.NoAccounts
}

private fun bannerColor(
    connection: ConnectionState,
    openCount: Int,
    green: Color,
    orange: Color,
    blue: Color,
    red: Color,
    gray: Color,
): Color = when (connection) {
    ConnectionState.CONNECTED -> if (openCount > 0) orange else green
    ConnectionState.SYNCING -> blue
    ConnectionState.OFFLINE -> red
    ConnectionState.NEEDS_LOGIN -> gray
}

// MARK: - Vorschau

@Preview(name = "Banner hell", showBackground = true)
@Composable
private fun ConnectionBannerPreview() {
    KassaTheme(darkTheme = false) { BannerGallery() }
}

@Preview(name = "Banner dunkel", showBackground = true)
@Composable
private fun ConnectionBannerDarkPreview() {
    KassaTheme(darkTheme = true) { BannerGallery() }
}

@Preview(name = "Banner grosse Schrift", showBackground = true, fontScale = 1.5f)
@Composable
private fun ConnectionBannerLargeFontPreview() {
    KassaTheme(darkTheme = false) { BannerGallery() }
}

@Composable
private fun BannerGallery() {
    Column(
        modifier = Modifier
            .background(KassaTheme.colors.groupedBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConnectionBanner(ConnectionState.CONNECTED, openCount = 0, failedCount = 0)
        ConnectionBanner(ConnectionState.CONNECTED, openCount = 3, failedCount = 0)
        ConnectionBanner(ConnectionState.SYNCING, openCount = 0, failedCount = 0)
        ConnectionBanner(ConnectionState.OFFLINE, openCount = 2, failedCount = 1)
        ConnectionBanner(ConnectionState.NEEDS_LOGIN, openCount = 0, failedCount = 0)
    }
}
