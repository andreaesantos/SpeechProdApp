package dev.andrea.perroquet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.common.PlaybackException
// import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.andrea.perroquet.logging.EventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import androidx.media3.datasource.RawResourceDataSource

/**
 * Base activity for experiment execution with state management.
 */
abstract class BaseExperimentActivity : AppCompatActivity() {

    private val _experimentState = MutableStateFlow(ExperimentState.IDLE)
    val experimentState: StateFlow<ExperimentState> = _experimentState.asStateFlow()
    protected var currentTrial = 0           // 1-based after starting
    protected var totalTrials = 0

    protected open val isClinicalMode: Boolean = false
    var experimentStartTime = 0L
    private var stateStartTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    protected var player: ExoPlayer? = null
    protected var currentVideoName: String? = null
    private var videoStartTime = 0L
    private var videoDuration = 0L
    protected var imageWindowActive = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var delayedVideoStart: Runnable? = null
    private val VIDEO_LEAD_IN_MS = 1000L
    protected var errorCount = 0
    protected val maxErrorsBeforeRecovery = 3
    protected var lastError: String? = null
    protected var recoveryAttempted = false
    protected var batteryLevel = 100
    protected var isBatteryLow = false
    private val batteryReceiver = object : BroadcastReceiver() {
         override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryLevel = (level * 100 / scale.toFloat()).toInt()
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL

            // Consider battery low if below 10% and not charging
            val newLowBatteryState = batteryLevel < 10 && !isCharging

            // Only log if state changed
            if (newLowBatteryState != isBatteryLow) {
                isBatteryLow = newLowBatteryState
                if (isBatteryLow) {
                    Log.w(TAG, "Battery level low: $batteryLevel%")
                    try {
                        val logger = dev.andrea.perroquet.logging.EventLogger.getInstance()
                        logger.logEvent(
                            EventType.BATTERY_WARNING,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to log battery warning: ${e.message}")
                    }
                }
            }
        }
    }

