import React from 'react';

/**
 * The backdrop behind the whole film: soft folds of green light drifting through the dark, like
 * silk under a slow light. Pure function of time `t` (seconds), so every frame renders the same.
 * `px`/`py` nudge it with the camera for a touch of parallax; `bright` 0…1 floods it pale for
 * the big statements.
 */

type Fold = {
  /** centre, in screen px, and how far it drifts */
  x: number;
  y: number;
  dx: number;
  dy: number;
  /** size of the ellipse and its rotation */
  w: number;
  h: number;
  rot: number;
  /** drift speed and phase */
  speed: number;
  phase: number;
  color: string;
  /** a ring (a fold's lit edge) or a filled glow */
  ring?: boolean;
  alpha: number;
};

const FOLDS: Fold[] = [
  { x: 1500, y: 980, dx: 160, dy: 70, w: 1700, h: 900, rot: -18, speed: 0.07, phase: 0.0, color: '31,163,107', ring: true, alpha: 0.9 },
  { x: 260, y: 120, dx: 140, dy: 90, w: 1500, h: 760, rot: 24, speed: 0.06, phase: 1.7, color: '23,120,80', ring: true, alpha: 0.75 },
  { x: 1650, y: 140, dx: 120, dy: 60, w: 900, h: 620, rot: -30, speed: 0.08, phase: 3.1, color: '54,161,117', alpha: 0.45 },
  { x: 320, y: 960, dx: 150, dy: 80, w: 1100, h: 700, rot: 12, speed: 0.05, phase: 4.4, color: '30,72,55', alpha: 0.9 },
  { x: 980, y: 560, dx: 220, dy: 120, w: 1400, h: 520, rot: 8, speed: 0.045, phase: 2.2, color: '61,220,151', ring: true, alpha: 0.22 },
];

export const Aurora: React.FC<{ t: number; px?: number; py?: number; bright?: number }> = ({ t, px = 0, py = 0, bright = 0 }) => (
  <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', background: '#050a08' }}>
    {FOLDS.map((f, i) => {
      const a = t * f.speed * Math.PI * 2 + f.phase;
      const x = f.x + Math.sin(a) * f.dx - px * (0.04 + i * 0.012);
      const y = f.y + Math.cos(a * 0.8) * f.dy - py * (0.04 + i * 0.012);
      const rot = f.rot + Math.sin(a * 0.6) * 8;
      const c = f.color;
      const fill = f.ring
        ? `radial-gradient(closest-side, rgba(${c},0) 52%, rgba(${c},${f.alpha * 0.55}) 70%, rgba(${c},${f.alpha}) 80%, rgba(${c},${f.alpha * 0.4}) 88%, rgba(${c},0) 100%)`
        : `radial-gradient(closest-side, rgba(${c},${f.alpha}) 0%, rgba(${c},${f.alpha * 0.5}) 45%, rgba(${c},0) 100%)`;
      return (
        <div
          key={i}
          style={{
            position: 'absolute',
            left: x - f.w / 2,
            top: y - f.h / 2,
            width: f.w,
            height: f.h,
            transform: `rotate(${rot}deg)`,
            background: fill,
            filter: 'blur(40px)',
          }}
        />
      );
    })}
    {/* A gentle vignette keeps the centre calm for the devices and the edges deep. */}
    <div style={{ position: 'absolute', inset: 0, background: 'radial-gradient(75% 70% at 50% 50%, rgba(5,10,8,0.55) 0%, rgba(5,10,8,0) 55%, rgba(5,10,8,0.5) 100%)' }} />
    {bright > 0 && <div style={{ position: 'absolute', inset: 0, background: `radial-gradient(90% 80% at 50% 50%, rgba(236,255,246,${bright}) 0%, rgba(160,240,205,${bright * 0.9}) 55%, rgba(54,161,117,${bright * 0.8}) 100%)` }} />}
  </div>
);
