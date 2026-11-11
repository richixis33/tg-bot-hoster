# 🚀 TeleHost — Telegram Bot Hoster (Rust & Material Design 3)

[![Rust](https://img.shields.io/badge/Rust-1.99%2B-orange.svg?logo=rust)](https://www.rust-lang.org/)
[![UI](https://img.shields.io/badge/Material%20Design-3%20(Material%20You)-6750A4.svg?logo=google)](https://m3.material.io/)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux%20%7C%20Android-blue.svg)](#)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**TeleHost** — это современная программа-хостинг для запуска, мониторинга и управления Telegram ботами. Бэкенд написан на **Rust**, а интерфейс выполнен в стиле **Google Material Design 3 (Material You)** с поддержкой динамических тем, живой веб-консоли, менеджера PIP-пакетов и поддержкой сборки под **Android (APK)**.

---

## ✨ Основные возможности

### 1. 🤖 Управление и запуск ботов
* **Поддержка любых форматов**: прямой запуск `.bat` файлов (например, `start_business_bot.bat`), Python-скриптов (`main.py`) и произвольных команд.
* **Супервизор процессов**: мгновенный старт, остановка, перезапуск в один клик.
* **Auto-Restart**: автоматический перезапуск бота при неожиданном падении или ошибке.
* **Мониторинг ресурсов**: отображение статуса (РАБОТАЕТ / ОСТАНОВЛЕН / ОШИБКА), PID процесса и таймера аптайма.

### 2. 🎨 Интерфейс Google Material Design 3
* **Material You Tonal Palettes**: 5 динамических цветовых схем (Violet, Ocean Blue, Emerald Green, Amber Orange, Rose Pink).
* **Dark / Light режимы**: адаптивные поверхности M3 Surface Containers.
* **M3 Navigation Rail & Bar**: адаптивная навигация для десктопа и мобильных экранов.
* **M3 Floating Action Button (FAB)** и модальные диалоги добавления ботов.

### 3. 📦 Встроенный менеджер PIP-пакетов
* **Поиск и просмотр** установленных в системе библиотек Python с версиями.
* **Установка модулей на лету** (`pip install <имя_пакета>`) прямо из веб-интерфейса.
* **Удаление модулей** в один клик.
* **Пакетная установка из `requirements.txt`**: автоматическая установка всех зависимостей выбранного бота одной кнопкой с живым выводом лога установки.

### 4. 💻 Живая Web-Консоль (Terminal)
* Потоковый вывод `stdout` и `stderr` с цветовым выделением системных сообщений, предупреждений и ошибок.
* Автопрокрутка, очистка консоли и возможность **скачать лог в файл `.txt`**.

### 5. 📝 Редактор файлов бота
* Прямо в браузере редактируйте `.env` (например, вставка `BOT_TOKEN`), `start_business_bot.bat`, `config.py` или `requirements.txt`.
* Подсчет строк, поддержка табуляции и сохранение изменений без необходимости открывать блокнот.

### 6. 📱 Сборка под Android (APK)
* В репозитории содержится готовый проект Android Studio (`android/`) с `WebView`, фоновой службой `BotHostService` и встроенными оффлайн-ассетами интерфейса.
* Настроен **GitHub Actions CI/CD** (`.github/workflows/build-apk.yml`), который компилирует `.apk` файл автоматически на серверах GitHub!

---

## 📂 Структура репозитория

```
tg-bot-hoster/
├── Cargo.toml                      # Конфигурация Rust проекта
├── TeleHost.exe                    # Скомпилированный бинарный файл для Windows (x64)
├── start_telehost.bat              # Скрипт быстрого запуска в 1 клик
├── telehost_server.py              # Python-fallback сервер (работает везде без компиляции)
├── src/
│   └── main.rs                     # Ядро на Rust: HTTP-сервер, управление процессами, PIP API
├── ui/                             # Интерфейс Material Design 3
│   ├── index.html                  # Каркас интерфейса с Google Material Symbols
│   ├── style.css                   # Токены Material 3, темы, карточки, консоль
│   └── app.js                      # Интерактивная логика, AJAX-запросы, живой логгер
├── android/                        # Android проект для сборки в APK
│   ├── app/
│   │   ├── build.gradle
│   │   └── src/main/
│   │       ├── AndroidManifest.xml # Манифест с Foreground Service и WakeLock
│   │       ├── java/com/telehost/app/MainActivity.kt
│   │       ├── java/com/telehost/app/BotHostService.kt
│   │       └── assets/web/         # Встроенный M3 интерфейс
│   ├── build.gradle
│   └── settings.gradle
├── examples/
│   └── telegram_business_bot/      # Встроенный готовый пример бота (Business Crypto Bot)
│       ├── main.py                 # Точка входа aiogram 3
│       ├── start_business_bot.bat  # Bat-файл запуска
│       ├── requirements.txt        # Зависимости бота
│       ├── config.py
│       ├── .env.example
│       └── handlers/
└── .github/
    └── workflows/
        ├── build-windows.yml       # Автосборка Windows EXE в GitHub Actions
        └── build-apk.yml           # Автосборка Android APK в GitHub Actions
```

---

## 🚀 Быстрый старт на Windows

### Вариант 1: Запуск готового `.exe` (Рекомендуется)
1. Дважды кликните по файлу `TeleHost.exe` или запустите `start_telehost.bat`.
2. В браузере автоматически откроется панель управления:
   👉 **`http://localhost:8080/`**
3. Пример бота (`Crypto Business Bot`) уже добавлен в список и готов к запуску!

### Вариант 2: Сборка из исходников на Rust
```bash
# Сборка и запуск
cargo run --release
```

### Вариант 3: Запуск через Python (Fallback)
Если на компьютере нет Rust:
```bash
python telehost_server.py
```

---

## 🤖 Как настроить и запустить пример бота

1. В интерфейсе TeleHost перейдите во вкладку **"Файлы"**.
2. Выберите бота `Crypto Business Bot (Example)` и файл `.env`.
3. Укажите ваш токен от BotFather:
   ```env
   BOT_TOKEN=1234567890:ABCdefGHIjklMNOpqrsTUVwxyz
   ```
4. Нажмите **"Сохранить файл"**.
5. Перейдите во вкладку **"PIP Модули"** и нажмите **"Установить requirements.txt"**.
6. Во вкладке **"Боты"** нажмите кнопку **"Старт"** ▶️.
7. Перейдите в **"Консоль"**, чтобы наблюдать за работой бота в реальном времени!

---

## 📱 Сборка Android APK

### Автоматическая сборка через GitHub Actions:
1. Зайдите в ваш репозиторий на GitHub: `https://github.com/richixis33/tg-bot-hoster`
2. Перейдите во вкладку **Actions**.
3. Выберите воркфлоу **"Build Android APK"** и нажмите **"Run workflow"**.
4. После завершения скачайте готовый `.apk` из блока **Artifacts**!

### Ручная сборка в Android Studio:
1. Откройте папку `android/` в Android Studio.
2. Дождитесь Gradle Sync.
3. Выберите **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

---

## 📄 Лицензия
MIT License © 2026 richixis33
