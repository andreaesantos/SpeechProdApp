package dev.andrea.speechprod

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Singleton managing the single long-running session recording.
 *
 * start() ← ParticipantInputActivity, just before launching ExperimentActivity
 * stop()  ← ExperimentActivity on EXPERIMENT_END, exit dialog, or onDestroy (safety net)
 *
 * Output: <RunStore>/audio/session_continuous_P<id>.wav
 */
object ContinuousRecorder {

    private const val TAG = "ContinuousRecorder"

    private var recorder: AudioRecorder? = null

    var isRunning: Boolean = false
        private set

    fun start(
        context: Context,
        participantId: Int,
        date: String,
        runId: String,
        onStarted: (() -> Unit)? = null,
        onFileSaved: ((File) -> Unit)? = null
    ) {
        if (isRunning) { Log.d(TAG, "Already running — ignoring duplicate start()"); return }

        recorder  = AudioRecorder(context.applicationContext)
        isRunning = true

        val fileName = "session_P${participantId}_${date}_run_${runId}.wav"
        Log.d(TAG, "Starting → $fileName  participant=$participantId  runId=$runId")

        recorder!!.startSessionRecording(
            participantId = participantId,
            runId         = runId,
            date          = date,
            fileName      = fileName,
            onComplete    = { file ->
                isRunning = false
                recorder  = null
                Log.i(TAG, "Saved: ${file.absolutePath}  (${file.length()} bytes)")
                onFileSaved?.invoke(file)
            },
            onError = { error ->
                isRunning = false
                recorder  = null
                Log.e(TAG, "Error: $error")
            }
        )
        onStarted?.invoke()
    }

    fun stop() {
        if (!isRunning) { Log.d(TAG, "stop() — not running, nothing to do"); return }
        Log.d(TAG, "Stopping continuous session recording")
        recorder?.stopRecording()
        // isRunning + recorder reset via callbacks
    }
}