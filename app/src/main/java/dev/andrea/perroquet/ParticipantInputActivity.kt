package dev.andrea.perroquet

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.andrea.perroquet.ui.theme.MyApplicationTheme
import java.time.LocalDate

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
                        onStartExperiment = { participantId, sessionNumber ->
                            navigateToInstructions(participantId, sessionNumber)
                        }
                    )
                }
            }
        }
    }

    private fun navigateToInstructions(participantId: Int, sessionNumber: Int) {
        val intent = Intent(this, InstructionActivity::class.java).apply {
            putExtra("PARTICIPANT_ID", participantId)
            putExtra("DATE", LocalDate.now().toString())
            putExtra("SESSION_NUMBER", sessionNumber)
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParticipantInputScreen(onStartExperiment: (Int, Int) -> Unit) {
    var participantIdText by remember { mutableStateOf("") }
    var sessionNumberText by remember { mutableStateOf("1") }

    var participantError by remember { mutableStateOf(false) }
    var sessionError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = sessionNumberText,
            onValueChange = {
                // keep digits only (optional, but nice to avoid junk input)
                sessionNumberText = it.filter { ch -> ch.isDigit() }
                sessionError = false
            },
            label = { Text("Session number") },
            placeholder = { Text("e.g., 1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = sessionError,
            supportingText = {
                if (sessionError) Text("Session must be a positive number")
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        )

        Button(
            onClick = {
                val participantId = participantIdText.toIntOrNull()
                val sessionNumber = sessionNumberText.toIntOrNull()

                val validParticipant =
                    participantId != null && ExperimentConfig.isValidParticipantId(participantId)
                val validSession =
                    sessionNumber != null && sessionNumber > 0

                participantError = !validParticipant
                sessionError = !validSession

                if (validParticipant && validSession) {
                    onStartExperiment(participantId!!, sessionNumber!!)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("Start Experiment")
        }
    }
}
