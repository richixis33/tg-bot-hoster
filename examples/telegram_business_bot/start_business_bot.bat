@echo off
title Telegram Business Crypto Bot
cd /d "%~dp0"

echo =======================================================
echo        Telegram Business Crypto Bot Launcher
echo =======================================================
echo.

python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python is not installed or not in PATH!
    echo Please install Python 3.10+ and check "Add Python to PATH".
    echo.
    pause
    exit /b 1
)

echo [1/2] Checking dependencies...
python -m pip install -q -r requirements.txt

echo [2/2] Starting Telegram Business Bot...
echo.
python main.py

if errorlevel 1 (
    echo.
    echo [ERROR] Bot exited with error.
    pause
)
