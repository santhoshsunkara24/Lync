import React from 'react';
import { color, font, space } from '../theme';

/**
 * Liftoff, ported from the Mac app (mac/Sources/WallhopMac/UI/LiftoffView.swift): Lync's link
 * flying on its thrust while files go across, speed lines streaming back past it; when the files
 * land it blasts off along its own axis and drops back in with a glint. Here it's a pure function
 * of time, so every frame renders the same: `t` is seconds since the transfer started, `doneAt`
 * when it landed (or undefined while it's still going).
 */

const PATH_A = 'M120.22 91.94 L62.24 149.92 A31 31 0 0 0 106.08 193.76 L116.69 183.15';
const PATH_B = 'M135.78 164.06 L193.76 106.08 A31 31 0 0 0 149.92 62.24 L139.31 72.85';
const U = [0.7071, -0.7071]; // forward: up and to the right
const P = [0.7071, 0.7071]; // across
/** Speed lines run in five fixed lanes, taken in this order, so they never sit side by side. */
const LANES = [0, -0.34, 0.17, -0.17, 0.34];
const REACH = 1.1;
const LAUNCH = 0.26;
const DONE = 1.15;

const clamp = (v: number) => Math.min(1, Math.max(0, v));
const easeOut = (t: number) => 1 - Math.pow(1 - clamp(t), 3);
const easeIn = (t: number) => Math.pow(clamp(t), 2.4);
const springK = (t: number, dur: number, bounce: number) => {
  if (t <= 0) return 0;
  const z = 1 - bounce;
  const w = (2 * Math.PI) / dur;
  const wd = w * Math.sqrt(1 - z * z);
  return 1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + ((z * w) / wd) * Math.sin(wd * t));
};

type Streak = { off: number; d: number; len: number; w: number; life: number };

/** The speed lines on screen at time `t`: emitted at a rate, each streaming back and fading. */
const streaks = (t: number, doneAt: number | undefined, link: number): Streak[] => {
  const out: Streak[] = [];
  const sendEnd = doneAt ?? Infinity;
  // Emitted at 9 a second while sending, 18 a second during the blast-off.
  const emit: [number, number, number][] = []; // [time, speed, index]
  let i = 0;
  for (let e = LAUNCH; e < Math.min(t, sendEnd); e += 1 / 9) emit.push([e, link * 2.2, i++]);
  if (doneAt !== undefined) for (let e = doneAt; e < Math.min(t, doneAt + 0.42); e += 1 / 18) emit.push([e, link * 3.6, i++]);
  for (const [at, v, n] of emit) {
    const d = link * 0.42 + v * (t - at);
    const life = 1 - (d - link * 0.42) / (link * REACH);
    if (life <= 0) continue;
    out.push({ off: LANES[n % LANES.length] * link, d, len: link * (0.22 + (0.1 * ((n + 1) % 3)) / 2), w: link * 0.032, life });
  }
  return out;
};

