import React from 'react';
import { AbsoluteFill, Audio, Img, Sequence, staticFile, useCurrentFrame } from 'remotion';
import { Caption } from '../components/Caption';
import { CallCard, CodeCard, NotificationCard } from '../components/Cards';
import { MacBook, macScreen, Phone, phoneScreen } from '../components/Devices';
import { MacDock, MacMenuBar, MacWallpaper, SignInWindow } from '../components/Desktop';
import { DragGlow } from '../components/DragGlow';
import { Icon } from '../components/Icon';
import { ReceivingCard } from '../components/Liftoff';
import { NotchCard } from '../components/NotchCard';
import { PromisePill } from '../components/Promises';
import { Aurora } from '../components/Aurora';
import { QuickSettings } from '../components/QuickSettings';
import { PhoneHome, PhoneLock } from '../components/PhoneHome';
import { Pointer, Touch } from '../components/Pointer';
import { cardWidth, color, font } from '../theme';
import { Camera, cameraAt, linear, mix, ramp, sec, Shot, springAt } from './motion';

/**
 * The launch film as one continuous take. The phone and the MacBook sit side by side in one
 * world, with the opening question in the dark above them; a single camera glides between them
 * and in and out of the notch. Every moment closes what it opened, so nothing ever cuts.
 * All times are seconds into the film.
 */

/** The photos' flight from the phone to the Mac is inserted here, holding the rest of the film. */
const INSERT_AT = 50.4;
const INSERT = 4.4;
export const FILM_SECONDS = 81 + INSERT;
/** The film plays this much faster than it is timed here (all times below stay in the slower timing). */
export const SPEED = 1.15;
/** How long the finished film runs. */
export const FILM_LENGTH = FILM_SECONDS / SPEED;

// ---------------------------------------------------------------------------------------------
// The world: where everything is.

type Place = { cx: number; top: number; s: number };
const MAC_W = (macScreen.width + 36) * 1.12;
const MAC_SCREEN = { x: (MAC_W - (macScreen.width + 36)) / 2 + 21, y: 21 };
const MAC: Place = { cx: 1180, top: 250, s: 0.5 };
const PHONE: Place = { cx: 380, top: 300, s: 0.5 };
/** The dark sky above the devices, where the opening question and the end card sit. */
const SKY = { x: 960, y: -560 };

/** A point on the Mac's screen (points) or the phone's (dp), as a point in the world. */
const onMac = (x: number, y: number): [number, number] => [MAC.cx - (MAC_W * MAC.s) / 2 + (MAC_SCREEN.x + x) * MAC.s, MAC.top + (MAC_SCREEN.y + y) * MAC.s];
const onPhone = (x: number, y: number): [number, number] => [PHONE.cx - (436 * PHONE.s) / 2 + (12 + x) * PHONE.s, PHONE.top + (12 + y) * PHONE.s];

/** Camera framings, so the path below reads as a shot list. */
const at = (p: [number, number], z: number, extra: Partial<Shot> = {}): Shot => ({ fx: p[0], fy: p[1], z, ...extra });
const notchAt = (dy: number, z: number, dx = 0) => at(onMac(macScreen.width / 2 + dx, dy), z);
const NOTCH = notchAt(60, 3.4);
const PHONE_CLOSE = at(onPhone(phoneScreen.width / 2, phoneScreen.height / 2), 1.9);
const MAC_WHOLE = at(onMac(macScreen.width / 2, macScreen.height / 2 + 30), 1.95);
const BOTH = at([(PHONE.cx + MAC.cx) / 2, 520], 1.12);

// The real Mac panel (rendered by the app's snapshot tool at 3×), in points on the Mac's screen.
const PANEL_W = 380;
const PANEL_H: Record<string, number> = {
  'mac-panel-notch': 2936 / 3,
  'mac-panel-paused': 2936 / 3,
  'mac-settings-notch': 2621 / 3,
  'mac-settings-menubar': 2621 / 3,
  'mac-panel-menubar': 2840 / 3,
};
const PANEL_VISIBLE = 640;
const PANEL_X = (macScreen.width - PANEL_W) / 2;
/** The panel's buttons on its page, measured from the renders. */
const BTN = { pause: [284, 639], gear: [345, 62], menuBar: [280, 148] };
/** How far the panel's page scrolls to bring the music card into view. */
const MUSIC_SCROLL = 260;
const LINK_ICON_X = macScreen.width - 242;

/** The one camera path through the whole film. */
const PATH: [number, Shot][] = [
  [0, at([SKY.x, SKY.y], 1)],
  [3.2, at([SKY.x, SKY.y + 20], 1.04)],
  [4.8, PHONE_CLOSE],
  [6.8, { ...PHONE_CLOSE, z: 1.98 }],
  [8.4, MAC_WHOLE],
  [10.0, { ...MAC_WHOLE, z: 2.02 }],
  [11.0, BOTH],
  [13.0, { ...BOTH, z: 1.08 }],
  [14.0, notchAt(150, 2.9)],
  [15.8, notchAt(160, 3.05)],
  [16.4, NOTCH],
  [18.2, notchAt(64, 3.3)],
  [19.1, at(onMac(macScreen.width / 2 - 40, 450), 2.6)],
  [21.5, at(onMac(macScreen.width / 2 - 30, 455), 2.65)],
  [22.3, notchAt(70, 3.6, 30)],
  [26.6, notchAt(66, 3.5, 30)],
  [27.3, notchAt(80, 3.3, -50)],
  [31.6, notchAt(80, 3.4, 50)],
  [32.6, at(onMac(macScreen.width / 2, 420), 2.3, { rx: 4 })],
  [35.0, notchAt(150, 3.2)],
  [37.6, notchAt(140, 3.3)],
  [39.0, notchAt(120, 3.0)],
  [40.4, notchAt(260, 3.0)],
  [42.6, at(onMac(PANEL_X + BTN.pause[0] - 70, BTN.pause[1] - MUSIC_SCROLL), 4.6)],
  [45.8, at(onMac(PANEL_X + BTN.pause[0] - 50, BTN.pause[1] - MUSIC_SCROLL), 4.5)],
  [46.6, notchAt(200, 2.6)],
  [47.6, PHONE_CLOSE],
  [58.6, { ...PHONE_CLOSE, z: 2.02 }],
  [59.6, NOTCH],
  [62.0, notchAt(62, 3.5)],
  [62.6, notchAt(150, 3.0)],
  [63.4, notchAt(300, 3.2)],
  [66.4, notchAt(290, 3.3)],
  [67.4, at(onMac(LINK_ICON_X - 120, 320), 2.6)],
  [69.0, at(onMac(LINK_ICON_X - 130, 320), 2.65)],
  [69.9, BOTH],
  [72.6, { ...BOTH, z: 1.05 }],
  [73.8, notchAt(190, 1.8)],
  [76.6, notchAt(190, 1.9)],
  [78.3, at([SKY.x, SKY.y], 1)],
  [81, at([SKY.x, SKY.y + 10], 1.03)],
];

