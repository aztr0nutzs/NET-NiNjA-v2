(function attachHoloRenderers(global) {
  function createRegistry() {
    const renderersByScreen = new Map();
    let activeScreenId = null;

    function list(screenId) {
      if (!renderersByScreen.has(screenId)) {
        renderersByScreen.set(screenId, []);
      }
      return renderersByScreen.get(screenId);
    }

    function forScreen(screenId, callback) {
      if (!screenId || typeof callback !== "function") return;
      list(screenId).forEach((renderer) => callback(renderer));
    }

    return {
      register(screenId, renderer) {
        if (!screenId || !renderer) return;
        if (typeof renderer.start !== "function" || typeof renderer.stop !== "function" || typeof renderer.resize !== "function") {
          throw new Error("Renderer must implement start(), stop(), resize()");
        }
        const items = list(screenId);
        if (!items.includes(renderer)) {
          items.push(renderer);
        }
      },
      start(screenId, options = {}) {
        if (!screenId) return;
        const force = !!options.force;
        const skipResize = !!options.skipResize;
        if (activeScreenId && activeScreenId !== screenId) {
          this.stop(activeScreenId);
        }
        if (activeScreenId === screenId && !force) {
          return;
        }
        if (force) {
          forScreen(screenId, (renderer) => renderer.stop());
        }
        activeScreenId = screenId;
        forScreen(screenId, (renderer) => {
          if (!skipResize) {
            renderer.resize();
          }
          renderer.start();
        });
      },
      stop(screenId) {
        if (!screenId) return;
        forScreen(screenId, (renderer) => renderer.stop());
        if (activeScreenId === screenId) {
          activeScreenId = null;
        }
      },
      stopAll() {
        for (const items of renderersByScreen.values()) {
          items.forEach((renderer) => renderer.stop());
        }
        activeScreenId = null;
      },
      resize(screenId) {
        forScreen(screenId, (renderer) => renderer.resize());
      },
      resizeActive(screenId = activeScreenId) {
        if (!screenId) return;
        this.resize(screenId);
      },
      getActiveScreenId() {
        return activeScreenId;
      },
      has(screenId) {
        return list(screenId).length > 0;
      },
    };
  }

  global.HoloRenderers = {
    createRegistry,
  };
})(window);