export const Liftoff: React.FC<{ t: number; doneAt?: number; width?: number; height?: number }> = ({ t, doneAt, width = 84, height = 60 }) => {
  const unit = Math.min(width, height);
  const link = unit * (unit < 120 ? 0.64 : 0.5);
  const cx = width / 2;
  const cy = height / 2;
  let back = 0;
  let stretch = 1;
  let alpha = 1;
  let glint = -1;
  let hover = 0;
  let bright = false;
  if (doneAt !== undefined && t >= doneAt) {
    const tp = (t - doneAt) * 1000;
    if (tp < 420) {
      const k = easeIn(tp / 420);
      back = 6 + k * unit * 1.2;
      stretch = 1 + 0.4 * k;
    } else if (tp < DONE * 1000) {
      const r = springK((tp - 420) / 1000, 0.55, 0.32);
      back = -unit * 0.55 * (1 - r);
      alpha = clamp((tp - 420) / 160);
      glint = (tp - 560) / 520;
    }
  } else if (t < LAUNCH) {
    const k = easeOut(t / LAUNCH);
    back = -10 * k;
    stretch = 1 - 0.07 * k;
  } else if (t >= LAUNCH) {
    const tp = (t - LAUNCH) * 1000;
    back = -10 + 16 * easeOut(tp / 220);
    stretch = 1.03;
    hover = Math.sin((t * 1000) / 70) * 0.9;
    bright = true;
  }
  const bx = cx + U[0] * back + P[0] * hover;
  const by = cy + U[1] * back + P[1] * hover;
  const k = link / 256;
  const lines = streaks(t, doneAt, link);
  return (
    <svg width={width} height={height} style={{ display: 'block', overflow: 'hidden' }} aria-hidden>
      <defs>
        <radialGradient id="liftGlow">
          <stop offset="0" stopColor="#5fd9a0" stopOpacity={0.32 * (bright ? 0.9 : 0.6)} />
          <stop offset="1" stopColor="#5fd9a0" stopOpacity="0" />
        </radialGradient>
        <linearGradient id="tubeA" gradientUnits="userSpaceOnUse" x1="0" y1="60" x2="0" y2="196">
          <stop offset="0" stopColor="#ffffff" />
          <stop offset="0.5" stopColor="#e8fff4" />
          <stop offset="1" stopColor="#b9eed3" />
        </linearGradient>
        <linearGradient id="tubeB" gradientUnits="userSpaceOnUse" x1="0" y1="60" x2="0" y2="196">
          <stop offset="0" stopColor="#d6fbe9" />
          <stop offset="1" stopColor="#8fddb8" />
        </linearGradient>
      </defs>
      {lines.map((l, i) => {
        const x = bx - U[0] * l.d + P[0] * l.off;
        const y = by - U[1] * l.d + P[1] * l.off;
        return (
          <line key={i} x1={x} y1={y} x2={x - U[0] * l.len} y2={y - U[1] * l.len} stroke="#e2ece7" strokeWidth={l.w} strokeLinecap="round" opacity={0.7 * Math.min(1, (1 - l.life) * 8) * l.life} />
        );
      })}
      {alpha > 0.002 && (
        <g opacity={alpha}>
          <circle cx={bx} cy={by} r={link * 0.75} fill="url(#liftGlow)" />
          <g transform={`translate(${bx} ${by}) rotate(-45) scale(${stretch} ${1 / Math.sqrt(stretch)}) rotate(45) scale(${k}) translate(-128 -128)`}>
            {[
              [PATH_A, '#9fe6c3', 'url(#tubeA)', 0.9],
              [PATH_B, '#7fd5ae', 'url(#tubeB)', 0.6],
            ].map(([d, edge, tube, hi]) => (
              <g key={d as string} fill="none" strokeLinecap="round" strokeLinejoin="round">
                <path d={d as string} stroke={edge as string} strokeWidth={19} />
                <path d={d as string} stroke={tube as string} strokeWidth={16} transform="translate(-1 -1)" />
                <path d={d as string} stroke={`rgba(255,255,255,${hi})`} strokeWidth={3} transform="translate(-3 -3)" />
                {glint > 0 && glint < 1 && <path d={d as string} stroke={`rgba(255,255,255,${0.95 * Math.sin(glint * Math.PI)})`} strokeWidth={7} strokeDasharray="24 600" strokeDashoffset={24 - glint * 230} />}
              </g>
            ))}
          </g>
        </g>
      )}
    </svg>
  );
};

/**
 * The Mac app's receiving card (LiveCardView.transferCard): Liftoff in its dark tile, the photos'
 * thumbnails, who it's from, the size, and a fine progress line in the code colour.
 */
export const ReceivingCard: React.FC<{ t: number; doneAt?: number; progress: number; from: string; summary: string; thumbs: string[] }> = ({ t, doneAt, progress, from, summary, thumbs }) => {
  const done = doneAt !== undefined && t >= doneAt;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: space.s, fontFamily: font.body }}>
      <div style={{ width: 84, height: 60, borderRadius: 16, background: 'rgb(12,17,15)', overflow: 'hidden', flex: 'none' }}>
        <Liftoff t={t} doneAt={doneAt} />
      </div>
      <div style={{ position: 'relative', width: 40, height: 34, flex: 'none' }}>
        {thumbs.map((g, i) => (
          <div key={i} style={{ position: 'absolute', left: 2 + i * 3, top: 2, width: 28, height: 28, borderRadius: 7, border: '2px solid #fff', background: `linear-gradient(135deg, ${g})`, transform: `rotate(${(i - 1) * 7}deg)` }} />
        ))}
      </div>
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: space.xxs }}>
        <span style={{ fontSize: 14, fontWeight: 600, color: color.white, whiteSpace: 'nowrap' }}>{done ? 'Copied · ⌘V to paste' : `From ${from}`}</span>
        <span style={{ fontSize: 12, fontWeight: 500, color: 'rgba(255,255,255,0.65)', whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>{summary}</span>
        <div style={{ height: 3, borderRadius: 3, background: 'rgba(255,255,255,0.1)', overflow: 'hidden' }}>
          <div style={{ width: `${Math.max(2, Math.min(1, progress) * 100)}%`, height: '100%', borderRadius: 3, background: `linear-gradient(90deg, ${color.mint}80, ${color.mint})` }} />
        </div>
      </div>
    </div>
  );
};
