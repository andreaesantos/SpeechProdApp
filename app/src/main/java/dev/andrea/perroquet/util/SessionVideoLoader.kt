package dev.andrea.perroquet.util

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
        private const val CSV_FILE_NAME = "final_video_list" // res/raw/final_video_list.csv
    }

    fun loadVideosInOrder(): List<String> {
        return try {
            val resourceId = context.resources.getIdentifier(CSV_FILE_NAME, "raw", context.packageName)
            if (resourceId == 0) {
                Log.e(TAG, "CSV file not found: $CSV_FILE_NAME")
                return emptyList()
            }

            context.resources.openRawResource(resourceId).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val header = reader.readLine() ?: return emptyList()
                    val headers = header.split(",").map { it.trim().lowercase() }

                    val orderIdx = headers.indexOf("order")
                    val filenameIdx = headers.indexOf("filename")
                    if (orderIdx == -1 || filenameIdx == -1) {
                        Log.e(TAG, "CSV must contain headers: order, filename. Found: $headers")
                        return emptyList()
                    }

                    val rows = mutableListOf<Pair<Int, String>>()

                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue

                        val cols = line.split(",").map { it.trim() }
                        if (cols.size <= maxOf(orderIdx, filenameIdx)) continue

                        val order = cols[orderIdx].toIntOrNull() ?: continue
                        val filename = cols[filenameIdx]
                        rows.add(order to filename)
                    }

                    rows.sortedBy { it.first }.map { it.second }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading videos: ${e.message}", e)
            emptyList()
        }
    }
}