// ---------------------------------------------------------------------------------------------
// Small helpers.

/** Something that opens at `a` and closes at `b`: 0 → 1 → 0, eased both ways. */
const life = (f: number, a: number, b: number, inFor = 0.4, outFor = 0.4) => ramp(f, a, a + inFor) * (1 - ramp(f, b - outFor, b));

/** A pointer path: a gentle curve with a small overshoot and settle. */
const glide = (f: number, from: number[], to: number[], t0: number, t1: number): [number, number] => {
  const t = ramp(f, t0, t1);
  const settle = Math.sin(Math.min(1, linear(f, t1 - 0.05, t1 + 0.3)) * Math.PI) * 4;
  const arc = Math.sin(t * Math.PI) * 40;
  return [mix(from[0], to[0], t) + settle, mix(from[1], to[1], t) - arc];
};

/** A click at `t`: press 0…1…0 and a ring running out. */
const click = (f: number, t: number) => ({ press: linear(f, t, t + 0.08) * (1 - linear(f, t + 0.12, t + 0.24)), ring: linear(f, t + 0.05, t + 0.6) });

const UnderNotch: React.FC<{ children: React.ReactNode; width?: number }> = ({ children, width = cardWidth }) => (
  <div style={{ position: 'absolute', left: (macScreen.width - width) / 2, top: 0, width, display: 'flex', justifyContent: 'center' }}>{children}</div>
);

const PointerAt: React.FC<{ p: number[]; kind?: 'arrow' | 'hand'; press?: number; ring?: number; opacity: number }> = ({ p, kind, press, ring, opacity }) =>
  opacity > 0 ? (
    <div style={{ position: 'absolute', left: p[0], top: p[1], opacity }}>
      <Pointer kind={kind} size={46} press={press} ring={ring} />
    </div>
  ) : null;

/** A caption in the frame (not the world): types on, holds, fades out. */
const Line: React.FC<{ f: number; text: string; from: number; to: number; out: number; size?: number }> = ({ f, text, from, to, out, size = 58 }) => {
  const p = linear(f, from, to);
  const fade = 1 - ramp(f, out, out + 0.4);
  if (p <= 0 || fade <= 0) return null;
  return (
    <div style={{ position: 'absolute', left: 0, right: 0, bottom: 92, opacity: fade }}>
      <Caption text={text} progress={p} size={size} />
    </div>
  );
};

/** The real panel renders stacked (to switch a button's state), scrolling, growing out of the notch. */
const RealPanel: React.FC<{ layers: [string, number, number?][]; scroll?: number; open: number; x?: number; top?: number; fromNotch?: boolean }> = ({
  layers,
  scroll = 0,
  open,
  x = PANEL_X,
  top = 0,
  fromNotch = true,
}) =>
  open <= 0.001 ? null : (
    <div
      style={{
        position: 'absolute',
        left: x,
        top,
        width: PANEL_W,
        height: PANEL_VISIBLE,
        transformOrigin: fromNotch ? '50% 0%' : '70% 0%',
        transform: `scale(${0.6 + 0.4 * open})`,
        opacity: Math.min(1, open * 2),
        borderRadius: fromNotch ? '0 0 32px 32px' : 32,
        overflow: 'hidden',
        boxShadow: '0 30px 80px rgba(0,0,0,0.55)',
        background: color.ink,
      }}
    >
      {layers.map(([name, opacity, dx = 0]) =>
        opacity > 0 ? (
          <Img key={name} src={staticFile(`screens/${name}.png`)} style={{ position: 'absolute', left: 0, top: 0, width: PANEL_W, height: PANEL_H[name], opacity, transform: `translate(${dx}px, ${-scroll}px)` }} />
        ) : null,
      )}
      {fromNotch && <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 34, background: 'linear-gradient(180deg, #000 80%, transparent)' }} />}
    </div>
  );

const PhotoStack: React.FC<{ spread?: number }> = ({ spread = 1 }) => (
  <div style={{ position: 'relative', width: 150, height: 112 }}>
    {(
      [
        ['#d06a6f', '#4a2d86', -10, -14],
        ['#15245e', '#2fb57e', 8, 12],
        ['#f2c14e', '#e0386a', 0, 0],
      ] as [string, string, number, number][]
    ).map(([a, b, r, x], i) => (
      <div key={i} style={{ position: 'absolute', inset: 0, borderRadius: 10, border: '4px solid #fff', background: `linear-gradient(135deg, ${a}, ${b})`, transform: `rotate(${r * spread}deg) translateX(${x * spread}px)`, boxShadow: '0 10px 24px rgba(0,0,0,0.4)' }} />
    ))}
  </div>
);

const Halo: React.FC<{ x: number; y: number; r: number; strength: number }> = ({ x, y, r, strength }) =>
  strength > 0 ? <div style={{ position: 'absolute', left: x - r, top: y - r, width: r * 2, height: r * 2, borderRadius: '50%', background: `radial-gradient(closest-side, rgba(47,181,126,${0.35 * strength}), transparent)` }} /> : null;

// ---------------------------------------------------------------------------------------------
// What's on the Mac's screen at any moment.

