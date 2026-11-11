import React from 'react';
import { Audio, Img, Sequence, staticFile, useCurrentFrame } from 'remotion';
import { CallCard, CodeCard, NotificationCard } from '../components/Cards';
import { MacBook, Phone } from '../components/Devices';
import { MacMenuBar, MacWallpaper } from '../components/Desktop';
import { Icon } from '../components/Icon';
import { ReceivingCard } from '../components/Liftoff';
import { NotchCard } from '../components/NotchCard';
import { DragGlow } from '../components/DragGlow';
import { PhoneHome } from '../components/PhoneHome';
import { Pointer } from '../components/Pointer';
import { linear, ramp, sec, springAt } from '../film/motion';
import { color, font, notch } from '../theme';

/**
 * The vertical short (1080×1920): fast kinetic type on a beat, the ring logo reveal, then Lync's
 * cards dropping out of a notch one after another, a glowing floor, the two devices, the end card.
 * Everything is timed in beats of the music (135 BPM), so cuts land on the music.
 */

export const W = 1080;
export const H = 1920;
const BEAT = 60 / 135;
const b = (n: number) => n * BEAT;
export const SHORT_SECONDS = b(100);

export const clamp = (v: number) => Math.min(1, Math.max(0, v));
const mixRGB = (a: number[], c: number[], t: number) => `rgb(${a.map((v, i) => Math.round(v + (c[i] - v) * t)).join(',')})`;
const WHITE = [255, 255, 255];
const MINT = [127, 227, 176];
const HOT = [190, 255, 225];

// ---------------------------------------------------------------------------------------------
// Type

type Part = [string, boolean?];

/**
 * A line typed on letter by letter: each letter blurs in, rising, with a pale green glint passing
 * through; accented parts settle in mint. `out` blurs the whole line away, a little bigger.
 */
export const Sweep: React.FC<{
  t: number;
  at: number;
  out: number;
  lines: Part[][];
  size: number;
  display?: boolean;
  y?: number;
  glow?: boolean;
  stagger?: number;
  style?: React.CSSProperties;
  left?: number;
  right?: number;
}> = ({ t, at, out, lines, size, display, y = H / 2, glow, stagger = 0.022, style, left = 60, right = 60 }) => {
  if (t < at - 0.05 || t > out + 0.3) return null;
  const e = clamp((t - out) / 0.22);
  let n = 0;
  return (
    <div
      style={{
        position: 'absolute',
        left,
        right,
        top: y,
        transform: `translateY(-50%) scale(${1 + 0.1 * e})`,
        opacity: 1 - e,
        filter: e > 0 ? `blur(${e * 14}px)` : undefined,
        textAlign: 'center',
        fontFamily: display ? font.display : font.body,
        fontWeight: display ? 400 : 600,
        fontSize: size,
        lineHeight: 1.12,
        letterSpacing: display ? 0 : '-0.02em',
        ...style,
      }}
    >
      {lines.map((parts, li) => (
        <div key={li} style={{ whiteSpace: 'nowrap' }}>
          {parts.map(([text, accent], pi) => (
            <span key={pi}>
              {text.split('').map((ch, ci) => {
                const i = n++;
                const start = at + i * stagger;
                const p = clamp((t - start) / 0.26);
                const ease = 1 - Math.pow(1 - p, 3);
                const glint = Math.exp(-Math.pow((t - start - 0.22) / 0.13, 2));
                const base = accent ? MINT : WHITE;
                return (
                  <span
                    key={ci}
                    style={{
                      display: 'inline-block',
                      whiteSpace: 'pre',
                      opacity: ease,
                      transform: `translateY(${(1 - ease) * 0.32}em)`,
                      filter: p < 1 ? `blur(${(1 - ease) * 10}px)` : undefined,
                      color: mixRGB(base, HOT, glint * 0.9),
                      textShadow: glow || accent ? `0 0 ${0.25 * size}px rgba(51,255,171,${0.35 + glint * 0.4})` : glint > 0.05 ? `0 0 ${0.3 * size}px rgba(51,255,171,${glint * 0.6})` : undefined,
                    }}
                  >
                    {ch}
                  </span>
                );
              })}
            </span>
          ))}
        </div>
      ))}
    </div>
  );
};

/** A neon word: an outline lit from inside, flickering on like a tube, with an icon beside it. */
export const NeonWord: React.FC<{ t: number; at: number; out: number; word: string; icon: string; y: number; size?: number }> = ({ t, at, out, word, icon, y, size = 190 }) => {
  const k = t - at;
  if (k < 0 || t > out + 0.3) return null;
  const flicker = k < 0.05 ? 1 : k < 0.1 ? 0.25 : k < 0.15 ? 1 : k < 0.2 ? 0.5 : 1;
  const e = clamp((t - out) / 0.22);
  const rise = 1 - Math.pow(1 - clamp(k / 0.3), 3);
  const neon = `0 0 6px rgba(218,251,238,0.9), 0 0 22px rgba(51,255,171,0.85), 0 0 60px rgba(51,255,171,0.55), 0 0 120px rgba(51,255,171,0.35)`;
  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        right: 0,
        top: y,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 30,
        transform: `translateY(calc(-50% + ${(1 - rise) * 40}px)) scale(${1 + 0.12 * e})`,
        opacity: flicker * (1 - e),
        filter: e > 0 ? `blur(${e * 14}px)` : undefined,
      }}
    >
      <div style={{ filter: 'drop-shadow(0 0 10px rgba(51,255,171,0.9)) drop-shadow(0 0 30px rgba(51,255,171,0.6))' }}>
        <Icon name={icon} size={size * 0.58} color={color.pale} />
      </div>
      <span
        style={{
          fontFamily: font.body,
          fontWeight: 700,
          fontSize: size,
          letterSpacing: '-0.03em',
          color: 'rgba(218,251,238,0.12)',
          WebkitTextStroke: `4px ${color.pale}`,
          textShadow: neon,
        }}
      >
        {word}
      </span>
    </div>
  );
};

