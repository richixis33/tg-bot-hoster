//! TeleHost — Telegram Bot Hoster in Rust
//! Features: Process lifecycle management (.bat, .py), Pip package manager, Material Design 3 Web UI

use std::collections::HashMap;
use std::fs;
use std::io::{BufRead, BufReader, Read};
use std::net::SocketAddr;
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use tiny_http::{Header, Method, Request, Response, Server, StatusCode};

// Embedded Material Design 3 Web Assets
static HTML: &str = include_str!("../ui/index.html");
static CSS: &str = include_str!("../ui/style.css");
static JS: &str = include_str!("../ui/app.js");

const CONFIG_FILE: &str = "telehost_bots.json";
const DEFAULT_PORT: u16 = 8080;

#[derive(Clone, Debug)]
pub struct Bot {
    pub id: String,
    pub name: String,
    pub working_dir: String,
    pub command: String,
    pub auto_restart: bool,
    pub status: String, // "stopped", "running", "error"
    pub pid: Option<u32>,
    pub started_at: Option<u64>,
    pub logs: Vec<String>,
}

pub struct AppState {
    pub bots: Mutex<Vec<Bot>>,
    pub processes: Mutex<HashMap<String, Child>>,
    pub python_cmd: Mutex<String>,
}

fn main() {
    println!("=======================================================");
    println!("     🚀 TeleHost — Telegram Bot Hoster (Rust M3)");
    println!("=======================================================");

    let port = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(DEFAULT_PORT);

    let state = Arc::new(AppState {
        bots: Mutex::new(Vec::new()),
        processes: Mutex::new(HashMap::new()),
        python_cmd: Mutex::new("python".to_string()),
    });

    // Detect python version
    let py_ver = get_python_version("python");
    println!("🐍 Detected runtime: {}", py_ver);

    // Load or initialize bots config
    load_or_init_bots(&state);

    let addr: SocketAddr = format!("0.0.0.0:{}", port).parse().unwrap();
    let server = match Server::http(addr) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("❌ Failed to bind to port {}: {}", port, e);
            std::process::exit(1);
        }
    };

    let local_url = format!("http://localhost:{}", port);
    println!("🌐 Material Design 3 Dashboard running at: {}", local_url);
    println!("Press Ctrl+C to stop the hoster.\n");

    // Open browser automatically on Windows
    let _ = Command::new("cmd")
        .args(&["/C", "start", &local_url])
        .spawn();

    let server_arc = Arc::new(server);

    // Request handling loop
    for request in server_arc.incoming_requests() {
        let state_clone = Arc::clone(&state);
        thread::spawn(move || {
            handle_request(request, state_clone);
        });
    }
}

// ============================================================================
// HTTP Request Router & Handler
// ============================================================================

fn handle_request(mut req: Request, state: Arc<AppState>) {
    let url = req.url().to_string();
    let method = req.method().clone();

    // 1. Static UI Assets
    if method == Method::Get {
        if url == "/" || url == "/index.html" {
            let res = Response::from_string(HTML)
                .with_header(header("Content-Type", "text/html; charset=utf-8"));
            let _ = req.respond(res);
            return;
        } else if url == "/style.css" {
            let res = Response::from_string(CSS)
                .with_header(header("Content-Type", "text/css; charset=utf-8"));
            let _ = req.respond(res);
            return;
        } else if url == "/app.js" {
            let res = Response::from_string(JS)
                .with_header(header("Content-Type", "application/javascript; charset=utf-8"));
            let _ = req.respond(res);
            return;
        }
    }

    // 2. API Routes
    if url.starts_with("/api/") {
        handle_api_request(req, &url, &method, state);
        return;
    }

    // Fallback 404
    let _ = req.respond(Response::from_string("Not Found").with_status_code(StatusCode(404)));
}