const MacScreen: React.FC<{ f: number; ins: number }> = ({ f, ins }) => {
  const t = f / 60;
  const wifi = !(t > 57.8 && t < 62.4);
  // Reveal: the screen dims, the glow blooms, the logo snaps in, then it all goes again.
  const reveal = life(f, 13.4, 16.0, 1.0, 0.6);
  const snap = springAt(f, 14.0, 'bouncy') * (1 - ramp(f, 15.4, 15.9));
  const word = ramp(f, 14.5, 15.0) * (1 - ramp(f, 15.4, 15.9));

  // Codes: card drops in slow motion, the pointer pastes into a sign-in window.
  const codeOpen = springAt(f, 16.2, 'bouncy', 2.5) * (1 - ramp(f, 21.3, 21.7));
  const signIn = life(f, 16.0, 21.9, 0.4, 0.5);
  const field = [macScreen.width / 2 - 60, 470];
  const codePtr = glide(f, [980, 640], field, 18.4, 19.2);
  const codeClick = click(f, 19.35);
  const typed = '482913'.slice(0, Math.floor(linear(f, 19.5, 19.9) * 6.99));

  // Call: Answer on phone, then the slim "on phone" row.
  const callOpen = springAt(f, 22.3, 'bouncy', 1.6) * (1 - ramp(f, 26.4, 26.9));
  // Where the hand's fingertip meets the button (measured in the studio; the hotspot is 17.5, 2.8 into the pointer).
  const answer = [macScreen.width / 2 + 33.5, 109];
  const callPtr = glide(f, [answer[0] + 260, answer[1] + 280], answer, 23.6, 24.6);
  const callClick = click(f, 24.8);
  const answered = t > 24.95;

  // Message: Reply, type, Send.
  const msgOpen = springAt(f, 27.2, 'bouncy', 1.6) * (1 - ramp(f, 31.0, 31.4));
  const replyBtn = [macScreen.width / 2 - 130, 131];
  const send = [macScreen.width / 2 + 140.5, 133];
  const msgPtr = t < 30.2 ? glide(f, [replyBtn[0] + 300, replyBtn[1] + 240], replyBtn, 28.0, 28.8) : glide(f, replyBtn, send, 30.2, 30.7);
  const r1 = click(f, 28.95);
  const r2 = click(f, 30.85);
  const replying = t > 29.1;
  const reply = 'Sending it in five'.slice(0, Math.floor(linear(f, 29.3, 30.1) * 18.99));

  // Files: dragged up to the notch, released in slow motion.
  const drag = ramp(f, 32.8, 35.0);
  const dragFrom = [macScreen.width / 2 + 120, 640];
  const dragTo = [macScreen.width / 2 - 10, 190];
  const dragPos = [mix(dragFrom[0], dragTo[0], drag), mix(dragFrom[1], dragTo[1], drag)];
  const approach = Math.min(1, Math.max(0, (drag - 0.2) / 0.75));
  const release = ramp(f, 35.4, 36.6);
  const glowOn = life(f, 32.6, 37.0, 0.4, 0.6);
  const sentCard = springAt(f, 36.6, 'bouncy') * (1 - ramp(f, 37.6, 38.0));

  // Panel, then music in it.
  const notchPt = [macScreen.width / 2 + 30, 12];
  const openPtr = glide(f, [notchPt[0] + 300, notchPt[1] + 360], notchPt, 38.4, 39.3);
  const openClick = click(f, 39.45);
  const panelOpen = springAt(f, 39.55, 'smooth', 1.3) * (1 - ramp(f, 46.0, 46.5));
  const scroll = ramp(f, 40.8, 42.4) * MUSIC_SCROLL;
  const pause = [PANEL_X + BTN.pause[0], BTN.pause[1] - MUSIC_SCROLL];
  const pausePtr = glide(f, [pause[0] + 200, pause[1] + 220], [pause[0] - 6, pause[1] - 8], 42.7, 43.5);
  const pauseClick = click(f, 43.7);
  const paused = ramp(f, 43.8, 44.0);

  // Off Wi‑Fi: the app's Bluetooth note, then a code.
  const btNote = springAt(f, 59.0, 'bouncy') * (1 - ramp(f, 59.9, 60.2));
  const btCode = springAt(f, 60.3, 'bouncy') * (1 - ramp(f, 61.6, 62.0));

  // Notch or menu bar, in Settings.
  const modesPtrA = glide(f, [notchPt[0] + 260, notchPt[1] + 300], notchPt, 62.2, 62.9);
  const modesOpen = springAt(f, 63.05, 'smooth', 1.3) * (1 - ramp(f, 66.5, 66.85));
  const gear = [PANEL_X + BTN.gear[0] - 6, BTN.gear[1] - 6];
  const menuBar = [PANEL_X + BTN.menuBar[0] - 6, BTN.menuBar[1] - 6];
  const modesPtr = t < 63.4 ? modesPtrA : t < 64.9 ? glide(f, notchPt, gear, 63.5, 64.2) : glide(f, gear, menuBar, 64.9, 65.5);
  const m0 = click(f, 63.0);
  const m1 = click(f, 64.35);
  const m2 = click(f, 65.65);
  const turn = ramp(f, 64.45, 64.85);
  const chosen = ramp(f, 65.75, 65.95);
  const fromMenu = springAt(f, 67.0, 'smooth', 1.2) * (1 - ramp(f, 68.8, 69.2));

  const wide = life(f, 73.2, 77.0, 0.8, 0.6);
  // The photos from the phone arriving in the notch (during the inserted flight).
  // Liftoff runs from the card opening; the files land at 2.85 s.
  const arrive = ins < 0 ? 0 : springAt(sec(ins), 1.5, 'bouncy') * (1 - ramp(sec(ins), 3.95, 4.35));
  const received = ins < 0 ? 0 : ramp(sec(ins), 1.7, 2.85);

  return (
    <>
      <MacWallpaper drift={linear(f, 0, sec(FILM_SECONDS)) * 2} />
      <MacMenuBar wifi={wifi} />
      <MacDock />

      {reveal > 0 && (
        <>
          <div style={{ position: 'absolute', inset: 0, background: `rgba(0,0,0,${0.55 * reveal})` }} />
          <UnderNotch width={600}>
            <div style={{ opacity: reveal }}>
              <DragGlow approach={0.6 + 0.4 * reveal} lightOnly />
            </div>
          </UnderNotch>
          <div style={{ position: 'absolute', left: 0, right: 0, top: 150, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 18 }}>
            <Img src={staticFile('lync-icon.svg')} style={{ width: 120, height: 120, transform: `scale(${snap}) rotate(${(1 - snap) * -20}deg)`, opacity: Math.min(1, snap * 1.5) }} />
            <div style={{ fontFamily: font.display, fontSize: 96, color: color.white, opacity: word, transform: `translateY(${(1 - word) * 16}px)`, filter: `blur(${(1 - word) * 8}px)` }}>Lync</div>
          </div>
        </>
      )}

      {signIn > 0 && (
        <div style={{ position: 'absolute', left: (macScreen.width - 760) / 2, top: 230, opacity: signIn, transform: `translateY(${(1 - signIn) * 20}px)` }}>
          <SignInWindow digits={typed} />
        </div>
      )}
      {codeOpen > 0.001 && (
        <UnderNotch>
          <NotchCard open={codeOpen}>
            <CodeCard sender="Halo Bank" dots={ramp(f, 16.6, 17.3)} copied={ramp(f, 17.3, 17.6)} />
          </NotchCard>
        </UnderNotch>
      )}
      <PointerAt p={codePtr} press={codeClick.press} ring={codeClick.ring} opacity={life(f, 18.2, 20.6, 0.2, 0.4)} />
      {t > 19.3 && t < 19.95 && <div style={{ position: 'absolute', left: codePtr[0] + 40, top: codePtr[1] + 34, padding: '6px 10px', borderRadius: 8, background: 'rgba(0,0,0,0.75)', fontFamily: font.body, fontSize: 16, fontWeight: 600, color: '#fff' }}>⌘V</div>}

      {callOpen > 0.001 && (
        <UnderNotch>
          <NotchCard open={callOpen}>
            {answered ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontFamily: font.body }}>
                <div style={{ width: 24, height: 24, borderRadius: 24, background: 'rgba(92,214,128,0.22)', display: 'grid', placeItems: 'center' }}>
                  <Icon name="phone.fill" size={12} color={color.success} />
                </div>
                <span style={{ fontSize: 13, fontWeight: 600, color: '#fff' }}>Mi amor ❤️</span>
                <span style={{ fontSize: 12, fontWeight: 500, color: 'rgba(255,255,255,0.6)' }}>On phone · 0:0{Math.min(9, Math.floor((f - sec(24.95)) / 60))}</span>
                <div style={{ flex: 1 }} />
                <div style={{ width: 40, height: 24, borderRadius: 99, background: color.danger, display: 'grid', placeItems: 'center' }}>
                  <Icon name="phone.down.fill" size={13} color={color.ink} />
                </div>
              </div>
            ) : (
              <CallCard name="Mi amor ❤️" initials="MA" sim="SIM 1" answerPressed={callClick.press} />
            )}
          </NotchCard>
        </UnderNotch>
      )}
      <PointerAt p={callPtr} kind="hand" press={callClick.press} ring={callClick.ring} opacity={life(f, 23.4, 26.2, 0.2, 0.4)} />

      {msgOpen > 0.001 && (
        <UnderNotch>
          <NotchCard open={msgOpen}>
            <NotificationCard app="Messages" time="now" title="Design team" body="Can you share the new home screen?" reply={replying ? reply : undefined} caret={replying && f % 60 < 34} />
          </NotchCard>
        </UnderNotch>
      )}
      {life(f, 31.0, 31.9, 0.2, 0.3) > 0 && (
        <UnderNotch>
          <div style={{ marginTop: 60, padding: '8px 14px', borderRadius: 99, background: color.pale, color: color.onPale, fontFamily: font.body, fontSize: 13, fontWeight: 600, opacity: life(f, 31.0, 31.9, 0.2, 0.3) }}>Reply sent</div>
        </UnderNotch>
      )}
      <PointerAt p={msgPtr} kind="hand" press={Math.max(r1.press, r2.press)} ring={t < 30 ? r1.ring : r2.ring} opacity={life(f, 27.8, 31.2, 0.2, 0.3)} />

      {glowOn > 0 && (
        <UnderNotch width={500}>
          <div style={{ opacity: glowOn }}>
            <DragGlow approach={release > 0 ? 1 : approach} ready={drag > 0.97} summary="3 photos" breathe={Math.sin(f / 18) * 2 * approach} />
          </div>
        </UnderNotch>
      )}
      {life(f, 32.6, 36.6, 0.3, 0.01) > 0 && (
        <div style={{ position: 'absolute', left: dragPos[0] - 75, top: dragPos[1] - 56 - release * 80, transform: `scale(${1 - 0.85 * release})`, opacity: ramp(f, 32.6, 32.9) * (1 - release) }}>
          <PhotoStack spread={1 - release} />
        </div>
      )}
      <PointerAt p={[dragPos[0] + 30, dragPos[1] + 20]} opacity={life(f, 32.6, 35.9, 0.3, 0.4)} />
      {sentCard > 0.001 && (
        <UnderNotch>
          <NotchCard open={sentCard}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, fontFamily: font.body }}>
              <div style={{ position: 'relative', width: 40, height: 32 }}>
                {['#4a2d86', '#2fb57e', '#e0386a'].map((c, i) => (
                  <div key={c} style={{ position: 'absolute', inset: 0, borderRadius: 6, border: '2px solid #fff', background: c, transform: `rotate(${(i - 1) * 8}deg) translateX(${i * 3}px)` }} />
                ))}
              </div>
              <div>
                <div style={{ fontSize: 15, fontWeight: 600, color: '#fff' }}>On your phone</div>
                <div style={{ fontSize: 12, fontWeight: 500, color: 'rgba(255,255,255,0.6)' }}>3 photos</div>
              </div>
            </div>
          </NotchCard>
        </UnderNotch>
      )}

      <RealPanel open={panelOpen} scroll={scroll} layers={[['mac-panel-notch', 1], ['mac-panel-paused', paused]]} />
      <PointerAt p={openPtr} press={openClick.press} ring={openClick.ring} opacity={life(f, 38.2, 39.9, 0.2, 0.3)} />
      <PointerAt p={pausePtr} kind="hand" press={pauseClick.press} ring={pauseClick.ring} opacity={life(f, 42.5, 45.4, 0.2, 0.4)} />

      {btNote > 0.001 && (
        <UnderNotch width={300}>
          <NotchCard open={btNote} width={300}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontFamily: font.body, fontSize: 13, fontWeight: 600, color: '#fff' }}>
              <Icon name="dot.radiowaves.left.and.right" size={15} color={color.mint} />
              Pixel 9 · over Bluetooth
            </div>
          </NotchCard>
        </UnderNotch>
      )}
      {btCode > 0.001 && (
        <UnderNotch>
          <NotchCard open={btCode}>
            <CodeCard sender="Halo Bank" dots={ramp(f, 60.6, 61.1)} copied={ramp(f, 61.1, 61.4)} />
          </NotchCard>
        </UnderNotch>
      )}

      <RealPanel
        open={modesOpen}
        layers={[
          ['mac-panel-notch', 1, -turn * 120],
          ['mac-settings-notch', turn, (1 - turn) * PANEL_W],
          ['mac-settings-menubar', turn * chosen, (1 - turn) * PANEL_W],
        ]}
      />
      <RealPanel open={fromMenu} layers={[['mac-panel-menubar', 1]]} fromNotch={false} x={LINK_ICON_X - PANEL_W / 2} top={40} />
      <PointerAt p={modesPtr} press={Math.max(m0.press, m1.press, m2.press)} ring={t < 63.6 ? m0.ring : t < 65 ? m1.ring : m2.ring} opacity={life(f, 62.0, 66.3, 0.2, 0.4)} />

      {arrive > 0.001 && (
        <UnderNotch>
          <NotchCard open={arrive}>
            <ReceivingCard
              t={ins - 1.55}
              doneAt={1.3}
              progress={received}
              from="Pixel 9"
              summary={`3 photos · ${(2.4 * received).toFixed(1)} of 2.4 MB`}
              thumbs={['#d06a6f,#4a2d86', '#15245e,#2fb57e', '#f2c14e,#e0386a']}
            />
          </NotchCard>
        </UnderNotch>
      )}

      {wide > 0 && <div style={{ position: 'absolute', inset: 0, background: `rgba(0,0,0,${0.35 * wide})` }} />}
      {wide > 0 && (
        <UnderNotch width={600}>
          <div style={{ opacity: wide }}>
            <DragGlow approach={1} lightOnly />
          </div>
        </UnderNotch>
      )}
    </>
  );
};

