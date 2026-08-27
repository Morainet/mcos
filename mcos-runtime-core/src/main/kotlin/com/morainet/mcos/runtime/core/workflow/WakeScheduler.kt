package com.morainet.mcos.runtime.core.workflow

/**
 * Host seam for durable schedule wakeups ([10-roadmap.md §6]). The
 * [ScheduleTriggerManager] computes the earliest armed cron boundary and asks
 * the host to wake the process at that time; on wake the host drives
 * [com.morainet.mcos.runtime.api-side] `driveScheduleTick()`. The Android host
 * implements this with an `AlarmManager` exact alarm, so schedules fire even
 * when the app is backgrounded or in Doze.
 *
 * A runtime built without a [WakeScheduler] falls back to the in-process poll
 * driver alone — accurate only while the process is alive.
 */
interface WakeScheduler {
    /**
     * Ensure the process is woken at (or shortly after) [epochMs] to drive a
     * schedule tick. Called on every arm/disarm/fire with the new earliest
     * boundary; the host replaces any previously requested wake.
     */
    fun scheduleWakeAt(epochMs: Long)
}
