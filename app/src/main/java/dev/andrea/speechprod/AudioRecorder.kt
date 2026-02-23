package dev.andrea.speechprod

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import dev.andrea.speechprod.util.RunStore

/**
 * Handles audio recording functionality for the experiment.
 */
class AudioRecorder(context: Context) {
    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BITS_PER_SAMPLE = 16
        private const val BUFFER_SIZE_FACTOR = 2
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private var outputFile: File? = null
    private var bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR

    private val WAV_HEADER_BYTES = 44L
    @Volatile private var totalAudioBytesWritten: Long = 0L

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Start recording audio for a fixed duration
     * @param participantId The participant ID
     * @param blockNumber The current block number
     * @param trialNumber The current trial number
     * @param durationMs The recording duration in milliseconds
     * @param onComplete Callback when recording is complete
     * @param onError Callback when an error occurs
     */
    private var onCompleteCallback: ((File) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    /**
     * Starts a session-level recording with an explicit filename instead of
     * deriving it from block/trial numbers. Everything else is identical to
     * startRecording().
     */
    fun startSessionRecording(
        participantId: Int,
        runId: String,
        date: String,
        fileName: String,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onCompleteCallback = onComplete
        this.onErrorCallback    = onError

        if (isRecording) { onError("Recording already in progress"); return }

        try {
            val runDir    = RunStore.getOrCreateRunDir(context, participantId, date, runId)
            val outputDir = File(runDir, "audio").apply { mkdirs() }
            outputFile    = File(outputDir, fileName)
            Log.d(TAG, "Session recording output: ${outputFile?.absolutePath}")

            val permCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            if (permCheck != PackageManager.PERMISSION_GRANTED) {
                onError("Recording permission not granted"); releaseResources(); return
            }

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBuf <= 0) { onError("Invalid buffer size: $minBuf"); releaseResources(); return }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("AudioRecord init failed"); releaseResources(); return
            }

            isRecording            = true
            totalAudioBytesWritten = 0L
            recordingThread = Thread { writeAudioDataToFile(onError) }
            recordingThread?.start()
            audioRecord?.startRecording()
            Log.d(TAG, "Session recording started → ${outputFile?.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting session recording: ${e.message}", e)
            onError("Error starting session recording: ${e.message}")
            releaseResources()
        }
    }
    /**
     * Stop the current recording
     */
    fun stopRecording() {
        if (!isRecording) return

        isRecording = false

        // 1) Stop AudioRecord first to unblock read()
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }

        // 2) Wait for writer thread to finish & close the stream
        try {
            recordingThread?.join(1500) // small timeout is fine
        } catch (e: Exception) {
            Log.e(TAG, "Error joining recording thread", e)
        }

        // 3) Finalize file + callbacks
        try {
            val file = outputFile
            if (file != null && file.exists() && file.length() > 44) { // >44 = more than WAV header
                updateWavHeader(file)
                onCompleteCallback?.invoke(file)
            } else {
                onErrorCallback?.invoke("Recording file is empty or too short (size=${file?.length() ?: -1})")            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing recording", e)
            onErrorCallback?.invoke("Error finalizing recording: ${e.message}")
        } finally {
            releaseResources()
            Log.d(TAG, "Stopped recording")
        }
    }

    /**
     * Write audio data to WAV file
     */
    private fun writeAudioDataToFile(onError: (String) -> Unit) {
        val data = ByteArray(bufferSize)
        var outputStream: FileOutputStream? = null

        try {
            outputStream = FileOutputStream(outputFile)

            // Write WAV header
            writeWavHeader(outputStream)

            // Write audio data
            while (isRecording) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: AudioRecord.ERROR_INVALID_OPERATION

                when {
                    read > 0 -> {
                        outputStream.write(data, 0, read)
                        totalAudioBytesWritten += read.toLong()
                    }
                    read == 0 -> {
                        // no data yet; ok to continue
                    }
                    else -> {
                        Log.e(TAG, "AudioRecord.read() error=$read")
                        onError("AudioRecord.read() error=$read")
                        break
                    }
                }
            }

            // Update WAV header with final file size
            // updateWavHeader(outputFile)

        } catch (e: IOException) {
            Log.e(TAG, "Error writing audio data: ${e.message}", e)
            onError("Error writing audio data: ${e.message}")
        } finally {
            try {
                outputStream?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing output stream: ${e.message}", e)
            }
        }
    }

    /**
     * Write WAV header to the beginning of the file
     */
    private fun writeWavHeader(outputStream: FileOutputStream) {
        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            put("RIFF".toByteArray())  // ChunkID
            putInt(0)  // ChunkSize (placeholder, will update later)
            put("WAVE".toByteArray())  // Format

            // fmt subchunk
            put("fmt ".toByteArray())  // Subchunk1ID
            putInt(16)  // Subchunk1Size (16 for PCM)
            putShort(1)  // AudioFormat (1 for PCM)
            putShort(1)  // NumChannels (1 for mono)
            putInt(SAMPLE_RATE)  // SampleRate
            putInt(SAMPLE_RATE * BITS_PER_SAMPLE / 8)  // ByteRate
            putShort((BITS_PER_SAMPLE / 8).toShort())  // BlockAlign
            putShort(BITS_PER_SAMPLE.toShort())  // BitsPerSample

            // data subchunk
            put("data".toByteArray())  // Subchunk2ID
            putInt(0)  // Subchunk2Size (placeholder, will update later)
        }.array()

        outputStream.write(header)
    }

    /**
     * Update the WAV header with the final file size
     */
    private fun updateWavHeader(file: File?) {
        if (file == null || !file.exists()) {
            return
        }

        try {
            val fileSize = file.length()
            val headerBuffer = ByteBuffer.allocate(8).apply {
                order(ByteOrder.LITTLE_ENDIAN)

                // Update ChunkSize (file size - 8)
                putInt((fileSize - 8).toInt())

                // Update Subchunk2Size (file size - 44)
                putInt((fileSize - 44).toInt())
            }

            val randomAccessFile = java.io.RandomAccessFile(file, "rw")
            // Update ChunkSize at position 4
            randomAccessFile.seek(4)
            randomAccessFile.write(headerBuffer.array(), 0, 4)

            // Update Subchunk2Size at position 40
            randomAccessFile.seek(40)
            randomAccessFile.write(headerBuffer.array(), 4, 4)

            randomAccessFile.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating WAV header: ${e.message}", e)
        }
    }

    /**
     * Release resources
     */
    private fun releaseResources() {
        try {
            audioRecord?.release()
            audioRecord = null
            recordingThread = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources: ${e.message}", e)
        }
    }
}