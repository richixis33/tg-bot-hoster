/**
 * TeleHost — Material Design 3 Frontend Controller
 */

// Hook window.fetch for TeleHost Android Bridge if running in APK
(function() {
    if (typeof window !== 'undefined' && window.TeleHostBridge) {
        const originalFetch = window.fetch;
        window.fetch = async function(url, options) {
            if (typeof url === 'string' && url.startsWith('/api/')) {
                try {
                    const method = (options && options.method) ? options.method : 'GET';
                    let bodyStr = '';
                    if (options && options.body) {
                        bodyStr = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
                    }
                    const resText = window.TeleHostBridge.callApi(url, method, bodyStr);
                    return new Response(resText, {
                        status: 200,
                        headers: { 'Content-Type': 'application/json' }
                    });
                } catch (e) {
                    console.error('TeleHostBridge fetch error:', e);
                }
            }
            return originalFetch(url, options);
        };
    }
})();

const STATE = {
    bots: [],
    selectedConsoleBotId: null,
    selectedEditorBotId: null,
    installedPackages: [],
    lastLogIndex: 0,
    isStreamingLogs: false,
    logPollingTimer: null,
    systemInfo: null,
};

// Initialize Application
document.addEventListener('DOMContentLoaded', () => {
    initNavigation();
    initThemeManager();
    initModals();
    initBotActions();
    initConsole();
    initPipManager();
    initFileEditor();
    initSettings();

    // Initial Data Fetch
    fetchSystemInfo();
    fetchBots();
    fetchPipPackages();

    // Start auto polling for status every 2 seconds
    setInterval(fetchBotsSilent, 2000);
});

/* ==========================================================================
   Navigation
   ========================================================================== */

function initNavigation() {
    const navItems = document.querySelectorAll('.nav-item');
    const panes = document.querySelectorAll('.tab-pane');
    const fab = document.getElementById('mainFab');

    navItems.forEach(item => {
        item.addEventListener('click', () => {
            const tab = item.getAttribute('data-tab');

            navItems.forEach(i => i.classList.remove('active'));
            panes.forEach(p => p.classList.remove('active'));

            item.classList.add('active');
            const activePane = document.getElementById(`pane-${tab}`);
            if (activePane) activePane.classList.add('active');

            // Toggle FAB visibility (only show on dashboard)
            if (fab) {
                fab.style.display = (tab === 'dashboard') ? 'flex' : 'none';
            }

            // Tab-specific activations
            if (tab === 'console') {
                startLogStreaming();
            } else {
                stopLogStreaming();
            }

            if (tab === 'editor') {
                loadFileForEditor();
            }
        });
    });
}

/* ==========================================================================
   Theme & Styling Manager
   ========================================================================== */

function initThemeManager() {
    const paletteCircles = document.querySelectorAll('.palette-circle');
    const darkModeToggle = document.getElementById('settingDarkMode');
    const themeToggleBtn = document.getElementById('themeToggleBtn');

    // Load saved theme
    const savedPalette = localStorage.getItem('telehost_palette') || 'theme-violet';
    document.body.className = savedPalette;

    paletteCircles.forEach(circle => {
        if (circle.getAttribute('data-theme') === savedPalette) {
            circle.classList.add('active');
        } else {
            circle.classList.remove('active');
        }

        circle.addEventListener('click', () => {
            const theme = circle.getAttribute('data-theme');
            document.body.className = theme;
            localStorage.setItem('telehost_palette', theme);

            paletteCircles.forEach(c => c.classList.remove('active'));
            circle.classList.add('active');
            showSnackbar(`Цветовая схема переключена: ${theme.replace('theme-', '')}`);
        });
    });

    if (darkModeToggle) {
        darkModeToggle.addEventListener('change', () => {
            if (darkModeToggle.checked) {
                document.documentElement.classList.remove('light');
                document.documentElement.classList.add('dark');
            } else {
                document.documentElement.classList.remove('dark');
                document.documentElement.classList.add('light');
            }
        });
    }

    if (themeToggleBtn) {
        themeToggleBtn.addEventListener('click', () => {
            const themes = ['theme-violet', 'theme-blue', 'theme-emerald', 'theme-amber', 'theme-rose'];
            const current = document.body.className.split(' ')[0] || 'theme-violet';
            const nextIndex = (themes.indexOf(current) + 1) % themes.length;
            const nextTheme = themes[nextIndex];
            document.body.className = nextTheme;
            localStorage.setItem('telehost_palette', nextTheme);

            paletteCircles.forEach(c => {
                c.classList.toggle('active', c.getAttribute('data-theme') === nextTheme);
            });
            showSnackbar(`Тема: ${nextTheme.replace('theme-', '')}`);
        });
    }
}

