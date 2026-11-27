package com.morainet.mcos.android.demo

import android.app.Application
import android.content.pm.ApplicationInfo
import com.morainet.mcos.android.AppDeps
import com.morainet.mcos.android.CompositionRoot
import com.morainet.mcos.android.McosHostApp
import com.morainet.mcos.android.RuntimeBootstrap
import com.morainet.mcos.android.demo.device.AdbInspectionPlugin
import com.morainet.mcos.android.demo.records.RecordExportPlugin
import java.io.File

/**
 * Owns the process-lifetime [AppDeps] (durable schedule hosting, 10 §6). Built
 * here — before any Activity — so a broadcast receiver that cold-starts the
 * process (a schedule alarm or `BOOT_COMPLETED`) finds a live runtime with no
 * Activity present; [MainActivity] reuses this same graph. On start it kicks
 * the process-once rehydrate so schedules re-arm (and the next exact alarm is
 * set) even on a headless launch.
 *
 * Implements [McosHostApp] so the SDK's receivers (boot re-arm) can reach the
 * deps without the library depending on any concrete app class — an
 * integrating app does exactly the same.
 */
class McosApplication : Application(), McosHostApp {

    lateinit var deps: AppDeps
        private set

    /** Null only in the window before [onCreate] — an early broadcast sees null and skips. */
    override val mcosDeps: AppDeps?
        get() = if (::deps.isInitialized) deps else null

    override fun onCreate() {
        super.onCreate()
        val builtIns = CompositionRoot.defaultBuiltIns() + RecordExportPlugin(File(filesDir, "record-exports"))
        val debugTools = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            listOf(AdbInspectionPlugin(File(filesDir, "agent-adb")))
        } else {
            emptyList()
        }
        deps = CompositionRoot.create(
            this,
            builtIns = builtIns + debugTools,
        )
        RuntimeBootstrap.ensureRehydrated(deps)
    }
}
