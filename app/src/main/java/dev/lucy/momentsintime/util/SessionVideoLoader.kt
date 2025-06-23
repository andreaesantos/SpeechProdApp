package dev.lucy.momentsintime.util

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Utility class to load video files for a specific session from a CSV file
 */
class SessionVideoLoader(private val context: Context) {
    
    companion object {
        private const val TAG = "SessionVideoLoader"
        private const val CSV_FILE_NAME = "found_files_with_session"
    }
    
    /**
     * Load video files for a specific session from the CSV file
     * @param sessionNumber The session number (1 or 2)
     * @return List of video file names for the specified session
     */
    fun loadVideosForSession(sessionNumber: Int, trialsPerBlock: Int): List<String> {
        val videoFiles = mutableListOf<String>()
        
        try {
            // Open the CSV file from raw resources
            val resourceId = context.resources.getIdentifier(
                CSV_FILE_NAME, "raw", context.packageName
            )
            
            if (resourceId == 0) {
                Log.e(TAG, "CSV file not found: $CSV_FILE_NAME")
                return emptyList()
            }
            
            // Read the CSV file
            val inputStream = context.resources.openRawResource(resourceId)
            val reader = BufferedReader(InputStreamReader(inputStream))
            
            // Skip header line if present
            var line = reader.readLine()
            
            // Read header line to find session column index
            val headerLine = line
            val headers = headerLine.split(",").map { it.trim().lowercase() }
            val fileNameColumnIndex = 0  // Assuming filename is always the first column
            
            // Read each line and filter by session number
            while (reader.readLine()?.also { line = it } != null) {
                val columns = line.split(",")
                val fileSession = columns[columns.size -1 ].trim().toIntOrNull()
                    
                if (fileSession == sessionNumber) {
                    // Add the video file name to the list
                    val fileName = columns[fileNameColumnIndex].trim()
                    videoFiles.add(fileName)
                }

            }
            
            reader.close()

            // Shuffle within blocks of 10
            for (i in videoFiles.indices step trialsPerBlock) {
                val end = minOf(i + trialsPerBlock, videoFiles.size)
                val sublist = videoFiles.subList(i, end)
                sublist.shuffle()
            }
            Log.d(TAG, "Loaded ${videoFiles.size} videos for session $sessionNumber")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading videos for session $sessionNumber: ${e.message}", e)
        }
        
        return videoFiles
    }
}
