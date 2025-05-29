package com.yourcompany.wordlearner.manager
import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import com.yourcompany.wordlearner.R
import kotlinx.coroutines.NonCancellable.start
import java.io.File

class AudioHelper(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingPath: String? = null // This path is managed by AudioHelper internally

    fun startRecording(outputFilePath: String): Boolean {
        stopRecording() // Ensure any previous recording is stopped and resources released

        return try {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outputFilePath)
                prepare()
                start()
            }
            currentRecordingPath = outputFilePath // Store the path of the current recording
            Log.d("AudioHelper", "Recording started to: $outputFilePath")
            true
        } catch (e: Exception) {
            Log.e("AudioHelper", "Failed to start recording: ${e.message}", e)
            cleanup() // Release all resources if starting fails
            false
        }
    }

    fun stopRecording(): String? {
        return try {
            val pathOnStop = currentRecordingPath // Capture path before clearing
            mediaRecorder?.stop() // Stop the recorder
            mediaRecorder?.release() // Release resources
            mediaRecorder = null // Clear instance
            currentRecordingPath = null // Clear internal path as recording is done

            // Check if the file actually exists and has content AFTER stopping
            val file = pathOnStop?.let { File(it) }
            if (file != null && file.exists() && file.length() > 0) {
                Log.d("AudioHelper", "Recording stopped successfully. File: ${file.absolutePath}, Size: ${file.length()}")
                pathOnStop // Return the path if valid
            } else {
                Log.e("AudioHelper", "Recording stopped but file is invalid/empty or missing: $pathOnStop")
                file?.takeIf { it.exists() }?.delete() // Delete empty/corrupted file if it exists
                null // Return null to indicate failure
            }
        } catch (e: Exception) {
            Log.e("AudioHelper", "Error stopping recording: ${e.message}", e)
            // If stop fails, the file might be incomplete. Attempt to delete it.
            currentRecordingPath?.let { path ->
                File(path).takeIf { it.exists() }?.delete()
                Log.w("AudioHelper", "Attempted to delete incomplete recording: $path")
            }
            null
        } finally {
            // Ensure resources are always released, even if catch fails
            mediaRecorder?.release()
            mediaRecorder = null
            currentRecordingPath = null
        }
    }

    fun playAudio(audioPath: String) {
        // Use the new helper function to validate the file before attempting playback
        if (!isAudioFileValid(audioPath)) {
            Log.e("AudioHelper", "Cannot play invalid audio file: $audioPath")
            // Optionally, throw an exception or return a specific error here if needed by caller
            return
        }

        try {
            stopAudio() // Stop any previous playback
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioPath)
                prepare()
                start()
                setOnCompletionListener {
                    Log.d("AudioHelper", "Audio playback completed.")
                    it.release() // Release player when done
                    mediaPlayer = null // Clear reference
                }
            }
            Log.d("AudioHelper", "Playback started for: $audioPath")
        } catch (e: Exception) {
            Log.e("AudioHelper", "Playback failed for $audioPath: ${e.message}", e)
            stopAudio() // Ensure resources are released on error
        }
    }

    fun stopAudio() {
        mediaPlayer?.release()
        mediaPlayer = null
        Log.d("AudioHelper", "Audio playback stopped/released.")
    }

    fun cleanup() {
        stopRecording() // Will stop recording if active and clean up
        stopAudio() // Will stop playback if active and clean up
        Log.d("AudioHelper", "AudioHelper cleanup completed.")
    }

    fun playSuccessSound() {
        MediaPlayer.create(context, R.raw.correct_sound)?.apply {
            setOnCompletionListener { it.release() }
            start()
            Log.d("AudioHelper", "Playing success sound.")
        } ?: Log.e("AudioHelper", "playSuccessSound(): Failed to create MediaPlayer for success sound.")
    }

    fun playFailSound() {
        MediaPlayer.create(context, R.raw.fail_sound)?.apply {
            setOnCompletionListener { it.release() }
            start()
            Log.d("AudioHelper", "Playing fail sound.")
        } ?: Log.e("AudioHelper", "playFailSound(): Failed to create MediaPlayer for fail sound.")
    }

    // New helper method to check file validity
    fun isAudioFileValid(path: String?): Boolean {
        if (path == null) {
            Log.w("AudioHelper", "isAudioFileValid: Path is null.")
            return false
        }
        val file = File(path)
        val exists = file.exists()
        val hasContent = file.length() > 0

        if (!exists) {
            Log.w("AudioHelper", "isAudioFileValid: File does not exist at $path")
        } else if (!hasContent) {
            Log.w("AudioHelper", "isAudioFileValid: File exists but is empty at $path")
        }
        return exists && hasContent
    }
}