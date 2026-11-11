import React from 'react';
import { Audio, Img, Sequence, staticFile, useCurrentFrame } from 'remotion';
import { CallCard, CodeCard, NotificationCard } from '../components/Cards';
import { MacBook, Phone, phoneScreen } from '../components/Devices';
import { MacMenuBar, MacWallpaper } from '../components/Desktop';
import { DragGlow } from '../components/DragGlow';
import { Icon } from '../components/Icon';
import { ReceivingCard } from '../components/Liftoff';
import { NotchCard } from '../components/NotchCard';
import { PhoneHome, PhoneLock } from '../components/PhoneHome';
import { Pointer, Touch } from '../components/Pointer';
import { QuickSettings } from '../components/QuickSettings';
import { linear, ramp, sec, springAt } from '../film/motion';
import { clamp, Flash, NeonWord, PhotoStack, press, Sweep, THUMBS } from '../short/Short';
import { color, font, notch } from '../theme';
import { BgKind, WideBackground } from './Backgrounds';

/**
 * The wide film (1920×1080), in the short's style: fast type on the beat, the ring logo, then
 * every feature as a neon word over Lync's own cards coming out of a notch, with the phone
 * beside it when the phone is part of the story. Timed in beats of the music (135 BPM).
 */

const W = 1920;
const H = 1080;
const BEAT = 60 / 135;
const b = (n: number) => n * BEAT;
export const WIDE_SECONDS = b(128);

/** The top edge of the "screen", the notch's centre, and the scale the notch's cards are drawn at. */
const LINE = 300;
const CX = W / 2;
const S = 2.2;
/** The panel's scale and its place under the notch. */
const P = 1.5;
const PANEL_W = 380;
const PANEL_VISIBLE = 470;
const PANEL_LEFT = CX - (PANEL_W * P) / 2;
const BTN = { pause: [284, 639], gear: [345, 62], menuBar: [280, 148] };
const MUSIC_SCROLL = 260;
/** The menu bar's Lync icon, on screen. */
const LINK_ICON: [number, number] = [1530, LINE + 31];

// ---------------------------------------------------------------------------------------------
// Timeline

const LOGO = b(12);
const NOTCH_SCENE = b(18);
const FEATURES = b(22);
/** Codes is long: a whole checkout on the Mac, the code arriving and pasted. The music repeats a bar to make room. */
const FEATURE_BEATS = [14, 6, 6, 8, 6, 6, 6, 8, 6, 8, 6];
const FEATURE_NAMES = ['Codes', 'Calls', 'Replies', 'Files', 'Drop', 'Panel', 'Music', 'Menu bar', 'Android app', 'Your colors', 'Off Wi‑Fi'];
const FEATURE_ICONS = ['lock', 'phone.fill', 'message', 'photo', 'arrow.up.doc.fill', 'menubar.rectangle', 'music.note', 'menubar.rectangle', 'iphone.gen3', 'photo', 'wifi.slash'];
const F: number[] = [];
FEATURE_BEATS.reduce((at, n) => (F.push(b(at)), at + n), 22);
const PROMISES = b(102);
const FEND = (i: number) => (i + 1 < F.length ? F[i + 1] : PROMISES);
const DEVICES = b(110);
const END = b(116);

// ---------------------------------------------------------------------------------------------
// Pieces

/** Dark green, with light leaking in at the left and right edges, drifting. */
const Backdrop: React.FC<{ t: number }> = ({ t }) => {
  const a = Math.sin(t * 0.5) * 140;
  const c = Math.cos(t * 0.37) * 160;
  return (
    <div style={{ position: 'absolute', inset: 0, background: '#040907', overflow: 'hidden' }}>
      <div style={{ position: 'absolute', left: -520, top: 80 + a, width: 900, height: 1000, borderRadius: '50%', background: 'radial-gradient(closest-side, rgba(31,163,107,0.5), rgba(31,163,107,0))', filter: 'blur(30px)' }} />
      <div style={{ position: 'absolute', right: -540, top: -60 + c, width: 920, height: 1100, borderRadius: '50%', background: 'radial-gradient(closest-side, rgba(23,120,80,0.55), rgba(23,120,80,0))', filter: 'blur(30px)' }} />
      <div style={{ position: 'absolute', inset: 0, background: 'radial-gradient(55% 60% at 50% 50%, rgba(4,9,7,0.75), rgba(4,9,7,0))' }} />
    </div>
  );
};