// ---------------------------------------------------------------------------------------------
// Backdrop and light

/** Dark green, with light leaking in along both edges, drifting up and down. */
const Backdrop: React.FC<{ t: number }> = ({ t }) => {
  const a = Math.sin(t * 0.5) * 220;
  const c = Math.cos(t * 0.37) * 260;
  return (
    <div style={{ position: 'absolute', inset: 0, background: '#040907', overflow: 'hidden' }}>
      <div style={{ position: 'absolute', left: -420, top: 500 + a, width: 700, height: 1300, borderRadius: '50%', background: 'radial-gradient(closest-side, rgba(31,163,107,0.55), rgba(31,163,107,0))', filter: 'blur(30px)' }} />
      <div style={{ position: 'absolute', right: -440, top: 200 + c, width: 720, height: 1400, borderRadius: '50%', background: 'radial-gradient(closest-side, rgba(23,120,80,0.6), rgba(23,120,80,0))', filter: 'blur(30px)' }} />
      <div style={{ position: 'absolute', inset: 0, background: 'radial-gradient(70% 45% at 50% 50%, rgba(4,9,7,0.7), rgba(4,9,7,0))' }} />
    </div>
  );
};

/** The ring: a circle of green light whose inside stays dark and whose outside floods the frame. */
export const Ring: React.FC<{ r: number; strength: number; spin: number; w?: number; h?: number }> = ({ r, strength, spin, w: W = 1080, h: H = 1920 }) => {
  if (strength <= 0.001 || r < 2) return null;
  const R = Math.max(r, 1);
  return (
    <div style={{ position: 'absolute', inset: 0, opacity: strength }}>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background: `radial-gradient(circle at 50% 50%, #030806 0px, #030806 ${R * 0.7}px, #0d4a32 ${R * 0.88}px, #4dffb5 ${R}px, #1f9a68 ${R * 1.07}px, #0f5a3c ${R * 1.4}px, #0a3324 ${R * 2.1}px)`,
          filter: 'blur(14px)',
        }}
      />
      {/* A bright streak running round the ring. */}
      <svg width={W} height={H} style={{ position: 'absolute', inset: 0 }} aria-hidden>
        <defs>
          <linearGradient id="comet" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stopColor="#ffffff" stopOpacity="0" />
            <stop offset="1" stopColor="#ffffff" stopOpacity="0.95" />
          </linearGradient>
        </defs>
        <g transform={`rotate(${spin} ${W / 2} ${H / 2})`} style={{ filter: 'drop-shadow(0 0 8px #bfffe0)' }}>
          <path d={`M ${W / 2 + R * 0.96 * Math.cos(Math.PI * 0.55)} ${H / 2 - R * 0.96 * Math.sin(Math.PI * 0.55)} A ${R * 0.96} ${R * 0.96} 0 0 1 ${W / 2 + R * 0.96 * Math.cos(Math.PI * 0.95)} ${H / 2 - R * 0.96 * Math.sin(Math.PI * 0.95)}`} stroke="url(#comet)" strokeWidth={5} fill="none" strokeLinecap="round" />
        </g>
      </svg>
    </div>
  );
};

/** A flash of pale light on a hit. */
export const Flash: React.FC<{ t: number; at: number; strength?: number }> = ({ t, at, strength = 0.7 }) => {
  const k = t - at;
  if (k < 0 || k > 0.5) return null;
  return <div style={{ position: 'absolute', inset: 0, background: '#dafbee', opacity: strength * Math.pow(1 - k / 0.5, 2.2), mixBlendMode: 'screen' }} />;
};

// ---------------------------------------------------------------------------------------------
// The notch, its line, and the cards

/** Where the top edge of the "screen" runs, and how big the notch and its cards are drawn. */
const LINE = 880;
const S = 2.45;

/** The top edge of a screen: a fine line of light with the screen's glow below it and the notch hanging from it. */
const ScreenEdge: React.FC<{ draw: number; notchIn: number; fade: number }> = ({ draw, notchIn, fade }) => {
  if (draw <= 0 || fade <= 0) return null;
  return (
    <div style={{ position: 'absolute', inset: 0, opacity: fade }}>
      <div style={{ position: 'absolute', left: 0, right: 0, top: LINE, height: 900, background: 'radial-gradient(60% 55% at 50% 0%, rgba(54,161,117,0.32), rgba(54,161,117,0))', opacity: draw }} />
      <div
        style={{
          position: 'absolute',
          left: W / 2 - (W / 2) * draw,
          width: W * draw,
          top: LINE - 1,
          height: 3,
          background: 'linear-gradient(90deg, rgba(127,227,176,0), rgba(191,255,224,0.95) 50%, rgba(127,227,176,0))',
          boxShadow: '0 0 24px rgba(51,255,171,0.7)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          left: W / 2 - (notch.width * S) / 2,
          top: LINE,
          width: notch.width * S,
          height: notch.height * S * notchIn,
          background: '#000',
          borderRadius: `0 0 ${26 * notchIn}px ${26 * notchIn}px`,
          boxShadow: notchIn > 0 ? '0 0 0 1.5px rgba(127,227,176,0.25)' : undefined,
        }}
      />
    </div>
  );
};

