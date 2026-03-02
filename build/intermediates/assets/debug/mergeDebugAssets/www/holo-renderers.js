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
        if (activeScreenId && activeScreenId !== screenId) {
          this.stop(activeScreenId);
        }
        if (activeScreenId === screenId && !force) {
          return;
        }
        const items = list(screenId);
        if (force) {
          items.forEach((renderer) => renderer.stop());
        }
        activeScreenId = screenId;
        items.forEach((renderer) => {
          renderer.resize();
          renderer.start();
        });
      },
      stop(screenId) {
        if (!screenId) return;
        list(screenId).forEach((renderer) => renderer.stop());
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
      resizeActive() {
        if (!activeScreenId) return;
        list(activeScreenId).forEach((renderer) => renderer.resize());
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