/** The top edge of a screen: a fine line of light, the screen's glow below it, the notch hanging from it. */
const ScreenEdge: React.FC<{ draw: number; notchIn: number; fade: number; menuBar: number }> = ({ draw, notchIn, fade, menuBar }) => {
  if (draw <= 0 || fade <= 0) return null;
  return (
    <div style={{ position: 'absolute', inset: 0, opacity: fade }}>
      <div style={{ position: 'absolute', left: 0, right: 0, top: LINE, height: 780, background: 'radial-gradient(55% 75% at 50% 0%, rgba(54,161,117,0.3), rgba(54,161,117,0))', opacity: draw }} />
      {/* The menu bar, when Lync lives there: Lync's link, Wi‑Fi, battery and the time. */}
      {menuBar > 0 && (
        <div style={{ position: 'absolute', left: 0, right: 0, top: LINE, height: notch.height * S, background: 'rgba(0,0,0,0.35)', opacity: menuBar, display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 34, paddingRight: 60, fontFamily: font.body, fontSize: 26, fontWeight: 600, color: color.white }}>
          <div style={{ position: 'absolute', left: LINK_ICON[0] - 22, top: (notch.height * S) / 2 - 22, width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
            <Icon name="link" size={30} color={color.white} />
          </div>
          <span style={{ opacity: 0.9 }}>82%</span>
          <span>Mon 9:41 AM</span>
        </div>
      )}
      <div
        style={{
          position: 'absolute',
          left: CX - (W / 2) * draw,
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
          left: CX - (notch.width * S) / 2,
          top: LINE,
          width: notch.width * S,
          height: notch.height * S * notchIn,
          background: '#000',
          borderRadius: `0 0 ${22 * notchIn}px ${22 * notchIn}px`,
          boxShadow: notchIn > 0 ? '0 0 0 1.5px rgba(127,227,176,0.25)' : undefined,
        }}
      />
    </div>
  );
};

/** A card growing out of the notch: from the notch's size, springing open, tilting flat. */
const FromNotch: React.FC<{ open: number; children: React.ReactNode; width?: number }> = ({ open, children, width = 400 }) => {
  if (open <= 0.001) return null;
  return (
    <div
      style={{
        position: 'absolute',
        left: CX - width / 2,
        top: LINE,
        width,
        transformOrigin: '50% 0%',
        transform: `perspective(1000px) rotateX(${(1 - Math.min(1, open)) * 26}deg) scale(${(0.46 + 0.54 * open) * S}, ${(0.2 + 0.8 * open) * S})`,
        filter: 'drop-shadow(0 14px 28px rgba(0,0,0,0.6))',
      }}
    >
      <NotchCard open={Math.min(1, open * 1.4)} width={width}>
        <div style={{ opacity: clamp((open - 0.5) * 2.5) }}>{children}</div>
      </NotchCard>
    </div>
  );
};

/** The Mac app's real panel (snapshots at 3×), stacked to switch states, sliding sideways for settings. */
const PANEL_H: Record<string, number> = {
  'mac-panel-notch': 2936 / 3,
  'mac-panel-paused': 2936 / 3,
  'mac-settings-notch': 2621 / 3,
  'mac-settings-menubar': 2621 / 3,
  'mac-panel-menubar': 2840 / 3,
};
const Panel: React.FC<{ open: number; layers: [string, number, number?][]; scroll?: number; left?: number; top?: number; fromNotch?: boolean }> = ({
  open,
  layers,
  scroll = 0,
  left = PANEL_LEFT,
  top = LINE,
  fromNotch = true,
}) => {
  if (open <= 0.001) return null;
  return (
    <div
      style={{
        position: 'absolute',
        left,
        top,
        width: PANEL_W,
        height: PANEL_VISIBLE,
        transformOrigin: '0 0',
        transform: `scale(${P})`,
      }}
    >
      <div
        style={{
          position: 'absolute',
          inset: 0,
          transformOrigin: fromNotch ? '50% 0%' : '70% 0%',
          transform: `perspective(1000px) rotateX(${(1 - Math.min(1, open)) * 20}deg) scale(${0.5 + 0.5 * open}, ${0.2 + 0.8 * open})`,
          opacity: clamp(open * 2),
          borderRadius: fromNotch ? '0 0 32px 32px' : 32,
          overflow: 'hidden',
          background: color.ink,
          boxShadow: '0 20px 50px rgba(0,0,0,0.6)',
          WebkitMaskImage: 'linear-gradient(180deg, #000 80%, transparent)',
        }}
      >
        {layers.map(([name, opacity, dx = 0]) =>
          opacity > 0 ? <Img key={name} src={staticFile(`screens/${name}.png`)} style={{ position: 'absolute', left: 0, top: 0, width: PANEL_W, height: PANEL_H[name], opacity, transform: `translate(${dx}px, ${-scroll}px)` }} /> : null,
        )}
        {fromNotch && <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 34, background: 'linear-gradient(180deg, #000 80%, transparent)' }} />}
      </div>
    </div>
  );
};

/** Card space → screen: a point on a 400-wide notch card. */
const onCard = (x: number, y: number, width = 400): [number, number] => [CX + (x - width / 2) * S, LINE + y * S];
/** Panel space → screen. */
const onPanel = (x: number, y: number): [number, number] => [PANEL_LEFT + x * P, LINE + y * P];

/** The pointer at a point (its tip), gliding along [time, x, y] keys and clicking. */
const Cursor: React.FC<{ t: number; path: [number, number, number][]; clicks?: number[]; show: number; kind?: 'arrow' | 'hand' }> = ({ t, path, clicks = [], show, kind = 'hand' }) => {
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
  const size = 46 * 1.6;
  const tip = kind === 'arrow' ? [0.12, 0.08] : [0.38, 0.06];
  return (
    <div style={{ position: 'absolute', left: x - size * tip[0], top: y - size * tip[1], opacity: show }}>
      <Pointer kind={kind} size={size} press={c.press} ring={c.ring} />
    </div>
  );
};

/** Where the phone stands beside the notch, and phone screen space → screen. */
const PH = { left: 150, top: 330, s: 0.66 };
const onPhone = (x: number, y: number, ph = PH): [number, number] => [ph.left + (12 + x) * ph.s, ph.top + (12 + y) * ph.s];

const Tap: React.FC<{ t: number; at: number; p: [number, number] }> = ({ t, at, p }) => {
  const c = press(t, at);
  if (c.press <= 0 && (c.ring <= 0 || c.ring >= 1)) return null;
  return (
    <div style={{ position: 'absolute', left: p[0] - 28 * 0.8, top: p[1] - 28 * 0.8, transform: 'scale(0.8)', transformOrigin: '0 0' }}>
      <Touch press={c.press} ring={c.ring} size={56} />
    </div>
  );
};

/** A small line of mint text, under the notch's card. */
const Note: React.FC<{ text: string; show: number; y: number; x?: number }> = ({ text, show, y, x = CX }) =>
  show > 0 ? (
    <div style={{ position: 'absolute', left: x - 500, width: 1000, top: y, textAlign: 'center', fontFamily: font.body, fontSize: 34, fontWeight: 600, color: color.mint, opacity: show, transform: `translateY(${(1 - show) * 12}px)` }}>{text}</div>
  ) : null;

// ---------------------------------------------------------------------------------------------
// Scenes

const Hook: React.FC<{ t: number }> = ({ t }) => (
  <>
    <Sweep t={t} at={b(0)} out={b(3.7)} y={H / 2 - 60} size={92} lines={[[['Why should only']]]} />
    <Sweep t={t} at={b(1.5)} out={b(3.7)} y={H / 2 + 50} size={92} lines={[[['iPhone + Mac', true], [' users']]]} />
    <Sweep t={t} at={b(4)} out={b(7.7)} y={H / 2} size={170} display lines={[[['have all the fun?']]]} />
    <Sweep t={t} at={b(8)} out={b(11.7)} y={H / 2} size={120} lines={[[['Android, ', true], ['meet your Mac.']]]} />
  </>
);

/**
 * The logo's arrival: streaks of light rush in to the centre, the icon lands with a soft bloom and
 * a thin shockwave rippling out, then it slides aside as the wordmark slides out from behind it
 * and a glint runs across. `y` is the lockup's centre line.
 */
