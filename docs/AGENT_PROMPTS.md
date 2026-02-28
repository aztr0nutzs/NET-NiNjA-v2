# Copy/paste prompts

## Codex (Web HUD Lead) - vendorization
Work ONLY in `src/main/assets/www/`.
Goal: remove ALL external URLs and vendor required assets locally under `vendor/`.
Preserve visuals/animations/behavior. Keep mobile-first.

## Codex (Android Lead) - native bridge stub
Work ONLY in Kotlin/Manifest.
Goal: add safe JS bridge stub (disabled by default) + debug-only toggle UI.

## Claude (Reviewer)
Produce a punch list with exact file paths and concrete edits.
