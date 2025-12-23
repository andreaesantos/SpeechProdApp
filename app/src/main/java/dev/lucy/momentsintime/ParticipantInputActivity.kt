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
    var isError by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Brain Recording Experiment",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        
        OutlinedTextField(
            value = participantIdText,
            onValueChange = { 
                participantIdText = it
                isError = false
            },
            label = { Text("Participant ID") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = isError,
            supportingText = {
                if (isError) {
                    Text("Participant ID and Session Number must be positive numbers")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )
        
        // Session selection
//        Text(
//            text = "Session",
//            style = MaterialTheme.typography.bodyLarge,
//            modifier = Modifier
//                .align(Alignment.Start)
//                .padding(start = 4.dp, bottom = 8.dp)
//        )

        OutlinedTextField(
            value = sessionNumberText,
            onValueChange = {
                // allow only digits, keep it simple
                sessionNumberText = it.filter { ch -> ch.isDigit() }
                isError = false
            },
            label = { Text("Session Number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

//        Row(
 //           modifier = Modifier
 //               .fillMaxWidth()
//                .padding(bottom = 16.dp),
//            verticalAlignment = Alignment.CenterVertically
//        ) {
//            // Session 1 radio button
//            Row(
//                verticalAlignment = Alignment.CenterVertically,
//                modifier = Modifier
//                    .weight(1f)
//                    .clickable { sessionNumber = 1 }
//            ) {
//                RadioButton(
//                    selected = sessionNumber == 1,
//                    onClick = { sessionNumber = 1 }
//                )
//                Text(
//                    text = "Session 1",
//                    style = MaterialTheme.typography.bodyMedium,
//                    modifier = Modifier.padding(start = 8.dp)
//                )
//            }
//
//            // Session 2 radio button
//            Row(
//                verticalAlignment = Alignment.CenterVertically,
//                modifier = Modifier
//                    .weight(1f)
//                    .clickable { sessionNumber = 2 }
//            ) {
//                RadioButton(
//                    selected = sessionNumber == 2,
//                    onClick = { sessionNumber = 2 }
//                )
//                Text(
//                    text = "Session 2",
//                    style = MaterialTheme.typography.bodyMedium,
//                    modifier = Modifier.padding(start = 8.dp)
//                )
//            }
//        }
//        // another row of session
//        Row(
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(bottom = 16.dp),
//            verticalAlignment = Alignment.CenterVertically
//        ){
//            // Session 3 radio button
//            Row(
//                verticalAlignment = Alignment.CenterVertically,
//                modifier = Modifier
//                    .weight(1f)
//                    .clickable { sessionNumber = 3 }
//            ) {
//                RadioButton(
//                    selected = sessionNumber == 3,
//                    onClick = { sessionNumber = 3 }
//                )
//                Text(
//                    text = "Session 3",
//                    style = MaterialTheme.typography.bodyMedium,
//                    modifier = Modifier.padding(start = 8.dp)
//                )
//            }
//
//            // Session 4 radio button
//            Row(
//                verticalAlignment = Alignment.CenterVertically,
//                modifier = Modifier
//                    .weight(1f)
//                    .clickable { sessionNumber = 4 }
//            ) {
//                RadioButton(
//                    selected = sessionNumber == 4,
//                    onClick = { sessionNumber = 4 }
//                )
//                Text(
//                    text = "Session 4",
//                    style = MaterialTheme.typography.bodyMedium,
//                    modifier = Modifier.padding(start = 8.dp)
//                )
//            }
//        }

        Button(
            onClick = {
                val participantId = participantIdText.toIntOrNull()
                val sessionNumber = sessionNumberText.toIntOrNull()

                val participantOk =
                    participantId != null && ExperimentConfig.isValidParticipantId(participantId)

                val sessionOk =
                    sessionNumber != null && sessionNumber > 0   // no max

                if (participantOk && sessionOk) {
                    onStartExperiment(participantId!!, sessionNumber!!)
                } else {
                    isError = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ){
            Text("Start Experiment")
        }
//        Button(
//            onClick = {
//                val participantId = participantIdText.toIntOrNull()
//                if (participantId != null && ExperimentConfig.isValidParticipantId(participantId)) {
//                    onStartExperiment(participantId, sessionNumber)
//                } else {
//                    isError = true
//                }
//            },
//            modifier = Modifier
//                .fillMaxWidth()
//                .height(50.dp)
//        ) {
//            Text("Start Experiment")
//        }
    }
}