/* ==========================================================================
   Modals & Dialogs
   ========================================================================== */

function initModals() {
    const modal = document.getElementById('addBotModal');
    const openBtn = document.getElementById('openAddBotModalBtn');
    const fabBtn = document.getElementById('mainFab');
    const closeBtns = document.querySelectorAll('.close-modal-btn');
    const saveBtn = document.getElementById('saveNewBotBtn');

    const openModal = () => {
        modal.classList.add('open');
        document.getElementById('botNameInput').focus();
    };

    const closeModal = () => {
        modal.classList.remove('open');
    };

    if (openBtn) openBtn.addEventListener('click', openModal);
    if (fabBtn) fabBtn.addEventListener('click', openModal);

    closeBtns.forEach(btn => btn.addEventListener('click', closeModal));

    modal.addEventListener('click', (e) => {
        if (e.target === modal) closeModal();
    });

    if (saveBtn) {
        saveBtn.addEventListener('click', async () => {
            const name = document.getElementById('botNameInput').value.trim();
            const working_dir = document.getElementById('botDirInput').value.trim();
            const command = document.getElementById('botCommandInput').value.trim();
            const auto_restart = document.getElementById('botAutoRestartInput').checked;

            if (!name || !working_dir || !command) {
                showSnackbar('Пожалуйста, заполните все обязательные поля!');
                return;
            }

            try {
                const res = await fetch('/api/bots/add', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name, working_dir, command, auto_restart })
                });

                if (res.ok) {
                    showSnackbar(`Бот "${name}" успешно добавлен!`);
                    closeModal();
                    // Reset fields
                    document.getElementById('botNameInput').value = '';
                    document.getElementById('botDirInput').value = '';
                    document.getElementById('botCommandInput').value = '';
                    fetchBots();
                } else {
                    showSnackbar('Ошибка при создании бота.');
                }
            } catch (err) {
                showSnackbar(`Ошибка сети: ${err.message}`);
            }
        });
    }
}

/* ==========================================================================
   Bots Management & Dashboard
   ========================================================================== */

function initBotActions() {
    const refreshBtn = document.getElementById('refreshBotsBtn');
    if (refreshBtn) refreshBtn.addEventListener('click', () => {
        fetchBots();
        showSnackbar('Список ботов обновлен');
    });

    const searchInput = document.getElementById('botSearchInput');
    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            renderBotsGrid(e.target.value.toLowerCase());
        });
    }
}

async function fetchSystemInfo() {
    try {
        const res = await fetch('/api/system');
        if (res.ok) {
            STATE.systemInfo = await res.json();
            document.getElementById('pythonVersionLabel').textContent = STATE.systemInfo.python_version || 'Python 3';
            document.getElementById('metricPythonRuntime').textContent = STATE.systemInfo.python_version || 'Python 3';
        }
    } catch (err) {
        console.error('Failed to fetch system info:', err);
    }
}

async function fetchBots() {
    try {
        const res = await fetch('/api/bots');
        if (res.ok) {
            STATE.bots = await res.json();
            renderBotsGrid();
            updateMetrics();
            updateBotDropdowns();
        }
    } catch (err) {
        console.error('Failed to fetch bots:', err);
    }
}

async function fetchBotsSilent() {
    try {
        const res = await fetch('/api/bots');
        if (res.ok) {
            STATE.bots = await res.json();
            updateMetrics();
            updateBotStatusIndicators();
        }
    } catch (err) {}
}

function updateMetrics() {
    const total = STATE.bots.length;
    const active = STATE.bots.filter(b => b.status === 'running').length;

    document.getElementById('metricTotalBots').textContent = total;
    document.getElementById('metricActiveBots').textContent = active;
    document.getElementById('activeBotsCountLabel').textContent = `${active} работают`;

    const pill = document.getElementById('runningBotsPill');
    if (pill) {
        if (active > 0) {
            pill.className = 'status-pill status-pill-active';
        } else {
            pill.className = 'status-pill';
        }
    }
}

