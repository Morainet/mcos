package com.morainet.mcos.android

import android.app.Application

/**
 * Owns the process-lifetime [AppDeps] (durable schedule hosting, 10 §6). Built
 * here — before any Activity — so a broadcast receiver that cold-starts the
 * process (a schedule alarm or `BOOT_COMPLETED`) finds a live runtime with no
 * Activity present; [MainActivity] reuses this same graph. On start it kicks
 * the process-once rehydrate so schedules re-arm (and the next exact alarm is
 * set) even on a headless launch.
 */
class McosApplication : Application() {

    lateinit var deps: AppDeps
        private set

    override fun onCreate() {
        super.onCreate()
        deps = CompositionRoot.create(this)
        RuntimeBootstrap.ensureRehydrated(deps)
    }
}
