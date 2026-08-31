package dev.andrea.perroquet.logging

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileWriter
import java.util.concurrent.CopyOnWriteArrayList
import dev.andrea.perroquet.util.RunStore


/**
 * Event data class for logging experiment events
 */
data class ExperimentEvent(
    val absoluteTime: Long = System.currentTimeMillis(),
    val relativeTime: Long,
    val type: EventType,
    val triggerCode: Int? = null,
    val blockNumber: Int? = null,
    val trialNumber: Int? = null,
    val videoName: String? = null,
    val audioFileName: String? = null,
    val state: String? = null,
    val details: Map<String, Any>? = null
)

/**
 * Enum defining the types of events that can be logged
 */
enum class EventType {
    EXPERIMENT_START,
    EXPERIMENT_END,
    EXPERIMENT_ENDED,
    PROTOCOL_FINISHED,
    BLOCK_START,
    BLOCK_END,
    TRIAL_START,
    TRIAL_END,
    STIMULUS_ONSET,
    STIMULUS_OFFSET,
    FIXATION_START,
    FIXATION_END,
    RECORDING_START,
    RECORDING_END,
    STATE_CHANGE,
    SYSTEM_RECOVERY,
    EXPERIMENT_ABORTED,
    BATTERY_WARNING,
    ERROR,
    IMAGE_TIMEOUT
}

/**
 * Singleton for logging experiment events to JSON files
 */
