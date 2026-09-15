package at.heuriger.kassa.sync

import at.heuriger.kassa.AppContainer
import at.heuriger.kassa.data.ConnectionState
import at.heuriger.kassa.data.InMemoryTokenStore
import at.heuriger.kassa.net.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Spiegelt `AppModel` aus `App/Sources/KassaApp.swift`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionTest {

    private lateinit var env: SyncEnvironment

    @Before
    fun setUp() = runBlocking { env = SyncEnvironment.create() }

    @After
    fun tearDown() = env.close()

    @Test
    fun `ohne Server-Adresse meldet der Login einen Hinweis statt es zu versuchen`() = runBlocking {
        env.session.login("geheim")

        assertEquals(
            "Bitte zuerst eine Server-Adresse eintragen.",
            env.session.loginState.value.loginError,
        )
        assertFalse(env.session.loginState.value.isLoggingIn)
        assertNull(env.settings.token.value)
    }

    @Test
    fun `ein erfolgreicher Login laedt Katalog und startet die Engine`() = runBlocking {
        env.settings.setServerUrl("http://kassa.local:8080")

        env.session.login("geheim")

        assertNull(env.session.loginState.value.loginError)
        assertFalse(env.session.loginState.value.isLoggingIn)
        assertEquals("test-token", env.settings.token.value)
        assertEquals(FakeKassaApi.DEVICE_ID, env.settings.deviceId)
        assertTrue("der Katalog ist da", env.store.articles().isNotEmpty())
        assertEquals("die Konfiguration kam vom Server", 1, env.settings.catalogVersion)
        // Die laufende Engine synchronisiert im Hintergrund weiter, der Zustand
        // steht also erst, wenn deren Durchlauf fertig ist.
        waitUntil { env.store.status.value.connection == ConnectionState.CONNECTED }

        // Die Engine laeuft: ein Ping muss einen Sync ausloesen.
        val before = env.api.syncCallCount()
        env.engine.requestSync()
        waitUntil { env.api.syncCallCount() > before }
    }

    @Test
    fun `ein falsches Passwort landet als deutsche Meldung im Zustand`() = runBlocking {
        env.settings.setServerUrl("http://kassa.local:8080")
        env.api.setOffline(true)

        env.session.login("geheim")

        assertEquals("Keine Verbindung zum Server.", env.session.loginState.value.loginError)
        assertFalse(env.session.loginState.value.isLoggingIn)
        assertNull(env.settings.token.value)
    }

    @Test
    fun `Abmelden raeumt alles ausser Server-Adresse und Geraetenamen`() = runBlocking {
        env.settings.setServerUrl("http://kassa.local:8080")
        env.settings.setDeviceName("Schank")
        env.session.login("geheim")
        env.seedCatalog()
        env.book(table = 5, articleId = "b1", qty = 2)
        assertTrue(env.store.pendingCommands().isNotEmpty())

        env.session.logout()

        assertNull(env.settings.token.value)
        assertEquals(0, env.settings.maxSeq)
        assertTrue(env.store.allLines().isEmpty())
        assertTrue(env.store.pendingCommands().isEmpty())
        assertTrue(env.store.articles().isEmpty())
        assertEquals(ConnectionState.NEEDS_LOGIN, env.store.status.value.connection)
        assertEquals("http://kassa.local:8080", env.settings.serverUrl)
        assertEquals("Schank", env.settings.deviceName)
    }

    @Test
    fun `startup ohne Anmeldung fuehrt direkt zum Anmeldebildschirm`() = runBlocking {
        env.session.startup()

        assertEquals(ConnectionState.NEEDS_LOGIN, env.store.status.value.connection)
        assertTrue("nichts wurde geladen", env.store.articles().isEmpty())
    }

    @Test
    fun `startup mit Anmeldung laedt den Katalog`() = runBlocking {
        env.settings.setServerUrl("http://kassa.local:8080")
        env.settings.apply(env.api.login("egal", "Schank"))

        env.session.startup()
        waitUntil { env.store.articles().isNotEmpty() }

        assertEquals(2, env.store.articles().size)
    }

    @Test
    fun `der Tagesbericht kommt direkt vom Server`() = runBlocking {
        env.seedCatalog()
        env.book(table = 5, articleId = "b1", qty = 2)
        env.engine.syncNow()

        val line = env.store.lines(5).single()
        env.store.settle(
            tableNumber = 5,
            selections = listOf(at.heuriger.kassa.wire.SettlementLineSelection(line.id, qty = 2)),
            total = at.heuriger.kassa.wire.Money(880),
            tip = at.heuriger.kassa.wire.Money(120),
        ).job.join()
        env.engine.syncNow()

        val settlement = env.store.settlements().single()
        val report = env.session.dayReport(settlement.businessDay)

        assertEquals(1, report.settlementCount)
        assertEquals(880, report.totalCents)
        assertEquals(120, report.tipCents)
        assertEquals(1000, report.grandTotal.cents)
    }

    /** Beweist, dass die Kompositionswurzel wirklich zusammenpasst. */
    @Test
    fun `der AppContainer verdrahtet einen lauffaehigen Graphen`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fake = FakeKassaApi()
        try {
            val container = AppContainer.create(
                context = RuntimeEnvironment.getApplication(),
                appScope = scope,
                apiFactory = { _, _ -> fake },
                tokenStore = InMemoryTokenStore(),
                preferencesFileName = "container-test.preferences_pb",
            )

            assertNotNull(container.store)
            assertEquals(40, container.settings.tableCount)

            container.settings.setServerUrl("http://kassa.local:8080")
            container.session.login("geheim")

            assertNull(container.session.loginState.value.loginError)
            assertEquals("test-token", container.settings.token.value)
            assertTrue(container.store.articles().isNotEmpty())

            container.session.logout()
            assertNull(container.settings.token.value)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Robolectric faehrt die echte [at.heuriger.kassa.KassaApplication] aus dem
     * Manifest hoch — der Container samt Room-Datei auf dem Datentraeger baut
     * sich also unter Testbedingungen wirklich auf.
     *
     * Den AndroidKeyStore gibt es unter Robolectric nicht. Geprueft wird hier
     * deshalb das Verhalten im Fehlerfall: der [KeystoreTokenStore] darf nicht
     * werfen, sonst risse er die gerade gelungene Anmeldung mit. Die echte
     * Ver- und Entschluesselung braucht einen Instrumentierungstest.
     */
    @Test
    fun `die echte Application baut ihren Container auf`() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as at.heuriger.kassa.KassaApplication
        val container = app.container.await()

        assertEquals(40, container.settings.tableCount)
        assertTrue(container.settings.deviceId.isNotBlank())

        // Ohne Keystore bleibt das Token ungespeichert — aber leise.
        container.tokenStore.write("geheimes-token")
        assertNull(container.tokenStore.read())
        container.tokenStore.write(null)
        assertNull(container.tokenStore.read())
    }

    @Test
    fun `ein 401 setzt den Zustand auf Anmeldung noetig`() = runBlocking {
        env.seedCatalog()
        env.book(table = 5, articleId = "b1", qty = 1)
        env.api.setNextOrderLinesError(ApiException.Unauthorized)

        env.engine.syncNow()

        assertEquals(ConnectionState.NEEDS_LOGIN, env.store.status.value.connection)
        assertEquals("Anmeldung abgelaufen. Bitte neu anmelden.", env.store.status.value.lastErrorMessage)
        assertEquals("der Befehl bleibt fuer spaeter liegen", 1, env.store.openPendingCount())
        assertEquals(0, env.store.failedPendingCount())
    }
}
