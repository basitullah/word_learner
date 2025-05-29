package com.yourcompany.wordlearner

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog // Import for AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.yourcompany.wordlearner.manager.AlarmScheduler
import com.yourcompany.wordlearner.manager.SettingsManager
import com.yourcompany.wordlearner.services.InterruptionService

class MainActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_REQUEST_CODE = 101
    private val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 102
    private val MEDIA_PERMISSION_REQUEST_CODE = 103

    private lateinit var settingsManager: SettingsManager
    private lateinit var alarmScheduler: AlarmScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager(this)
        alarmScheduler = AlarmScheduler(this, settingsManager)

        findViewById<Button>(R.id.btn_parent_portal).setOnClickListener {
            // First check general permissions, then exact alarm permission if needed
            checkAndRequestPermissions()
        }

        // You might want to start the foreground service on app launch
        // if it's meant to run continuously, or when user enables the feature.
        // startService(Intent(this, InterruptionService::class.java))
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume called.")

        // It's generally better to schedule alarms when the feature is enabled
        // or after an interruption, not unconditionally in onResume.
        // However, if your app's core functionality relies on it starting immediately,
        // you can keep this, but be mindful of resource usage.
        // For debugging/initial setup, it's fine, but for production,
        // consider triggering this from a specific user action or a broadcast.
        // alarmScheduler.scheduleNextInterruption() // Consider removing or moving this
        checkExactAlarmPermission() // Check this permission in onResume
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestMediaPermissions()
            } else {
                // All required permissions (Overlay, Media/Storage, Record Audio) are granted
                // Now check for Exact Alarm permission if it's Android 12+
                checkExactAlarmPermissionAndNavigate()
            }
        } else { // Android 12 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestLegacyStorageAndAudioPermissions()
            } else {
                // All required permissions (Overlay, Media/Storage, Record Audio) are granted
                // On Android < 12, SCHEDULE_EXACT_ALARM is not a runtime permission,
                // so we can directly navigate or schedule.
                navigateToParentPortal()
            }
        }
    }

    private fun checkExactAlarmPermissionAndNavigate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12 (API 31)
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                showExactAlarmPermissionDialog()
            } else {
                navigateToParentPortal()
            }
        } else {
            // For Android < 12, SCHEDULE_EXACT_ALARM is granted via manifest
            navigateToParentPortal()
        }
    }

    // New function to check only the exact alarm permission (useful in onResume)
    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.d("MainActivity", "Exact alarm permission NOT granted onResume.")
                // You could show a subtle warning here, but don't force the dialog
                // unless the user tries to enable the feature. The `checkAndRequestPermissions`
                // flow handles the explicit request when the user taps btn_parent_portal.
            } else {
                Log.d("MainActivity", "Exact alarm permission granted onResume.")
                // If it's granted, and your app's logic requires it, you can schedule here.
                // alarmScheduler.scheduleNextInterruption()
            }
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        overlayPermissionLauncher.launch(intent)
        Toast.makeText(this, "Please grant 'Draw over other apps' permission.", Toast.LENGTH_LONG).show()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            Log.d("MainActivity", "Overlay permission granted. Checking other permissions.")
            checkAndRequestPermissions() // Re-check all permissions after overlay is granted
        } else {
            Toast.makeText(this, "Overlay permission denied. App functionality will be limited.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestMediaPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.RECORD_AUDIO
        )
        mediaPermissionsLauncher.launch(permissions)
    }

    private val mediaPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val readMediaAudioGranted = permissions[Manifest.permission.READ_MEDIA_AUDIO] ?: false

        if (recordAudioGranted && readMediaAudioGranted) {
            Log.d("MainActivity", "Media/Audio permissions granted. Checking exact alarm permission.")
            checkExactAlarmPermissionAndNavigate()
        } else {
            Toast.makeText(this, "Audio recording/storage permissions denied. Parent Portal features limited.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestLegacyStorageAndAudioPermissions() {
        val permissions = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO
        )
        legacyPermissionsLauncher.launch(permissions)
    }

    private val legacyPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val writeStorageGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false

        if (recordAudioGranted && writeStorageGranted) {
            Log.d("MainActivity", "Legacy storage/audio permissions granted. Navigating to portal.")
            navigateToParentPortal()
        } else {
            Toast.makeText(this, "Audio recording/storage permissions denied. Parent Portal features limited.", Toast.LENGTH_LONG).show()
        }
    }

    // New dialog and launcher for SCHEDULE_EXACT_ALARM
    private fun showExactAlarmPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("For timely word interruptions, please grant the 'Alarms & reminders' permission in settings. This is crucial for the app's core functionality.")
            .setPositiveButton("Go to Settings") { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                intent.data = Uri.fromParts("package", packageName, null)
                exactAlarmPermissionLauncher.launch(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Exact alarm permission denied. Word interruptions may not function correctly.", Toast.LENGTH_LONG).show()
                // Optionally disable features that rely on exact alarms
            }
            .create()
            .show()
    }

    private val exactAlarmPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            Log.d("MainActivity", "Exact alarm permission granted via settings.")
            navigateToParentPortal() // Proceed if granted
        } else {
            Toast.makeText(this, "Exact alarm permission denied. Word interruptions will not be reliable.", Toast.LENGTH_LONG).show()
        }
    }

    private fun navigateToParentPortal() {
        val intent = Intent(this, ParentActivity::class.java) // Ensure ParentActivity is correctly defined
        startActivity(intent)
    }
}