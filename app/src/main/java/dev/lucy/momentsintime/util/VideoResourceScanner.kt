package dev.andrea.perroquet.util

import android.content.Context
import android.util.Log

/**
 * Utility class to scan for video resources in the raw directory
 */
class VideoResourceScanner(private val context: Context) {
    
    companion object {
        private const val TAG = "VideoResourceScanner"
    }
    
    /**
     * Scans the raw directory for video resources
     * @return List of video resource names (without extension)
     */
    fun scanForVideos(): List<String> {
        val videoNames = mutableListOf<String>()
        
        try {
            // Get all resource IDs in the raw folder
            val fields = context.resources.getIdentifier("", "raw", context.packageName)
            val rawClass = Class.forName("${context.packageName}.R\$raw")
            val declaredFields = rawClass.declaredFields
            
            // Filter for video files
            for (field in declaredFields) {
                val resourceName = field.name
                // Assuming videos have names like "video1", "video2", etc.
                // You can adjust this filter as needed
                if (resourceName.startsWith("vid")) {
                    videoNames.add(resourceName)
                    Log.d(TAG, "Found video resource: $resourceName")
                }
            }
            
            // Sort videos by name to ensure consistent order
            videoNames.sort()
            
            Log.d(TAG, "Found ${videoNames.size} video resources")
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning for videos: ${e.message}", e)
        }
        
        return videoNames
    }
}
