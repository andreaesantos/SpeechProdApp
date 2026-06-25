package dev.andrea.speechprod.util

import android.content.Context
import java.io.File

object RunStore {

    /**
     * Generates a flattened folder structure matching your target layout precisely:
     * /Android/data/<pkg>/files/participants/pYYMMDD/runs/run_<runNumber>/
     */
    fun getOrCreateRunDir(
        context: Context,
        participantId: Int, // FIXED: Reverted to Int to match the app's standard type
        runNumber: String   // Your timestamp/identifier string
    ): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir

        // FLATTENED: Removed /tasks/<shorthand>/ levels entirely
        val participantFolder = File(File(baseDir, "participants"), "p$participantId")
        val runsFolder = File(participantFolder, "runs")
        val runDir = File(runsFolder, "run_$runNumber")

        if (!runDir.exists()) {
            runDir.mkdirs()
        }

        return runDir
    }

    /**
     * Finds the parent directory where your decisions JSON should sit.
     * Maps to: /Android/data/<pkg>/files/participants/pYYMMDD/
     */
    fun getOrCreateDecisionsDir(context: Context, participantId: Int): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir

        // FLATTENED: Decisions sit right at the parent level, next to the "runs" folder
        val participantFolder = File(File(baseDir, "participants"), "p$participantId")

        if (!participantFolder.exists()) {
            participantFolder.mkdirs()
        }
        return participantFolder
    }
}