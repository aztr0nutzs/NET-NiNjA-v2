(function attachControlWiringDebug(global) {
  const DEBUG_ENABLED =
    new URLSearchParams(global.location.search).get("debugControlWiring") === "1" ||
    global.localStorage.getItem("netninja.debug.controlWiring") === "1";

  if (!DEBUG_ENABLED) return;

  const listenerStore = new WeakMap();
  const originalAddEventListener = EventTarget.prototype.addEventListener;

  function safeSelector(element) {
    if (!(element instanceof Element)) return "";
    if (element.id) return `#${element.id}`;
    const name = element.getAttribute("name");
    if (name) return `${element.tagName.toLowerCase()}[name="${name}"]`;
    const dataTab = element.getAttribute("data-tab");
    if (dataTab) return `${element.tagName.toLowerCase()}[data-tab="${dataTab}"]`;
    const dataThemeId = element.getAttribute("data-theme-id");
    if (dataThemeId) return `${element.tagName.toLowerCase()}[data-theme-id="${dataThemeId}"]`;
    const dataDeviceIp = element.getAttribute("data-device-ip");
    if (dataDeviceIp) return `${element.tagName.toLowerCase()}[data-device-ip="${dataDeviceIp}"]`;
    const classes = Array.from(element.classList || []).slice(0, 3);
    if (classes.length > 0) return `${element.tagName.toLowerCase()}.${classes.join(".")}`;
    return element.tagName.toLowerCase();
  }

  function controlLabel(element) {
    if (!(element instanceof Element)) return "";
    const aria = element.getAttribute("aria-label");
    if (aria) return aria.trim();
    const value = "value" in element ? String(element.value || "").trim() : "";
    if (value) return value;
    return element.textContent ? element.textContent.replace(/\s+/g, " ").trim() : "";
  }

  EventTarget.prototype.addEventListener = function patchedAddEventListener(type, listener, options) {
    if (this instanceof Element) {
      const existing = listenerStore.get(this) || [];
      existing.push({
        type,
        listenerName: typeof listener === "function" && listener.name ? listener.name : "(anonymous)",
      });
      listenerStore.set(this, existing);
    }
    return originalAddEventListener.call(this, type, listener, options);
  };

  function collectControls(root) {
    return Array.from(
      root.querySelectorAll(
        [
          "button",
          "input",
          "select",
          "a[href]",
          "[role='button']",
          "[data-action]",
          "[data-tab]",
          "[data-device-ip]",
        ].join(","),
      ),
    );
  }

  function getControlReport() {
    return collectControls(document).map((element) => {
      const listeners = listenerStore.get(element) || [];
      return {
        selector: safeSelector(element),
        tag: element.tagName.toLowerCase(),
        label: controlLabel(element),
        handlerCount: listeners.length,
        handlers: listeners,
        inlineHandler: ["onclick", "oninput", "onchange", "onkeydown"].some((name) => typeof element[name] === "function"),
        disabled: "disabled" in element ? !!element.disabled : false,
        hidden:
          element.hasAttribute("hidden") ||
          element.getAttribute("aria-hidden") === "true" ||
          global.getComputedStyle(element).display === "none",
      };
    });
  }

  global.__NETNINJA_CONTROL_WIRING_DEBUG__ = {
    enabled: true,
    getReport: getControlReport,
    printReport() {
      const report = getControlReport();
      console.table(
        report.map((item) => ({
          selector: item.selector,
          tag: item.tag,
          label: item.label,
          handlerCount: item.handlerCount,
          inlineHandler: item.inlineHandler,
          disabled: item.disabled,
          hidden: item.hidden,
        })),
      );
      return report;
    },
  };

  global.addEventListener("DOMContentLoaded", () => {
    global.__NETNINJA_CONTROL_WIRING_DEBUG__.printReport();
  }, { once: true });
})(window);
