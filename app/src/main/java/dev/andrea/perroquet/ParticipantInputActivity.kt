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


class ParticipantInputActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PARTICIPANT_ID = "PARTICIPANT_ID"
        const val EXTRA_DATE = "DATE"
        const val EXTRA_RUN_ID = "RUN_ID"
        const val EXTRA_MODE = "MODE"

        const val MODE_FULL = "FULL"
        const val MODE_PASSED_ONLY = "PASSED_ONLY"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO: Replace this with your real "passed videos" store.
        // For now, this returns 0 so the Passed-only button will be disabled.
        fun getPassedCount(participantId: Int): Int {
            // e.g., DecisionStore(...).getPassedCount(participantId)
            return 0
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var step by remember { mutableStateOf(1) }
                    var participantId by remember { mutableStateOf<Int?>(null) }

                    if (step == 1) {
                        ParticipantIdScreen(
                            onNext = { pid ->
                                participantId = pid
                                step = 2
                            }
                        )
                    } else {
                        val pid = participantId ?: -1
                        val passedCount = remember(pid) { getPassedCount(pid) }

                        ModePickerScreen(
                            participantId = pid,
                            passedCount = passedCount,
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
    onNext: (Int) -> Unit
) {
    var participantIdText by remember { mutableStateOf("") }
    var participantError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Répétition de Mots",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = participantIdText,
            onValueChange = {
                participantIdText = it
                participantError = false
            },
            label = { Text("Participant ID") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = participantError,
            supportingText = {
                if (participantError) Text("Participant ID must be a positive number")
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
    passedCount: Int,
    onBack: () -> Unit,
    onPickMode: (String) -> Unit
) {

    val context = LocalContext.current
    val passedCount = DecisionStore(context).getPassedCount(participantId)
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

        // Full experiment
        Button(
            onClick = { onPickMode(ParticipantInputActivity.MODE_FULL) },
            modifier = Modifier.fillMaxWidth().height(70.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Continue full experiment")
                Text(
                    text = "Resume from where you left off",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Passed-only
        Button(
            onClick = { onPickMode(ParticipantInputActivity.MODE_PASSED_ONLY) },
            enabled = passedEnabled,
            modifier = Modifier.fillMaxWidth().height(70.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Run passed-only")
                Text(
                    text = if (passedEnabled) {
                        "Only videos you marked PASS ($passedCount)"
                    } else {
                        "No passed videos yet"
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
            Text("Back")
        }
    }
}
