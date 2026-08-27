package com.morainet.mcos.android

import com.morainet.mcos.marketplace.InstallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Process-once rehydration of persisted marketplace installs + armed schedules
 * (durable schedule hosting, 10 §6). Runs whether the process was started by
 * the launcher, an AlarmManager schedule wake, or `BOOT_COMPLETED`, so a
 * scheduled workflow re-registers and re-arms without the Activity ever
 * opening. The shared [Deferred] lets every caller await the single run —
 * the boot receiver in particular must not let the process die before the
 * next exact alarm is set.
 */
object RuntimeBootstrap {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var job: Deferred<Int>? = null

    /** The deps the cached [job] rehydrated — keyed so a new graph re-runs. */
    @Volatile
    private var forDeps: AppDeps? = null

    /**
     * Rehydrate once per [AppDeps]; concurrent callers share (and can await)
     * the same run. Keyed on the deps instance: production builds one graph (so
     * this is process-once), while each test builds its own graph and re-runs.
     * Resolves to the number of restored plugins + re-armed schedules, so a
     * caller can refresh its palette only when something actually changed.
     */
    fun ensureRehydrated(deps: AppDeps): Deferred<Int> {
        job?.let { if (forDeps === deps) return it }
        return synchronized(this) {
            job?.let { if (forDeps === deps) return it }
            scope.async { rehydrate(deps) }.also { job = it; forDeps = deps }
        }
    }

    private suspend fun rehydrate(deps: AppDeps): Int = runCatching {
        val outcomes = deps.marketplace.installer.rehydrateInstalled(
            pluginFactory = { pkg -> deps.marketplace.pluginFactory.factoryFor(pkg) },
            seedKey = { key -> deps.marketplace.keyStore.put(key) },
        )
        val restored = outcomes.filter { it.state == InstallState.INSTALLED }
        restored.forEach { it.plugin?.onLoad(deps.hostServices) }
        // Workflows are registered now → re-arm persisted schedules, which also
        // sets the next AlarmManager wake via the WakeScheduler.
        val rearmed = deps.runtime.rehydrateSchedules()
        restored.size + rearmed
    }.getOrDefault(0)
}
