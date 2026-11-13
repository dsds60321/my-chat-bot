use tauri::{
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
    Emitter, Manager,
};
use tauri_plugin_window_state::StateFlags;
use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Read, Write};
use std::path::Path;
use std::sync::Mutex;

#[derive(Default)]
struct AnswerState(Mutex<Option<String>>);

fn require_window(window: &tauri::WebviewWindow, label: &str) -> Result<(), String> {
    if window.label() == label { Ok(()) } else { Err("허용되지 않은 창입니다.".into()) }
}

struct TrayLabels {
    show: MenuItem<tauri::Wry>,
    hide: MenuItem<tauri::Wry>,
}

#[tauri::command]
fn set_branding_name(app: tauri::AppHandle, window: tauri::WebviewWindow, name: String,
                     labels: tauri::State<'_, TrayLabels>) -> Result<(), String> {
    require_window(&window, "main")?;
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 40 || name.chars().any(char::is_control) {
        return Err("앱 이름을 확인해 주세요.".into());
    }
    labels.show.set_text(format!("{name} 표시")).map_err(|_| "메뉴 이름을 변경하지 못했어요.")?;
    labels.hide.set_text(format!("{name} 숨김")).map_err(|_| "메뉴 이름을 변경하지 못했어요.")?;
    if let Some(tray) = app.tray_by_id("main-tray") {
        tray.set_tooltip(Some(name)).map_err(|_| "앱 이름을 변경하지 못했어요.")?;
    }
    window.set_title(name).map_err(|_| "창 이름을 변경하지 못했어요.")?;
    Ok(())
}

#[tauri::command]
fn read_branding(app: tauri::AppHandle, window: tauri::WebviewWindow) -> Option<String> {
    if !matches!(window.label(), "main" | "answer") { return None; }
    let path = app.path().app_config_dir().ok()?.join("branding.json");
    if !fs::symlink_metadata(&path).ok()?.is_file() { return None; }
    const MAX_BYTES: u64 = 4096;
    let mut contents = String::new();
    fs::File::open(path).ok()?.take(MAX_BYTES + 1).read_to_string(&mut contents).ok()?;
    (contents.len() as u64 <= MAX_BYTES).then_some(contents)
}

