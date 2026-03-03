# Control Wiring Matrix

## Audit Baseline

- Scope audited: `src/main/assets/www/index.html`, `src/main/assets/www/cyber_speed.html`, `src/main/assets/www/cyber_networks.html`, `src/main/assets/www/holo_devices2.html`, `src/main/assets/www/native-bridge.js`, `src/main/assets/www/holo-renderers.js`, `src/main/assets/www/holo-theme-manager.js`, `src/main/assets/www/holo-themes.js`, `src/main/assets/www/holo-motion.js`, `src/main/java/com/netninja/v21/MainActivity.kt`, `src/main/java/com/netninja/v21/NativeBridge.kt`, `src/main/java/com/netninja/v21/discovery/LanDiscovery.kt`, `src/main/java/com/netninja/v21/speedtest/SpeedtestEngine.kt`.
- Dominant current issue: `src/main/assets/www/index.html` contains unresolved merge-conflict markers at lines `2146-2157`. That breaks the main inline script parse, so controls defined after the parser enters that block do not bind at runtime.
- Because of that parse failure, most controls on the main shipped screen are currently `Broken` even when matching handler code exists later in the file.
- `cyber_speed.html`, `cyber_networks.html`, and `holo_devices2.html` are present as standalone/mock pages. The shipped Android shell loads `file:///android_asset/www/index.html` only, so those standalone pages are `Unreachable` from the current app unless loaded manually.
- Debug helper added: `src/main/assets/www/control-wiring-debug.js`. Enable with `?debugControlWiring=1` or `localStorage.setItem('netninja.debug.controlWiring', '1')`, then call `window.__NETNINJA_CONTROL_WIRING_DEBUG__.printReport()`.

## Native Bridge Surface

| JS-visible method | Native implementation | Notes |
| --- | --- | --- |
| `getPreference(key)` | `NativeBridge.getPreference` -> `MainActivity.getPreferenceValue` | Used by wrapper storage API |
| `setPreference(key, value)` | `NativeBridge.setPreference` -> `MainActivity.setPreferenceValue` | Used by wrapper storage API |
| `removePreference(key)` | `NativeBridge.removePreference` -> `MainActivity.removePreferenceValue` | Used by wrapper storage API |
| `getBridgeState()` | `NativeBridge.getBridgeState` -> `MainActivity.getBridgeStateJson` | Main UI bridge status source |
| `setBridgeEnabled(enabled)` | `NativeBridge.setBridgeEnabled` -> `MainActivity.setBridgeEnabled` | Main UI toggle path |
| `getDeviceInfo()` | `NativeBridge.getDeviceInfo` -> `MainActivity.getDeviceInfoJson` | Used by config screen |
| `requestWifiScan()` | `NativeBridge.requestWifiScan` -> `MainActivity.requestWifiScanFromJs` | Alias, not used by shipped UI |
| `getLastWifiScan()` | `NativeBridge.getLastWifiScan` -> `MainActivity.getLastWifiScanJson` | Alias, not used by shipped UI |
| `exportDiagnostics(payload)` | `NativeBridge.exportDiagnostics` -> `MainActivity.exportDiagnosticsFromJs` | Save-to-document flow |
| `speedtestStart(jsonConfig)` | `NativeBridge.speedtestStart` -> `MainActivity.startSpeedtestFromJs` | Native speedtest entry |
| `speedtestAbort()` | `NativeBridge.speedtestAbort` -> `MainActivity.abortSpeedtestFromJs` | Native speedtest abort |
| `speedtestReset()` | `NativeBridge.speedtestReset` -> `MainActivity.resetSpeedtestFromJs` | Abort-only reset |
| `lanScanStart(jsonConfig)` | `NativeBridge.lanScanStart` -> `MainActivity.startLanScanFromJs` | Native LAN scan entry |
| `lanScanStop()` | `NativeBridge.lanScanStop` -> `MainActivity.stopLanScanFromJs` | Scan stop path |
| `lanScanGetLast()` | `NativeBridge.lanScanGetLast` -> `MainActivity.getLastLanScanJson` | Cached scan restore |

## Main App Shell: `index.html`

