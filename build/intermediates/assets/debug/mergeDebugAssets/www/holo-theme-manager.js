(function attachThemeManager(global) {
  const STORAGE_PREFIX = "theme.screen.";
  const DEFAULT_THEME_ID = "holo_cyan";
  const STORAGE_FALLBACK = new Map();
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

  function canUseLocalStorage() {
    try {
      return !!global.localStorage;
    } catch {
      return false;
    }
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
    if (canUseLocalStorage()) {
      try {
        const localValue = global.localStorage.getItem(key);
        if (typeof localValue === "string" && localValue) return localValue;
      } catch {}
    }
    return STORAGE_FALLBACK.get(key) || null;
  }

  function writeStoredThemeId(screenId, themeId) {
    const key = storageKeyFor(screenId);
    if (!bridgeSet(key, themeId)) {
      if (canUseLocalStorage()) {
        try {
          global.localStorage.setItem(key, themeId);
          return;
        } catch {}
      }
      STORAGE_FALLBACK.set(key, themeId);
    }
  }

  function removeStoredThemeId(screenId) {
    const key = storageKeyFor(screenId);
    if (!bridgeRemove(key)) {
      if (canUseLocalStorage()) {
        try {
          global.localStorage.removeItem(key);
        } catch {}
      }
      STORAGE_FALLBACK.delete(key);
    }
  }

  function resolveTheme(themeId) {
    const registry = global.HoloThemeRegistry || global.HoloThemes;
    if (!registry || typeof registry.getTheme !== "function") return null;
    return registry.getTheme(themeId || DEFAULT_THEME_ID);
  }

  function getThemeRegistry() {
    const registry = global.HoloThemeRegistry || global.HoloThemes;
    if (!registry || typeof registry.getAllThemes !== "function") return null;
    return registry;
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
      debugLog("applyTheme fallback", { requested: themeId, resolved: DEFAULT_THEME_ID, reason: "missing-registry" });
      return DEFAULT_THEME_ID;
    }
    let styleTag = document.getElementById("theme-vars");
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
    applyEffects(theme);
    global.__NETNINJA_THEME_MANAGER_READY = true;
    debugLog("applied", { themeId: theme.id, themeCount: getThemeRegistry()?.getAllThemes?.().length || 0 });
    return theme.id;
  }

  function getThemeIdForScreen(screenId) {
    const raw = readStoredThemeId(screenId);
    const theme = resolveTheme(raw || DEFAULT_THEME_ID);
    return theme?.id || DEFAULT_THEME_ID;
  }

  function setThemeIdForScreen(screenId, themeId) {
    const resolved = applyTheme(themeId);
    writeStoredThemeId(screenId, resolved);
    return resolved;
  }

  function resetThemeForScreen(screenId) {
    removeStoredThemeId(screenId);
    const resolved = applyTheme(DEFAULT_THEME_ID);
    return resolved;
  }

  function applyThemeForScreen(screenId) {
    const resolved = getThemeIdForScreen(screenId);
    return applyTheme(resolved);
  }

  global.HoloThemeManager = {
    DEFAULT_THEME_ID,
    getThemeRegistry,
    getThemeIdForScreen,
    setThemeIdForScreen,
    resetThemeForScreen,
    applyTheme,
    applyThemeForScreen
  };
  global.__NETNINJA_THEME_MANAGER_READY = true;
})(window);