const LockupReveal: React.FC<{ t: number; at: number; y: number; waves?: number[] }> = ({ t, at, y, waves = [0.04, 0.26] }) => {
  const k = t - at;
  const ICON = 150;
  const WORD = 330;
  const GAP = 34;
  const total = ICON + GAP + WORD;
  const icon = springAt(sec(t), at, 'bouncy');
  const slide = ramp(sec(t), at + 0.42, at + 0.95);
  const iconX = CX - total / 2 + ICON / 2 + (total / 2 - ICON / 2) * (1 - slide);
  const bloom = springAt(sec(t), at, 'smooth');
  const breathe = 1 + 0.04 * Math.sin(k * 2.2);
  const glint = clamp((k - 1.0) / 0.55);
  return (
    <>
      {/* Bloom: a soft pool of the phone's green behind the mark. */}
      <div style={{ position: 'absolute', left: CX - 700, top: y - 520, width: 1400, height: 1040, borderRadius: '50%', background: 'radial-gradient(closest-side, rgba(51,255,171,0.32), rgba(31,163,107,0.14) 45%, rgba(31,163,107,0))', opacity: bloom, transform: `scale(${(0.6 + 0.4 * bloom) * breathe})` }} />
      {/* Streaks rushing in. */}
      {k > -0.4 && k < 0.1 && (
        <svg width={W} height={H} style={{ position: 'absolute', inset: 0 }} aria-hidden>
          {Array.from({ length: 16 }, (_, i) => {
            const a = (i / 16) * Math.PI * 2 + (i % 3) * 0.11;
            const u = clamp((k + 0.4 - (i % 4) * 0.04) / 0.4);
            if (u <= 0 || u >= 1) return null;
            const e = u * u;
            const d = 1150 - 1060 * e;
            const len = 220 * (1 - 0.6 * e);
            const x1 = CX + Math.cos(a) * d;
            const y1 = y + Math.sin(a) * d;
            const x2 = CX + Math.cos(a) * (d + len);
            const y2 = y + Math.sin(a) * (d + len);
            return <line key={i} x1={x1} y1={y1} x2={x2} y2={y2} stroke="#dafbee" strokeWidth={3} strokeLinecap="round" opacity={0.85 * Math.sin(u * Math.PI)} style={{ filter: 'drop-shadow(0 0 6px #33ffab)' }} />;
          })}
        </svg>
      )}
      {/* Shockwaves: thin lines of light, rippling out once. */}
      {waves.map((w) => {
        const u = (k - w) / 1.2;
        if (u <= 0 || u >= 1) return null;
        const r = 80 + 1000 * (1 - Math.pow(1 - u, 3));
        return <div key={w} style={{ position: 'absolute', left: CX - r, top: y - r, width: r * 2, height: r * 2, borderRadius: '50%', border: '2px solid rgba(218,251,238,0.9)', boxShadow: '0 0 18px rgba(51,255,171,0.7), inset 0 0 18px rgba(51,255,171,0.5)', opacity: 0.8 * Math.pow(1 - u, 1.6) }} />;
      })}
      {/* The lockup. */}
      <div style={{ position: 'absolute', left: iconX - ICON / 2, top: y - ICON / 2, width: ICON, height: ICON, transform: `scale(${0.3 + 0.7 * icon})`, opacity: clamp(icon * 2), filter: 'drop-shadow(0 0 30px rgba(51,255,171,0.45))' }}>
        <Img src={staticFile('lync-icon.svg')} style={{ width: ICON, height: ICON }} />
      </div>
      <div style={{ position: 'absolute', left: iconX + ICON / 2 + GAP, top: y - 95, width: WORD + 40, height: 190, overflow: 'hidden', clipPath: `inset(0 ${(1 - slide) * 100}% 0 0)` }}>
        <div style={{ position: 'relative', fontFamily: font.display, fontSize: 150, lineHeight: '190px', color: color.white, transform: `translateX(${(1 - slide) * -120}px)`, textShadow: '0 0 40px rgba(51,255,171,0.35)', whiteSpace: 'nowrap' }}>
          Lync
          {glint > 0 && glint < 1 && (
            <span style={{ position: 'absolute', left: 0, top: 0, color: 'transparent', backgroundImage: `linear-gradient(105deg, transparent ${glint * 140 - 30}%, rgba(255,255,255,0.95) ${glint * 140 - 15}%, transparent ${glint * 140}%)`, WebkitBackgroundClip: 'text', backgroundClip: 'text' }}>Lync</span>
          )}
        </div>
      </div>
    </>
  );
};

const Logo: React.FC<{ t: number }> = ({ t }) => {
  const k = t - LOGO;
  if (k < -0.45 || t > NOTCH_SCENE + 0.4) return null;
  const away = ramp(sec(t), NOTCH_SCENE - 0.35, NOTCH_SCENE + 0.25);
  const tag = ramp(sec(t), LOGO + 0.9, LOGO + 1.3);
  return (
    <div style={{ position: 'absolute', inset: 0, opacity: 1 - away, transform: `scale(${1 + away * 0.25})`, filter: away > 0 ? `blur(${away * 16}px)` : undefined }}>
      <LockupReveal t={t} at={LOGO} y={H / 2 - 30} />
      <div style={{ position: 'absolute', left: 0, right: 0, top: H / 2 + 80, textAlign: 'center', fontFamily: font.body, fontSize: 38, fontWeight: 600, color: color.pale, letterSpacing: '0.04em', opacity: tag, transform: `translateY(${(1 - tag) * 14}px)` }}>Android ⇄ Mac</div>
      <Flash t={t} at={LOGO} strength={0.35} />
    </div>
  );
};

/** The wallpapers in "Your colors", as turns of the colour wheel from Lync's green, and when each lands. */
const COLOR_HUES = [0, 70, 150, 205];
const COLOR_CHANGES = [0.55, 1.35, 2.15];
const wallpaperHue = (t: number, at: number, lag: number) =>
  COLOR_CHANGES.reduce((h, c, i) => h + (COLOR_HUES[i + 1] - COLOR_HUES[i]) * ramp(sec(t), at + c + lag, at + c + lag + (lag ? 0.4 : 0.22)), COLOR_HUES[0]);

/** The phone beside the notch while it's part of the story: Files and Drop, then the app and Wi‑Fi. */
const SidePhone: React.FC<{ t: number }> = ({ t }) => {
  const windows: [number, number][] = [
    [F[3], FEND(4)],
    [F[8], FEND(10)],
  ];
  const w = windows.find(([a, z]) => t > a - 0.3 && t < z + 0.3);
  if (!w) return null;
  const inn = springAt(sec(t), w[0] - 0.1, 'smooth');
  const out = ramp(sec(t), w[1] - 0.35, w[1] - 0.05);
  const show = inn * (1 - out);
  if (show <= 0.001) return null;
  let screen: React.ReactNode;
  if (t < F[4]) {
    // Files: the home screen, Photos on the widget tapped.
    const at = F[3];
    const pressed = press(t, at + 0.45).press;
    screen = <PhoneHome photosPressed={pressed} drift={linear(sec(t), at, at + 3)} />;
  } else if (t < F[5]) {
    // Drop: the lock screen, the photos arriving from the Mac.
    const at = F[4];
    screen = <PhoneLock arrive={ramp(sec(t), at + 1.15, at + 1.45)} from="Lync" message="3 photos from your Mac · saved to Photos" />;
  } else if (t < F[9]) {
    // The Android app: its tabs, swiped through.
    const at = F[8];
    const tabs = ['phone-home', 'phone-activity', 'phone-settings'];
    const pos = ramp(sec(t), at + 0.75, at + 1.05) + ramp(sec(t), at + 1.65, at + 1.95);
    screen = (
      <div style={{ position: 'absolute', inset: 0, overflow: 'hidden' }}>
        {tabs.map((name, i) => (
          <Img key={name} src={staticFile(`screens/${name}.png`)} style={{ position: 'absolute', left: 0, top: 0, width: phoneScreen.width + 4, height: phoneScreen.height + 4, transform: `translateX(${(i - pos) * 100}%)` }} />
        ))}
      </div>
    );
  } else if (t < F[10]) {
    // Your colors: the wallpaper changes, and Lync takes on its colours a moment later.
    const at = F[9];
    screen = <PhoneHome drift={linear(sec(t), at, at + 4)} wallHue={wallpaperHue(t, at, 0)} appHue={wallpaperHue(t, at, 0.28)} />;
  } else {
    // Off Wi‑Fi: the shade down, Wi‑Fi off, Bluetooth on, the shade back up.
    const at = F[10];
    const shade = ramp(sec(t), at + 0.1, at + 0.45) * (1 - ramp(sec(t), at + 1.35, at + 1.7));
    screen = (
      <>
        <PhoneHome drift={1} />
        {shade > 0 && <QuickSettings pull={shade} wifiOff={ramp(sec(t), at + 0.72, at + 0.84)} wifiPressed={press(t, at + 0.65).press} />}
      </>
    );
  }
  return (
    <div style={{ position: 'absolute', left: PH.left, top: PH.top, transformOrigin: '0 0', transform: `translateX(${(1 - show) * -260}px) scale(${PH.s})`, opacity: clamp(show * 1.5), filter: 'drop-shadow(0 30px 40px rgba(0,0,0,0.55))' }}>
      <Phone>{screen}</Phone>
    </div>
  );
};

