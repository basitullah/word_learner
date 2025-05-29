package com.yourcompany.wordlearner.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yourcompany.wordlearner.services.InterruptionService // Adjust package name

class InterruptionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("InterruptionReceiver", "Alarm received. Starting InterruptionService.")
        // Start the service that will display the overlay
        val serviceIntent = Intent(context, InterruptionService::class.java)
        context.startService(serviceIntent)
    }
}