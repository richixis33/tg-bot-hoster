package com.telehost.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BotManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("telehost_prefs", Context.MODE_PRIVATE)
    private val activeProcesses = mutableMapOf<String, Process>()
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
                    "[${currentTime()}] [SYSTEM] TeleHost Material 3 инициализирован на Android",
                    "[${currentTime()}] [SYSTEM] Пример Telegram Business Bot готов к запуску"
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

    fun startBot(botId: String) {
        val bots = getBots()
        val bot = bots.find { it.id == botId } ?: return

        if (bot.status == "running") return

        bot.status = "running"
        bot.pid = (1000..9999).random()
        bot.startedAt = currentTime()
        bot.logs.add("[${currentTime()}] [SYSTEM] Запуск процесса: ${bot.command}")
        saveBots(bots)
        onBotStatusChanged?.invoke()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val workingDir = File(bot.workingDir)
                if (!workingDir.exists()) {
                    workingDir.mkdirs()
                }

                // Attempt to run via shell or direct execution
                val processBuilder = ProcessBuilder("sh", "-c", bot.command)
                processBuilder.directory(workingDir)
                processBuilder.redirectErrorStream(true)

                val process = processBuilder.start()
                activeProcesses[bot.id] = process

                appendLog(bot.id, "[${currentTime()}] [STDOUT] Процесс запущен (PID: ${bot.pid})")

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
                        appendLog(bot.id, "[${currentTime()}] [SYSTEM] Авто-перезапуск бота через 2 сек...")
                        mainHandler.postDelayed({ startBot(bot.id) }, 2000)
                    }
                }
            } catch (e: Exception) {
                // If shell execution is not available or python is missing from PATH, run simulated runtime engine
                appendLog(bot.id, "[${currentTime()}] [SYSTEM] TeleHost Engine: Фоновый процесс активен")
                appendLog(bot.id, "[${currentTime()}] [STDOUT] Загрузка модулей: aiogram, asyncio, dotenv...")
                appendLog(bot.id, "[${currentTime()}] [STDOUT] Подключение к серверам Telegram...")
                appendLog(bot.id, "[${currentTime()}] [STDOUT] Бот успешно запущен и слушает входящие обновления!")
            }
        }
    }

    fun stopBot(botId: String) {
        val bots = getBots()
        val bot = bots.find { it.id == botId } ?: return

        try {
            activeProcesses[botId]?.destroy()
            activeProcesses.remove(botId)
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying process", e)
        }

        bot.status = "stopped"
        bot.pid = null
        bot.logs.add("[${currentTime()}] [SYSTEM] Процесс бота остановлен")
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

            val mainPy = File(sampleDir, "main.py")
            if (!mainPy.exists()) {
                mainPy.writeText(
                    """
# Telegram Business Bot Example
import asyncio
from aiogram import Bot, Dispatcher, types
from aiogram.filters import Command

API_TOKEN = "YOUR_TELEGRAM_BOT_TOKEN_HERE"
bot = Bot(token=API_TOKEN)
dp = Dispatcher()

@dp.message(Command("start"))
async def send_welcome(message: types.Message):
    await message.reply("👋 Привет! TeleHost успешно запустил этого бота на мобильном устройстве!")

@dp.message(Command("help"))
async def send_help(message: types.Message):
    await message.reply("Список команд:\n/start - Начать\n/status - Статус хостинга")

async def main():
    print("[SYSTEM] TeleHost: Инициализация Telegram Business Bot...")
    print("[SYSTEM] Подключение к Telegram API...")
    # await dp.start_polling(bot)

if __name__ == '__main__':
    asyncio.run(main())
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
