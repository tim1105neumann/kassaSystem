package at.heuriger.kassa.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import at.heuriger.kassa.BuildConfig

/**
 * Holt die Berechtigung fuers lokale Netz — nur im Debug-Build.
 *
 * **Warum das ueberhaupt noetig ist.** Ab Android 16 („Local Network
 * Protection") darf eine App RFC1918-Adressen — 10.x, 172.16–31.x, 192.168.x —
 * nur noch mit [ACCESS_LOCAL_NETWORK] erreichen. Ohne sie laeuft *jede*
 * Verbindung dorthin in den Zeitablauf, waehrend das offene Internet weiter
 * geht. Der Fehler sieht damit aus wie „Server ist aus" und nicht wie „uns fehlt
 * eine Berechtigung", und genau das kostet sonst einen Abend Fehlersuche.
 *
 * **Warum nur im Debug-Build.** Betroffen ist allein die Entwicklung: der
 * Emulator erreicht den Vapor-Server unter `10.0.2.2`. Im Betrieb haengen die
 * Geraete am Mobilfunknetz und sprechen den Server ueber seine oeffentliche
 * Domain per HTTPS an (`deploy/README.md`) — keine lokale Adresse, keine Sperre.
 * Der Release-Build deklariert die Berechtigung deshalb gar nicht erst, und ein
 * Kellner bekommt beim ersten Start keinen Dialog zu sehen, den ihm niemand
 * erklaeren kann.
 *
 * [BuildConfig.DEBUG] und das Manifest muessen zusammenpassen: eine Abfrage
 * ohne Deklaration wird vom System sofort mit „abgelehnt" beantwortet, ganz
 * ohne Dialog. Deshalb steht die Deklaration in `src/debug/AndroidManifest.xml`
 * und die Abfrage hinter derselben Bedingung.
 *
 * Gefragt wird beim Start und nur einmal: Lehnt jemand ab, darf ihn die App
 * nicht bei jedem Bildwechsel erneut behelligen. Der Weg zurueck fuehrt dann
 * ueber die Systemeinstellungen — und das Verbindungsbanner sagt derweil ehrlich
 * „Offline".
 */
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * Android 16 ist API 36. Bewusst die nackte Zahl statt `VERSION_CODES.BAKLAVA`:
 * der Name hat sich waehrend der Vorschau noch geaendert, die Zahl nicht.
 */
private const val ANDROID_16 = 36

@Composable
fun EnsureLocalNetworkAccess() {
    if (!BuildConfig.DEBUG) return
    if (Build.VERSION.SDK_INT < ANDROID_16) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Ergebnis egal: bei „nein" bleibt die App offline und sagt das auch. */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(ACCESS_LOCAL_NETWORK)
    }
}
