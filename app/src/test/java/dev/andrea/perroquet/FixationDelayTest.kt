package dev.andrea.perroquet

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class FixationDelayTest {

    private lateinit var testExperimentActivity: TestFixationActivity
    
    // Test implementation of BaseExperimentActivity for testing fixation delay
    class TestFixationActivity : BaseExperimentActivity() {
        var lastState: ExperimentState? = null
        val stateChangeLatch = CountDownLatch(1)
        var fixationStartTime = 0L
        var fixationEndTime = 0L
        
        override fun onStateChanged(state: ExperimentState) {
            super.onStateChanged(state)
            lastState = state
            
            if (state == ExperimentState.FIXATION_DELAY) {
                fixationStartTime = SystemClock.elapsedRealtime()
            } else if (lastState == ExperimentState.FIXATION_DELAY) {
                fixationEndTime = SystemClock.elapsedRealtime()
            }
            
            stateChangeLatch.countDown()
        }
        
        fun resetLatch() {
            val newLatch = CountDownLatch(1)
            javaClass.getDeclaredField("stateChangeLatch").apply {
                isAccessible = true
                set(this@TestFixationActivity, newLatch)
            }
        }
        
        fun testTransitionToState(state: ExperimentState) {
            transitionToState(state)
        }
        
        fun getFixationDuration(): Long {
            return fixationEndTime - fixationStartTime
        }
    }
    
    @Before
    fun setup() {
        testExperimentActivity = TestFixationActivity()
    }
    
    @Test
    fun `test fixation delay duration`() {
        // Transition to fixation delay state
        testExperimentActivity.testTransitionToState(ExperimentState.FIXATION_DELAY)
        
        // Simulate the ExperimentActivity behavior by transitioning to SPEECH_RECORDING after 1000ms
        Thread.sleep(1000)
        testExperimentActivity.testTransitionToState(ExperimentState.SPEECH_PRODUCTION)
        
        // Verify the duration is approximately 1000ms (with some tolerance for test execution)
        val duration = testExperimentActivity.getFixationDuration()
        assertTrue("Fixation duration should be approximately 1000ms", 
            duration >= 950 && duration <= 1050)
    }
    
    @Test
    fun `test state transition sequence`() {
        // Test the sequence: TRIAL_VIDEO -> FIXATION_DELAY -> SPEECH_RECORDING
        testExperimentActivity.testTransitionToState(ExperimentState.TRIAL_VIDEO)
        assertEquals(ExperimentState.TRIAL_VIDEO, testExperimentActivity.lastState)
        
        testExperimentActivity.resetLatch()
        testExperimentActivity.testTransitionToState(ExperimentState.FIXATION_DELAY)
        assertEquals(ExperimentState.FIXATION_DELAY, testExperimentActivity.lastState)
        
        testExperimentActivity.resetLatch()
        testExperimentActivity.testTransitionToState(ExperimentState.SPEECH_PRODUCTION)
        assertEquals(ExperimentState.SPEECH_PRODUCTION, testExperimentActivity.lastState)
    }
}
