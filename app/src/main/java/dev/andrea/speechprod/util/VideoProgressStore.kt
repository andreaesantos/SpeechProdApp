package dev.andrea.speechprod.util

import android.content.Context

class VideoProgressStore(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "video_progress"
        private fun key(participantId: Int) = "last_completed_index_$participantId"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastCompletedIndex(participantId: Int): Int =
        prefs.getInt(key(participantId), -1)

    fun setLastCompletedIndex(participantId: Int, index: Int) {
        prefs.edit().putInt(key(participantId), index).apply()
    }

    fun reset(participantId: Int) {
        prefs.edit().remove(key(participantId)).apply()
    }

    fun hasAnyProgress(participantId: Int): Boolean {
        return getLastCompletedIndex(participantId) >= 0
    }

}