package dev.andrea.perroquet

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.Image
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
import kotlinx.coroutines.Dispatchers
import android.util.Log
import android.widget.ImageView
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import dev.andrea.perroquet.logging.EventLogger
import dev.andrea.perroquet.logging.EventType
import dev.andrea.perroquet.usbserial.SerialPortHelper
import dev.andrea.perroquet.util.SessionStimuliLoader
import dev.andrea.perroquet.util.VideoProgressStore
import dev.andrea.perroquet.util.RunStore

class ExperimentActivity : BaseExperimentActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var trialTextView: TextView
    private lateinit var timeTextView: TextView
    private lateinit var startButton: Button
    private lateinit var nextButton: Button
    private lateinit var reloadButton: Button
    private lateinit var passButton: Button
    private lateinit var failButton: Button
    private lateinit var decisionStore: dev.andrea.perroquet.util.DecisionStore

    private lateinit var exitButton: Button
    private lateinit var imageView: ImageView
    private lateinit var experimentContentTextView: TextView
    private lateinit var recordingContainer: View

    private lateinit var connectionStatusTextView: TextView
    private lateinit var batteryStatusTextView: TextView

    private lateinit var trialsLeftTextView: TextView

    private var participantId: Int = -1
    private var dateString: String = ""

    private var runId: String = ""

    private var mode: String = ParticipantInputActivity.MODE_FULL
    private val IMAGE_DISPLAY_MS = 8_000L
    private var imageTimeoutRunnable: Runnable? = null

    // CHANGED: Removed isAudioCaptureRunning flag - recording is always running
    var config: ExperimentConfig.Standard? = null
    var imageQueue: List<String> = emptyList()
    private val stimuliLoader by lazy { SessionStimuliLoader(this) }
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

    // Audio recording - CHANGED: Simplified
    private lateinit var audioRecorder: AudioRecorder
    private var currentRecordingFile: File? = null
    private var permissionsGranted = false

    private var isReloadingTrial = false

    // CHANGED: Removed stopRequested flag - not needed for continuous recording

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
            Toast.makeText(this, getString(R.string.permission_audio_granted), Toast.LENGTH_SHORT).show()
            // CHANGED: Start continuous recording once permissions granted
            startContinuousRecording()
        } else {
            Toast.makeText(this, getString(R.string.permission_audio_denied), Toast.LENGTH_LONG).show()
        }
    }

    // CHANGED: New method for continuous recording
    private fun startContinuousRecording() {
        if (currentRecordingFile != null) {
            Log.d(TAG, "Recording already in progress, skipping")
            return
        }

        Log.d(TAG, "Starting continuous recording")

        audioRecorder.startRecording(
            participantId = participantId,
            runId = runId,
            date = dateString,
            trialNumber = currentTrial, // No specific trial - continuous
            onComplete = { file ->
                currentRecordingFile = file
                Log.d(TAG, "Continuous recording completed: ${file.name}")

                // Log recording end
                eventLogger.logRecordingEvent(
                    EventType.RECORDING_END,
                    trialNumber = currentTrial,
                    file.name
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    serialPortHelper.sendEventTrigger(EventType.RECORDING_END)
                }
            },
            onError = { error ->
                eventLogger.logError("Continuous recording error: $error")
                currentRecordingFile = null
            }
        )

        // Log recording start
        eventLogger.logRecordingEvent(
            EventType.RECORDING_START,
            trialNumber = currentTrial ,
            "continuous_recording"
        )
        lifecycleScope.launch(Dispatchers.IO) {
            serialPortHelper.sendEventTrigger(EventType.RECORDING_START)
        }
    }

    private fun isClinical() = (mode == ParticipantInputActivity.MODE_PASSED_ONLY)

    private fun startImageWindow(imagePath: String) {
        imageWindowActive = true

        val file = File(imagePath)
        Log.d(TAG, "Image path: $imagePath")

        imageView.setImageURI(android.net.Uri.fromFile(file))
        imageView.visibility = View.VISIBLE
        imageView.bringToFront()

        recordingContainer.visibility = View.VISIBLE

        imageTimeoutRunnable?.let { handler.removeCallbacks(it) }

        imageTimeoutRunnable = Runnable {
            imageWindowActive = false
            imageView.visibility = View.GONE
            recordingContainer.setBackgroundColor(Color.WHITE)

            Log.d(TAG, "Image window expired -> white screen")
        }

        handler.postDelayed(imageTimeoutRunnable!!, IMAGE_DISPLAY_MS)
    }

    private fun onDecision(decision: String) {
        // Prevent duplicate decisions
        if (!passButton.isEnabled && !failButton.isEnabled) {
            Log.d(TAG, "Decision already recorded, ignoring")
            return
        }

        recordDecision(decision)

        passButton.isEnabled = false
        failButton.isEnabled = false

        if (imageWindowActive) {
            // Early click: cancel timer, hide UI, manually trigger video end
            imageTimeoutRunnable?.let { handler.removeCallbacks(it) }
            imageWindowActive = false

            Log.d(TAG, "Early decision '$decision' -> triggering video end manually")

            // Hide UI elements immediately
            imageView.visibility = View.GONE
            recordingContainer.visibility = View.GONE

            // Manually log VIDEO_END since the video didn't actually play to completion
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val logger = dev.andrea.perroquet.logging.EventLogger.getInstance()
                    logger.logVideoEvent(
                        EventType.STIMULUS_OFFSET,
                        null,
                        currentTrial,
                        currentVideoName ?: "unknown"
                    )
                    serialPortHelper.sendEventTrigger(EventType.STIMULUS_OFFSET)
                } catch (e: Exception) {
                    Log.e(TAG, "Error logging early video end: ${e.message}")
                }
            }

            // CHANGED: Just advance directly - no recording state needed
            advanceAfterDecision()

        } else {
            // Normal click after 8s timeout (white screen showing)
            Log.d(TAG, "Decision '$decision' after image timeout -> advancing")

            recordingContainer.visibility = View.GONE
            advanceAfterDecision()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_experiment)
        mode = intent.getStringExtra(ParticipantInputActivity.EXTRA_MODE)
            ?: ParticipantInputActivity.MODE_FULL

        passButton = findViewById(R.id.passButton)
        failButton = findViewById(R.id.failButton)

        decisionStore = dev.andrea.perroquet.util.DecisionStore(this)

        passButton.setOnClickListener { onDecision("PASS") }
        failButton.setOnClickListener { onDecision("FAIL") }

        exitButton = findViewById(R.id.exitButton)

        exitButton.setOnClickListener {
            showExitConfirmDialog()
        }

        // Status text views
        connectionStatusTextView = findViewById(R.id.connectionStatusTextView)

        // Hide battery warning by default, only show if battery is low at start
        batteryStatusTextView = findViewById(R.id.batteryStatusTextView)
        batteryStatusTextView.visibility = View.GONE

        trialsLeftTextView = findViewById(R.id.trialsLeftTextView)

        // Hide the status bar and make the app full screen
        hideSystemUI()

        // Get intent data
        participantId = intent.getIntExtra(ParticipantInputActivity.EXTRA_PARTICIPANT_ID, -1)
        dateString = intent.getStringExtra(ParticipantInputActivity.EXTRA_DATE) ?: LocalDate.now().toString()
        runId = intent.getStringExtra(ParticipantInputActivity.EXTRA_RUN_ID) ?: java.util.UUID.randomUUID().toString()

        if (mode == ParticipantInputActivity.MODE_RESTART) {
            val lastCompleted = progressStore.getLastCompletedIndex(participantId)
            if (lastCompleted < 0) {
                Toast.makeText(
                    this,
                    "Impossible de recommencer : aucune session n'a encore été commencée.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return
            }
        }

        val runDir = RunStore.getOrCreateRunDir(this, participantId, dateString, runId)
        val logDir = File(runDir, "logs").apply { mkdirs() }

        val allImages = stimuliLoader.loadStimuliInOrder()

        imageQueue = if (mode == ParticipantInputActivity.MODE_PASSED_ONLY) {
            val passed = decisionStore.getPassedStimuli(participantId)
            allImages.filter { it in passed }
        } else {
            allImages
        }

        if (mode == ParticipantInputActivity.MODE_PASSED_ONLY && imageQueue.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_videos_valides), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Create experiment config
        config = ExperimentConfig.Standard(
            participantId = participantId,
            date = LocalDate.parse(dateString),
            runId = runId
        )

        // Read progress and compute where to resume (next trial)
        val lastCompleted = progressStore.getLastCompletedIndex(participantId)

        resumeStartIndex = if (mode == ParticipantInputActivity.MODE_RESTART) {
            // Restart from beginning
            progressStore.setLastCompletedIndex(participantId, -1)
            0
        } else {
            // Continue
            (lastCompleted + 1).coerceAtLeast(0)
        }
        Log.d("ExperimentActivity", "Mode=$mode lastCompleted=$lastCompleted resumeStartIndex=$resumeStartIndex")
        if (resumeStartIndex >= imageQueue.size) {
            Log.d(TAG, "Participant already completed all videos. lastCompleted=$lastCompleted")
            resumeStartIndex = 0
        }

        // compute remaining trials based on resume point
        val remaining = imageQueue.size - resumeStartIndex
        if (remaining <= 0) {
            Toast.makeText(this, getString(R.string.aucune_video_restante), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Log.d(TAG, "Resume: lastCompleted=$lastCompleted -> startIndex=$resumeStartIndex (remaining=$remaining)")
        Log.d(TAG, "Prepared ordered video queue (${imageQueue.size}): ${imageQueue.joinToString()}")

        // Initialize experiment (no blocks)
        initializeExperiment(remaining)

        // Log prepared video queue
        Log.d(TAG, "Prepared video queue with ${imageQueue.size} videos: ${imageQueue.joinToString()}")

        // Initialize audio recorder
        audioRecorder = AudioRecorder(this)

        // Check and request permissions (will start recording when granted)
        checkAndRequestPermissions()

        // Initialize views
        statusTextView = findViewById(R.id.statusTextView)
        trialTextView = findViewById(R.id.trialTextView)
        timeTextView = findViewById(R.id.timeTextView)
        startButton = findViewById(R.id.startButton)
        nextButton = findViewById(R.id.nextButton)
        reloadButton = findViewById(R.id.reloadButton)

        startButton.setOnClickListener { handleNextButtonClick() }
        nextButton.setOnClickListener { handleNextButtonClick() }

        reloadButton.setOnClickListener {
            reloadCurrentTrial()
        }
        imageView = findViewById(R.id.imageView)
        experimentContentTextView = findViewById(R.id.experimentContentTextView)
        recordingContainer = findViewById(R.id.recordingContainer)

        applyModeToButtons()

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
        // stop periodic UI updates
        handler.removeCallbacks(updateTimeRunnable)

        // CHANGED: Stop continuous recording
        stopContinuousRecording()

        // stop usb
        serialPortHelper.cleanup()

        super.onDestroy()
    }

    // CHANGED: New method to stop continuous recording
    private fun stopContinuousRecording() {
        Log.d(TAG, "Stopping continuous recording")
        try {
            audioRecorder.stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}", e)
        }
    }

    override fun onStart() {
        super.onStart()
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
            // CHANGED: Stop continuous recording
            stopContinuousRecording()

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
            // CHANGED: Start recording immediately if permissions already granted
            startContinuousRecording()
        }
    }

    override fun onStateChanged(state: ExperimentState) {
        if (state == ExperimentState.TRIAL_VIDEO) {
            val imageFile = imageQueue
                .getOrNull(resumeStartIndex + currentTrial - 1)
                ?: return

            startImageWindow(imageFile)

            trialsLeftTextView.visibility = View.VISIBLE

            runOnUiThread {
                passButton.isEnabled = true
                failButton.isEnabled = true
            }
        }

        if (state == ExperimentState.EXPERIMENT_END) {
            eventLogger.logEvent(EventType.EXPERIMENT_END)

            lifecycleScope.launch(Dispatchers.IO) {
                serialPortHelper.sendEventTrigger(EventType.EXPERIMENT_END)
            }

            eventLogger.saveEvents()

            progressStore.setLastCompletedIndex(participantId, -1)

            nextButton.isEnabled = false
            nextButton.text = getString(R.string.end)

            // CHANGED: Stop continuous recording at experiment end
            stopContinuousRecording()
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
                startNextTrial()
            }

            ExperimentState.EXPERIMENT_END -> {
                // Close the app completely when experiment is done
                finishAffinity()
            }

            // CHANGED: Removed SPEECH_RECORDING handling
            else -> { /* No action needed */ }
        }
    }

    private fun applyModeToButtons() {
        val clinical = isClinical()

        if (clinical) {
            // Clinical: Next + Exit only
            startButton.visibility = View.GONE
            reloadButton.visibility = View.GONE
            passButton.visibility = View.GONE
            failButton.visibility = View.GONE

            nextButton.visibility = View.VISIBLE
            exitButton.visibility = View.VISIBLE
        } else {
            // Full experiment: no Next button at all
            nextButton.visibility = View.GONE
            exitButton.visibility = View.VISIBLE

            // Keep these managed by updateUI/state
            reloadButton.visibility = View.GONE
            passButton.visibility = View.GONE
            failButton.visibility = View.GONE
        }
    }

    private fun reloadCurrentTrial() {
        val imageFile = imageQueue
            .getOrNull(resumeStartIndex + currentTrial - 1)
            ?: return

        startImageWindow(imageFile)
    }

    /**
     * Update the connection status display
     */
    private fun updateConnectionStatus(state: SerialPortHelper.ConnectionState) {
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

            val text_visibility = when (state) {
                SerialPortHelper.ConnectionState.CONNECTED -> View.GONE
                else -> View.VISIBLE
            }
            connectionStatusTextView.apply {
                text = statusText
                setTextColor(textColor)
                visibility = text_visibility
                bringToFront()
                alpha = 0.7f
                animate().alpha(1.0f).setDuration(300).start()
            }
        }
    }

    private fun updateUI(state: ExperimentState) {
        val clinical = isClinical()

        // Update status text
        statusTextView.text = getString(R.string.statut_format, state.name)

        // Update trial counters
        trialTextView.text = getString(R.string.trial_counter_format, currentTrial, totalTrials)

        exitButton.visibility = View.VISIBLE
        exitButton.bringToFront()

        // hide overlays
        imageView.visibility = View.GONE
        experimentContentTextView.visibility = View.GONE
        if (state != ExperimentState.TRIAL_VIDEO) {
            recordingContainer.visibility = View.GONE
        }

        // hide decision buttons
        passButton.visibility = View.GONE
        failButton.visibility = View.GONE
        reloadButton.visibility = View.GONE

        val current = currentTrial.coerceAtLeast(1)
        val total = totalTrials.coerceAtLeast(1)

        // Update experiment content visibility
        when (state) {
            ExperimentState.TRIAL_VIDEO -> {
                imageView.visibility = View.VISIBLE
                trialsLeftTextView.visibility = View.VISIBLE
                trialsLeftTextView.text = getString(R.string.trial_counter_format, current, total)
                trialsLeftTextView.bringToFront()
                exitButton.bringToFront()
                reloadButton.visibility = if (clinical) View.GONE else View.VISIBLE

                passButton.visibility = if (clinical) View.GONE else View.VISIBLE
                failButton.visibility = if (clinical) View.GONE else View.VISIBLE
            }

            ExperimentState.IDLE -> {
                experimentContentTextView.visibility = View.VISIBLE
                experimentContentTextView.text = getString(R.string.pret_a_commencer)
                reloadButton.visibility = View.GONE
            }

            // CHANGED: Removed SPEECH_RECORDING case

            ExperimentState.EXPERIMENT_END -> {
                experimentContentTextView.visibility = View.VISIBLE
                experimentContentTextView.text = getString(R.string.experience_terminee)
                reloadButton.visibility = View.GONE
            }

            else -> {
                experimentContentTextView.visibility = View.VISIBLE
                experimentContentTextView.text = getString(R.string.zone_contenu_experience)
                trialsLeftTextView.visibility = View.GONE
            }
        }

        when (state) {
            ExperimentState.IDLE -> {
                if (clinical) {
                    nextButton.visibility = View.VISIBLE
                    nextButton.isEnabled = true
                    nextButton.text = getString(R.string.start)
                    startButton.visibility = View.GONE
                } else {
                    startButton.visibility = View.VISIBLE
                    startButton.isEnabled = true
                    startButton.text = getString(R.string.start)
                    nextButton.visibility = View.GONE
                }
            }

            ExperimentState.EXPERIMENT_END -> {
                if (clinical) {
                    nextButton.visibility = View.VISIBLE
                    nextButton.isEnabled = true
                    nextButton.text = getString(R.string.end)
                    startButton.visibility = View.GONE
                } else {
                    startButton.visibility = View.VISIBLE
                    startButton.isEnabled = true
                    startButton.text = getString(R.string.end)
                    nextButton.visibility = View.GONE
                }
            }

            // CHANGED: Removed SPEECH_RECORDING case

            else -> {
                nextButton.visibility = View.GONE
                startButton.visibility = View.GONE
            }
        }
    }

    override fun onVideoError(errorMessage: String) {
        super.onVideoError(errorMessage)
        eventLogger.logError("Video error: $errorMessage")
        Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
    }

    override fun showErrorDialog(title: String, message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val dialog = AlertDialog.Builder(this@ExperimentActivity)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.to_continue)) { _, _ ->
                        errorCount = 0
                        recoveryAttempted = true

                        eventLogger.logEvent(EventType.SYSTEM_RECOVERY)

                        if (currentTrial < totalTrials) {
                            startNextTrial()
                        } else {
                            transitionToState(ExperimentState.EXPERIMENT_END)
                        }
                    }
                    .setNegativeButton(getString(R.string.terminer_experience)) { _, _ ->
                        eventLogger.logEvent(EventType.EXPERIMENT_ABORTED)
                        eventLogger.saveEvents()
                        transitionToState(ExperimentState.EXPERIMENT_END)
                    }
                    .create()

                dialog.show()
            } catch (e: Exception) {
                Log.e("ExperimentActivity", "Failed to show error dialog: ${e.message}", e)
                Toast.makeText(this@ExperimentActivity, "$title: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private fun updateTimeDisplay() {
        val elapsedMs = getElapsedExperimentTime()
        val seconds = (elapsedMs / 1000) % 60
        val minutes = (elapsedMs / (1000 * 60)) % 60

        timeTextView.text = String.format("Time: %02d:%02d.%03d",
            minutes, seconds, elapsedMs % 1000)
    }

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

                batteryStatusTextView.postDelayed({
                    batteryStatusTextView.visibility = View.GONE
                }, 10000)
            }

            eventLogger.logEvent(EventType.BATTERY_WARNING)
        }
    }

    private fun currentImageId(): String {
        return imageQueue
            .getOrNull(resumeStartIndex + currentTrial - 1)
            ?.let { File(it).name }
            ?: "unknown_image"
    }

    private fun recordDecision(decision: String) {
        val imageName = currentImageId()
        decisionStore.setDecision(participantId, imageName, decision)
        Log.i("Decision", "participant=$participantId decision=$decision video=$imageName")
    }

    // CHANGED: Simplified - just advance to next trial
    private fun advanceAfterDecision() {
        handleTrialComplete()
    }

    /**
     * Handle completion of trial - CHANGED: No recording handling
     */
    private fun handleTrialComplete() {

        val absoluteIndex = resumeStartIndex + (currentTrial - 1)
        progressStore.setLastCompletedIndex(participantId, absoluteIndex)
        Log.d(TAG, "Saved progress: participant=$participantId absoluteIndex=$absoluteIndex trial=$currentTrial")

        handler.postDelayed({
            if (currentTrial < totalTrials) {
                startNextTrial()
            } else {
                transitionToState(ExperimentState.EXPERIMENT_END)
            }
        }, 250)
    }
}