/** A card growing out of the notch: from the notch's own size, springing open, tilting flat. */
const FromNotch: React.FC<{ open: number; children: React.ReactNode }> = ({ open, children }) => {
  if (open <= 0.001) return null;
  const sx = 0.46 + 0.54 * open;
  return (
    <div
      style={{
        position: 'absolute',
        left: W / 2 - 200,
        top: LINE,
        width: 400,
        transformOrigin: '50% 0%',
        transform: `perspective(1000px) rotateX(${(1 - Math.min(1, open)) * 26}deg) scale(${sx * S}, ${(0.2 + 0.8 * open) * S})`,
        filter: 'drop-shadow(0 14px 28px rgba(0,0,0,0.6))',
      }}
    >
      <NotchCard open={Math.min(1, open * 1.4)}>
        <div style={{ opacity: clamp((open - 0.5) * 2.5) }}>{children}</div>
      </NotchCard>
    </div>
  );
};

export const press = (t: number, at: number) => ({ press: linear(sec(t), at, at + 0.08) * (1 - linear(sec(t), at + 0.12, at + 0.24)), ring: linear(sec(t), at + 0.05, at + 0.6) });

/** A pointer gliding to a point in card space (drawn at the card's scale) and clicking. */
const CardPointer: React.FC<{ t: number; path: [number, number, number][]; clicks: number[]; show: number }> = ({ t, path, clicks, show }) => {
  if (show <= 0) return null;
  let x = path[0][1];
  let y = path[0][2];
  for (let i = 0; i < path.length - 1; i++) {
    const [t0, x0, y0] = path[i];
    const [t1, x1, y1] = path[i + 1];
    if (t >= t0 && t <= t1) {
      const k = ramp(sec(t), t0, t1);
      x = x0 + (x1 - x0) * k;
      y = y0 + (y1 - y0) * k;
    } else if (t > t1) {
      x = x1;
      y = y1;
    }
  }
  const c = clicks.map((at) => press(t, at)).reduce((m, v) => ({ press: Math.max(m.press, v.press), ring: v.ring > 0 && v.ring < 1 ? v.ring : m.ring }), { press: 0, ring: 0 });
  // The hand's fingertip sits at (0.38, 0.06) of its box.
  const size = 46;
  return (
    <div style={{ position: 'absolute', left: W / 2 - 200 * S + (x - size * 0.38) * S, top: LINE + (y - size * 0.06) * S, opacity: show, transformOrigin: '0 0', transform: `scale(${S})` }}>
      <Pointer kind="hand" size={size} press={c.press} ring={c.ring} />
    </div>
  );
};

// ---------------------------------------------------------------------------------------------
// Scenes (times in seconds of the short)

const HOOK_END = b(12);
const LOGO = b(12);
const NOTCH_SCENE = b(18);
const FEATURES = b(22);
const FEATURE_LEN = b(6);
const FLOOR = b(64);
const DEVICES = b(72);
const END = b(80);

const Hook: React.FC<{ t: number }> = ({ t }) => (
  <>
    <Sweep t={t} at={b(0)} out={b(3.7)} y={H / 2 - 70} size={96} lines={[[['Why should only']]]} />
    <Sweep t={t} at={b(1.5)} out={b(3.7)} y={H / 2 + 50} size={96} lines={[[['iPhone + Mac', true], [' users']]]} />
    <Sweep t={t} at={b(4)} out={b(7.7)} size={150} display lines={[[['have all']], [['the fun?']]]} />
    <Sweep t={t} at={b(8)} out={b(11.7)} y={H / 2 - 70} size={120} lines={[[['Android,', true]]]} />
    <Sweep t={t} at={b(9.5)} out={b(11.7)} y={H / 2 + 70} size={120} lines={[[['meet your Mac.']]]} />
  </>
);

const Logo: React.FC<{ t: number }> = ({ t }) => {
  const k = t - LOGO;
  if (k < -0.1 || t > NOTCH_SCENE + 0.4) return null;
  const grow = springAt(sec(t), LOGO, 'smooth', 0.8);
  const away = ramp(sec(t), NOTCH_SCENE - 0.35, NOTCH_SCENE + 0.25);
  const r = 140 + 400 * grow + 900 * away;
  const icon = springAt(sec(t), LOGO + 0.08, 'bouncy');
  const word = ramp(sec(t), LOGO + 0.3, LOGO + 0.7);
  const tag = ramp(sec(t), LOGO + b(2.5), LOGO + b(2.5) + 0.4);
  const out = 1 - away;
  return (
    <>
      <Ring r={r} strength={clamp(k / 0.15) * (1 - away * 0.9)} spin={k * 140} />
      <div style={{ position: 'absolute', left: 0, right: 0, top: H / 2 - 230, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 22, opacity: out, transform: `scale(${1 + away * 0.3})`, filter: away > 0 ? `blur(${away * 16}px)` : undefined }}>
        <Img src={staticFile('lync-icon.svg')} style={{ width: 200, height: 200, transform: `scale(${icon}) rotate(${(1 - icon) * -25}deg)`, opacity: clamp(icon * 1.6) }} />
        <div style={{ fontFamily: font.display, fontSize: 190, lineHeight: 1, color: color.white, opacity: word, filter: `blur(${(1 - word) * 10}px)`, transform: `translateY(${(1 - word) * 30}px)`, textShadow: '0 0 40px rgba(51,255,171,0.4)' }}>Lync</div>
        <div style={{ fontFamily: font.body, fontSize: 46, fontWeight: 600, color: color.pale, opacity: tag, transform: `translateY(${(1 - tag) * 16}px)`, letterSpacing: '0.01em' }}>Android ⇄ Mac</div>
      </div>
      <Flash t={t} at={LOGO} />
    </>
  );
};

