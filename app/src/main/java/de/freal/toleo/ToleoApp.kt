package de.freal.toleo

import android.app.Application
import de.freal.toleo.sync.SyncWorker

class ToleoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.shopAccess.applyProxy()
        if (container.auth.isLoggedIn) SyncWorker.schedulePeriodic(this)
    }
}
