package de.freal.toleo.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.freal.toleo.ToleoApp
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ToleoApp).container
        if (!container.auth.isLoggedIn) return Result.success()
        if (inputData.getBoolean(KEY_LIBRARY, true)) {
            runCatching { container.library.refreshInventory() }
        }
        return when (val status = container.sync.sync()) {
            is SyncStatus.Failed -> if (status.needsLogin) Result.failure() else Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val KEY_LIBRARY = "library"
        private const val PERIODIC = "toleo-periodic-sync"
        private const val ONE_SHOT = "toleo-sync-now"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Push local changes soon (e.g. after closing a book). Works offline: runs once the network is back. */
        fun syncSoon(context: Context, includeLibrary: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_LIBRARY to includeLibrary))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(ONE_SHOT)
        }
    }
}
