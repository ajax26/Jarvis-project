"use client";

import { useEffect, useRef } from "react";

const COUNT = 1400;
const GOLDEN = Math.PI * (3 - Math.sqrt(5));

// Fibonacci lattice: evenly spread points on a unit sphere
const points = Array.from({ length: COUNT }, (_, i) => {
  const y = 1 - (i / (COUNT - 1)) * 2;
  const r = Math.sqrt(1 - y * y);
  const a = i * GOLDEN;
  return { x: Math.cos(a) * r, y, z: Math.sin(a) * r, lat: Math.asin(y), lon: a };
});

export default function DotSphere({ className = "" }: { className?: string }) {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current!;
    const ctx = canvas.getContext("2d")!;
    const still = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    let w = 0, h = 0, raf = 0;

    const resize = () => {
      const dpr = window.devicePixelRatio || 1;
      w = canvas.clientWidth;
      h = canvas.clientHeight;
      canvas.width = w * dpr;
      canvas.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    const ro = new ResizeObserver(resize);
    ro.observe(canvas);
    resize();

    const draw = (ms: number) => {
      const t = ms / 1000;
      const R = Math.min(w, h) * 0.38;
      const cx = w / 2, cy = h / 2, f = R * 3;
      const ry = t * 0.35, rx = Math.sin(t * 0.25) * 0.4;
      const cY = Math.cos(ry), sY = Math.sin(ry), cX = Math.cos(rx), sX = Math.sin(rx);

      ctx.clearRect(0, 0, w, h);

      for (const p of points) {
        // ripple bands travelling across the surface
        const k = 1 + 0.07 * Math.sin(p.lat * 6 - t * 2.2) * Math.cos(p.lon * 0.5 + t);
        const x0 = p.x * k, y0 = p.y * k, z0 = p.z * k;
        const x1 = x0 * cY + z0 * sY;
        const z1 = -x0 * sY + z0 * cY;
        const y2 = y0 * cX - z1 * sX;
        const z2 = y0 * sX + z1 * cX;

        const s = f / (f + z2 * R);
        const depth = (1 - z2) / 2; // 1 = front, 0 = back
        const hot = k > 1.05;
        // back #536684 -> front #98A9C4, ripple crests #DCE3EF
        ctx.fillStyle = hot
          ? `rgba(220,227,239,${0.4 + depth * 0.6})`
          : `rgba(${83 + depth * 69},${102 + depth * 67},${132 + depth * 64},${0.25 + depth * 0.7})`;
        ctx.beginPath();
        ctx.arc(cx + x1 * R * s, cy + y2 * R * s, (0.6 + depth * 1.4) * s, 0, Math.PI * 2);
        ctx.fill();
      }

      if (!still) raf = requestAnimationFrame(draw);
    };
    raf = requestAnimationFrame(draw);

    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
    };
  }, []);

  return <canvas ref={ref} className={className} aria-hidden="true" />;
}