/** Where the checkout window sits: right of the words, under the notch's card. */
const SHOP = { left: 730, top: 590, w: 880, h: 400 };

/**
 * Codes, told as a checkout: paying on the Mac, the bank's code lands in the notch already
 * copied, ⌘V fills the boxes, and the order goes through. The phone never comes out.
 */
const CheckoutStory: React.FC<{ t: number; at: number; end: number; close: number }> = ({ t, at, end, close }) => {
  const k = (s: number) => at + s;
  const show = springAt(sec(t), k(0.1), 'smooth') * close;
  const payAt = k(0.95);
  const focusAt = k(1.85);
  const pasteAt = k(2.7);
  const verifyAt = k(3.25);
  const verify = ramp(sec(t), payAt + 0.15, payAt + 0.4);
  const done = ramp(sec(t), verifyAt + 0.1, verifyAt + 0.35);
  const filled = (j: number) => clamp((t - pasteAt - 0.05 - j * 0.035) / 0.14);
  const focused = t > focusAt && t < pasteAt + 0.05;
  const keys = ramp(sec(t), focusAt + 0.25, focusAt + 0.5) * (1 - ramp(sec(t), pasteAt + 0.35, pasteAt + 0.6));
  const pay: [number, number] = [SHOP.left + SHOP.w / 2, SHOP.top + 334];
  const box: [number, number] = [SHOP.left + 78, SHOP.top + 216];
  const ver: [number, number] = [SHOP.left + SHOP.w / 2, SHOP.top + 334];
  const page = (o: number, dy: number): React.CSSProperties => ({ position: 'absolute', left: 40, right: 40, top: 76, bottom: 32, opacity: o, transform: `translateY(${dy}px)` });
  const button = (label: string, live: number, pressed: number): React.ReactNode => (
    <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: 68, borderRadius: 99, background: live > 0.5 ? color.pale : 'rgba(255,255,255,0.12)', color: live > 0.5 ? color.onPale : 'rgba(255,255,255,0.5)', display: 'grid', placeItems: 'center', fontSize: 26, fontWeight: 700, transform: `scale(${1 - pressed * 0.04})` }}>{label}</div>
  );
  return (
    <>
      <Sweep t={t} at={k(0.25)} out={k(1.9)} y={780} left={40} right={1260} size={60} lines={[[['Checking out']], [['on your Mac?']]]} />
      <Sweep t={t} at={k(2.0)} out={k(3.35)} y={780} left={40} right={1260} size={60} lines={[[['No reaching for']], [['your phone.', true]]]} />
      <Sweep t={t} at={k(3.45)} out={end - 0.25} y={780} left={40} right={1260} size={60} lines={[[['The code is']], [['already copied.']], [['Just ⌘V.', true]]]} />
      {/* The checkout, in a browser window on the Mac. */}
      {show > 0.001 && (
        <div style={{ position: 'absolute', left: SHOP.left, top: SHOP.top, width: SHOP.w, height: SHOP.h, borderRadius: 26, background: '#121614', border: '1.5px solid rgba(255,255,255,0.12)', boxShadow: '0 30px 70px rgba(0,0,0,0.55)', overflow: 'hidden', fontFamily: font.body, color: color.white, opacity: clamp(show * 2), transform: `translateY(${(1 - show) * 60}px) scale(${0.94 + 0.06 * show})` }}>
          <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 52, display: 'flex', alignItems: 'center', gap: 10, padding: '0 20px', borderBottom: '1.5px solid rgba(255,255,255,0.07)' }}>
            {['#ff5f57', '#febc2e', '#28c840'].map((c) => <div key={c} style={{ width: 13, height: 13, borderRadius: 99, background: c }} />)}
            <div style={{ marginLeft: 120, flex: 1, maxWidth: 420, height: 32, borderRadius: 10, background: 'rgba(255,255,255,0.07)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, fontSize: 17, fontWeight: 500, color: 'rgba(255,255,255,0.6)' }}>
              <Icon name="lock" size={14} color="rgba(255,255,255,0.6)" />
              kettleandco.shop/checkout
            </div>
          </div>
          {/* 1: the cart. */}
          {verify < 1 && (
            <div style={page(1 - verify, -verify * 20)}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 22, marginTop: 14 }}>
                <div style={{ width: 92, height: 92, borderRadius: 18, background: 'linear-gradient(135deg, #e9b98a, #a05a32)' }} />
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 28, fontWeight: 700 }}>Pour‑over coffee set</div>
                  <div style={{ fontSize: 20, fontWeight: 500, color: color.secondary, marginTop: 6 }}>Ceramic · Qty 1</div>
                </div>
                <div style={{ fontSize: 30, fontWeight: 700 }}>₹2,499</div>
              </div>
              <div style={{ height: 1.5, background: 'rgba(255,255,255,0.08)', margin: '28px 0 0' }} />
              {button('Pay ₹2,499', 1, press(t, payAt).press)}
            </div>
          )}
          {/* 2: the bank asks for the code. */}
          {verify > 0 && done < 1 && (
            <div style={page(verify * (1 - done), (1 - verify) * 24 - done * 20)}>
              <div style={{ fontSize: 30, fontWeight: 700, marginTop: 6 }}>Verify it’s you</div>
              <div style={{ fontSize: 20, fontWeight: 500, color: color.secondary, marginTop: 6 }}>Enter the code Halo Bank sent to your phone</div>
              <div style={{ display: 'flex', gap: 14, marginTop: 26 }}>
                {'482913'.split('').map((d, j) => {
                  const p = filled(j);
                  const ring = (focused && j === 0) || p > 0;
                  return (
                    <div key={j} style={{ width: 70, height: 84, borderRadius: 16, background: 'rgba(255,255,255,0.07)', border: `2px solid ${ring ? 'rgba(127,227,176,0.85)' : 'rgba(255,255,255,0.14)'}`, display: 'grid', placeItems: 'center', fontSize: 42, fontWeight: 700, marginLeft: j === 3 ? 14 : 0 }}>
                      {p > 0 ? <span style={{ opacity: p, transform: `translateY(${(1 - p) * 14}px)` }}>{d}</span> : focused && j === 0 && Math.floor((t - focusAt) * 2.4) % 2 === 0 ? <div style={{ width: 3, height: 40, background: color.mint }} /> : null}
                    </div>
                  );
                })}
                {/* ⌘V, pressed. */}
                <div style={{ display: 'flex', gap: 8, marginLeft: 26, alignSelf: 'center', opacity: keys, transform: `translateY(${(1 - keys) * 10}px)` }}>
                  {['⌘', 'V'].map((c) => (
                    <div key={c} style={{ width: 58, height: 58, borderRadius: 12, background: 'rgba(255,255,255,0.1)', border: '2px solid rgba(255,255,255,0.2)', display: 'grid', placeItems: 'center', fontSize: 28, fontWeight: 700, transform: `translateY(${press(t, pasteAt - 0.04).press * 5}px)` }}>{c}</div>
                  ))}
                </div>
              </div>
              {button('Verify', filled(5), press(t, verifyAt).press)}
            </div>
          )}
          {/* 3: done. */}
          {done > 0 && (
            <div style={{ ...page(done, (1 - done) * 24), display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 14 }}>
              <div style={{ transform: `scale(${0.6 + 0.4 * springAt(sec(t), verifyAt + 0.1, 'bouncy')})` }}>
                <Icon name="checkmark.circle.fill" size={96} color={color.success} />
              </div>
              <div style={{ fontSize: 34, fontWeight: 700 }}>Order placed</div>
              <div style={{ fontSize: 22, fontWeight: 500, color: color.secondary }}>Arriving Thursday</div>
            </div>
          )}
        </div>
      )}
      {/* The bank's code, out of the notch, already copied. */}
      <FromNotch open={springAt(sec(t), k(1.35), 'bouncy') * (1 - ramp(sec(t), verifyAt + 0.4, verifyAt + 0.75))}>
        <CodeCard sender="Halo Bank · for Kettle & Co." dots={linear(sec(t), k(1.55), k(1.95))} copied={ramp(sec(t), k(2.05), k(2.2))} />
      </FromNotch>
      <Cursor
        t={t}
        path={[
          [k(0.35), pay[0] + 260, pay[1] + 190],
          [k(0.85), pay[0], pay[1]],
          [k(1.4), pay[0], pay[1]],
          [k(1.75), box[0], box[1]],
          [k(2.85), box[0], box[1]],
          [k(3.15), ver[0], ver[1]],
        ]}
        clicks={[payAt, focusAt, verifyAt]}
        show={ramp(sec(t), k(0.35), k(0.5)) * (1 - ramp(sec(t), verifyAt + 0.3, verifyAt + 0.5)) * close}
      />
    </>
  );
};