| Screen name | Visible label text | DOM selector | Expected action | Current handler | Current status | Fix needed | Dependencies |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Speedtest / Header | `THEME` | `#btnTheme` | Open speedtest theme picker modal | `openThemeModal` | Broken | Remove merge-conflict markers so the binding at `index.html` executes | `HoloThemeManager`, modal DOM |
| Speedtest / Header | settings icon | `#btnSettings` | Open Shield settings/config tab | anonymous click -> `setActive("config")` | Broken | Remove merge-conflict markers so click listener binds | tab router in `index.html` |
| Speedtest / Controls | `ENGAGE` | `#btnStart` | Start native speedtest run | `startRun` | Broken | Remove merge-conflict markers so listener binds | bridge enabled, `speedtestStart`, `SpeedtestEngine` |
| Speedtest / Controls | `ABORT` | `#btnAbort` | Abort active speedtest | `abortRun` | Broken | Remove merge-conflict markers so listener binds | `speedtestAbort`, `SpeedtestEngine` |
| Speedtest / Controls | `RESET` | `#btnReset` | Reset speedtest UI and native engine state | anonymous click -> `speedtestReset` + `resetUI` | Broken | Remove merge-conflict markers so listener binds | `speedtestReset`, local UI state |
| Global nav | `Core` | `#navEngine` | Show speedtest screen | anonymous click -> `setActive("engine")` | Broken | Remove merge-conflict markers so nav listeners bind | renderer registry, theme manager |
| Global nav | `Graph` | `#navList` | Show LAN device list screen | anonymous click -> `setActive("list")` + `onListTabSelected` | Broken | Remove merge-conflict markers so nav listeners bind | renderer registry, bridge state, LAN scan cache |
| Global nav | `Nodes` | `#navMap` | Show LAN topology map screen | anonymous click -> `setActive("map")` + `renderMapFromScan` | Broken | Remove merge-conflict markers so nav listeners bind | map renderer, LAN scan cache |
| Global nav | `Shield` | `#navConfig` | Show settings/config screen | anonymous click -> `setActive("config")` | Broken | Remove merge-conflict markers so nav listeners bind | renderer registry |
| Theme modal | `Reset to Default` | `#themeReset` | Reset speedtest theme to default | anonymous click -> `HoloThemeManager.resetThemeForScreen("speedtest")` | Broken | Remove merge-conflict markers so listener binds | `HoloThemeManager`, gauge redraw |
| Theme modal | `✕` | `#themeClose` | Close theme modal | `closeThemeModal` | Broken | Remove merge-conflict markers so listener binds | modal DOM |
| Theme modal backdrop | no visible label | `#speedtestThemeBackdrop` | Close theme modal when backdrop tapped | `closeThemeModal` | Broken | Remove merge-conflict markers so listener binds | modal DOM |
| Theme modal options | theme label text generated at runtime | `.theme-option[data-theme-id]` | Apply selected speedtest theme | anonymous click in `buildThemeOption` | Broken | Remove merge-conflict markers so theme option generation and listener binding run | `HoloThemeManager`, theme registry |
| WiFi List / Header | `MEMORY` | `#wifiTelemetryBtn` | Show cached scan telemetry summary banner | anonymous click banner handler | Broken | Remove merge-conflict markers so listener binds | local runtime scan cache |
| WiFi List / Actions | `⚡ Scan LAN` | `#wifiScanBtn` | Start native LAN discovery | anonymous click -> `requestNativeWifiScan(true)` | Broken | Remove merge-conflict markers so listener binds | bridge enabled, LAN availability, `lanScanStart`, `LanDiscovery` |
| WiFi List / Actions | `📤 Export` | `#wifiExportBtn` | Export diagnostics JSON | `exportDiagnostics` | Broken | Remove merge-conflict markers so listener binds | bridge enabled, `exportDiagnostics`, Android document picker |
| WiFi List / Dynamic rows | hostname/IP/MAC row text generated at runtime | `#wifiDeviceList [data-device-ip]` | Open LAN device detail modal | anonymous click in `bindDeviceListClicks` -> `openDeviceDetail` | Broken | Remove merge-conflict markers so row rendering and click binding run | LAN scan results, modal DOM |
| Device detail modal | `✕` | `#deviceDetailClose` | Close device detail modal | `closeDeviceDetail` | Broken | Remove merge-conflict markers so listener binds | modal DOM |
| Device detail modal backdrop | no visible label | `#deviceDetailBackdrop` | Close device detail modal when backdrop tapped | `closeDeviceDetail` | Broken | Remove merge-conflict markers so listener binds | modal DOM |
| Config / Toggle | `Auto Scan` | `#cfgAutoScan` | Auto-start LAN scan when Graph tab is opened | shared config `change` handler | Broken | Remove merge-conflict markers so config listeners bind | config persistence, bridge state |
| Config / Toggle | `Holo FX` | `#cfgHoloFX` | Enable/disable overlay and particle FX | shared config `change` handler | Broken | Remove merge-conflict markers so config listeners bind | visual config only |
| Config / Toggle | `Alerts` | `#cfgAlerts` | Enable/disable non-error banners and map alert card | shared config `change` handler | Broken | Remove merge-conflict markers so config listeners bind | visual config only |
| Config / Toggle | `Fallback Mode (Disabled)` | `#cfgDemoMode` | User expects demo/fallback mode toggle | none beyond forced disable in `setConfigControls` | Misleading | Keep disabled label explicit or remove dormant control; it never enables a feature | local config only |
| Config / Toggle | `Native Bridge` | `#cfgBridgeEnabled` | Enable/disable native bridge features | shared config `change` handler -> `setBridgeEnabled` | Broken | Remove merge-conflict markers so toggle binds | `setBridgeEnabled`, bridge state event |
| Config / Slider | `Neon Intensity` | `#cfgNeonIntensity` | Preview and save neon glow level | `input` -> `applyConfigPreview`; `change` -> shared config handler | Broken | Remove merge-conflict markers so input/change listeners bind | visual config only |
| Config / Slider | `Scan Speed` | `#cfgScanSpeed` | Preview and save scan aggressiveness | `input` -> `applyConfigPreview`; `change` -> shared config handler | Broken | Remove merge-conflict markers so input/change listeners bind | LAN scan config builder |
| Config / Select | `Accent Mode` | `#cfgAccentMode` | Change accent palette | shared config `change` handler | Broken | Remove merge-conflict markers so change listener binds | visual config only |
| Config / Select | `Performance Mode` | `#cfgPerfMode` | Change effect density and LAN scan profile | shared config `change` handler | Broken | Remove merge-conflict markers so change listener binds | `applyPerfMode`, LAN scan config builder |
| Config / Actions | `💾 Save` | `#cfgSaveBtn` | Persist current settings | `saveConfig` | Broken | Remove merge-conflict markers so listener binds | bridge storage wrapper, optional `setBridgeEnabled` |
| Config / Actions | `♻️ Reset` | `#cfgResetBtn` | Restore defaults and clear cached settings | `resetConfig` | Broken | Remove merge-conflict markers so listener binds | bridge storage wrapper, cached scan reset |
| Config / Device | `🧠 Refresh Device` | `#cfgRefreshDeviceBtn` | Refresh native device snapshot | anonymous click -> `refreshDeviceInfo` | Broken | Remove merge-conflict markers so listener binds | bridge enabled, `getDeviceInfo` |
| Config / Device | `🛰️ Export Diag` | `#cfgExportDiagBtn` | Export diagnostics JSON | `exportDiagnostics` | Broken | Remove merge-conflict markers so listener binds | bridge enabled, Android document picker |
| Map / Header | computer icon button | `#mapDeviceMatrixBtn` | Jump from map screen to device matrix/list | anonymous click -> `setActive("list")` + `onListTabSelected` | Broken | Remove merge-conflict markers so listener binds | tab router, bridge/cache |
| Map / Search | placeholder `SEARCH HOSTNAME IP OR MAC...` | `#mapSearchInput` | Filter visible map/list nodes | `input` -> filter text + `renderMapFromScan` | Broken | Remove merge-conflict markers so listener binds | LAN scan results, map renderer |
| Map / Empty state CTA | `Run Scan` | `#mapRunScanBtn` | Start scan and switch to Graph view | anonymous click -> `setActive("list")` + `requestNativeWifiScan(true)` | Broken | Remove merge-conflict markers so listener binds | bridge enabled, `lanScanStart` |
| Map / Mode | `Overdrive` | `#mapOverdriveBtn` | Toggle higher-energy map rendering mode | anonymous click toggle | Broken | Remove merge-conflict markers so listener binds | map renderer `setOverdriveState` |
| Map / Mode | `Cloak` | `#mapCloakBtn` | Toggle dim/stealth map rendering mode | anonymous click toggle | Broken | Remove merge-conflict markers so listener binds | map renderer `setCloakState` |
| Map / Dynamic cards | `Node_#` card content generated at runtime | `#mapNodesList [data-device-ip]` | Open LAN device detail modal | anonymous click in `bindDeviceListClicks` -> `openDeviceDetail` | Broken | Remove merge-conflict markers so row rendering and click binding run | LAN scan results, modal DOM |
| Map / Canvas node hit target | rendered topology nodes | `#holoMapViewport` / `#holoMapCanvas` | Pan/zoom/select a node and open details on tap | pointer handlers in `createHoloMapRenderer`; tap path -> `openDeviceDetail` | Broken | Remove merge-conflict markers so renderer setup and pointer listeners run | canvas renderer, LAN scan results |
| Global keyboard shortcut | `Escape` | `document` | Close theme or device-detail modal | anonymous `keydown` listener | Broken | Remove merge-conflict markers so document listener binds | modal DOM |