// ---------------------------------------------------------------------------------------------
// What's on the phone's screen.

const PhoneScreen: React.FC<{ f: number }> = ({ f }) => {
  const t = f / 60;
  const on = ramp(f, 4.2, 4.6);
  // Unlocked to the home screen for the widget, into Lync, then locked again.
  const home = ramp(f, 46.8, 47.2) * (1 - ramp(f, 51.25, 51.4));
  // Lync opens out of its icon, the way Android launches apps.
  const launch = ramp(f, 51.0, 51.4);
  const app = launch * (1 - ramp(f, 56.6, 57.0));
  const photosPress = linear(f, 48.5, 48.62) * (1 - linear(f, 48.72, 48.85));
  const sheet = ramp(f, 48.9, 49.6) * (1 - ramp(f, 50.4, 50.9));
  const tabs = ['phone-home', 'phone-activity', 'phone-settings'];
  const pos = ramp(f, 52.72, 53.15) + ramp(f, 54.52, 54.95);
  const lockMessage = t < 56.8 ? ramp(f, 4.6, 5.1) : ramp(f, 59.9, 60.3);
  // Off Wi‑Fi: the shade comes down, Wi‑Fi goes off, Bluetooth stays on, the shade goes up.
  const shade = life(f, 56.9, 58.7, 0.4, 0.35);
  const wifiOff = ramp(f, 57.7, 57.85);
  const wifiPress = linear(f, 57.6, 57.7) * (1 - linear(f, 57.74, 57.86));
  return (
    <>
      <PhoneLock arrive={lockMessage} dim={1 - on} />
      {home > 0 && (
        <div style={{ position: 'absolute', inset: 0, opacity: home }}>
          <PhoneHome photosPressed={photosPress} drift={linear(f, sec(46), sec(51))} />
          {sheet > 0 && (
            <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: 560, borderRadius: '28px 28px 0 0', background: '#151816', transform: `translateY(${(1 - sheet) * 560}px)`, padding: 18, fontFamily: font.body }}>
              <div style={{ width: 36, height: 4, borderRadius: 4, background: 'rgba(255,255,255,0.3)', margin: '0 auto 14px' }} />
              <div style={{ fontSize: 16, fontWeight: 600, color: '#fff', marginBottom: 12 }}>Photos</div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 4 }}>
                {['#d06a6f,#4a2d86', '#15245e,#2fb57e', '#f2c14e,#e0386a', '#2fb57e,#1b6f8f', '#e0386a,#4a2d86', '#7fe3b0,#15245e', '#f2c14e,#2fb57e', '#4a2d86,#1b6f8f', '#d06a6f,#f2c14e'].map((g, i) => (
                  <div key={i} style={{ aspectRatio: '1', borderRadius: 4, background: `linear-gradient(135deg, ${g})`, outline: i < 3 && t > 49.9 + i * 0.25 ? `3px solid ${color.mint}` : 'none', outlineOffset: -3 }} />
                ))}
              </div>
            </div>
          )}
        </div>
      )}
      {shade > 0 && <QuickSettings pull={shade} wifiOff={wifiOff} wifiPressed={wifiPress} />}
      {app > 0 && (
        <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', opacity: Math.min(1, app * 1.6), transformOrigin: '253px 470px', transform: `scale(${0.15 + 0.85 * launch})`, borderRadius: 46 * (1 - launch) + 10 }}>
          {tabs.map((name, i) => (
            <Img key={name} src={staticFile(`screens/${name}.png`)} style={{ position: 'absolute', left: 0, top: 0, width: phoneScreen.width + 4, height: phoneScreen.height + 4, transform: `translateX(${(i - pos) * 100}%)` }} />
          ))}
        </div>
      )}
    </>
  );
};

