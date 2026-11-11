package com.telehost.app

import org.json.JSONArray
import org.json.JSONObject

data class BotItem(
    val id: String,
    var name: String,
    var workingDir: String,
    var command: String,
    var autoRestart: Boolean = true,
    var status: String = "stopped", // "running" or "stopped"
    var pid: Int? = null,
    var startedAt: String? = null,
    val logs: MutableList<String> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("working_dir", workingDir)
        obj.put("command", command)
        obj.put("auto_restart", autoRestart)
        obj.put("status", status)
        obj.put("pid", pid ?: JSONObject.NULL)
        obj.put("started_at", startedAt ?: JSONObject.NULL)

        val logsArr = JSONArray()
        logs.forEach { logsArr.put(it) }
        obj.put("logs", logsArr)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): BotItem {
            val id = obj.optString("id", "bot_${System.currentTimeMillis()}")
            val name = obj.optString("name", "Unnamed Bot")
            val workingDir = obj.optString("working_dir", "")
            val command = obj.optString("command", "python main.py")
            val autoRestart = obj.optBoolean("auto_restart", true)
            val status = obj.optString("status", "stopped")
            val pid = if (obj.has("pid") && !obj.isNull("pid")) obj.getInt("pid") else null
            val startedAt = if (obj.has("started_at") && !obj.isNull("started_at")) obj.getString("started_at") else null

            val logsList = mutableListOf<String>()
            val logsArr = obj.optJSONArray("logs")
            if (logsArr != null) {
                for (i in 0 until logsArr.length()) {
                    logsList.add(logsArr.getString(i))
                }
            }

            return BotItem(
                id = id,
                name = name,
                workingDir = workingDir,
                command = command,
                autoRestart = autoRestart,
                status = status,
                pid = pid,
                startedAt = startedAt,
                logs = logsList
            )
        }
    }
}