/** One feature: its neon word, and whatever happens at the notch. */
const Feature: React.FC<{ t: number; i: number }> = ({ t, i }) => {
  const at = F[i];
  const end = FEND(i);
  if (t < at - 0.1 || t > end + 0.3) return null;
  const name = FEATURE_NAMES[i];
  const close = 1 - ramp(sec(t), end - 0.35, end - 0.06);
  const word = <NeonWord t={t} at={at + 0.02} out={end - 0.25} word={name} icon={FEATURE_ICONS[i]} y={150} size={120} />;
  const spring = (from: number) => springAt(sec(t), from, 'bouncy');
  let body: React.ReactNode = null;

  if (name === 'Codes') {
    body = <CheckoutStory t={t} at={at} end={end} close={close} />;
  } else if (name === 'Calls') {
    const clickAt = at + 1.0;
    const [ax, ay] = onCard(250, 112);
    body = (
      <>
        <FromNotch open={spring(at + 0.08) * close}>
          <CallCard name="Mi amor ❤️" initials="MA" sim="SIM 1" answerPressed={press(t, clickAt).press} />
        </FromNotch>
        <Cursor t={t} path={[[at + 0.25, ax + 200, ay + 220], [at + 0.85, ax, ay]]} clicks={[clickAt]} show={ramp(sec(t), at + 0.25, at + 0.4) * close} />
        <Note text="Picked up on your phone" y={660} show={ramp(sec(t), clickAt + 0.12, clickAt + 0.35) * close} />
      </>
    );
  } else if (name === 'Replies') {
    const replyAt = at + 0.7;
    const sendAt = at + 1.85;
    const message = 'Sending it now!';
    const typed = message.slice(0, Math.round(linear(sec(t), at + 0.85, at + 1.5) * message.length));
    const [rx, ry] = onCard(86, 134);
    const [sx, sy] = onCard(356, 135);
    body = (
      <>
        <FromNotch open={spring(at + 0.08) * close}>
          {t > sendAt + 0.1 ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 64, justifyContent: 'center', fontFamily: font.body, fontSize: 16, fontWeight: 600, color: color.white }}>
              <Icon name="checkmark.circle.fill" size={22} color={color.mint} />
              Sent to Design team
            </div>
          ) : (
            <NotificationCard app="Messages" time="now" title="Design team" body="Can you share the new home screen?" reply={t > replyAt + 0.1 ? typed : undefined} caret={t > replyAt + 0.1} />
          )}
        </FromNotch>
        <Cursor
          t={t}
          path={[
            [at + 0.2, rx + 160, ry + 200],
            [at + 0.6, rx, ry],
            [at + 1.5, rx, ry],
            [at + 1.75, sx, sy],
          ]}
          clicks={[replyAt, sendAt]}
          show={ramp(sec(t), at + 0.2, at + 0.35) * (1 - ramp(sec(t), sendAt + 0.25, sendAt + 0.45))}
        />
      </>
    );
  } else if (name === 'Files') {
    // From the phone's home screen widget: Photos, and the photos fly into the notch.
    const tapAt = at + 0.45;
    const from = onPhone(110, 262);
    const to: [number, number] = [CX, LINE + 30];
    const cardOpen = spring(at + 1.35) * close;
    const progress = linear(sec(t), at + 1.45, at + 2.6);
    body = (
      <>
        <Tap t={t} at={tapAt} p={from} />
        {[0, 1, 2].map((j) => {
          const u = clamp((t - (tapAt + 0.2 + j * 0.13)) / 0.8);
          if (u <= 0 || u >= 1) return null;
          const e = u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2;
          const cx = (from[0] + to[0]) / 2;
          const cy = Math.min(from[1], to[1]) - 160;
          const x = (1 - e) * (1 - e) * from[0] + 2 * (1 - e) * e * cx + e * e * to[0];
          const y = (1 - e) * (1 - e) * from[1] + 2 * (1 - e) * e * cy + e * e * to[1];
          const size = 120 * (1 - 0.7 * e);
          return <div key={j} style={{ position: 'absolute', left: x - size / 2, top: y - size / 2, width: size, height: size, borderRadius: 10, border: '3px solid #fff', background: `linear-gradient(135deg, ${THUMBS[j]})`, boxShadow: '0 12px 30px rgba(0,0,0,0.45), 0 0 24px rgba(127,227,176,0.3)', transform: `rotate(${(1 - e) * (j - 1) * 10}deg)`, opacity: u > 0.9 ? (1 - u) / 0.1 : 1 }} />;
        })}
        <FromNotch open={cardOpen}>
          <ReceivingCard t={Math.max(0, t - at - 1.4)} doneAt={1.25} progress={progress} from="Pixel 9" summary={progress >= 1 ? '3 photos · 2.4 MB' : `${(2.4 * progress).toFixed(1)} of 2.4 MB`} thumbs={THUMBS} />
        </FromNotch>
      </>
    );
  } else if (name === 'Drop') {
    // From the Mac: photos dragged up into the notch's glow, let go, and they're on the phone.
    const drag = ramp(sec(t), at + 0.1, at + 0.75);
    const release = ramp(sec(t), at + 0.82, at + 1.02);
    const glow = ramp(sec(t), at + 0.1, at + 0.35) * (1 - ramp(sec(t), at + 0.95, at + 1.2));
    const x = CX + 420 * (1 - drag);
    const y = 960 - (960 - (LINE + 330)) * drag - release * 80;
    body = (
      <>
        {glow > 0 && (
          <div style={{ position: 'absolute', left: CX - 158 * S, top: LINE, width: 316, transformOrigin: '0 0', transform: `scale(${S})`, opacity: glow }}>
            <DragGlow approach={0.3 + 0.7 * drag} ready={drag > 0.97} summary="3 photos" phone="Pixel 9" />
          </div>
        )}
        {release < 1 && (
          <div style={{ position: 'absolute', left: x - 75 * 1.4, top: y - 56 * 1.4, transform: `scale(${1.4 * (1 - 0.85 * release)})`, opacity: ramp(sec(t), at, at + 0.2) * (1 - release) }}>
            <PhotoStack spread={1 - release} />
          </div>
        )}
        <Cursor t={t} kind="arrow" path={[[at, x + 60, y + 40], [at + 2, x + 60, y + 40]]} show={ramp(sec(t), at, at + 0.2) * (1 - ramp(sec(t), at + 0.85, at + 1.05))} />
        <FromNotch open={spring(at + 1.02) * close}>
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
        </FromNotch>
      </>
    );
  } else if (name === 'Panel') {
    // A click on the notch opens the phone's panel (it stays open into Music).
    const clickAt = at + 0.45;
    body = <Cursor t={t} kind="arrow" path={[[at + 0.05, CX + 260, LINE + 260], [at + 0.38, CX + 6, LINE + 22]]} clicks={[clickAt]} show={ramp(sec(t), at + 0.05, at + 0.2) * (1 - ramp(sec(t), at + 0.9, at + 1.1))} />;
  } else if (name === 'Music') {
    const clickAt = at + 1.05;
    const [px, py] = onPanel(BTN.pause[0], BTN.pause[1] - MUSIC_SCROLL);
    body = (
      <>
        <Cursor t={t} path={[[at + 0.3, px + 220, py + 120], [at + 0.85, px, py]]} clicks={[clickAt]} show={ramp(sec(t), at + 0.3, at + 0.45) * close} />
        <Note text="Paused on your phone" y={LINE + 80} x={1580} show={ramp(sec(t), clickAt + 0.1, clickAt + 0.35) * close} />
      </>
    );
  } else if (name === 'Menu bar') {
    // Settings: Menu bar. The panel goes back into the notch and comes out of the menu bar instead.
    const gearAt = at + 0.55;
    const pickAt = at + 1.2;
    const iconAt = at + 2.45;
    const [gx, gy] = onPanel(BTN.gear[0], BTN.gear[1]);
    const [mx, my] = onPanel(BTN.menuBar[0], BTN.menuBar[1]);
    body = (
      <Cursor
        t={t}
        kind="arrow"
        path={[
          [at + 0.05, gx + 120, gy + 200],
          [at + 0.45, gx, gy],
          [at + 0.95, gx, gy],
          [at + 1.12, mx, my],
          [at + 1.9, mx, my],
          [at + 2.35, LINK_ICON[0], LINK_ICON[1]],
        ]}
        clicks={[gearAt, pickAt, iconAt]}
        show={ramp(sec(t), at + 0.05, at + 0.2) * close}
      />
    );
    body = (
      <>
        {body}
        <Sweep t={t} at={at + 0.35} out={at + 1.75} y={600} left={40} right={1260} size={60} lines={[[['Already have']], [['a notch app?']]]} />
        <Sweep t={t} at={at + 1.95} out={end - 0.25} y={600} left={40} right={1260} size={60} lines={[[['Keep it.', true]], [['Lync moves to']], [['your menu bar.']]]} />
      </>
    );
  } else if (name === 'Off Wi‑Fi') {
    body = (
      <>
        <Tap t={t} at={at + 0.65} p={onPhone(109, 158)} />
        <FromNotch open={spring(at + 0.95) * (1 - ramp(sec(t), at + 1.6, at + 1.85))} width={300}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontFamily: font.body, fontSize: 13, fontWeight: 600, color: '#fff', justifyContent: 'center' }}>
            <Icon name="dot.radiowaves.left.and.right" size={15} color={color.mint} />
            Pixel 9 · over Bluetooth
          </div>
        </FromNotch>
        <FromNotch open={spring(at + 1.85) * close}>
          <CodeCard sender="Halo Bank" dots={linear(sec(t), at + 2.0, at + 2.3)} copied={ramp(sec(t), at + 2.3, at + 2.45)} />
        </FromNotch>
        <Note text="Still connected, over Bluetooth" y={640} show={ramp(sec(t), at + 1.0, at + 1.3) * close} />
      </>
    );
  } else if (name === 'Your colors') {
    const open = springAt(sec(t), at + 0.1, 'smooth') * close;
    body = (
      <>
        <div style={{ position: 'absolute', inset: 0, filter: `hue-rotate(${wallpaperHue(t, at, 0.28)}deg)` }}>
          <Panel open={open} layers={[['mac-panel-notch', 1]]} />
        </div>
        <Sweep t={t} at={at + 0.4} out={end - 0.25} y={560} left={1290} right={30} size={54} lines={[[['Lync takes on']], [['your wallpaper’s']], [['colors.', true]]]} />
      </>
    );
  } else if (name === 'Android app') {
    body = (
      <>
        <Tap t={t} at={at + 0.65} p={onPhone(206, 839)} />
        <Tap t={t} at={at + 1.55} p={onPhone(330, 839)} />
      </>
    );
  }
  return (
    <>
      {word}
      {body}
    </>
  );
};

