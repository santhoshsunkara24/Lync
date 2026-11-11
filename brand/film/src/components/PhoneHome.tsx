import React from 'react';
import { Img, staticFile } from 'remotion';
import { color, font } from '../theme';
import { AppTile, CalendarTile } from './Desktop';
import { Icon } from './Icon';
import { phoneScreen, PhoneStatusBar } from './Devices';
import { MacWidget } from './Widget';

/**
 * The phone's screens for the film, all made up: a home screen (wallpaper, clock, the Lync
 * widget, apps, dock and search) and a lock screen with a message arriving.
 */

/** Soft colour pooling from the bottom, like a phone wallpaper, in Lync's greens. */
export const PhoneWallpaper: React.FC<{ drift?: number }> = ({ drift = 0 }) => (
  <div
    style={{
      position: 'absolute',
      inset: 0,
      background: [
        `radial-gradient(80% 45% at ${20 + drift * 8}% 100%, #2fb57ecc, transparent 70%)`,
        `radial-gradient(70% 40% at ${85 - drift * 6}% 88%, #1b6f8fcc, transparent 70%)`,
        `radial-gradient(90% 55% at 50% 0%, #10261d, transparent 70%)`,
        'linear-gradient(180deg, #0a1511, #0c1f18 60%, #123a2b)',
      ].join(','),
    }}
  />
);

const label: React.CSSProperties = { fontFamily: font.body, fontSize: 12, fontWeight: 500, color: 'rgba(255,255,255,0.92)', marginTop: 6, textAlign: 'center' };

const App: React.FC<{ name: string; children: React.ReactNode }> = ({ name, children }) => (
  <div style={{ width: 76, display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
    {children}
    <div style={label}>{name}</div>
  </div>
);

/** The home screen, with the widget pressed by `photosPressed` 0…1. */
/** `wallHue`/`appHue` turn the wallpaper's and Lync's colours round the colour wheel, to show Lync taking on the wallpaper's colours. */
export const PhoneHome: React.FC<{ photosPressed?: number; drift?: number; wallHue?: number; appHue?: number }> = ({ photosPressed = 0, drift = 0, wallHue = 0, appHue = 0 }) => {
  const s = 58;
  return (
    <div style={{ position: 'absolute', inset: 0, width: phoneScreen.width, height: phoneScreen.height }}>
      <div style={{ position: 'absolute', inset: 0, filter: wallHue ? `hue-rotate(${wallHue}deg)` : undefined }}>
        <PhoneWallpaper drift={drift} />
      </div>
      <PhoneStatusBar />
      <div style={{ position: 'absolute', left: 0, right: 0, top: 70, textAlign: 'center', fontFamily: font.body, color: color.white }}>
        <div style={{ fontSize: 15, fontWeight: 500, opacity: 0.85 }}>Mon, Nov 9</div>
      </div>
      <div style={{ position: 'absolute', left: 20, top: 112, filter: appHue ? `hue-rotate(${appHue}deg)` : undefined }}>
        <MacWidget photosPressed={photosPressed} />
      </div>
      <div style={{ position: 'absolute', left: 18, right: 18, top: 340, display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', rowGap: 22, justifyItems: 'center' }}>
        <App name="Calendar"><CalendarTile size={s} /></App>
        <App name="Photos"><AppTile size={s} from="#ffb86b" to="#f05a7e" glyph="photo" round /></App>
        <App name="Music"><AppTile size={s} from="#ff7a8a" to="#e2304f" glyph="music.note" round /></App>
        <App name="Files"><AppTile size={s} from="#5aa7ff" to="#1d5fd6" glyph="folder.badge.minus" round /></App>
        <App name="Mail"><AppTile size={s} from="#58b6ff" to="#1f7ae0" glyph="paperplane" round /></App>
        <App name="Notes"><AppTile size={s} from="#ffe48a" to="#f5b928" glyph="square.and.pencil" round /></App>
        <App name="Lync"><Img src={staticFile('lync-icon.svg')} style={{ width: s, height: s }} /></App>
        <App name="Settings"><AppTile size={s} from="#a3a8b0" to="#5d6168" glyph="gearshape" round /></App>
      </div>
      {/* The dock and the search bar. */}
      <div style={{ position: 'absolute', left: 30, right: 30, bottom: 104, display: 'flex', justifyContent: 'space-between' }}>
        <AppTile size={s} from="#6be38b" to="#22a24a" glyph="phone.fill" round />
        <AppTile size={s} from="#6be38b" to="#1e9e74" glyph="message" round />
        <AppTile size={s} from="#4fd1ff" to="#1576d8" glyph="safari" round />
        <AppTile size={s} from="#3a3d42" to="#1c1d20" round>
          <div style={{ width: s * 0.42, height: s * 0.42, borderRadius: '50%', border: '4px solid #fff' }} />
        </AppTile>
      </div>
      <div style={{ position: 'absolute', left: 28, right: 28, bottom: 34, height: 52, borderRadius: 26, background: 'rgba(255,255,255,0.16)', display: 'flex', alignItems: 'center', gap: 12, padding: '0 20px', fontFamily: font.body, fontSize: 15, color: 'rgba(255,255,255,0.75)' }}>
        <Icon name="magnifyingglass" size={18} color="rgba(255,255,255,0.75)" />
        Search
      </div>
    </div>
  );
};

/**
 * The lock screen: big clock and date, and a message sliding down from the top.
 * `arrive` 0…1 brings the message in.
 */
export const PhoneLock: React.FC<{ arrive?: number; from?: string; message?: string; dim?: number }> = ({
  arrive = 0,
  from = 'Halo Bank',
  message = 'Your sign-in code is 482 913. Don’t share it with anyone.',
  dim = 0,
}) => (
  <div style={{ position: 'absolute', inset: 0, width: phoneScreen.width, height: phoneScreen.height }}>
    <PhoneWallpaper />
    <div style={{ position: 'absolute', inset: 0, background: `rgba(0,0,0,${0.25 + dim * 0.6})` }} />
    <PhoneStatusBar />
    <div style={{ position: 'absolute', left: 0, right: 0, top: 110, textAlign: 'center', fontFamily: font.body, color: color.white }}>
      <div style={{ fontSize: 18, fontWeight: 500, opacity: 0.9 }}>Monday, November 9</div>
      <div style={{ fontSize: 112, fontWeight: 300, lineHeight: 1, marginTop: 4, letterSpacing: '-0.02em' }}>9:41</div>
    </div>
    <div
      style={{
        position: 'absolute',
        left: 14,
        right: 14,
        top: 330,
        borderRadius: 26,
        padding: '14px 16px',
        background: 'rgba(30,32,30,0.82)',
        display: 'flex',
        gap: 12,
        alignItems: 'flex-start',
        opacity: arrive,
        transform: `translateY(${(1 - arrive) * -40}px) scale(${0.96 + 0.04 * arrive})`,
        fontFamily: font.body,
      }}
    >
      <AppTile size={40} from="#6be38b" to="#1e9e74" glyph="message" round />
      <div style={{ flex: 1 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, color: 'rgba(255,255,255,0.6)' }}>
          <span>Messages</span>
          <span>now</span>
        </div>
        <div style={{ fontSize: 15, fontWeight: 600, color: color.white, marginTop: 2 }}>{from}</div>
        <div style={{ fontSize: 14, color: 'rgba(255,255,255,0.85)', marginTop: 2, lineHeight: 1.35 }}>{message}</div>
      </div>
    </div>
  </div>
);
