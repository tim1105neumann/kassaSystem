package at.heuriger.kassa

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Einstiegspunkt der App. Haelt den [AppContainer] und den app-weiten Scope.
 *
 * Der Container entsteht asynchron, weil [at.heuriger.kassa.data.SettingsStore]
 * einmal vom Datentraeger lesen muss; `onCreate` darf dafuer nicht blockieren.
 * Die UI wartet auf das [Deferred], statt einen halbfertigen Container zu sehen.
 */
class KassaApplication : Application() {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var container: Deferred<AppContainer>
        private set

    override fun onCreate() {
        super.onCreate()
        container = appScope.async { AppContainer.create(this@KassaApplication, appScope) }
    }
}
