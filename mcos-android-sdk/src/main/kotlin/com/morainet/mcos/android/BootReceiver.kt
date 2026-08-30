package com.morainet.mcos.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms schedules after a reboot (durable schedule hosting, 10 §6). The OS
 * cold-starts our process for `BOOT_COMPLETED`, so the host app's Application
 * (implementing [McosHostApp]) has already built the deps and kicked
 * rehydration; here we just hold the process alive until that finishes — by
 * which point the `WakeScheduler` has set the next exact alarm — via
 * [BroadcastReceiver.goAsync].
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val deps = (context.applicationContext as? McosHostApp)?.mcosDeps ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                RuntimeBootstrap.ensureRehydrated(deps).await()
            } finally {
                pending.finish()
            }
        }
    }
}
