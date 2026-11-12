import React from 'react';

/**
 * The QR-to-link moment, as both apps play it (mac QrLinkingView.swift, android QrLinking.kt): the
 * code's squares turn mint and round into dots, stream into two hooks held apart, fuse, the hooks
 * clasp with a little overshoot, light up, a glint runs along one and a ring of light goes round.
 * Same timeline, same numbers. [t] is seconds since the phone scanned (negative: the plain QR).
 */

/** A demo pairing code (no real keys), the modules as CoreImage draws them, quiet zone included. */
const QR_ROWS = '00000000000000000000000000000000000,01111111000100010011110011011111110,01000001001000100011111010010000010,01011101010010001000100111010111010,01011101001001100010001111010111010,01011101001001100100100111010111010,01000001000111011010111011010000010,01111111010101010101010101011111110,00000000010010001010111111000000000,01110111110001000110111001110001000,00101100011011101011010000111011010,00100011000111010101000100111100010,01100100111101111010111100111010010,01000001000110010101011011111000010,00100110011110011010011101111001110,01011011111000100101011100101110110,00001100101110001010111001110110110,01010101101001000101011111011000100,01010000100011101010011001011001110,00010001000111010101001000100010110,01110100010001111010111001000000000,00111001010110010101111011111010000,00111100000110011010000001100010010,01010001111100100101001101101001110,00111100011110001010111100000100100,01011001100001000101111101111100100,00000000010011101010010111000110110,01111111010111010101010001010111110,01000001011001111010111101000100100,01011101010110010101111001111100100,01011101001110011010011001000111110,01011101011100100101001001000111010,01000001011010001010101011101100100,01111111011001000101111011110110110,00000000000000000000000000000000000'.split(',');
export const QR: boolean[][] = QR_ROWS.map((r) => [...r].map((c) => c === '1'));

// Timeline, seconds: the apps' own.
const morphFrom = 0.1, morphTo = 0.42, travelFrom = 0.34, travelSpread = 0.36, travel = 0.72;
export const ARRIVE = travelFrom + travelSpread + travel;
const fused = ARRIVE + 0.28;
export const CLASP = fused + 0.05;
const windUp = 0.08, contact = 0.18, litAt = 0.53;
/** When "You're linked." comes in, from the scan. */
export const LINKED = CLASP + 0.75;

const seg = (t: number, a: number, b: number) => Math.min(1, Math.max(0, (t - a) / (b - a)));
const eo = (x: number) => 1 - Math.pow(1 - x, 3);
const eio = (x: number) => (x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2);
const settle = (x: number, response: number) => {
  if (x <= 0) return 0;
  const w = (2 * Math.PI) / response;
  return 1 - (1 + w * x) * Math.exp(-w * x);
};
const springClose = (x: number) => {
  if (x <= 0) return 0;
  const z = 0.58, w = (2 * Math.PI) / 0.55, wd = w * Math.sqrt(1 - z * z);
  return 1 - Math.exp(-z * w * x) * (Math.cos(wd * x) + ((z * w) / wd) * Math.sin(wd * x));
};
type RGB = [number, number, number];
const mint: RGB = [0x7f, 0xe3, 0xb0];
const ringC: RGB = [0x5f, 0xd9, 0xa0];
const apartA: RGB = [0x65, 0xa1, 0x84];
const apartB: RGB = [0x4f, 0x6b, 0x60];
const ink: RGB = [10, 13, 13];
const mix = (a: RGB, b: RGB, p: number, alpha = 1) => `rgba(${a.map((v, i) => Math.round(v + (b[i] - v) * p)).join(',')},${alpha})`;

const APART = 46;
const U = { x: Math.SQRT1_2, y: -Math.SQRT1_2 };
// The hooks, already turned -45° (the icon's): A lower-left, B upper-right.
const HOOK_A = 'M120.22 91.94 L62.24 149.92 A31 31 0 0 0 106.08 193.76 L116.69 183.15';
const HOOK_B = 'M135.78 164.06 L193.76 106.08 A31 31 0 0 0 149.92 62.24 L139.31 72.85';
const HOOK_LENGTH = 82 + Math.PI * 31 + 15;

/** The tile the code sits on and the drawing canvas, in points; link units per point. */
const TILE = 292;
const CANVAS = 340;
const K = (CANVAS / 256) * 0.86;

