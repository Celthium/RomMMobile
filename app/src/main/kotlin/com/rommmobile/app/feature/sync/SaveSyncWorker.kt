package com.rommmobile.app.feature.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rommmobile.app.di.ApplicationScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Automatic runs: the hourly one and the one at app start. Manual runs call the engine directly. */
@HiltWorker
class SaveSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: SaveSyncEngine,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val r = engine.runAll(auto = true)
        return if (r.errors > 0 && runAttemptCount < 2 && r.uploaded + r.downloaded == 0) Result.retry() else Result.success()
    }
}

/**
 * Keeps WorkManager in line with the settings, and writes waiting files when the user comes back to
 * the app (often straight from RetroArch, which is then usually closed).
 */
@Singleton
class SaveSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SyncPrefs,
    private val engine: SaveSyncEngine,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val wm get() = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    @Volatile private var lastForegroundRun = 0L

    fun start() {
        scope.launch {
            prefs.settings.map { it.enabled && (it.kindAuto(SaveKind.SAVE) || it.kindAuto(SaveKind.STATE)) }
                .distinctUntilChanged()
                .collect { auto ->
                    if (auto) {
                        wm.enqueueUniquePeriodicWork(
                            PERIODIC,
                            ExistingPeriodicWorkPolicy.KEEP,
                            PeriodicWorkRequestBuilder<SaveSyncWorker>(1, TimeUnit.HOURS).setConstraints(network).build(),
                        )
                        // A newly enqueued periodic job runs right away: no extra one-off here.
                    } else {
                        wm.cancelUniqueWork(PERIODIC)
                    }
                }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch {
                    val p = prefs.snapshot()
                    if (!p.enabled) return@launch
                    engine.applyPending(force = false)
                    // Back in the app after a while: a quick automatic run, at most every 10 min.
                    val now = System.currentTimeMillis()
                    if (now - lastForegroundRun > 10 * 60_000L && (p.kindAuto(SaveKind.SAVE) || p.kindAuto(SaveKind.STATE))) {
                        lastForegroundRun = now
                        runOnceSoon()
                    }
                }
            }
        })
    }

    private fun runOnceSoon() {
        wm.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SaveSyncWorker>().setConstraints(network).build())
    }

    private companion object {
        const val PERIODIC = "save-sync-periodic"
        const val ONCE = "save-sync-once"
    }
}
