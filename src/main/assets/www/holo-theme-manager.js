(function attachThemeManager(global) {
  const STORAGE_PREFIX = "theme.screen.";
  const DEFAULT_THEME_ID = "holo_cyan";
  const DEBUG_ENABLED =
    new URLSearchParams(global.location?.search || "").get("debugThemes") === "1" ||
    global.localStorage?.getItem("netninja.debug.themes") === "1";

  function debugLog(message, extra) {
    if (!DEBUG_ENABLED) return;
    if (typeof extra === "undefined") {
      console.debug(`[theme] ${message}`);
    } else {
      console.debug(`[theme] ${message}`, extra);
    }
  }

  function hasBridgeStorage() {
    const bridge = global.NetNinjaBridge;
    return !!(
      bridge &&
      typeof bridge.getPreference === "function" &&
      typeof bridge.setPreference === "function" &&
      typeof bridge.removePreference === "function"
    );
  }

  function storageKeyFor(screenId) {
    return `${STORAGE_PREFIX}${screenId}`;
  }

  function bridgeGet(key) {
    if (!hasBridgeStorage()) return null;
    try {
      const value = global.NetNinjaBridge.getPreference(key);
      return typeof value === "string" ? value : null;
    } catch {
      return null;
    }
  }

  function bridgeSet(key, value) {
    if (!hasBridgeStorage()) return false;
    try {
      global.NetNinjaBridge.setPreference(key, String(value));
      return true;
    } catch {
      return false;
    }
  }

  function bridgeRemove(key) {
    if (!hasBridgeStorage()) return false;
    try {
      global.NetNinjaBridge.removePreference(key);
      return true;
    } catch {
      return false;
    }
  }

  function readStoredThemeId(screenId) {
    const key = storageKeyFor(screenId);
    const fromBridge = bridgeGet(key);
    if (fromBridge) return fromBridge;
    return global.localStorage.getItem(key);
  }

  function writeStoredThemeId(screenId, themeId) {
    const key = storageKeyFor(screenId);
    if (!bridgeSet(key, themeId)) {
      global.localStorage.setItem(key, themeId);
    }
  }

  function removeStoredThemeId(screenId) {
    const key = storageKeyFor(screenId);
    if (!bridgeRemove(key)) {
      global.localStorage.removeItem(key);
    }
  }

  function resolveTheme(themeId) {
    const registry = global.HoloThemeRegistry || global.HoloThemes;
    if (!registry || typeof registry.getTheme !== "function") return null;
    return registry.getTheme(themeId);
  }

  function getThemeRegistry() {
    const registry = global.HoloThemeRegistry || global.HoloThemes;
    if (!registry || typeof registry.getAllThemes !== "function") return null;
    return registry;
  }

  function hasValidThemeId(themeId) {
    const registry = getThemeRegistry();
    if (!registry) return false;
    if (typeof registry.hasTheme === "function") {
      return registry.hasTheme(themeId);
    }
    return !!resolveTheme(themeId);
  }

  function getStyleTag() {
    return document.getElementById("theme-vars");
  }

  function clearAppliedTheme() {
    const styleTag = getStyleTag();
    if (styleTag) {
      styleTag.remove();
    }
    document.documentElement.removeAttribute("data-theme-id");
    document.documentElement.removeAttribute("data-theme-override");
    document.documentElement.removeAttribute("data-theme-particles");
    document.documentElement.style.removeProperty("--scanline-opacity");
    document.documentElement.style.removeProperty("--effect-glow-strength");
    document.documentElement.style.removeProperty("--effect-bloom-strength");
    debugLog("cleared override");
    return null;
  }

  function applyEffects(theme) {
    const root = document.documentElement;
    const effects = theme.effects || {};
    if (typeof effects.scanlines === "number") {
      root.style.setProperty("--scanline-opacity", String(effects.scanlines));
    }
    if (typeof effects.glowStrength === "number") {
      root.style.setProperty("--effect-glow-strength", String(effects.glowStrength));
    }
    if (typeof effects.bloomStrength === "number") {
      root.style.setProperty("--effect-bloom-strength", String(effects.bloomStrength));
    }
    root.dataset.themeParticles = effects.particles === false ? "off" : "on";
  }

  function applyTheme(themeId) {
    const theme = resolveTheme(themeId);
    if (!theme) {
      debugLog("applyTheme skipped", { requested: themeId, reason: "invalid-theme" });
      return clearAppliedTheme();
    }
    let styleTag = getStyleTag();
    if (!styleTag) {
      styleTag = document.createElement("style");
      styleTag.id = "theme-vars";
      document.head.appendChild(styleTag);
    }
    const vars = Object.entries(theme.vars || {})
      .map(([key, value]) => `${key}:${value};`)
      .join("");
    styleTag.textContent = `:root{${vars}}`;
    document.documentElement.dataset.themeId = theme.id;
    document.documentElement.dataset.themeOverride = "on";
    applyEffects(theme);
    debugLog("applied", { themeId: theme.id, themeCount: getThemeRegistry()?.getAllThemes?.().length || 0 });
    return theme.id;
  }

  function getThemeIdForScreen(screenId) {
    const raw = readStoredThemeId(screenId);
    return hasValidThemeId(raw) ? raw : null;
  }

  function setThemeIdForScreen(screenId, themeId) {
    if (!hasValidThemeId(themeId)) {
      debugLog("setThemeIdForScreen skipped", { screenId, requested: themeId });
      return null;
    }
    const resolved = applyTheme(themeId);
    if (!resolved) return null;
    writeStoredThemeId(screenId, resolved);
    return resolved;
  }

  function resetThemeForScreen(screenId) {
    removeStoredThemeId(screenId);
    clearAppliedTheme();
    return null;
  }

  function applyThemeForScreen(screenId) {
    const storedThemeId = getThemeIdForScreen(screenId);
    if (!storedThemeId) {
      return clearAppliedTheme();
    }
    return applyTheme(storedThemeId);
  }

  global.HoloThemeManager = {
    DEFAULT_THEME_ID,
    clearAppliedTheme,
    hasValidThemeId,
    getThemeRegistry,
    getThemeIdForScreen,
    setThemeIdForScreen,
    resetThemeForScreen,
    applyTheme,
    applyThemeForScreen
  };
  global.__NETNINJA_THEME_MANAGER_READY = true;
})(window);
