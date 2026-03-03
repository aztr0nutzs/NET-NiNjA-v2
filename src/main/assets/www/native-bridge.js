(function attachNetNinjaNativeBridge(global) {
  const BRIDGE_NAME = "NetNinjaBridge";
  const NATIVE_EVENT = "netninja:native";
  const listeners = new Set();
  let feedbackHandler = null;

  function bridge() {
    return global[BRIDGE_NAME] || null;
  }

  function available() {
    return !!bridge();
  }

  function emitFeedback(message, level = "error") {
    if (!message || typeof feedbackHandler !== "function") return;
    feedbackHandler(String(message), String(level || "error"));
  }

  function normalizeResult(rawValue) {
    if (rawValue == null || rawValue === "") {
      return { ok: true };
    }

    if (typeof rawValue === "string") {
      try {
        const parsed = JSON.parse(rawValue);
        return parsed && typeof parsed === "object"
          ? parsed
          : { ok: true, value: parsed };
      } catch {
        return { ok: true, value: rawValue };
      }
    }

    if (typeof rawValue === "object") {
      return rawValue;
    }

    return { ok: true, value: rawValue };
  }

  function buildBridgeError(message, code, result) {
    const error = new Error(String(message || "Native bridge call failed."));
    error.code = code || "bridge_error";
    if (result !== undefined) error.result = result;
    return error;
  }

  function call(method, args, options = {}) {
    const callArgs = Array.isArray(args) ? args : [];
    const bridgeApi = bridge();
    if (!bridgeApi || typeof bridgeApi[method] !== "function") {
      const reason = !bridgeApi ? "Native bridge unavailable." : `Native bridge method unavailable: ${method}`;
      const error = buildBridgeError(reason, "bridge_unavailable");
      if (options.feedback !== false) {
        emitFeedback(options.feedbackMessage || reason, options.feedbackLevel || "error");
      }
      throw error;
    }

    try {
      const result = normalizeResult(bridgeApi[method](...callArgs));
      if (result && typeof result === "object" && result.ok === false) {
        const message = result.message || options.feedbackMessage || `${method} failed.`;
        if (options.feedback !== false) {
          emitFeedback(message, options.feedbackLevel || "error");
        }
        throw buildBridgeError(message, result.code || "bridge_call_failed", result);
      }
      return result;
    } catch (error) {
      if (options.feedback !== false) {
        const message = error?.message || options.feedbackMessage || `${method} failed.`;
        emitFeedback(message, options.feedbackLevel || "error");
      }
      if (error instanceof Error) throw error;
      throw buildBridgeError(String(error || `${method} failed.`), "bridge_exception");
    }
  }

  function request(method, args, options = {}) {
    try {
      return call(method, args, options);
    } catch (error) {
      if (options.throwOnError) throw error;
      return null;
    }
  }

  function invoke(method, args, options = {}) {
    return request(method, args, { ...options, throwOnError: false }) != null;
  }

  function subscribe(listener) {
    if (typeof listener !== "function") return function noop() {};
    listeners.add(listener);
    return () => listeners.delete(listener);
  }

  function dispatchNativeEvent(event) {
    const detail = event?.detail || {};
    listeners.forEach((listener) => {
      try {
        listener(detail);
      } catch (error) {
        console.error("[NetNinjaNativeBridge] listener failed", error);
      }
    });
  }

  if (!global.__NETNINJA_NATIVE_BRIDGE_READY) {
    global.addEventListener(NATIVE_EVENT, dispatchNativeEvent);
    global.__NETNINJA_NATIVE_BRIDGE_READY = true;
  }

  global.NetNinjaNativeBridge = {
    BRIDGE_NAME,
    NATIVE_EVENT,
    available,
    call(method, ...args) {
      return call(method, args);
    },
    request(method, ...args) {
      return request(method, args);
    },
    invoke(method, ...args) {
      return invoke(method, args);
    },
    on: subscribe,
    setFeedbackHandler(handler) {
      feedbackHandler = typeof handler === "function" ? handler : null;
    },
    storage: {
      get(key) {
        const result = request("getPreference", [String(key)], { feedback: false });
        if (result == null) return global.localStorage.getItem(String(key));
        if (Object.prototype.hasOwnProperty.call(result, "value")) {
          return result.value == null ? null : String(result.value);
        }
        return bridge()?.getPreference?.(String(key)) ?? global.localStorage.getItem(String(key));
      },
      set(key, value) {
        if (!invoke("setPreference", [String(key), String(value)], { feedback: false })) {
          global.localStorage.setItem(String(key), String(value));
        }
      },
      remove(key) {
        if (!invoke("removePreference", [String(key)], { feedback: false })) {
          global.localStorage.removeItem(String(key));
        }
      },
    },
  };
})(window);
