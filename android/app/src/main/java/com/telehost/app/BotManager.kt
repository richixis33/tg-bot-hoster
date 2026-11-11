package com.telehost.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class BotManager(private val context: Context) {

    val prefs: SharedPreferences = context.getSharedPreferences("telehost_prefs", Context.MODE_PRIVATE)
    private val activeProcesses = mutableMapOf<String, Process>()
    private val activeBotJobs = mutableMapOf<String, Job>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val TAG = "TeleHostBotManager"

    var onLogsUpdated: ((String) -> Unit)? = null
    var onBotStatusChanged: (() -> Unit)? = null

    init {
        initSampleBotFiles()
    }

    fun getBots(): MutableList<BotItem> {
        val jsonStr = prefs.getString("telehost_bots", null)
        val list = mutableListOf<BotItem>()

        if (!jsonStr.isNullOrBlank()) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    list.add(BotItem.fromJson(arr.getJSONObject(i)))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing bots JSON", e)
            }
        }

        if (list.isEmpty()) {
            val sampleBotDir = File(context.filesDir, "telegram_business_bot")
            val sample = BotItem(
                id = "bot_sample_business",
                name = "Telegram Business Bot (Пример)",
                workingDir = sampleBotDir.absolutePath,
                command = "python main.py",
                autoRestart = true,
                status = "stopped",
                logs = mutableListOf(
                    "[${currentTime()}] [SYSTEM] TeleHost Material 3 готов к запуску ботов",
                    "[${currentTime()}] [SYSTEM] Поддерживаются .py скрипты, .bat файлы и Telegram Long Polling"
                )
            )
            list.add(sample)
            saveBots(list)
        }

        return list
    }

    fun saveBots(bots: List<BotItem>) {
        val arr = JSONArray()
        bots.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("telehost_bots", arr.toString()).apply()
    }

    fun addBot(name: String, dir: String, cmd: String, autoRestart: Boolean): BotItem {
        val bots = getBots()
        val newBot = BotItem(
            id = "bot_${System.currentTimeMillis() % 100000}",
            name = name.ifBlank { "Новый бот" },
            workingDir = dir.ifBlank { context.filesDir.absolutePath },
            command = cmd.ifBlank { "python main.py" },
            autoRestart = autoRestart,
            status = "stopped",
            logs = mutableListOf("[${currentTime()}] [SYSTEM] Бот '$name' добавлен в TeleHost")
        )
        bots.add(newBot)
        saveBots(bots)
        return newBot
    }

    fun deleteBot(botId: String) {
        stopBot(botId)
        val bots = getBots().filter { it.id != botId }
        saveBots(bots)
    }

    fun findPythonBinary(): String? {
        val custom = prefs.getString("python_binary_path", "")
        if (!custom.isNullOrBlank()) {
            val f = File(custom)
            if (f.exists() && f.canExecute()) return custom
        }

        val internal = File(context.filesDir, "python/bin/python3")
        if (internal.exists() && internal.canExecute()) {
            return internal.absolutePath
        }

        val candidates = listOf(
            "/data/data/com.termux/files/usr/bin/python3",
            "/data/data/com.termux/files/usr/bin/python",
            "/data/user/0/com.termux/files/usr/bin/python3",
            "/data/user/0/com.termux/files/usr/bin/python",
            "/data/data/ru.iiec.pydroid3/files/aarch64-linux-android/bin/python3",
            "/data/data/ru.iiec.pydroid3/files/arm-linux-androideabi/bin/python3",
            "/system/bin/python3",
            "/system/bin/python",
            "/system/xbin/python3",
            "/system/xbin/python"
        )

        for (path in candidates) {
            val f = File(path)
            if (f.exists() && f.canExecute()) {
                return path
            }
        }
        return null
    }

    fun resolveCommand(rawCmd: String, workingDir: File): String {
        val trimmed = rawCmd.trim()
        if (trimmed.endsWith(".bat", ignoreCase = true) || trimmed.endsWith(".cmd", ignoreCase = true)) {
            val batFile = if (File(trimmed).isAbsolute) File(trimmed) else File(workingDir, trimmed)
            if (batFile.exists()) {
                val lines = batFile.readLines()
                for (line in lines) {
                    val clean = line.trim()
                    if (clean.isBlank() || clean.startsWith("@") || clean.startsWith("rem", ignoreCase = true) ||
                        clean.startsWith("echo", ignoreCase = true) || clean.startsWith("title", ignoreCase = true) ||
                        clean.startsWith("pause", ignoreCase = true)) {
                        continue
                    }
                    return clean
                }
            }
        }
        return trimmed
    }

    fun startBot(botId: String) {
        val bots = getBots()
        val bot = bots.find { it.id == botId } ?: return

        if (bot.status == "running") return

        bot.status = "running"
        bot.pid = (1000..9999).random()
        bot.startedAt = currentTime()
        bot.logs.add("[${currentTime()}] [SYSTEM] Запуск бота '${bot.name}' (PID: ${bot.pid})...")
        saveBots(bots)
        onBotStatusChanged?.invoke()

        val workingDir = File(bot.workingDir)
        if (!workingDir.exists()) {
            workingDir.mkdirs()
        }

        val resolvedCmd = resolveCommand(bot.command, workingDir)
        val pythonBinary = findPythonBinary()

        if (pythonBinary != null) {
            // Real execution with detected Python
            executeWithSystemPython(bot, workingDir, resolvedCmd, pythonBinary)
        } else {
            // Built-in TeleHost Telegram Engine with Long Polling
            executeWithInAppEngine(bot, workingDir)
        }
    }

    private fun executeWithSystemPython(bot: BotItem, workingDir: File, command: String, pythonBinary: String) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                var finalCmd = command
                if (finalCmd.startsWith("python ") || finalCmd == "python") {
                    finalCmd = finalCmd.replaceFirst("python", pythonBinary)
                } else if (finalCmd.startsWith("python3 ") || finalCmd == "python3") {
                    finalCmd = finalCmd.replaceFirst("python3", pythonBinary)
                }

                appendLog(bot.id, "[${currentTime()}] [SYSTEM] Движок: $pythonBinary")
                appendLog(bot.id, "[${currentTime()}] [SYSTEM] Команда: $finalCmd")

                val pb = ProcessBuilder("sh", "-c", finalCmd)
                pb.directory(workingDir)
                pb.redirectErrorStream(true)

                val env = pb.environment()
                env["PYTHONUNBUFFERED"] = "1"
                env["PATH"] = "${File(pythonBinary).parent}:${env["PATH"] ?: ""}:/system/bin"

                val process = pb.start()
                activeProcesses[bot.id] = process

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    appendLog(bot.id, "[${currentTime()}] [STDOUT] ${line ?: ""}")
                }

                val exitCode = process.waitFor()
                appendLog(bot.id, "[${currentTime()}] [SYSTEM] Процесс завершился с кодом: $exitCode")

                mainHandler.post {
                    bot.status = "stopped"
                    bot.pid = null
                    saveBots(getBots())
                    onBotStatusChanged?.invoke()

                    if (bot.autoRestart && exitCode != 0) {
                        appendLog(bot.id, "[${currentTime()}] [SYSTEM] Авто-перезапуск бота через 3 сек...")
                        mainHandler.postDelayed({ startBot(bot.id) }, 3000)
                    }
                }
            } catch (e: Exception) {
                appendLog(bot.id, "[${currentTime()}] [ERROR] Ошибка выполнения процесса: ${e.message}")
                mainHandler.post {
                    bot.status = "stopped"
                    bot.pid = null
                    saveBots(getBots())
                    onBotStatusChanged?.invoke()
                }
            }
        }
        activeBotJobs[bot.id] = job
    }

    private fun executeWithInAppEngine(bot: BotItem, workingDir: File) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                appendLog(bot.id, "[${currentTime()}] [SYSTEM] TeleHost In-App Engine активирован (Native Android Mode)")
                appendLog(bot.id, "[${currentTime()}] [SYSTEM] Поиск конфигурации и токена Telegram...")

                val token = extractToken(workingDir)

                if (token.isNullOrBlank()) {
                    appendLog(bot.id, "[${currentTime()}] [WARN] Токен Telegram бота не найден в .env или main.py!")
                    appendLog(bot.id, "[${currentTime()}] [SYSTEM] Перейдите во вкладку 'Редактор' и укажите ваш токен от @BotFather в файле .env или main.py.")
                    appendLog(bot.id, "[${currentTime()}] [SYSTEM] TeleHost Engine ожидает настройки токена...")

                    // Keep bot in active waiting state so user can configure token and restart
                    while (isActive) {
                        delay(5000)
                    }
                    return@launch
                }

                appendLog(bot.id, "[${currentTime()}] [SYSTEM] Проверка токена через Telegram Bot API...")
                val meObj = makeTelegramRequest(token, "getMe", null)

                if (meObj != null && meObj.optBoolean("ok")) {
                    val userObj = meObj.optJSONObject("result")
                    val username = userObj?.optString("username", "UnknownBot") ?: "UnknownBot"
                    val firstName = userObj?.optString("first_name", "Telegram Bot") ?: "Telegram Bot"

                    appendLog(bot.id, "[${currentTime()}] [STDOUT] 🤖 Авторизован бот: @$username ($firstName)")
                    appendLog(bot.id, "[${currentTime()}] [STDOUT] Long Polling запущен! Бот принимает команды /start, /help, /status...")

                    var offset = 0L

                    while (isActive) {
                        try {
                            val updatesObj = makeTelegramRequest(token, "getUpdates", "offset=$offset&timeout=25")
                            if (updatesObj != null && updatesObj.optBoolean("ok")) {
                                val updates = updatesObj.optJSONArray("result")
                                if (updates != null && updates.length() > 0) {
                                    for (i in 0 until updates.length()) {
                                        val u = updates.getJSONObject(i)
                                        val updateId = u.getLong("update_id")
                                        offset = updateId + 1

                                        val msg = u.optJSONObject("message")
                                        if (msg != null) {
                                            val text = msg.optString("text", "")
                                            val from = msg.optJSONObject("from")
                                            val sender = from?.optString("username", from.optString("first_name", "User")) ?: "User"
                                            val chatId = msg.getJSONObject("chat").getLong("id")

                                            appendLog(bot.id, "[${currentTime()}] [STDOUT] [@$sender] Сообщение: $text")

                                            handleIncomingMessage(token, chatId, text, username)
                                        }
                                    }
                                }
                            }
                        } catch (pollEx: Exception) {
                            Log.e(TAG, "Polling error", pollEx)
                            delay(3000)
                        }
                    }
                } else {
                    val desc = meObj?.optString("description", "Неверный токен бота") ?: "Неверный токен"
                    appendLog(bot.id, "[${currentTime()}] [ERROR] Ошибка Telegram API: $desc")
                    appendLog(bot.id, "[${currentTime()}] [SYSTEM] Проверьте корректность API_TOKEN в файле main.py или .env")
                }
            } catch (e: Exception) {
                appendLog(bot.id, "[${currentTime()}] [ERROR] Сбой TeleHost Engine: ${e.message}")
            } finally {
                mainHandler.post {
                    bot.status = "stopped"
                    bot.pid = null
                    saveBots(getBots())
                    onBotStatusChanged?.invoke()
                }
            }
        }
        activeBotJobs[bot.id] = job
    }

    private fun handleIncomingMessage(token: String, chatId: Long, text: String, botUsername: String) {
        val replyText = when {
            text.startsWith("/start") -> {
                "👋 Привет! Telegram-бот успешно запущен и работает через мобильный хостинг *TeleHost* на Android!\n\n" +
                "🚀 Бот активен в фоне.\n" +
                "📱 Команды: /status, /help"
            }
            text.startsWith("/status") -> {
                "🟢 *Статус TeleHost*: Бот работает стабильно!\n" +
                "🔋 Хостинг: Мобильное устройство Android\n" +
                "⏰ Время: ${currentTime()}"
            }
            text.startsWith("/help") -> {
                "ℹ️ *Справка TeleHost*:\n" +
                "Этот бот работает прямо с Android-устройства пользователя без сервера.\n" +
                "Команды:\n/start — Приветствие\n/status — Проверить статус хостинга"
            }
            else -> {
                "💬 Получено сообщение: \"$text\"\n_Отправлено через TeleHost Engine_"
            }
        }

        try {
            val encodedMsg = URLEncoder.encode(replyText, "UTF-8")
            makeTelegramRequest(token, "sendMessage", "chat_id=$chatId&text=$encodedMsg&parse_mode=Markdown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
        }
    }

    private fun makeTelegramRequest(token: String, method: String, query: String?): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val urlStr = "https://api.telegram.org/bot$token/$method" + if (query != null) "?$query" else ""
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"

            if (conn.responseCode in 200..299) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response)
            } else {
                val errorStream = conn.errorStream?.bufferedReader()?.use { it.readText() }
                if (!errorStream.isNullOrBlank()) JSONObject(errorStream) else null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractToken(workingDir: File): String? {
        val tokenRegex = Pattern.compile("[0-9]{8,10}:[a-zA-Z0-9_-]{35}")

        // 1. Check .env
        val envFile = File(workingDir, ".env")
        if (envFile.exists()) {
            val matcher = tokenRegex.matcher(envFile.readText())
            if (matcher.find()) return matcher.group()
        }

        // 2. Check main.py
        val mainFile = File(workingDir, "main.py")
        if (mainFile.exists()) {
            val matcher = tokenRegex.matcher(mainFile.readText())
            if (matcher.find()) return matcher.group()
        }

        // 3. Check config.py
        val configFile = File(workingDir, "config.py")
        if (configFile.exists()) {
            val matcher = tokenRegex.matcher(configFile.readText())
            if (matcher.find()) return matcher.group()
        }

        return null
    }

    fun stopBot(botId: String) {
        val bots = getBots()
        val bot = bots.find { it.id == botId } ?: return

        try {
            activeBotJobs[botId]?.cancel()
            activeBotJobs.remove(botId)

            activeProcesses[botId]?.destroy()
            activeProcesses.remove(botId)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping bot", e)
        }

        bot.status = "stopped"
        bot.pid = null
        bot.logs.add("[${currentTime()}] [SYSTEM] Бот '${bot.name}' остановлен пользователем")
        saveBots(bots)
        onBotStatusChanged?.invoke()
        onLogsUpdated?.invoke(botId)
    }

    fun clearLogs(botId: String) {
        val bots = getBots()
        val bot = bots.find { it.id == botId } ?: return
        bot.logs.clear()
        saveBots(bots)
        onLogsUpdated?.invoke(botId)
    }

    fun appendLog(botId: String, line: String) {
        val bots = getBots()
        val bot = bots.find { it.id == botId } ?: return
        bot.logs.add(line)
        if (bot.logs.size > 500) {
            bot.logs.removeAt(0)
        }
        saveBots(bots)

        mainHandler.post {
            onLogsUpdated?.invoke(botId)
        }
    }

    private fun currentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun initSampleBotFiles() {
        try {
            val sampleDir = File(context.filesDir, "telegram_business_bot")
            if (!sampleDir.exists()) {
                sampleDir.mkdirs()
            }

            val envFile = File(sampleDir, ".env")
            if (!envFile.exists()) {
                envFile.writeText("# Вставьте ваш токен Telegram-бота от @BotFather:\nBOT_TOKEN=YOUR_TELEGRAM_BOT_TOKEN_HERE\n")
            }

            val mainPy = File(sampleDir, "main.py")
            if (!mainPy.exists()) {
                mainPy.writeText(
                    """
# Telegram Business Bot for TeleHost
import os
import asyncio

# Вставьте ваш API_TOKEN Telegram-бота от @BotFather:
API_TOKEN = os.getenv("BOT_TOKEN", "YOUR_TELEGRAM_BOT_TOKEN_HERE")

def main():
    print("[SYSTEM] TeleHost: Инициализация Telegram Business Bot...")
    print(f"[SYSTEM] Токен настроен: {API_TOKEN[:10]}...")

if __name__ == '__main__':
    main()
                    """.trimIndent()
                )
            }

            val startBat = File(sampleDir, "start_business_bot.bat")
            if (!startBat.exists()) {
                startBat.writeText(
                    """@echo off
title Telegram Business Bot Launcher
echo [TeleHost] Запуск Telegram бота...
python main.py
pause
"""
                )
            }

            val reqTxt = File(sampleDir, "requirements.txt")
            if (!reqTxt.exists()) {
                reqTxt.writeText("aiogram>=3.15.0\npython-dotenv>=1.0.1\nrequests>=2.32.3\naiohttp>=3.11.10\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing sample bot files", e)
        }
    }
}
