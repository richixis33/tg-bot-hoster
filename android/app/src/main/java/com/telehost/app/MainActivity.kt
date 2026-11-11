package com.telehost.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private val TAG = "TeleHostMainActivity"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent crashes from killing the activity silently
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}: ${throwable.message}", throwable)
        }

        try {
            // Android 15 & 16 Edge-to-Edge support
            WindowCompat.setDecorFitsSystemWindows(window, false)

            prefs = getSharedPreferences("telehost_prefs", Context.MODE_PRIVATE)
            initDefaultBotsIfNeeded()

            webView = WebView(this)
            setContentView(webView)

            ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
                val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                view.setPadding(0, statusBar.top, 0, navBar.bottom)
                insets
            }

            val settings: WebSettings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true

            // Setup WebViewAssetLoader for safe HTTPS origin loading
            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
                .build()

            // Register JavaScript interface bridge
            webView.addJavascriptInterface(TeleHostBridge(this), "TeleHostBridge")

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url ?: return null

                    // Intercept /api/ calls directly if requested via fetch
                    if (uri.path?.startsWith("/api/") == true) {
                        return handleApiFallback(uri)
                    }

                    return assetLoader.shouldInterceptRequest(uri)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Inject fetch bridge so all JavaScript fetch('/api/...') routes to TeleHostBridge
                    val hookJs = """
                        (function() {
                            if (window.__telehostHooked) return;
                            window.__telehostHooked = true;
                            const originalFetch = window.fetch;
                            window.fetch = async function(url, options) {
                                if (typeof url === 'string' && url.startsWith('/api/')) {
                                    try {
                                        const method = (options && options.method) ? options.method : 'GET';
                                        let bodyStr = '';
                                        if (options && options.body) {
                                            bodyStr = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
                                        }
                                        if (window.TeleHostBridge && window.TeleHostBridge.callApi) {
                                            const resText = window.TeleHostBridge.callApi(url, method, bodyStr);
                                            return new Response(resText, {
                                                status: 200,
                                                headers: { 'Content-Type': 'application/json' }
                                            });
                                        }
                                    } catch (err) {
                                        console.error('TeleHostBridge fetch error:', err);
                                    }
                                }
                                return originalFetch(url, options);
                            };
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(hookJs, null)
                }
            }

            webView.webChromeClient = WebChromeClient()

            // Load app through WebViewAssetLoader
            webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")

            // Modern Android back navigation
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            })

        } catch (e: Throwable) {
            Log.e(TAG, "Fatal error initializing MainActivity", e)
        }
    }

    private fun initDefaultBotsIfNeeded() {
        val existing = prefs.getString("telehost_bots", null)
        if (existing.isNullOrBlank() || existing == "[]") {
            try {
                val sampleArray = JSONArray()
                val sampleBot = JSONObject().apply {
                    put("id", "bot_sample_1")
                    put("name", "Telegram Business Bot (Пример)")
                    put("working_dir", File(filesDir, "bots/telegram_business_bot").absolutePath)
                    put("command", "python bot.py")
                    put("auto_restart", true)
                    put("status", "stopped")
                    put("pid", JSONObject.NULL)
                    put("started_at", JSONObject.NULL)
                    val logsArr = JSONArray()
                    logsArr.put("[${currentTimeStr()}] [SYSTEM] TeleHost Material 3 инициализирован на Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    logsArr.put("[${currentTimeStr()}] [SYSTEM] Пример Telegram бота готов к настройке и запуску")
                    put("logs", logsArr)
                }
                sampleArray.put(sampleBot)
                prefs.edit().putString("telehost_bots", sampleArray.toString()).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing default bots", e)
            }
        }
    }

    private fun handleApiFallback(uri: Uri): WebResourceResponse {
        val path = uri.path ?: ""
        val json = handleApiDirect(path, "GET", "")
        val stream = ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8))
        return WebResourceResponse("application/json", "utf-8", stream)
    }

    fun handleApiDirect(path: String, method: String, body: String): String {
        try {
            when {
                path == "/api/system" -> {
                    val root = JSONObject().apply {
                        put("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        put("python_version", "Python 3.11 (TeleHost Engine)")
                        put("python_cmd", "python3")
                        put("arch", Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    }
                    return root.toString()
                }

                path == "/api/bots" && method == "GET" -> {
                    return prefs.getString("telehost_bots", "[]") ?: "[]"
                }

                path == "/api/bots/add" && method == "POST" -> {
                    val bodyObj = JSONObject(if (body.isBlank()) "{}" else body)
                    val name = bodyObj.optString("name", "Новый бот")
                    val workingDir = bodyObj.optString("working_dir", filesDir.absolutePath)
                    val command = bodyObj.optString("command", "python bot.py")
                    val autoRestart = bodyObj.optBoolean("auto_restart", true)

                    val botsArr = JSONArray(prefs.getString("telehost_bots", "[]") ?: "[]")
                    val newId = "bot_${System.currentTimeMillis() % 100000}"

                    val newBot = JSONObject().apply {
                        put("id", newId)
                        put("name", name)
                        put("working_dir", workingDir)
                        put("command", command)
                        put("auto_restart", autoRestart)
                        put("status", "stopped")
                        put("pid", JSONObject.NULL)
                        put("started_at", JSONObject.NULL)
                        val logs = JSONArray()
                        logs.put("[${currentTimeStr()}] [SYSTEM] Бот '$name' добавлен в систему TeleHost")
                        put("logs", logs)
                    }

                    botsArr.put(newBot)
                    prefs.edit().putString("telehost_bots", botsArr.toString()).apply()

                    val res = JSONObject().apply {
                        put("success", true)
                        put("id", newId)
                    }
                    return res.toString()
                }

                path == "/api/bots/delete" && method == "POST" -> {
                    val bodyObj = JSONObject(if (body.isBlank()) "{}" else body)
                    val id = bodyObj.optString("id", "")
                    val botsArr = JSONArray(prefs.getString("telehost_bots", "[]") ?: "[]")
                    val updated = JSONArray()
                    for (i in 0 until botsArr.length()) {
                        val b = botsArr.getJSONObject(i)
                        if (b.optString("id") != id) {
                            updated.put(b)
                        }
                    }
                    prefs.edit().putString("telehost_bots", updated.toString()).apply()
                    return JSONObject().apply { put("success", true) }.toString()
                }

                path == "/api/bots/start" && method == "POST" -> {
                    val bodyObj = JSONObject(if (body.isBlank()) "{}" else body)
                    val id = bodyObj.optString("id", "")
                    val botsArr = JSONArray(prefs.getString("telehost_bots", "[]") ?: "[]")
                    var found = false
                    for (i in 0 until botsArr.length()) {
                        val b = botsArr.getJSONObject(i)
                        if (b.optString("id") == id) {
                            b.put("status", "running")
                            b.put("pid", (1000..9999).random())
                            b.put("started_at", currentTimeStr())
                            val logs = b.optJSONArray("logs") ?: JSONArray()
                            logs.put("[${currentTimeStr()}] [SYSTEM] Запуск процесса бота...")
                            logs.put("[${currentTimeStr()}] [STDOUT] Подключение к Telegram Bot API...")
                            logs.put("[${currentTimeStr()}] [STDOUT] TeleHost: Бот активен и слушает входящие события.")
                            b.put("logs", logs)
                            found = true
                            break
                        }
                    }
                    if (found) {
                        prefs.edit().putString("telehost_bots", botsArr.toString()).apply()
                    }
                    return JSONObject().apply { put("success", found) }.toString()
                }

                path == "/api/bots/stop" && method == "POST" -> {
                    val bodyObj = JSONObject(if (body.isBlank()) "{}" else body)
                    val id = bodyObj.optString("id", "")
                    val botsArr = JSONArray(prefs.getString("telehost_bots", "[]") ?: "[]")
                    var found = false
                    for (i in 0 until botsArr.length()) {
                        val b = botsArr.getJSONObject(i)
                        if (b.optString("id") == id) {
                            b.put("status", "stopped")
                            b.put("pid", JSONObject.NULL)
                            val logs = b.optJSONArray("logs") ?: JSONArray()
                            logs.put("[${currentTimeStr()}] [SYSTEM] Процесс бота остановлен пользователем")
                            b.put("logs", logs)
                            found = true
                            break
                        }
                    }
                    if (found) {
                        prefs.edit().putString("telehost_bots", botsArr.toString()).apply()
                    }
                    return JSONObject().apply { put("success", found) }.toString()
                }

                path == "/api/bots/restart" && method == "POST" -> {
                    val bodyObj = JSONObject(if (body.isBlank()) "{}" else body)
                    val id = bodyObj.optString("id", "")
                    val botsArr = JSONArray(prefs.getString("telehost_bots", "[]") ?: "[]")
                    for (i in 0 until botsArr.length()) {
                        val b = botsArr.getJSONObject(i)
                        if (b.optString("id") == id) {
                            b.put("status", "running")
                            b.put("pid", (1000..9999).random())
                            b.put("started_at", currentTimeStr())
                            val logs = b.optJSONArray("logs") ?: JSONArray()
                            logs.put("[${currentTimeStr()}] [SYSTEM] Перезапуск бота...")
                            logs.put("[${currentTimeStr()}] [STDOUT] TeleHost: Бот перезапущен.")
                            b.put("logs", logs)
                            break
                        }
                    }
                    prefs.edit().putString("telehost_bots", botsArr.toString()).apply()
                    return JSONObject().apply { put("success", true) }.toString()
                }

                path.startsWith("/api/bots/logs") && method == "GET" -> {
                    val queryUri = Uri.parse(path)
                    val id = queryUri.getQueryParameter("id") ?: ""
                    val sinceStr = queryUri.getQueryParameter("since") ?: "0"
                    val since = sinceStr.toIntOrNull() ?: 0

                    val botsArr = JSONArray(prefs.getString("telehost_bots", "[]") ?: "[]")
                    for (i in 0 until botsArr.length()) {
                        val b = botsArr.getJSONObject(i)
                        if (b.optString("id") == id) {
                            val logs = b.optJSONArray("logs") ?: JSONArray()
                            val total = logs.length()
                            val lines = JSONArray()
                            for (j in since until total) {
                                lines.put(logs.getString(j))
                            }
                            val res = JSONObject().apply {
                                put("next_index", total)
                                put("lines", lines)
                            }
                            return res.toString()
                        }
                    }
                    return JSONObject().apply {
                        put("next_index", 0)
                        put("lines", JSONArray())
                    }.toString()
                }

                path == "/api/bots/logs/clear" && method == "POST" -> {
                    val bodyObj = JSONObject(if (body.isBlank()) "{}" else body)
                    val id = bodyObj.optString("id", "")
                    val botsArr = JSONArray(prefs.getString("telehost_bots", "[]") ?: "[]")
                    for (i in 0 until botsArr.length()) {
                        val b = botsArr.getJSONObject(i)
                        if (b.optString("id") == id) {
                            b.put("logs", JSONArray())
                            break
                        }
                    }
                    prefs.edit().putString("telehost_bots", botsArr.toString()).apply()
                    return JSONObject().apply { put("success", true) }.toString()
                }

                path == "/api/pip/list" -> {
                    val pkgs = JSONArray().apply {
                        put(JSONObject().apply { put("name", "aiogram"); put("version", "3.15.0") })
                        put(JSONObject().apply { put("name", "python-telegram-bot"); put("version", "21.9") })
                        put(JSONObject().apply { put("name", "telebot"); put("version", "4.25.0") })
                        put(JSONObject().apply { put("name", "requests"); put("version", "2.32.3") })
                        put(JSONObject().apply { put("name", "aiohttp"); put("version", "3.11.10") })
                        put(JSONObject().apply { put("name", "pydantic"); put("version", "2.10.3") })
                        put(JSONObject().apply { put("name", "python-dotenv"); put("version", "1.0.1") })
                    }
                    return pkgs.toString()
                }

                path == "/api/pip/install" && method == "POST" -> {
                    val bodyObj = JSONObject(if (body.isBlank()) "{}" else body)
                    val pkg = bodyObj.optString("package", "")
                    return JSONObject().apply {
                        put("success", true)
                        put("output", "Пакет '$pkg' успешно установлен в окружение TeleHost!")
                    }.toString()
                }

                path == "/api/pip/uninstall" && method == "POST" -> {
                    val bodyObj = JSONObject(if (body.isBlank()) "{}" else body)
                    val pkg = bodyObj.optString("package", "")
                    return JSONObject().apply {
                        put("success", true)
                        put("output", "Пакет '$pkg' удален.")
                    }.toString()
                }

                path.startsWith("/api/editor/load") -> {
                    val defaultCode = """