/** The panel across Panel, Music and Menu bar: opening, scrolling to music, pausing, settings, the menu bar. */
const PanelStory: React.FC<{ t: number }> = ({ t }) => {
  const [panelAt, musicAt, menuAt] = [F[5], F[6], F[7]];
  if (t < panelAt || t > FEND(7) + 0.3) return null;
  const open = springAt(sec(t), panelAt + 0.5, 'smooth') * (1 - ramp(sec(t), menuAt + 1.5, menuAt + 1.8));
  const scroll = MUSIC_SCROLL * ramp(sec(t), musicAt + 0.05, musicAt + 0.5) * (1 - ramp(sec(t), menuAt, menuAt + 0.35));
  const paused = ramp(sec(t), musicAt + 1.1, musicAt + 1.25);
  const turn = ramp(sec(t), menuAt + 0.6, menuAt + 0.9);
  const chosen = ramp(sec(t), menuAt + 1.25, menuAt + 1.35);
  const fromMenu = springAt(sec(t), menuAt + 2.55, 'smooth') * (1 - ramp(sec(t), FEND(7) - 0.35, FEND(7) - 0.05));
  return (
    <>
      <Panel
        open={open}
        scroll={scroll}
        layers={[
          ['mac-panel-notch', 1, -turn * 120],
          ['mac-panel-paused', paused * (1 - turn), -turn * 120],
          ['mac-settings-notch', turn, (1 - turn) * PANEL_W],
          ['mac-settings-menubar', turn * chosen, (1 - turn) * PANEL_W],
        ]}
      />
      <Panel open={fromMenu} layers={[['mac-panel-menubar', 1]]} fromNotch={false} left={LINK_ICON[0] - PANEL_W * P * 0.7} top={LINE + notch.height * S + 8} />
    </>
  );
};

