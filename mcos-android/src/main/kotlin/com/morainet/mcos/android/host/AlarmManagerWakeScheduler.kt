package com.morainet.mcos.android.host

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.morainet.mcos.android.ScheduleAlarmReceiver
import com.morainet.mcos.runtime.core.workflow.WakeScheduler

/**
 * [WakeScheduler] backed by [AlarmManager] (durable schedule hosting, 10 §6):
 * schedules an **exact, allow-while-idle** alarm at the earliest cron boundary
 * so a backgrounded or Doze'd app still fires its schedules. A single alarm is
 * kept (one [PendingIntent], `FLAG_UPDATE_CURRENT`) — the manager re-requests
 * the earliest boundary on every arm/disarm/fire, replacing the prior one.
 *
 * When exact alarms aren't permitted (API 31+ user/policy revocation) it
 * degrades to `setAndAllowWhileIdle` (inexact, batched by the OS) rather than
 * throwing — schedules still fire, just less precisely.
 */
class AlarmManagerWakeScheduler(context: Context) : WakeScheduler {

    private val appContext = context.applicationContext
    private val alarmManager =
        appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun scheduleWakeAt(epochMs: Long) {
        val intent = Intent(appContext, ScheduleAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, pending)
            }
        } catch (_: SecurityException) {
            // Exact-alarm access was revoked between the check and the call.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, pending)
        }
    }

    private companion object {
        // Stable request code so FLAG_UPDATE_CURRENT replaces the one pending wake.
        const val REQUEST_CODE = 0x5CED
    }
}