type Dot = { sign: number; x: number; y: number; cx: number; cy: number; nx: number; ny: number; bend: number; delay: number };
const geometry = (() => {
  const r = 31, half = 62, long = 82, short = 15;
  const cos = Math.cos(-Math.PI / 4), sin = Math.sin(-Math.PI / 4);
  const turn = (x: number, y: number) => [128 + (x - 128) * cos - (y - 128) * sin, 128 + (x - 128) * sin + (y - 128) * cos];
  const along = (sign: number, s: number): [[number, number], [number, number]] => {
    const ax = 128 - sign * half;
    if (s <= long) return [[ax + sign * (long - s), 128 - sign * r], [-sign, 0]];
    const arc = Math.PI * r;
    if (s - long <= arc) {
      const a = -Math.PI / 2 - (s - long) / r;
      return [[ax + sign * r * Math.cos(a), 128 + sign * r * Math.sin(a)], [sign * Math.sin(a), -sign * Math.cos(a)]];
    }
    const e = s - long - arc;
    return [[ax + sign * e, 128 + sign * r], [sign, 0]];
  };
  const unit = 1 / K;
  const tileSide = TILE * unit;
  const n = QR.length;
  const qrSide = (TILE - 32) * unit;
  const cell = qrSide / n;
  const origin = 128 - qrSide / 2;
  const cells: [number, number][] = [];
  QR.forEach((row, j) => row.forEach((on, i) => on && cells.push([origin + (i + 0.5) * cell, origin + (j + 0.5) * cell])));
  const total = long + Math.PI * r + short;
  const targets: [number, [number, number]][] = [];
  for (const sign of [1, -1]) {
    for (let s = 0; s <= total + 0.01; s += 4.4) {
      const [p, tan] = along(sign, Math.min(s, total));
      for (const lane of [-7.2, -2.4, 2.4, 7.2]) {
        targets.push([sign, turn(p[0] - tan[1] * lane, p[1] + tan[0] * lane) as [number, number]]);
      }
    }
  }
  const key = (p: [number, number]) => p[0] + p[1] * 0.35;
  const pts = [...targets].sort((a, b) => key(a[1]) - key(b[1]));
  const pool = [...cells].sort((a, b) => key(a) - key(b));
  const used = new Set<number>();
  const maxD = Math.hypot(qrSide / 2, qrSide / 2);
  const step = pool.length / Math.max(1, pts.length);
  const dots: Dot[] = pts.map(([s, p], idx) => {
    const sign = s > 0 ? -1 : 1;
    const i = Math.min(pool.length - 1, Math.floor(idx * step));
    used.add(i);
    const [cx, cy] = pool[i];
    const ex = p[0] + sign * U.x * APART, ey = p[1] + sign * U.y * APART;
    const dx = ex - cx, dy = ey - cy, d = Math.max(0.001, Math.hypot(dx, dy));
    const wobble = (((idx * 7919) % 100) / 100 - 0.5) * 15;
    return { sign, x: p[0], y: p[1], cx, cy, nx: -dy / d, ny: dx / d, bend: sign * 30 + wobble, delay: travelFrom + travelSpread * (Math.hypot(cx - 128, cy - 128) / maxD) };
  });
  const spare = pool.filter((_, i) => !used.has(i));
  return { dots, spare, cell, tileSide, cells, origin, qrSide };
})();

