package com.yourcompany.wordlearner.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yourcompany.wordlearner.manager.AlarmScheduler // Adjust package name
import com.yourcompany.wordlearner.manager.SettingsManager // Adjust package name

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed. Rescheduling alarms.")
            // Re-schedule the next interruption
            val settingsManager = SettingsManager(context)
            val alarmScheduler = AlarmScheduler(context, settingsManager)
            alarmScheduler.scheduleNextInterruption()
        }
    }
}