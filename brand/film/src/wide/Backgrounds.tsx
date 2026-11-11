import React from 'react';
import { staticFile } from 'remotion';

/**
 * Backgrounds for the wide film, each a pure function of time `t` (seconds), 1920×1080.
 *  - silk:    ribbons of green light flowing slowly across, like a calm wallpaper
 *  - grid:    a fine dot grid lit by a soft spotlight from the top, a slow light sweeping across
 *  - aurora:  curtains of light hanging from the top and swaying, fading into the dark below
 */

const W = 1920;
const H = 1080;

/** Film grain, so the dark gradients never band: a still noise tile, jumping each frame. */
const Grain: React.FC<{ t: number; opacity?: number }> = ({ t, opacity = 0.07 }) => {
  const f = Math.floor(t * 24);
  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        opacity,
        mixBlendMode: 'overlay',
        backgroundImage: `url(${staticFile('grain.png')})`,
        backgroundSize: '256px 256px',
        backgroundPosition: `${(f * 97) % 256}px ${(f * 61) % 256}px`,
      }}
    />
  );
};

const Vignette: React.FC = () => <div style={{ position: 'absolute', inset: 0, background: 'radial-gradient(80% 85% at 50% 45%, rgba(3,7,5,0) 50%, rgba(3,7,5,0.85) 100%)' }} />;

