package dev.andrea.perroquet

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class VideoPlaybackTest {

    private lateinit var context: Context
    private lateinit var mockPlayer: ExoPlayer
    private lateinit var testActivity: TestVideoActivity
    
    class TestVideoActivity : BaseExperimentActivity() {
        var videoPlaybackStarted = false
        var videoPlaybackEnded = false
        var lastVideoName: String? = null
        val stateChangeLatch = CountDownLatch(1)

        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        override fun playVideo(videoName: String) {
            lastVideoName = videoName
            videoPlaybackStarted = true
            // Simulate video playback
            handler.postDelayed({
                videoPlaybackEnded = true
                onVideoPlaybackEnded()
            }, 100)
        }
        
        fun resetFlags() {
            videoPlaybackStarted = false
            videoPlaybackEnded = false
            lastVideoName = null
        }
        
        // Expose protected methods for testing
        fun testTransitionToState(state: ExperimentState) {
            transitionToState(state)
        }
        
        fun testInitializeExperiment(blocks: Int, trials: Int) {
            initializeExperiment(blocks, trials)
        }
        
        fun testStartNextBlock() {
            startNextBlock()
        }
        
        fun testStartNextTrial() {
            startNextTrial()
        }
        
        // Access to protected methods via reflection
        private val onVideoPlaybackEndedMethod = BaseExperimentActivity::class.java
            .getDeclaredMethod("onVideoPlaybackEnded")
            .apply { isAccessible = true }
            
        fun onVideoPlaybackEnded() {
            onVideoPlaybackEndedMethod.invoke(this)
            stateChangeLatch.countDown()
        }

        fun testOnDestroy() {
            super.onDestroy()
        }
    }
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockPlayer = mock(ExoPlayer::class.java)
        testActivity = TestVideoActivity()
    }
    
    @After
    fun tearDown() {
        testActivity.testOnDestroy()
    }
    
    @Test
    fun `test video playback starts in TRIAL_VIDEO state`() {
        // Initialize experiment
        testActivity.testInitializeExperiment(1, 1)
        testActivity.testStartNextBlock()
        testActivity.testStartNextTrial()
        
        // Verify video playback started
        assertTrue(testActivity.videoPlaybackStarted)
        assertEquals("video1", testActivity.lastVideoName)
    }
    
    @Test
    fun `test state transition after video ends`() {
        // Initialize experiment
        testActivity.testInitializeExperiment(1, 1)
        testActivity.testStartNextBlock()
        testActivity.testStartNextTrial()
        
        // Wait for video to end and state to change
        assertTrue(testActivity.stateChangeLatch.await(1, TimeUnit.SECONDS))
        
        // Verify state changed to FIXATION_DELAY
        assertEquals(ExperimentState.FIXATION_DELAY, testActivity.experimentState.value)
    }
    
    @Test
    fun `test video name generation for trials`() {
        // Initialize experiment
        testActivity.testInitializeExperiment(2, 3)
        
        // Block 1, Trial 1
        testActivity.testStartNextBlock()
        testActivity.testStartNextTrial()
        assertEquals("video1", testActivity.lastVideoName)
        
        // Reset flags
        testActivity.resetFlags()
        
        // Block 1, Trial 2
        testActivity.testStartNextTrial()
        assertEquals("video2", testActivity.lastVideoName)
        
        // Reset flags
        testActivity.resetFlags()
        
        // Block 1, Trial 3
        testActivity.testStartNextTrial()
        assertEquals("video3", testActivity.lastVideoName)
        
        // Reset flags
        testActivity.resetFlags()
        
        // Block 2, Trial 1
        testActivity.testStartNextBlock()
        testActivity.testStartNextTrial()
        assertEquals("video4", testActivity.lastVideoName)
    }
}
