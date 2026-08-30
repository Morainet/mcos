package com.morainet.mcos.android

/**
 * Implemented by the host app's [android.app.Application] so SDK components
 * that cold-start the process ([BootReceiver] on `BOOT_COMPLETED`) can reach
 * the process-lifetime [AppDeps] without the SDK depending on any app class.
 *
 * The reference shell's `McosApplication` implements it after
 * [CompositionRoot.create]; a custom-host app wiring its own UI does the same.
 * Returning null before composition finished is fine — the receiver skips the
 * re-arm rather than crashing an early boot.
 */
interface McosHostApp {
    /** The process deps, or null while [CompositionRoot.create] has not run yet. */
    val mcosDeps: AppDeps?
}