#[tauri::command]
fn pet_drag_button_down(window: tauri::WebviewWindow) -> Result<Option<bool>, String> {
    require_window(&window, "main")?;
    #[cfg(target_os = "macos")]
    {
        #[link(name = "CoreGraphics", kind = "framework")]
        extern "C" {
            fn CGEventSourceButtonState(state_id: i32, button: u32) -> bool;
        }
        // The combined session state remains available during native window dragging.
        Ok(Some(unsafe { CGEventSourceButtonState(0, 0) }))
    }
    #[cfg(target_os = "windows")]
    {
        #[link(name = "user32")]
        extern "system" {
            fn GetAsyncKeyState(virtual_key: i32) -> i16;
            fn GetSystemMetrics(index: i32) -> i32;
        }
        // Follow the primary button mapping, including left-handed mouse settings.
        let primary = if unsafe { GetSystemMetrics(23) } != 0 { 0x02 } else { 0x01 };
        Ok(Some(unsafe { GetAsyncKeyState(primary) < 0 }))
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    { Ok(None) }
}

#[tauri::command]
async fn open_answer(app: tauri::AppHandle, window: tauri::WebviewWindow, state: tauri::State<'_, AnswerState>, snapshot: String) -> Result<(), String> {
    require_window(&window, "main")?;
    if snapshot.is_empty() || snapshot.len() > 2_100_000 { return Err("답변 크기 제한을 초과했어요.".into()); }
    *state.0.lock().map_err(|_| "답변을 열지 못했어요.")? = Some(snapshot);
    if let Some(reader) = app.get_webview_window("answer") {
        reader.emit("answer:update", ()).map_err(|_| "답변을 전달하지 못했어요.")?;
        reader.show().map_err(|_| "읽기 창을 표시하지 못했어요.")?;
        reader.set_focus().map_err(|_| "읽기 창을 표시하지 못했어요.")?;
    } else {
        tauri::WebviewWindowBuilder::new(&app, "answer", tauri::WebviewUrl::App("answer.html".into()))
            .title("새창")
            .inner_size(480.0, 560.0).min_inner_size(320.0, 320.0)
            .resizable(true).decorations(true).always_on_top(true)
            .visible_on_all_workspaces(true)
            // This window only loads its bundled reader, never a remote URL.
            .on_navigation(|url| matches!(url.as_str(), "tauri://localhost/answer.html" | "http://tauri.localhost/answer.html" | "http://127.0.0.1:1420/answer.html"))
            .build().map_err(|_| "읽기 창을 열지 못했어요.")?;
    }
    Ok(())
}

#[tauri::command]
fn read_answer(window: tauri::WebviewWindow, state: tauri::State<'_, AnswerState>) -> Result<Option<String>, String> {
    require_window(&window, "answer")?;
    Ok(state.0.lock().map_err(|_| "답변을 읽지 못했어요.")?.clone())
}

#[tauri::command]
fn close_answer(app: tauri::AppHandle, window: tauri::WebviewWindow, state: tauri::State<'_, AnswerState>) -> Result<(), String> {
    require_window(&window, "main")?;
    *state.0.lock().map_err(|_| "답변을 지우지 못했어요.")? = None;
    if let Some(reader) = app.get_webview_window("answer") { reader.close().map_err(|_| "읽기 창을 닫지 못했어요.")?; }
    Ok(())
}

#[tauri::command]
fn save_source(app: tauri::AppHandle, window: tauri::WebviewWindow, request: tauri::ipc::Request<'_>) -> Result<String, String> {
    require_window(&window, "main")?;
    let invalid = "다운로드할 파일 형식이 올바르지 않아요.";
    let tauri::ipc::InvokeBody::Raw(payload) = request.body() else { return Err(invalid.into()); };
    let boundary = payload.iter().take(256).position(|&byte| byte == 0).ok_or(invalid)?;
    let (name, bytes) = (&payload[..boundary], &payload[boundary + 1..]);
    let name = std::str::from_utf8(name).map_err(|_| invalid)?;
    if name.is_empty() || name.starts_with('.') || name.ends_with(['.', ' ']) || bytes.len() > 20 * 1024 * 1024
        || name.chars().any(|c| c.is_control() || "/\\:<>\"|?*".contains(c)) {
        return Err(invalid.into());
    }
    #[cfg(target_os = "windows")]
    if is_windows_device_name(name) { return Err(invalid.into()); }
    let directory = app.path().download_dir().map_err(|_| "다운로드 폴더를 찾지 못했어요.")?;
    let save_error = "다운로드 폴더에 파일을 저장하지 못했어요.";
    fs::create_dir_all(&directory).map_err(|_| save_error)?;
    let name_path = Path::new(name);
    let stem = name_path.file_stem().and_then(|s| s.to_str()).ok_or(invalid)?;
    let suffix = name_path.extension().and_then(|s| s.to_str()).map(|s| format!(".{s}")).unwrap_or_default();
    for index in 0..1000 {
        let saved_name = if index == 0 { name.to_owned() } else { format!("{stem} ({index}){suffix}") };
        let path = directory.join(&saved_name);
        match OpenOptions::new().write(true).create_new(true).open(&path) {
            Ok(mut file) => {
                if file.write_all(bytes).is_ok() {
                    drop(file);
                    return Ok(if is_openable_source(&path) && open_source(&path) {
                        format!("다운로드 폴더에 ‘{saved_name}’ 사본을 저장하고 열었어요.")
                    } else {
                        format!("다운로드 폴더에 ‘{saved_name}’ 사본을 저장했어요. 자동으로 열지 못했으니 다운로드 폴더에서 열어 주세요.")
                    });
                }
                drop(file);
                let _ = fs::remove_file(path);
                return Err(save_error.into());
            }
            Err(error) if error.kind() == ErrorKind::AlreadyExists => continue,
            Err(_) => return Err(save_error.into()),
        }
    }
    Err(save_error.into())
}

fn is_openable_source(path: &Path) -> bool {
    matches!(path.extension().and_then(|ext| ext.to_str()).map(str::to_ascii_lowercase).as_deref(),
        Some("md" | "txt" | "text" | "java" | "xml" | "json" | "sql" | "xlsx" | "xls" | "docx" | "pdf" | "png" | "jpg" | "jpeg"))
}

#[cfg(any(target_os = "windows", test))]
fn is_windows_device_name(name: &str) -> bool {
    let stem = name.split('.').next().unwrap_or_default().trim_end().to_ascii_uppercase();
    matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL") ||
        stem.strip_prefix("COM").or_else(|| stem.strip_prefix("LPT"))
            .is_some_and(|port| matches!(port, "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" | "¹" | "²" | "³"))
}

#[cfg(target_os = "macos")]
fn open_source(path: &Path) -> bool {
    if !std::process::Command::new("/usr/bin/xattr")
        .args(["-w", "com.apple.quarantine", "0081;00000000;MyChatBot;"])
        .arg(path).status().is_ok_and(|status| status.success()) { return false; }
    std::process::Command::new("/usr/bin/open").arg(path).status().is_ok_and(|status| status.success())
}

#[cfg(target_os = "windows")]
fn open_source(path: &Path) -> bool {
    use std::{ffi::OsString, os::windows::ffi::OsStrExt, ptr::null};
    use windows_sys::Win32::System::Com::{CoInitializeEx, CoUninitialize, COINIT_APARTMENTTHREADED, COINIT_DISABLE_OLE1DDE};
    use windows_sys::Win32::UI::{Shell::ShellExecuteW, WindowsAndMessaging::SW_SHOWNORMAL};
    // Keep Windows' downloaded-file protections when handing the copy to another app.
    let mut zone = OsString::from(path.as_os_str());
    zone.push(":Zone.Identifier");
    if fs::write(zone, b"[ZoneTransfer]\r\nZoneId=3\r\n").is_err() { return false; }
    let filename: Vec<u16> = path.as_os_str().encode_wide().chain([0]).collect();
    let verb: Vec<u16> = "open".encode_utf16().chain([0]).collect();
    // Invoke the file association directly without passing the path through a shell.
    unsafe {
        let initialized = CoInitializeEx(null(), (COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE) as u32) >= 0;
        let result = ShellExecuteW(std::ptr::null_mut(), verb.as_ptr(), filename.as_ptr(), null(), null(), SW_SHOWNORMAL);
        if initialized { CoUninitialize(); }
        result as isize > 32
    }
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
fn open_source(_: &Path) -> bool { false }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_supported_document_extensions_are_opened() {
        for name in ["approved-guide.md", "보고서 (2).XLSX", "manual.pdf"] {
            assert!(is_openable_source(Path::new(name)), "{name}");
        }
        for name in ["manual.pdf.exe", "report.lnk", "launch.command", "script.ps1", "launch.url", "document"] {
            assert!(!is_openable_source(Path::new(name)), "{name}");
        }
    }

    #[test]
    fn rejects_windows_device_names_even_with_document_extensions() {
        for name in ["NUL.pdf", "com1.xlsx", "LPT².md", "AUX .docx"] {
            assert!(is_windows_device_name(name), "{name}");
        }
        assert!(!is_windows_device_name("COM10.pdf"));
    }
}

fn show_pet(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.set_focus();
        let _ = window.emit("pet:show", ());
    }
}