/** Silk: wide soft ribbons of green crossing the frame, drifting and undulating. */
const Silk: React.FC<{ t: number }> = ({ t }) => {
  const ribbon = (i: number, y: number, amp: number, width: number, speed: number, opacity: number, stroke: string) => {
    const p = t * speed + i * 1.7;
    const pts = Array.from({ length: 7 }, (_, j) => {
      const x = -300 + j * 420;
      const yy = y + Math.sin(p + j * 0.9) * amp + Math.cos(p * 0.6 + j * 1.3) * amp * 0.4;
      return [x, yy];
    });
    let d = `M ${pts[0][0]} ${pts[0][1]}`;
    for (let j = 1; j < pts.length; j++) {
      const [x0, y0] = pts[j - 1];
      const [x1, y1] = pts[j];
      d += ` C ${x0 + 210} ${y0}, ${x1 - 210} ${y1}, ${x1} ${y1}`;
    }
    return <path key={i} d={d} stroke={stroke} strokeWidth={width} fill="none" strokeLinecap="round" opacity={opacity} />;
  };
  return (
    <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(180deg, #030806 0%, #05110c 55%, #071a12 100%)' }}>
      <svg width={W} height={H} style={{ position: 'absolute', inset: 0 }} aria-hidden>
        <defs>
          <linearGradient id="silkA" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stopColor="#0e5a46" stopOpacity="0" />
            <stop offset="0.35" stopColor="#2fb57e" />
            <stop offset="0.7" stopColor="#7fe3b0" />
            <stop offset="1" stopColor="#1b6f8f" stopOpacity="0" />
          </linearGradient>
          <linearGradient id="silkB" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stopColor="#1b6f8f" stopOpacity="0" />
            <stop offset="0.4" stopColor="#1f8f68" />
            <stop offset="1" stopColor="#0e5a46" stopOpacity="0" />
          </linearGradient>
          <filter id="silkBlur" x="-20%" y="-50%" width="140%" height="200%">
            <feGaussianBlur stdDeviation="40" />
          </filter>
          <filter id="silkSoft" x="-20%" y="-50%" width="140%" height="200%">
            <feGaussianBlur stdDeviation="6" />
          </filter>
        </defs>
        <g filter="url(#silkBlur)">
          {ribbon(0, 820, 90, 260, 0.18, 0.45, 'url(#silkB)')}
          {ribbon(1, 930, 70, 200, 0.22, 0.55, 'url(#silkA)')}
          {ribbon(2, 180, 60, 160, 0.15, 0.22, 'url(#silkB)')}
        </g>
        <g filter="url(#silkSoft)">
          {ribbon(3, 900, 70, 6, 0.22, 0.5, 'url(#silkA)')}
          {ribbon(4, 860, 85, 3, 0.18, 0.35, 'url(#silkA)')}
        </g>
      </svg>
      <Vignette />
      <Grain t={t} />
    </div>
  );
};

/** Grid: a fine dot grid under a soft top spotlight, with a slow band of light passing over it. */
const Grid: React.FC<{ t: number }> = ({ t }) => {
  const sweep = ((t * 0.12) % 1.4) - 0.2;
  return (
    <div style={{ position: 'absolute', inset: 0, background: '#040907' }}>
      <div style={{ position: 'absolute', inset: 0, backgroundImage: 'radial-gradient(rgba(127,227,176,0.4) 1.6px, transparent 2px)', backgroundSize: '36px 36px', backgroundPosition: `${(t * 6) % 36}px 0px`, WebkitMaskImage: 'radial-gradient(70% 80% at 50% 20%, #000 0%, rgba(0,0,0,0.35) 55%, transparent 85%)' }} />
      <div style={{ position: 'absolute', inset: 0, background: 'radial-gradient(45% 55% at 50% 0%, rgba(54,161,117,0.38), rgba(54,161,117,0) 100%)' }} />
      <div style={{ position: 'absolute', inset: 0, background: `linear-gradient(105deg, transparent ${sweep * 100 - 12}%, rgba(127,227,176,0.07) ${sweep * 100}%, transparent ${sweep * 100 + 12}%)` }} />
      <Vignette />
      <Grain t={t} opacity={0.05} />
    </div>
  );
};

/** Aurora: curtains of light hanging from the top edge, swaying, fading down into the dark. */
const Aurora: React.FC<{ t: number }> = ({ t }) => {
  const curtains = [
    { x: 260, w: 520, c: '47,181,126', a: 0.55, s: 0.21, ph: 0 },
    { x: 760, w: 640, c: '127,227,176', a: 0.32, s: 0.17, ph: 1.4 },
    { x: 1250, w: 560, c: '31,143,104', a: 0.55, s: 0.24, ph: 2.6 },
    { x: 1640, w: 480, c: '27,111,143', a: 0.45, s: 0.19, ph: 3.9 },
    { x: 980, w: 360, c: '191,255,224', a: 0.18, s: 0.29, ph: 5.1 },
  ];
  return (
    <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(180deg, #06120d 0%, #040907 60%, #030605 100%)', overflow: 'hidden' }}>
      {curtains.map((c, i) => {
        const sway = Math.sin(t * c.s + c.ph) * 90;
        const len = 640 + Math.sin(t * c.s * 1.3 + c.ph) * 120;
        const skew = Math.sin(t * c.s * 0.8 + c.ph) * 10;
        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: c.x - c.w / 2 + sway,
              top: -120,
              width: c.w,
              height: len,
              transform: `skewX(${skew}deg)`,
              background: `linear-gradient(180deg, rgba(${c.c},${c.a}) 0%, rgba(${c.c},${c.a * 0.55}) 40%, rgba(${c.c},0) 100%)`,
              filter: 'blur(50px)',
              borderRadius: '0 0 50% 50%',
            }}
          />
        );
      })}
      {/* Fine vertical rays inside the curtains. */}
      <div style={{ position: 'absolute', inset: 0, backgroundImage: 'repeating-linear-gradient(90deg, rgba(191,255,224,0.035) 0px, rgba(191,255,224,0.035) 2px, transparent 2px, transparent 26px)', backgroundPosition: `${Math.sin(t * 0.2) * 40}px 0`, WebkitMaskImage: 'linear-gradient(180deg, #000 0%, transparent 55%)' }} />
      <Vignette />
      <Grain t={t} />
    </div>
  );
};

export type BgKind = 'silk' | 'grid' | 'aurora';
export const WideBackground: React.FC<{ t: number; kind: BgKind }> = ({ t, kind }) => (kind === 'grid' ? <Grid t={t} /> : kind === 'aurora' ? <Aurora t={t} /> : <Silk t={t} />);