/** Fingertip taps on the phone, in world coordinates. */
const PhoneTouches: React.FC<{ f: number }> = ({ f }) => {
  const taps: [number, number, number][] = [
    [48.5, 20 + 90, 112 + 150], // Photos on the widget
    [50.95, 253, 470], // the Lync app icon
    [52.6, 412 * 0.5, 915 - 76], // Activity tab
    [54.4, 412 * 0.8, 915 - 76], // Settings tab
    [57.6, 109, 158], // the Wi‑Fi tile
  ];
  return (
    <>
      {taps.map(([time, x, y]) => {
        const press = linear(f, time, time + 0.1) * (1 - linear(f, time + 0.14, time + 0.26));
        const ring = linear(f, time + 0.05, time + 0.6);
        if (press <= 0 && (ring <= 0 || ring >= 1)) return null;
        const [wx, wy] = onPhone(x, y);
        return (
          <div key={time} style={{ position: 'absolute', left: wx - 14, top: wy - 14, transform: `scale(${PHONE.s})`, transformOrigin: '50% 50%' }}>
            <Touch press={press} ring={ring} size={56} />
          </div>
        );
      })}
    </>
  );
};

// ---------------------------------------------------------------------------------------------
// The closing hero: Lync's cards stream out of the notch one after another and settle around it,
// level and evenly spaced, floating gently on their own layers.

