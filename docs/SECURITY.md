# WebView Security

- Mixed content: blocked
- No universal file URL access
- Debugging only in debug builds
- If adding a JS bridge later: expose minimal methods, validate inputs
- Native bridge is present but action-gated and disabled by default
- Wi-Fi permissions are requested only on scan actions, not app launch
- Diagnostics export uses SAF/Create Document instead of direct storage permissions
