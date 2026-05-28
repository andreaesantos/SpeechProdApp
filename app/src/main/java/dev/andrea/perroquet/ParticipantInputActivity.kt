package dev.andrea.perroquet

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.andrea.perroquet.ui.theme.MyApplicationTheme
import dev.andrea.perroquet.util.DecisionStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import dev.andrea.perroquet.util.VideoProgressStore
import androidx.core.content.edit
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ParticipantInputActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PARTICIPANT_ID = "PARTICIPANT_ID"
        const val EXTRA_PARTICIPANT_NAME = "PARTICIPANT_NAME"
        const val EXTRA_DATE = "DATE"
        const val EXTRA_RUN_ID = "RUN_ID"
        const val EXTRA_MODE = "MODE"

        const val MODE_FULL = "FULL"
        const val MODE_RESTART = "RESTART"
        const val MODE_PASSED_ONLY = "PASSED_ONLY"
        private const val PREFS_NAME = "perroquet_prefs"
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

    private fun getAllRegisteredNames(): List<String> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.all.keys
            .filter { it.startsWith(PREFIX_ID_MAPPING) }
            .map { it.removePrefix(PREFIX_ID_MAPPING) }
            .sorted()
    }

    private fun saveParticipantInfo(id: Int, name: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val normalizedName = name.lowercase().trim()
        val existingName = prefs.getString(PREFIX_NAME_MAPPING + id, "") ?: ""

        if (existingName.isNotBlank() && normalizedName.isNotBlank() && existingName != normalizedName) {
            DecisionStore(this).clearDecisions(id)
            VideoProgressStore(this).setLastCompletedIndex(id, -1)
        }

        prefs.edit {
            putInt(KEY_LAST_PARTICIPANT_ID, id)
            if (normalizedName.isNotBlank()) {
                putString(PREFIX_NAME_MAPPING + id, normalizedName)
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
    ) { _ ->
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
                            getRegisteredNames = { getAllRegisteredNames() },
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

        lifecycleScope.launch {
            BeepHelper.playAlignmentBeeps()
        }

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
    getRegisteredNames: () -> List<String>,
    onNext: (Int, String) -> Unit
) {
    var participantIdText by rememberSaveable { mutableStateOf("") }
    var participantNameText by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val registeredNames = remember { getRegisteredNames() }
    val isIdLocked = remember(participantNameText) { onLoadIdByName(participantNameText) != null }

    LaunchedEffect(initialParticipantId) {
        if (participantIdText.isBlank()) {
            val pid = initialParticipantId?.takeIf { it > 0 }
            if (pid != null) {
                participantIdText = pid.toString()
                participantNameText = onLoadName(pid)
            }
        }
    }

    LaunchedEffect(participantIdText) {
        val pid = participantIdText.toIntOrNull()
        if (pid != null && ExperimentConfig.isValidParticipantId(pid)) {
            val name = onLoadName(pid)
            if (name.isNotBlank()) participantNameText = name
        }
    }

    LaunchedEffect(participantNameText) {
        if (participantNameText.isNotBlank()) {
            val pid = onLoadIdByName(participantNameText)
            if (pid != null) participantIdText = pid.toString()
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
            value = participantIdText,
            onValueChange = {
                if (!isIdLocked) {
                    participantIdText = it
                    idError = null
                }
            },
            label = { Text("Identifiant du participant (Numéro)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = idError != null,
            supportingText = {
                if (isIdLocked) Text("ID verrouillé pour ce participant")
                else idError?.let { Text(it) }
            },
            enabled = !isIdLocked,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            OutlinedTextField(
                value = participantNameText,
                onValueChange = {
                    participantNameText = it.lowercase()
                    nameError = null
                    idError = null
                },
                label = { Text("Nom du participant (minuscules)") },
                isError = nameError != null,
                supportingText = { nameError?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = {
                    IconButton(onClick = { expanded = !expanded }) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    }
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )

            val filteredOptions = if (participantNameText.isBlank()) {
                registeredNames
            } else {
                registeredNames.filter { it.contains(participantNameText, ignoreCase = true) }
            }
            if (filteredOptions.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    filteredOptions.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { Text(text = selectionOption) },
                            onClick = {
                                participantNameText = selectionOption
                                expanded = false
                                nameError = null
                                idError = null
                            }
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                val pid = participantIdText.toIntOrNull()
                val normalizedName = participantNameText.lowercase().trim()

                if (participantNameText.isBlank()) {
                    nameError = "Le nom ne peut pas être vide"
                    return@Button
                }
                if (pid == null || !ExperimentConfig.isValidParticipantId(pid)) {
                    idError = "L'ID doit être un nombre positif"
                    return@Button
                }

                val existingIdForName = onLoadIdByName(participantNameText)
                if (existingIdForName != null && existingIdForName != pid) {
                    nameError = "Ce nom est déjà lié à l'ID $existingIdForName"
                    return@Button
                }

                val existingNameForId = onLoadName(pid)
                if (existingNameForId.isNotBlank() &&
                    existingNameForId.lowercase().trim() != normalizedName) {
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
            text = if (participantName.isNotBlank()) "$participantName ($participantId)"
            else "Participant $participantId",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = { onPickMode(ParticipantInputActivity.MODE_FULL) },
            modifier = Modifier.fillMaxWidth().height(70.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Premier Essai / Continuer l'expérience")
                Text(
                    text = "Reprendre là où vous vous êtes arrêté(e)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(12.dp))

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

        Button(
            onClick = { onPickMode(ParticipantInputActivity.MODE_PASSED_ONLY) },
            enabled = passedEnabled,
            modifier = Modifier.fillMaxWidth().height(70.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Stimulations corticales")
                Text(
                    text = if (passedEnabled) "Uniquement les vidéos validées ($passedCount)"
                    else "Aucune vidéo validée pour le moment",
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