package at.heuriger.kassa.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import at.heuriger.kassa.wire.LoginResponse
import at.heuriger.kassa.wire.ServerConfigDto
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Spiegelt `App/Sources/Sync/AppSettings.swift`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsStoreTest {

    private lateinit var file: File
    private lateinit var tokenStore: InMemoryTokenStore
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        file = File(RuntimeEnvironment.getApplication().filesDir, "s-${UUID.randomUUID()}.preferences_pb")
        tokenStore = InMemoryTokenStore()
    }

    @After
    fun tearDown() = runBlocking {
        closeCurrent()
        file.delete()
        Unit
    }

    /**
     * Ein Neustart der App. DataStore laesst keine zwei lebenden Instanzen auf
     * derselben Datei zu, die alte muss also wirklich beendet sein — `cancel()`
     * allein reicht nicht, es braucht das `join()`.
     */
    private suspend fun open(): SettingsStore {
        closeCurrent()
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = next
        return SettingsStore.create(
            PreferenceDataStoreFactory.create(scope = next) { file },
            tokenStore,
        )
    }

    private suspend fun closeCurrent() {
        val running = scope ?: return
        running.cancel()
        running.coroutineContext.job.join()
        scope = null
    }

    @Test
    fun `Vorgaben stimmen mit iOS ueberein`() = runBlocking {
        val settings = open()
        assertEquals(40, settings.tableCount)
        assertEquals(6, settings.cutoffHour)
        assertEquals(0, settings.maxSeq)
        assertEquals(0, settings.catalogVersion)
        assertEquals("", settings.serverUrl)
        assertFalse(settings.isLoggedIn)
    }

    @Test
    fun `deviceId bleibt ueber Neustarts stabil`() = runBlocking {
        val first = open().deviceId
        assertTrue(first.isNotBlank())
        assertEquals(first, open().deviceId)
    }

    @Test
    fun `Werte ueberleben den Neustart`() = runBlocking {
        val settings = open()
        settings.setServerUrl("  http://kassa.local:8080  ")
        settings.setDeviceName("Schank")
        settings.setMaxSeq(42)
        settings.apply(ServerConfigDto(tableCount = 12, businessDayCutoffHour = 4, catalogVersion = 7))

        val reopened = open()
        assertEquals("die URL wird getrimmt", "http://kassa.local:8080", reopened.serverUrl)
        assertEquals("Schank", reopened.deviceName)
        assertEquals(42, reopened.maxSeq)
        assertEquals(12, reopened.tableCount)
        assertEquals(4, reopened.cutoffHour)
        assertEquals(7, reopened.catalogVersion)
    }

    @Test
    fun `Login setzt Token und deviceId`() = runBlocking {
        val settings = open()
        settings.setServerUrl("http://kassa.local:8080")
        settings.apply(LoginResponse(token = "geheim", deviceId = "geraet-7"))

        assertEquals("geheim", settings.token.value)
        assertEquals("geraet-7", settings.deviceId)
        assertTrue(settings.isLoggedIn)
        assertEquals("das Token liegt im sicheren Speicher", "geheim", tokenStore.read())
    }

    @Test
    fun `Abmelden loescht Token und maxSeq, behaelt aber Server und Geraetenamen`() = runBlocking {
        val settings = open()
        settings.setServerUrl("http://kassa.local:8080")
        settings.setDeviceName("Schank")
        settings.apply(LoginResponse(token = "geheim", deviceId = "geraet-7"))
        settings.setMaxSeq(99)

        settings.logout()

        assertNull(settings.token.value)
        assertNull(tokenStore.read())
        assertEquals(0, settings.maxSeq)
        assertEquals("http://kassa.local:8080", settings.serverUrl)
        assertEquals("Schank", settings.deviceName)
        assertFalse(settings.isLoggedIn)
        assertEquals("die deviceId bleibt", "geraet-7", settings.deviceId)
    }

    @Test
    fun `ohne Server-URL gilt man nicht als angemeldet`() = runBlocking {
        val settings = open()
        settings.apply(LoginResponse(token = "geheim", deviceId = "geraet-7"))
        assertFalse(settings.isLoggedIn)
    }
}
