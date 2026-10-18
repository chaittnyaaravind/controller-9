# ADR 0002 — Tauri v2 shell with one static frontend

**Status:** accepted (2026-10-17)

**Decision.** The desktop companion is a Tauri v2 app (`hosts/app`) whose UI is plain HTML/CSS/JS
(`hosts/app/ui`, no bundler, no npm runtime dependencies). Rust commands expose the host core.

**Why.** One UI for three OSes; native windows and installers (DMG/MSI-NSIS/deb/AppImage) from
`tauri build`; the webview is the OS's own (WKWebView / WebView2 / WebKitGTK).

**Trade-offs.** Linux builds need WebKitGTK dev packages; Windows needs the WebView2 runtime (present
on Windows 11). A strict CSP blocks remote content; the UI never loads anything from the network.