function renderBotsGrid(filterText = '') {
    const container = document.getElementById('botsContainer');
    if (!container) return;

    const filtered = STATE.bots.filter(b => {
        if (!filterText) return true;
        return b.name.toLowerCase().includes(filterText) ||
               b.working_dir.toLowerCase().includes(filterText) ||
               b.command.toLowerCase().includes(filterText);
    });

    if (filtered.length === 0) {
        container.innerHTML = `
            <div class="empty-state" style="grid-column: 1 / -1; text-align: center; padding: 3rem; color: var(--on-surface-variant);">
                <span class="material-symbols-outlined" style="font-size: 48px; opacity: 0.5;">smart_toy</span>
                <p style="margin-top: 10px;">Боты не найдены. Нажмите "Добавить бота", чтобы подключить проект.</p>
            </div>
        `;
        return;
    }

    container.innerHTML = filtered.map(bot => {
        const isRunning = bot.status === 'running';
        const isError = bot.status === 'error';
        const statusClass = isRunning ? 'running' : (isError ? 'error' : 'stopped');
        const statusText = isRunning ? 'РАБОТАЕТ' : (isError ? 'ОШИБКА' : 'ОСТАНОВЛЕН');

        return `
            <div class="bot-card" data-bot-id="${bot.id}">
                <div class="bot-card-header">
                    <div class="bot-title-group">
                        <div class="bot-icon-circle">
                            <span class="material-symbols-outlined">smart_toy</span>
                        </div>
                        <div>
                            <div class="bot-name">${escapeHtml(bot.name)}</div>
                            <span style="font-size: 0.75rem; color: var(--on-surface-variant); font-family: var(--font-mono);">${bot.id}</span>
                        </div>
                    </div>
                    <span class="status-chip ${statusClass}">${statusText}</span>
                </div>

                <div class="bot-card-body">
                    <div class="bot-meta-item">
                        <span class="material-symbols-outlined">folder</span>
                        <span class="bot-meta-val" title="${escapeHtml(bot.working_dir)}">${escapeHtml(bot.working_dir)}</span>
                    </div>
                    <div class="bot-meta-item">
                        <span class="material-symbols-outlined">terminal</span>
                        <span class="bot-meta-val">${escapeHtml(bot.command)}</span>
                    </div>
                    <div class="bot-meta-item">
                        <span class="material-symbols-outlined">info</span>
                        <span class="bot-meta-val">PID: ${bot.pid || '-'} • Uptime: ${formatUptime(bot.started_at)}</span>
                    </div>
                </div>

                <div class="bot-card-actions">
                    <div class="bot-action-buttons">
                        ${!isRunning ? `
                            <button class="md3-button md3-button-filled" onclick="startBot('${bot.id}')" style="padding: 6px 14px; font-size: 0.8rem;">
                                <span class="material-symbols-outlined">play_arrow</span> Старт
                            </button>
                        ` : `
                            <button class="md3-button md3-button-outlined" onclick="stopBot('${bot.id}')" style="padding: 6px 14px; font-size: 0.8rem; color: var(--error); border-color: var(--error);">
                                <span class="material-symbols-outlined">stop</span> Стоп
                            </button>
                        `}
                        <button class="md3-button md3-button-tonal" onclick="restartBot('${bot.id}')" style="padding: 6px 12px; font-size: 0.8rem;" title="Перезапуск">
                            <span class="material-symbols-outlined">refresh</span>
                        </button>
                        <button class="md3-button md3-button-tonal" onclick="openConsoleForBot('${bot.id}')" style="padding: 6px 12px; font-size: 0.8rem;" title="Открыть консоль">
                            <span class="material-symbols-outlined">terminal</span>
                        </button>
                    </div>
                    <button class="icon-button icon-btn-danger" onclick="deleteBot('${bot.id}')" title="Удалить бота">
                        <span class="material-symbols-outlined">delete</span>
                    </button>
                </div>
            </div>
        `;
    }).join('');
}

