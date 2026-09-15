package at.heuriger.kassa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import at.heuriger.kassa.AppContainer
import at.heuriger.kassa.R
import at.heuriger.kassa.net.ApiException
import at.heuriger.kassa.sync.germanMessage
import at.heuriger.kassa.ui.articles.ArticlePickerScreen
import at.heuriger.kassa.ui.components.KassaBarButton
import at.heuriger.kassa.ui.components.KassaNavBar
import at.heuriger.kassa.ui.login.LoginScreen
import at.heuriger.kassa.ui.report.DayReportScreen
import at.heuriger.kassa.ui.report.DayReportState
import at.heuriger.kassa.ui.settings.SettingsScreen
import at.heuriger.kassa.ui.settle.SettlementScreen
import at.heuriger.kassa.ui.table.TableDetailScreen
import at.heuriger.kassa.ui.tables.TableGridScreen
import at.heuriger.kassa.ui.tables.rememberMinuteTicker
import at.heuriger.kassa.ui.tables.tableSummaries
import at.heuriger.kassa.ui.tables.toTileState
import at.heuriger.kassa.ui.theme.KassaTheme
import at.heuriger.kassa.wire.BusinessDay
import at.heuriger.kassa.wire.KassaClock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/**
 * Die Wurzel der Oberflaeche. Spiegel von `RootView.swift`.
 *
 * Ein einziger [NavHost]. Anmeldung und Raster teilen sich absichtlich das Ziel
 * [Route.Home] statt zwei getrennte zu sein: nach dem Anmelden soll der Inhalt
 * wechseln, ohne dass ein Bildschirm hereingeschoben wird und ohne dass die
 * Anmeldung im Rueckwaertsstapel liegen bleibt.
 */
@Composable
fun KassaNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    // Muss vor dem ersten Netzaufruf stehen — ohne sie ist der Server im Haus
    // nicht erreichbar.
    EnsureLocalNetworkAccess()

    // Genau einmal pro Prozess: Client ausrichten, Katalog holen, Sync starten.
    LaunchedEffect(container) { container.session.startup() }

    // Das beobachtbare Pendant zu `AppSettings.isLoggedIn`. Der synchrone
    // Getter des Unterbaus weckt die Komposition nicht auf; dieser Flow schon.
    val isLoggedIn by container.settings.isLoggedInFlow
        .collectAsStateWithLifecycle(initialValue = container.settings.isLoggedIn)

    NavHost(
        navController = navController,
        startDestination = Route.Home,
        modifier = modifier,
    ) {
        composable<Route.Home> {
            HomeScreen(
                container = container,
                isLoggedIn = isLoggedIn,
                onOpenDayReport = { navController.navigate(Route.DayReport) },
                onOpenSettings = { navController.navigate(Route.Settings) },
                onOpenTable = { navController.navigate(Route.Table(it)) },
            )
        }

        composable<Route.DayReport> {
            DayReportSheet(container = container, onDone = { navController.popBackStack() })
        }

        composable<Route.Settings> {
            SettingsSheet(container = container, onDone = { navController.popBackStack() })
        }

        composable<Route.Table> { entry ->
            val route: Route.Table = entry.toRoute()
            TableDetail(
                container = container,
                tableNumber = route.number,
                onBack = { navController.popBackStack() },
                onAddArticles = { navController.navigate(Route.Articles(route.number)) },
                onSettle = { navController.navigate(Route.Settle(route.number)) },
            )
        }

        composable<Route.Articles> { entry ->
            val route: Route.Articles = entry.toRoute()
            ArticlePicker(
                container = container,
                tableNumber = route.number,
                onDone = { navController.popBackStack() },
            )
        }

        composable<Route.Settle> { entry ->
            val route: Route.Settle = entry.toRoute()
            Settlement(
                container = container,
                tableNumber = route.number,
                onBack = { navController.popBackStack() },
                // Nach dem Kassieren zurueck auf die Wurzel, nicht nur einen
                // Schritt: der Tisch dahinter ist gerade leer geraeumt worden,
                // und ein Stapel „Tisch 7 kassieren -> Tisch 7 (leer)" waere nur
                // ein Bildschirm, den niemand mehr sehen will.
                onSettled = { navController.popBackStack(Route.Home, inclusive = false) },
            )
        }
    }
}

