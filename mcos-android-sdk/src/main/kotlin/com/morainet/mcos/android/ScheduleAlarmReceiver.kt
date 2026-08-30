package com.morainet.mcos.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.morainet.mcos.runtime.api.McosRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Process-lifetime handle to the live [McosRuntime] so a broadcast receiver can
 * reach it (durable schedule hosting, 10 §6). [CompositionRoot] sets it when it
 * builds the app's dependency graph.
 *
 * 🟡 Part-2 boundary: this is only populated once the app has built its deps
 * (MainActivity.onCreate). A schedule alarm that starts the process *cold* (app
 * previously killed) finds it null and no-ops; full cold-start firing needs an
 * Application-scoped headless runtime + boot re-arm, which remain follow-ups
 * (needs on-device testing).
 */
object McosRuntimeHolder {
    @Volatile
    var runtime: McosRuntime? = null
}

/**
 * Wake target for [com.morainet.mcos.android.host.AlarmManagerWakeScheduler]:
 * an exact alarm at the earliest cron boundary fires here and we drive one
 * schedule tick, which runs anything due and re-arms the next wake.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runtime = McosRuntimeHolder.runtime ?: return
        // driveScheduleTick suspends (it may launch a run); keep the process
        // alive across it with goAsync's PendingResult.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                runtime.driveScheduleTick()
            } finally {
                pending.finish()
            }
        }
    }
}
