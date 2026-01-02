package dev.andrea.perroquet.util

import android.content.Context

class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("sessions_store", Context.MODE_PRIVATE)

    private fun key(participantId: Int) = "used_sessions_$participantId"

    fun hasUsedSession(participantId: Int, sessionNumber: Int): Boolean {
        val used = prefs.getStringSet(key(participantId), emptySet()) ?: emptySet()
        return used.contains(sessionNumber.toString())
    }

    fun markSessionUsed(participantId: Int, sessionNumber: Int) {
        val k = key(participantId)
        val used = prefs.getStringSet(k, emptySet())?.toMutableSet() ?: mutableSetOf()
        used.add(sessionNumber.toString())
        prefs.edit().putStringSet(k, used).apply()
    }

    fun clearParticipant(participantId: Int) {
        prefs.edit().remove(key(participantId)).apply()
    }
}