// MARK: - Bindung an Store und Sitzung

/**
 * Bindet das Tischdetail an den Store.
 *
 * `remember(container, tableNumber)`, weil `observeLines` bei jedem Aufruf einen
 * *neuen* Flow baut — ohne das liefe die Room-Abfrage bei jeder Rekomposition
 * von vorn.
 */
@Composable
private fun TableDetail(
    container: AppContainer,
    tableNumber: Int,
    onBack: () -> Unit,
    onAddArticles: () -> Unit,
    onSettle: () -> Unit,
) {
    val linesFlow = remember(container, tableNumber) { container.store.observeLines(tableNumber) }
    val lines by linesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    TableDetailScreen(
        tableNumber = tableNumber,
        lines = lines,
        onBack = onBack,
        // `changeQty` nimmt die ID, nicht das Objekt: der Store liest die Zeile
        // in seiner Transaktion frisch nach, statt einer veralteten
        // Momentaufnahme aus der Komposition zu vertrauen.
        onChangeQty = { lineId, newQty -> container.store.changeQty(lineId, newQty) },
        onAddArticles = onAddArticles,
        onSettle = onSettle,
    )
}

/** Bindet die Artikelauswahl an Katalog und Store. */
@Composable
private fun ArticlePicker(
    container: AppContainer,
    tableNumber: Int,
    onDone: () -> Unit,
) {
    val articlesFlow = remember(container) { container.store.observeActiveArticles() }
    val articles by articlesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    ArticlePickerScreen(
        tableNumber = tableNumber,
        articles = articles,
        onBack = onDone,
        onBook = { items ->
            // `addLines` kehrt sofort zurueck und schreibt im app-weiten Scope
            // weiter — das Wegnavigieren bricht die Buchung nicht ab.
            container.store.addLines(tableNumber, items)
            onDone()
        },
    )
}

/** Bindet das Kassieren an Store, Sitzung und Sync. */
@Composable
private fun Settlement(
    container: AppContainer,
    tableNumber: Int,
    onBack: () -> Unit,
    onSettled: () -> Unit,
) {
    val linesFlow = remember(container, tableNumber) { container.store.observeLines(tableNumber) }
    val lines by linesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    SettlementScreen(
        tableNumber = tableNumber,
        lines = lines,
        onBack = onBack,
        onPrintOverview = { selections ->
            container.session.printOverview(tableNumber, selections)
        },
        onConfirm = { selections, total, tip ->
            container.store.settle(tableNumber, selections, total, tip)
            // Den Vorgang gleich anstossen statt auf den 10-Sekunden-Takt zu
            // warten: die Kollegin am zweiten Geraet soll den Tisch sofort als
            // frei sehen.
            container.engine.requestSync()
            onSettled()
        },
    )
}

/**
 * Raster oder Anmeldung, darueber immer dieselbe Leiste — genau die Anordnung
 * aus `RootView.swift`. Der Tagesabschluss ist gesperrt, solange niemand
 * angemeldet ist: ohne Server gibt es keinen Bericht.
 */
@Composable
private fun HomeScreen(
    container: AppContainer,
    isLoggedIn: Boolean,
    onOpenDayReport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTable: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(KassaTheme.colors.groupedBackground)) {
        KassaNavBar(
            title = stringResource(
                if (isLoggedIn) R.string.title_tables else R.string.title_login
            ),
            leading = {
                KassaBarButton(
                    icon = Icons.Outlined.Assessment,
                    contentDescription = stringResource(R.string.title_day_report),
                    enabled = isLoggedIn,
                    onClick = onOpenDayReport,
                )
            },
            trailing = {
                KassaBarButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.title_settings),
                    onClick = onOpenSettings,
                )
            },
        )

        if (isLoggedIn) {
            TableGrid(container = container, onOpenTable = onOpenTable)
        } else {
            val loginState by container.session.loginState.collectAsStateWithLifecycle()
            val settingsState by container.settings.settings.collectAsStateWithLifecycle()

            LoginScreen(
                initialServerUrl = settingsState.serverUrl,
                initialDeviceName = settingsState.deviceName,
                isLoggingIn = loginState.isLoggingIn,
                loginError = loginState.loginError,
                onSubmit = { url, name, password ->
                    scope.launch {
                        // Erst festschreiben, dann anmelden: `login` liest die
                        // Adresse aus den Einstellungen, nicht aus Parametern.
                        container.settings.setServerUrl(url)
                        container.settings.setDeviceName(name)
                        container.session.login(password)
                    }
                },
            )
        }
    }
}