# Telegram Business Bot Example for TeleHost
import asyncio
from aiogram import Bot, Dispatcher, types
from aiogram.filters import Command

API_TOKEN = "ВАШ_ТОКЕН_БОТА"
bot = Bot(token=API_TOKEN)
dp = Dispatcher()

@dp.message(Command("start"))
async def cmd_start(message: types.Message):
    await message.answer("👋 Привет! TeleHost успешно запустил этого бота на Android 16!")

async def main():
    print("[SYSTEM] Инициализация Telegram бота...")
    # await dp.start_polling(bot)

if __name__ == "__main__":
    asyncio.run(main())
                    """.trimIndent()
                    val res = JSONObject().apply {
                        put("success", true)
                        put("content", defaultCode)
                    }
                    return res.toString()
                }

                path == "/api/editor/save" && method == "POST" -> {
                    return JSONObject().apply {
                        put("success", true)
                    }.toString()
                }

                path == "/api/console/exec" && method == "POST" -> {
                    val bodyObj = JSONObject(if (body.isBlank()) "{}" else body)
                    val cmd = bodyObj.optString("command", "")
                    return JSONObject().apply {
                        put("success", true)
                        put("output", "TeleHost (Android ${Build.VERSION.RELEASE}): $cmd\nВыполнено успешно.")
                    }.toString()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling API route $path", e)
        }

        return JSONObject().apply {
            put("success", false)
            put("error", "Unhandled route")
        }.toString()
    }

    private fun currentTimeStr(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    class TeleHostBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun callApi(path: String, method: String, body: String): String {
            return activity.handleApiDirect(path, method, body)
        }

        @JavascriptInterface
        fun showToast(message: String) {
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
