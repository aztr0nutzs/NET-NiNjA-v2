window.HoloMotion = (() => {
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const lerp = (a, b, t) => a + (b - a) * t;

  function spring(current, target, stiffness = 0.12) {
    return lerp(current, target, clamp(stiffness, 0.01, 1));
  }

  function animate(onFrame) {
    let raf = 0;
    const loop = (ts) => {
      onFrame(ts);
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }

  function attachParallax(root, selector = '[data-depth]') {
    if (!root || reducedMotion) return () => {};
    const items = Array.from(root.querySelectorAll(selector));
    if (!items.length) return () => {};

    let tx = 0;
    let ty = 0;
    let cx = 0;
    let cy = 0;

    const onMove = (event) => {
      const rect = root.getBoundingClientRect();
      const x = (event.clientX - rect.left) / Math.max(rect.width, 1);
      const y = (event.clientY - rect.top) / Math.max(rect.height, 1);
      tx = (x - 0.5) * 2;
      ty = (y - 0.5) * 2;
    };

    const stop = animate(() => {
      cx = spring(cx, tx, 0.08);
      cy = spring(cy, ty, 0.08);
      items.forEach((item) => {
        const depth = Number(item.dataset.depth || 1);
        item.style.transform = `translate3d(${(cx * depth * 5).toFixed(2)}px, ${(cy * depth * 3.5).toFixed(2)}px, 0)`;
      });
    });

    root.addEventListener('pointermove', onMove, { passive: true });
    root.addEventListener('pointerleave', () => {
      tx = 0;
      ty = 0;
    });

    return () => {
      root.removeEventListener('pointermove', onMove);
      stop();
    };
  }

  return { reducedMotion, clamp, lerp, spring, animate, attachParallax };
})();
