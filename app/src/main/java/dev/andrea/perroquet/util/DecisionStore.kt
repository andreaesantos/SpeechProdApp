package dev.andrea.perroquet.util

import android.content.Context
import org.json.JSONObject
import java.io.File

class DecisionStore(
    private val context: Context,
    private val datasetKey: String = "perroquet_video_list" // matches the raw csv name
) {
    private fun participantFile(participantId: Int): File {
        val dir = File(context.getExternalFilesDir(null), "decisions").apply { mkdirs() }
        return File(dir, "${datasetKey}_passfail_p$participantId.json")
    }

    /** Map videoName -> "PASS"/"FAIL" (latest wins) */
    fun loadDecisionMap(participantId: Int): MutableMap<String, String> {
        val f = participantFile(participantId)
        if (!f.exists()) return mutableMapOf()
        val txt = f.readText()
        if (txt.isBlank()) return mutableMapOf()

        val obj = JSONObject(txt)
        val out = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.getString(k)
        }
        return out
    }

    fun setDecision(participantId: Int, videoName: String, decision: String) {
        require(decision == "PASS" || decision == "FAIL") { "decision must be PASS or FAIL" }

        val map = loadDecisionMap(participantId)
        map[videoName] = decision

        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)

        participantFile(participantId).writeText(obj.toString())
    }

    fun clearDecisions(participantId: Int) {
        val f = participantFile(participantId)
        if (f.exists()) {
            f.delete()
        }
    }

    fun getPassedStimuli(participantId: Int): Set<String> =
        loadDecisionMap(participantId).filterValues { it == "PASS" }.keys

    fun getPassedCount(participantId: Int): Int = getPassedStimuli(participantId).size
}
