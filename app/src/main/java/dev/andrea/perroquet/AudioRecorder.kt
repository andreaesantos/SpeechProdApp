package dev.andrea.perroquet

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
import dev.andrea.perroquet.util.RunStore

/**
 * Handles audio recording functionality for the experiment.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"

        // Audio configuration
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
    private val SAFETY_TIMEOUT = 120_000L // 2 minutes
//    private var safetyTimer: Timer? = null

    fun startRecording(
        participantId: Int,
        runId: String,          // <-- add this
        date: String,           // <-- add this (recommended)
        blockNumber: Int? = null,
        trialNumber: Int,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onCompleteCallback = onComplete
        this.onErrorCallback = onError
//        safetyTimer = Timer().apply {
//            schedule(object : TimerTask() {
//                override fun run() {
//                    if (isRecording) {
//                        Log.w(TAG, "Safety timeout triggered")
//                        stopRecording()
//                    }
//                }
//            }, SAFETY_TIMEOUT)
//        }
        if (isRecording) {
            onError("Recording already in progress")
            return
        }

        try {
            Log.d(TAG, "Starting audio recording for participant $participantId, block $blockNumber, trial $trialNumber")

            // Try to get audio directory from EventLogger, fall back to default if not available
            // Always record into a unique run folder (no overwrites across runs)
            val runDir = RunStore.getOrCreateRunDir(
                context = context,
                participantId = participantId,
                date = date,
                runId = runId
            )

            // Put audio in a subfolder if you want
            val outputDir = File(runDir, "audio").apply { mkdirs() }


            // Create output file with naming convention
            val fileName = if (blockNumber == null) {
                "trial_${trialNumber}.wav"
            } else {
                "block_${blockNumber}_trial_${trialNumber}.wav"
            }
            outputFile = File(outputDir, fileName)
            Log.d(TAG, "Output file path: ${outputFile?.absolutePath}")
            outputFile = File(outputDir, fileName)
            Log.d(TAG, "Output file path: ${outputFile?.absolutePath}")

            // Check for recording permission
            val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            Log.d(TAG, "RECORD_AUDIO permission check result: $permissionCheck")

            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                val errorMsg = "Recording permission not granted (result: $permissionCheck)"
                Log.e(TAG, errorMsg)
                onError(errorMsg)
                releaseResources()
                return
            }

            try {
                // Initialize AudioRecord with explicit buffer size
                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
                )

                Log.d(TAG, "Minimum buffer size: $minBufferSize, using: $bufferSize")

                if (minBufferSize <= 0) {
                    val errorMsg = "Invalid minimum buffer size: $minBufferSize"
                    Log.e(TAG, errorMsg)
                    onError(errorMsg)
                    releaseResources()
                    return
                }

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                // Check if AudioRecord was initialized properly
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    val errorMsg = "Failed to initialize AudioRecord (state: ${audioRecord?.state})"
                    Log.e(TAG, errorMsg)
                    onError(errorMsg)
                    releaseResources()
                    return
                }

                Log.d(TAG, "AudioRecord successfully initialized")
            } catch (e: SecurityException) {
                val errorMsg = "Security exception: Recording permission denied: ${e.message}"
                Log.e(TAG, errorMsg, e)
                onError(errorMsg)
                releaseResources()
                return
            } catch (e: IllegalArgumentException) {
                val errorMsg = "Invalid AudioRecord parameters: ${e.message}"
                Log.e(TAG, errorMsg, e)
                onError(errorMsg)
                releaseResources()
                return
            } catch (e: Exception) {
                val errorMsg = "Error initializing AudioRecord: ${e.message}"
                Log.e(TAG, errorMsg, e)
                onError(errorMsg)
                releaseResources()
                return
            }

            isRecording = true
            totalAudioBytesWritten = 0L

            // Start recording in a separate thread
            recordingThread = Thread {
                writeAudioDataToFile(onError)
            }
            recordingThread?.start()

//            // Log recording start event
//            try {
//                val fileName = outputFile?.name ?: "unknown_file.wav"
//                dev.andrea.perroquet.logging.EventLogger.getInstance().logRecordingEvent(
//                    dev.andrea.perroquet.logging.EventType.RECORDING_START,
//                    blockNumber,
//                    trialNumber,
//                    fileName
//                )
//            } catch (e: Exception) {
//                Log.e(TAG, "Error logging recording start: ${e.message}", e)
//            }
            // Start the actual recording
            audioRecord?.startRecording()

            // Recording will be manually stopped by user

            Log.d(TAG, "Started recording to ${outputFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ${e.message}", e)
            onError("Error starting recording: ${e.message}")
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