## Standalone Page: `cyber_speed.html`

Status note: this page has working standalone handlers, but it is not loaded by the Android shell today because `MainActivity` loads `index.html` only.

| Screen name | Visible label text | DOM selector | Expected action | Current handler | Current status | Fix needed | Dependencies |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Cyber Speed / Header | settings icon | `#btnSettings` | Show environment/settings info | anonymous click -> `alert(...)` | Unreachable | Load this page or embed it in current shell if intended to ship | browser-only |
| Cyber Speed / Footer | `SAFE_MODE` switch | `#safeSwitch` | Toggle gentler vs spicier test profile | anonymous click/keydown toggles `state.safeMode` | Unreachable | Load this page or wire its setting into host shell | local page state |
| Cyber Speed / Controls | `STOP` | `#btnAbort` | Abort running test | anonymous click | Unreachable | Load this page or keep as non-shipped prototype | local page state; host message in shell mode |
| Cyber Speed / Controls | `RESET` | `#btnReset` | Reset test UI | anonymous click -> `resetUI` | Unreachable | Load this page or keep as non-shipped prototype | local page state; host message in shell mode |
| Cyber Speed / Controls | `START` | `#btnStart` | Start speedtest | anonymous click; in host shell emits `nn_speedtest_start`, otherwise runs browser test | Unreachable | Load this page or keep as non-shipped prototype | host `postMessage` or browser fetch-based test |
| Cyber Speed / Nav | `HUD` | `nav .tab[data-tab="engine"]` | Switch active tab to HUD | anonymous click -> active class + `postMessage` | Unreachable | Load this page or keep as non-shipped prototype | host `postMessage` consumer |
| Cyber Speed / Nav | `ANALYTICS` | `nav .tab[data-tab="list"]` | Navigate to list/analytics tab | anonymous click -> active class + `postMessage` | Unreachable | Load this page or keep as non-shipped prototype | host `postMessage` consumer |
| Cyber Speed / Nav | `NODES` | `nav .tab[data-tab="map"]` | Navigate to map tab | anonymous click -> active class + `postMessage` | Unreachable | Load this page or keep as non-shipped prototype | host `postMessage` consumer |
| Cyber Speed / Nav | `ARCHIVES` | `nav .tab[data-tab="config"]` | Navigate to config tab | anonymous click -> active class + `postMessage` | Unreachable | Load this page or keep as non-shipped prototype | host `postMessage` consumer |

