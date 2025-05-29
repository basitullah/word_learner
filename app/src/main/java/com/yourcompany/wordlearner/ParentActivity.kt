package com.yourcompany.wordlearner

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yourcompany.wordlearner.data.AppDatabase
import com.yourcompany.wordlearner.data.Word
import com.yourcompany.wordlearner.data.WordDao
import com.yourcompany.wordlearner.manager.AlarmScheduler
import com.yourcompany.wordlearner.manager.AudioHelper
import com.yourcompany.wordlearner.manager.SettingsManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

class ParentActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var wordDao: WordDao
    private lateinit var audioHelper: AudioHelper

    private lateinit var rvWords: RecyclerView
    private lateinit var wordAdapter: WordAdapter
    private lateinit var fabAddWord: FloatingActionButton

    private lateinit var spinnerInterval: Spinner
    private lateinit var cbMon: CheckBox
    private lateinit var cbTue: CheckBox
    private lateinit var cbWed: CheckBox
    private lateinit var cbThu: CheckBox
    private lateinit var cbFri: CheckBox
    private lateinit var cbSat: CheckBox
    private lateinit var cbSun: CheckBox
    private lateinit var btnChangePin: Button

    private var isRecording = false // Tracks if recording is currently active within the dialog
    private var currentDialogRecordingFilePath: String? = null // Path for the recording in the *current dialog session*

    // Permission launcher for audio and storage
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            Toast.makeText(this, "All required permissions granted for recording and storage.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this,
                "Some permissions were denied. Recording and playback may not work correctly.",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPinEntryDialog { authenticated ->
            if (authenticated) {
                setContentView(R.layout.activity_parent)
                initializeComponents()
                loadSettings()
                observeWords()
                checkAndRequestPermissions() // Ensure permissions are checked when ParentActivity is entered
            } else {
                finish() // If PIN is incorrect, close the activity
            }
        }
    }

    private fun initializeComponents() {
        settingsManager = SettingsManager(this)
        alarmScheduler = AlarmScheduler(this, settingsManager)
        wordDao = AppDatabase.getDatabase(applicationContext).wordDao()
        audioHelper = AudioHelper(this) // Initialize AudioHelper here

        rvWords = findViewById(R.id.rv_words)
        fabAddWord = findViewById(R.id.btn_add_word)

        wordAdapter = WordAdapter(
            onEditClick = { word -> showAddEditWordDialog(word) },
            onDeleteClick = { word -> confirmDeleteWord(word) },
            onPlayAudioClick = { word -> word.audioFilePath?.let { audioHelper.playAudio(it) } }
        )
        rvWords.layoutManager = LinearLayoutManager(this)
        rvWords.adapter = wordAdapter

        fabAddWord.setOnClickListener {
            showAddEditWordDialog(null) // Pass null for a new word
        }

        // Initialize Settings UI
        spinnerInterval = findViewById(R.id.spinner_interval)
        ArrayAdapter.createFromResource(
            this,
            R.array.interval_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerInterval.adapter = adapter
        }

        spinnerInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedIntervalString = parent.getItemAtPosition(position).toString()
                if (settingsManager.interruptionIntervalString != selectedIntervalString) {
                    settingsManager.interruptionIntervalString = selectedIntervalString
                    Toast.makeText(this@ParentActivity, "Interval set to $selectedIntervalString", Toast.LENGTH_SHORT).show()
                    alarmScheduler.scheduleNextInterruption() // Reschedule alarm when settings change
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        cbMon = findViewById(R.id.cb_mon)
        cbTue = findViewById(R.id.cb_tue)
        cbWed = findViewById(R.id.cb_wed)
        cbThu = findViewById(R.id.cb_thu)
        cbFri = findViewById(R.id.cb_fri)
        cbSat = findViewById(R.id.cb_sat)
        cbSun = findViewById(R.id.cb_sun)
        val dayCheckBoxes = mapOf(
            "Monday" to cbMon, "Tuesday" to cbTue, "Wednesday" to cbWed,
            "Thursday" to cbThu, "Friday" to cbFri, "Saturday" to cbSat, "Sunday" to cbSun
        )

        for ((dayName, checkBox) in dayCheckBoxes) {
            checkBox.setOnCheckedChangeListener { _, _ ->
                val selectedDays = dayCheckBoxes.filter { it.value.isChecked }.keys
                if (settingsManager.activeDays != selectedDays) {
                    settingsManager.activeDays = selectedDays
                    Toast.makeText(this, "Active days updated.", Toast.LENGTH_SHORT).show()
                    alarmScheduler.scheduleNextInterruption() // Reschedule alarm when settings change
                }
            }
        }

        btnChangePin = findViewById(R.id.btn_change_pin)
        btnChangePin.setOnClickListener {
            showChangePinDialog()
        }
    }

    private fun showPinEntryDialog(callback: (Boolean) -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_entry, null)
        val etPin = dialogView.findViewById<TextInputEditText>(R.id.et_pin)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Parent Access")
            .setView(dialogView)
            .setCancelable(false) // Don't allow dismissing without PIN
            .setPositiveButton("Enter", null) // Set to null to handle manually
            .setNegativeButton("Cancel") { _, _ ->
                callback(false)
            }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val enteredPin = etPin.text.toString()
                if (enteredPin == SettingsManager(this).parentPin) { // Use settingsManager
                    dialog.dismiss()
                    callback(true)
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun showChangePinDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_entry, null)
        val etNewPin = dialogView.findViewById<TextInputEditText>(R.id.et_pin)
        etNewPin.hint = "New PIN (4 digits)"
        etNewPin.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        etNewPin.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s?.length ?: 0 > 4) {
                    etNewPin.setText(s.toString().substring(0, 4))
                    etNewPin.setSelection(4)
                }
            }
        })

        AlertDialog.Builder(this)
            .setTitle("Change Parent PIN")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newPin = etNewPin.text.toString()
                if (newPin.length == 4 && newPin.all { it.isDigit() }) {
                    settingsManager.parentPin = newPin
                    Toast.makeText(this, "PIN changed successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PIN must be 4 digits.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadSettings() {
        // Set interval spinner
        val intervalOptions = resources.getStringArray(R.array.interval_options)
        val currentIntervalString = settingsManager.interruptionIntervalString
        val selection = intervalOptions.indexOf(currentIntervalString)
        if (selection != -1) {
            spinnerInterval.setSelection(selection)
        }

        // Set active days checkboxes
        val activeDays = settingsManager.activeDays
        val dayCheckBoxes = mapOf(
            "Monday" to cbMon, "Tuesday" to cbTue, "Wednesday" to cbWed,
            "Thursday" to cbThu, "Friday" to cbFri, "Saturday" to cbSat, "Sunday" to cbSun
        )
        for ((dayName, checkBox) in dayCheckBoxes) {
            checkBox.isChecked = activeDays.contains(dayName)
        }
    }

    private fun observeWords() {
        lifecycleScope.launch {
            wordDao.getAllWords().collectLatest { words ->
                wordAdapter.submitList(words)
            }
        }
    }

    private fun showAddEditWordDialog(word: Word?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_edit_word, null)
        val etWordText = dialogView.findViewById<TextInputEditText>(R.id.et_word_text)
        val btnRecordToggle = dialogView.findViewById<Button>(R.id.btn_record_toggle)
        val btnPlayRecorded = dialogView.findViewById<Button>(R.id.btn_play_recorded)
        val tvRecordingStatus = dialogView.findViewById<TextView>(R.id.tv_recording_status)

        var audioFileToSavePath: String? = word?.audioFilePath
        isRecording = false
        currentDialogRecordingFilePath = null

        // Pre-populate text and enable play button if editing an existing word with valid audio
        word?.let {
            etWordText.setText(it.text)
            if (it.audioFilePath != null && audioHelper.isAudioFileValid(it.audioFilePath)) {
                btnPlayRecorded.isEnabled = true
                tvRecordingStatus.text = "Audio recorded"
            } else {
                btnPlayRecorded.isEnabled = false
                tvRecordingStatus.text = "No audio recorded / Invalid file"
            }
        } ?: run {
            // For new words, disable play button initially
            btnPlayRecorded.isEnabled = false
            tvRecordingStatus.text = "Record audio"
        }


        btnRecordToggle.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                Toast.makeText(this, "Microphone permission is required to record audio.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (isRecording) {
                // STOP RECORDING LOGIC
                val recordedPath = audioHelper.stopRecording()
                val recordedFile = recordedPath?.let { File(it) }

                if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0) {
                    audioFileToSavePath = recordedPath // Assign the valid path
                    tvRecordingStatus.text = "Recording saved: ${recordedFile.name}"
                    btnPlayRecorded.isEnabled = true
                    Log.d("ParentActivity", "Recording successful. File: ${recordedFile.absolutePath}, Size: ${recordedFile.length()} bytes")
                    Toast.makeText(this, "Recording successful!", Toast.LENGTH_SHORT).show()
                } else {
                    // Recording failed or resulted in an empty/non-existent file
                    audioFileToSavePath = null // Crucial: ensure path is null if file is bad
                    tvRecordingStatus.text = "Recording failed or empty! Try again."
                    btnPlayRecorded.isEnabled = false
                    Log.e("ParentActivity", "Recording stopped but file is invalid/empty or missing at: $recordedPath")
                    Toast.makeText(this, "Recording failed or file is empty! Try again.", Toast.LENGTH_LONG).show()
                }
                btnRecordToggle.text = "Record"
                isRecording = false
                currentDialogRecordingFilePath = null // Recording session concluded
            } else {
                // START RECORDING LOGIC
                val wordText = etWordText.text.toString().trim()
                if (wordText.isEmpty()) {
                    Toast.makeText(this, "Please enter a word first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val newFilePath = generateAudioFilePath(wordText)
                if (audioHelper.startRecording(newFilePath)) {
                    currentDialogRecordingFilePath = newFilePath
                    tvRecordingStatus.text = "Recording..."
                    btnRecordToggle.text = "Stop"
                    btnPlayRecorded.isEnabled = false // Disable play button while recording
                    isRecording = true
                } else {
                    Toast.makeText(this, "Failed to start recording. Check microphone.", Toast.LENGTH_SHORT).show()
                    currentDialogRecordingFilePath = null // Ensure temp path is cleared on start failure
                    isRecording = false // Ensure state is consistent
                }
            }
        }

        btnPlayRecorded.setOnClickListener {
            audioFileToSavePath?.let { path ->
                if (audioHelper.isAudioFileValid(path)) { // Re-check validity before playing
                    audioHelper.playAudio(path)
                } else {
                    Toast.makeText(this, "Audio file not found or corrupted. Please record the word again.", Toast.LENGTH_LONG).show()
                    Log.w("ParentActivity", "Attempted to play invalid audio file: $path")
                    audioFileToSavePath = null // Invalidate the path if it's found to be bad
                    btnPlayRecorded.isEnabled = false // Disable play button
                    tvRecordingStatus.text = "Recording corrupted/missing"
                }
            } ?: Toast.makeText(this, "No audio recorded to play.", Toast.LENGTH_SHORT).show()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save", null) // Set to null to handle manually
            .setNegativeButton("Cancel") { _, _ ->
                // Dialog dismissed by Cancel button
                if (isRecording) { // If recording was active and cancel was pressed
                    audioHelper.stopRecording() // This will also delete temp file if currentDialogRecordingFilePath points to it
                }
                currentDialogRecordingFilePath = null // Clear dialog's temp path
                isRecording = false
            }
            .create()

        dialog.setOnDismissListener {
            Log.d("ParentActivity", "Dialog dismissed. Cleaning up recording state. isRecording: $isRecording")
            if (isRecording) { // If still recording when dialog is dismissed
                audioHelper.stopRecording() // Stop and release resources
            }
            currentDialogRecordingFilePath = null // Clear dialog's temp path again for robustness
            isRecording = false // Ensure activity's state is reset
        }

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val enteredText = etWordText.text.toString().trim()
                if (enteredText.isEmpty()) {
                    Toast.makeText(this, "Word text cannot be empty.", Toast.LENGTH_SHORT).show()
                } else if (audioFileToSavePath == null || !audioHelper.isAudioFileValid(audioFileToSavePath)) { // Use helper function
                    Toast.makeText(this, "Please record valid audio for the word to save.", Toast.LENGTH_LONG).show()
                    Log.w("ParentActivity", "Attempted to save word with invalid audio path: $audioFileToSavePath")
                } else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        // If it's an edit and the audio file path has changed, delete the old audio file
                        if (word != null && word.audioFilePath != audioFileToSavePath) {
                            val oldAudioFile = File(word.audioFilePath)
                            if (oldAudioFile.exists()) {
                                oldAudioFile.delete()
                                Log.d("ParentActivity", "Deleted old audio file: ${word.audioFilePath}")
                            }
                        }

                        val newWord = Word(
                            id = word?.id ?: 0,
                            text = enteredText,
                            audioFilePath = audioFileToSavePath!!.also {
                                Log.d("ParentActivity", "Saving word with audio path: $it")
                            }
                        )

                        if (word == null) {
                            wordDao.insertWord(newWord)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ParentActivity, "Word added!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            wordDao.updateWord(newWord)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ParentActivity, "Word updated!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteWord(word: Word) {
        AlertDialog.Builder(this)
            .setTitle("Delete Word")
            .setMessage("Are you sure you want to delete '${word.text}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    wordDao.deleteWord(word)
                    // Also delete the associated audio file
                    val audioFile = File(word.audioFilePath)
                    if (audioFile.exists()) {
                        audioFile.delete()
                        Log.d("ParentActivity", "Deleted audio file: ${word.audioFilePath}")
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ParentActivity, "'${word.text}' deleted.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // RECORD_AUDIO is needed on all versions for recording
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // Handle storage permissions based on API level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) { // Android 10 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        // For Android 11 & 12, neither WRITE_EXTERNAL_STORAGE nor READ_MEDIA_AUDIO is typically needed for
        // writing/reading app-specific internal storage (filesDir).

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ParentActivity", "onDestroy called. Releasing audio resources.")
        audioHelper.cleanup() // Call the combined cleanup method to release MediaPlayer/MediaRecorder
    }

    // Generates a unique file path for audio recordings within app's private storage
    private fun generateAudioFilePath(word: String): String {
        // Create 'audio' subdirectory in app's internal files directory if it doesn't exist
        val audioDir = File(filesDir, "audio").apply { mkdirs() }
        // Sanitize word text for filename and append timestamp for uniqueness
        val safeWord = word.replace("[^a-zA-Z0-9.-]".toRegex(), "_") // Replace non-alphanumeric/dot/dash with underscore
        return File(audioDir, "${safeWord}_${System.currentTimeMillis()}.mp4").absolutePath
    }
}


// RecyclerView Adapter and ViewHolder
class WordAdapter(
    private val onEditClick: (Word) -> Unit,
    private val onDeleteClick: (Word) -> Unit,
    private val onPlayAudioClick: (Word) -> Unit
) : androidx.recyclerview.widget.ListAdapter<Word, WordAdapter.WordViewHolder>(WordDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_word, parent, false)
        return WordViewHolder(view)
    }

    override fun onBindViewHolder(holder: WordViewHolder, position: Int) {
        val word = getItem(position)
        holder.bind(word)
    }

    inner class WordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvWordText: TextView = itemView.findViewById(R.id.tv_word_text)
        private val btnPlayAudio: ImageButton = itemView.findViewById(R.id.btn_play_audio)
        private val btnEditWord: ImageButton = itemView.findViewById(R.id.btn_edit_word)
        private val btnDeleteWord: ImageButton = itemView.findViewById(R.id.btn_delete_word)

        init {
            // Use ResourcesCompat for font to safely get font resources across API levels
            val font = ResourcesCompat.getFont(itemView.context, R.font.font)
            tvWordText.typeface = font
        }

        fun bind(word: Word) {
            tvWordText.text = word.text
            btnPlayAudio.setOnClickListener { onPlayAudioClick(word) }
            btnEditWord.setOnClickListener { onEditClick(word) }
            btnDeleteWord.setOnClickListener { onDeleteClick(word) }
        }
    }

    private class WordDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<Word>() {
        override fun areItemsTheSame(oldItem: Word, newItem: Word): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Word, newItem: Word): Boolean {
            return oldItem == newItem
        }
    }
}