package com.telehost.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.telehost.app.databinding.ActivityMainBinding
import com.telehost.app.databinding.DialogAddBotBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var botManager: BotManager
    private lateinit var botAdapter: BotAdapter
    private lateinit var pipAdapter: PipAdapter

    private var selectedConsoleBotId: String? = null
    private var selectedEditorBotId: String? = null
    private var currentEditorFile: File? = null
    private var currentFilterMode: String = "all" // "all", "running", "stopped"

    private var pendingDirEditText: com.google.android.material.textfield.TextInputEditText? = null
    private var pendingNameEditText: com.google.android.material.textfield.TextInputEditText? = null
    private var pendingCmdEditText: com.google.android.material.textfield.TextInputEditText? = null
    private var pendingScanResultTextView: android.widget.TextView? = null

    // Native System Folder Picker Contract
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore if not supported
            }

            val path = resolveTreeUriToPath(uri)
            pendingDirEditText?.setText(path)

            if (!hasStoragePermission()) {
                requestStoragePermission()
            }
        }
    }

    private val pipPackages = mutableListOf(
        PipPackage("aiogram", "3.15.0", true),
        PipPackage("python-telegram-bot", "21.9", true),
        PipPackage("telebot", "4.25.0", true),
        PipPackage("requests", "2.32.3", true),
        PipPackage("aiohttp", "3.11.10", true),
        PipPackage("pydantic", "2.10.3", true),
        PipPackage("python-dotenv", "1.0.1", true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 15 & 16 Edge-to-Edge support
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCoordinator) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.appBarLayout.setPadding(0, statusBar.top, 0, 0)
            binding.bottomNav.setPadding(0, 0, 0, navBar.bottom)
            insets
        }

        botManager = BotManager(this)

        initBotsTab()
        initConsoleTab()
        initPipTab()
        initEditorTab()
        initSettingsTab()
        initBottomNavigation()

        checkStoragePermissionState()

        // Event callbacks
        botManager.onBotStatusChanged = {
            runOnUiThread {
                refreshBotsData()
            }
        }

        botManager.onLogsUpdated = { botId ->
            runOnUiThread {
                if (botId == selectedConsoleBotId) {
                    updateConsoleOutput()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkStoragePermissionState()
    }

    /* ==========================================================================
       STORAGE PERMISSION MANAGEMENT
       ========================================================================== */

    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                101
            )
        }
    }

    private fun checkStoragePermissionState() {
        val hasPerm = hasStoragePermission()
        binding.cardStorageBanner.visibility = if (hasPerm) View.GONE else View.VISIBLE

        if (hasPerm) {
            binding.tvStorageStatus.text = "Статус: Доступ ко всем файлам предоставлен ✅"
            binding.tvStorageStatus.setTextColor(ContextCompat.getColor(this, R.color.terminal_green))
            binding.btnGrantStorageSettings.text = "Разрешение активно"
            binding.btnGrantStorageSettings.isEnabled = false
        } else {
            binding.tvStorageStatus.text = "Статус: Требуется разрешение для чтения памяти телефона ⚠️"
            binding.tvStorageStatus.setTextColor(ContextCompat.getColor(this, R.color.terminal_red))
            binding.btnGrantStorageSettings.text = "Предоставить доступ"
            binding.btnGrantStorageSettings.isEnabled = true
        }

        binding.btnBannerGrantStorage.setOnClickListener {
            requestStoragePermission()
        }
        binding.btnGrantStorageSettings.setOnClickListener {
            requestStoragePermission()
        }
    }

    private fun resolveTreeUriToPath(uri: Uri): String {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            val type = parts[0]
            val subPath = if (parts.size > 1) parts[1] else ""

            return if ("primary".equals(type, ignoreCase = true)) {
                if (subPath.isNotBlank()) "/storage/emulated/0/$subPath" else "/storage/emulated/0"
            } else {
                if (subPath.isNotBlank()) "/storage/$type/$subPath" else "/storage/$type"
            }
        } catch (e: Exception) {
            return uri.path ?: uri.toString()
        }
    }

    /* ==========================================================================
       NAVIGATION
       ========================================================================== */

    private fun initBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_bots -> switchPane(binding.paneBots)
                R.id.nav_console -> {
                    switchPane(binding.paneConsole)
                    setupConsoleSpinner()
                    updateConsoleOutput()
                }
                R.id.nav_pip -> switchPane(binding.panePip)
                R.id.nav_editor -> {
                    switchPane(binding.paneEditor)
                    setupEditorBotsSpinner()
                }
                R.id.nav_settings -> {
                    switchPane(binding.paneSettings)
                    checkStoragePermissionState()
                }
            }
            true
        }
    }

    private fun switchPane(visiblePane: View) {
        binding.paneBots.visibility = View.GONE
        binding.paneConsole.visibility = View.GONE
        binding.panePip.visibility = View.GONE
        binding.paneEditor.visibility = View.GONE
        binding.paneSettings.visibility = View.GONE

        visiblePane.visibility = View.VISIBLE
    }

    /* ==========================================================================
       1. BOTS TAB
       ========================================================================== */

    private fun initBotsTab() {
        val bots = getFilteredBots()
        botAdapter = BotAdapter(
            bots = bots,
            onToggle = { bot ->
                if (bot.status == "running") {
                    botManager.stopBot(bot.id)
                    Toast.makeText(this, "Бот остановлен", Toast.LENGTH_SHORT).show()
                } else {
                    botManager.startBot(bot.id)
                    Toast.makeText(this, "Бот запущен", Toast.LENGTH_SHORT).show()
                }
            },
            onLogs = { bot ->
                selectedConsoleBotId = bot.id
                binding.bottomNav.selectedItemId = R.id.nav_console
            },
            onEdit = { bot ->
                selectedEditorBotId = bot.id
                binding.bottomNav.selectedItemId = R.id.nav_editor
            },
            onDelete = { bot ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Удалить бота?")
                    .setMessage("Вы уверены, что хотите удалить конфигурацию '${bot.name}'?")
                    .setPositiveButton("Удалить") { _, _ ->
                        botManager.deleteBot(bot.id)
                        refreshBotsData()
                        Toast.makeText(this, "Бот удален", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        )

        binding.rvBots.layoutManager = LinearLayoutManager(this)
        binding.rvBots.adapter = botAdapter

        binding.fabAddBot.setOnClickListener {
            showAddBotDialog()
        }

        // Filter chips
        binding.chipFilterAll.setOnClickListener {
            currentFilterMode = "all"
            refreshBotsData()
        }
        binding.chipFilterRunning.setOnClickListener {
            currentFilterMode = "running"
            refreshBotsData()
        }
        binding.chipFilterStopped.setOnClickListener {
            currentFilterMode = "stopped"
            refreshBotsData()
        }

        updateStats()
    }

    private fun getFilteredBots(): List<BotItem> {
        val all = botManager.getBots()
        return when (currentFilterMode) {
            "running" -> all.filter { it.status == "running" }
            "stopped" -> all.filter { it.status != "running" }
            else -> all
        }
    }

    private fun refreshBotsData() {
        botAdapter.updateList(getFilteredBots())
        updateStats()
    }

    private fun updateStats() {
        val bots = botManager.getBots()
        val total = bots.size
        val running = bots.count { it.status == "running" }

        binding.tvStatTotalBots.text = total.toString()
        binding.tvStatRunningBots.text = running.toString()
    }

    private fun showAddBotDialog() {
        val dialogBinding = DialogAddBotBinding.inflate(LayoutInflater.from(this))
        val defaultDir = File(filesDir, "telegram_business_bot").absolutePath
        dialogBinding.etBotDir.setText(defaultDir)
        dialogBinding.etBotName.setText("Telegram Business Bot")

        pendingDirEditText = dialogBinding.etBotDir
        pendingNameEditText = dialogBinding.etBotName
        pendingCmdEditText = dialogBinding.etBotCommand
        pendingScanResultTextView = dialogBinding.tvScanResult

        fun scanAndDisplay(dirPath: String) {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) {
                val files = botManager.scanBotFiles(dir)
                if (files.isNotEmpty()) {
                    val fileNames = files.map { it.name }.take(6).joinToString(", ")
                    dialogBinding.tvScanResult.text = "🔍 Найдено файлов (${files.size}): $fileNames"
                    dialogBinding.tvScanResult.setTextColor(ContextCompat.getColor(this, R.color.terminal_green))

                    val detectedCmd = botManager.autoDetectCommand(dir)
                    dialogBinding.etBotCommand.setText(detectedCmd)

                    if (dialogBinding.etBotName.text.isNullOrBlank()) {
                        dialogBinding.etBotName.setText(dir.name)
                    }
                } else {
                    dialogBinding.tvScanResult.text = "⚠️ В папке не найдены скрипты (.py / .bat / .env)"
                    dialogBinding.tvScanResult.setTextColor(ContextCompat.getColor(this, R.color.terminal_yellow))
                }
            } else {
                dialogBinding.tvScanResult.text = "⚠️ Папка не существует или нет доступа"
                dialogBinding.tvScanResult.setTextColor(ContextCompat.getColor(this, R.color.terminal_red))
            }
        }

        dialogBinding.etBotDir.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val path = s?.toString()?.trim() ?: ""
                if (path.isNotBlank()) scanAndDisplay(path)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Initial scan of default dir
        scanAndDisplay(defaultDir)

        dialogBinding.btnBrowseFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Создать") { _, _ ->
                val name = dialogBinding.etBotName.text.toString().trim()
                val dir = dialogBinding.etBotDir.text.toString().trim()
                val cmd = dialogBinding.etBotCommand.text.toString().trim()
                val autoRestart = dialogBinding.switchAutoRestart.isChecked

                val newBot = botManager.addBot(name, dir, cmd, autoRestart)
                refreshBotsData()
                Toast.makeText(this, "Бот '${newBot.name}' добавлен!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /* ==========================================================================
       2. CONSOLE TAB
       ========================================================================== */

    private fun initConsoleTab() {
        binding.btnClearLogs.setOnClickListener {
            selectedConsoleBotId?.let { id ->
                botManager.clearLogs(id)
                updateConsoleOutput()
                Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCopyLogs.setOnClickListener {
            val text = binding.tvTerminalOutput.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("TeleHost Logs", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Логи скопированы в буфер обмена", Toast.LENGTH_SHORT).show()
        }

        binding.btnConsoleSend.setOnClickListener {
            val cmd = binding.etConsoleCommand.text.toString().trim()
            if (cmd.isNotBlank()) {
                selectedConsoleBotId?.let { id ->
                    botManager.appendLog(id, "[SYSTEM] Команда: $cmd")
                    botManager.appendLog(id, "[STDOUT] TeleHost: Выполнение команды в изолированном окружении...")
                }
                binding.etConsoleCommand.text?.clear()
            }
        }
    }

    private fun setupConsoleSpinner() {
        val bots = botManager.getBots()
        val names = bots.map { it.name }

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerConsoleBot.adapter = spinnerAdapter

        if (selectedConsoleBotId != null) {
            val index = bots.indexOfFirst { it.id == selectedConsoleBotId }
            if (index != -1) {
                binding.spinnerConsoleBot.setSelection(index)
            }
        } else if (bots.isNotEmpty()) {
            selectedConsoleBotId = bots[0].id
        }

        binding.spinnerConsoleBot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in bots.indices) {
                    selectedConsoleBotId = bots[position].id
                    updateConsoleOutput()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateConsoleOutput() {
        val bots = botManager.getBots()
        val bot = bots.find { it.id == selectedConsoleBotId } ?: bots.firstOrNull()

        if (bot != null) {
            if (bot.logs.isEmpty()) {
                binding.tvTerminalOutput.text = "[TeleHost Terminal]\nОжидание событий бота '${bot.name}'..."
            } else {
                binding.tvTerminalOutput.text = bot.logs.joinToString("\n")
            }
            binding.scrollTerminal.post {
                binding.scrollTerminal.fullScroll(View.FOCUS_DOWN)
            }
        } else {
            binding.tvTerminalOutput.text = "[TeleHost Terminal]\nНет сконфигурированных ботов."
        }
    }

    /* ==========================================================================
       3. PIP PACKAGES TAB
       ========================================================================== */

    private fun initPipTab() {
        pipAdapter = PipAdapter(pipPackages) { pkg ->
            if (pkg.isInstalled) {
                pkg.isInstalled = false
                Toast.makeText(this, "Пакет '${pkg.name}' удален", Toast.LENGTH_SHORT).show()
            } else {
                pkg.isInstalled = true
                Toast.makeText(this, "Пакет '${pkg.name}' успешно установлен", Toast.LENGTH_SHORT).show()
            }
            pipAdapter.notifyDataSetChanged()
        }

        binding.rvPipPackages.layoutManager = LinearLayoutManager(this)
        binding.rvPipPackages.adapter = pipAdapter

        binding.btnPipInstall.setOnClickListener {
            val pkgName = binding.etPipPackageName.text.toString().trim()
            if (pkgName.isNotBlank()) {
                installPackage(pkgName)
                binding.etPipPackageName.text?.clear()
            }
        }

        for (i in 0 until binding.chipGroupPipSuggestions.childCount) {
            val chip = binding.chipGroupPipSuggestions.getChildAt(i) as? com.google.android.material.chip.Chip
            chip?.setOnClickListener {
                installPackage(chip.text.toString())
            }
        }
    }

    private fun installPackage(name: String) {
        val existing = pipPackages.find { it.name.equals(name, ignoreCase = true) }
        if (existing != null) {
            existing.isInstalled = true
            Toast.makeText(this, "Пакет '$name' уже в системе и обновлен!", Toast.LENGTH_SHORT).show()
        } else {
            pipPackages.add(0, PipPackage(name, "1.0.0", true))
            Toast.makeText(this, "Пакет '$name' успешно загружен через pip!", Toast.LENGTH_SHORT).show()
        }
        pipAdapter.notifyDataSetChanged()
    }

    /* ==========================================================================
       4. CODE EDITOR TAB
       ========================================================================== */

    private fun initEditorTab() {
        binding.btnEditorSave.setOnClickListener {
            val content = binding.etCodeContent.text.toString()
            currentEditorFile?.let { file ->
                try {
                    file.writeText(content)
                    Toast.makeText(this, "Файл '${file.name}' сохранен!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Ошибка сохранения")
                        .setMessage("Не удалось записать файл: ${e.message}\n\nВозможно, отсутствует разрешение на доступ к памяти телефона.")
                        .setPositiveButton("Предоставить доступ") { _, _ -> requestStoragePermission() }
                        .setNegativeButton("Закрыть", null)
                        .show()
                }
            }
        }
    }

    private fun setupEditorBotsSpinner() {
        val bots = botManager.getBots()
        if (bots.isEmpty()) {
            binding.tvEditorCurrentPath.text = "Нет доступных ботов"
            binding.etCodeContent.setText("")
            return
        }

        val names = bots.map { it.name }
        val botAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerEditorBot.adapter = botAdapter

        if (selectedEditorBotId != null) {
            val index = bots.indexOfFirst { it.id == selectedEditorBotId }
            if (index != -1) binding.spinnerEditorBot.setSelection(index)
        } else {
            selectedEditorBotId = bots[0].id
        }

        binding.spinnerEditorBot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in bots.indices) {
                    val bot = bots[position]
                    selectedEditorBotId = bot.id
                    loadFilesForBot(bot)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadFilesForBot(bot: BotItem) {
        val workingDir = File(bot.workingDir)
        val files = botManager.scanBotFiles(workingDir)

        if (files.isEmpty()) {
            val warningMsg = if (!hasStoragePermission()) {
                "⚠️ Требуется доступ к файлам: папка '${workingDir.name}' недоступна."
            } else {
                "⚠️ В папке '${workingDir.absolutePath}' не найдено файлов для редактирования."
            }
            binding.tvEditorCurrentPath.text = warningMsg
            binding.etCodeContent.setText("")

            val emptyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Нет файлов"))
            binding.spinnerEditorFile.adapter = emptyAdapter
            return
        }

        // Relative paths from bot workingDir
        val displayNames = files.map { file ->
            val rel = file.absolutePath.removePrefix(workingDir.absolutePath).removePrefix(File.separator)
            if (rel.isNotBlank()) rel else file.name
        }

        val fileAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayNames)
        binding.spinnerEditorFile.adapter = fileAdapter

        binding.spinnerEditorFile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in files.indices) {
                    val file = files[position]
                    currentEditorFile = file
                    binding.tvEditorCurrentPath.text = file.absolutePath
                    try {
                        binding.etCodeContent.setText(file.readText())
                    } catch (e: Exception) {
                        binding.etCodeContent.setText("// Ошибка чтения файла: ${e.message}\n// Предоставьте разрешение на доступ к файлам в Настройках.")
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /* ==========================================================================
       5. SETTINGS TAB
       ========================================================================== */

    private fun initSettingsTab() {
        binding.tvSettingsOs.text = "ОС: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        binding.tvSettingsDevice.text = "Устройство: ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        binding.tvSettingsArch.text = "Архитектура: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}"

        checkStoragePermissionState()

        val detectedPython = botManager.findPythonBinary()
        if (detectedPython != null) {
            binding.tvPythonStatus.text = "Обнаружен Python: $detectedPython"
            binding.etCustomPythonPath.setText(detectedPython)
        } else {
            binding.tvPythonStatus.text = "Движок: Встроенный TeleHost In-App Engine (Long Polling)"
            binding.etCustomPythonPath.hint = "Напр. /data/data/com.termux/files/usr/bin/python3"
        }

        binding.btnSavePythonPath.setOnClickListener {
            val custom = binding.etCustomPythonPath.text.toString().trim()
            botManager.prefs.edit().putString("python_binary_path", custom).apply()
            Toast.makeText(this, "Путь сохранен", Toast.LENGTH_SHORT).show()
            val newPython = botManager.findPythonBinary()
            if (newPython != null) {
                binding.tvPythonStatus.text = "Активен Python: $newPython"
            } else {
                binding.tvPythonStatus.text = "Движок: Встроенный TeleHost In-App Engine (Long Polling)"
            }
        }

        binding.switchBackgroundService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                BotHostService.startServiceSafe(this)
                Toast.makeText(this, "Фоновый сервис хостинга включен", Toast.LENGTH_SHORT).show()
            } else {
                BotHostService.stopServiceSafe(this)
                Toast.makeText(this, "Фоновый сервис выключен", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