const MusicMini: React.FC = () => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 12, fontFamily: font.body }}>
    <div style={{ width: 44, height: 44, borderRadius: 12, background: 'linear-gradient(135deg, #f2c14e, #e0386a 55%, #4a2d86)', display: 'grid', placeItems: 'center' }}>
      <Icon name="music.note" size={20} color="rgba(255,255,255,0.9)" />
    </div>
    <div style={{ flex: 1 }}>
      <div style={{ fontSize: 14, fontWeight: 600, color: '#fff' }}>Slow Tides</div>
      <div style={{ fontSize: 12, fontWeight: 500, color: 'rgba(255,255,255,0.6)' }}>Nova Lane · on your phone</div>
    </div>
    <div style={{ width: 36, height: 36, borderRadius: 36, background: color.pale, display: 'grid', placeItems: 'center' }}>
      <Icon name="pause.fill" size={15} color={color.onPale} />
    </div>
  </div>
);

const WideCards: React.FC<{ f: number }> = ({ f }) => {
  const t = f / 60;
  if (t < 73 || t > 77.2) return null;
  const [nx, ny] = onMac(macScreen.width / 2, 16);
  const S = 0.5;
  const slots: [number, number, React.ReactNode][] = [
    [-300, 70, <CodeCard key="c" sender="Halo Bank" />],
    [300, 70, <CallCard key="k" name="Mi amor ❤️" initials="MA" />],
    [-300, 205, <NotificationCard key="n" app="Messages" time="now" title="Design team" body="Can you share the new home screen?" />],
    [300, 205, <ReceivingCard key="r" t={9} doneAt={1} progress={1} from="Pixel 9" summary="3 photos · 2.4 MB" thumbs={['#d06a6f,#4a2d86', '#15245e,#2fb57e', '#f2c14e,#e0386a']} />],
    [0, 150, <MusicMini key="m" />],
  ];
  return (
    <>
      {slots.map(([dx, dy, node], i) => {
        // Out of the notch: from its centre, small, to its place, one after another.
        const out = springAt(f, 73.25 + i * 0.16, 'smooth', 1.1);
        const fade = 1 - ramp(f, 76.5, 77.1);
        const x = nx + dx * out + Math.sin(t * 1.3 + i * 1.7) * 5 * out;
        const y = ny + dy * out + Math.cos(t * 1.1 + i * 1.3) * 4 * out;
        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: x,
              top: y,
              width: cardWidth,
              transform: `translate(-50%, -50%) scale(${S * (0.35 + 0.65 * Math.min(1, out))})`,
              opacity: Math.min(1, out * 1.8) * fade,
              filter: 'drop-shadow(0 18px 40px rgba(0,0,0,0.55))',
            }}
          >
            <div style={{ padding: '16px', borderRadius: 24, background: `linear-gradient(180deg, ${color.mid}, ${color.card})`, border: `1px solid ${color.edge}` }}>{node}</div>
          </div>
        );
      })}
    </>
  );
};

// ---------------------------------------------------------------------------------------------
// The photos' flight: from the picker on the phone, across to the Mac's notch.

/** Seconds into the inserted flight, or -1 outside it. */
const insertTime = (nf: number) => {
  const t = nf / 60 - INSERT_AT;
  return t >= 0 && t < INSERT ? t : -1;
};
/** The rest of the film's frame: as is before the flight, held during it, shifted after it. */
const baseFrame = (nf: number) => (nf < sec(INSERT_AT) ? nf : nf < sec(INSERT_AT + INSERT) ? sec(INSERT_AT) : nf - sec(INSERT));

/** During the flight the camera follows the photos across and comes back to the phone. */
const flightPath = (): [number, Shot][] => {
  const phone = cameraAt(sec(INSERT_AT), PATH);
  return [
    [INSERT_AT, phone],
    [INSERT_AT + 0.4, { ...phone, z: 1.75 }],
    [INSERT_AT + 1.5, notchAt(170, 2.3, -60)],
    [INSERT_AT + 2.6, notchAt(70, 3.4)],
    [INSERT_AT + 3.7, notchAt(72, 3.45)],
    [INSERT_AT + INSERT, phone],
  ];
};