const NotchScene: React.FC<{ t: number }> = ({ t }) => {
  if (t < NOTCH_SCENE - 0.1 || t > PROMISES + 0.3) return null;
  const draw = ramp(sec(t), NOTCH_SCENE + b(0.5), NOTCH_SCENE + b(0.5) + 0.45);
  const notchIn = springAt(sec(t), NOTCH_SCENE + b(1.5), 'snappy');
  const fade = 1 - ramp(sec(t), PROMISES - 0.3, PROMISES);
  const menuBar = ramp(sec(t), F[7] + 1.8, F[7] + 2.1) * (1 - ramp(sec(t), FEND(7) - 0.3, FEND(7)));
  return (
    <>
      <ScreenEdge draw={draw} notchIn={notchIn} fade={fade} menuBar={menuBar} />
      <Sweep t={t} at={NOTCH_SCENE} out={FEATURES - 0.25} y={150} size={84} stagger={0.016} lines={[[['Everything from your phone,']]]} />
      <Sweep t={t} at={NOTCH_SCENE + b(1.8)} out={FEATURES - 0.25} y={LINE + 300} size={120} display stagger={0.016} lines={[[['in your '], ['notch.', true]]]} />
      <PanelStory t={t} />
      {FEATURE_NAMES.map((_, i) => (
        <Feature key={i} t={t} i={i} />
      ))}
      <SidePhone t={t} />
    </>
  );
};

/** The promises, big and plain. */
const Promises: React.FC<{ t: number }> = ({ t }) => (
  <>
    <Sweep t={t} at={PROMISES} out={DEVICES - 0.2} y={H / 2 - 150} size={120} lines={[[['No account.']]]} />
    <Sweep t={t} at={PROMISES + b(2)} out={DEVICES - 0.2} y={H / 2} size={120} lines={[[['No cloud.']]]} />
    <Sweep t={t} at={PROMISES + b(4)} out={DEVICES - 0.2} y={H / 2 + 150} size={120} lines={[[['No tracking.', true]]]} />
  </>
);

/** The two devices together: the Mac with a delivery in its notch, the phone in front showing Lync's activity. */
const Devices: React.FC<{ t: number }> = ({ t }) => {
  if (t < DEVICES - 0.1 || t > END + 0.4) return null;
  const k = t - DEVICES;
  const mac = springAt(sec(t), DEVICES, 'smooth');
  const phone = springAt(sec(t), DEVICES + 0.22, 'smooth');
  const card = springAt(sec(t), DEVICES + 0.75, 'bouncy');
  const out = ramp(sec(t), END - 0.35, END + 0.15);
  const push = 1 + 0.035 * linear(sec(t), DEVICES, END);
  const macS = 0.44;
  const macW = (1470 + 36) * 1.12;
  const FLOOR_Y = 300 + (956 + 18 + 22 + 6) * macS;
  return (
    <div style={{ position: 'absolute', inset: 0, opacity: 1 - out, transform: `scale(${push * (1 - 0.08 * out)})`, filter: out > 0 ? `blur(${out * 12}px)` : undefined }}>
      <Sweep t={t} at={DEVICES + 0.05} out={END - 0.3} y={130} size={84} lines={[[['Just your '], ['two devices.', true]]]} />
      {/* A soft backlight behind the Mac, and a dark glossy floor the devices stand on. */}
      <div style={{ position: 'absolute', left: CX - 90 - 620, top: 180, width: 1240, height: 700, borderRadius: '50%', background: 'radial-gradient(closest-side, rgba(127,227,176,0.22), rgba(47,181,126,0.08) 55%, rgba(47,181,126,0))', opacity: mac }} />
      <div style={{ position: 'absolute', left: 0, right: 0, top: FLOOR_Y, bottom: 0, background: 'linear-gradient(180deg, rgba(10,30,22,0.9), rgba(4,9,7,0.95) 60%)', opacity: mac }} />
      <div style={{ position: 'absolute', left: CX - 700, width: 1400, top: FLOOR_Y - 1, height: 2, background: 'linear-gradient(90deg, rgba(191,255,224,0), rgba(191,255,224,0.35), rgba(191,255,224,0))', opacity: mac }} />
      {/* Reflections: the Mac and the phone mirrored in the floor, fading away. */}
      <div style={{ position: 'absolute', inset: 0, opacity: 0.16 * mac, WebkitMaskImage: `linear-gradient(180deg, transparent ${FLOOR_Y}px, #000 ${FLOOR_Y + 2}px, transparent ${FLOOR_Y + 230}px)` }}>
        <div style={{ position: 'absolute', left: CX - (macW * macS) / 2 - 90, top: 2 * FLOOR_Y - 300, transformOrigin: '0 0', transform: `scale(${macS}, ${-macS})` }}>
          <MacBook linked showBase={false}>
            <MacWallpaper drift={k * 0.3} />
          </MacBook>
        </div>
      </div>
      <div style={{ position: 'absolute', left: CX - (macW * macS) / 2 - 90, top: 300, transformOrigin: '0 0', transform: `translateY(${(1 - mac) * 220}px) scale(${macS})`, opacity: clamp(mac * 2) }}>
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
      <div style={{ position: 'absolute', left: CX + 250, top: 880 - 939, transformOrigin: '50% 100%', transform: `translate(${(1 - phone) * 360}px, ${(1 - phone) * 60}px) rotate(${-4 * phone}deg) scale(0.5)`, opacity: clamp(phone * 2), filter: 'drop-shadow(-30px 40px 50px rgba(0,0,0,0.6))' }}>
        <Phone>
          <Img src={staticFile('screens/phone-activity.png')} style={{ width: 412, height: 915, display: 'block' }} />
        </Phone>
      </div>
    </div>
  );
};