    companion object {
        internal const val TAG = "BaseExperimentActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on during experiment
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Acquire wake lock to prevent CPU from sleeping
        acquireWakeLock()

        // Initialize ExoPlayer
        initializePlayer()

        // Register battery receiver
        registerBatteryReceiver()

        // Observe state changes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                experimentState.collect { state ->
                    onStateChanged(state)
                    logStateTransition(state)
                }
            }
        }
    }

    /**
     * Log state transition to EventLogger
     */
    private fun logStateTransition(state: ExperimentState) {
        try {
            val logger = dev.andrea.perroquet.logging.EventLogger.getInstance()
//            logger.logStateChange(state.name)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log state transition: ${e.message}")
        }
    }

    /**
     * Register battery receiver to monitor battery level
     */
    private fun registerBatteryReceiver() {
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            registerReceiver(batteryReceiver, filter)

            // Check battery level at start
            val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryStatus != null) {
                val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                batteryLevel = (level * 100 / scale.toFloat()).toInt()
                val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL

                isBatteryLow = batteryLevel < 10 && !isCharging

                if (isBatteryLow) {
                    Log.w(TAG, "Starting with low battery level: $batteryLevel%")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register battery receiver: ${e.message}")
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null) {
            initializePlayer()
        }
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering battery receiver: ${e.message}")
        }

        releaseWakeLock()
        releasePlayer()
        super.onDestroy()
    }

    /**
     * Initialize the ExoPlayer instance
     */
    private fun initializePlayer() {
        player = ExoPlayer.Builder(this)
            .build()
            .also { exo ->
                exo.videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                exo.addListener(object : Player.Listener {

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.d(TAG, "Player state=$playbackState") // debug
                        if (playbackState == Player.STATE_ENDED) onVideoPlaybackEnded()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer error: ${error.errorCodeName}", error)
                        onVideoError("ExoPlayer error: ${error.errorCodeName}")
                    }
                })
            }
    }

    /**
     * Release the ExoPlayer instance
     */
    private fun releasePlayer() {
        player?.release()
        player = null
    }

    /**
     * Initialize experiment with configuration parameters
     */
    protected fun initializeExperiment(totalTrials: Int) {
        this.totalTrials = totalTrials
        this.currentTrial = 0
        this.experimentStartTime = SystemClock.elapsedRealtime()

        transitionToState(ExperimentState.IDLE)
    }

    /**
     * Transition to a new state
     */
    protected fun transitionToState(newState: ExperimentState) {
        val oldState = _experimentState.value
        stateStartTime = SystemClock.elapsedRealtime()

        // Reset error recovery flag when transitioning to a new state
        if (oldState != newState) {
            recoveryAttempted = false
        }

        // Check for battery level before critical states
        if (isBatteryLow && (newState == ExperimentState.SPEECH_PRODUCTION ||
                            newState == ExperimentState.TRIAL_VIDEO)) {
            // Log warning but continue
            Log.w(TAG, "Transitioning to $newState with low battery ($batteryLevel%)")
        }

        _experimentState.value = newState
    }

    /**
     * Handle error during experiment
     * @return true if error was handled, false if experiment should abort
     */
    protected fun handleError(errorMessage: String, errorSource: String): Boolean {
        lastError = errorMessage
        errorCount++

        try {
            // Log the error
            val logger = dev.andrea.perroquet.logging.EventLogger.getInstance()
            logger.logError("$errorSource error: $errorMessage")

            // Save logs immediately in case of crash
            logger.saveEvents(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log error: ${e.message}")
        }

        // If too many errors, suggest recovery
        if (errorCount >= maxErrorsBeforeRecovery && !recoveryAttempted) {
            recoveryAttempted = true
            return false // Suggest stopping experiment
        }

        return true // Continue experiment
    }

    /**
     * Called when the state changes
     */
    protected open fun onStateChanged(state: ExperimentState) {
        if (state == ExperimentState.TRIAL_VIDEO) {
            currentVideoName = getVideoNameForCurrentTrial()

            // Load video immediately (no delay needed for static display)
            playVideo(currentVideoName!!)

        } else {
            player?.pause()
        }
    }
    /**
     * Play the video for the current trial
     */
    protected fun playCurrentTrialVideo() {
        val videoName = getVideoNameForCurrentTrial()
        playVideo(videoName)
    }

    /**
     * Get the video name for the current trial
     */
    protected open fun getVideoNameForCurrentTrial(): String {
        val experimentActivity = this as? ExperimentActivity
        if (experimentActivity != null && experimentActivity.videoQueue.isNotEmpty()) {

            val absoluteIndex = experimentActivity.resumeStartIndex + (currentTrial - 1)

            return if (absoluteIndex in experimentActivity.videoQueue.indices) {
                experimentActivity.videoQueue[absoluteIndex]
            } else {
                Log.e(TAG, "Video index out of bounds: absoluteIndex=$absoluteIndex size=${experimentActivity.videoQueue.size}")
                experimentActivity.videoQueue.first()
            }
        }

        return "WR1.mp4"
    }


    /**
     * Play a video by name
     */
    protected open fun playVideo(videoName: String) {
        try {
            currentVideoName = videoName

            // 1. Clean the video name (remove extension if the CSV/list includes .mp4)
            val resourceName = videoName.substringBeforeLast(".")
                .lowercase()
                .trim()

            // 2. Get the Resource ID from the name (dynamic lookup)
            val resId = resources.getIdentifier(resourceName, "raw", packageName)

            if (resId == 0) {
                onVideoError("Video resource not found: res/raw/$resourceName")
                return
            }

            // 3. Log start events (keeping your existing logic)
            lifecycleScope.launch(Dispatchers.IO) {
                val logger = dev.andrea.perroquet.logging.EventLogger.getInstance()
                logger.logVideoEvent(EventType.VIDEO_START, null, currentTrial, videoName)
                (this@BaseExperimentActivity as? ExperimentActivity)?.serialPortHelper?.sendEventTrigger(EventType.VIDEO_START)
            }

            videoStartTime = SystemClock.elapsedRealtime()
            val p = player ?: run { onVideoError("Player not initialized"); return }

            // 4. Build the MediaItem using the RawResource URI
            val uri = RawResourceDataSource.buildRawResourceUri(resId)
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMediaId(resId.toString())
                .build()

            p.setMediaItem(mediaItem)
            p.prepare()
            p.playWhenReady = false
            p.seekTo(0)

            Log.d(TAG, "Started playing raw video: $resourceName (ID: $resId)")

        } catch (e: Exception) {
            Log.e(TAG, "Error playing video: ${e.message}", e)
            onVideoError("Error playing video: ${e.message}")
        }
    }

/**
     * Called when video playback ends
     */
    /**
     * Called when video playback ends
     */
    private fun onVideoPlaybackEnded() {
        if (experimentState.value == ExperimentState.TRIAL_VIDEO) {
            videoDuration = SystemClock.elapsedRealtime() - videoStartTime
            Log.d(TAG, "Video ended: $currentVideoName, duration: $videoDuration ms")

            // Log video end and send trigger (non-blocking)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val logger = dev.andrea.perroquet.logging.EventLogger.getInstance()
                    val eventType = dev.andrea.perroquet.logging.EventType.VIDEO_END

                    // Log the event
                    logger.logVideoEvent(
                        eventType,
                        null,
                        currentTrial,
                        currentVideoName ?: "unknown"
                    )

                    // Send trigger if helper is available
                    try {
                        val activity = this@BaseExperimentActivity as? ExperimentActivity
                        activity?.serialPortHelper?.sendEventTrigger(eventType)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending video end trigger: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error logging video end: ${e.message}")
                }
            }

            if (isClinicalMode) {
                Log.d(TAG, "Clinical mode: video ended, waiting for Next Button")
                return
            }

            // ADDED: Only transition if not in image display mode
            if (!imageWindowActive) {
                transitionToState(ExperimentState.SPEECH_PRODUCTION)
            }
        }
    }

    /**
     * Called when there's an error playing the video
     */
    protected open fun onVideoError(errorMessage: String) {
        Log.e(TAG, errorMessage)

        if (handleError(errorMessage, "Video playback")) {
            // Default implementation: move to next state
            if (experimentState.value == ExperimentState.TRIAL_VIDEO) {
                transitionToState(ExperimentState.SPEECH_PRODUCTION)
            }
        } else {
            // Critical error - show dialog in UI thread
            lifecycleScope.launch(Dispatchers.Main) {
                showErrorDialog(
                    "Critical Video Error",
                    "Multiple video errors occurred. Last error: $errorMessage\n\nDo you want to continue the experiment?"
                )
            }
        }
    }

    /**
     * Show error dialog with recovery options
     */
    protected open fun showErrorDialog(title: String, message: String) {
        // To be implemented by subclasses
        Log.e(TAG, "Error dialog: $title - $message")
    }

    /**
     * Start the next trial
     */
    protected fun startNextTrial() {
        currentTrial++

        if (currentTrial <= totalTrials) {
            transitionToState(ExperimentState.TRIAL_VIDEO)
        } else {
            transitionToState(ExperimentState.EXPERIMENT_END)
        }
    }

    /**
     * Get elapsed time since experiment started
     */
    protected fun getElapsedExperimentTime(): Long {
        return SystemClock.elapsedRealtime() - experimentStartTime
    }

    /**
     * Get elapsed time since current state started
     */
    protected fun getElapsedStateTime(): Long {
        return SystemClock.elapsedRealtime() - stateStartTime
    }

    /**
     * Acquire wake lock to prevent CPU from sleeping
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MomentsInTime:ExperimentWakeLock"
        )
        wakeLock?.acquire(10*60*1000L /*10 minutes*/)
    }

    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }
}