/** The three picked photos leave the picker and fly along a high arc into the notch, one after another. */
const FlyingPhotos: React.FC<{ ins: number }> = ({ ins }) => {
  if (ins < 0) return null;
  const tile = (412 - 36 - 8) / 3;
  const starts = [0, 1, 2].map((i) => onPhone(18 + tile / 2 + i * (tile + 4), 431 + tile / 2));
  const [nx, ny] = onMac(macScreen.width / 2, 18);
  const colours = ['#d06a6f,#4a2d86', '#15245e,#2fb57e', '#f2c14e,#e0386a'];
  return (
    <>
      {starts.map(([sx, sy], i) => {
        const t0 = 0.3 + i * 0.16;
        const u = Math.max(0, Math.min(1, (ins - t0) / 1.35));
        if (u <= 0 || u >= 1) return null;
        const e = u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2;
        // A quadratic arc that lifts well above both devices.
        const cx = (sx + nx) / 2;
        const cy = Math.min(sy, ny) - 170;
        const x = (1 - e) * (1 - e) * sx + 2 * (1 - e) * e * cx + e * e * nx;
        const y = (1 - e) * (1 - e) * sy + 2 * (1 - e) * e * cy + e * e * ny;
        const size = tile * PHONE.s * (1 - 0.65 * e) + 10 * Math.sin(u * Math.PI);
        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: x - size / 2,
              top: y - size / 2,
              width: size,
              height: size,
              borderRadius: 6,
              border: '2px solid #fff',
              background: `linear-gradient(135deg, ${colours[i]})`,
              boxShadow: '0 12px 30px rgba(0,0,0,0.45), 0 0 24px rgba(127,227,176,0.25)',
              transform: `rotate(${(1 - e) * (i - 1) * 10}deg)`,
              opacity: u > 0.92 ? (1 - u) / 0.08 : 1,
            }}
          />
        );
      })}
    </>
  );
};

// ---------------------------------------------------------------------------------------------
// Sound: real UI sounds only (public/sfx-real: a MacBook trackpad, a finger on a phone screen,
// a Mac keyboard, a soft notification, a small switch), trimmed to single clean hits and
// evened out. A sound only where something real would make one. Each cue is
// [seconds, sound, volume, length in seconds (to trim), during the photos' flight?].

type Cue = [number, string, number, number?, boolean?];

const CUES: Cue[] = [
  [4.6, 'notify', 0.57], // the phone buzzes
  [11.2, 'connect', 0.46], // phone and Mac link up

  [16.2, 'notify', 0.63], // the code drops from the notch
  [19.35, 'click', 0.52],
  [19.55, 'paste', 0.57], // ⌘V
  [22.3, 'notify', 0.63], // the call
  [24.8, 'click', 0.52],
  [27.2, 'notify', 0.57], // the message
  [28.95, 'click', 0.52],
  [29.3, 'typing', 0.52, 0.75], // the reply
  [30.85, 'click', 0.52],
  [35.4, 'click', 0.43], // letting go of the photos
  [36.6, 'notify', 0.52],
  [39.45, 'click', 0.52], // the notch
  [43.7, 'click', 0.52], // pause

  [48.5, 'tap', 0.5], // Photos on the widget
  [49.9, 'tap', 0.42],
  [50.15, 'tap', 0.42],
  [0, 'transfer', 1, undefined, true], // the photos leave the phone and land in the notch
  [50.95, 'tap', 0.46], // opening Lync

  [57.6, 'tap', 0.5], // Wi‑Fi off
  [60.3, 'notify', 0.57], // the code, over Bluetooth

  [63.0, 'click', 0.52], // the notch, settings, Menu bar
  [64.35, 'click', 0.52],
  [65.65, 'switch', 0.57],
  [67.0, 'click', 0.47], // from the menu bar

  [73.3, 'notify', 0.46], // the cards stream out of the notch
];

/** A cue's time in the finished film: the flight is inserted at INSERT_AT. */
const cueAt = ([t, , , , flight]: Cue) => (flight ? INSERT_AT + t : t < INSERT_AT ? t : t + INSERT);

const Sounds: React.FC = () => (
  <>
    {CUES.map((cue, i) => {
      const [, name, volume, length] = cue;
      return (
        <Sequence key={i} from={sec(cueAt(cue) / SPEED)} durationInFrames={length ? sec(length) : undefined} layout="none">
          <Audio src={staticFile(`sfx-real/${name}.wav`)} volume={volume} />
        </Sequence>
      );
    })}
  </>
);

// ---------------------------------------------------------------------------------------------
// The film.

