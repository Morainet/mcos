package com.morainet.mcos.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.morainet.mcos.runtime.api.McosRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Process-lifetime runtime handle for hosts that do not expose [McosHostApp]. */
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
        val deps = (context.applicationContext as? McosHostApp)?.mcosDeps
        val runtime = deps?.runtime ?: McosRuntimeHolder.runtime ?: return
        // driveScheduleTick suspends (it may launch a run); keep the process
        // alive across it with goAsync's PendingResult.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Application.onCreate starts rehydration asynchronously. A
                // cold-start alarm must wait for it before checking what is due.
                if (deps != null) RuntimeBootstrap.ensureRehydrated(deps).await()
                runtime.driveScheduleTick()
            } finally {
                pending.finish()
            }
        }
    }
}
