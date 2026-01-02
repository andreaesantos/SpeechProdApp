package dev.andrea.perroquet

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import android.util.Log
import android.widget.ImageView
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import dev.andrea.perroquet.logging.EventLogger
import dev.andrea.perroquet.logging.EventType
import dev.andrea.perroquet.usbserial.SerialPortHelper
import dev.andrea.perroquet.util.SessionVideoLoader
import dev.andrea.perroquet.util.VideoProgressStore
import dev.andrea.perroquet.util.RunStore


class ExperimentActivity : BaseExperimentActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var blockTextView: TextView
    private lateinit var trialTextView: TextView
    private lateinit var timeTextView: TextView
    private lateinit var startButton: Button
    private lateinit var nextButton: Button
    private lateinit var reloadButton: Button
    private lateinit var passButton: Button
    private lateinit var failButton: Button
    private lateinit var decisionStore: dev.andrea.perroquet.util.DecisionStore

    private lateinit var exitButton: Button
    private lateinit var playerView: PlayerView
    private lateinit var experimentContentTextView: TextView
    private lateinit var microphoneImageView: ImageView
    private lateinit var recordingContainer: View
    private lateinit var recordingCountdownView: CircularCountdownView
    private lateinit var fixationCrossLayout: View
    private lateinit var fixationCrossTextView: TextView
    private lateinit var circularCountdownView: CircularCountdownView
    private lateinit var connectionStatusTextView: TextView
    private lateinit var batteryStatusTextView: TextView

    private var participantId: Int = -1
    private var dateString: String = ""

    private var runId: String = ""

    var config: ExperimentConfig.Standard? = null
    var videoQueue: List<String> = emptyList()
    private val videoLoader by lazy { SessionVideoLoader(this) }
    private val progressStore by lazy { VideoProgressStore(this) }

    // offset into the full ordered list (for resume)
    var resumeStartIndex: Int = 0
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateTimeDisplay()
            handler.postDelayed(this, 100) // Update every 100ms
        }
    }

    // Audio recording
    private lateinit var audioRecorder: AudioRecorder
    private var currentRecordingFile: File? = null
    private var permissionsGranted = false

    // Event logger and serial port helper
    private lateinit var eventLogger: EventLogger
    lateinit var serialPortHelper: SerialPortHelper

    // Permission request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        permissionsGranted = allGranted

        if (allGranted) {
            Toast.makeText(this, "Audio recording permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Audio recording permission denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_experiment)
        reloadButton = findViewById(R.id.reloadButton)
        reloadButton.visibility = View.GONE
        passButton = findViewById(R.id.passButton)
        failButton = findViewById(R.id.failButton)

        decisionStore = dev.andrea.perroquet.util.DecisionStore(this) // uses datasetKey default
        passButton.setOnClickListener { recordDecision("PASS") }
        failButton.setOnClickListener { recordDecision("FAIL") }


        exitButton = findViewById(R.id.exitButton)

        exitButton.setOnClickListener {
            showExitConfirmDialog()
        }

        // Hide the status bar and make the app full screen
        hideSystemUI()

        // Get intent data
        participantId = intent.getIntExtra(ParticipantInputActivity.EXTRA_PARTICIPANT_ID, -1)
        dateString = intent.getStringExtra(ParticipantInputActivity.EXTRA_DATE) ?: LocalDate.now().toString()
        runId = intent.getStringExtra(ParticipantInputActivity.EXTRA_RUN_ID) ?: java.util.UUID.randomUUID().toString()


        val runDir = RunStore.getOrCreateRunDir(this, participantId, dateString, runId)
        val logDir = File(runDir, "logs").apply { mkdirs() }

        val mode = intent.getStringExtra(ParticipantInputActivity.EXTRA_MODE) ?: ParticipantInputActivity.MODE_FULL
        val allVideos = videoLoader.loadVideosInOrder()

        if (mode == ParticipantInputActivity.MODE_PASSED_ONLY && videoQueue.isEmpty()) {
            Toast.makeText(this, "No passed videos yet", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Create experiment config
        config = ExperimentConfig.Standard(
            participantId = participantId,
            date = LocalDate.parse(dateString),
            runId = runId
        )

        videoQueue = if (mode == ParticipantInputActivity.MODE_PASSED_ONLY) {
            val passed = decisionStore.getPassedVideos(participantId)
            allVideos.filter { it in passed }
        } else {
            allVideos
        }

        val blocks = config?.blocks ?: 3
        val trials = config?.trialsPerBlock ?: 5
        val needed = blocks * trials

        if (videoQueue.size < needed) {
            Log.e(TAG, "Not enough videos. Got ${videoQueue.size}, need $needed (blocks=$blocks trialsPerBlock=$trials)")
            Toast.makeText(this, "Not enough videos: ${videoQueue.size} (need $needed)", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Read progress and compute where to resume (next trial)
        val lastCompleted = progressStore.getLastCompletedIndex(participantId) // -1 if none
        resumeStartIndex = (lastCompleted + 1).coerceAtLeast(0)

        if (resumeStartIndex >= videoQueue.size) {
            Log.d(TAG, "Participant already completed all videos. lastCompleted=$lastCompleted")
            // You can decide what to do: finish, show message, etc.
            // For now, start from beginning (or end the experiment)
            resumeStartIndex = 0
        }

        // Debug
        Log.d(TAG, "Resume: lastCompleted=$lastCompleted -> startIndex=$resumeStartIndex")
        Log.d(TAG, "Prepared ordered video queue (${videoQueue.size}): ${videoQueue.joinToString()}")


        // Log prepared video queue
        Log.d(TAG, "Prepared video queue with ${videoQueue.size} videos: ${videoQueue.joinToString()}")

        // Log prepared video queue
        Log.d("ExperimentActivity", "Prepared video queue with ${videoQueue.size} videos: ${videoQueue.joinToString()}")

        // Initialize audio recorder
        audioRecorder = AudioRecorder(this)

        // Check and request permissions
        checkAndRequestPermissions()

        // Initialize views
        statusTextView = findViewById(R.id.statusTextView)
        blockTextView = findViewById(R.id.blockTextView)
        trialTextView = findViewById(R.id.trialTextView)
        timeTextView = findViewById(R.id.timeTextView)
        startButton = findViewById(R.id.startButton)
        nextButton = findViewById(R.id.nextButton)
        reloadButton = findViewById(R.id.reloadButton)

        reloadButton.setOnClickListener {
            reloadCurrentVideo()
        }
        playerView = findViewById(R.id.playerView)
        experimentContentTextView = findViewById(R.id.experimentContentTextView)
        recordingContainer = findViewById(R.id.recordingContainer)
        microphoneImageView = findViewById(R.id.microphoneImageView)

        // Initialize fixation cross views
        fixationCrossLayout = findViewById(R.id.fixationCrossLayout)
        fixationCrossTextView = fixationCrossLayout.findViewById(R.id.fixationCrossTextView)
        circularCountdownView = fixationCrossLayout.findViewById(R.id.circularCountdownView)

        // Status text views
        connectionStatusTextView = findViewById(R.id.connectionStatusTextView)

        // Hide battery warning by default, only show if battery is low at start
        batteryStatusTextView = findViewById(R.id.batteryStatusTextView)
        batteryStatusTextView.visibility = View.GONE

        // Connect player to view and disable controls
        playerView.player = player ?: run {
            Log.e(TAG, "Player is null when binding to PlayerView")
            return
        }
        playerView.useController = false  // Disable the control panel
        playerView.controllerAutoShow = false  // Prevent controls from showing automatically

        // Initialize experiment
        initializeExperiment(config?.blocks ?: 3, config?.trialsPerBlock ?: 5)
        startButton.visibility = View.GONE

        // Initialize event logger
        eventLogger = EventLogger.initialize(this, this.experimentStartTime, logDir)
        eventLogger.setExperimentInfo(participantId, dateString, runId)


        // Initialize serial port helper
        serialPortHelper = SerialPortHelper(this)

        // Make sure connection status is visible
        connectionStatusTextView.visibility = View.VISIBLE

        // Observe connection state
        lifecycleScope.launch {
            serialPortHelper.connectionState.collect { state ->
                updateConnectionStatus(state)
            }
        }

        // Try to connect to a USB device
        lifecycleScope.launch(Dispatchers.IO) {
            val connected = serialPortHelper.connectToFirstAvailable()
            if (!connected) {
                Log.w("ExperimentActivity", "No USB devices found for initial connection")
            }
        }

        nextButton.setOnClickListener {
            handleNextButtonClick()
        }

        // Start time updates
        handler.post(updateTimeRunnable)

        // Observe state changes
        lifecycleScope.launch {
            experimentState.collect { state ->
                updateUI(state)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateTimeRunnable)
        audioRecorder.stopRecording()
        serialPortHelper.cleanup()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        playerView.player = player  // re-bind after Base may recreate it
    }

    private fun showExitConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("Quitter l'expérience ?")
            .setMessage("L'expérience va s'arrêter maintenant. Voulez-vous quitter ?")
            .setPositiveButton("Quitter") { _, _ ->
                exitExperimentNow()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun exitExperimentNow() {
        try {
            // Stop video
            player?.stop()

            // Stop recording if running
            try { audioRecorder.stopRecording() } catch (_: Exception) {}

            // Save progress at the last completed index (optional)
            // If you want to mark “aborted”, you can store current absolute index too

            // Log + save
            try {
                eventLogger.logEvent(EventType.EXPERIMENT_ABORTED)
                eventLogger.saveEvents(true)
            } catch (_: Exception) {}

            // Clean up USB
            try { serialPortHelper.cleanup() } catch (_: Exception) {}

        } finally {
            // Close the app
            finishAffinity()
        }
    }
    /**
     * Hides the system UI (status bar and navigation bar)
     */
    private fun hideSystemUI() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // For API 30 and above
                WindowCompat.setDecorFitsSystemWindows(window, false)

                // Use post to ensure window is fully initialized
                window.decorView.post {
                    window.insetsController?.let {
                        it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            } else {
                // For API 29 and below
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            }

            // Keep screen on during experiment
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Make sure connection status is still visible after hiding system UI
            handler.postDelayed({
                connectionStatusTextView.visibility = View.VISIBLE
                connectionStatusTextView.bringToFront()
            }, 100)
        } catch (e: Exception) {
            Log.e("ExperimentActivity", "Error hiding system UI: ${e.message}", e)
        }
    }

    /**
     * Check and request necessary permissions
     */
    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        // Check each permission individually and log the result
        permissions.forEach { permission ->
            val isGranted = ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
            Log.d("PermissionCheck", "$permission granted: $isGranted")
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("PermissionCheck", "Requesting permissions: ${permissionsToRequest.joinToString()}")
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            Log.d("PermissionCheck", "All permissions already granted")
            permissionsGranted = true
        }
    }

    override fun onStateChanged(state: ExperimentState) {
        // Log state change with additional details
//        eventLogger.logStateChange(state.name,)

        // Check if we need to show battery warning (only at experiment start)
        if (state == ExperimentState.IDLE && isBatteryLow) {
            showBatteryWarning()
        }

        // Send trigger for state change
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val eventType = when (state) {
                    ExperimentState.BLOCK_START -> EventType.BLOCK_START
                    ExperimentState.BLOCK_END -> EventType.BLOCK_END
                    ExperimentState.EXPERIMENT_END -> EventType.EXPERIMENT_END
                    else -> null
                }

                eventType?.let {
                    // Only attempt to send if connected
                    if (serialPortHelper.connectionState.value == SerialPortHelper.ConnectionState.CONNECTED) {
                        val success = serialPortHelper.sendEventTrigger(eventType)
                        if (success) {
                            Log.d("ExperimentActivity", "Sent trigger for state: $state")
                            eventLogger.logEvent(eventType)
                        } else {
                            Log.w("ExperimentActivity", "Failed to send trigger for state: $state")
                            eventLogger.logError("Failed to send trigger for state: $state")
                        }
                    } else {
                        Log.d("ExperimentActivity", "Not sending trigger - USB not connected")
                    }
                }
            } catch (e: Exception) {
                Log.e("ExperimentActivity", "Error sending trigger: ${e.message}", e)
                eventLogger.logError("Error sending trigger: ${e.message}")
            }
        }

        // No battery status updates on state change

        when (state) {
            ExperimentState.BLOCK_START -> {
                // Log block start
                eventLogger.logBlockEvent(EventType.BLOCK_START, currentBlock)

                // Automatically transition to first trial after a short delay
                handler.postDelayed({
                    startNextTrial()
                }, 500)
            }

            ExperimentState.TRIAL_VIDEO -> {
                // Log trial start
//                eventLogger.logTrialEvent(EventType.TRIAL_START, currentBlock, currentTrial)

                // Play video
                playCurrentTrialVideo()
            }

            ExperimentState.FIXATION_DELAY -> {
                // Log fixation start
                eventLogger.logEvent(EventType.FIXATION_START)

                // send trigger code
                lifecycleScope.launch(Dispatchers.IO) {
                    serialPortHelper.sendEventTrigger(EventType.FIXATION_START)
                }

                // Show fixation cross and start countdown
                startFixationCountdown(config?.fixationDurationMs ?: 1000) // 1000ms delay

                // log fixation end
                eventLogger.logEvent(EventType.FIXATION_END)

                // send trigger code
                lifecycleScope.launch(Dispatchers.IO) {
                    serialPortHelper.sendEventTrigger(EventType.FIXATION_END)
                }
            }

            ExperimentState.SPEECH_RECORDING -> {
                // Start audio recording
                startAudioRecording()
            }

            ExperimentState.BLOCK_END -> {
                // Log block end
                eventLogger.logBlockEvent(EventType.BLOCK_END, currentBlock)

                // Show next button to proceed to next block
                nextButton.isEnabled = true
            }

            ExperimentState.EXPERIMENT_END -> {
                eventLogger.logEvent(EventType.EXPERIMENT_END)
                eventLogger.saveEvents()

                progressStore.setLastCompletedIndex(participantId, -1)

                nextButton.isEnabled = false
                nextButton.text = "Done"
            }

            else -> { /* No action needed */ }
        }
    }

    private fun handleNextButtonClick() {
        when (experimentState.value) {
            ExperimentState.IDLE -> {
                // Log experiment start
                eventLogger.logEvent(EventType.EXPERIMENT_START)

                // Send experiment start trigger
                lifecycleScope.launch(Dispatchers.IO) {
                    serialPortHelper.sendEventTrigger(EventType.EXPERIMENT_START)
                }

                // Ensure system UI is hidden when experiment starts
                hideSystemUI()
                startNextBlock()
            }

            ExperimentState.BLOCK_END -> {
                startNextBlock()
            }

            ExperimentState.EXPERIMENT_END -> {
                // Close the app completely when experiment is done
                finishAffinity()
            }

            ExperimentState.SPEECH_RECORDING -> {
                audioRecorder.stopRecording()
            }

            else -> { /* No action needed */ }
        }
    }

    private fun reloadCurrentVideo() {
        val p = playerView.player ?: return

        try {
            // Restart current media item from the beginning
            p.seekTo(0)
            p.play()
            Log.d(TAG, "Reloaded current video (seekTo 0)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reload current video: ${e.message}", e)
            Toast.makeText(this, "Could not reload video", Toast.LENGTH_SHORT).show()
        }
    }


    /**
     * Update the connection status display
     */
    private fun updateConnectionStatus(state: SerialPortHelper.ConnectionState) {
        // Always use runOnUiThread for UI updates
        runOnUiThread {
            Log.d("ExperimentActivity", "Updating connection status to: $state")

            val statusText = when (state) {
                SerialPortHelper.ConnectionState.CONNECTED -> "USB: Connected ✓"
                SerialPortHelper.ConnectionState.CONNECTING -> "USB: Connecting..."
                SerialPortHelper.ConnectionState.DISCONNECTED -> "USB: Disconnected"
                SerialPortHelper.ConnectionState.NO_DEVICES -> "USB: No devices found"
                SerialPortHelper.ConnectionState.PERMISSION_PENDING -> "USB: Permission requested"
                SerialPortHelper.ConnectionState.PERMISSION_DENIED -> "USB: Permission denied"
                SerialPortHelper.ConnectionState.DRIVER_NOT_FOUND -> "USB: No driver found"
                SerialPortHelper.ConnectionState.CONNECTION_FAILED -> "USB: Connection failed"
                SerialPortHelper.ConnectionState.ERROR -> "USB: Error"
            }

            val textColor = when (state) {
                SerialPortHelper.ConnectionState.CONNECTED -> getColor(android.R.color.holo_green_dark)
                SerialPortHelper.ConnectionState.CONNECTING,
                SerialPortHelper.ConnectionState.PERMISSION_PENDING -> getColor(android.R.color.holo_blue_dark)
                else -> getColor(android.R.color.holo_red_dark)
            }

            // only show text if not connected
            val text_visibility = when (state) {
                SerialPortHelper.ConnectionState.CONNECTED -> View.GONE
                else -> View.VISIBLE
            }
            connectionStatusTextView.apply {
                text = statusText
                setTextColor(textColor)
                visibility = text_visibility

                // Ensure it's on top of other views
                bringToFront()

                // Add a brief animation to draw attention
                alpha = 0.7f
                animate().alpha(1.0f).setDuration(300).start()
            }

        }
    }

    private fun updateUI(state: ExperimentState) {
        // Update status text
        statusTextView.text = "Status: ${state.name}"

        // Update block and trial counters
        blockTextView.text = "Block: $currentBlock / $totalBlocks"
        trialTextView.text = "Trial: $currentTrial / $trialsPerBlock"

        exitButton.visibility = View.VISIBLE
        exitButton.bringToFront()

        // Update experiment content visibility
        when (state) {
            ExperimentState.TRIAL_VIDEO -> {
                playerView.visibility = View.VISIBLE
                experimentContentTextView.visibility = View.GONE
                fixationCrossLayout.visibility = View.GONE
                passButton.visibility = View.VISIBLE
                failButton.visibility = View.VISIBLE

                reloadButton.visibility = View.VISIBLE
            }
            ExperimentState.FIXATION_DELAY -> {
                playerView.visibility = View.GONE
                experimentContentTextView.visibility = View.GONE
                fixationCrossLayout.visibility = View.VISIBLE
                passButton.visibility = View.GONE
                failButton.visibility = View.GONE

            }
            ExperimentState.IDLE -> {
                playerView.visibility = View.GONE
                experimentContentTextView.visibility = View.VISIBLE
                fixationCrossLayout.visibility = View.GONE
                experimentContentTextView.text = "Prêt(e) à commencer l’expérience"
                passButton.visibility = View.GONE
                failButton.visibility = View.GONE

            }
            else -> {
                playerView.visibility = View.GONE
                fixationCrossLayout.visibility = View.GONE
                passButton.visibility = View.GONE
                failButton.visibility = View.GONE

                // Handle special case for speech recording
                if (state == ExperimentState.SPEECH_RECORDING) {
                    experimentContentTextView.visibility = View.GONE
                    recordingContainer.visibility = View.VISIBLE
                    startMicAnimation()
                } else {
                    experimentContentTextView.visibility = View.VISIBLE
                    recordingContainer.visibility = View.GONE

                    // Update content text based on state
                    experimentContentTextView.text = when (state) {
                        ExperimentState.BLOCK_START -> "Début du bloc $currentBlock"
                        ExperimentState.BLOCK_END -> "Bloc $currentBlock terminé\n\nAppuyez sur Suivant pour continuer"
                        ExperimentState.EXPERIMENT_END -> "Expérience terminée\n\nMerci de votre participation"
                        else -> "Experiment Content Area"
                    }
                }
            }

        }

        // Update button visibility and state
        when (state) {
            ExperimentState.IDLE -> {
                nextButton.visibility = View.VISIBLE
                nextButton.isEnabled = true
                nextButton.text = "Démarrer"
            }

            ExperimentState.BLOCK_END -> {
                nextButton.visibility = View.VISIBLE
                nextButton.isEnabled = true
                nextButton.text = "Bloc suivant"
            }

            ExperimentState.EXPERIMENT_END -> {
                nextButton.visibility = View.VISIBLE
                nextButton.isEnabled = true
                nextButton.text = "Terminer"
            }

            ExperimentState.SPEECH_RECORDING -> {
                nextButton.visibility = View.VISIBLE
                nextButton.isEnabled = true
                nextButton.text = "Suivant"
            }

            else -> {
                // Hide the button during experiment trials
                nextButton.visibility = View.GONE
            }
        }
    }

    override fun onVideoError(errorMessage: String) {
        super.onVideoError(errorMessage)
        // Log error
        eventLogger.logError("Video error: $errorMessage")
        Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
    }

    /**
     * Show error dialog with recovery options
     */
    override fun showErrorDialog(title: String, message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val dialog = AlertDialog.Builder(this@ExperimentActivity)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("Continue") { _, _ ->
                        // Reset error count and continue
                        errorCount = 0
                        recoveryAttempted = true

                        // Log recovery attempt
                        eventLogger.logEvent(
                            EventType.SYSTEM_RECOVERY,
                        )

                        // Continue with next trial
                        if (currentTrial < trialsPerBlock) {
                            startNextTrial()
                        } else {
                            transitionToState(ExperimentState.BLOCK_END)
                        }
                    }
                    .setNegativeButton("End Experiment") { _, _ ->
                        // Log experiment abort
                        eventLogger.logEvent(
                            EventType.EXPERIMENT_ABORTED,
                        )

                        // Save logs before ending
                        eventLogger.saveEvents()

                        // End experiment
                        transitionToState(ExperimentState.EXPERIMENT_END)
                    }
                    .create()

                dialog.show()
            } catch (e: Exception) {
                Log.e("ExperimentActivity", "Failed to show error dialog: ${e.message}", e)
                // Fallback to toast
                Toast.makeText(this@ExperimentActivity, "$title: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateTimeDisplay() {
        val elapsedMs = getElapsedExperimentTime()
        val seconds = (elapsedMs / 1000) % 60
        val minutes = (elapsedMs / (1000 * 60)) % 60

        timeTextView.text = String.format("Time: %02d:%02d.%03d",
            minutes, seconds, elapsedMs % 1000)
    }

    /**
     * Show battery warning if battery is low at experiment start
     */
    private fun showBatteryWarning() {
        if (isBatteryLow) {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL

            val chargingSymbol = if (isCharging) "⚡" else ""

            runOnUiThread {
                batteryStatusTextView.text = String.format("WARNING: Low Battery: %d%% %s", batteryLevel, chargingSymbol)
                batteryStatusTextView.setTextColor(getColor(android.R.color.holo_red_light))
                batteryStatusTextView.visibility = View.VISIBLE

                // Auto-hide after 10 seconds
                batteryStatusTextView.postDelayed({
                    batteryStatusTextView.visibility = View.GONE
                }, 10000)
            }

            // Log battery warning
            eventLogger.logEvent(EventType.BATTERY_WARNING)
        }
    }

    /**
     * Start the fixation cross countdown timer
     * @param durationMs Total duration of the fixation period in milliseconds
     */
    private fun startFixationCountdown(durationMs: Long) {
        val updateIntervalMs = 16L // Update at ~60fps for smooth animation
        val totalSteps = durationMs / updateIntervalMs
        var remainingSteps = totalSteps

        // Initial display
        updateCountdownDisplay(durationMs, durationMs)

        // Create a repeating task to update the countdown
        val countdownRunnable = object : Runnable {
            override fun run() {
                remainingSteps--
                val remainingMs = remainingSteps * updateIntervalMs

                // Update the display
                updateCountdownDisplay(remainingMs, durationMs)

                if (remainingSteps > 0) {
                    // Schedule the next update
                    handler.postDelayed(this, updateIntervalMs)
                } else {
                    // Countdown finished, move to next state
                    transitionToState(ExperimentState.SPEECH_RECORDING)
                }
            }
        }

        // Start the countdown
        handler.postDelayed(countdownRunnable, updateIntervalMs)
    }

    /**
     * Update the countdown display
     * @param remainingMs Remaining time in milliseconds
     * @param totalMs Total duration in milliseconds
     */
    private fun updateCountdownDisplay(remainingMs: Long, totalMs: Long) {
        // Calculate progress (0.0 to 1.0)
        val progress = remainingMs.toFloat() / totalMs

        // Update the circular countdown view with progress only (no text)
        circularCountdownView.progress = progress
    }

    private fun currentVideoId(): String {
        // Prefer mediaId if you set it when creating MediaItems
        player?.currentMediaItem?.mediaId?.let { if (it.isNotBlank()) return it }

        // Fallback: use filename from uri
        val uri = player?.currentMediaItem?.localConfiguration?.uri
        return uri?.lastPathSegment ?: "unknown_video"
    }

    private fun recordDecision(decision: String) {
        val videoName = currentVideoId()

        decisionStore.setDecision(participantId, videoName, decision)

        Toast.makeText(this, "$decision: $videoName", Toast.LENGTH_SHORT).show()
    }

    /**
     * Start audio recording for the current trial
     */
    private fun startAudioRecording() {
        Log.d("ExperimentActivity", "Starting audio recording, permissions granted: $permissionsGranted")

        // Double-check permissions at runtime
        val micPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (!micPermission) {
            Log.e("ExperimentActivity", "Microphone permission is not granted at runtime check")
            Toast.makeText(
                this,
                "Cannot record audio: microphone permission not granted",
                Toast.LENGTH_LONG
            ).show()

            // Log permission error
            eventLogger.logError("Microphone permission denied during recording")

            // Request permission again if needed
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))

            // Skip recording and move to next state
            handleRecordingComplete()
            return
        }

        // Update UI to show recording state
        experimentContentTextView.visibility = View.GONE
        recordingContainer.visibility = View.VISIBLE

        // send trigger code
        lifecycleScope.launch(Dispatchers.IO) {
            serialPortHelper.sendEventTrigger(EventType.RECORDING_START)
        }

        // Start recording
        audioRecorder.startRecording(
            participantId = participantId,
            runId = runId,
            date = dateString,
            blockNumber = currentBlock,
            trialNumber = currentTrial,
            onComplete = { file ->
                currentRecordingFile = file
                Log.d("ExperimentActivity", "Recording completed successfully: ${file.absolutePath}")

                // Log recording end
                eventLogger.logRecordingEvent(
                    EventType.RECORDING_END,
                    currentBlock,
                    currentTrial,
                    file.name
                )

                // send trigger code
                lifecycleScope.launch(Dispatchers.IO) {
                    serialPortHelper.sendEventTrigger(EventType.RECORDING_END)
                }

                runOnUiThread {
//                    Toast.makeText(
//                        this,
//                        "Recording saved: ${file.name}",
//                        Toast.LENGTH_SHORT
//                    ).show()
                    handleRecordingComplete()
                }
            },
            onError = { error ->
                Log.e("ExperimentActivity", "Recording error: $error")

                // Log error
                eventLogger.logError("Recording error: $error")

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Recording error: $error",
                        Toast.LENGTH_LONG
                    ).show()
                    handleRecordingComplete()
                }
            }
        )
    }

    /**
     * Handle completion of recording
     */
    private fun handleRecordingComplete() {
        stopMicAnimation()

        val globalIndex = (currentBlock - 1) * trialsPerBlock + (currentTrial - 1)
        val absoluteIndex = resumeStartIndex + globalIndex
        progressStore.setLastCompletedIndex(participantId, absoluteIndex)
        Log.d(TAG, "Saved progress: participant=$participantId absoluteIndex=$absoluteIndex (block=$currentBlock trial=$currentTrial)")
        // Add 1 second delay before next trial/block

        handler.postDelayed({
            if (currentTrial < trialsPerBlock) {
                startNextTrial()
            } else {
                transitionToState(ExperimentState.BLOCK_END)
            }
        }, 1000) // 1000ms = 1 second
    }

    private fun startMicAnimation() {
        microphoneImageView.animate()
            .alpha(0.5f)
            .setDuration(500)
            .withEndAction {
                microphoneImageView.animate()
                    .alpha(1.0f)
                    .setDuration(500)
                    .withEndAction {
                        if (experimentState.value == ExperimentState.SPEECH_RECORDING) {
                            startMicAnimation() // Loop if still recording
                        }
                    }
                    .start()
            }
            .start()
    }

    private fun stopMicAnimation() {
        microphoneImageView.animate().cancel()
        microphoneImageView.alpha = 1.0f
    }
}