function updateBotStatusIndicators() {
    STATE.bots.forEach(bot => {
        const card = document.querySelector(`.bot-card[data-bot-id="${bot.id}"]`);
        if (!card) return;

        const isRunning = bot.status === 'running';
        const isError = bot.status === 'error';
        const chip = card.querySelector('.status-chip');
        if (chip) {
            chip.className = `status-chip ${isRunning ? 'running' : (isError ? 'error' : 'stopped')}`;
            chip.textContent = isRunning ? 'РАБОТАЕТ' : (isError ? 'ОШИБКА' : 'ОСТАНОВЛЕН');
        }
    });
}

async function startBot(id) {
    try {
        const res = await fetch('/api/bots/start', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id })
        });
        if (res.ok) {
            showSnackbar('Запуск бота...');
            fetchBots();
        } else {
            showSnackbar('Не удалось запустить бота.');
        }
    } catch (err) {
        showSnackbar(`Ошибка запуска: ${err.message}`);
    }
}

async function stopBot(id) {
    try {
        const res = await fetch('/api/bots/stop', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id })
        });
        if (res.ok) {
            showSnackbar('Бот остановлен.');
            fetchBots();
        } else {
            showSnackbar('Не удалось остановить бота.');
        }
    } catch (err) {
        showSnackbar(`Ошибка остановки: ${err.message}`);
    }
}

async function restartBot(id) {
    try {
        const res = await fetch('/api/bots/restart', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id })
        });
        if (res.ok) {
            showSnackbar('Перезапуск бота...');
            fetchBots();
        }
    } catch (err) {
        showSnackbar(`Ошибка перезапуска: ${err.message}`);
    }
}

async function deleteBot(id) {
    if (!confirm('Вы уверены, что хотите удалить этого бота из списка хостинга?')) return;
    try {
        const res = await fetch('/api/bots/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id })
        });
        if (res.ok) {
            showSnackbar('Бот удален из списка');
            fetchBots();
        }
    } catch (err) {
        showSnackbar(`Ошибка: ${err.message}`);
    }
}

function openConsoleForBot(id) {
    const navItem = document.querySelector('.nav-item[data-tab="console"]');
    if (navItem) navItem.click();

    const select = document.getElementById('consoleBotSelect');
    if (select) {
        select.value = id;
        STATE.selectedConsoleBotId = id;
        STATE.lastLogIndex = 0;
        document.getElementById('terminalOutput').innerHTML = '';
        fetchLogs();
    }
}

/* ==========================================================================
   Console / Terminal Stream
   ========================================================================== */

