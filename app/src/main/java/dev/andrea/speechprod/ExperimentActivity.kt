package dev.andrea.speechprod

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
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import android.util.Log
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import dev.andrea.speechprod.logging.EventLogger
import dev.andrea.speechprod.logging.EventType
import dev.andrea.speechprod.usbserial.SerialPortHelper
import dev.andrea.speechprod.util.SessionVideoLoader
import dev.andrea.speechprod.util.VideoProgressStore
import dev.andrea.speechprod.util.RunStore


class ExperimentActivity : BaseExperimentActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var trialTextView: TextView
    private lateinit var timeTextView: TextView
    private lateinit var startButton: Button
    private lateinit var nextButton: Button
    private lateinit var reloadButton: Button
    private lateinit var passButton: Button
    private lateinit var failButton: Button
    private lateinit var decisionStore: dev.andrea.speechprod.util.DecisionStore

    private lateinit var exitButton: Button
    private lateinit var playerView: PlayerView
    private lateinit var experimentContentTextView: TextView
    private lateinit var recordingContainer: View

    private lateinit var connectionStatusTextView: TextView
    private lateinit var batteryStatusTextView: TextView
    private lateinit var trialsLeftTextView: TextView

    private var participantId: Int = -1
    private var dateString: String = ""
    private var runId: String = ""
    private var mode: String = ParticipantInputActivity.MODE_FULL

    private var recordingBgRunnable: Runnable? = null
    private val RECORDING_BLACK_MS = 8_000L

    var config: ExperimentConfig.Standard? = null
    var videoQueue: List<String> = emptyList()
    private val videoLoader by lazy { SessionVideoLoader(this) }
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

    private var isReloadingTrial = false

    // Event logger and serial port helper
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

        decisionStore = dev.andrea.speechprod.util.DecisionStore(this)

        passButton.setOnClickListener {
            recordDecision("PASS")
            passButton.isEnabled = false
            failButton.isEnabled = false
            advanceAfterDecision()
        }

        failButton.setOnClickListener {
            recordDecision("FAIL")
            passButton.isEnabled = false
            failButton.isEnabled = false
            advanceAfterDecision()
        }

        exitButton = findViewById(R.id.exitButton)
        exitButton.setOnClickListener { showExitConfirmDialog() }

        connectionStatusTextView = findViewById(R.id.connectionStatusTextView)

        batteryStatusTextView = findViewById(R.id.batteryStatusTextView)
        batteryStatusTextView.visibility = View.GONE

        trialsLeftTextView = findViewById(R.id.trialsLeftTextView)

        hideSystemUI()

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

        val runDir = RunStore.getOrCreateRunDir(
            context = this, // or 'context' depending on what's available there
            participantId = participantId,
            runNumber = runId
        )
        val logDir = File(runDir, "logs").apply { mkdirs() }

        val allVideos = videoLoader.loadVideosInOrder()

        videoQueue = if (mode == ParticipantInputActivity.MODE_PASSED_ONLY) {
            val passed = decisionStore.getPassedVideos(participantId)
            allVideos.filter { it in passed }
        } else {
            allVideos
        }

        if (mode == ParticipantInputActivity.MODE_PASSED_ONLY && videoQueue.isEmpty()) {
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

        if (resumeStartIndex >= videoQueue.size) {
            Log.d(TAG, "Participant already completed all videos. lastCompleted=$lastCompleted")
            resumeStartIndex = 0
        }

        val remaining = videoQueue.size - resumeStartIndex
        if (remaining <= 0) {
            Toast.makeText(this, getString(R.string.aucune_video_restante), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Log.d(TAG, "Resume: lastCompleted=$lastCompleted -> startIndex=$resumeStartIndex (remaining=$remaining)")
        Log.d(TAG, "Prepared ordered video queue (${videoQueue.size}): ${videoQueue.joinToString()}")

        initializeExperiment(remaining)

        // Initialize views
        statusTextView = findViewById(R.id.statusTextView)
        trialTextView = findViewById(R.id.trialTextView)
        timeTextView = findViewById(R.id.timeTextView)
        startButton = findViewById(R.id.startButton)
        nextButton = findViewById(R.id.nextButton)
        reloadButton = findViewById(R.id.reloadButton)

        startButton.setOnClickListener { handleNextButtonClick() }
        nextButton.setOnClickListener { handleNextButtonClick() }

        reloadButton.setOnClickListener { reloadCurrentTrial() }

        playerView = findViewById(R.id.playerView)
        experimentContentTextView = findViewById(R.id.experimentContentTextView)
        recordingContainer = findViewById(R.id.recordingContainer)

        applyModeToButtons()

        playerView.player = player ?: run {
            Log.e(TAG, "Player is null when binding to PlayerView")
            return
        }
        playerView.useController = false
        playerView.controllerAutoShow = false

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
            if (!connected) {
                Log.w("ExperimentActivity", "No USB devices found for initial connection")
            }
        }

        handler.post(updateTimeRunnable)

        lifecycleScope.launch {
            experimentState.collect { state -> updateUI(state) }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateTimeRunnable)
        recordingBgRunnable?.let { handler.removeCallbacks(it) }
        recordingBgRunnable = null

        // Safety net — idempotent, no-op if already stopped normally
        ContinuousRecorder.stop()
        eventLogger.logEvent(EventType.RECORDING_END)

        try { eventLogger.saveEvents(true)} catch (_: Exception) {}

        serialPortHelper.cleanup()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        playerView.player = player
    }

    // ── Trial advancement ─────────────────────────────────────────────────────

    /**
     * Called directly by Next / Pass / Fail buttons.
     * No recording callback chain needed — ContinuousRecorder runs independently.
     */
    private fun advanceToNextTrial() {
        recordingBgRunnable?.let { handler.removeCallbacks(it) }
        recordingBgRunnable = null

        playerView.player = null

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

    private fun advanceAfterDecision() {
        if (experimentState.value != ExperimentState.SPEECH_PRODUCTION) return
        advanceToNextTrial()
    }

    // ── Button handling ───────────────────────────────────────────────────────

    private fun handleNextButtonClick() {
        when (experimentState.value) {
            ExperimentState.IDLE -> {
                eventLogger.logEvent(EventType.EXPERIMENT_START)
                eventLogger.logEvent(EventType.RECORDING_START)
                lifecycleScope.launch(Dispatchers.IO) {
                    serialPortHelper.sendEventTrigger(EventType.EXPERIMENT_START)
                }

                hideSystemUI()
                startNextTrial()
            }

            ExperimentState.EXPERIMENT_END -> {
                ContinuousRecorder.stop()
                eventLogger.logEvent(EventType.RECORDING_END)
                finishAffinity()
            }

            ExperimentState.SPEECH_PRODUCTION -> {
                // Next button pressed — advance directly, no recording to stop
                advanceToNextTrial()
            }

            else -> { /* No action needed */ }
        }
    }

    // ── Exit ──────────────────────────────────────────────────────────────────

    private fun showExitConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("Quitter l'expérience ?")
            .setMessage("L'expérience va s'arrêter maintenant. Voulez-vous quitter ? (N'oubliez pas de balayer vers le haut pour fermer l'application après avoir quitté.)")
            .setPositiveButton("Quitter") { _, _ -> exitExperimentNow() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun exitExperimentNow() {
        try {
            player?.stop()

            // Stop and save the continuous session recording
            ContinuousRecorder.stop()

            try {
                eventLogger.logEvent(EventType.EXPERIMENT_ABORTED)
                eventLogger.saveEvents(true)
            } catch (_: Exception) {}

            try { serialPortHelper.cleanup() } catch (_: Exception) {}

        } finally {
            finishAffinity()
        }
    }

    // ── State changes ─────────────────────────────────────────────────────────

    override fun onStateChanged(state: ExperimentState) {
        if (isReloadingTrial && state == ExperimentState.TRIAL_VIDEO) {
            isReloadingTrial = false
        } else {
            super.onStateChanged(state)
        }

        if (state == ExperimentState.IDLE && isBatteryLow) {
            showBatteryWarning()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val eventType = when (state) {
                    else -> null
                }
                eventType?.let {
                    if (serialPortHelper.connectionState.value == SerialPortHelper.ConnectionState.CONNECTED) {
                        val success = serialPortHelper.sendEventTrigger(eventType)
                        if (success) {
                            Log.d("ExperimentActivity", "Sent trigger for state: $state")
                            eventLogger.logEvent(eventType)
                        } else {
                            Log.w("ExperimentActivity", "Failed to send trigger for state: $state")
                            eventLogger.logError("Failed to send trigger for state: $state")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ExperimentActivity", "Error sending trigger: ${e.message}", e)
                eventLogger.logError("Error sending trigger: ${e.message}")
            }
        }

        when (state) {

            ExperimentState.TRIAL_VIDEO -> {
                eventLogger.logEvent(EventType.TRIAL_START)
                if (playerView.player == null && player != null) {
                    playerView.player = player
                }
                playerView.visibility = View.VISIBLE
                trialsLeftTextView.visibility = View.VISIBLE

                if (isAuditoryNamingFlavor()) {
                    recordingContainer.visibility = View.VISIBLE
                    recordingContainer.setBackgroundColor(Color.WHITE)
                    recordingBgRunnable?.let { handler.removeCallbacks(it) }
                    val r = Runnable { recordingContainer.setBackgroundColor(Color.BLACK) }
                    recordingBgRunnable = r
                    handler.postDelayed(r, RECORDING_BLACK_MS)
                }
            }

            ExperimentState.SPEECH_PRODUCTION -> {
                // Just handle UI.
                runOnUiThread {
                    recordingContainer.visibility = View.VISIBLE
                    if (!isAuditoryNamingFlavor()) {
                        recordingContainer.setBackgroundColor(Color.WHITE)
                    }
                    passButton.isEnabled = true
                    failButton.isEnabled = true
                }

                if (!isAuditoryNamingFlavor() && !isPictureNamingFlavor()) {
                    recordingBgRunnable?.let { handler.removeCallbacks(it) }
                    val r = Runnable { recordingContainer.setBackgroundColor(Color.BLACK) }
                    recordingBgRunnable = r
                    handler.postDelayed(r, RECORDING_BLACK_MS)
                }

                eventLogger.logEvent(EventType.TRIAL_END)
            }

            ExperimentState.EXPERIMENT_END -> {
                eventLogger.logEvent(EventType.EXPERIMENT_END)
                lifecycleScope.launch(Dispatchers.IO) {
                    serialPortHelper.sendEventTrigger(EventType.EXPERIMENT_END)
                }
                eventLogger.saveEvents()

                // Stop and save the continuous session recording
                //ContinuousRecorder.stop()

                progressStore.setLastCompletedIndex(participantId, -1)
                nextButton.isEnabled = false
                nextButton.text = getString(R.string.end)
            }

            else -> { /* No action needed */ }
        }
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
        exitButton.bringToFront()

        playerView.visibility = View.GONE
        experimentContentTextView.visibility = View.GONE
        if (!(state == ExperimentState.TRIAL_VIDEO && isAuditoryNamingFlavor())) {
            recordingContainer.visibility = View.GONE
        }

        passButton.visibility = View.GONE
        failButton.visibility = View.GONE
        reloadButton.visibility = View.GONE

        val current = currentTrial.coerceAtLeast(1)
        val total = totalTrials.coerceAtLeast(1)

        when (state) {
            ExperimentState.TRIAL_VIDEO -> {
                playerView.visibility = View.VISIBLE
                trialsLeftTextView.visibility = View.VISIBLE
                trialsLeftTextView.text = getString(R.string.trial_counter_format, current, total)
                trialsLeftTextView.bringToFront()
                exitButton.bringToFront()
                reloadButton.visibility = if (clinical) View.GONE else View.VISIBLE

                if (isAuditoryNamingFlavor()) {
                    recordingContainer.visibility = View.VISIBLE
                    recordingContainer.setBackgroundColor(Color.WHITE)
                    recordingContainer.bringToFront()
                }
            }

            ExperimentState.IDLE -> {
                experimentContentTextView.visibility = View.VISIBLE
                experimentContentTextView.text = getString(R.string.pret_a_commencer)
                reloadButton.visibility = View.GONE
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
                reloadButton.visibility = View.GONE
            }

            else -> {
                experimentContentTextView.visibility = View.VISIBLE
                experimentContentTextView.text = getString(R.string.zone_contenu_experience)
                trialsLeftTextView.visibility = View.GONE
            }
        }

        if (state != ExperimentState.SPEECH_PRODUCTION && state != ExperimentState.TRIAL_VIDEO) {
            recordingBgRunnable?.let { handler.removeCallbacks(it) }
            recordingBgRunnable = null
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

            ExperimentState.SPEECH_PRODUCTION -> {
                if (clinical) {
                    nextButton.visibility = View.VISIBLE
                    nextButton.isEnabled = true
                    nextButton.text = getString(R.string.next)
                } else {
                    nextButton.visibility = View.GONE
                }
                startButton.visibility = View.GONE
            }

            else -> {
                nextButton.visibility = View.GONE
                startButton.visibility = View.GONE
            }
        }
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    private fun reloadCurrentVideo() {
        val p = playerView.player ?: return
        try {
            p.seekTo(0)
            p.play()
            Log.d(TAG, "Reloaded current video (seekTo 0)")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    eventLogger.logVideoEvent(
                        dev.andrea.speechprod.logging.EventType.STIMULUS_ONSET,
                        null, currentTrial, currentVideoId()
                    )
                    serialPortHelper.sendEventTrigger(dev.andrea.speechprod.logging.EventType.STIMULUS_ONSET)
                } catch (e: Exception) {
                    Log.e(TAG, "Error logging reload onset: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reload current video: ${e.message}", e)
            Toast.makeText(this, getString(R.string.impossible_recharger_video), Toast.LENGTH_SHORT).show()
        }
    }

    private fun reloadCurrentTrial() {
        when (experimentState.value) {
            ExperimentState.TRIAL_VIDEO -> {
                reloadCurrentVideo()
            }
            ExperimentState.SPEECH_PRODUCTION -> {
                isReloadingTrial = true
                recordingBgRunnable?.let { handler.removeCallbacks(it) }
                recordingBgRunnable = null
                runOnUiThread {
                    recordingContainer.visibility = View.GONE
                    playerView.visibility = View.VISIBLE
                }
                transitionToState(ExperimentState.TRIAL_VIDEO)
                handler.post { reloadCurrentVideo() }
            }
            else -> { /* do nothing */ }
        }
    }

    // ── Error handling ────────────────────────────────────────────────────────

    override fun onVideoError(errorMessage: String) {
        super.onVideoError(errorMessage)
        eventLogger.logError("Video error: $errorMessage")
        Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
    }

    override fun showErrorDialog(title: String, message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                AlertDialog.Builder(this@ExperimentActivity)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.to_continue)) { _, _ ->
                        errorCount = 0
                        recoveryAttempted = true
                        eventLogger.logEvent(EventType.SYSTEM_RECOVERY)
                        if (currentTrial < totalTrials) startNextTrial()
                        else transitionToState(ExperimentState.EXPERIMENT_END)
                    }
                    .setNegativeButton(getString(R.string.terminer_experience)) { _, _ ->
                        eventLogger.logEvent(EventType.EXPERIMENT_ABORTED)
                        eventLogger.saveEvents()
                        transitionToState(ExperimentState.EXPERIMENT_END)
                    }
                    .create()
                    .show()
            } catch (e: Exception) {
                Log.e("ExperimentActivity", "Failed to show error dialog: ${e.message}", e)
                Toast.makeText(this@ExperimentActivity, "$title: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isAuditoryNamingFlavor() = BuildConfig.FLAVOR == "auditorynaming"
    private fun isPictureNamingFlavor()  = BuildConfig.FLAVOR == "picturenaming"

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
                    "WARNING: Low Battery: %d%% %s", batteryLevel, chargingSymbol
                )
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
                SerialPortHelper.ConnectionState.CONNECTED        -> "USB: Connected ✓"
                SerialPortHelper.ConnectionState.CONNECTING       -> "USB: Connecting..."
                SerialPortHelper.ConnectionState.DISCONNECTED     -> "USB: Disconnected"
                SerialPortHelper.ConnectionState.NO_DEVICES       -> "USB: No devices found"
                SerialPortHelper.ConnectionState.PERMISSION_PENDING -> "USB: Permission requested"
                SerialPortHelper.ConnectionState.PERMISSION_DENIED  -> "USB: Permission denied"
                SerialPortHelper.ConnectionState.DRIVER_NOT_FOUND   -> "USB: No driver found"
                SerialPortHelper.ConnectionState.CONNECTION_FAILED   -> "USB: Connection failed"
                SerialPortHelper.ConnectionState.ERROR              -> "USB: Error"
            }
            val textColor = when (state) {
                SerialPortHelper.ConnectionState.CONNECTED -> getColor(android.R.color.holo_green_dark)
                SerialPortHelper.ConnectionState.CONNECTING,
                SerialPortHelper.ConnectionState.PERMISSION_PENDING -> getColor(android.R.color.holo_blue_dark)
                else -> getColor(android.R.color.holo_red_dark)
            }
            val textVisibility = when (state) {
                SerialPortHelper.ConnectionState.CONNECTED -> View.GONE
                else -> View.VISIBLE
            }
            connectionStatusTextView.apply {
                text = statusText
                setTextColor(textColor)
                visibility = textVisibility
                bringToFront()
                alpha = 0.7f
                animate().alpha(1.0f).setDuration(300).start()
            }
        }
    }

    private fun currentVideoId(): String {
        player?.currentMediaItem?.mediaId?.let { if (it.isNotBlank()) return it }
        val uri = player?.currentMediaItem?.localConfiguration?.uri
        return uri?.lastPathSegment ?: "unknown_video"
    }

    private fun recordDecision(decision: String) {
        val videoName = currentVideoId()
        decisionStore.setDecision(participantId, runId, videoName, decision)
        Log.i("Decision", "participant=$participantId decision=$decision video=$videoName")
    }

    private fun hideSystemUI() {
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
            handler.postDelayed({
                connectionStatusTextView.visibility = View.VISIBLE
                connectionStatusTextView.bringToFront()
            }, 100)
        } catch (e: Exception) {
            Log.e("ExperimentActivity", "Error hiding system UI: ${e.message}", e)
        }
    }
}