const FEATURE_LIST = ['Codes', 'Calls', 'Replies', 'Files', 'Drop', 'Music', 'Panel'] as const;
const FEATURE_ICON: Record<string, string> = { Codes: 'lock', Calls: 'phone.fill', Replies: 'message', Files: 'photo', Drop: 'arrow.up.doc.fill', Music: 'music.note', Panel: 'menubar.rectangle' };
export const THUMBS = ['#d06a6f,#4a2d86', '#15245e,#2fb57e', '#f2c14e,#e0386a'];

export const PhotoStack: React.FC<{ spread?: number }> = ({ spread = 1 }) => (
  <div style={{ position: 'relative', width: 150, height: 112 }}>
    {(
      [
        ['#d06a6f', '#4a2d86', -10, -14],
        ['#15245e', '#2fb57e', 8, 12],
        ['#f2c14e', '#e0386a', 0, 0],
      ] as [string, string, number, number][]
    ).map(([a, c, r, x], i) => (
      <div key={i} style={{ position: 'absolute', inset: 0, borderRadius: 10, border: '4px solid #fff', background: `linear-gradient(135deg, ${a}, ${c})`, transform: `rotate(${r * spread}deg) translateX(${x * spread}px)`, boxShadow: '0 10px 24px rgba(0,0,0,0.4)' }} />
    ))}
  </div>
);

/** Music playing on the phone, with its pause button (the notch's media card). */
const MusicCard: React.FC<{ paused: boolean; progress: number; pressed: number }> = ({ paused, progress, pressed }) => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: 10, fontFamily: font.body }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
      <div style={{ width: 44, height: 44, borderRadius: 12, background: 'linear-gradient(135deg, #f2c14e, #e0386a 55%, #4a2d86)', display: 'grid', placeItems: 'center' }}>
        <Icon name="music.note" size={20} color="rgba(255,255,255,0.9)" />
      </div>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 14, fontWeight: 600, color: '#fff' }}>Slow Tides</div>
        <div style={{ fontSize: 12, fontWeight: 500, color: 'rgba(255,255,255,0.6)' }}>{paused ? 'Paused · on your phone' : 'Nova Lane · on your phone'}</div>
      </div>
      <div style={{ width: 36, height: 36, borderRadius: 36, background: color.pale, display: 'grid', placeItems: 'center', transform: `scale(${1 - 0.1 * pressed})` }}>
        <Icon name={paused ? 'play.fill' : 'pause.fill'} size={15} color={color.onPale} />
      </div>
    </div>
    <div style={{ height: 3, borderRadius: 3, background: 'rgba(255,255,255,0.12)' }}>
      <div style={{ width: `${progress * 100}%`, height: '100%', borderRadius: 3, background: color.mint }} />
    </div>
  </div>
);

/** The phone's panel (a real screenshot of the Mac app) growing out of the notch. */
const PanelFromNotch: React.FC<{ open: number; scroll: number }> = ({ open, scroll }) => {
  if (open <= 0.001) return null;
  const P = 1.95;
  const visible = 500;
  return (
    <div
      style={{
        position: 'absolute',
        left: W / 2 - 190,
        top: LINE,
        width: 380,
        height: visible,
        transformOrigin: '50% 0%',
        transform: `perspective(1000px) rotateX(${(1 - Math.min(1, open)) * 22}deg) scale(${(0.5 + 0.5 * open) * P}, ${(0.2 + 0.8 * open) * P})`,
        opacity: clamp(open * 2),
        borderRadius: '0 0 32px 32px',
        overflow: 'hidden',
        background: color.ink,
        boxShadow: '0 20px 50px rgba(0,0,0,0.6)',
        WebkitMaskImage: 'linear-gradient(180deg, #000 78%, transparent)',
      }}
    >
      <Img src={staticFile('screens/mac-panel-notch.png')} style={{ position: 'absolute', left: 0, top: -scroll, width: 380, height: 2936 / 3 }} />
      <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 34, background: 'linear-gradient(180deg, #000 80%, transparent)' }} />
    </div>
  );
};