function initConsole() {
    const select = document.getElementById('consoleBotSelect');
    const clearBtn = document.getElementById('clearConsoleBtn');
    const downloadBtn = document.getElementById('downloadLogsBtn');
    const startBtn = document.getElementById('consoleStartBtn');
    const stopBtn = document.getElementById('consoleStopBtn');
    const restartBtn = document.getElementById('consoleRestartBtn');

    if (select) {
        select.addEventListener('change', () => {
            STATE.selectedConsoleBotId = select.value;
            STATE.lastLogIndex = 0;
            document.getElementById('terminalOutput').innerHTML = '';
            fetchLogs();
        });
    }

    if (clearBtn) {
        clearBtn.addEventListener('click', async () => {
            document.getElementById('terminalOutput').innerHTML = '';
            STATE.lastLogIndex = 0;
            if (STATE.selectedConsoleBotId) {
                await fetch('/api/bots/logs/clear', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ id: STATE.selectedConsoleBotId })
                });
            }
            showSnackbar('Консоль очищена');
        });
    }

    if (downloadBtn) {
        downloadBtn.addEventListener('click', () => {
            const terminal = document.getElementById('terminalOutput');
            const text = terminal.innerText;
            const blob = new Blob([text], { type: 'text/plain' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `bot_log_${STATE.selectedConsoleBotId || 'all'}.txt`;
            a.click();
            URL.revokeObjectURL(url);
        });
    }

    if (startBtn) startBtn.addEventListener('click', () => { if (STATE.selectedConsoleBotId) startBot(STATE.selectedConsoleBotId); });
    if (stopBtn) stopBtn.addEventListener('click', () => { if (STATE.selectedConsoleBotId) stopBot(STATE.selectedConsoleBotId); });
    if (restartBtn) restartBtn.addEventListener('click', () => { if (STATE.selectedConsoleBotId) restartBot(STATE.selectedConsoleBotId); });
}

function startLogStreaming() {
    if (STATE.isStreamingLogs) return;
    STATE.isStreamingLogs = true;
    STATE.logPollingTimer = setInterval(fetchLogs, 800);
}

function stopLogStreaming() {
    STATE.isStreamingLogs = false;
    if (STATE.logPollingTimer) {
        clearInterval(STATE.logPollingTimer);
        STATE.logPollingTimer = null;
    }
}

async function fetchLogs() {
    const botId = STATE.selectedConsoleBotId || (STATE.bots[0] ? STATE.bots[0].id : null);
    if (!botId) return;

    try {
        const res = await fetch(`/api/bots/logs?id=${botId}&since=${STATE.lastLogIndex}`);
        if (res.ok) {
            const data = await res.json();
            if (data.lines && data.lines.length > 0) {
                appendLogsToTerminal(data.lines);
                STATE.lastLogIndex = data.next_index;
            }
        }
    } catch (err) {}
}

function appendLogsToTerminal(lines) {
    const terminal = document.getElementById('terminalOutput');
    const autoScroll = document.getElementById('autoScrollCheck').checked;

    lines.forEach(line => {
        const div = document.createElement('div');
        div.className = 'terminal-line';

        if (line.includes('[ERROR]') || line.includes('Traceback') || line.includes('Error:')) {
            div.classList.add('stderr-line');
        } else if (line.includes('[SYSTEM]')) {
            div.classList.add('system-line');
        } else if (line.includes('[WARN]')) {
            div.classList.add('warn-line');
        } else {
            div.classList.add('stdout-line');
        }

        div.textContent = line;
        terminal.appendChild(div);
    });

    if (autoScroll) {
        terminal.scrollTop = terminal.scrollHeight;
    }
}

/* ==========================================================================
   PIP Package Manager
   ========================================================================== */

function initPipManager() {
    const installBtn = document.getElementById('pipInstallBtn');
    const installReqBtn = document.getElementById('pipInstallReqBtn');
    const refreshBtn = document.getElementById('refreshPipListBtn');
    const searchInput = document.getElementById('pipSearchInput');
    const suggestionChips = document.querySelectorAll('.chip-suggestion');

    if (installBtn) {
        installBtn.addEventListener('click', () => {
            const pkg = document.getElementById('pipPackageInput').value.trim();
            if (!pkg) {
                showSnackbar('Введите название пакета для установки');
                return;
            }
            installPipPackage(pkg);
        });
    }

    if (installReqBtn) {
        installReqBtn.addEventListener('click', () => {
            const botId = document.getElementById('reqBotSelect').value;
            const bot = STATE.bots.find(b => b.id === botId);
            if (!bot) {
                showSnackbar('Выберите бота для установки requirements.txt');
                return;
            }
            installRequirementsForBot(bot);
        });
    }

    if (refreshBtn) refreshBtn.addEventListener('click', fetchPipPackages);

    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            renderPipTable(e.target.value.toLowerCase());
        });
    }

    suggestionChips.forEach(chip => {
        chip.addEventListener('click', () => {
            const pkg = chip.getAttribute('data-pkg');
            document.getElementById('pipPackageInput').value = pkg;
            installPipPackage(pkg);
        });
    });
}

