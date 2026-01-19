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



class ParticipantInputActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PARTICIPANT_ID = "PARTICIPANT_ID"
        const val EXTRA_DATE = "DATE"
        const val EXTRA_RUN_ID = "RUN_ID"
        const val EXTRA_MODE = "MODE"

        const val MODE_FULL = "FULL"

        const val MODE_RESTART = "RESTART"        // Restart from first video trial
        const val MODE_PASSED_ONLY = "PASSED_ONLY"
        private const val PREFS_NAME = "perroquet_prefs"
        private const val KEY_LAST_PARTICIPANT_ID = "last_participant_id"
    }

    private fun loadLastParticipantId(): Int? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return if (prefs.contains(KEY_LAST_PARTICIPANT_ID)) prefs.getInt(KEY_LAST_PARTICIPANT_ID, -1) else null
    }

    private fun saveLastParticipantId(id: Int) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit {
                putInt(KEY_LAST_PARTICIPANT_ID, id)
            }
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

                    val lastPid = remember { loadLastParticipantId() }

                    if (step == 1) {
                        ParticipantIdScreen(
                            initialParticipantId = lastPid,
                            onNext = { pid ->
                                saveLastParticipantId(pid)

                                participantId = pid
                                step = 2
                            }
                        )
                    } else {
                        val pid = participantId ?: -1

                        ModePickerScreen(
                            participantId = pid,
                            onBack = { step = 1 },
                            onPickMode = { mode ->
                                val date = LocalDate.now().toString()
                                val runId = generateRunId()
                                navigateToInstructions(
                                    participantId = pid,
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
        date: String,
        runId: String,
        mode: String
    ) {
        val intent = Intent(this, InstructionActivity::class.java).apply {
            putExtra(EXTRA_PARTICIPANT_ID, participantId)
            putExtra(EXTRA_DATE, date)
            putExtra(EXTRA_RUN_ID, runId)
            putExtra(EXTRA_MODE, mode)
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParticipantIdScreen(
    initialParticipantId: Int?,
    onNext: (Int) -> Unit
) {

    var participantIdText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(initialParticipantId) {
        if (participantIdText.isBlank()) {
            participantIdText = initialParticipantId?.takeIf { it > 0 }?.toString() ?: ""
        }
    }
    var participantError by remember { mutableStateOf(false) }

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
                participantIdText = it
                participantError = false
            },
            label = { Text("Identifiant du participant") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = participantError,
            supportingText = {
                if (participantError) Text("L’ID du participant doit être un nombre positif")
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )

        Button(
            onClick = {
                val pid = participantIdText.toIntOrNull()
                val valid = pid != null && ExperimentConfig.isValidParticipantId(pid)
                participantError = !valid
                if (valid) onNext(pid!!)
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
            text = "Participant $participantId",
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
