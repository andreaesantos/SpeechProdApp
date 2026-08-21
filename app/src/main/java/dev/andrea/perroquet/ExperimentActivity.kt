package dev.andrea.perroquet

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import dev.andrea.perroquet.logging.EventLogger
import dev.andrea.perroquet.logging.EventType
import dev.andrea.perroquet.usbserial.SerialPortHelper
import dev.andrea.perroquet.util.SessionStimuliLoader
import dev.andrea.perroquet.util.VideoProgressStore
import dev.andrea.perroquet.util.RunStore
import dev.andrea.perroquet.util.FallbackParticipantIdGenerator

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

    private var imageTimeoutRunnable: Runnable? = null
    private var stimulusOffsetJob: Job? = null
    private val IMAGE_DISPLAY_MS = 8_000L
    private var eventsSaved = false

    var config: ExperimentConfig.Standard? = null
    var imageQueue: List<String> = emptyList()
    private val stimuliLoader by lazy { SessionStimuliLoader(this) }
    private val progressStore by lazy { VideoProgressStore(this) }

    var resumeStartIndex: Int = 0
        private set

    private val handler = Handler(Looper.getMainLooper())

    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateTimeDisplay()
            handler.postDelayed(this, 100)
        }
    }

    private lateinit var eventLogger: EventLogger
    lateinit var serialPortHelper: SerialPortHelper

    private fun isClinical() = (mode == ParticipantInputActivity.MODE_PASSED_ONLY)

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
            if (experimentState.value == ExperimentState.EXPERIMENT_END) {
                finishProtocol()
            } else {
                showExitConfirmDialog()
            }
        }

        connectionStatusTextView = findViewById(R.id.connectionStatusTextView)
        batteryStatusTextView = findViewById(R.id.batteryStatusTextView)
        batteryStatusTextView.visibility = View.GONE
        trialsLeftTextView = findViewById(R.id.trialsLeftTextView)

        hideSystemUI()

        val hasParticipantIdExtra = intent.hasExtra(ParticipantInputActivity.EXTRA_PARTICIPANT_ID)
        val intentParticipantId = intent.getIntExtra(ParticipantInputActivity.EXTRA_PARTICIPANT_ID, -1)
        participantId = FallbackParticipantIdGenerator.getSafeParticipantId(intentParticipantId, hasParticipantIdExtra)
        
        if (!hasParticipantIdExtra || participantId != intentParticipantId) {
            Log.w("ExperimentActivity", "Using fallback or modified participantID: $participantId (intent had: $intentParticipantId, extra present: $hasParticipantIdExtra)")
        }

        dateString = intent.getStringExtra(ParticipantInputActivity.EXTRA_DATE) ?: LocalDate.now().toString()
        runId = intent.getStringExtra(ParticipantInputActivity.EXTRA_RUN_ID) ?: java.util.UUID.randomUUID().toString()

        if (mode == ParticipantInputActivity.MODE_RESTART) {
            val lastCompleted = progressStore.getLastCompletedIndex(participantId)
            if (lastCompleted < 0) {
                Toast.makeText(this,
                    "Impossible de recommencer : aucune session n'a encore été commencée.",
                    Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }

        val runDir = RunStore.getOrCreateRunDir(
            context = this,
            participantId = participantId,
            runNumber = runId
        )
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

        config = ExperimentConfig.Standard(
            participantId = participantId,
            date = LocalDate.parse(dateString),
            runId = runId
        )

        val lastCompleted = progressStore.getLastCompletedIndex(participantId)

        resumeStartIndex = if (mode == ParticipantInputActivity.MODE_RESTART) {
            progressStore.setLastCompletedIndex(participantId, -1)
            0
        } else {
            (lastCompleted + 1).coerceAtLeast(0)
        }

        Log.d("ExperimentActivity", "Mode=$mode lastCompleted=$lastCompleted resumeStartIndex=$resumeStartIndex")

        if (resumeStartIndex >= imageQueue.size) {
            Log.d(TAG, "Participant already completed all images. lastCompleted=$lastCompleted")
            resumeStartIndex = 0
        }

        val remaining = imageQueue.size - resumeStartIndex
        if (remaining <= 0) {
            Toast.makeText(this, getString(R.string.aucune_video_restante), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Log.d(TAG, "Resume: lastCompleted=$lastCompleted -> startIndex=$resumeStartIndex (remaining=$remaining)")

        initializeExperiment(remaining)

        statusTextView = findViewById(R.id.statusTextView)
        trialTextView = findViewById(R.id.trialTextView)
        timeTextView = findViewById(R.id.timeTextView)
        startButton = findViewById(R.id.startButton)
        nextButton = findViewById(R.id.nextButton)
        reloadButton = findViewById(R.id.reloadButton)

        startButton.setOnClickListener { handleNextButtonClick() }
        nextButton.setOnClickListener { handleNextButtonClick() }
        reloadButton.setOnClickListener { reloadCurrentTrial() }

        imageView = findViewById(R.id.imageView)
        experimentContentTextView = findViewById(R.id.experimentContentTextView)
        recordingContainer = findViewById(R.id.recordingContainer)

        applyModeToButtons()
        startButton.visibility = View.GONE

        eventLogger = EventLogger.initialize(this, this.experimentStartTime, logDir)
        eventLogger.clearEvents()
        eventLogger.setExperimentInfo(participantId, dateString, runId)

        serialPortHelper = SerialPortHelper(this)
        connectionStatusTextView.visibility = View.VISIBLE

        lifecycleScope.launch {
            serialPortHelper.connectionState.collect { state ->
                updateConnectionStatus(state)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val connected = serialPortHelper.connectToFirstAvailable()
            if (!connected) Log.w("ExperimentActivity", "No USB devices found")
        }

        handler.post(updateTimeRunnable)
    }

    // ── Image display ─────────────────────────────────────────────────────────

    private fun showImageForTrial() {
        val imagePath = imageQueue.getOrNull(resumeStartIndex + currentTrial - 1) ?: run {
            Log.e(TAG, "No image at index ${resumeStartIndex + currentTrial - 1}")
            return
        }

        val fileName = File(imagePath).name  // strips any path, keeps "pn1.jpg"

        imageTimeoutRunnable?.let { handler.removeCallbacks(it) }
        stimulusOffsetJob = null

        try {
            assets.open("PN_mp4/$fileName").use { inputStream ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap == null) {
                    Log.e(TAG, "Failed to decode bitmap from assets: PN_mp4/$fileName")
                    return
                }
                imageView.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not open asset: PN_mp4/$fileName — ${e.message}")
            return
        }

        imageView.visibility = View.VISIBLE
        recordingContainer.visibility = View.VISIBLE
        recordingContainer.setBackgroundColor(Color.WHITE)

        passButton.isEnabled = true
        failButton.isEnabled = true

        lifecycleScope.launch(Dispatchers.IO) {
            eventLogger.logVideoEvent(EventType.STIMULUS_ONSET, null, currentTrial, fileName)
            serialPortHelper.sendEventTrigger(EventType.STIMULUS_ONSET)
        }

        imageTimeoutRunnable = Runnable {
            imageView.visibility = View.GONE
            recordingContainer.setBackgroundColor(Color.BLACK)
            Log.d(TAG, "Image timeout -> black screen")

            stimulusOffsetJob = lifecycleScope.launch(Dispatchers.IO) {
                eventLogger.logVideoEvent(EventType.STIMULUS_OFFSET, null, currentTrial, fileName)
                serialPortHelper.sendEventTrigger(EventType.STIMULUS_OFFSET)
            }
        }
        handler.postDelayed(imageTimeoutRunnable!!, IMAGE_DISPLAY_MS)
    }

    // ── Decision handling ─────────────────────────────────────────────────────

    private fun onDecision(decision: String) {
        // Prevent duplicate decisions
        if (!passButton.isEnabled && !failButton.isEnabled) return

        recordDecision(decision)
        passButton.isEnabled = false
        failButton.isEnabled = false

        // Cancel the image timeout — decision already made
        imageTimeoutRunnable?.let { handler.removeCallbacks(it) }
        imageTimeoutRunnable = null

        // Hide image immediately
        imageView.visibility = View.GONE
        recordingContainer.visibility = View.GONE

        val fileName = imageQueue.getOrNull(resumeStartIndex + currentTrial - 1)
            ?.let { File(it).name } ?: "unknown"

        val existingJob = stimulusOffsetJob
        if (existingJob != null) {
            lifecycleScope.launch {
                existingJob.join()
                advanceAfterDecision()
            }
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                eventLogger.logVideoEvent(EventType.STIMULUS_OFFSET, null, currentTrial, fileName)
                serialPortHelper.sendEventTrigger(EventType.STIMULUS_OFFSET)
                withContext(Dispatchers.Main) { advanceAfterDecision() }
            }
        }
    }

    // ── State changes ─────────────────────────────────────────────────────────

    override fun onStateChanged(state: ExperimentState) {
        updateUI(state)
        when (state) {
            ExperimentState.TRIAL_VIDEO -> {
                eventLogger.logEvent(EventType.TRIAL_START)
                serialPortHelper.sendEventTrigger(EventType.TRIAL_START)
                showImageForTrial()
            }

            ExperimentState.SPEECH_PRODUCTION -> {
                // Nothing to do — pass/fail buttons already enabled in showImageForTrial()
            }

            ExperimentState.EXPERIMENT_END -> {
                imageTimeoutRunnable?.let { handler.removeCallbacks(it) }
                eventLogger.logEvent(EventType.EXPERIMENT_ENDED)
                serialPortHelper.sendEventTrigger(EventType.EXPERIMENT_ENDED)
                progressStore.setLastCompletedIndex(participantId, -1)
            }

            else -> { /* no-op */ }
        }
    }

    // ── Trial advancement ─────────────────────────────────────────────────────

    private fun advanceAfterDecision() {
        eventLogger.logEvent(EventType.TRIAL_END)
        serialPortHelper.sendEventTrigger(EventType.TRIAL_END)

        val absoluteIndex = resumeStartIndex + (currentTrial - 1)
        progressStore.setLastCompletedIndex(participantId, absoluteIndex)
        Log.d(TAG, "Saved progress: participant=$participantId absoluteIndex=$absoluteIndex")

        handler.postDelayed({
            if (currentTrial < totalTrials) startNextTrial()
            else transitionToState(ExperimentState.EXPERIMENT_END)
        }, 250)
    }

    private fun reloadCurrentTrial() {
        imageTimeoutRunnable?.let { handler.removeCallbacks(it) }
        imageTimeoutRunnable = null
        showImageForTrial()
    }

    // ── Button handling ───────────────────────────────────────────────────────

    private fun handleNextButtonClick() {
        when (experimentState.value) {
            ExperimentState.IDLE -> {
                eventLogger.logEvent(EventType.EXPERIMENT_START)
                eventLogger.logEvent(EventType.RECORDING_START)
                lifecycleScope.launch(Dispatchers.IO) {
                    serialPortHelper.sendEventTrigger(EventType.EXPERIMENT_START)
                    serialPortHelper.sendEventTrigger(EventType.RECORDING_START)
                }
                hideSystemUI()
                startNextTrial()
            }
            ExperimentState.TRIAL_VIDEO -> {
                // Clinical mode: Next pressed during image display — advance immediately
                if (isClinical()) {
                    imageTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    imageTimeoutRunnable = null
                    imageView.visibility = View.GONE
                    recordingContainer.visibility = View.GONE
                    val fileName = imageQueue.getOrNull(resumeStartIndex + currentTrial - 1)
                        ?.let { File(it).name } ?: "unknown"
                    val existingJob = stimulusOffsetJob
                    if (existingJob != null) {
                        lifecycleScope.launch {
                            existingJob.join()
                            advanceAfterDecision()
                        }
                    } else {
                        lifecycleScope.launch(Dispatchers.IO) {
                            eventLogger.logVideoEvent(EventType.STIMULUS_OFFSET, null, currentTrial, fileName)
                            serialPortHelper.sendEventTrigger(EventType.STIMULUS_OFFSET)
                            withContext(Dispatchers.Main) { advanceAfterDecision() }
                        }
                    }
                }
            }
            ExperimentState.EXPERIMENT_END -> finishProtocol()
            else -> { /* no-op */ }
        }
    }

    // ── Exit ──────────────────────────────────────────────────────────────────

    private fun showExitConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("Quitter l'expérience ?")
            .setMessage("L'expérience va s'arrêter maintenant. Voulez-vous quitter ? (N'oubliez pas de balayer vers le haut pour fermer l'application après avoir quitté")
            .setPositiveButton("Quitter") { _, _ -> exitExperimentNow() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun exitExperimentNow() {
        try {
            imageTimeoutRunnable?.let { handler.removeCallbacks(it) }
            ContinuousRecorder.stop()
            eventLogger.logEvent(EventType.RECORDING_END)
            serialPortHelper.sendEventTrigger(EventType.RECORDING_END)
            eventLogger.logEvent(EventType.EXPERIMENT_ENDED)
            serialPortHelper.sendEventTrigger(EventType.EXPERIMENT_ENDED)
            eventLogger.saveEvents(true)
            eventsSaved = true
        } finally {
            finishAffinity()
        }
    }

    private fun finishProtocol() {
        ContinuousRecorder.stop()
        eventLogger.logEvent(EventType.RECORDING_END)
        serialPortHelper.sendEventTrigger(EventType.RECORDING_END)
        eventLogger.logEvent(EventType.PROTOCOL_FINISHED)
        eventLogger.saveEvents()
        eventsSaved = true
        finishAffinity()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun applyModeToButtons() {
        val clinical = isClinical()
        if (clinical) {
            startButton.visibility = View.GONE
            reloadButton.visibility = View.GONE
            passButton.visibility = View.GONE
            failButton.visibility = View.GONE
            nextButton.visibility = View.VISIBLE
            exitButton.visibility = View.VISIBLE
        } else {
            nextButton.visibility = View.GONE
            exitButton.visibility = View.VISIBLE
            reloadButton.visibility = View.GONE
            passButton.visibility = View.GONE
            failButton.visibility = View.GONE
        }
    }

    private fun updateUI(state: ExperimentState) {
        val clinical = isClinical()

        statusTextView.text = getString(R.string.statut_format, state.name)
        trialTextView.text = getString(R.string.trial_counter_format, currentTrial, totalTrials)

        exitButton.visibility = View.VISIBLE

        // Hide everything by default, then show what's needed per state
        if (state != ExperimentState.TRIAL_VIDEO) {
            imageView.visibility = View.GONE
            recordingContainer.visibility = View.GONE
        }
        experimentContentTextView.visibility = View.GONE
        passButton.visibility = View.GONE
        failButton.visibility = View.GONE
        reloadButton.visibility = View.GONE

        val current = currentTrial.coerceAtLeast(1)
        val total = totalTrials.coerceAtLeast(1)

        when (state) {
            ExperimentState.TRIAL_VIDEO -> {
                trialsLeftTextView.visibility = View.VISIBLE
                trialsLeftTextView.text = getString(R.string.trial_counter_format, current, total)
                trialsLeftTextView.bringToFront()
                passButton.visibility = if (clinical) View.GONE else View.VISIBLE
                failButton.visibility = if (clinical) View.GONE else View.VISIBLE

                if (clinical) {
                    nextButton.visibility = View.VISIBLE
                    nextButton.isEnabled = true
                    nextButton.text = getString(R.string.next)
                }
            }

            ExperimentState.IDLE -> {
                experimentContentTextView.visibility = View.VISIBLE
                experimentContentTextView.text = getString(R.string.pret_a_commencer)
            }

            ExperimentState.SPEECH_PRODUCTION -> {
                recordingContainer.visibility = View.VISIBLE
                passButton.visibility = if (clinical) View.GONE else View.VISIBLE
                failButton.visibility = if (clinical) View.GONE else View.VISIBLE
                reloadButton.visibility = if (clinical) View.GONE else View.VISIBLE
            }

            ExperimentState.EXPERIMENT_END -> {
                experimentContentTextView.visibility = View.VISIBLE
                experimentContentTextView.text = getString(R.string.experience_terminee)
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
                    nextButton.visibility = View.VISIBLE; nextButton.isEnabled = true
                    nextButton.text = getString(R.string.start); startButton.visibility = View.GONE
                } else {
                    startButton.visibility = View.VISIBLE; startButton.isEnabled = true
                    startButton.text = getString(R.string.start); nextButton.visibility = View.GONE
                }
            }
            ExperimentState.EXPERIMENT_END -> {
                nextButton.visibility = View.GONE
                startButton.visibility = View.GONE
            }
            ExperimentState.SPEECH_PRODUCTION -> {
                if (clinical) {
                    nextButton.visibility = View.VISIBLE; nextButton.isEnabled = true
                    nextButton.text = getString(R.string.next)
                } else {
                    nextButton.visibility = View.GONE
                }
                startButton.visibility = View.GONE
            }
            else -> {
                if (!clinical) nextButton.visibility = View.GONE
                startButton.visibility = View.GONE
            }
        }
    }

    // ── Error handling ────────────────────────────────────────────────────────

    override fun onVideoError(errorMessage: String) {
        super.onVideoError(errorMessage)
        eventLogger.logError("Image error: $errorMessage")
        Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
    }

    override fun showErrorDialog(title: String, message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                AlertDialog.Builder(this@ExperimentActivity)
                    .setTitle(title).setMessage(message).setCancelable(false)
                    .setPositiveButton(getString(R.string.to_continue)) { _, _ ->
                        errorCount = 0; recoveryAttempted = true
                        eventLogger.logEvent(EventType.SYSTEM_RECOVERY)
                        if (currentTrial < totalTrials) startNextTrial()
                        else transitionToState(ExperimentState.EXPERIMENT_END)
                    }
                    .setNegativeButton(getString(R.string.terminer_experience)) { _, _ ->
                        eventLogger.logEvent(EventType.EXPERIMENT_ABORTED)
                        eventLogger.saveEvents()
                        transitionToState(ExperimentState.EXPERIMENT_END)
                    }
                    .create().show()
            } catch (e: Exception) {
                Toast.makeText(this@ExperimentActivity, "$title: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    override fun onDestroy() {
        handler.removeCallbacks(updateTimeRunnable)
        imageTimeoutRunnable?.let { handler.removeCallbacks(it) }
        ContinuousRecorder.stop()
        if (!eventsSaved) {
            try { eventLogger.saveEvents(true) } catch (_: Exception) {}
        }
        serialPortHelper.cleanup()
        super.onDestroy()
    }

    @SuppressLint("DefaultLocale")
    private fun updateTimeDisplay() {
        val elapsedMs = getElapsedExperimentTime()
        val seconds = (elapsedMs / 1000) % 60
        val minutes = (elapsedMs / (1000 * 60)) % 60
        timeTextView.text = String.format("Time: %02d:%02d.%03d", minutes, seconds, elapsedMs % 1000)
    }

    private fun showBatteryWarning() {
        if (isBatteryLow) {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val chargingSymbol = if (isCharging) "⚡" else ""
            runOnUiThread {
                batteryStatusTextView.text = String.format(
                    "WARNING: Low Battery: %d%% %s", batteryLevel, chargingSymbol)
                batteryStatusTextView.setTextColor(getColor(android.R.color.holo_red_light))
                batteryStatusTextView.visibility = View.VISIBLE
                batteryStatusTextView.postDelayed({ batteryStatusTextView.visibility = View.GONE }, 10000)
            }
            eventLogger.logEvent(EventType.BATTERY_WARNING)
        }
    }

    private fun updateConnectionStatus(state: SerialPortHelper.ConnectionState) {
        runOnUiThread {
            val statusText = when (state) {
                SerialPortHelper.ConnectionState.CONNECTED          -> "USB: Connected ✓"
                SerialPortHelper.ConnectionState.CONNECTING         -> "USB: Connecting..."
                SerialPortHelper.ConnectionState.DISCONNECTED       -> "USB: Disconnected"
                SerialPortHelper.ConnectionState.NO_DEVICES         -> "USB: No devices found"
                SerialPortHelper.ConnectionState.PERMISSION_PENDING -> "USB: Permission requested"
                SerialPortHelper.ConnectionState.PERMISSION_DENIED  -> "USB: Permission denied"
                SerialPortHelper.ConnectionState.DRIVER_NOT_FOUND   -> "USB: No driver found"
                SerialPortHelper.ConnectionState.CONNECTION_FAILED  -> "USB: Connection failed"
                SerialPortHelper.ConnectionState.ERROR              -> "USB: Error"
            }
            val textColor = when (state) {
                SerialPortHelper.ConnectionState.CONNECTED -> getColor(android.R.color.holo_green_dark)
                SerialPortHelper.ConnectionState.CONNECTING,
                SerialPortHelper.ConnectionState.PERMISSION_PENDING -> getColor(android.R.color.holo_blue_dark)
                else -> getColor(android.R.color.holo_red_dark)
            }
            connectionStatusTextView.apply {
                text = statusText
                setTextColor(textColor)
                visibility = if (state == SerialPortHelper.ConnectionState.CONNECTED) View.GONE else View.VISIBLE
                bringToFront()
                alpha = 0.7f
                animate().alpha(1.0f).setDuration(300).start()
            }
        }
    }

    private fun recordDecision(decision: String) {
        val imageName = imageQueue.getOrNull(resumeStartIndex + currentTrial - 1)
            ?.let { File(it).name } ?: "unknown"
        decisionStore.setDecision(participantId, runId, imageName, decision)
        Log.i("Decision", "participant=$participantId decision=$decision image=$imageName")
    }

    protected fun hideSystemUI() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.decorView.post {
                    window.insetsController?.let {
                        it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        it.systemBarsBehavior =
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            } else {
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
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding system UI: ${e.message}", e)
        }
    }
}