fn handle_api_request(mut req: Request, url: &str, method: &Method, state: Arc<AppState>) {
    // GET /api/system
    if url == "/api/system" && method == &Method::Get {
        let py_cmd = state.python_cmd.lock().unwrap().clone();
        let py_ver = get_python_version(&py_cmd);
        let json = format!(
            r#"{{"os":"windows","python_version":"{}","python_cmd":"{}","arch":"x86_64"}}"#,
            escape_json(&py_ver),
            escape_json(&py_cmd)
        );
        respond_json(req, &json);
        return;
    }

    // GET /api/bots
    if url == "/api/bots" && method == &Method::Get {
        let bots = state.bots.lock().unwrap();
        let json = serialize_bots(&bots);
        respond_json(req, &json);
        return;
    }

    // POST /api/bots/add
    if url == "/api/bots/add" && method == &Method::Post {
        let body = read_body_string(&mut req);
        let name = extract_json_field(&body, "name").unwrap_or_else(|| "Bot".to_string());
        let working_dir = extract_json_field(&body, "working_dir").unwrap_or_default();
        let command = extract_json_field(&body, "command").unwrap_or_default();
        let auto_restart = extract_json_bool(&body, "auto_restart").unwrap_or(true);

        let id = format!("bot_{}", now_millis() % 100000);
        let new_bot = Bot {
            id: id.clone(),
            name,
            working_dir,
            command,
            auto_restart,
            status: "stopped".to_string(),
            pid: None,
            started_at: None,
            logs: vec![format!("[{}] Бот добавлен в систему", current_time_str())],
        };

        {
            let mut bots = state.bots.lock().unwrap();
            bots.push(new_bot);
            save_bots_to_disk(&bots);
        }

        respond_json(req, &format!(r#"{{"success":true,"id":"{}"}}"#, id));
        return;
    }

    // POST /api/bots/delete
    if url == "/api/bots/delete" && method == &Method::Post {
        let body = read_body_string(&mut req);
        if let Some(id) = extract_json_field(&body, "id") {
            stop_bot_internal(&id, &state);
            let mut bots = state.bots.lock().unwrap();
            bots.retain(|b| b.id != id);
            save_bots_to_disk(&bots);
            respond_json(req, r#"{"success":true}"#);
        } else {
            respond_json(req, r#"{"success":false,"error":"Missing bot id"}"#);
        }
        return;
    }

    // POST /api/bots/start
    if url == "/api/bots/start" && method == &Method::Post {
        let body = read_body_string(&mut req);
        if let Some(id) = extract_json_field(&body, "id") {
            let res = start_bot_internal(&id, Arc::clone(&state));
            respond_json(req, &format!(r#"{{"success":{}}}"#, res));
        } else {
            respond_json(req, r#"{"success":false,"error":"Missing bot id"}"#);
        }
        return;
    }

    // POST /api/bots/stop
    if url == "/api/bots/stop" && method == &Method::Post {
        let body = read_body_string(&mut req);
        if let Some(id) = extract_json_field(&body, "id") {
            stop_bot_internal(&id, &state);
            respond_json(req, r#"{"success":true}"#);
        } else {
            respond_json(req, r#"{"success":false,"error":"Missing bot id"}"#);
        }
        return;
    }

    // POST /api/bots/restart
    if url == "/api/bots/restart" && method == &Method::Post {
        let body = read_body_string(&mut req);
        if let Some(id) = extract_json_field(&body, "id") {
            stop_bot_internal(&id, &state);
            thread::sleep(Duration::from_millis(500));
            let res = start_bot_internal(&id, Arc::clone(&state));
            respond_json(req, &format!(r#"{{"success":{}}}"#, res));
        } else {
            respond_json(req, r#"{"success":false,"error":"Missing bot id"}"#);
        }
        return;
    }

    // GET /api/bots/logs?id=...&since=N
    if url.starts_with("/api/bots/logs") && method == &Method::Get {
        let id = extract_query_param(url, "id").unwrap_or_default();
        let since: usize = extract_query_param(url, "since")
            .and_then(|s| s.parse().ok())
            .unwrap_or(0);

        let bots = state.bots.lock().unwrap();
        if let Some(bot) = bots.iter().find(|b| b.id == id) {
            let total = bot.logs.len();
            let slice = if since < total {
                &bot.logs[since..]
            } else {
                &[]
            };

            let lines_json: Vec<String> = slice
                .iter()
                .map(|l| format!("\"{}\"", escape_json(l)))
                .collect();

            let json = format!(
                r#"{{"next_index":{},"lines":[{}]}}"#,
                total,
                lines_json.join(",")
            );
            respond_json(req, &json);
        } else {
            respond_json(req, r#"{"next_index":0,"lines":[]}"#);
        }
        return;
    }

    // POST /api/bots/logs/clear
    if url == "/api/bots/logs/clear" && method == &Method::Post {
        let body = read_body_string(&mut req);
        if let Some(id) = extract_json_field(&body, "id") {
            let mut bots = state.bots.lock().unwrap();
            if let Some(bot) = bots.iter_mut().find(|b| b.id == id) {
                bot.logs.clear();
            }
        }
        respond_json(req, r#"{"success":true}"#);
        return;
    }

    // GET /api/pip/list
    if url == "/api/pip/list" && method == &Method::Get {
        let py_cmd = state.python_cmd.lock().unwrap().clone();
        let output = Command::new(&py_cmd)
            .args(&["-m", "pip", "list", "--format=json"])
            .output();

        match output {
            Ok(out) if out.status.success() => {
                let json = String::from_utf8_lossy(&out.stdout).to_string();
                respond_json(req, &json);
            }
            _ => {
                respond_json(req, "[]");
            }
        }
        return;
    }

    // POST /api/pip/install
    if url == "/api/pip/install" && method == &Method::Post {
        let body = read_body_string(&mut req);
        if let Some(pkg) = extract_json_field(&body, "package") {
            let py_cmd = state.python_cmd.lock().unwrap().clone();
            let output = Command::new(&py_cmd)
                .args(&["-m", "pip", "install", &pkg])
                .output();

            let (success, out_str) = match output {
                Ok(out) => {
                    let text = format!(
                        "{}\n{}",
                        String::from_utf8_lossy(&out.stdout),
                        String::from_utf8_lossy(&out.stderr)
                    );
                    (out.status.success(), text)
                }
                Err(e) => (false, format!("Failed to run pip: {}", e)),
            };

            let json = format!(
                r#"{{"success":{},"output":"{}"}}"#,
                success,
                escape_json(&out_str)
            );
            respond_json(req, &json);
        } else {
            respond_json(req, r#"{"success":false,"error":"Missing package name"}"#);
        }
        return;
    }

    // POST /api/pip/uninstall
    if url == "/api/pip/uninstall" && method == &Method::Post {
        let body = read_body_string(&mut req);
        if let Some(pkg) = extract_json_field(&body, "package") {
            let py_cmd = state.python_cmd.lock().unwrap().clone();
            let output = Command::new(&py_cmd)
                .args(&["-m", "pip", "uninstall", "-y", &pkg])
                .output();

            let (success, out_str) = match output {
                Ok(out) => (out.status.success(), String::from_utf8_lossy(&out.stdout).to_string()),
                Err(e) => (false, format!("Error: {}", e)),
            };

            let json = format!(
                r#"{{"success":{},"output":"{}"}}"#,
                success,
                escape_json(&out_str)
            );
            respond_json(req, &json);
        } else {
            respond_json(req, r#"{"success":false}"#);
        }
        return;
    }

    // POST /api/pip/install_requirements
    if url == "/api/pip/install_requirements" && method == &Method::Post {
        let body = read_body_string(&mut req);
        let working_dir = extract_json_field(&body, "working_dir").unwrap_or_else(|| ".".to_string());
        let req_path = Path::new(&working_dir).join("requirements.txt");

        let py_cmd = state.python_cmd.lock().unwrap().clone();
        let output = Command::new(&py_cmd)
            .args(&["-m", "pip", "install", "-r", req_path.to_str().unwrap_or("requirements.txt")])
            .current_dir(&working_dir)
            .output();

        let (success, out_str) = match output {
            Ok(out) => {
                let text = format!(
                    "{}\n{}",
                    String::from_utf8_lossy(&out.stdout),
                    String::from_utf8_lossy(&out.stderr)
                );
                (out.status.success(), text)
            }
            Err(e) => (false, format!("Error executing pip install -r: {}", e)),
        };

        let json = format!(
            r#"{{"success":{},"output":"{}"}}"#,
            success,
            escape_json(&out_str)
        );
        respond_json(req, &json);
        return;
    }

    // GET /api/files/read?bot_id=...&file=...
    if url.starts_with("/api/files/read") && method == &Method::Get {
        let bot_id = extract_query_param(url, "bot_id").unwrap_or_default();
        let filename = extract_query_param(url, "file").unwrap_or_else(|| ".env".to_string());

        let bots = state.bots.lock().unwrap();
        if let Some(bot) = bots.iter().find(|b| b.id == bot_id) {
            let full_path = Path::new(&bot.working_dir).join(&filename);
            match fs::read_to_string(&full_path) {
                Ok(content) => {
                    let json = format!(
                        r#"{{"success":true,"file":"{}","content":"{}"}}"#,
                        escape_json(&filename),
                        escape_json(&content)
                    );
                    respond_json(req, &json);
                }
                Err(e) => {
                    respond_json(req, &format!(r#"{{"success":false,"error":"{}"}}"#, e));
                }
            }
        } else {
            respond_json(req, r#"{"success":false,"error":"Bot not found"}"#);
        }
        return;
    }

    // POST /api/files/write
    if url == "/api/files/write" && method == &Method::Post {
        let body = read_body_string(&mut req);
        let bot_id = extract_json_field(&body, "bot_id").unwrap_or_default();
        let filename = extract_json_field(&body, "file").unwrap_or_default();
        let content = extract_json_field(&body, "content").unwrap_or_default();

        let bots = state.bots.lock().unwrap();
        if let Some(bot) = bots.iter().find(|b| b.id == bot_id) {
            let full_path = Path::new(&bot.working_dir).join(&filename);
            match fs::write(&full_path, content) {
                Ok(_) => respond_json(req, r#"{"success":true}"#),
                Err(e) => respond_json(req, &format!(r#"{{"success":false,"error":"{}"}}"#, e)),
            }
        } else {
            respond_json(req, r#"{"success":false,"error":"Bot not found"}"#);
        }
        return;
    }

    let _ = req.respond(Response::from_string("API Not Found").with_status_code(StatusCode(404)));
}

// ============================================================================
// Process Lifecycle & Execution Logic
// ============================================================================

fn start_bot_internal(id: &str, state: Arc<AppState>) -> bool {
    let mut bots = state.bots.lock().unwrap();
    let bot_idx = match bots.iter().position(|b| b.id == id) {
        Some(idx) => idx,
        None => return false,
    };

    let bot = &mut bots[bot_idx];
    if bot.status == "running" {
        return true;
    }

    let cmd_str = bot.command.trim();
    let working_dir = bot.working_dir.clone();
    let bot_id = bot.id.clone();

    // Prepare child process
    let mut command = if cmd_str.to_lowercase().ends_with(".bat") || cmd_str.to_lowercase().ends_with(".cmd") {
        let mut c = Command::new("cmd.exe");
        c.args(&["/C", cmd_str]);
        c
    } else if cmd_str.to_lowercase().ends_with(".py") {
        let mut c = Command::new("python");
        c.args(&["-u", cmd_str]);
        c
    } else {
        let mut c = Command::new("cmd.exe");
        c.args(&["/C", cmd_str]);
        c
    };

    if !working_dir.is_empty() {
        command.current_dir(Path::new(&working_dir));
    }

    command.stdout(Stdio::piped());
    command.stderr(Stdio::piped());

    match command.spawn() {
        Ok(mut child) => {
            let pid = child.id();
            let stdout = child.stdout.take();
            let stderr = child.stderr.take();

            bot.status = "running".to_string();
            bot.pid = Some(pid);
            bot.started_at = Some(now_millis());
            bot.logs.push(format!(
                "[{}] 🚀 Запущен процесс (PID: {}): {}",
                current_time_str(),
                pid,
                cmd_str
            ));

            let mut proc_map = state.processes.lock().unwrap();
            proc_map.insert(bot_id.clone(), child);

            // Spawn stdout reader thread
            if let Some(out) = stdout {
                let state_clone = Arc::clone(&state);
                let b_id = bot_id.clone();
                thread::spawn(move || {
                    let reader = BufReader::new(out);
                    for line_res in reader.lines() {
                        if let Ok(line) = line_res {
                            let mut b_lock = state_clone.bots.lock().unwrap();
                            if let Some(b) = b_lock.iter_mut().find(|x| x.id == b_id) {
                                b.logs.push(format!("[{}] {}", current_time_str(), line));
                                if b.logs.len() > 1500 {
                                    b.logs.remove(0);
                                }
                            }
                        }
                    }
                });
            }

            // Spawn stderr reader thread
            if let Some(err) = stderr {
                let state_clone = Arc::clone(&state);
                let b_id = bot_id.clone();
                thread::spawn(move || {
                    let reader = BufReader::new(err);
                    for line_res in reader.lines() {
                        if let Ok(line) = line_res {
                            let mut b_lock = state_clone.bots.lock().unwrap();
                            if let Some(b) = b_lock.iter_mut().find(|x| x.id == b_id) {
                                b.logs.push(format!("[{}] [ERROR] {}", current_time_str(), line));
                                if b.logs.len() > 1500 {
                                    b.logs.remove(0);
                                }
                            }
                        }
                    }
                });
            }

            // Monitor process termination in background
            let state_monitor = Arc::clone(&state);
            let b_id_monitor = bot_id.clone();
            thread::spawn(move || {
                loop {
                    thread::sleep(Duration::from_millis(500));
                    let mut proc_lock = state_monitor.processes.lock().unwrap();
                    if let Some(child_proc) = proc_lock.get_mut(&b_id_monitor) {
                        match child_proc.try_wait() {
                            Ok(Some(status)) => {
                                // Process exited
                                proc_lock.remove(&b_id_monitor);
                                let mut b_lock = state_monitor.bots.lock().unwrap();
                                if let Some(b) = b_lock.iter_mut().find(|x| x.id == b_id_monitor) {
                                    b.status = if status.success() {
                                        "stopped".to_string()
                                    } else {
                                        "error".to_string()
                                    };
                                    b.pid = None;
                                    b.logs.push(format!(
                                        "[{}] ⏹ Процесс завершился с кодом: {:?}",
                                        current_time_str(),
                                        status.code()
                                    ));

                                    // Auto-restart if configured and was error
                                    if b.auto_restart && !status.success() {
                                        b.logs.push(format!(
                                            "[{}] 🔄 Автоматический перезапуск через 3 секунды...",
                                            current_time_str()
                                        ));
                                        drop(b_lock);
                                        drop(proc_lock);
                                        thread::sleep(Duration::from_secs(3));
                                        start_bot_internal(&b_id_monitor, Arc::clone(&state_monitor));
                                        return;
                                    }
                                }
                                break;
                            }
                            Ok(None) => {} // Still running
                            Err(_) => {
                                proc_lock.remove(&b_id_monitor);
                                break;
                            }
                        }
                    } else {
                        break;
                    }
                }
            });

            true
        }
        Err(e) => {
            bot.status = "error".to_string();
            bot.logs.push(format!(
                "[{}] [ERROR] Ошибка запуска процесса: {}",
                current_time_str(),
                e
            ));
            false
        }
    }
}

fn stop_bot_internal(id: &str, state: &AppState) {
    let mut proc_lock = state.processes.lock().unwrap();
    if let Some(mut child) = proc_lock.remove(id) {
        let pid = child.id();
        // On Windows, use taskkill to kill the whole process tree (cmd + python)
        let _ = Command::new("taskkill")
            .args(&["/F", "/T", "/PID", &pid.to_string()])
            .output();

        let _ = child.kill();
    }

    let mut bots = state.bots.lock().unwrap();
    if let Some(bot) = bots.iter_mut().find(|b| b.id == id) {
        bot.status = "stopped".to_string();
        bot.pid = None;
        bot.started_at = None;
        bot.logs.push(format!("[{}] ⏹ Бот был остановлен пользователем", current_time_str()));
    }
}

// ============================================================================
// Configuration & Persistence
// ============================================================================

fn load_or_init_bots(state: &AppState) {
    let config_path = Path::new(CONFIG_FILE);

    if config_path.exists() {
        if let Ok(content) = fs::read_to_string(config_path) {
            let loaded = parse_bots_from_json(&content);
            if !loaded.is_empty() {
                let mut bots = state.bots.lock().unwrap();
                *bots = loaded;
                println!("📂 Loaded {} bot(s) from {}", bots.len(), CONFIG_FILE);
                return;
            }
        }
    }

    // Default configuration with the example bot in this repository!
    let example_dir = "./examples/telegram_business_bot";
    let default_bot = Bot {
        id: "crypto_business_bot".to_string(),
        name: "Crypto Business Bot (Example)".to_string(),
        working_dir: example_dir.to_string(),
        command: "start_business_bot.bat".to_string(),
        auto_restart: true,
        status: "stopped".to_string(),
        pid: None,
        started_at: None,
        logs: vec![format!("[{}] Готов к запуску через TeleHost", current_time_str())],
    };

    let mut bots = state.bots.lock().unwrap();
    bots.push(default_bot);
    save_bots_to_disk(&bots);
    println!("✨ Initialized default bot example: Crypto Business Bot");
}

fn save_bots_to_disk(bots: &[Bot]) {
    let json = serialize_bots(bots);
    let _ = fs::write(CONFIG_FILE, json);
}

// ============================================================================
// JSON Serialization Helpers (Lightweight, No Proc-Macros)
// ============================================================================

fn serialize_bots(bots: &[Bot]) -> String {
    let items: Vec<String> = bots
        .iter()
        .map(|b| {
            format!(
                r#"{{"id":"{}","name":"{}","working_dir":"{}","command":"{}","auto_restart":{},"status":"{}","pid":{},"started_at":{}}}"#,
                escape_json(&b.id),
                escape_json(&b.name),
                escape_json(&b.working_dir),
                escape_json(&b.command),
                b.auto_restart,
                b.status,
                b.pid.map(|p| p.to_string()).unwrap_or_else(|| "null".to_string()),
                b.started_at.map(|t| t.to_string()).unwrap_or_else(|| "null".to_string())
            )
        })
        .collect();

    format!("[{}]", items.join(","))
}

fn parse_bots_from_json(json: &str) -> Vec<Bot> {
    let mut bots = Vec::new();
    let trimmed = json.trim();
    if !trimmed.starts_with('[') || !trimmed.ends_with(']') {
        return bots;
    }

    let inner = &trimmed[1..trimmed.len() - 1];
    for obj_str in split_json_objects(inner) {
        if let (Some(id), Some(name), Some(working_dir), Some(command)) = (
            extract_json_field(&obj_str, "id"),
            extract_json_field(&obj_str, "name"),
            extract_json_field(&obj_str, "working_dir"),
            extract_json_field(&obj_str, "command"),
        ) {
            let auto_restart = extract_json_bool(&obj_str, "auto_restart").unwrap_or(true);
            bots.push(Bot {
                id,
                name,
                working_dir,
                command,
                auto_restart,
                status: "stopped".to_string(),
                pid: None,
                started_at: None,
                logs: vec![format!("[{}] Загружен из файла конфигурации", current_time_str())],
            });
        }
    }
    bots
}

fn split_json_objects(s: &str) -> Vec<String> {
    let mut res = Vec::new();
    let mut depth = 0;
    let mut start = 0;

    for (i, c) in s.char_indices() {
        if c == '{' {
            if depth == 0 {
                start = i;
            }
            depth += 1;
        } else if c == '}' {
            depth -= 1;
            if depth == 0 {
                res.push(s[start..=i].to_string());
            }
        }
    }
    res
}

fn extract_json_field(json: &str, field: &str) -> Option<String> {
    let pattern = format!("\"{}\":", field);
    let pos = json.find(&pattern)?;
    let rest = json[pos + pattern.len()..].trim_start();

    if rest.starts_with('"') {
        let after_quote = &rest[1..];
        let mut end = 0;
        let mut escaped = false;
        for (i, c) in after_quote.char_indices() {
            if escaped {
                escaped = false;
            } else if c == '\\' {
                escaped = true;
            } else if c == '"' {
                end = i;
                break;
            }
        }
        let val = &after_quote[..end];
        Some(unescape_json(val))
    } else {
        let end = rest.find(|c| c == ',' || c == '}' || c == ']').unwrap_or(rest.len());
        Some(rest[..end].trim().to_string())
    }
}

fn extract_json_bool(json: &str, field: &str) -> Option<bool> {
    let pattern = format!("\"{}\":", field);
    let pos = json.find(&pattern)?;
    let rest = json[pos + pattern.len()..].trim_start();
    if rest.starts_with("true") {
        Some(true)
    } else if rest.starts_with("false") {
        Some(false)
    } else {
        None
    }
}

fn extract_query_param(url: &str, param: &str) -> Option<String> {
    let query_start = url.find('?')?;
    let query = &url[query_start + 1..];
    for pair in query.split('&') {
        let mut parts = pair.split('=');
        if let (Some(k), Some(v)) = (parts.next(), parts.next()) {
            if k == param {
                return Some(urldecode(v));
            }
        }
    }
    None
}

fn escape_json(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            _ => out.push(c),
        }
    }
    out
}

fn unescape_json(s: &str) -> String {
    s.replace("\\\\", "\\")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
}

fn urldecode(s: &str) -> String {
    let mut res = String::new();
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '%' {
            let h1 = chars.next().unwrap_or('0');
            let h2 = chars.next().unwrap_or('0');
            if let Ok(byte) = u8::from_str_radix(&format!("{}{}", h1, h2), 16) {
                res.push(byte as char);
            }
        } else if c == '+' {
            res.push(' ');
        } else {
            res.push(c);
        }
    }
    res
}

// ============================================================================
// Utilities
// ============================================================================

fn read_body_string(req: &mut Request) -> String {
    let mut body = String::new();
    let _ = req.as_reader().read_to_string(&mut body);
    body
}

fn respond_json(req: Request, json: &str) {
    let res = Response::from_string(json)
        .with_header(header("Content-Type", "application/json; charset=utf-8"))
        .with_header(header("Access-Control-Allow-Origin", "*"));
    let _ = req.respond(res);
}

fn header(name: &str, value: &str) -> Header {
    Header::from_bytes(name.as_bytes(), value.as_bytes()).unwrap()
}

fn get_python_version(cmd: &str) -> String {
    let out = Command::new(cmd).arg("--version").output();
    match out {
        Ok(o) => {
            let stdout = String::from_utf8_lossy(&o.stdout).trim().to_string();
            let stderr = String::from_utf8_lossy(&o.stderr).trim().to_string();
            if !stdout.is_empty() {
                stdout
            } else if !stderr.is_empty() {
                stderr
            } else {
                "Python 3.x".to_string()
            }
        }
        Err(_) => "Python (not found)".to_string(),
    }
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn current_time_str() -> String {
    let total_secs = now_millis() / 1000;
    let secs = total_secs % 60;
    let mins = (total_secs / 60) % 60;
    let hours = (total_secs / 3600) % 24;
    format!("{:02}:{:02}:{:02}", hours, mins, secs)
}