/** One feature: its neon word above the line, its card out of the notch, a pointer if it needs one. */
const Feature: React.FC<{ t: number; i: number }> = ({ t, i }) => {
  const at = FEATURES + i * FEATURE_LEN;
  const end = at + FEATURE_LEN;
  if (t < at - 0.1 || t > end + 0.3) return null;
  const k = t - at;
  const name = FEATURE_LIST[i];
  const close = 1 - ramp(sec(t), end - 0.35, end - 0.06);
  let open = springAt(sec(t), at + 0.08, 'bouncy') * close;
  let card: React.ReactNode = null;
  let pointer: React.ReactNode = null;
  let extra: React.ReactNode = null;
  if (name === 'Codes') {
    const dots = linear(sec(t), at + 0.3, at + 0.7);
    const copied = ramp(sec(t), at + 0.8, at + 0.95);
    card = <CodeCard sender="Halo Bank" dots={dots} copied={copied} />;
    // A sign-in field below, filled by a paste.
    const field = ramp(sec(t), at + 0.85, at + 1.1) * close;
    const pasted = t > at + 1.45;
    const keys = press(t, at + 1.32).press;
    extra = field > 0 ? (
      <div style={{ position: 'absolute', left: 0, right: 0, top: 1400, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 28, opacity: field, transform: `translateY(${(1 - field) * 30}px)` }}>
        <div style={{ fontFamily: font.body, fontSize: 34, fontWeight: 600, color: color.secondary }}>Verification code</div>
        <div style={{ display: 'flex', gap: 14 }}>
          {'482913'.split('').map((d, j) => {
            const p = pasted ? clamp((t - at - 1.45 - j * 0.03) / 0.15) : 0;
            return (
              <div key={j} style={{ width: 96, height: 120, borderRadius: 22, background: 'rgba(255,255,255,0.08)', border: `2px solid ${p > 0 ? 'rgba(127,227,176,0.8)' : 'rgba(255,255,255,0.16)'}`, display: 'grid', placeItems: 'center', fontFamily: font.body, fontSize: 64, fontWeight: 600, color: color.white, marginLeft: j === 3 ? 22 : 0 }}>
                <span style={{ opacity: p, transform: `translateY(${(1 - p) * 20}px)` }}>{d}</span>
              </div>
            );
          })}
        </div>
        <div style={{ display: 'flex', gap: 12, opacity: pasted ? 0.4 : 1 }}>
          {['⌘', 'V'].map((c) => (
            <div key={c} style={{ width: 84, height: 84, borderRadius: 18, background: 'rgba(255,255,255,0.1)', border: '2px solid rgba(255,255,255,0.18)', display: 'grid', placeItems: 'center', fontFamily: font.body, fontSize: 40, fontWeight: 600, color: color.white, transform: `translateY(${keys * 6}px)` }}>{c}</div>
          ))}
        </div>
      </div>
    ) : null;
  } else if (name === 'Calls') {
    const clickAt = at + 1.0;
    card = <CallCard name="Mi amor ❤️" initials="MA" sim="SIM 1" answerPressed={press(t, clickAt).press} />;
    pointer = <CardPointer t={t} path={[[at + 0.25, 330, 260], [at + 0.85, 250, 112]]} clicks={[clickAt]} show={ramp(sec(t), at + 0.25, at + 0.4) * close} />;
    extra = t > clickAt + 0.12 ? (
      <div style={{ position: 'absolute', left: 0, right: 0, top: 1350, textAlign: 'center', fontFamily: font.body, fontSize: 40, fontWeight: 600, color: color.mint, opacity: ramp(sec(t), clickAt + 0.12, clickAt + 0.35) * close }}>
        Picked up on your phone
      </div>
    ) : null;
  } else if (name === 'Replies') {
    const replyAt = at + 0.7;
    const sendAt = at + 1.85;
    const message = 'Sending it now!';
    const typed = message.slice(0, Math.round(linear(sec(t), at + 0.85, at + 1.5) * message.length));
    card = t > sendAt + 0.1 ? (
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 64, justifyContent: 'center', fontFamily: font.body, fontSize: 16, fontWeight: 600, color: color.white }}>
        <Icon name="checkmark.circle.fill" size={22} color={color.mint} />
        Sent to Design team
      </div>
    ) : (
      <NotificationCard app="Messages" time="now" title="Design team" body="Can you share the new home screen?" reply={t > replyAt + 0.1 ? typed : undefined} caret={t > replyAt + 0.1} />
    );
    pointer = (
      <CardPointer
        t={t}
        path={[
          [at + 0.2, 300, 280],
          [at + 0.6, 86, 133],
          [at + 1.5, 86, 135],
          [at + 1.75, 356, 135],
        ]}
        clicks={[replyAt, sendAt]}
        show={ramp(sec(t), at + 0.2, at + 0.35) * (1 - ramp(sec(t), sendAt + 0.25, sendAt + 0.45))}
      />
    );
  } else if (name === 'Files') {
    // Photos from the phone, flying in to the Mac.
    const progress = linear(sec(t), at + 0.3, at + 1.6);
    card = <ReceivingCard t={Math.max(0, k - 0.2)} doneAt={1.5} progress={progress} from="Pixel 9" summary={progress >= 1 ? '3 photos · 2.4 MB' : `${Math.round(progress * 3)} of 3 photos`} thumbs={THUMBS} />;
  } else if (name === 'Drop') {
    // Photos dragged up from the Mac into the notch, and on to the phone.
    const drag = ramp(sec(t), at + 0.1, at + 0.75);
    const release = ramp(sec(t), at + 0.82, at + 1.02);
    const glow = ramp(sec(t), at + 0.1, at + 0.35) * (1 - ramp(sec(t), at + 0.95, at + 1.2));
    open = springAt(sec(t), at + 1.02, 'bouncy') * close;
    const x = W / 2 + 230 * (1 - drag);
    const y = 1650 - (1650 - (LINE + 430)) * drag - release * 90;
    card = (
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, fontFamily: font.body }}>
        <div style={{ position: 'relative', width: 40, height: 32 }}>
          {['#4a2d86', '#2fb57e', '#e0386a'].map((c, j) => (
            <div key={c} style={{ position: 'absolute', inset: 0, borderRadius: 6, border: '2px solid #fff', background: c, transform: `rotate(${(j - 1) * 8}deg) translateX(${j * 3}px)` }} />
          ))}
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 15, fontWeight: 600, color: '#fff' }}>On your phone</div>
          <div style={{ fontSize: 12, fontWeight: 500, color: 'rgba(255,255,255,0.6)' }}>3 photos · Pixel 9</div>
        </div>
        <Icon name="checkmark.circle.fill" size={22} color={color.mint} />
      </div>
    );
    extra = (
      <>
        {glow > 0 && (
          <div style={{ position: 'absolute', left: W / 2 - 158 * S, top: LINE, width: 316, transformOrigin: '0 0', transform: `scale(${S})`, opacity: glow }}>
            <DragGlow approach={0.3 + 0.7 * drag} ready={drag > 0.97} summary="3 photos" phone="Pixel 9" />
          </div>
        )}
        {release < 1 && (
          <div style={{ position: 'absolute', left: x - 75 * 1.8, top: y - 56 * 1.8, transformOrigin: '50% 50%', transform: `scale(${1.8 * (1 - 0.85 * release)})`, opacity: ramp(sec(t), at, at + 0.2) * (1 - release) }}>
            <PhotoStack spread={1 - release} />
          </div>
        )}
        {t < at + 1.1 && (
          <div style={{ position: 'absolute', left: x + 40, top: y + 30, opacity: ramp(sec(t), at, at + 0.2) * (1 - ramp(sec(t), at + 0.85, at + 1.05)), transformOrigin: '0 0', transform: 'scale(2)' }}>
            <Pointer kind="arrow" size={46} />
          </div>
        )}
      </>
    );
  } else if (name === 'Music') {
    const clickAt = at + 1.05;
    const paused = t > clickAt + 0.06;
    const progress = 0.32 + 0.12 * linear(sec(Math.min(t, clickAt)), at, at + 3);
    card = <MusicCard paused={paused} progress={progress} pressed={press(t, clickAt).press} />;
    pointer = <CardPointer t={t} path={[[at + 0.25, 300, 200], [at + 0.85, 366, 66]]} clicks={[clickAt]} show={ramp(sec(t), at + 0.25, at + 0.4) * close} />;
    extra = paused ? (
      <div style={{ position: 'absolute', left: 0, right: 0, top: 1260, textAlign: 'center', fontFamily: font.body, fontSize: 40, fontWeight: 600, color: color.mint, opacity: ramp(sec(t), clickAt + 0.1, clickAt + 0.35) * close }}>
        Paused on your phone
      </div>
    ) : null;
  } else {
    const panel = springAt(sec(t), at + 0.08, 'smooth') * close;
    const scroll = 260 * ramp(sec(t), at + 0.9, at + 2.0);
    return (
      <>
        <NeonWord t={t} at={at + 0.02} out={end - 0.25} word={name} icon={FEATURE_ICON[name]} y={LINE - 260} />
        <PanelFromNotch open={panel} scroll={scroll} />
      </>
    );
  }
  return (
    <>
      <NeonWord t={t} at={at + 0.02} out={end - 0.25} word={name} icon={FEATURE_ICON[name]} y={LINE - 260} />
      {extra}
      <FromNotch open={open}>{card}</FromNotch>
      {pointer}
    </>
  );
};

