package com.yourcompany.wordlearner.manager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.yourcompany.wordlearner.receivers.InterruptionReceiver // Adjust package name
import java.util.Calendar

class AlarmScheduler(private val context: Context, private val settingsManager: SettingsManager) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val code = 0 // Unique request code for PendingIntent

    fun scheduleNextInterruption() {
        Log.d("AlarmScheduler", "Attempting to schedule next interruption.")
        cancelAlarm() // Cancel any existing alarms first

        if (!isTodayActiveDay()) {
            Log.d("AlarmScheduler", "Today is not an active day. Skipping scheduling.")
            return
        }

        val intervalMillis = settingsManager.interruptionInterval

        // IMPORTANT: Check for SCHEDULE_EXACT_ALARM permission on Android 12 (API 31) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31 (Android 12)
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.e("AlarmScheduler", "Exact alarm permission not granted. Cannot schedule interruption.")
                // It's crucial not to proceed with scheduling here if permission is missing.
                // The MainActivity is responsible for prompting the user.
                return
            }
        }

        if (intervalMillis <= 0) {
            Log.w("AlarmScheduler", "Interruption interval is 0 or less. Not scheduling.")
            return
        }

        val triggerTime = System.currentTimeMillis() + intervalMillis

        val intent = Intent(context, InterruptionReceiver::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context,
            code, intent, pendingIntentFlags)


        // Use setExactAndAllowWhileIdle for reliability where available, otherwise setExact
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            // setExact is reliable enough for API < 23 as Doze mode is not as aggressive
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
        Log.d("AlarmScheduler", "Next interruption scheduled for: ${java.util.Date(triggerTime)} (Interval: ${intervalMillis / 1000 / 60} mins)")
    }

    private fun cancelAlarm() {
        val intent = Intent(context, InterruptionReceiver::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context,
            code, intent, pendingIntentFlags)
        alarmManager.cancel(pendingIntent)
        Log.d("AlarmScheduler", "Alarm cancelled.")
    }

    private fun isTodayActiveDay(): Boolean {
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val todayName = settingsManager.getDayOfWeekName(dayOfWeek)
        return settingsManager.activeDays.contains(todayName)
    }
}