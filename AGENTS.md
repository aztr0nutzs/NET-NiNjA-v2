# AI Agents (RACI-ish)

## Android Lead (Codex in Android Studio)
Owns decisions about Kotlin/Gradle, app structure, WebView security, build variants.

## Web HUD Lead (Codex in Kiro)
Owns decisions about HTML/CSS/JS screens, animations, and asset packaging.

## Support Agent (Copilot)
Small refactors, helper code, quick UI tweaks, tests.

## Reviewer Agent (Claude)
Architecture review, edge cases, risk scan, missing requirements.

## Change control
- Changes touching BOTH Android + Web UI require Android Lead approval.
- No repo-wide rewrites. Small, targeted diffs only.
