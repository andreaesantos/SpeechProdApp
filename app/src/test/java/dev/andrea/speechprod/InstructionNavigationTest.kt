package dev.andrea.speechprod

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstructionNavigationTest {

    @Test
    fun clickingNextButton_finishesInstructionActivity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, InstructionActivity::class.java).apply {
            putExtra(ParticipantInputActivity.EXTRA_PARTICIPANT_ID, 123)
            putExtra(ParticipantInputActivity.EXTRA_DATE, "2026-01-12")
            putExtra(ParticipantInputActivity.EXTRA_RUN_ID, "test-run")
            putExtra(ParticipantInputActivity.EXTRA_MODE, ParticipantInputActivity.MODE_FULL)
        }

        ActivityScenario.launch<InstructionActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // Click next
                activity.findViewById<android.widget.Button>(R.id.nextButton).performClick()

                // InstructionActivity should be finishing after navigation
                assert(activity.isFinishing)
            }
        }
    }
}
