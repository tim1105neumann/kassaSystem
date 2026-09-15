package at.heuriger.kassa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import at.heuriger.kassa.ui.KassaNavHost
import at.heuriger.kassa.ui.theme.KassaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KassaTheme {
                KassaApp()
            }
        }
    }
}

/**
 * Wartet auf den [AppContainer] und zeigt erst danach die Oberflaeche.
 *
 * [KassaApplication.container] ist ein `Deferred`, weil die Einstellungen einmal
 * vom Datentraeger gelesen werden muessen. Ein `lateinit`-Zugriff waere hier ein
 * Absturz beim Kaltstart, und `runBlocking` waere ein blockierter UI-Thread —
 * also ein kurzer Ladezustand, bis `await()` zurueckkommt.
 */
@Composable
private fun KassaApp() {
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext
            as KassaApplication

    var container by remember { mutableStateOf<AppContainer?>(null) }
    LaunchedEffect(application) { container = application.container.await() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KassaTheme.colors.groupedBackground)
            // Kein `Scaffold`: die Leiste ist selbst gebaut. Die Systemleisten
            // muessen deshalb hier ausgespart werden, sonst liegt der grosse
            // Titel unter der Statusleiste.
            .safeDrawingPadding(),
    ) {
        val ready = container
        if (ready == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.startup_loading),
                    style = KassaTheme.typography.subheadline,
                    color = KassaTheme.colors.secondaryLabel,
                )
            }
        } else {
            KassaNavHost(container = ready)
        }
    }
}
