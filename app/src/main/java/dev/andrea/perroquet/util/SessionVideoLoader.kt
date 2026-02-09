package dev.andrea.perroquet.util

import android.content.Context
import android.util.Log
import dev.andrea.perroquet.R
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Utility class to load stimuli files for a specific session from a CSV file
 */
class SessionStimuliLoader(private val context: Context) {

    companion object {
        private const val TAG = "SessionStimuliLoader"
        private const val CSV_FILE_NAME = "trials"
    }

    fun loadStimuliInOrder(): List<String> {
        // 1) Resolve CSV resource id safely
        val resourceId = runCatching { R.raw.trials }.getOrNull()
        if (resourceId == null || resourceId == 0) {
            Log.e(TAG, "CSV raw resource not found: R.raw.trials")
            return emptyList()
        }

        // 2) Parse CSV
        val stimuli = try {
            context.resources.openRawResource(resourceId).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->

                    val headerLine = reader.readLine()
                    if (headerLine.isNullOrBlank()) {
                        Log.e(TAG, "CSV is empty or missing header row.")
                        return emptyList()
                    }

                    val headers = headerLine
                        .removePrefix("\uFEFF")   // strip UTF-8 BOM if present
                        .split(",")
                        .map { it.trim().lowercase() }
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
                        if (filename.isBlank()) continue

                        rows.add(order to filename)
                    }

                    rows.sortedBy { it.first }.map { it.second }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading stimuli from CSV: ${e.message}", e)
            emptyList()
        }

        // 3) Handle “no videos found”
        if (stimuli.isEmpty()) {
            Log.w(TAG, "No stimuli found in CSV (or none parsed successfully).")
        } else {
            Log.i(TAG, "Loaded ${stimuli.size} stimuli from CSV. First: ${stimuli.first()}")
        }

        return stimuli
    }
}