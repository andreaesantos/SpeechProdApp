package dev.andrea.perroquet.util

import android.content.Context
import java.io.File

object RunStore {

    fun getOrCreateParticipantDir(context: Context, participantId: Int): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val participantFolder = File(File(baseDir, "participants"), "p$participantId")
        if (!participantFolder.exists()) participantFolder.mkdirs()
        return participantFolder
    }

    // Keep old names as aliases so callers don't all need updating at once
    fun getOrCreateRunDir(context: Context, participantId: Int, runNumber: String): File =
        getOrCreateParticipantDir(context, participantId)

    fun getOrCreateDecisionsDir(context: Context, participantId: Int): File =
        getOrCreateParticipantDir(context, participantId)
}