class EventLogger private constructor(
    private val context: Context,
    private val experimentStartTime: Long,
    private val logDir: File
) {
    private val events = CopyOnWriteArrayList<ExperimentEvent>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private var participantId: Int = -1

    private var runId: String = ""
    private var sessionDate: String = ""


    companion object {
        private const val TAG = "EventLogger"
        private var instance: EventLogger? = null

        fun initialize(context: Context, experimentStartTime: Long, logDir: File): EventLogger {
            return instance ?: synchronized(this) {
                instance ?: EventLogger(context.applicationContext, experimentStartTime, logDir)
                    .also { instance = it }
            }
        }

        fun getInstance(): EventLogger {
            return instance ?: throw IllegalStateException("EventLogger not initialized")
        }
    }

    private fun getTriggerCodeForType(type: EventType): Int? = when (type) {
        EventType.EXPERIMENT_START  -> 101
        EventType.EXPERIMENT_END,
        EventType.EXPERIMENT_ENDED  -> 200
        EventType.BLOCK_START       -> 10
        EventType.BLOCK_END         -> 15
        EventType.TRIAL_START       -> 20
        EventType.TRIAL_END         -> 25
        EventType.STIMULUS_ONSET    -> 30
        EventType.STIMULUS_OFFSET   -> 35
        EventType.FIXATION_START    -> 40
        EventType.FIXATION_END      -> 45
        EventType.RECORDING_START   -> 50
        EventType.RECORDING_END     -> 55
        else                        -> null
    }

    /**
     * Set experiment metadata
     */
    fun setExperimentInfo(participantId: Int, date: String, runId: String) {
        this.participantId = participantId
        this.sessionDate = date
        this.runId = runId
        Log.d(TAG, "Experiment start time set: $experimentStartTime")
    }

    /**
     * Log an experiment event
     */
//    fun logEvent(event: ExperimentEvent) {
//        scope.launch {
//            events.add(event)
//            Log.d(TAG, "Logged event: ${event.type}")
//
//            // Save after certain important events
//            if (event.type in listOf(
//                    EventType.EXPERIMENT_START,
//                    EventType.BLOCK_START,
//                    EventType.FIXATION_START,
//                    EventType.TRIAL_START,
//                    EventType.FIXATION_END,
//                    EventType.TRIAL_END,
//                    EventType.BLOCK_END,
//                    EventType.ERROR
//                )
//            ) {
//                saveEvents(is_intermediate = true)
//            }
//        }
//    }

    /**
     * Log a simple event with just a type
     */
    fun logEvent(type: EventType) {
        events.add(
            ExperimentEvent(
                type = type,
                triggerCode = getTriggerCodeForType(type),
                relativeTime = SystemClock.elapsedRealtime() - experimentStartTime
            )
        )
        Log.d(TAG, "Logged event: $type")
        if (type in listOf(
                EventType.ERROR,
                EventType.BATTERY_WARNING,
                EventType.EXPERIMENT_ABORTED,
                EventType.SYSTEM_RECOVERY
            )
        ) {
            saveEvents(is_intermediate = true)
        }
    }

    /**
     * Log a state change event
     */
    fun logStateChange(state: String) {
        events.add(
            ExperimentEvent(
                type = EventType.STATE_CHANGE,
                triggerCode = getTriggerCodeForType(EventType.STATE_CHANGE),
                state = state,
                relativeTime = SystemClock.elapsedRealtime() - experimentStartTime
            )
        )
        Log.d(TAG, "Logged state change: $state")
    }

    /**
     * Log a block event
     */
    fun logBlockEvent(type: EventType, blockNumber: Int?) {
        events.add(
            ExperimentEvent(
                type = type,
                triggerCode = getTriggerCodeForType(type),
                blockNumber = blockNumber,
                relativeTime = SystemClock.elapsedRealtime() - experimentStartTime
            )
        )
        Log.d(TAG, "Logged block event: $type, block: $blockNumber")
    }

    /**
     * Log a trial event
     */
    fun logTrialEvent(type: EventType, blockNumber: Int?, trialNumber: Int) {
        events.add(
            ExperimentEvent(
                type = type,
                triggerCode = getTriggerCodeForType(type),
                blockNumber = blockNumber,
                trialNumber = trialNumber,
                relativeTime = SystemClock.elapsedRealtime() - experimentStartTime
            )
        )
        Log.d(TAG, "Logged trial event: $type, block: $blockNumber, trial: $trialNumber")
    }

    /**
     * Log a video event
     */
    fun logVideoEvent(type: EventType, blockNumber: Int?, trialNumber: Int, videoName: String) {
        events.add(
            ExperimentEvent(
                type = type,
                triggerCode = getTriggerCodeForType(type),
                blockNumber = blockNumber,
                trialNumber = trialNumber,
                videoName = videoName,
                relativeTime = SystemClock.elapsedRealtime() - experimentStartTime
            )
        )
        Log.d(TAG, "Logged video event: $type, block: $blockNumber, trial: $trialNumber, video: $videoName")
    }

    /**
     * Log a recording event
     */
    fun logRecordingEvent(
        type: EventType,
        trialNumber: Int,
        audioFileName: String
    ) {
        events.add(
            ExperimentEvent(
                type = type,
                triggerCode = getTriggerCodeForType(type),
                trialNumber = trialNumber,
                audioFileName = audioFileName,
                relativeTime = SystemClock.elapsedRealtime() - experimentStartTime
            )
        )
        Log.d(TAG, "Logged recording event: $type, trial: $trialNumber, file: $audioFileName")
        if (type in listOf(EventType.RECORDING_END)) {
            saveEvents(is_intermediate = true)
        }
    }

    /**
     * Log an error event
     */
    fun logError(message: String, details: Map<String, Any>? = null) {
        events.add(
            ExperimentEvent(
                type = EventType.ERROR,
                triggerCode = getTriggerCodeForType(EventType.ERROR),
                relativeTime = SystemClock.elapsedRealtime() - experimentStartTime,
                details = details?.plus("message" to message) ?: mapOf("message" to message)
            )
        )
        Log.e(TAG, "Logged error: $message")
        saveEvents(is_intermediate = true)
    }

    /**
     * Save events to a JSON file
     */
    fun saveEvents(is_intermediate: Boolean = false) {
        if (events.isEmpty()) {
            Log.d(TAG, "No events to save")
            return
        }

        scope.launch {
            mutex.withLock {
                try {
                    val logsDir = ensureLogsDirectory()
                    val fileName = if (is_intermediate) {
                        "intermediate_p${participantId}_${sessionDate}_run_${runId}_pn.json"
                    } else {
                        "p${participantId}_${sessionDate}_run_${runId}_pn.json"
                    }
                    val logFile = File(logsDir, fileName)

                    // Create a copy of events to avoid concurrent modification
                    val eventsCopy = ArrayList(events)

                    FileWriter(logFile).use { writer ->
                        val json = gson.toJson(eventsCopy)
                        writer.write(json)
                        writer.flush()
                    }

                    Log.d(TAG, "Saved ${eventsCopy.size} events to ${logFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving events: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Ensure the logs directory exists
     */
    private fun ensureLogsDirectory(): File {
        return RunStore.getOrCreateRunDir(
            context = context,
            participantId = participantId,
            runNumber = runId
        )
    }

    fun getAudioDirectory(): File {
        return RunStore.getOrCreateRunDir(
            context = context,
            participantId = participantId,
            runNumber = runId
        )
    }

    /**
     * Clear all events (typically after saving)
     */
    fun clearEvents() {
        events.clear()
        Log.d(TAG, "Cleared all events")
    }
}
