package dev.andrea.speechprod.util

import android.content.Context
import java.io.File

object RunStore {

    /**
     * Example path:
     * /Android/data/<pkg>/files/runs/p_12/2026-01-02/run_20260102_134512_123/
     */
    fun getOrCreateRunDir(
        context: Context,
        participantId: Int,
        date: String,
        runId: String
    ): File {
        val base = File(context.getExternalFilesDir(null), "runs")
        val dir = File(base, "p_$participantId/$date/run_$runId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