const NotchScene: React.FC<{ t: number }> = ({ t }) => {
  const draw = ramp(sec(t), NOTCH_SCENE + b(0.5), NOTCH_SCENE + b(0.5) + 0.45);
  const notchIn = springAt(sec(t), NOTCH_SCENE + b(1.5), 'snappy');
  const fade = 1 - ramp(sec(t), FLOOR - 0.3, FLOOR);
  return (
    <>
      <ScreenEdge draw={draw} notchIn={notchIn} fade={fade} />
      <Sweep t={t} at={NOTCH_SCENE} out={FEATURES - 0.25} y={LINE - 300} size={92} stagger={0.016} lines={[[['Everything from']], [['your phone,']]]} />
      <Sweep t={t} at={NOTCH_SCENE + b(1.8)} out={FEATURES - 0.25} y={LINE + 330} size={120} display stagger={0.016} lines={[[['in your '], ['notch.', true]]]} />
      {FEATURE_LIST.map((_, i) => (
        <Feature key={i} t={t} i={i} />
      ))}
    </>
  );
};

/** A glowing floor far below, specks of light drifting up, and big tilted words above it. */
const Floor: React.FC<{ t: number }> = ({ t }) => {
  if (t < FLOOR - 0.1 || t > DEVICES + 0.4) return null;
  const k = t - FLOOR;
  const inn = ramp(sec(t), FLOOR - 0.1, FLOOR + 0.4);
  const out = 1 - ramp(sec(t), DEVICES - 0.2, DEVICES + 0.2);
  const horizon = 1240 - inn * 40;
  return (
    <div style={{ position: 'absolute', inset: 0, opacity: out }}>
      <div style={{ position: 'absolute', left: 0, right: 0, top: horizon, bottom: 0, background: 'linear-gradient(180deg, rgba(61,220,151,0.55), rgba(31,163,107,0.25) 25%, rgba(10,51,36,0) 75%)', opacity: inn }} />
      <div style={{ position: 'absolute', left: 0, right: 0, top: horizon - 900, height: 900, background: 'linear-gradient(0deg, rgba(31,163,107,0.35), rgba(31,163,107,0))', opacity: inn }} />
      {/* Vertical panes of light rising from the floor, at the sides. */}
      {[-1, 1].map((s) => (
        <div key={s} style={{ position: 'absolute', top: horizon - 700, height: 700, width: 280, [s < 0 ? 'left' : 'right']: 0, background: `linear-gradient(${s < 0 ? 90 : 270}deg, rgba(61,220,151,0.35), rgba(61,220,151,0))`, opacity: inn }} />
      ))}
      <div style={{ position: 'absolute', left: W / 2 - (W / 2) * inn, width: W * inn, top: horizon - 2, height: 5, background: 'linear-gradient(90deg, rgba(218,251,238,0.2), #ffffff 50%, rgba(218,251,238,0.2))', boxShadow: '0 0 30px #33ffab, 0 0 80px rgba(51,255,171,0.7)' }} />
      {Array.from({ length: 16 }, (_, i) => {
        const x = (i * 263) % W;
        const y = horizon - 60 - ((k * (40 + (i % 5) * 18) + i * 97) % 900);
        return <div key={i} style={{ position: 'absolute', left: x, top: y, width: 5, height: 5, borderRadius: 5, background: '#bfffe0', boxShadow: '0 0 12px #33ffab', opacity: 0.25 + 0.5 * ((i * 7) % 3) / 2 }} />;
      })}
      <div style={{ position: 'absolute', inset: 0, transform: 'perspective(1400px) rotateY(-16deg) rotateZ(-7deg)', transformOrigin: '50% 45%' }}>
        <Sweep t={t} at={FLOOR + 0.05} out={FLOOR + b(3) - 0.2} y={horizon - 330} size={120} glow lines={[[['Even off '], ['Wi‑Fi.', true]]]} />
        <Sweep t={t} at={FLOOR + b(3)} out={FLOOR + b(5.5) - 0.15} y={horizon - 330} size={130} glow lines={[[['No account.']]]} />
        <Sweep t={t} at={FLOOR + b(5.5)} out={DEVICES - 0.2} y={horizon - 330} size={130} glow lines={[[['No cloud.']]]} />
      </div>
    </div>
  );
};