## Standalone Page: `cyber_networks.html`

Status note: purely static markup in current repo snapshot. No page script, no ids, and no runtime binding.

| Screen name | Visible label text | DOM selector | Expected action | Current handler | Current status | Fix needed | Dependencies |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Cyber Networks / Header | analytics icon | `button:has(.material-symbols-outlined)` first header icon | Likely open analytics overlay | none | Unreachable | Add explicit selector/id and bind or remove if decorative only | none |
| Cyber Networks / Header | notifications icon | `button:has(.material-symbols-outlined)` second header icon | Likely open alerts/notifications | none | Unreachable | Add explicit selector/id and bind or remove if decorative only | none |
| Cyber Networks / Controls | add icon | left vertical control stack first button | Likely zoom in or add node | none | Unreachable | Add explicit selector/id and bind or remove if decorative only | none |
| Cyber Networks / Controls | remove icon | left vertical control stack second button | Likely zoom out or remove layer | none | Unreachable | Add explicit selector/id and bind or remove if decorative only | none |
| Cyber Networks / Controls | near_me icon | floating circular button | Likely center/recenter map | none | Unreachable | Add explicit selector/id and bind or remove if decorative only | none |
| Cyber Networks / Search | placeholder `SCAN_NODE` | search input without id | Search a node | none | Unreachable | Add explicit selector/id and bind if this page is meant to ship | none |
| Cyber Networks / CTA | `BRIDGE_DATA` | major hub card button | Likely open node bridge details | none | Unreachable | Add explicit selector/id and bind or remove if decorative only | none |
| Cyber Networks / Bottom nav | `Topology` | bottom nav first anchor | Navigate to topology view | none | Unreachable | Replace `href="#"` placeholders with real navigation if page is intended to ship | none |
| Cyber Networks / Bottom nav | `Cores` | bottom nav second anchor | Navigate to cores view | none | Unreachable | Replace placeholder anchor with real navigation | none |
| Cyber Networks / Bottom nav | rocket icon | center launch button | Likely run scan or launch action | none | Unreachable | Add explicit selector/id and bind or remove if decorative only | none |
| Cyber Networks / Bottom nav | `Arteries` | bottom nav fourth anchor | Navigate to network links view | none | Unreachable | Replace placeholder anchor with real navigation | none |
| Cyber Networks / Bottom nav | `Config` | bottom nav fifth anchor | Navigate to configuration | none | Unreachable | Replace placeholder anchor with real navigation | none |