/** Bindet das Raster an Store und Einstellungen. */
@Composable
private fun TableGrid(container: AppContainer, onOpenTable: (Int) -> Unit) {
    val scope = rememberCoroutineScope()

    // `remember`, weil jeder dieser Aufrufe einen *neuen* Flow baut. Ohne das
    // wuerde bei jeder Rekomposition neu abonniert und die Room-Abfrage liefe
    // wieder von vorn.
    val summariesFlow = remember(container) { tableSummaries(container.store, container.settings) }
    val openCountFlow = remember(container) { container.store.observeOpenPendingCount() }
    val failedCountFlow = remember(container) { container.store.observeFailedPendingCount() }

    val summaries by summariesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val status by container.store.status.collectAsStateWithLifecycle()
    val openCount by openCountFlow.collectAsStateWithLifecycle(initialValue = 0)
    val failedCount by failedCountFlow.collectAsStateWithLifecycle(initialValue = 0)

    // Ein Tick alle 30 s stellt die Laufzeiten weiter, ohne die Summen neu zu
    // rechnen.
    val tick = rememberMinuteTicker()
    val tiles = remember(summaries, tick) {
        val now = KassaClock.now()
        summaries.map { it.toTileState(now) }
    }

    var isRefreshing by remember { mutableStateOf(false) }

    TableGridScreen(
        tiles = tiles,
        connection = status.connection,
        openPendingCount = openCount,
        failedPendingCount = failedCount,
        conflictMessage = status.conflictMessage,
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                // `syncNow()` ist mutex-geschuetzt und kehrt erst zurueck, wenn
                // der Abgleich durch ist. Der Ring laeuft damit genau so lange
                // wie die Arbeit — kein Beobachten von `lastSyncAt` mit
                // Zeitlimit, das bei einem Sync ohne Aenderung nie ausloest.
                container.engine.syncNow()
                isRefreshing = false
            }
        },
        onDismissConflict = { container.store.setConflict(null) },
        onTableClick = onOpenTable,
    )
}

// MARK: - Blaetter von unten

/**
 * Der Rahmen fuer Tagesabschluss und Einstellungen — auf iOS zwei `.sheet`, hier
 * zwei [ModalBottomSheet].
 *
 * Beide sind trotzdem eigene Navigationsziele und keine Zustandsvariablen des
 * Rasters: der Systemzurueck soll das Blatt schliessen, und beim Drehen darf es
 * nicht verschwinden. Was ein Ziel normalerweise fuellen wuerde, uebernimmt die
 * Flaeche hinter dem Blatt — sie traegt denselben Gruppenhintergrund wie das
 * Raster, damit unter dem Abdunkler kein schwarzes Loch steht.
 *
 * `skipPartiallyExpanded`, weil beide Blaetter Formulare sind: eine halbhohe
 * Zwischenstufe waere nur ein Zustand, aus dem heraus man ohnehin sofort weiter
 * aufzieht.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KassaSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = KassaTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(modifier = Modifier.fillMaxSize().background(colors.groupedBackground))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.groupedBackground,
        content = content,
    )
}

/**
 * Bindet den Tagesabschluss an die Sitzung.
 *
 * Der Bericht kommt bei jedem Wechsel des Betriebstags frisch vom Server —
 * [LaunchedEffect] auf `businessDay` und nicht auf das gewaehlte Datum: zwei
 * Kalendertage koennen denselben Betriebstag ergeben, und dafuer noch einmal zu
 * laden waere eine Netzrunde ohne neues Ergebnis.
 *
 * Der Cutoff wird bewusst **nicht** hier gerechnet, sondern von
 * [BusinessDay.day] — dieselbe Funktion, die auch das Raster und der Server
 * benutzen. Der `DatePicker` liefert Mitternacht UTC; die Tageszeit von jetzt
 * bleibt dabei erhalten, sonst laege ein frueh gewaehlter Tag an der
 * Cutoff-Grenze im falschen Betriebstag.
 */