/**
 * The two devices together, a hero shot: the Mac with a delivery in its notch, the phone in front
 * of it showing Lync's activity, both standing on a soft pool of green light.
 */
const Devices: React.FC<{ t: number }> = ({ t }) => {
  if (t < DEVICES - 0.1 || t > END + 0.4) return null;
  const k = t - DEVICES;
  const mac = springAt(sec(t), DEVICES, 'smooth');
  const phone = springAt(sec(t), DEVICES + 0.22, 'smooth');
  const card = springAt(sec(t), DEVICES + 0.75, 'bouncy');
  const out = ramp(sec(t), END - 0.35, END + 0.15);
  const push = 1 + 0.035 * linear(sec(t), DEVICES, END);
  const macS = 0.5;
  const macW = (1470 + 36) * 1.12;
  const phoneS = 0.6;
  const macLeft = W / 2 - (macW * macS) / 2 - 60;
  return (
    <div style={{ position: 'absolute', inset: 0, opacity: 1 - out, transform: `scale(${push * (1 - 0.08 * out)})`, filter: out > 0 ? `blur(${out * 12}px)` : undefined }}>
      <Sweep t={t} at={DEVICES + 0.05} out={END - 0.3} y={520} size={96} lines={[[['Just your']], [['two devices.', true]]]} />
      {/* The pool of light they stand in. */}
      <div style={{ position: 'absolute', left: W / 2 - 620, top: 1080, width: 1240, height: 560, borderRadius: '50%', background: 'radial-gradient(closest-side, rgba(61,220,151,0.45), rgba(31,163,107,0.18) 55%, rgba(31,163,107,0))', opacity: mac }} />
      <div style={{ position: 'absolute', left: W / 2 - 420, top: 1360, width: 840, height: 4, borderRadius: 4, background: 'linear-gradient(90deg, rgba(191,255,224,0), rgba(191,255,224,0.8), rgba(191,255,224,0))', boxShadow: '0 0 30px rgba(51,255,171,0.6)', opacity: mac }} />
      <div style={{ position: 'absolute', left: macLeft, top: 840, transformOrigin: '0 0', transform: `translateY(${(1 - mac) * 260}px) scale(${macS})`, opacity: clamp(mac * 2) }}>
        <MacBook linked>
          <MacWallpaper drift={k * 0.3} />
          <MacMenuBar />
          {card > 0.01 && (
            <div style={{ position: 'absolute', left: (1470 - 400) / 2, top: 0 }}>
              <NotchCard open={card}>
                <ReceivingCard t={9} doneAt={1} progress={1} from="Pixel 9" summary="3 photos · 2.4 MB" thumbs={THUMBS} />
              </NotchCard>
            </div>
          )}
        </MacBook>
      </div>
      <div
        style={{
          position: 'absolute',
          left: 642,
          top: 1480 - 939,
          transformOrigin: '50% 100%',
          transform: `translate(${(1 - phone) * 360}px, ${(1 - phone) * 80}px) rotate(${-4 * phone}deg) scale(${phoneS})`,
          opacity: clamp(phone * 2),
          filter: 'drop-shadow(-30px 40px 50px rgba(0,0,0,0.6))',
        }}
      >
        <div style={{ transformOrigin: '0 0' }}>
          <Phone>
            <Img src={staticFile('screens/phone-activity.png')} style={{ width: 412, height: 915, display: 'block' }} />
          </Phone>
        </div>
      </div>
    </div>
  );
};