async function fetchPipPackages() {
    const tbody = document.getElementById('packagesTableBody');
    tbody.innerHTML = '<tr><td colspan="3" class="table-loading">Загрузка списка модулей Python...</td></tr>';

    try {
        const res = await fetch('/api/pip/list');
        if (res.ok) {
            STATE.installedPackages = await res.json();
            renderPipTable();
            document.getElementById('installedCount').textContent = STATE.installedPackages.length;
        } else {
            tbody.innerHTML = '<tr><td colspan="3" class="table-loading">Не удалось получить список модулей.</td></tr>';
        }
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="3" class="table-loading">Ошибка: ${err.message}</td></tr>`;
    }
}

function renderPipTable(filter = '') {
    const tbody = document.getElementById('packagesTableBody');
    const filtered = STATE.installedPackages.filter(p => !filter || p.name.toLowerCase().includes(filter));

    if (filtered.length === 0) {
        tbody.innerHTML = '<tr><td colspan="3" class="table-loading">Модули не найдены</td></tr>';
        return;
    }

    tbody.innerHTML = filtered.map(pkg => `
        <tr>
            <td style="font-weight: 500;">${escapeHtml(pkg.name)}</td>
            <td><span class="pkg-version-badge">${escapeHtml(pkg.version)}</span></td>
            <td>
                <button class="chip chip-danger" onclick="uninstallPipPackage('${pkg.name}')" title="Удалить пакет">
                    <span class="material-symbols-outlined" style="font-size: 16px;">delete</span> Удалить
                </button>
            </td>
        </tr>
    `).join('');
}

async function installPipPackage(pkgName) {
    appendPipLog(`[PIP] Начало установки: ${pkgName}...`);
    showSnackbar(`Установка пакета ${pkgName}...`);

    try {
        const res = await fetch('/api/pip/install', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ package: pkgName })
        });
        const data = await res.json();
        if (data.output) {
            appendPipLog(data.output);
        }
        if (res.ok && data.success) {
            showSnackbar(`Пакет "${pkgName}" успешно установлен!`);
            fetchPipPackages();
        } else {
            showSnackbar(`Ошибка установки "${pkgName}"`);
        }
    } catch (err) {
        appendPipLog(`[ERROR] ${err.message}`);
    }
}

async function installRequirementsForBot(bot) {
    appendPipLog(`[PIP] Установка зависимостей requirements.txt из папки: ${bot.working_dir}...`);
    showSnackbar(`Установка requirements.txt для "${bot.name}"...`);

    try {
        const res = await fetch('/api/pip/install_requirements', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ working_dir: bot.working_dir })
        });
        const data = await res.json();
        if (data.output) {
            appendPipLog(data.output);
        }
        if (res.ok && data.success) {
            showSnackbar(`Все зависимости для "${bot.name}" успешно установлены!`);
            fetchPipPackages();
        } else {
            showSnackbar('Ошибка при установке requirements.txt');
        }
    } catch (err) {
        appendPipLog(`[ERROR] ${err.message}`);
    }
}

async function uninstallPipPackage(pkgName) {
    if (!confirm(`Удалить Python модуль "${pkgName}"?`)) return;

    appendPipLog(`[PIP] Удаление пакета: ${pkgName}...`);
    showSnackbar(`Удаление ${pkgName}...`);

    try {
        const res = await fetch('/api/pip/uninstall', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ package: pkgName })
        });
        const data = await res.json();
        if (data.output) appendPipLog(data.output);
        if (res.ok && data.success) {
            showSnackbar(`Пакет "${pkgName}" удален`);
            fetchPipPackages();
        }
    } catch (err) {
        appendPipLog(`[ERROR] ${err.message}`);
    }
}

function appendPipLog(text) {
    const terminal = document.getElementById('pipTerminal');
    const div = document.createElement('div');
    div.className = 'terminal-line';
    div.textContent = text;
    terminal.appendChild(div);
    terminal.scrollTop = terminal.scrollHeight;
}

/* ==========================================================================
   File Editor
   ========================================================================== */

function initFileEditor() {
    const botSelect = document.getElementById('editorBotSelect');
    const fileSelect = document.getElementById('editorFileSelect');
    const textarea = document.getElementById('editorTextarea');
    const saveBtn = document.getElementById('saveFileBtn');
    const reloadBtn = document.getElementById('reloadFileBtn');

    if (botSelect) botSelect.addEventListener('change', loadFileForEditor);
    if (fileSelect) fileSelect.addEventListener('change', loadFileForEditor);
    if (reloadBtn) reloadBtn.addEventListener('click', loadFileForEditor);

    if (textarea) {
        textarea.addEventListener('input', updateLineNumbers);
        // Tab key support
        textarea.addEventListener('keydown', (e) => {
            if (e.key === 'Tab') {
                e.preventDefault();
                const start = textarea.selectionStart;
                const end = textarea.selectionEnd;
                textarea.value = textarea.value.substring(0, start) + '    ' + textarea.value.substring(end);
                textarea.selectionStart = textarea.selectionEnd = start + 4;
            }
        });
    }

    if (saveBtn) {
        saveBtn.addEventListener('click', async () => {
            const botId = botSelect.value;
            const filename = fileSelect.value;
            const content = textarea.value;

            try {
                const res = await fetch('/api/files/write', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ bot_id: botId, file: filename, content })
                });

                if (res.ok) {
                    showSnackbar(`Файл ${filename} успешно сохранен!`);
                    document.getElementById('editorStatusDisplay').textContent = 'Изменения сохранены';
                } else {
                    showSnackbar('Ошибка сохранения файла.');
                }
            } catch (err) {
                showSnackbar(`Ошибка: ${err.message}`);
            }
        });
    }
}

async function loadFileForEditor() {
    const botSelect = document.getElementById('editorBotSelect');
    const fileSelect = document.getElementById('editorFileSelect');
    const textarea = document.getElementById('editorTextarea');

    const botId = botSelect.value || (STATE.bots[0] ? STATE.bots[0].id : null);
    const filename = fileSelect.value || '.env';

    if (!botId) return;

    document.getElementById('editorFilePathDisplay').textContent = `Путь: ${filename}`;
    document.getElementById('editorStatusDisplay').textContent = 'Загрузка...';

    try {
        const res = await fetch(`/api/files/read?bot_id=${botId}&file=${encodeURIComponent(filename)}`);
        if (res.ok) {
            const data = await res.json();
            textarea.value = data.content || '';
            updateLineNumbers();
            document.getElementById('editorStatusDisplay').textContent = 'Готов к редактированию';
        } else {
            textarea.value = '';
            updateLineNumbers();
            document.getElementById('editorStatusDisplay').textContent = 'Файл не найден (будет создан при сохранении)';
        }
    } catch (err) {
        document.getElementById('editorStatusDisplay').textContent = `Ошибка: ${err.message}`;
    }
}

function updateLineNumbers() {
    const textarea = document.getElementById('editorTextarea');
    const lineNumDiv = document.getElementById('editorLineNumbers');
    const lines = (textarea.value.split('\n').length) || 1;
    let numbers = '';
    for (let i = 1; i <= lines; i++) {
        numbers += i + '\n';
    }
    lineNumDiv.textContent = numbers;
}

/* ==========================================================================
   Settings
   ========================================================================== */

function initSettings() {
    // Local persistence of settings
    const pythonInput = document.getElementById('settingPythonPath');
    const portInput = document.getElementById('settingPort');

    if (pythonInput) {
        pythonInput.value = localStorage.getItem('telehost_python') || 'python';
        pythonInput.addEventListener('change', () => {
            localStorage.setItem('telehost_python', pythonInput.value);
            showSnackbar('Настройка Python сохранена');
        });
    }

    if (portInput) {
        portInput.value = localStorage.getItem('telehost_port') || '8080';
        portInput.addEventListener('change', () => {
            localStorage.setItem('telehost_port', portInput.value);
            showSnackbar('Порт сохранен (потребуется перезапуск приложения)');
        });
    }
}

/* ==========================================================================
   Utilities & Helpers
   ========================================================================== */

function updateBotDropdowns() {
    const consoleSelect = document.getElementById('consoleBotSelect');
    const reqSelect = document.getElementById('reqBotSelect');
    const editorSelect = document.getElementById('editorBotSelect');

    const options = STATE.bots.map(b => `<option value="${b.id}">${escapeHtml(b.name)}</option>`).join('');

    if (consoleSelect && STATE.bots.length > 0) {
        const prev = consoleSelect.value;
        consoleSelect.innerHTML = options;
        if (prev && STATE.bots.some(b => b.id === prev)) consoleSelect.value = prev;
        else STATE.selectedConsoleBotId = STATE.bots[0].id;
    }

    if (reqSelect) reqSelect.innerHTML = options;
    if (editorSelect) editorSelect.innerHTML = options;
}

function formatUptime(started_at) {
    if (!started_at) return '-';
    const seconds = Math.floor((Date.now() - started_at) / 1000);
    if (seconds < 60) return `${seconds}с`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}м ${seconds % 60}с`;
    const hours = Math.floor(minutes / 60);
    return `${hours}ч ${minutes % 60}м`;
}

function showSnackbar(message) {
    const container = document.getElementById('snackbarContainer');
    if (!container) return;

    const snackbar = document.createElement('div');
    snackbar.className = 'snackbar';
    snackbar.textContent = message;

    container.appendChild(snackbar);

    setTimeout(() => {
        snackbar.style.opacity = '0';
        snackbar.style.transform = 'translateY(10px)';
        snackbar.style.transition = 'all 200ms ease';
        setTimeout(() => snackbar.remove(), 200);
    }, 3000);
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/[&<>'"]/g, tag => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        "'": '&#39;',
        '"': '&quot;'
    }[tag] || tag));
}
