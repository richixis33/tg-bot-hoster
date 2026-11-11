@echo off
title TeleHost Launcher
cd /d "%~dp0"

echo =======================================================
echo     🚀 TeleHost — Telegram Bot Hoster (Rust M3)
echo =======================================================
echo.

if exist "TeleHost.exe" (
    echo [INFO] Запуск скомпилированного TeleHost.exe...
    TeleHost.exe
    goto end
)

if exist "target\release\tg-bot-hoster.exe" (
    echo [INFO] Запуск target\release\tg-bot-hoster.exe...
    target\release\tg-bot-hoster.exe
    goto end
)

cargo --version >nul 2>&1
if not errorlevel 1 (
    echo [INFO] Компиляция и запуск через Cargo...
    cargo run --release
    goto end
)

python --version >nul 2>&1
if not errorlevel 1 (
    echo [INFO] Cargo не найден, запуск через Python fallback...
    python telehost_server.py
    goto end
)

echo [ERROR] Ни TeleHost.exe, ни Cargo, ни Python не найдены!
pause

:end