const EndCard: React.FC<{ t: number }> = ({ t }) => {
  if (t < END - 0.1) return null;
  const k = t - END;
  const grow = springAt(sec(t), END, 'smooth', 0.9);
  const icon = springAt(sec(t), END + 0.1, 'bouncy');
  const fade = (from: number) => ramp(sec(t), END + from, END + from + 0.4);
  return (
    <>
      <Ring r={180 + 420 * grow} strength={clamp(k / 0.2)} spin={60 + k * 70} />
      <div style={{ position: 'absolute', left: 0, right: 0, top: H / 2 - 330, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 26 }}>
        <Img src={staticFile('lync-icon.svg')} style={{ width: 180, height: 180, transform: `scale(${icon})`, opacity: clamp(icon * 1.6) }} />
        <div style={{ fontFamily: font.display, fontSize: 170, lineHeight: 1, color: color.white, opacity: fade(0.2), filter: `blur(${(1 - fade(0.2)) * 10}px)` }}>Lync</div>
        <div style={{ fontFamily: font.body, fontSize: 46, fontWeight: 600, color: color.white, textAlign: 'center', lineHeight: 1.25, opacity: fade(0.6), transform: `translateY(${(1 - fade(0.6)) * 16}px)` }}>
          Everything from your phone,
          <br />
          <span style={{ color: color.mint }}>in your notch.</span>
        </div>
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: H / 2 + 230, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 22, opacity: fade(1.3), transform: `translateY(${(1 - fade(1.3)) * 20}px)` }}>
        <div style={{ fontFamily: font.body, fontSize: 36, fontWeight: 600, color: 'rgba(255,255,255,0.75)' }}>Free for Android and Mac</div>
      </div>
      <Flash t={t} at={END} strength={0.5} />
    </>
  );
};

// ---------------------------------------------------------------------------------------------
// Sound

/** [seconds, file in public/short, volume] */
const F = (i: number) => FEATURES + i * FEATURE_LEN;
const SOUNDS: [number, string, number][] = [
  [0, 'music', 1],
  // Hook: a breath of air on each line.
  [0, 'swish', 0.3],
  [b(1.5) - 0.15, 'swish', 0.28],
  [b(4) - 0.15, 'whoosh', 0.42],
  [b(8) - 0.15, 'whoosh', 0.38],
  [b(9.5) - 0.15, 'swish', 0.28],
  [LOGO - 0.02, 'shimmer', 0.55],
  [NOTCH_SCENE - 0.15, 'whoosh', 0.38],
  [NOTCH_SCENE + b(1.5), 'pop', 0.45],
  // Between the features, a light swish as one card goes back in and the next comes out.
  ...[1, 2, 3, 4, 5, 6].map((i): [number, string, number] => [F(i) - 0.15, 'swish', 0.22]),
  [F(0) + 0.08, 'notify', 0.45],
  [F(0) + 1.32, 'paste', 0.55],
  [F(1) + 0.08, 'notify', 0.45],
  [F(1) + 1.0, 'click', 0.55],
  [F(2) + 0.08, 'notify', 0.45],
  [F(2) + 0.7, 'click', 0.55],
  [F(2) + 0.85, 'typing', 0.45],
  [F(2) + 1.85, 'click', 0.55],
  [F(3) + 0.2, 'transfer', 0.75],
  [F(4) + 0.82, 'pop', 0.45],
  [F(4) + 1.02, 'notify', 0.42],
  [F(5) + 0.08, 'notify', 0.42],
  [F(5) + 1.05, 'click', 0.55],
  [F(6) + 0.08, 'pop', 0.4],
  [FLOOR - 0.15, 'whoosh', 0.45],
  [FLOOR + b(3) - 0.02, 'pop', 0.38],
  [FLOOR + b(5.5) - 0.02, 'pop', 0.38],
  [DEVICES - 0.15, 'whoosh', 0.4],
  [DEVICES + 0.75, 'connect', 0.45],
  [END - 0.02, 'shimmer', 0.5],
];

const Sounds: React.FC = () => (
  <>
    {SOUNDS.map(([at, name, volume], i) => (
      <Sequence key={i} from={Math.max(0, sec(at))} layout="none">
        <Audio src={staticFile(`short/${name}.wav`)} volume={volume} />
      </Sequence>
    ))}
  </>
);

export const Short: React.FC = () => {
  const t = useCurrentFrame() / 60;
  return (
    <div style={{ position: 'absolute', inset: 0, width: W, height: H, overflow: 'hidden', background: '#040907' }}>
      <Sounds />
      <Backdrop t={t} />
      {t < HOOK_END + 0.3 && <Hook t={t} />}
      <Logo t={t} />
      {t > NOTCH_SCENE - 0.1 && t < FLOOR + 0.3 && <NotchScene t={t} />}
      <Floor t={t} />
      <Devices t={t} />
      <EndCard t={t} />
    </div>
  );
};