const EndCard: React.FC<{ t: number }> = ({ t }) => {
  if (t < END - 0.45) return null;
  const fade = (from: number) => ramp(sec(t), END + from, END + from + 0.4);
  const pill: React.CSSProperties = { height: 60, padding: '0 28px', borderRadius: 99, display: 'flex', alignItems: 'center', fontFamily: font.body, fontSize: 22, fontWeight: 600 };
  return (
    <>
      <LockupReveal t={t} at={END} y={H / 2 - 150} />
      <div style={{ position: 'absolute', left: 0, right: 0, top: H / 2 - 20, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 18 }}>
        <div style={{ fontFamily: font.body, fontSize: 38, fontWeight: 600, color: color.white, opacity: fade(0.6), transform: `translateY(${(1 - fade(0.6)) * 14}px)` }}>
          Everything from your phone, <span style={{ color: color.mint }}>in your notch.</span>
        </div>
        <div style={{ fontFamily: font.body, fontSize: 28, fontWeight: 500, color: 'rgba(255,255,255,0.72)', opacity: fade(1.0) }}>Free for Android and Mac</div>
        <div style={{ display: 'flex', gap: 16, marginTop: 10, opacity: fade(1.3), transform: `translateY(${(1 - fade(1.3)) * 14}px)` }}>
          <div style={{ ...pill, background: color.pale, color: color.onPale }}>Get it on Google Play</div>
          <div style={{ ...pill, background: 'rgba(255,255,255,0.13)', color: '#fff' }}>Download for Mac</div>
        </div>
        <div style={{ fontFamily: font.body, fontSize: 20, fontWeight: 500, color: 'rgba(255,255,255,0.5)', opacity: fade(1.5) }}>santhoshsunkara24.github.io/Lync</div>
      </div>
      <Flash t={t} at={END} strength={0.5} />
    </>
  );
};

// ---------------------------------------------------------------------------------------------
// Sound: the same music and effects as the short.

const SOUNDS: [number, string, number][] = [
  [0, 'wide-music-otp', 1],
  [0, 'swish', 0.3],
  [b(1.5) - 0.15, 'swish', 0.28],
  [b(4) - 0.15, 'whoosh', 0.42],
  [b(8) - 0.15, 'whoosh', 0.38],
  [LOGO - 0.2, 'whoosh', 0.35],
  [LOGO - 0.02, 'shimmer', 0.55],
  [NOTCH_SCENE - 0.15, 'whoosh', 0.38],
  [NOTCH_SCENE + b(1.5), 'pop', 0.45],
  ...[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((i): [number, string, number] => [F[i] - 0.15, 'swish', 0.22]),
  // Codes: pay, the code arrives and is copied, pasted, the order goes through
  [F[0] + 0.95, 'click', 0.55],
  [F[0] + 1.4, 'notify', 0.45],
  [F[0] + 1.85, 'click', 0.45],
  [F[0] + 2.7, 'paste', 0.55],
  [F[0] + 3.25, 'click', 0.55],
  [F[0] + 3.35, 'pop', 0.45],
  // Calls
  [F[1] + 0.08, 'notify', 0.45],
  [F[1] + 1.0, 'click', 0.55],
  // Replies
  [F[2] + 0.08, 'notify', 0.45],
  [F[2] + 0.7, 'click', 0.55],
  [F[2] + 0.85, 'typing', 0.45],
  [F[2] + 1.85, 'click', 0.55],
  // Files: the tap on the widget, the photos landing
  [F[3] + 0.45, 'tap', 0.5],
  [F[3] + 1.3, 'transfer', 0.7],
  // Drop
  [F[4] + 0.82, 'pop', 0.45],
  [F[4] + 1.02, 'notify', 0.42],
  // Panel, Music, Menu bar
  [F[5] + 0.45, 'click', 0.55],
  [F[6] + 1.05, 'click', 0.55],
  [F[7] + 0.55, 'click', 0.5],
  [F[7] + 1.2, 'click', 0.5],
  [F[7] + 2.45, 'click', 0.5],
  // The Android app's tabs
  [F[8] + 0.65, 'tap', 0.45],
  [F[8] + 1.55, 'tap', 0.45],
  // Off Wi‑Fi: the tile, then the Mac still hearing from the phone
  // Your colors: a soft pop as each wallpaper lands
  ...COLOR_CHANGES.map((c): [number, string, number] => [F[9] + c, 'pop', 0.3]),
  // Off Wi‑Fi: the tile, then the Mac still hearing from the phone
  [F[10] + 0.65, 'switch', 0.5],
  [F[10] + 0.95, 'connect', 0.42],
  [F[10] + 1.85, 'notify', 0.42],
  [PROMISES - 0.15, 'whoosh', 0.42],
  [PROMISES + b(2) - 0.02, 'pop', 0.38],
  [PROMISES + b(4) - 0.02, 'pop', 0.38],
  [DEVICES - 0.15, 'whoosh', 0.4],
  [DEVICES + 0.75, 'connect', 0.45],
  [END - 0.2, 'whoosh', 0.3],
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

export const Wide: React.FC<{ bg?: BgKind }> = ({ bg = 'aurora' }) => {
  const t = useCurrentFrame() / 60;
  return (
    <div style={{ position: 'absolute', inset: 0, width: W, height: H, overflow: 'hidden', background: '#040907' }}>
      <Sounds />
      <WideBackground t={t} kind={bg} />
      {t < LOGO + 0.3 && <Hook t={t} />}
      <Logo t={t} />
      <NotchScene t={t} />
      {t > PROMISES - 0.1 && t < DEVICES + 0.3 && <Promises t={t} />}
      <Devices t={t} />
      <EndCard t={t} />
    </div>
  );
};
