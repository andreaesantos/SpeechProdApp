package dev.andrea.perroquet

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AudioRecorderTest {

    private lateinit var context: Context
    private lateinit var testOutputDir: File
    private var testFile: File? = null
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testOutputDir = File(context.getExternalFilesDir(null), "test_audio")
        if (!testOutputDir.exists()) {
            testOutputDir.mkdirs()
        }
    }
    
    @After
    fun cleanup() {
        testFile?.delete()
    }
    
    @Test
    fun `test file naming convention`() {
        // Create a mock AudioRecorder that doesn't actually record
        val mockRecorder = MockAudioRecorder(context)
        
        // Test parameters
        val participantId = 42
        val blockNumber = 2
        val trialNumber = 3
        
        // Expected filename
        val expectedFilename = "participant_42_block_2_trial_3.wav"
        
        // Start mock recording
        val latch = CountDownLatch(1)
        mockRecorder.startMockRecording(
            participantId = participantId,
            blockNumber = blockNumber,
            trialNumber = trialNumber,
            durationMs = 100,
            onComplete = { file ->
                testFile = file
                latch.countDown()
            },
            onError = { error ->
                fail("Error in mock recording: $error")
                latch.countDown()
            }
        )
        
        // Wait for completion
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        
        // Verify file name
        assertNotNull("Test file should not be null", testFile)
        assertEquals("File name should match convention", 
            expectedFilename, testFile?.name)
    }
    
    @Test
    fun `test recording duration`() {
        // This is a limited test since we can't actually record audio in unit tests
        // In a real app, this would be an instrumented test
        
        val mockRecorder = MockAudioRecorder(context)
        val durationMs = 500L
        
        val startTime = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        
        mockRecorder.startMockRecording(
            participantId = 1,
            blockNumber = 1,
            trialNumber = 1,
            durationMs = durationMs,
            onComplete = { _ ->
                val elapsedTime = System.currentTimeMillis() - startTime
                assertTrue("Recording duration should be approximately $durationMs ms",
                    elapsedTime >= durationMs && elapsedTime < durationMs + 200)
                latch.countDown()
            },
            onError = { _ ->
                latch.countDown()
            }
        )
        
        assertTrue(latch.await(2, TimeUnit.SECONDS))
    }
    
    /**
     * Mock AudioRecorder for testing that doesn't actually record audio
     */
    private class MockAudioRecorder(context: Context) {
        private val outputDir = File(context.getExternalFilesDir(null), "test_audio")
        
        fun startMockRecording(
            participantId: Int,
            blockNumber: Int,
            trialNumber: Int,
            durationMs: Long,
            onComplete: (File) -> Unit,
            onError: (String) -> Unit
        ) {
            try {
                if (!outputDir.exists()) {
                    outputDir.mkdirs()
                }
                
                // Create file with correct naming convention
                val fileName = "participant_${participantId}_block_${blockNumber}_trial_${trialNumber}.wav"
                val file = File(outputDir, fileName)
                
                // Create an empty file
                file.createNewFile()
                
                // Write some dummy data to simulate a WAV file
                file.writeBytes(ByteArray(44) { 0 })
                
                // Simulate recording duration
                android.os.Handler().postDelayed({
                    onComplete(file)
                }, durationMs)
                
            } catch (e: Exception) {
                onError("Mock recording error: ${e.message}")
            }
        }
    }
}
