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
class ExperimentStateTest {

    private lateinit var testExperimentActivity: TestExperimentActivity
    
    // Test implementation of BaseExperimentActivity for testing
    class TestExperimentActivity : BaseExperimentActivity() {
        var lastState: ExperimentState? = null
        val stateChangeLatch = CountDownLatch(1)
        
        override fun onStateChanged(state: ExperimentState) {
            super.onStateChanged(state)
            lastState = state
            stateChangeLatch.countDown()
        }

        fun resetLatch() {
            // Create a new latch for the next state change
            val newLatch = CountDownLatch(1)
            javaClass.getDeclaredField("stateChangeLatch").apply {
                isAccessible = true
                set(this@TestExperimentActivity, newLatch)
            }
        }

        fun testInitializeExperiment(blocks: Int, trialsPerBlock: Int) {
            super.initializeExperiment(blocks, trialsPerBlock)
        }

        fun testStartNextBlock() {
            super.startNextBlock()
        }

        fun testStartNextTrial() {
            super.startNextTrial()
        }

        fun testGetElapsedExperimentTime(): Long {
            return super.getElapsedExperimentTime()
        }

        fun testCurrentBlock(): Int {
            return super.currentBlock
        }

        fun testCurrentTrial(): Int {
            return super.currentTrial
        }
    }
    
    @Before
    fun setup() {
        testExperimentActivity = TestExperimentActivity()
    }
    
    @Test
    fun `test initial state is IDLE`() {
        testExperimentActivity.testInitializeExperiment(3, 5)
        assertEquals(ExperimentState.IDLE, testExperimentActivity.experimentState.value)
    }
    
    @Test
    fun `test block start transition`() {
        testExperimentActivity.testInitializeExperiment(3, 5)
        testExperimentActivity.testStartNextBlock()
        
        // Wait for state change
        assertTrue(testExperimentActivity.stateChangeLatch.await(1, TimeUnit.SECONDS))
        assertEquals(ExperimentState.BLOCK_START, testExperimentActivity.lastState)
        assertEquals(1, testExperimentActivity.testCurrentBlock())
        assertEquals(0, testExperimentActivity.testCurrentTrial())
    }
    
    @Test
    fun `test trial start transition`() {
        testExperimentActivity.testInitializeExperiment(3, 5)
        testExperimentActivity.testStartNextBlock()
        
        // Wait for state change
        assertTrue(testExperimentActivity.stateChangeLatch.await(1, TimeUnit.SECONDS))
        testExperimentActivity.resetLatch()
        
        testExperimentActivity.testStartNextTrial()
        
        // Wait for state change
        assertTrue(testExperimentActivity.stateChangeLatch.await(1, TimeUnit.SECONDS))
        assertEquals(ExperimentState.TRIAL_VIDEO, testExperimentActivity.lastState)
        assertEquals(1, testExperimentActivity.testCurrentBlock())
        assertEquals(1, testExperimentActivity.testCurrentTrial())
    }
    
    @Test
    fun `test block end when trials complete`() {
        testExperimentActivity.testInitializeExperiment(1, 2)
        testExperimentActivity.testStartNextBlock()
        
        // Wait for state change
        assertTrue(testExperimentActivity.stateChangeLatch.await(1, TimeUnit.SECONDS))
        testExperimentActivity.resetLatch()
        
        // First trial
        testExperimentActivity.testStartNextTrial()
        assertTrue(testExperimentActivity.stateChangeLatch.await(1, TimeUnit.SECONDS))
        testExperimentActivity.resetLatch()
        
        // Second trial
        testExperimentActivity.testStartNextTrial()
        assertTrue(testExperimentActivity.stateChangeLatch.await(1, TimeUnit.SECONDS))
        testExperimentActivity.resetLatch()
        
        // Third trial (should trigger block end)
        testExperimentActivity.testStartNextTrial()
        assertTrue(testExperimentActivity.stateChangeLatch.await(1, TimeUnit.SECONDS))
        
        assertEquals(ExperimentState.BLOCK_END, testExperimentActivity.lastState)
    }
    
    @Test
    fun `test experiment end when blocks complete`() {
        testExperimentActivity.testInitializeExperiment(1, 1)
        testExperimentActivity.testStartNextBlock()
        
        // Wait for state change
        assertTrue(testExperimentActivity.stateChangeLatch.await(1, TimeUnit.SECONDS))
        testExperimentActivity.resetLatch()
        
        // First trial
        testExperimentActivity.testStartNextTrial()
        assertTrue(testExperimentActivity.stateChangeLatch.await(1, TimeUnit.SECONDS))
        testExperimentActivity.resetLatch()
        
        // Second trial (should trigger block end)
        testExperimentActivity.testStartNextTrial()
        assertTrue(testExperimentActivity.stateChangeLatch.await(1, TimeUnit.SECONDS))
        testExperimentActivity.resetLatch()
        
        // Start next block (should trigger experiment end)
        testExperimentActivity.testStartNextBlock()
        assertTrue(testExperimentActivity.stateChangeLatch.await(1, TimeUnit.SECONDS))
        
        assertEquals(ExperimentState.EXPERIMENT_END, testExperimentActivity.lastState)
    }
    
    @Test
    fun `test elapsed time tracking`() {
        testExperimentActivity.testInitializeExperiment(3, 5)
        
        // Sleep to simulate time passing
        Thread.sleep(100)
        
        val elapsedTime = testExperimentActivity.testGetElapsedExperimentTime()
        assertTrue("Elapsed time should be at least 100ms", elapsedTime >= 100)
    }
}
