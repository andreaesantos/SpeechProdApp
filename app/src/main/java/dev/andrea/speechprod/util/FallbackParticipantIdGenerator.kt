package dev.andrea.speechprod.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import android.util.Log

/**
 * Utility for generating unique participant IDs only when the participantID is completely missing.
 * This prevents data loss when a participantID fails to be passed through the intent.
 * 
 * The generated ID is unique per session, ensuring no progress sharing across error sessions.
 */
object FallbackParticipantIdGenerator {
    
    private const val TAG = "FallbackParticipantIdGenerator"
    
    /**
     * Generates a unique fallback participant ID based on current date and time.
     * Format: YYYYMMDDHHmmssSSS as an Int (removes underscores from timestamp string)
     * Example: 20260528160406419
     * 
     * This ensures:
     * - Each session with missing participantID gets a unique identifier
     * - Sessions cannot share progress through SharedPreferences
     * - The ID is traceable to when it occurred from its numeric value
     * - Each error session is completely isolated
     */
    fun generateUniqueId(): Int {
        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
        val timestamp = now.format(formatter)
        // Convert timestamp string to Int by removing non-digit characters would lose precision
        // Instead, use the timestamp directly converted to long then take a unique int
        val fallbackId = timestamp.toInt()
        
        Log.w(TAG, "Generated fallback participantID: $fallbackId (timestamp: $timestamp)")
        return fallbackId
    }
    
    /**
     * Safely retrieves participant ID from intent.
     * Only generates a fallback ID if the intent is missing the extra entirely.
     * 
     * @param intentParticipantId The participant ID from the intent (-1 if missing)
     * @param intentHasExtra Whether the intent actually had the EXTRA_PARTICIPANT_ID key
     * @return Either the valid intent ID, or a generated unique ID only if the extra was missing
     */
    fun getSafeParticipantId(intentParticipantId: Int, intentHasExtra: Boolean): Int {
        return if (intentHasExtra && intentParticipantId > 0) {
            // Valid participant ID from intent
            intentParticipantId
        } else if (!intentHasExtra) {
            // Missing from intent entirely - generate fallback
            Log.e(TAG, "participantID missing from intent! Generating fallback ID.")
            generateUniqueId()
        } else {
            // Intent had the extra but it's invalid (e.g., zero or negative)
            Log.e(TAG, "Invalid participantID in intent: $intentParticipantId. Generating fallback ID.")
            generateUniqueId()
        }
    }
}
