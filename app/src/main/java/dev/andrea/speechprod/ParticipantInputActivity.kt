package dev.andrea.speechprod

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.andrea.speechprod.ui.theme.MyApplicationTheme
import dev.andrea.speechprod.util.DecisionStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import dev.andrea.speechprod.util.VideoProgressStore
import androidx.core.content.edit
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class ParticipantInputActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PARTICIPANT_ID = "PARTICIPANT_ID"
        const val EXTRA_PARTICIPANT_NAME = "PARTICIPANT_NAME"
        const val EXTRA_DATE = "DATE"
        const val EXTRA_RUN_ID = "RUN_ID"
        const val EXTRA_MODE = "MODE"

        const val MODE_FULL = "FULL"

        const val MODE_RESTART = "RESTART"        // Restart from first video trial
        const val MODE_PASSED_ONLY = "PASSED_ONLY"
        private const val PREFS_NAME = "speechprod_prefs"
        private const val KEY_LAST_PARTICIPANT_ID = "last_participant_id"
        private const val PREFIX_NAME_MAPPING = "name_mapping_"
        private const val PREFIX_ID_MAPPING = "id_mapping_"
    }

    private fun loadLastParticipantId(): Int? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return if (prefs.contains(KEY_LAST_PARTICIPANT_ID)) prefs.getInt(KEY_LAST_PARTICIPANT_ID, -1) else null
    }

    private fun loadParticipantName(id: Int): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(PREFIX_NAME_MAPPING + id, "") ?: ""
    }

    private fun loadParticipantIdByName(name: String): Int? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val id = prefs.getInt(PREFIX_ID_MAPPING + name.lowercase().trim(), -1)
        return if (id != -1) id else null
    }

    private fun saveParticipantInfo(id: Int, name: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val existingName = prefs.getString(PREFIX_NAME_MAPPING + id, "") ?: ""
        val normalizedName = name.lowercase().trim()

        // If the name changed for this ID, reset decisions and progress
        if (existingName.isNotBlank() && name.isNotBlank() && existingName != name) {
            DecisionStore(this).clearDecisions(id)
            VideoProgressStore(this).setLastCompletedIndex(id, -1)
        }

        prefs.edit {
            putInt(KEY_LAST_PARTICIPANT_ID, id)
            if (name.isNotBlank()) {
                putString(PREFIX_NAME_MAPPING + id, name)
                putInt(PREFIX_ID_MAPPING + normalizedName, id)
            }
        }
    }

    private var pendingNavigationArgs: NavigationArgs? = null
    private data class NavigationArgs(
        val participantId: Int,
        val participantName: String,
        val date: String,
        val runId: String,
        val mode: String
    )

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val args = pendingNavigationArgs ?: return@registerForActivityResult
        pendingNavigationArgs = null
        startContinuousRecordingAndNavigate(args)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var step by remember { mutableStateOf(1) }
                    var participantId by remember { mutableStateOf<Int?>(null) }
                    var participantName by remember { mutableStateOf("") }

                    val lastPid = remember { loadLastParticipantId() }

                    if (step == 1) {
                        ParticipantIdScreen(
                            initialParticipantId = lastPid,
                            onLoadName = { pid -> loadParticipantName(pid) },
                            onLoadIdByName = { name -> loadParticipantIdByName(name) },
                            onNext = { pid, name ->
                                saveParticipantInfo(pid, name)
                                participantId = pid
                                participantName = name
                                step = 2
                            }
                        )
                    } else {
                        val pid = participantId ?: -1
                        val name = participantName

                        ModePickerScreen(
                            participantId = pid,
                            participantName = name,
                            onBack = { step = 1 },
                            onPickMode = { mode ->
                                val date = LocalDate.now().toString()
                                val runId = generateRunId()
                                navigateToInstructions(
                                    participantId = pid,
                                    participantName = name,
                                    date = date,
                                    runId = runId,
                                    mode = mode
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    private fun generateRunId(): String {
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
        return LocalDateTime.now().format(fmt)
    }

    private fun navigateToInstructions(
        participantId: Int,
        participantName: String,
        date: String,
        runId: String,
        mode: String
    ) {
        val args = NavigationArgs(participantId, participantName, date, runId, mode)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startContinuousRecordingAndNavigate(args)
        } else {
            pendingNavigationArgs = args
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startContinuousRecordingAndNavigate(args: NavigationArgs) {
        ContinuousRecorder.start(
            context       = this,
            participantId = args.participantId,
            date          = args.date,
            runId         = args.runId
        )

        val intent = Intent(this, InstructionActivity::class.java).apply {
            putExtra(EXTRA_PARTICIPANT_ID, args.participantId)
            putExtra(EXTRA_PARTICIPANT_NAME, args.participantName)
            putExtra(EXTRA_DATE, args.date)
            putExtra(EXTRA_RUN_ID, args.runId)
            putExtra(EXTRA_MODE, args.mode)
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParticipantIdScreen(
    initialParticipantId: Int?,
    onLoadName: (Int) -> String,
    onLoadIdByName: (String) -> Int?,
    onNext: (Int, String) -> Unit
) {
    var participantIdText by rememberSaveable { mutableStateOf("") }
    var participantNameText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(initialParticipantId) {
        if (participantIdText.isBlank()) {
            val pid = initialParticipantId?.takeIf { it > 0 }
            if (pid != null) {
                participantIdText = pid.toString()
                participantNameText = onLoadName(pid)
            }
        }
    }
    
    // Auto-load name when ID changes
    LaunchedEffect(participantIdText) {
        val pid = participantIdText.toIntOrNull()
        if (pid != null && ExperimentConfig.isValidParticipantId(pid)) {
            val name = onLoadName(pid)
            if (name.isNotBlank()) {
                participantNameText = name
            }
        }
    }

    // Auto-load ID when name changes
    LaunchedEffect(participantNameText) {
        if (participantNameText.isNotBlank()) {
            val pid = onLoadIdByName(participantNameText)
            if (pid != null) {
                participantIdText = pid.toString()
            }
        }
    }

    var nameError by remember { mutableStateOf<String?>(null) }
    var idError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.task_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = participantNameText,
            onValueChange = { 
                participantNameText = it
                nameError = null
                idError = null
            },
            label = { Text("Nom du participant") },
            isError = nameError != null,
            supportingText = { nameError?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = participantIdText,
            onValueChange = {
                participantIdText = it
                nameError = null
                idError = null
            },
            label = { Text("Identifiant du participant (Numéro)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = idError != null,
            supportingText = { idError?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )

        Button(
            onClick = {
                val pid = participantIdText.toIntOrNull()
                val normalizedName = participantNameText.lowercase().trim()
                
                // 1. Validation basics
                if (participantNameText.isBlank()) {
                    nameError = "Le nom ne peut pas être vide"
                    return@Button
                }
                if (pid == null || !ExperimentConfig.isValidParticipantId(pid)) {
                    idError = "L’ID doit être un nombre positif"
                    return@Button
                }

                // 2. Strict Linkage Check: Is this name already linked to a DIFFERENT ID?
                val existingIdForName = onLoadIdByName(participantNameText)
                if (existingIdForName != null && existingIdForName != pid) {
                    nameError = "Ce nom est déjà lié à l'ID $existingIdForName"
                    return@Button
                }

                // 3. Strict Linkage Check: Is this ID already linked to a DIFFERENT Name?
                val existingNameForId = onLoadName(pid)
                if (existingNameForId.isNotBlank() && existingNameForId.lowercase().trim() != normalizedName) {
                    idError = "Cet ID est déjà lié à $existingNameForId"
                    return@Button
                }

                onNext(pid, participantNameText)
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Next")
        }
    }
}

@Composable
private fun ModePickerScreen(
    participantId: Int,
    participantName: String,
    onBack: () -> Unit,
    onPickMode: (String) -> Unit
) {
    val context = LocalContext.current

    val hasProgress = remember(participantId) {
        VideoProgressStore(context).hasAnyProgress(participantId)
    }

    val passedCount = remember(participantId) {
        DecisionStore(context).getPassedCount(participantId)
    }
    val passedEnabled = passedCount > 0

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (participantName.isNotBlank()) "$participantName ($participantId)" else "Participant $participantId",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // 1) CONTINUER
        Button(
            onClick = { onPickMode(ParticipantInputActivity.MODE_FULL) },
            modifier = Modifier.fillMaxWidth().height(70.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Continuer l’expérience")
                Text(
                    text = "Reprendre là où vous vous êtes arrêté(e)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 2) RECOMMENCER
        Button(
            onClick = { onPickMode(ParticipantInputActivity.MODE_RESTART) },
            enabled = hasProgress,
            modifier = Modifier.fillMaxWidth().height(70.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Recommencer du début")
                Text(
                    text = "Disponible après avoir commencé une session",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 3) STIMULATIONS CLINIQUES
        Button(
            onClick = { onPickMode(ParticipantInputActivity.MODE_PASSED_ONLY) },
            enabled = passedEnabled,
            modifier = Modifier.fillMaxWidth().height(70.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Stimulations corticales")
                Text(
                    text = if (passedEnabled) {
                        "Uniquement les vidéos validées ($passedCount)"
                    } else {
                        "Aucune vidéo validée pour le moment"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Retour")
        }
    }
}
