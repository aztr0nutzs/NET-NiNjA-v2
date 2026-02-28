# Architecture

- Single Activity + WebView
- Loads `file:///android_asset/www/index.html`
- Debug WebView inspection enabled only in debug builds
- Native bridge is minimal, event-driven, and disabled by default until the HUD enables it
- Wi-Fi scan permissions are requested only from explicit scan actions
- Diagnostics export uses the system document picker (no storage permission launch flow)
