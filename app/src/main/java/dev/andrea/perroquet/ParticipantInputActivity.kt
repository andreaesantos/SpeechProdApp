package dev.andrea.perroquet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import dev.andrea.perroquet.ui.theme.MyApplicationTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ParticipantInputActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ParticipantInputScreen(
                        onStartExperiment = { participantId ->
                            val date = LocalDate.now().toString()
                            val runId = generateRunId()

                            navigateToInstructions(
                                participantId = participantId,
                                date = date,
                                runId = runId
                            )
                        }
                    )
                }
            }
        }
    }

    private fun generateRunId(): String {
        // Human-readable + very unlikely to collide
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
        return LocalDateTime.now().format(fmt)
    }

    private fun navigateToInstructions(participantId: Int, date: String, runId: String) {
        val intent = Intent(this, InstructionActivity::class.java).apply {
            putExtra("PARTICIPANT_ID", participantId)
            putExtra("DATE", date)
            putExtra("RUN_ID", runId)
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParticipantInputScreen(onStartExperiment: (Int) -> Unit) {
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
            modifier = Modifier.padding(bottom = 32.dp)
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
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        )

        Button(
            onClick = {
                val participantId = participantIdText.toIntOrNull()
                val validParticipant =
                    participantId != null && ExperimentConfig.isValidParticipantId(participantId)

                participantError = !validParticipant
                if (validParticipant) onStartExperiment(participantId!!)
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Start Experiment")
        }
    }
}