pub fn run() {
    tauri::Builder::default()
        .manage(AnswerState::default())
        .invoke_handler(tauri::generate_handler![set_branding_name, read_branding, open_answer, read_answer, close_answer, save_source, pet_drag_button_down])
        .plugin(tauri_plugin_single_instance::init(|app, _, _| show_pet(app)))
        .plugin(
            tauri_plugin_window_state::Builder::default()
                .with_state_flags(StateFlags::POSITION | StateFlags::SIZE)
                .with_denylist(&["answer"])
                .build(),
        )
        .setup(|app| {
            let show = MenuItem::with_id(app, "show", "My Bot 표시", true, None::<&str>)?;
            let hide = MenuItem::with_id(app, "hide", "My Bot 숨김", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "종료", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &hide, &quit])?;
            app.manage(TrayLabels { show, hide });
            #[cfg(target_os = "macos")]
            let icon = tauri::image::Image::from_bytes(include_bytes!("../../../../assets/pet/tray-icon-macos.png"))?;
            #[cfg(not(target_os = "macos"))]
            let icon = tauri::image::Image::from_bytes(include_bytes!("../../../../assets/pet/tray-icon.png"))?;
            TrayIconBuilder::with_id("main-tray")
                .icon(icon)
                .icon_as_template(cfg!(target_os = "macos"))
                .tooltip("My Bot")
                .menu(&menu)
                .show_menu_on_left_click(true)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => show_pet(app),
                    "hide" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.hide();
                        }
                    }
                    "quit" => app.exit(0),
                    _ => (),
                })
                .build(app)?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("MyChatBot failed to start");
}
