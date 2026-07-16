package dev.andrea.speechprod.util

import android.content.Context
import org.json.JSONObject
import java.io.File

class DecisionStore(private val context: Context) {

    private fun runFile(participantId: Int, runId: String): File {
        val runDir = RunStore.getOrCreateRunDir(context, participantId, runId)
        return File(runDir, "passfail_p${participantId}_run_${runId}.json")
    }

    private fun allRunDirs(participantId: Int): List<File> {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val runsDir = File(File(File(baseDir, "participants"), "p$participantId"), "runs")
        return runsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }

    fun loadDecisionMap(participantId: Int, runId: String): MutableMap<String, String> {
        val f = runFile(participantId, runId)
        if (!f.exists()) return mutableMapOf()
        val txt = f.readText()
        if (txt.isBlank()) return mutableMapOf()
        return try {
            val obj = JSONObject(txt)
            val out = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.getString(k)
            }
            out
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun setDecision(participantId: Int, runId: String, videoName: String, decision: String) {
        require(decision == "PASS" || decision == "FAIL") { "decision must be PASS or FAIL" }
        val map = loadDecisionMap(participantId, runId)
        map[videoName] = decision
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        runFile(participantId, runId).writeText(obj.toString())
    }

    fun clearDecisions(participantId: Int) {
        allRunDirs(participantId).forEach { runDir ->
            runDir.listFiles { f -> f.name.startsWith("passfail_") }
                ?.forEach { it.delete() }
        }
    }

    fun getPassedVideos(participantId: Int): Set<String> {
        return allRunDirs(participantId)
            .flatMap { runDir ->
                val runId = runDir.name.removePrefix("run_")
                loadDecisionMap(participantId, runId).filterValues { it == "PASS" }.keys
            }
            .toSet()
    }

    fun getPassedCount(participantId: Int): Int = getPassedVideos(participantId).size
}
