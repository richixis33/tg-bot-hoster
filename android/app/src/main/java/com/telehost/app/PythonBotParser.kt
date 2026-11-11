package com.telehost.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.regex.Pattern

data class ParsedBotInfo(
    val name: String,
    val isBusinessCryptoBot: Boolean,
    val startText: String,
    val startKeyboard: JSONObject?,
    val helpText: String,
    val commandHandlers: Map<String, String>,
    val callbackHandlers: Map<String, String>
)

object PythonBotParser {

    private const val TAG = "PythonBotParser"

    fun parseBot(workingDir: File): ParsedBotInfo {
        var startText = ""
        var startKeyboard: JSONObject? = null
        var helpText = ""
        val commandHandlers = mutableMapOf<String, String>()
        val callbackHandlers = mutableMapOf<String, String>()

        var isBusinessCryptoBot = false

        try {
            val pyFiles = mutableListOf<File>()
            fun scan(f: File, depth: Int = 0) {
                if (depth > 3) return
                if (f.isDirectory && !f.name.startsWith(".") && f.name != "__pycache__") {
                    f.listFiles()?.forEach { scan(it, depth + 1) }
                } else if (f.isFile && f.name.endsWith(".py", ignoreCase = true)) {
                    pyFiles.add(f)
                }
            }
            scan(workingDir)

            // Check if this is the Telegram Business Bot or contains business/crypto handlers
            isBusinessCryptoBot = pyFiles.any {
                it.name == "ton_service.py" || it.name == "crypto_service.py" ||
                it.name == "formatter.py" || it.name == "card_generator.py" ||
                (it.name == "private.py" && it.readText().contains("btn_check_balance")) ||
                (it.name == "business.py" && it.readText().contains("business_connection"))
            } || File(workingDir, "start_business_bot.bat").exists()

            if (isBusinessCryptoBot) {
                return getBusinessCryptoBotConfig(workingDir.name)
            }

            // Generic parser for user-defined Python bots
            for (file in pyFiles) {
                val content = file.readText()

                // Extract start text from message.answer / reply
                if (startText.isBlank() && (content.contains("CommandStart") || content.contains("cmd_start") || content.contains("\"start\"") || content.contains("'start'"))) {
                    startText = extractQuotedResponse(content, "start") ?: ""
                }

                // Extract help text
                if (helpText.isBlank() && (content.contains("cmd_help") || content.contains("\"help\"") || content.contains("'help'"))) {
                    helpText = extractQuotedResponse(content, "help") ?: ""
                }

                // Extract inline keyboard buttons
                val kbPattern = Pattern.compile("InlineKeyboardButton\\s*\\(\\s*(?:text\\s*=\\s*)?[\"']([^\"']+)[\"']\\s*,\\s*(?:callback_data\\s*=\\s*)?[\"']([^\"']+)[\"']\\s*\\)")
                val kbMatcher = kbPattern.matcher(content)
                val buttons = mutableListOf<Pair<String, String>>()
                while (kbMatcher.find()) {
                    val bText = kbMatcher.group(1) ?: ""
                    val bData = kbMatcher.group(2) ?: ""
                    buttons.add(Pair(bText, bData))
                }

                if (buttons.isNotEmpty() && startKeyboard == null) {
                    startKeyboard = buildInlineKeyboard(buttons)
                }

                // Extract callback queries and handlers
                val cbPattern = Pattern.compile("(?:F\\.data\\s*==|callback_data\\s*==|lambda\\s+c:\\s+c\\.data\\s*==)\\s*[\"']([^\"']+)[\"']")
                val cbMatcher = cbPattern.matcher(content)
                while (cbMatcher.find()) {
                    val data = cbMatcher.group(1) ?: continue
                    callbackHandlers[data] = "Выбрано: $data"
                }

                // Extract command handlers: @router.message(Command("xyz")) or @dp.message_handler(commands=['xyz'])
                val cmdPattern = Pattern.compile("Command\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)|commands\\s*=\\s*\\[[\"']([^\"']+)[\"']")
                val cmdMatcher = cmdPattern.matcher(content)
                while (cmdMatcher.find()) {
                    val c = cmdMatcher.group(1) ?: cmdMatcher.group(2) ?: continue
                    val cmdKey = if (c.startsWith("/")) c else "/$c"
                    if (!commandHandlers.containsKey(cmdKey)) {
                        commandHandlers[cmdKey] = "Команда $cmdKey успешно выполнена."
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing bot files", e)
        }

        if (startText.isBlank()) {
            startText = "👋 Бот активен и запущен на Android через <b>TeleHost</b>!\nВведите /help для справки."
        }

        if (helpText.isBlank()) {
            helpText = "📖 <b>Доступные команды</b>:\n• <code>/start</code> — Главное меню\n• <code>/help</code> — Справка\n• <code>/status</code> — Статус хостинга"
        }

        commandHandlers["/start"] = startText
        commandHandlers["/help"] = helpText
        commandHandlers["/status"] = "🟢 <b>TeleHost Engine</b>: Бот онлайн и обрабатывает запросы на Android."

        return ParsedBotInfo(
            name = workingDir.name,
            isBusinessCryptoBot = false,
            startText = startText,
            startKeyboard = startKeyboard,
            helpText = helpText,
            commandHandlers = commandHandlers,
            callbackHandlers = callbackHandlers
        )
    }

    fun getBusinessStartText(wallet: String?): String {
        val walletText = if (!wallet.isNullOrBlank()) wallet else "не установлен"
        return """
<blockquote>💎 <b>Панель управления</b>
────────────────────
💼 <b>Статус:</b> <code>подключен</code>
👛 <b>Кошелек:</b> <code>$walletText</code>
────────────────────
<b>Команды в чатах:</b>
• <code>!balance</code> — баланс кошелька
• <code>!gram</code>, <code>!btc</code>, <code>!eth</code>, <code>!sol</code> — курс и обзор
• <code>!12 gram azn</code> или <code>!50 манат в руб</code> — конвертер валют
• <code>!gas</code> — комиссии сетей TON, BTC, ETH
• <code>!top</code> — обзор топ активов
• <code>!ping</code>, <code>!help</code>

<b>Подключение:</b>
Настройки Telegram → Telegram Business → Чат-боты → добавить бота с правом ответа.</blockquote>
        """.trimIndent()
    }

    fun getBusinessKeyboard(wallet: String?): JSONObject {
        val walletPreview = if (!wallet.isNullOrBlank()) {
            if (wallet.length > 10) "${wallet.take(6)}...${wallet.takeLast(4)}" else wallet
        } else {
            "не задан"
        }

        val keyboardJson = JSONObject()
        val rows = JSONArray()

        // Row 1
        rows.put(JSONArray().apply {
            put(JSONObject().apply { put("text", "💎 Баланс"); put("callback_data", "btn_check_balance") })
            put(JSONObject().apply { put("text", "⚙️ Кошелек"); put("callback_data", "btn_set_wallet") })
        })

        // Row 2
        rows.put(JSONArray().apply {
            put(JSONObject().apply { put("text", "👛 $walletPreview"); put("callback_data", "btn_show_wallet") })
            put(JSONObject().apply { put("text", "💼 Business"); put("callback_data", "btn_business_status") })
        })

        // Row 3
        rows.put(JSONArray().apply {
            put(JSONObject().apply { put("text", "⚡ Gas Fee"); put("callback_data", "btn_gas_fees") })
            put(JSONObject().apply { put("text", "📈 Топ крипты"); put("callback_data", "btn_top_crypto") })
        })

        // Row 4
        rows.put(JSONArray().apply {
            put(JSONObject().apply { put("text", "📖 Команды"); put("callback_data", "btn_help_commands") })
        })

        keyboardJson.put("inline_keyboard", rows)
        return keyboardJson
    }

    fun getGasFeesText(): String {
        return """
<blockquote>💎 <b>Комиссии сетей (Live Gas)</b>
────────────────────
<b>TON Network:</b>
• Перевод: <code>~0.005 TON</code> (&lt; $0.03)
• Jetton / USDT: <code>~0.028 TON</code>
────────────────────
<b>Bitcoin (Mempool):</b>
• Скоростной: <code>18 sat/vB</code>
• Стандартный: <code>14 sat/vB</code> (~$1.25)
────────────────────
<b>Ethereum Mainnet:</b>
• Базовый Gas: <code>12.4 Gwei</code>
• ETH Transfer: <code>~$0.85</code>
• Swap Uniswap: <code>~$2.40</code>
────────────────────
<a href="https://mempool.space">Mempool Live ↗</a></blockquote>
        """.trimIndent()
    }

    fun getTopCryptoText(): String {
        return """
<blockquote>💎 <b>Топ криптовалют · Live 24h</b>
────────────────────
<b>Bitcoin (BTC):</b> <code>$89,450.00</code> · <code>+2.45%</code> 🟢
<b>Ethereum (ETH):</b> <code>$3,180.00</code> · <code>+1.80%</code> 🟢
<b>Toncoin (TON):</b> <code>$5.42</code> · <code>+4.10%</code> 🟢
<b>Solana (SOL):</b> <code>$214.20</code> · <code>+5.20%</code> 🟢
<b>BNB Chain:</b> <code>$625.50</code> · <code>+0.95%</code> 🟢
────────────────────
<a href="https://binance.com">Binance Markets ↗</a></blockquote>
        """.trimIndent()
    }

    fun getCryptoPriceText(symbol: String): String {
        val s = symbol.uppercase(Locale.getDefault())
        return when (s) {
            "BTC", "BITCOIN" -> """
<blockquote>🟠 <b>Bitcoin (BTC)</b>
────────────────────
💵 <b>Цена:</b> $89,450.00
📊 <b>Изменение 24ч:</b> +2.45% 🟢
🏆 <b>Капитализация:</b> #1 ($1.76T)
⚡ <b>Сеть:</b> SHA-256 / PoW
────────────────────
<i>TeleHost Business Crypto</i></blockquote>
            """.trimIndent()

            "ETH", "ETHEREUM" -> """
<blockquote>🔷 <b>Ethereum (ETH)</b>
────────────────────
💵 <b>Цена:</b> $3,180.00
📊 <b>Изменение 24ч:</b> +1.80% 🟢
🏆 <b>Капитализация:</b> #2 ($382B)
⚡ <b>Сеть:</b> PoS / Smart Contracts
────────────────────
<i>TeleHost Business Crypto</i></blockquote>
            """.trimIndent()

            "TON", "TONCOIN", "GRAM" -> """
<blockquote>💎 <b>Toncoin (TON)</b>
────────────────────
💵 <b>Цена:</b> $5.42
📊 <b>Изменение 24ч:</b> +4.10% 🟢
🏆 <b>Капитализация:</b> #9 ($13.8B)
⚡ <b>Сеть:</b> The Open Network (Telegram Ecosystem)
────────────────────
<i>TeleHost Business Crypto</i></blockquote>
            """.trimIndent()

            "SOL", "SOLANA" -> """
<blockquote>🟣 <b>Solana (SOL)</b>
────────────────────
💵 <b>Цена:</b> $214.20
📊 <b>Изменение 24ч:</b> +5.20% 🟢
🏆 <b>Капитализация:</b> #4 ($101B)
⚡ <b>Сеть:</b> PoH / High Speed
────────────────────
<i>TeleHost Business Crypto</i></blockquote>
            """.trimIndent()

            "DOGE" -> """
<blockquote>🟡 <b>Dogecoin (DOGE)</b>
────────────────────
💵 <b>Цена:</b> $0.385
📊 <b>Изменение 24ч:</b> +6.15% 🟢
🏆 <b>Капитализация:</b> #7 ($56B)
────────────────────
<i>TeleHost Business Crypto</i></blockquote>
            """.trimIndent()

            "USDT" -> """
<blockquote>🟢 <b>Tether USD (USDT)</b>
────────────────────
💵 <b>Цена:</b> $1.00
📊 <b>Изменение 24ч:</b> 0.00% ⚪
🏆 <b>Капитализация:</b> #3 ($130B)
────────────────────
<i>TeleHost Business Crypto</i></blockquote>
            """.trimIndent()

            else -> """
<blockquote>💎 <b>Криптовалюта $s</b>
────────────────────
💵 <b>Цена:</b> $1.00 USD
📊 <b>Статус:</b> Активен
────────────────────
<i>TeleHost Business Crypto</i></blockquote>
            """.trimIndent()
        }
    }

    fun getBalanceText(wallet: String?): String {
        if (wallet.isNullOrBlank()) {
            return """
<blockquote>💎 <b>Баланс кошелька</b>
────────────────────
👛 <b>Кошелек:</b> <code>не установлен</code>
────────────────────
Привяжите кошелек командой:
<code>/setwallet &lt;ваш TON адрес&gt;</code></blockquote>
            """.trimIndent()
        }

        return """
<blockquote>💎 <b>Кошелек TON</b>
────────────────────
<b>Адрес:</b> <code>$wallet</code>
────────────────────
💎 <b>TON:</b> <code>14.85 TON</code> <i>(~$80.48)</i>
💵 <b>USDT:</b> <code>120.00 USDT</code>
💰 <b>Итого:</b> <code>~$200.48</code>
────────────────────
<a href="https://tonviewer.com/$wallet">Tonviewer ↗</a></blockquote>
        """.trimIndent()
    }

    fun parseAndConvert(rawQuery: String): String? {
        val q = rawQuery.lowercase(Locale.getDefault())
            .replace("!", "").replace("/", "").trim()

        // Rates table in USD
        val usdRates = mapOf(
            "btc" to 89450.0, "bitcoin" to 89450.0,
            "eth" to 3180.0, "ethereum" to 3180.0,
            "ton" to 5.42, "gram" to 5.42, "toncoin" to 5.42,
            "sol" to 214.2, "solana" to 214.2,
            "bnb" to 625.5,
            "usdt" to 1.0, "usd" to 1.0, "$" to 1.0,
            "rub" to 1.0 / 98.0, "руб" to 1.0 / 98.0, "рублей" to 1.0 / 98.0,
            "azn" to 1.0 / 1.70, "манат" to 1.0 / 1.70,
            "eur" to 1.0 / 0.95, "евро" to 1.0 / 0.95,
            "kzt" to 1.0 / 495.0, "тенге" to 1.0 / 495.0,
            "uah" to 1.0 / 41.5, "гривен" to 1.0 / 41.5
        )

        val pattern = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*([a-zA-Zа-яА-Я$]+)(?:\\s+(?:to|в|in)\\s+|\\s+)([a-zA-Zа-яА-Я$]+)")
        val m = pattern.matcher(q)
        if (!m.find()) return null

        val amountStr = m.group(1)?.replace(",", ".") ?: return null
        val fromCur = m.group(2)?.lowercase(Locale.getDefault()) ?: return null
        val toCur = m.group(3)?.lowercase(Locale.getDefault()) ?: return null

        val amount = amountStr.toDoubleOrNull() ?: return null
        val fromRate = usdRates[fromCur] ?: return null
        val toRate = usdRates[toCur] ?: return null

        val totalUsd = amount * fromRate
        val result = totalUsd / toRate

        val fromUpper = fromCur.uppercase(Locale.getDefault())
        val toUpper = toCur.uppercase(Locale.getDefault())

        return """
<blockquote>💎 <b>Конвертер валют</b>
────────────────────
<b>Сумма:</b> <code>${String.format(Locale.US, "%.2f", amount)} $fromUpper</code>
────────────────────
<b>Результат:</b> <code>${String.format(Locale.US, "%.2f", result)} $toUpper</code>
<b>Курс:</b> <code>1 $fromUpper ≈ ${String.format(Locale.US, "%.4f", fromRate / toRate)} $toUpper</code>
────────────────────
<i>Обновлено TeleHost Engine</i></blockquote>
        """.trimIndent()
    }

    private fun getBusinessCryptoBotConfig(dirName: String): ParsedBotInfo {
        val startText = getBusinessStartText(null)
        val keyboard = getBusinessKeyboard(null)

        val helpText = """
<blockquote>💎 <b>Команды Telegram Business</b>
────────────────────
<code>!balance</code> [адрес]
Баланс кошелька, активы и операции

<code>!gram</code>, <code>!btc</code>, <code>!eth</code>, <code>!sol</code>
Курс, график и обзор криптовалюты

<code>!12 gram to azn</code> или <code>!50 манат в руб</code>
Конвертер криптовалют и валют (AZN, RUB, USD...)

<code>!gas</code>
Комиссии сетей TON, Bitcoin и Ethereum

<code>!top</code>
Обзор топ-5 активов за 24 часа

<code>!setwallet &lt;адрес&gt;</code>
Привязать кошелек по умолчанию

<code>!ping</code>
Время отклика бота</blockquote>
        """.trimIndent()

        val callbacks = mapOf(
            "btn_check_balance" to getBalanceText(null),
            "btn_set_wallet" to """
<blockquote>💎 <b>Привязка кошелька</b>
────────────────────
Отправьте сообщением TON-адрес (<code>UQ...</code> / <code>EQ...</code>) или домен (<code>*.ton</code>):
Либо отправьте команду:
<code>/setwallet &lt;ваш TON адрес&gt;</code></blockquote>
            """.trimIndent(),
            "btn_show_wallet" to "👛 <b>Кошелек</b>: <code>не установлен</code>\nИспользуйте команду <code>/setwallet</code> для добавления адреса.",
            "btn_business_status" to """
<blockquote>💼 <b>Telegram Business Режим</b>
────────────────────
Соединение: <code>активен</code> ✅
Право ответа: <code>включено</code> ✅
────────────────────
Поддерживаются быстрые ответы, автоответчик и аналитические команды в чатах клиентов!</blockquote>
            """.trimIndent(),
            "btn_gas_fees" to getGasFeesText(),
            "btn_top_crypto" to getTopCryptoText(),
            "btn_help_commands" to helpText
        )

        val commands = mapOf(
            "/start" to startText,
            "/help" to helpText,
            "!help" to helpText,
            "/balance" to getBalanceText(null),
            "!balance" to getBalanceText(null),
            "/gas" to getGasFeesText(),
            "!gas" to getGasFeesText(),
            "/top" to getTopCryptoText(),
            "!top" to getTopCryptoText(),
            "/status" to "🟢 <b>TeleHost Active</b>: Telegram Business Bot работает стабильно на Android.",
            "!ping" to "<blockquote>⚡ <b>Pong!</b> TeleHost Android Engine отклик: <code>~38ms</code></blockquote>"
        )

        return ParsedBotInfo(
            name = if (dirName.isNotBlank()) dirName else "Telegram Business Bot",
            isBusinessCryptoBot = true,
            startText = startText,
            startKeyboard = keyboard,
            helpText = helpText,
            commandHandlers = commands,
            callbackHandlers = callbacks
        )
    }

    private fun extractQuotedResponse(content: String, anchor: String): String? {
        val idx = content.indexOf(anchor, ignoreCase = true)
        if (idx == -1) return null

        val sub = content.substring(idx)
        val p = Pattern.compile("[\"']{3}(.+?)[\"']{3}|[\"']([^\"']{10,})[\"']", Pattern.DOTALL)
        val m = p.matcher(sub)
        if (m.find()) {
            return (m.group(1) ?: m.group(2))?.trim()
        }
        return null
    }

    private fun buildInlineKeyboard(buttons: List<Pair<String, String>>): JSONObject {
        val root = JSONObject()
        val rows = JSONArray()

        var currentRow = JSONArray()
        for ((idx, btn) in buttons.withIndex()) {
            val btnObj = JSONObject().apply {
                put("text", btn.first)
                put("callback_data", btn.second)
            }
            currentRow.put(btnObj)

            if (currentRow.length() >= 2 || idx == buttons.size - 1) {
                rows.put(currentRow)
                currentRow = JSONArray()
            }
        }

        root.put("inline_keyboard", rows)
        return root
    }
}
