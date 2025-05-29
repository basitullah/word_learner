package com.yourcompany.wordlearner.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.content.res.ResourcesCompat // ADD THIS IMPORT
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.yourcompany.wordlearner.MainActivity // Adjust package name
import com.yourcompany.wordlearner.R // Adjust package name
import com.yourcompany.wordlearner.data.AppDatabase // Adjust package name
import com.yourcompany.wordlearner.data.Word // Adjust package name
import com.yourcompany.wordlearner.data.WordDao // Adjust package name
import com.yourcompany.wordlearner.manager.AlarmScheduler // Adjust package name
import com.yourcompany.wordlearner.manager.AudioHelper // Adjust package name
import com.yourcompany.wordlearner.manager.SettingsManager // Adjust package name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

class InterruptionService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var audioHelper: AudioHelper
    private lateinit var wordDao: WordDao
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var settingsManager: SettingsManager

    private var currentWord: Word? = null
    private var correctWordText: String = ""
    private var currentFails: Int = 0
    private var quizLoadJob: Job? = null

    companion object {
        private const val MAX_FAILS = 3
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "InterruptionServiceChannel"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioHelper = AudioHelper(this)
        wordDao = AppDatabase.getDatabase(applicationContext).wordDao()
        settingsManager = SettingsManager(this)
        alarmScheduler = AlarmScheduler(this, settingsManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d("InterruptionService", "onStartCommand called.")

        // Start as foreground service to ensure reliability and handle Android 14+
        startForegroundService()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Please grant 'Draw over other apps' permission for the app to function.",
                Toast.LENGTH_LONG
            ).show()
            Log.w("InterruptionService", "SYSTEM_ALERT_WINDOW permission not granted.")
            hideOverlay() // Ensure no lingering overlay if permission is missing
            stopSelf()
            return START_NOT_STICKY
        }

        if (!::overlayView.isInitialized || overlayView.parent == null) {
            showOverlay()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pendingIntent =
            PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reading Interrupter Active")
            .setContentText("Learning sessions are scheduled.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with a proper icon
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Low priority to be less intrusive
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.d("InterruptionService", "Foreground service started.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Reading Interrupter Service Channel",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }


    private fun showOverlay() {
        val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        }

        params.gravity = Gravity.TOP or Gravity.START
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_quiz, null)

        val font: Typeface? = try {
            ResourcesCompat.getFont(this, R.font.font) // FIX: Use ResourcesCompat
        } catch (e: Exception) {
            Log.e("InterruptionService", "Font not found: ${e.message}")
            null
        }

        overlayView.findViewById<TextView>(R.id.tv_word_prompt)?.apply {
            if (font != null) typeface = font
        }
        // Apply font to buttons dynamically as they are created

        try {
            windowManager.addView(overlayView, params)
            Log.d("InterruptionService", "Overlay added.")
            loadQuizContent()
        } catch (e: WindowManager.BadTokenException) {
            Log.e("InterruptionService", "Failed to add overlay window: ${e.message}")
            Toast.makeText(
                this,
                "Failed to show quiz overlay. Check permissions.",
                Toast.LENGTH_LONG
            ).show()
            stopSelf() // Stop service if overlay cannot be added
        }
    }

   /* private fun loadQuizContent() {
        quizLoadJob?.cancel() // Cancel any ongoing quiz loading
        quizLoadJob = lifecycleScope.launch(Dispatchers.IO) {
            val allWords = wordDao.getAllWords().firstOrNull()

            if (allWords.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@InterruptionService,
                        "No words configured. Please add words in parent settings.",
                        Toast.LENGTH_LONG
                    ).show()
                    hideOverlay()
                    stopSelf()
                }
                return@launch
            }

            // Filter out words without audio paths
            val wordsWithAudio = allWords.filter { word ->
                word.audioFilePath.let { path ->
                    File(path).exists().also { exists ->
                        if (!exists) {
                            Log.w(
                                "InterruptionService",
                                "Audio file missing for word: ${word.text}"
                            )
                        }
                    }
                } ?: false
            }

            // Ensure there are enough words for options (1 correct + 4 incorrect)
            if (allWords.size < 5) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@InterruptionService,
                        "Please add at least 5 words to the dictionary for quizzes.",
                        Toast.LENGTH_LONG
                    ).show()
                    hideOverlay()
                    stopSelf()
                }
                return@launch
            }

            currentWord = wordsWithAudio.random()
            correctWordText = currentWord?.text ?: ""
            val correctAudioPath = currentWord?.audioFilePath

            // Get 4 distinct random incorrect words (that have audio)
            val incorrectWords = wordsWithAudio
                .filter { it.id != currentWord?.id }
                .shuffled()
                .take(4)
                .map { it.text }

            // Combine correct and incorrect words and shuffle
            val options = (incorrectWords + correctWordText).shuffled()

            withContext(Dispatchers.Main) {
                val tvPrompt: TextView = overlayView.findViewById(R.id.tv_word_prompt)
                val btnContainer: LinearLayout = overlayView.findViewById(R.id.ll_button_container)

                tvPrompt.text = "Listen and choose:" // Or "What word is this?"

                btnContainer.removeAllViews() // Clear previous buttons

                val font: Typeface? = try {
                    ResourcesCompat.getFont(
                        this@InterruptionService,
                        R.font.font
                    ) // FIX: Use ResourcesCompat
                } catch (e: Exception) {
                    null
                }

                options.forEach { wordOption ->
                    val button = Button(this@InterruptionService).apply {
                        text = wordOption
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0, // Weight will distribute height
                            1f
                        ).apply {
                            setMargins(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
                        }
                        setBackgroundColor(
                            ContextCompat.getColor(
                                context,
                                R.color.button_default_background
                            )
                        )
                        setTextColor(ContextCompat.getColor(context, android.R.color.white))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f) // Large text size
                        if (font != null) typeface = font

                        setOnClickListener {
                            onOptionSelected(wordOption)
                        }
                    }
                    btnContainer.addView(button)
                }

                // Play audio for the correct word
                correctAudioPath?.let { path ->
                    if (File(path).exists()) {
                        audioHelper.playAudio(path)
                    } else {
                        Log.e("InterruptionService", "Audio file not found at: $path")
                        Toast.makeText(
                            this@InterruptionService,
                            "Audio playback failed",
                            Toast.LENGTH_SHORT
                        ).show()
                        hideOverlayAfterDelay(1000)
                    }
                } ?: run {
                    Log.e("InterruptionService", "No audio path for current word")
                    hideOverlayAfterDelay(1000)
                }
            }
        }
    }*/
   private fun loadQuizContent() {
       quizLoadJob?.cancel()
       quizLoadJob = lifecycleScope.launch(Dispatchers.IO) {
           try {
               // Use the new query to only get words with audio
               val wordsWithAudio = wordDao.getWordsWithAudio()

               if (wordsWithAudio.size < 5) {
                   withContext(Dispatchers.Main) {
                       val message = if (wordsWithAudio.isEmpty()) {
                           "No words with audio found. Please add words with recordings."
                       } else {
                           "Need at least 5 words with audio (found ${wordsWithAudio.size})."
                       }
                       Toast.makeText(this@InterruptionService, message, Toast.LENGTH_LONG).show()
                       hideOverlay()
                       stopSelf()
                   }
                   return@launch
               }

               // Select random word and verify its audio file exists
               currentWord = wordsWithAudio.random().also { word ->
                   if (!File(word.audioFilePath).exists()) {
                       Log.e("InterruptionService", "Audio file missing: ${word.audioFilePath}")
                       throw IllegalStateException("Audio file missing for ${word.text}")
                   }
               }

               correctWordText = currentWord!!.text

               // Get 4 incorrect options (with verified audio)
               val incorrectWords = wordsWithAudio
                   .filter { it.id != currentWord?.id }
                   .shuffled()
                   .take(4)
                   .also { list ->
                       if (list.size < 4) throw IllegalStateException("Not enough words for quiz")
                   }

               withContext(Dispatchers.Main) {
                   try {
                       setupQuizUI(correctWordText, incorrectWords.map { it.text })
                       audioHelper.playAudio(currentWord!!.audioFilePath)
                   } catch (e: Exception) {
                       Log.e("InterruptionService", "UI setup failed", e)
                       hideOverlay()
                       stopSelf()
                   }
               }
           } catch (e: Exception) {
               Log.e("InterruptionService", "Quiz loading failed", e)
               withContext(Dispatchers.Main) {
                   Toast.makeText(
                       this@InterruptionService,
                       "Failed to load quiz: ${e.message}",
                       Toast.LENGTH_LONG
                   ).show()
                   hideOverlay()
                   stopSelf()
               }
           }
       }
   }


    private fun setupQuizUI(correctAnswer: String, incorrectOptions: List<String>) {
        val tvPrompt: TextView = overlayView.findViewById(R.id.tv_word_prompt)
        val btnContainer: LinearLayout = overlayView.findViewById(R.id.ll_button_container)

        tvPrompt.text = "Listen and choose the word you heard:"
        btnContainer.removeAllViews()

        // Combine and shuffle options
        val allOptions = (incorrectOptions + correctAnswer).shuffled()

        allOptions.forEach { option ->
            Button(this).apply {
                text = option
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    setMargins(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())
                }
                setBackgroundColor(ContextCompat.getColor(context, R.color.button_default_background))
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                ResourcesCompat.getFont(context, R.font.font)?.let { typeface = it }
                setOnClickListener { onOptionSelected(option) }
                btnContainer.addView(this)
            }
        }
    }

    private fun onOptionSelected(selectedWord: String) {
        val buttons = mutableListOf<Button>()
        val btnContainer: LinearLayout = overlayView.findViewById(R.id.ll_button_container)
        for (i in 0 until btnContainer.childCount) {
            if (btnContainer.getChildAt(i) is Button) {
                buttons.add(btnContainer.getChildAt(i) as Button)
            }
        }

        if (selectedWord == correctWordText) {
            // Correct selection
            audioHelper.playSuccessSound()
            buttons.find { it.text == selectedWord }?.apply {
                setBackgroundColor(
                    ContextCompat.getColor(
                        this@InterruptionService,
                        R.color.correct_green
                    )
                )
                // You could add a checkmark icon here visually
            }
            currentFails = 0 // Reset fails on correct answer
            hideOverlayAfterDelay(1000) // Small delay for visual feedback
        } else {
            // Incorrect selection
            audioHelper.playFailSound()
            buttons.find { it.text == selectedWord }?.apply {
                setBackgroundColor(
                    ContextCompat.getColor(
                        this@InterruptionService,
                        R.color.incorrect_red
                    )
                )
                // You could add an X icon here visually
            }
            currentFails++

            if (currentFails >= MAX_FAILS) {
                Toast.makeText(this, "Let's try a new word!", Toast.LENGTH_SHORT).show()
                currentFails = 0
                hideOverlayAfterDelay(1500) { // Give a bit more time to see feedback
                    loadQuizContent() // Re-load quiz with new words after max fails
                }
            } else {
                Toast.makeText(this, "Try again!", Toast.LENGTH_SHORT).show()
                // Replay audio for the current word immediately after incorrect choice
                currentWord?.audioFilePath?.let { path ->
                    audioHelper.playAudio(path)
                }
                // Reset button colors after a short delay for next attempt
                lifecycleScope.launch(Dispatchers.Main) {
                    delay(500) // Give user time to see red X
                    buttons.forEach {
                        it.setBackgroundColor(
                            ContextCompat.getColor(
                                this@InterruptionService,
                                R.color.button_default_background
                            )
                        )
                    }
                }
            }
        }
    }

    private fun hideOverlayAfterDelay(delayMillis: Long, onHidden: (() -> Unit)? = null) {
        lifecycleScope.launch(Dispatchers.Main) {
            delay(delayMillis)
            hideOverlay()
            onHidden?.invoke()
        }
    }

    private fun hideOverlay() {
        if (::overlayView.isInitialized && overlayView.parent != null) {
            windowManager.removeView(overlayView)
            Log.d("InterruptionService", "Overlay removed.")
        }
        // Schedule the next interruption only AFTER the current one is handled and hidden
        alarmScheduler.scheduleNextInterruption()
        stopSelf() // Stop the service after the overlay is hidden
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("InterruptionService", "onDestroy called.")
        quizLoadJob?.cancel() // Cancel any ongoing coroutines
        hideOverlay() // Ensure overlay is removed on service destruction
        audioHelper.stopAudio() // Ensure audio is stopped
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // API 24 (Nougat)
            stopForeground(Service.STOP_FOREGROUND_REMOVE) // For API 24+
        } else {
            @Suppress("DEPRECATION") // Suppress the deprecation warning for older method
            stopForeground(true) // For API < 24, passing true removes the notification
        }
    }

    @Suppress("RedundantOverride") // FIX: Add this annotation
    override fun onBind(intent: Intent): IBinder? { // FIX: Changed from Intent? to Intent
        super.onBind(intent)
        return null // Not a bound service
    }

    // Extension function to convert DP to Pixels
    private fun Int.dpToPx(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}