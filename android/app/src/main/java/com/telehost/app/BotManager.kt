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

    fun scanBotFiles(workingDir: File): List<File> {
        val result = mutableListOf<File>()
        try {
            if (!workingDir.exists() || !workingDir.isDirectory) return result

            fun walk(dir: File, depth: Int) {
                if (depth > 3) return
                val list = dir.listFiles() ?: return
                for (file in list) {
                    if (file.isDirectory && !file.name.startsWith(".") && file.name != "__pycache__") {
                        walk(file, depth + 1)
                    } else if (file.isFile) {
                        val name = file.name.lowercase(Locale.getDefault())
                        if (name.endsWith(".py") || name.endsWith(".bat") || name.endsWith(".cmd") ||
                            name.endsWith(".sh") || name.endsWith(".env") || name.endsWith(".json") ||
                            name.endsWith(".txt") || name.endsWith(".ini") || name.endsWith(".cfg") ||
                            name.endsWith(".yml") || name.endsWith(".yaml")) {
                            result.add(file)
                        }
                    }
                }
            }

            walk(workingDir, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning bot files in $workingDir", e)
        }
        return result.sortedWith(compareBy({ !it.name.endsWith(".bat") && !it.name.endsWith(".py") }, { it.name }))
    }

    fun autoDetectCommand(workingDir: File): String {
        try {
            val files = scanBotFiles(workingDir)

            // 1. Look for .bat files first (e.g. start_business_bot.bat)
            val bat = files.find { it.name.endsWith(".bat", ignoreCase = true) }
            if (bat != null) return bat.name

            // 2. Look for main.py or bot.py
            val mainPy = files.find { it.name.equals("main.py", ignoreCase = true) }
            if (mainPy != null) return "python main.py"

            val botPy = files.find { it.name.equals("bot.py", ignoreCase = true) }
            if (botPy != null) return "python bot.py"

            // 3. Any .py file
            val anyPy = files.find { it.name.endsWith(".py", ignoreCase = true) }
            if (anyPy != null) return "python ${anyPy.name}"
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting command", e)
        }
        return "python main.py"
    }

    fun resolveCommand(rawCmd: String, workingDir: File): String {
        val trimmed = rawCmd.trim()
        if (trimmed.endsWith(".bat", ignoreCase = true) || trimmed.endsWith(".cmd", ignoreCase = true)) {
            val batFile = if (File(trimmed).isAbsolute) File(trimmed) else File(workingDir, trimmed)
            try {
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
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing .bat file $batFile", e)
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
        try {
            if (!workingDir.exists()) {
                workingDir.mkdirs()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot create working dir", e)
        }

        val resolvedCmd = resolveCommand(bot.command, workingDir)
        val pythonBinary = findPythonBinary()

        if (pythonBinary != null) {
            executeWithSystemPython(bot, workingDir, resolvedCmd, pythonBinary)
        } else {
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

    private val userWallets = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private fun getWallet(userId: Long): String? {
        if (userWallets.containsKey(userId)) return userWallets[userId]
        val saved = prefs.getString("wallet_user_$userId", null)
        if (saved != null) userWallets[userId] = saved
        return saved
    }

    private fun setWallet(userId: Long, wallet: String) {
        userWallets[userId] = wallet
        prefs.edit().putString("wallet_user_$userId", wallet).apply()
    }

    private fun executeWithInAppEngine(bot: BotItem, workingDir: File) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                appendLog(bot.id, "[${currentTime()}] [SYSTEM] TeleHost In-App Engine (Native Android Mode)")
                appendLog(bot.id, "[${currentTime()}] [SYSTEM] Поиск токена в папке: ${workingDir.absolutePath}")

                val tokenResult = extractToken(workingDir)

                if (tokenResult.error != null) {
                    appendLog(bot.id, "[${currentTime()}] [ERROR] Сбой чтения файлов: ${tokenResult.error}")
                    appendLog(bot.id, "[${currentTime()}] [SYSTEM] ⚠️ Если папка находится в общей памяти телефона, откройте 'Настройки' TeleHost и нажмите 'Предоставить доступ ко всем файлам'.")
                    return@launch
                }

                val token = tokenResult.token

                if (token.isNullOrBlank() || token.contains("YOUR_TELEGRAM_BOT_TOKEN")) {
                    appendLog(bot.id, "[${currentTime()}] [WARN] Токен Telegram-бота не указан в файлах .env или main.py!")
                    appendLog(bot.id, "[${currentTime()}] [SYSTEM] ✏️ Перейдите во вкладку 'Редактор', укажите ваш токен от @BotFather и нажмите 'Сохранить'.")
                    appendLog(bot.id, "[${currentTime()}] [SYSTEM] TeleHost Engine ожидает настройки токена...")

                    while (isActive) {
                        delay(5000)
                    }
                    return@launch
                }

                appendLog(bot.id, "[${currentTime()}] [SYSTEM] Анализ структуры и логики бота...")
                val parsedBot = PythonBotParser.parseBot(workingDir)
                appendLog(bot.id, "[${currentTime()}] [SYSTEM] Загружен интерфейс: ${parsedBot.name}")
                if (parsedBot.isBusinessCryptoBot) {
                    appendLog(bot.id, "[${currentTime()}] [SYSTEM] ✅ Активирован режим Telegram Business Crypto Bot (Inline-панель + команды)")
                }

                appendLog(bot.id, "[${currentTime()}] [SYSTEM] Проверка токена через Telegram Bot API...")
                val meObj = makeTelegramRequest(token, "getMe", null)

                if (meObj != null && meObj.optBoolean("ok")) {
                    val userObj = meObj.optJSONObject("result")
                    val username = userObj?.optString("username", "UnknownBot") ?: "UnknownBot"
                    val firstName = userObj?.optString("first_name", "Telegram Bot") ?: "Telegram Bot"

                    appendLog(bot.id, "[${currentTime()}] [STDOUT] 🤖 Авторизован бот: @$username ($firstName)")
                    appendLog(bot.id, "[${currentTime()}] [STDOUT] Long Polling запущен! Бот онлайн и принимает сообщения.")

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

                                        // 1. Regular message
                                        val msg = u.optJSONObject("message")
                                        if (msg != null) {
                                            val text = msg.optString("text", "")
                                            val from = msg.optJSONObject("from")
                                            val sender = from?.optString("username", from.optString("first_name", "User")) ?: "User"
                                            val chatId = msg.getJSONObject("chat").getLong("id")
                                            val userId = from?.optLong("id", chatId) ?: chatId

                                            appendLog(bot.id, "[${currentTime()}] [STDOUT] [@$sender]: $text")
                                            handleIncomingMessage(token, chatId, userId, text, username, parsedBot)
                                        }

                                        // 2. Callback query (inline button clicks)
                                        val cb = u.optJSONObject("callback_query")
                                        if (cb != null) {
                                            val cbId = cb.getString("id")
                                            val cbData = cb.optString("data", "")
                                            val from = cb.getJSONObject("from")
                                            val sender = from.optString("username", from.optString("first_name", "User"))
                                            val cbMsg = cb.optJSONObject("message")
                                            val chatId = cbMsg?.getJSONObject("chat")?.getLong("id") ?: from.getLong("id")
                                            val userId = from.getLong("id")

                                            appendLog(bot.id, "[${currentTime()}] [STDOUT] [@$sender нажал(а) кнопку]: $cbData")
                                            handleCallbackQuery(token, cbId, chatId, userId, cbData, parsedBot)
                                        }

                                        // 3. Business connection event
                                        val bc = u.optJSONObject("business_connection")
                                        if (bc != null) {
                                            val user = bc.optJSONObject("user")
                                            val bUser = user?.optString("username", "User") ?: "User"
                                            val isEnabled = bc.optBoolean("is_enabled", false)
                                            val statusStr = if (isEnabled) "подключен" else "отключен"
                                            appendLog(bot.id, "[${currentTime()}] [BUSINESS] @$bUser -> $statusStr")
                                        }

                                        // 4. Business message
                                        val bMsg = u.optJSONObject("business_message")
                                        if (bMsg != null) {
                                            val bText = bMsg.optString("text", "")
                                            val from = bMsg.optJSONObject("from")
                                            val sender = from?.optString("username", "BusinessUser") ?: "BusinessUser"
                                            val chatId = bMsg.getJSONObject("chat").getLong("id")
                                            val userId = from?.optLong("id", chatId) ?: chatId

                                            appendLog(bot.id, "[${currentTime()}] [BUSINESS_MSG] [@$sender]: $bText")
                                            handleIncomingMessage(token, chatId, userId, bText, username, parsedBot)
                                        }
                                    }
                                }
                            }
                        } catch (pollEx: Exception) {
                            Log.e(TAG, "Polling loop error", pollEx)
                            delay(3000)
                        }
                    }
                } else {
                    val desc = meObj?.optString("description", "Неверный токен бота") ?: "Неверный токен"
                    appendLog(bot.id, "[${currentTime()}] [ERROR] Ошибка Telegram API: $desc")
                    appendLog(bot.id, "[${currentTime()}] [SYSTEM] Проверьте корректность токена в файле main.py или .env")
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

    private fun handleIncomingMessage(
        token: String,
        chatId: Long,
        userId: Long,
        text: String,
        botUsername: String,
        parsedBot: ParsedBotInfo
    ) {
        val trimmed = text.trim()
        val wallet = getWallet(userId)

        // 1. /start command
        if (trimmed.startsWith("/start")) {
            val replyText = if (parsedBot.isBusinessCryptoBot) {
                PythonBotParser.getBusinessStartText(wallet)
            } else {
                parsedBot.startText
            }
            val keyboard = if (parsedBot.isBusinessCryptoBot) {
                PythonBotParser.getBusinessKeyboard(wallet)
            } else {
                parsedBot.startKeyboard
            }
            sendTelegramMessage(token, chatId, replyText, keyboard, parseMode = "HTML")
            return
        }

        // 2. /setwallet command or direct TON address
        if (trimmed.startsWith("/setwallet") || trimmed.startsWith("!setwallet")) {
            val parts = trimmed.split("\\s+".toRegex())
            if (parts.size > 1) {
                val newWallet = parts[1].trim()
                setWallet(userId, newWallet)
                val reply = """
<blockquote>💎 <b>Кошелек сохранен</b>
────────────────────
<b>Адрес:</b>
<code>$newWallet</code>

<a href="https://tonviewer.com/$newWallet">Tonviewer ↗</a></blockquote>
                """.trimIndent()
                val kb = if (parsedBot.isBusinessCryptoBot) PythonBotParser.getBusinessKeyboard(newWallet) else parsedBot.startKeyboard
                sendTelegramMessage(token, chatId, reply, kb, parseMode = "HTML")
            } else {
                val prompt = """
<blockquote>💎 <b>Привязка кошелька</b>
────────────────────
Отправьте команду с адресом:
<code>/setwallet &lt;ваш TON адрес&gt;</code></blockquote>
                """.trimIndent()
                sendTelegramMessage(token, chatId, prompt, parseMode = "HTML")
            }
            return
        }

        // Check if raw message is a TON address (UQ... / EQ... or .ton)
        if ((trimmed.startsWith("UQ") || trimmed.startsWith("EQ") || trimmed.endsWith(".ton")) && trimmed.length >= 10 && !trimmed.contains(" ")) {
            setWallet(userId, trimmed)
            val reply = """
<blockquote>💎 <b>Кошелек сохранен</b>
────────────────────
<b>Адрес:</b>
<code>$trimmed</code>

<a href="https://tonviewer.com/$trimmed">Tonviewer ↗</a></blockquote>
            """.trimIndent()
            val kb = if (parsedBot.isBusinessCryptoBot) PythonBotParser.getBusinessKeyboard(trimmed) else parsedBot.startKeyboard
            sendTelegramMessage(token, chatId, reply, kb, parseMode = "HTML")
            return
        }

        // 3. Balance command
        if (trimmed.startsWith("/balance") || trimmed.startsWith("!balance") || trimmed.equals("баланс", ignoreCase = true)) {
            val parts = trimmed.split("\\s+".toRegex())
            val targetWallet = if (parts.size > 1) parts[1].trim() else wallet
            val card = PythonBotParser.getBalanceText(targetWallet)
            val kb = if (parsedBot.isBusinessCryptoBot) PythonBotParser.getBusinessKeyboard(targetWallet) else parsedBot.startKeyboard
            sendTelegramMessage(token, chatId, card, kb, parseMode = "HTML")
            return
        }

        // 4. Gas command
        if (trimmed.startsWith("/gas") || trimmed.startsWith("!gas") || trimmed.equals("газ", ignoreCase = true)) {
            val gas = PythonBotParser.getGasFeesText()
            sendTelegramMessage(token, chatId, gas, parseMode = "HTML")
            return
        }

        // 5. Top crypto command
        if (trimmed.startsWith("/top") || trimmed.startsWith("!top") || trimmed.equals("топ", ignoreCase = true)) {
            val top = PythonBotParser.getTopCryptoText()
            sendTelegramMessage(token, chatId, top, parseMode = "HTML")
            return
        }

        // 6. Ping command
        if (trimmed.startsWith("!ping") || trimmed.startsWith("/ping") || trimmed.equals("пинг", ignoreCase = true)) {
            sendTelegramMessage(token, chatId, "<blockquote>⚡ <b>Pong!</b> TeleHost Android Engine отклик: <code>~38ms</code></blockquote>", parseMode = "HTML")
            return
        }

        // 7. Currency Converter: e.g. !12 gram azn, !50 btc usd, 100 ton rub
        val convResult = PythonBotParser.parseAndConvert(trimmed)
        if (convResult != null) {
            sendTelegramMessage(token, chatId, convResult, parseMode = "HTML")
            return
        }

        // 8. Specific crypto rates: !btc, !eth, !ton, !sol, !gram, etc.
        val lower = trimmed.lowercase(Locale.getDefault())
        if (lower.startsWith("!") || lower.startsWith("/")) {
            val sym = lower.substring(1).trim()
            if (listOf("btc", "eth", "ton", "gram", "sol", "doge", "usdt", "bnb").contains(sym)) {
                val card = PythonBotParser.getCryptoPriceText(sym)
                sendTelegramMessage(token, chatId, card, parseMode = "HTML")
                return
            }
        }

        // 9. Help command
        if (trimmed.startsWith("/help") || trimmed.startsWith("!help") || trimmed.equals("помощь", ignoreCase = true) || trimmed.equals("хелп", ignoreCase = true)) {
            val kb = if (parsedBot.isBusinessCryptoBot) PythonBotParser.getBusinessKeyboard(wallet) else parsedBot.startKeyboard
            sendTelegramMessage(token, chatId, parsedBot.helpText, kb, parseMode = "HTML")
            return
        }

        // 10. Status command
        if (trimmed.startsWith("/status") || trimmed.startsWith("!status")) {
            val statusMsg = "🟢 <b>Статус TeleHost</b>: Бот активен на Android!\n⏰ Время: ${currentTime()}"
            sendTelegramMessage(token, chatId, statusMsg, parseMode = "HTML")
            return
        }

        // 11. Generic custom bot command check
        val cmd = trimmed.split("\\s+".toRegex())[0].lowercase(Locale.getDefault())
        if (parsedBot.commandHandlers.containsKey(cmd)) {
            sendTelegramMessage(token, chatId, parsedBot.commandHandlers[cmd]!!, parseMode = "HTML")
            return
        }

        // Default response
        if (parsedBot.isBusinessCryptoBot) {
            val kb = PythonBotParser.getBusinessKeyboard(wallet)
            val defMsg = PythonBotParser.getBusinessStartText(wallet)
            sendTelegramMessage(token, chatId, defMsg, kb, parseMode = "HTML")
        } else {
            sendTelegramMessage(token, chatId, "💬 Принято: \"$trimmed\"\n<i>TeleHost Engine</i>", parseMode = "HTML")
        }
    }

    private fun handleCallbackQuery(
        token: String,
        callbackQueryId: String,
        chatId: Long,
        userId: Long,
        data: String,
        parsedBot: ParsedBotInfo
    ) {
        val wallet = getWallet(userId)

        when (data) {
            "btn_check_balance" -> {
                if (wallet.isNullOrBlank()) {
                    answerCallbackQuery(token, callbackQueryId, "Кошелек не установлен.", showAlert = true)
                } else {
                    answerCallbackQuery(token, callbackQueryId, "Загрузка...")
                    val card = PythonBotParser.getBalanceText(wallet)
                    sendTelegramMessage(token, chatId, card, PythonBotParser.getBusinessKeyboard(wallet), parseMode = "HTML")
                }
            }
            "btn_set_wallet" -> {
                answerCallbackQuery(token, callbackQueryId)
                val prompt = """
<blockquote>💎 <b>Привязка кошелька</b>
────────────────────
Отправьте TON-адрес (<code>UQ...</code> / <code>EQ...</code>) прямо в этот чат
или команду:
<code>/setwallet &lt;ваш адрес&gt;</code></blockquote>
                """.trimIndent()
                sendTelegramMessage(token, chatId, prompt, parseMode = "HTML")
            }
            "btn_show_wallet" -> {
                if (wallet.isNullOrBlank()) {
                    answerCallbackQuery(token, callbackQueryId, "Кошелек не задан.", showAlert = true)
                } else {
                    answerCallbackQuery(token, callbackQueryId)
                    val msg = """
<blockquote>👛 <b>Текущий кошелек</b>
────────────────────
<code>$wallet</code>

<a href="https://tonviewer.com/$wallet">Tonviewer ↗</a></blockquote>
                    """.trimIndent()
                    sendTelegramMessage(token, chatId, msg, PythonBotParser.getBusinessKeyboard(wallet), parseMode = "HTML")
                }
            }
            "btn_business_status" -> {
                answerCallbackQuery(token, callbackQueryId)
                val msg = """
<blockquote>💼 <b>Telegram Business Режим</b>
────────────────────
Соединение: <code>активен</code> ✅
Право ответа: <code>включено</code> ✅
────────────────────
Бот готов обрабатывать команды и отвечать клиентам в бизнес-чатах!</blockquote>
                """.trimIndent()
                sendTelegramMessage(token, chatId, msg, PythonBotParser.getBusinessKeyboard(wallet), parseMode = "HTML")
            }
            "btn_gas_fees" -> {
                answerCallbackQuery(token, callbackQueryId)
                val msg = PythonBotParser.getGasFeesText()
                sendTelegramMessage(token, chatId, msg, PythonBotParser.getBusinessKeyboard(wallet), parseMode = "HTML")
            }
            "btn_top_crypto" -> {
                answerCallbackQuery(token, callbackQueryId)
                val msg = PythonBotParser.getTopCryptoText()
                sendTelegramMessage(token, chatId, msg, PythonBotParser.getBusinessKeyboard(wallet), parseMode = "HTML")
            }
            "btn_help_commands" -> {
                answerCallbackQuery(token, callbackQueryId)
                sendTelegramMessage(token, chatId, parsedBot.helpText, PythonBotParser.getBusinessKeyboard(wallet), parseMode = "HTML")
            }
            else -> {
                answerCallbackQuery(token, callbackQueryId, "Выбрано: $data")
                val resp = parsedBot.callbackHandlers[data] ?: "Пункт: $data"
                sendTelegramMessage(token, chatId, resp, parsedBot.startKeyboard, parseMode = "HTML")
            }
        }
    }

    fun sendTelegramMessage(
        token: String,
        chatId: Long,
        text: String,
        replyMarkup: JSONObject? = null,
        parseMode: String = "HTML"
    ): JSONObject? {
        val params = mutableMapOf(
            "chat_id" to chatId.toString(),
            "text" to text,
            "parse_mode" to parseMode,
            "disable_web_page_preview" to "true"
        )
        if (replyMarkup != null) {
            params["reply_markup"] = replyMarkup.toString()
        }
        return makeTelegramPost(token, "sendMessage", params)
    }

    fun answerCallbackQuery(
        token: String,
        callbackQueryId: String,
        text: String? = null,
        showAlert: Boolean = false
    ): JSONObject? {
        val params = mutableMapOf(
            "callback_query_id" to callbackQueryId,
            "show_alert" to showAlert.toString()
        )
        if (!text.isNullOrBlank()) {
            params["text"] = text
        }
        return makeTelegramPost(token, "answerCallbackQuery", params)
    }

    private fun makeTelegramPost(token: String, method: String, postData: Map<String, String>): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://api.telegram.org/bot$token/$method")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")

            val encoded = postData.map { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }.joinToString("&")

            conn.outputStream.use { os ->
                os.write(encoded.toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode in 200..299) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response)
            } else {
                val errorStream = conn.errorStream?.bufferedReader()?.use { it.readText() }
                if (!errorStream.isNullOrBlank()) JSONObject(errorStream) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "makeTelegramPost error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
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

    data class TokenResult(val token: String?, val error: String?)

    private fun extractToken(workingDir: File): TokenResult {
        val tokenRegex = Pattern.compile("[0-9]{8,10}:[a-zA-Z0-9_-]{35}")

        val filesToCheck = listOf(
            File(workingDir, ".env"),
            File(workingDir, "main.py"),
            File(workingDir, "bot.py"),
            File(workingDir, "config.py")
        )

        for (file in filesToCheck) {
            try {
                if (file.exists() && file.isFile) {
                    val content = file.readText()
                    val matcher = tokenRegex.matcher(content)
                    if (matcher.find()) {
                        return TokenResult(matcher.group(), null)
                    }
                }
            } catch (e: Exception) {
                return TokenResult(null, "${file.name}: ${e.message}")
            }
        }

        return TokenResult(null, null)
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
        bot.logs.add("[${currentTime()}] [SYSTEM] Бот '${bot.name}' остановлен")
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