## Standalone Page: `holo_devices2.html`

Status note: static prototype markup in current repo snapshot. No page script, no ids, and no runtime binding.

| Screen name | Visible label text | DOM selector | Expected action | Current handler | Current status | Fix needed | Dependencies |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Holo Devices / CTA | `System Override` | primary CTA button near line 363 | Likely open device override/action | none | Unreachable | Add explicit selector/id and bind or remove if decorative only | none |
| Holo Devices / Bottom nav | `Overview` | bottom nav first button | Navigate to overview | none | Unreachable | Add explicit selector/id and bind if page is intended to ship | none |
| Holo Devices / Bottom nav | `Devices` | bottom nav second button | Navigate to devices list | none | Unreachable | Add explicit selector/id and bind if page is intended to ship | none |
| Holo Devices / Bottom nav | `Security` | bottom nav third button | Navigate to security | none | Unreachable | Add explicit selector/id and bind if page is intended to ship | none |
| Holo Devices / Bottom nav | `Settings` | bottom nav fourth button | Navigate to settings | none | Unreachable | Add explicit selector/id and bind if page is intended to ship | none |

## Notes On Current Wiring Gaps

- `index.html` is the only shipped page loaded by Android.
- The `speedFrame`, `networksFrame`, and `nodesFrame` iframes exist in `index.html` but do not have `src` set, so host `postMessage(...)` relays to them are currently no-ops.
- `cyber_speed.html` can send host messages (`nn_speedtest_start`, `nn_speedtest_abort`, `nn_speedtest_reset`, `nn_nav_tab`), but the shipped shell does not load it into `#speedFrame`.
- `cyber_speed.html` expects `payload.phaseProgress` for progress-bar updates, while native speedtest events emit `progress`, so even if the iframe were loaded its progress bars would still not fully track native events.
- `requestWifiScan()` and `getLastWifiScan()` remain exposed in `NativeBridge.kt` but the shipped UI uses `lanScanStart()` and `lanScanGetLast()` instead.
- Export is save-to-document only. No Android share-sheet bridge exists.

