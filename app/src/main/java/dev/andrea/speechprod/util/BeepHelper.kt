package dev.andrea.speechprod

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sin

object BeepHelper {

    private const val TAG = "BeepHelper"
    private const val SAMPLE_RATE = 44100
    private const val FREQUENCY = 440.0  // Hz
    private const val BEEP_DURATION_MS = 150  // duration of each beep
    private const val BEEP_GAP_MS = 200L      // gap between beeps
    private const val NUM_BEEPS = 4

    suspend fun playAlignmentBeeps(count: Int = NUM_BEEPS) = withContext(Dispatchers.IO) {
        try {
            repeat(count) { i ->
                playBeep()
                if (i < count - 1) delay(BEEP_GAP_MS)
            }
            Log.d(TAG, "Alignment beeps completed ($count)")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing alignment beeps: ${e.message}")
        }
    }

    private fun playBeep() {
        val numSamples = SAMPLE_RATE * BEEP_DURATION_MS / 1000
        val samples = ShortArray(numSamples)

        // Generate sine wave with fade in/out to avoid clicks
        val fadeFrames = (numSamples * 0.1).toInt()  // 10% fade
        for (i in 0 until numSamples) {
            val raw = sin(2.0 * Math.PI * i * FREQUENCY / SAMPLE_RATE)
            val envelope = when {
                i < fadeFrames -> i.toDouble() / fadeFrames
                i > numSamples - fadeFrames -> (numSamples - i).toDouble() / fadeFrames
                else -> 1.0
            }
            samples[i] = (raw * envelope * Short.MAX_VALUE).toInt().toShort()
        }

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(samples, 0, samples.size)
        track.play()

        // Wait for playback to finish then release
        Thread.sleep(BEEP_DURATION_MS.toLong() + 20)
        track.stop()
        track.release()
    }
}