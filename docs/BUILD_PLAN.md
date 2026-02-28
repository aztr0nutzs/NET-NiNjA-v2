# Multi-phase build plan

## Phase 0 (done here)
- Android WebView wrapper loads local HUD from assets
- AI guardrails: AGENTS + RULES + docs

## Phase 1: Web HUD hardening (HTML only)
- Remove CDNs, vendor all assets locally (`vendor/`)
- Fix all paths to be relative
- Performance switches: low/high FX
- Zero console errors

## Phase 2: Safe native bridge
- Add minimal JS bridge (disabled by default)
- Device info + export diagnostics (no launch permissions)

## Phase 3: Real Wi-Fi scan (optional)
- Request permissions only when user taps feature
- Demo mode fallback

## Phase 4: Release hardening
- R8, resource shrinking, disable debugging
- QA matrix + smoke tests