export const OneTake: React.FC = () => {
  const nf = useCurrentFrame() * SPEED;
  const ins = insertTime(nf);
  const f = baseFrame(nf);
  const t = f / 60;
  const shot = ins >= 0 ? cameraAt(nf, flightPath()) : cameraAt(f, PATH);
  const buzz = t > 4.6 && t < 5.3 ? Math.sin(f * 2.4) * 5 * (1 - linear(f, 4.6, 5.3)) : 0;
  const linked = t > 11.2;
  const pulse = life(f, 11.0, 13.2, 0.6, 1.2);
  const ambient = ramp(f, 4.2, 5) * 0.6 + pulse * 0.4;
  const [nx, ny] = onMac(macScreen.width / 2, 0);
  const [px, py] = onPhone(phoneScreen.width / 2, phoneScreen.height / 2);
  const endIn = (a: number) => ramp(f, a, a + 0.6);
  const endOut = 1 - ramp(f, 80.3, 81);
  return (
    <AbsoluteFill style={{ background: color.ink }}>
      <Sounds />
      <Aurora t={nf / 60} px={shot.fx - 960} py={shot.fy - 540} />
      <AbsoluteFill style={{ background: `radial-gradient(60% 50% at 50% 45%, rgba(47,181,126,${0.16 * ambient}), transparent 70%)` }} />
      <Camera shot={shot}>
        {/* The opening question, in the dark above the devices. */}
        {t < 5 && (
          <div style={{ position: 'absolute', left: SKY.x - 900, top: SKY.y - 100, width: 1800 }}>
            <Caption text="Why should only iPhone and Mac users" progress={linear(f, 0.3, 1.8)} size={76} />
            <Caption text="have all the fun?" progress={linear(f, 1.9, 2.7)} size={76} display style={{ marginTop: 18 }} />
          </div>
        )}

        <Halo x={px} y={py} r={340} strength={pulse} />
        <Halo x={nx} y={ny + 130} r={560} strength={pulse} />

        <div style={{ position: 'absolute', left: MAC.cx - (MAC_W * MAC.s) / 2, top: MAC.top, transform: `scale(${MAC.s})`, transformOrigin: '0 0' }}>
          <MacBook linked={linked}>
            <MacScreen f={f} ins={ins} />
          </MacBook>
        </div>
        <div style={{ position: 'absolute', left: PHONE.cx - (436 * PHONE.s) / 2, top: PHONE.top, transform: `scale(${PHONE.s})`, transformOrigin: '0 0' }}>
          <Phone shake={buzz} screenOff={t < 4.2}>
            <PhoneScreen f={f} />
          </Phone>
        </div>
        <PhoneTouches f={f} />
        <FlyingPhotos ins={ins} />

        {/* Lync's promises, over the two devices. */}
        <div style={{ position: 'absolute', left: (PHONE.cx + MAC.cx) / 2 - 600, top: 110, width: 1200, display: 'flex', justifyContent: 'center', gap: 22 }}>
          <PromisePill kind="account" label="No account" show={life(f, 69.5, 72.9, 0.5, 0.5)} slash={ramp(f, 70.1, 70.5)} />
          <PromisePill kind="cloud" label="No cloud" show={life(f, 69.8, 72.9, 0.5, 0.5)} slash={ramp(f, 70.4, 70.8)} />
          <PromisePill kind="tracking" label="No tracking" show={life(f, 70.1, 72.9, 0.5, 0.5)} slash={ramp(f, 70.7, 71.1)} />
        </div>

        <WideCards f={f} />

        {/* The end card, back up in the dark where the film began. */}
        {t > 77 && (
          <div style={{ position: 'absolute', left: SKY.x - 900, top: SKY.y - 330, width: 1800, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 26, opacity: endOut }}>
            <Img src={staticFile('lync-icon.svg')} style={{ width: 150, height: 150, opacity: endIn(77.9), transform: `scale(${0.9 + 0.1 * endIn(77.9)})` }} />
            <div style={{ fontFamily: font.display, fontSize: 120, color: '#fff', opacity: endIn(77.9) }}>Lync</div>
            <div style={{ fontFamily: font.body, fontSize: 32, fontWeight: 500, color: 'rgba(255,255,255,0.72)', opacity: endIn(78.5) }}>Free for Android and Mac</div>
            <div style={{ display: 'flex', gap: 16, opacity: endIn(79.0) }}>
              <div style={{ height: 60, padding: '0 28px', borderRadius: 99, background: color.pale, color: color.onPale, display: 'flex', alignItems: 'center', fontFamily: font.body, fontSize: 22, fontWeight: 600 }}>Get it on Google Play</div>
              <div style={{ height: 60, padding: '0 28px', borderRadius: 99, background: 'rgba(255,255,255,0.13)', color: '#fff', display: 'flex', alignItems: 'center', fontFamily: font.body, fontSize: 22, fontWeight: 600 }}>Download for Mac</div>
            </div>
            <div style={{ fontFamily: font.body, fontSize: 20, fontWeight: 500, color: 'rgba(255,255,255,0.46)', opacity: endIn(79.0) }}>lyncapp.vercel.app</div>
          </div>
        )}
      </Camera>

      {/* Captions sit on a soft fall to ink so they read over anything. */}
      <AbsoluteFill style={{ background: 'linear-gradient(180deg, transparent 66%, rgba(5,10,8,0.55) 86%, rgba(5,10,8,0.8))', opacity: t > 4.5 && t < 77 ? 1 : 0 }} />
      {ins < 0 ? (
        <>
        <Line f={f} text="Your phone buzzes." from={5.0} to={5.8} out={6.6} />
        <Line f={f} text="Your Mac doesn't." from={8.0} to={8.8} out={9.7} />
        <Line f={f} text="Until now." from={11.3} to={11.9} out={12.7} />
        <Line f={f} text="Codes, already copied." from={19.6} to={20.3} out={21.4} />
        <Line f={f} text="Answer from your Mac." from={23.0} to={23.9} out={26.4} />
        <Line f={f} text="Read it. Reply. Move on." from={27.6} to={28.6} out={31.4} />
        <Line f={f} text="Drop it on the notch." from={32.6} to={33.5} out={37.4} />
        <Line f={f} text="Your phone, one click away." from={40.0} to={40.9} out={41.8} />
        <Line f={f} text="Pause your music from your Mac." from={42.4} to={43.3} out={46.0} />
        <Line f={f} text="Or send from your home screen." from={47.6} to={48.6} out={49.9} size={54} />
        <Line f={f} text="And everything on your phone, too." from={51.4} to={52.4} out={56.4} size={54} />
        <Line f={f} text="Even off Wi‑Fi." from={57.9} to={58.7} out={61.8} />
        <Line f={f} text="In the notch or the menu bar." from={62.4} to={63.3} out={66.6} />
        <Line f={f} text="Already have a notch app? Keep it." from={67.3} to={68.2} out={69.2} />
        <Line f={f} text="No account. No cloud. Just your two devices." from={69.6} to={71.4} out={72.6} size={52} />
          <Line f={f} text="Everything from your phone, in your notch." from={74.3} to={75.3} out={76.6} size={54} />
        </>
      ) : (
        <Line f={nf} text="Straight to your Mac." from={INSERT_AT + 0.6} to={INSERT_AT + 1.3} out={INSERT_AT + 3.8} />
      )}
    </AbsoluteFill>
  );
};
