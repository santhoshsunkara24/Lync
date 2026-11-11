import React from 'react';
import { Img, staticFile } from 'remotion';
import { color, font, notch } from '../theme';
import { Icon } from './Icon';
import { macScreen, WifiGlyph } from './Devices';

/**
 * A believable Mac desktop for the film, with nothing real on it: a flowing wallpaper in Lync's
 * colours, a menu bar for a made-up notes app, and a Dock of generic app icons plus Lync.
 */

/** Soft ribbons of colour, blurred like a macOS wallpaper. `drift` slides them slowly. */
export const MacWallpaper: React.FC<{ drift?: number }> = ({ drift = 0 }) => (
  <svg width={macScreen.width} height={macScreen.height} viewBox={`0 0 ${macScreen.width} ${macScreen.height}`} style={{ position: 'absolute', inset: 0 }} aria-hidden>
    <defs>
      <linearGradient id="wpSky" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stopColor="#06110d" />
        <stop offset="0.55" stopColor="#0b2a20" />
        <stop offset="1" stopColor="#0f3b2c" />
      </linearGradient>
      <linearGradient id="wpRibbonA" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0" stopColor="#1f8f68" />
        <stop offset="0.5" stopColor="#5fd9a0" />
        <stop offset="1" stopColor="#1b6f8f" />
      </linearGradient>
      <linearGradient id="wpRibbonB" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0" stopColor="#0e5a46" />
        <stop offset="0.6" stopColor="#2fb57e" />
        <stop offset="1" stopColor="#7fe3b0" />
      </linearGradient>
      <filter id="wpBlur" x="-20%" y="-20%" width="140%" height="140%">
        <feGaussianBlur stdDeviation="38" />
      </filter>
      <filter id="wpSoft" x="-20%" y="-20%" width="140%" height="140%">
        <feGaussianBlur stdDeviation="10" />
      </filter>
    </defs>
    <rect width="100%" height="100%" fill="url(#wpSky)" />
    <g transform={`translate(${drift * -40} 0)`}>
      <path d="M-200 700 C 200 520, 520 860, 900 640 S 1500 520, 1800 640 L1800 1100 L-200 1100 Z" fill="url(#wpRibbonB)" opacity="0.55" filter="url(#wpBlur)" />
      <path d="M-200 600 C 260 420, 560 760, 980 560 S 1520 400, 1800 500" stroke="url(#wpRibbonA)" strokeWidth="120" fill="none" opacity="0.75" filter="url(#wpSoft)" />
      <path d="M-200 680 C 300 520, 620 820, 1020 640 S 1560 500, 1800 600" stroke="url(#wpRibbonB)" strokeWidth="60" fill="none" opacity="0.9" filter="url(#wpSoft)" />
      <path d="M-200 760 C 340 640, 700 900, 1080 740 S 1600 620, 1800 720" stroke="#bff3da" strokeWidth="6" fill="none" opacity="0.35" filter="url(#wpSoft)" />
    </g>
  </svg>
);

/** The menu bar of a made-up notes app, with Wi‑Fi, battery and the time. Nothing personal. */
export const MacMenuBar: React.FC<{ app?: string; time?: string; wifi?: boolean }> = ({ app = 'Notes', time = 'Mon 9:41 AM', wifi = true }) => {
  const item: React.CSSProperties = { fontFamily: font.body, fontSize: 13.5, fontWeight: 500, color: 'rgba(255,255,255,0.92)' };
  return (
    <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: notch.height, background: 'rgba(0,0,0,0.22)', display: 'flex', alignItems: 'center', padding: '0 14px', gap: 20 }}>
      {/* No Apple logo: a plain dot stands in for the system menu. */}
      <div style={{ width: 14, height: 14, borderRadius: 7, background: 'rgba(255,255,255,0.9)' }} />
      <span style={{ ...item, fontWeight: 700 }}>{app}</span>
      {['File', 'Edit', 'Format', 'View', 'Window', 'Help'].map((m) => (
        <span key={m} style={item}>{m}</span>
      ))}
      <div style={{ flex: 1 }} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, ...item, fontWeight: 600 }}>
        {/* Lync's own menu bar item: the link. */}
        <Icon name="link" size={17} color="rgba(255,255,255,0.92)" />
        {wifi ? <WifiGlyph size={16} /> : <Icon name="wifi.slash" size={16} color="rgba(255,255,255,0.5)" />}
        <Battery />
        <Icon name="magnifyingglass" size={14} color="rgba(255,255,255,0.92)" />
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>{time}</span>
      </div>
    </div>
  );
};

const Battery: React.FC = () => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 1 }}>
    <div style={{ width: 25, height: 12, borderRadius: 4, border: '1.3px solid rgba(255,255,255,0.6)', padding: 1.6 }}>
      <div style={{ width: '82%', height: '100%', borderRadius: 2, background: color.white }} />
    </div>
    <div style={{ width: 1.6, height: 4.5, borderRadius: 1, background: 'rgba(255,255,255,0.6)' }} />
  </div>
);

/** A generic app icon: a rounded square in a gradient with a simple white glyph. */
export const AppTile: React.FC<{ size: number; from: string; to: string; glyph?: string; children?: React.ReactNode; round?: boolean }> = ({ size, from, to, glyph, children, round }) => (
  <div
    style={{
      width: size,
      height: size,
      borderRadius: round ? size : size * 0.225,
      background: `linear-gradient(160deg, ${from}, ${to})`,
      display: 'grid',
      placeItems: 'center',
      boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.25), 0 2px 6px rgba(0,0,0,0.25)',
      flex: 'none',
    }}
  >
    {glyph && <Icon name={glyph} size={size * 0.5} color="#fff" />}
    {children}
  </div>
);