/** The QR, or the link it becomes, drawn [size] points square (the apps' 340 pt canvas). */
export const QrLink: React.FC<{ t: number; size?: number }> = ({ t, size = CANVAS }) => {
  const g = geometry;
  const view = 256 / 0.86;
  const vb = `${128 - view / 2} ${128 - view / 2} ${view} ${view}`;
  const glow = eo(seg(t, ARRIVE - 0.4, ARRIVE + 0.8));
  let body: React.ReactNode;
  if (t < 0) {
    // The plain code on its near-white tile.
    const s = g.tileSide;
    body = (
      <>
        <rect x={128 - s / 2} y={128 - s / 2} width={s} height={s} rx={(22 * s) / TILE} fill="#f5f5f5" />
        {g.cells.map(([x, y], i) => <rect key={i} x={x - g.cell / 2} y={y - g.cell / 2} width={g.cell + 0.05} height={g.cell + 0.05} fill={mix(ink, ink, 0)} />)}
      </>
    );
  } else if (t < ARRIVE) {
    const tileFade = 1 - eo(seg(t, morphFrom - 0.1, morphTo));
    const morph = eio(seg(t, morphFrom, morphTo));
    const fill = mix(ink, mint, morph);
    const side = g.cell + (4.4 - g.cell) * morph;
    const corner = 0.3 + (side / 2 - 0.3) * morph;
    const fade = 1 - seg(t, morphFrom, morphTo + 0.1);
    const s = g.tileSide;
    body = (
      <>
        {tileFade > 0 && <rect x={128 - s / 2} y={128 - s / 2} width={s} height={s} rx={(22 * s) / TILE} fill={`rgba(245,245,245,${tileFade})`} />}
        {fade > 0 && g.spare.map(([x, y], i) => <rect key={'s' + i} x={x - side / 2} y={y - side / 2} width={side} height={side} rx={corner} fill={mix(ink, mint, morph, fade)} />)}
        {g.dots.map((d, i) => {
          const p = settle(t - d.delay, travel * 0.7);
          const bend = Math.sin(Math.PI * p) * d.bend;
          const ex = d.x + d.sign * U.x * APART, ey = d.y + d.sign * U.y * APART;
          const x = d.cx + (ex - d.cx) * p + d.nx * bend, y = d.cy + (ey - d.cy) * p + d.ny * bend;
          return <rect key={i} x={x - side / 2} y={y - side / 2} width={side} height={side} rx={corner} fill={fill} />;
        })}
      </>
    );
  } else {
    const fuse = settle(t - ARRIVE, 0.3);
    const uu = t - CLASP;
    const gap = uu < 0 ? APART : uu < windUp ? APART + 8 * eo(seg(uu, 0, windUp)) : (APART + 8) * (1 - springClose(uu - windUp));
    const lit = eo(seg(uu, contact, litAt));
    const squash = uu < 0 ? 0 : Math.max(0, 1 - Math.abs(uu - contact) / 0.12);
    const gp = seg(uu, 0.28, 0.73);
    const rp = seg(uu, 0.2, 1.28);
    const r = 2.2 + 3.5 * fuse;
    body = (
      <>
        {fuse < 1 && g.dots.map((d, i) => <circle key={i} cx={d.x + d.sign * U.x * APART} cy={d.y + d.sign * U.y * APART} r={r} fill={mix(mint, mint, 0, 0.9 * (1 - fuse))} />)}
        <g transform={`translate(128 128) rotate(-45) scale(${1 + 0.05 * squash} ${1 - 0.06 * squash}) rotate(45) translate(-128 -128)`}>
          {([[HOOK_B, 1], [HOOK_A, -1]] as [string, number][]).map(([d, sign]) => (
            <g key={sign} transform={`translate(${sign * U.x * gap} ${sign * U.y * gap})`}>
              <path d={d} fill="none" stroke={mix(sign > 0 ? apartB : apartA, mint, lit, fuse)} strokeWidth={21} strokeLinecap="round" strokeLinejoin="round" />
              {sign < 0 && gp > 0 && gp < 1 && (
                <path d={d} fill="none" stroke={`rgba(255,255,255,${0.9 * Math.sin(Math.PI * gp)})`} strokeWidth={6} strokeLinecap="round" strokeDasharray={`22 ${HOOK_LENGTH * 2}`} strokeDashoffset={-gp * (HOOK_LENGTH + 22) + 22} />
              )}
            </g>
          ))}
        </g>
        {rp > 0 && rp < 1 && Array.from({ length: 28 }, (_, i) => {
          const f = i / 27;
          const head = -90 + rp * 396;
          const a0 = ((head - 140 * f - 5.7) * Math.PI) / 180, a1 = ((head - 140 * f) * Math.PI) / 180;
          return <path key={i} d={`M${128 + 142 * Math.cos(a0)} ${128 + 142 * Math.sin(a0)} A142 142 0 0 1 ${128 + 142 * Math.cos(a1)} ${128 + 142 * Math.sin(a1)}`} fill="none" stroke={mix(ringC, ringC, 0, 0.9 * Math.sin(Math.PI * rp) * Math.sin(Math.PI * f))} strokeWidth={3} strokeLinecap="round" />;
        })}
      </>
    );
  }
  return (
    <svg width={size} height={size} viewBox={vb} style={{ overflow: 'visible' }} aria-hidden>
      <defs>
        <radialGradient id="qrlinkGlow">
          <stop offset="0" stopColor={mix(ringC, ringC, 0, 0.17 * (0.3 + 0.7 * glow))} />
          <stop offset="0.55" stopColor={mix(ringC, ringC, 0, 0.04)} />
          <stop offset="1" stopColor="rgba(0,0,0,0)" />
        </radialGradient>
      </defs>
      {t >= 0 && <circle cx={128} cy={128} r={148} fill="url(#qrlinkGlow)" />}
      {body}
    </svg>
  );
};
