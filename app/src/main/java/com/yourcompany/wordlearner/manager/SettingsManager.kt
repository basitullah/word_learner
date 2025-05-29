package com.yourcompany.wordlearner.manager

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_INTERVAL = "interruption_interval"
        const val KEY_ACTIVE_DAYS = "active_days" // Stored as a comma-separated string, e.g., "Mon,Tue,Wed"
        const val KEY_PARENT_PIN = "parent_pin" // For a real app, hash this securely!
        const val DEFAULT_PIN = "1234"
    }

    // Interruption interval in milliseconds (default: 5 minutes)
    var interruptionInterval: Long
        get() {
            val intervalString = prefs.getString(KEY_INTERVAL, "1 minutes") ?: "1 minutes"
            return when (intervalString) {
                "1 minutes" -> 1 * 60 * 1000L
                "10 minutes" -> 10 * 60 * 1000L
                "30 minutes" -> 30 * 60 * 1000L
                "1 hour" -> 60 * 60 * 1000L
                else -> 5 * 60 * 1000L // Fallback
            }
        }
        set(value) {
            val intervalString = when (value) {
                1 * 60 * 1000L -> "1 minutes"
                10 * 60 * 1000L -> "10 minutes"
                30 * 60 * 1000L -> "30 minutes"
                60 * 60 * 1000L -> "1 hour"
                else -> "1 minutes" // Fallback
            }
            prefs.edit().putString(KEY_INTERVAL, intervalString).apply()
        }

    // Interruption interval as a string for Spinner selection (e.g., "5 minutes")
    var interruptionIntervalString: String
        get() = prefs.getString(KEY_INTERVAL, "1 minutes") ?: "1 minutes"
        set(value) {
            prefs.edit().putString(KEY_INTERVAL, value).apply()
        }


    // Active days stored as a Set<String> (e.g., "Monday", "Tuesday")
    var activeDays: Set<String>
        get() = prefs.getStringSet(KEY_ACTIVE_DAYS, getDefaultActiveDays()) ?: getDefaultActiveDays()
        set(value) = prefs.edit().putStringSet(KEY_ACTIVE_DAYS, value).apply()

    var parentPin: String
        get() = prefs.getString(KEY_PARENT_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        set(value) = prefs.edit().putString(KEY_PARENT_PIN, value).apply()

    private fun getDefaultActiveDays(): Set<String> {
        // Default to all weekdays
        return setOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")
    }

    fun getDayOfWeekName(dayOfWeek: Int): String {
        return when (dayOfWeek) {
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"
            Calendar.SUNDAY -> "Sunday"
            else -> ""
        }
    }
}