/** A calendar tile showing a date, drawn like a page. */
export const CalendarTile: React.FC<{ size: number; day?: string; date?: string }> = ({ size, day = 'MON', date = '9' }) => (
  <div style={{ width: size, height: size, borderRadius: size * 0.225, background: '#fff', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', boxShadow: '0 2px 6px rgba(0,0,0,0.25)', flex: 'none', fontFamily: font.body }}>
    <span style={{ fontSize: size * 0.18, fontWeight: 700, color: '#e5484d', letterSpacing: '0.04em' }}>{day}</span>
    <span style={{ fontSize: size * 0.48, fontWeight: 500, color: '#111', lineHeight: 1 }}>{date}</span>
  </div>
);

/** The Dock: a frosted shelf of generic apps, with Lync among them. */
export const MacDock: React.FC<{ lyncLit?: boolean }> = ({ lyncLit = true }) => {
  const s = 58;
  const tiles: React.ReactNode[] = [
    <AppTile key="files" size={s} from="#5aa7ff" to="#1d5fd6" glyph="folder.badge.minus" />,
    <AppTile key="web" size={s} from="#4fd1ff" to="#1576d8" glyph="safari" />,
    <AppTile key="mail" size={s} from="#58b6ff" to="#1f7ae0" glyph="paperplane" />,
    <AppTile key="msg" size={s} from="#6be38b" to="#22a24a" glyph="message" />,
    <AppTile key="photos" size={s} from="#ffb86b" to="#f05a7e" glyph="photo" />,
    <CalendarTile key="cal" size={s} />,
    <AppTile key="notes" size={s} from="#ffe48a" to="#f5b928" glyph="square.and.pencil" />,
    <AppTile key="music" size={s} from="#ff7a8a" to="#e2304f" glyph="music.note" />,
    <div key="lync" style={{ position: 'relative' }}>
      <Img src={staticFile('lync-icon.svg')} style={{ width: s, height: s, display: 'block' }} />
    </div>,
    <AppTile key="settings" size={s} from="#a3a8b0" to="#5d6168" glyph="gearshape" />,
  ];
  return (
    <div style={{ position: 'absolute', left: '50%', bottom: 10, transform: 'translateX(-50%)', padding: '8px 10px', borderRadius: 24, background: 'rgba(255,255,255,0.16)', border: '1px solid rgba(255,255,255,0.22)', display: 'flex', alignItems: 'flex-end', gap: 10 }}>
      {tiles.map((t, i) => (
        <div key={i} style={{ position: 'relative' }}>
          {t}
          {(i === 1 || i === 6 || (i === 8 && lyncLit)) && <div style={{ position: 'absolute', left: '50%', bottom: -6, width: 4, height: 4, borderRadius: 4, transform: 'translateX(-50%)', background: 'rgba(255,255,255,0.8)' }} />}
        </div>
      ))}
    </div>
  );
};

/** A browser window with a made-up site's sign-in page and a six-box code field. */
export const SignInWindow: React.FC<{ digits?: string; focused?: boolean }> = ({ digits = '', focused = true }) => (
  <div style={{ width: 760, borderRadius: 14, overflow: 'hidden', background: '#f6f6f4', boxShadow: '0 30px 80px rgba(0,0,0,0.45), 0 0 0 0.5px rgba(0,0,0,0.4)', fontFamily: font.body }}>
    <div style={{ height: 44, background: '#e9e9e6', display: 'flex', alignItems: 'center', gap: 8, padding: '0 14px', borderBottom: '1px solid #dcdcd8' }}>
      {['#ff5f57', '#febc2e', '#28c840'].map((c) => (
        <div key={c} style={{ width: 12, height: 12, borderRadius: 6, background: c }} />
      ))}
      <div style={{ flex: 1, display: 'flex', justifyContent: 'center' }}>
        <div style={{ height: 26, width: 340, borderRadius: 8, background: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, fontSize: 12.5, color: '#55574f' }}>
          <Icon name="lock" size={11} color="#55574f" /> accounts.northwind.app
        </div>
      </div>
    </div>
    <div style={{ padding: '54px 0 60px', display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
      <div style={{ width: 44, height: 44, borderRadius: 12, background: '#1e3a5f', color: '#fff', display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 20 }}>N</div>
      <div style={{ marginTop: 18, fontSize: 24, fontWeight: 600, color: '#1b1c19' }}>Check your phone</div>
      <div style={{ marginTop: 6, fontSize: 14, color: '#6b6d66' }}>Enter the 6-digit code we sent you</div>
      <div style={{ marginTop: 26, display: 'flex', gap: 10 }}>
        {[0, 1, 2, 3, 4, 5].map((i) => (
          <div
            key={i}
            style={{
              width: 50,
              height: 60,
              borderRadius: 10,
              background: '#fff',
              border: `1.5px solid ${focused && i === digits.length ? '#2f6fde' : '#d6d6d1'}`,
              boxShadow: focused && i === digits.length ? '0 0 0 3px rgba(47,111,222,0.18)' : 'none',
              display: 'grid',
              placeItems: 'center',
              fontSize: 26,
              fontWeight: 600,
              color: '#1b1c19',
              marginLeft: i === 3 ? 10 : 0,
            }}
          >
            {digits[i] ?? ''}
          </div>
        ))}
      </div>
      <div style={{ marginTop: 26, height: 44, width: 340, borderRadius: 10, background: digits.length === 6 ? '#1e3a5f' : '#aeb7c2', color: '#fff', display: 'grid', placeItems: 'center', fontSize: 15, fontWeight: 600 }}>Continue</div>
    </div>
  </div>
);