@Composable
private fun DayReportSheet(container: AppContainer, onDone: () -> Unit) {
    val settingsState by container.settings.settings.collectAsStateWithLifecycle()
    val cutoffHour = settingsState.cutoffHour

    val now = remember { KassaClock.now() }
    val today = remember(now) { now.atZone(BusinessDay.TIME_ZONE) }
    var pickedMillis by rememberSaveable {
        mutableStateOf(
            today.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    }
    val businessDay = remember(pickedMillis, cutoffHour) {
        val date = Instant.ofEpochMilli(pickedMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val instant = date.atTime(today.toLocalTime()).atZone(BusinessDay.TIME_ZONE).toInstant()
        BusinessDay.day(instant, cutoffHour)
    }

    var state by remember { mutableStateOf<DayReportState>(DayReportState.Loading) }

    LaunchedEffect(businessDay) {
        state = DayReportState.Loading
        state = try {
            DayReportState.Loaded(container.session.dayReport(businessDay))
        } catch (error: ApiException) {
            DayReportState.Failed(error.germanMessage)
        }
    }

    KassaSheet(onDismiss = onDone) {
        DayReportScreen(
            selectedDateMillis = pickedMillis,
            cutoffHour = cutoffHour,
            state = state,
            onSelectDate = { pickedMillis = it },
            onDone = onDone,
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

/** Bindet die Einstellungen an Store, Sitzung und Warteschlange. */
@Composable
private fun SettingsSheet(container: AppContainer, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()

    val settingsState by container.settings.settings.collectAsStateWithLifecycle()
    val isLoggedIn by container.settings.isLoggedInFlow
        .collectAsStateWithLifecycle(initialValue = container.settings.isLoggedIn)
    val status by container.store.status.collectAsStateWithLifecycle()
    val loginState by container.session.loginState.collectAsStateWithLifecycle()

    // `remember`, weil jeder dieser Aufrufe einen *neuen* Flow baut.
    val commandsFlow = remember(container) { container.store.observePendingCommands() }
    val openCountFlow = remember(container) { container.store.observeOpenPendingCount() }
    val commands by commandsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val openCount by openCountFlow.collectAsStateWithLifecycle(initialValue = 0)

    var isSyncing by remember { mutableStateOf(false) }

    KassaSheet(onDismiss = onDone) {
        SettingsScreen(
            serverUrl = settingsState.serverUrl,
            deviceName = settingsState.deviceName,
            isLoggedIn = isLoggedIn,
            connection = status.connection,
            lastSyncAt = status.lastSyncAt,
            lastErrorMessage = status.lastErrorMessage,
            commands = commands,
            openPendingCount = openCount,
            isLoggingIn = loginState.isLoggingIn,
            loginError = loginState.loginError,
            isSyncing = isSyncing,
            // Adresse und Engine-Neustart laufen im app-weiten Scope: ein
            // Schliessen des Blattes mitten im Vorgang duerfte die Sync-Schleife
            // nicht gestoppt zuruecklassen.
            onServerUrlSubmit = { url ->
                container.appScope.launch {
                    container.settings.setServerUrl(url)
                    container.session.applyServerUrlChange()
                }
            },
            onDeviceNameSubmit = { name ->
                container.appScope.launch { container.settings.setDeviceName(name) }
            },
            onSyncNow = {
                scope.launch {
                    isSyncing = true
                    // `syncNow()` ist mutex-geschuetzt und kehrt erst zurueck,
                    // wenn der Abgleich durch ist.
                    container.engine.syncNow()
                    isSyncing = false
                }
            },
            onLogin = { password -> scope.launch { container.session.login(password) } },
            onDiscardCommand = { id ->
                container.appScope.launch { container.session.discardCommand(id) }
            },
            onLogout = {
                // Auch hier app-weit: das Blatt geht sofort zu, das Loeschen des
                // lokalen Speichers laeuft zu Ende.
                container.appScope.launch { container.session.logout() }
                onDone()
            },
            onDone = onDone,
            modifier = Modifier.fillMaxHeight(),
        )
    }
}
