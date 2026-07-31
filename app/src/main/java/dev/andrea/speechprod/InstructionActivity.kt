package dev.andrea.speechprod

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDate
import java.util.UUID
import dev.andrea.speechprod.util.FallbackParticipantIdGenerator
import android.util.Log

class InstructionActivity : AppCompatActivity() {

    private var participantId: Int = -1
    private var sessionNumber: Int = 1
    private var dateString: String = ""
    private var runId: String = ""
    private var mode: String = ParticipantInputActivity.MODE_FULL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_instruction)

        val hasParticipantIdExtra = intent.hasExtra(ParticipantInputActivity.EXTRA_PARTICIPANT_ID)
        val intentParticipantId = intent.getIntExtra(ParticipantInputActivity.EXTRA_PARTICIPANT_ID, -1)
        participantId = FallbackParticipantIdGenerator.getSafeParticipantId(intentParticipantId, hasParticipantIdExtra)
        
        if (!hasParticipantIdExtra || participantId != intentParticipantId) {
            Log.w("InstructionActivity", "Using fallback or modified participantID: $participantId (intent had: $intentParticipantId, extra present: $hasParticipantIdExtra)")
        }

        dateString = intent.getStringExtra(ParticipantInputActivity.EXTRA_DATE) ?: LocalDate.now().toString()
        runId = intent.getStringExtra(ParticipantInputActivity.EXTRA_RUN_ID) ?: UUID.randomUUID().toString()
        mode = intent.getStringExtra(ParticipantInputActivity.EXTRA_MODE) ?: ParticipantInputActivity.MODE_FULL


        val icon = findViewById<ImageView>(R.id.instructionIcon)
        val nextButton = findViewById<Button>(R.id.nextButton)

        // Tap the icon to start
        icon.setOnClickListener {
            navigateToExperiment()
        }

        nextButton.setOnClickListener {navigateToExperiment()}
    }

    private fun navigateToExperiment() {
        val i = Intent(this, ExperimentActivity::class.java).apply {
            putExtra(ParticipantInputActivity.EXTRA_PARTICIPANT_ID, participantId)
            putExtra(ParticipantInputActivity.EXTRA_DATE, dateString)
            putExtra(ParticipantInputActivity.EXTRA_RUN_ID, runId)
            putExtra(ParticipantInputActivity.EXTRA_MODE, mode)
        }
        startActivity(i)
        finish()
    }
}
