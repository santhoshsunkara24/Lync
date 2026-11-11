import React from 'react';
import { color, font, notch } from '../theme';
import { Icon } from './Icon';

/** The MacBook's screen, in points (a 13" MacBook Air at its default size). */
export const macScreen = { width: 1470, height: 956 };
/** The phone's screen, in dp (a 6.3" Android phone). */
export const phoneScreen = { width: 412, height: 915 };

/**
 * A MacBook Air drawn head-on: aluminium lid edge, black bezel, the screen with its notch, and
 * the deck below. Everything on the screen is `children`, laid out in screen points.
 * `linked` lights the camera green, like the phone app's little Mac.
 */
export const MacBook: React.FC<{ children?: React.ReactNode; linked?: boolean; showBase?: boolean; screenOff?: boolean }> = ({
  children,
  linked = false,
  showBase = true,
  screenOff = false,
}) => {
  const bezel = 18;
  const lidW = macScreen.width + bezel * 2;
  const lidH = macScreen.height + bezel + 22;
  return (
    <div style={{ position: 'relative', width: lidW * 1.12, display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
      {/* Lid: a thin aluminium rim around the black glass. */}
      <div
        style={{
          width: lidW,
          height: lidH,
          borderRadius: '34px 34px 6px 6px',
          padding: 3,
          background: 'linear-gradient(180deg, #8e9196, #5b5e63 40%, #3d3f43)',
          boxShadow: '0 40px 120px rgba(0,0,0,0.55)',
        }}
      >
        <div style={{ width: '100%', height: '100%', borderRadius: '31px 31px 4px 4px', background: '#050506', padding: `${bezel}px ${bezel}px 22px`, position: 'relative' }}>
          <div style={{ position: 'relative', width: macScreen.width, height: macScreen.height, borderRadius: '14px 14px 0 0', overflow: 'hidden', background: screenOff ? '#020203' : color.ink }}>
            {!screenOff && children}
            {/* Glass: a faint sheen across the top left. */}
            <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(115deg, rgba(255,255,255,0.05), rgba(255,255,255,0) 38%)', pointerEvents: 'none' }} />
          </div>
          {/* The notch, cut into the top of the screen, with the camera. */}
          <div style={{ position: 'absolute', left: '50%', top: bezel - 1, width: notch.width, height: notch.height + 1, transform: 'translateX(-50%)', background: '#050506', borderRadius: '0 0 12px 12px' }}>
            <div
              style={{
                position: 'absolute',
                left: '50%',
                top: 10,
                width: 9,
                height: 9,
                transform: 'translateX(-50%)',
                borderRadius: 9,
                background: linked ? color.mint : '#141516',
                boxShadow: linked ? `0 0 10px ${color.mint}` : 'inset 0 0 2px #2a2c2e',
              }}
            />
          </div>
        </div>
      </div>
      {showBase && (
        <>
          {/* The hinge and the front of the deck, with the thumb scoop. */}
          <div style={{ width: lidW * 1.12, height: 26, marginTop: -2, borderRadius: '2px 2px 18px 18px', background: 'linear-gradient(180deg, #b9bcc1, #8a8d92 45%, #55585c)', position: 'relative' }}>
            <div style={{ position: 'absolute', left: '50%', top: 0, width: 220, height: 10, transform: 'translateX(-50%)', borderRadius: '0 0 12px 12px', background: 'linear-gradient(180deg, #6f7276, #9a9da2)' }} />
          </div>
          <div style={{ width: lidW * 1.04, height: 40, marginTop: -6, borderRadius: '50%', background: 'radial-gradient(closest-side, rgba(0,0,0,0.5), transparent)', filter: 'blur(6px)' }} />
        </>
      )}
    </div>
  );
};

/** A calm desktop: Lync's own wallpaper (ink with the phone's colours drifting in from below). */
export const Wallpaper: React.FC<{ drift?: number }> = ({ drift = 0 }) => (
  <div
    style={{
      position: 'absolute',
      inset: 0,
      background: [
        `radial-gradient(60% 70% at ${30 + drift * 6}% 110%, ${color.top}cc, transparent 70%)`,
        `radial-gradient(50% 60% at ${78 - drift * 4}% 100%, #1f6f8acc, transparent 70%)`,
        `radial-gradient(80% 60% at 50% 120%, ${color.mid}, transparent 75%)`,
        `linear-gradient(180deg, #08090a, #0d1412)`,
      ].join(','),
    }}
  />
);

/**
 * A clean menu bar: no app menus, no personal icons. Just Wi‑Fi, battery and a time, with an
 * empty gap where the notch sits.
 */
export const MenuBar: React.FC<{ time?: string; wifi?: boolean }> = ({ time = '9:41', wifi = true }) => (
  <div
    style={{
      position: 'absolute',
      left: 0,
      right: 0,
      top: 0,
      height: notch.height,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'flex-end',
      gap: 16,
      padding: '0 18px',
      background: 'rgba(0,0,0,0.25)',
      fontFamily: font.body,
      fontSize: 13,
      fontWeight: 600,
      color: color.white,
    }}
  >
    {wifi ? <WifiGlyph /> : <Icon name="wifi.slash" size={16} color="rgba(255,255,255,0.5)" />}
    <BatteryGlyph level={0.82} />
    <span style={{ fontVariantNumeric: 'tabular-nums' }}>{time}</span>
  </div>
);

/** Wi‑Fi: three arcs over a dot. */
export const WifiGlyph: React.FC<{ size?: number; c?: string }> = ({ size = 16, c = color.white }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden>
    <path d="M12 18.5a1.6 1.6 0 1 1 0 .01z" fill={c} />
    <path d="M8.2 15.1a5.4 5.4 0 0 1 7.6 0M5.1 12a9.8 9.8 0 0 1 13.8 0M2 8.9a14.2 14.2 0 0 1 20 0" fill="none" stroke={c} strokeWidth="2.2" strokeLinecap="round" />
  </svg>
);

const BatteryGlyph: React.FC<{ level: number }> = ({ level }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 1 }}>
    <div style={{ width: 24, height: 11, borderRadius: 3.5, border: '1.2px solid rgba(255,255,255,0.55)', padding: 1.5 }}>
      <div style={{ width: `${level * 100}%`, height: '100%', borderRadius: 1.5, background: color.white }} />
    </div>
    <div style={{ width: 1.5, height: 4, borderRadius: 1, background: 'rgba(255,255,255,0.55)' }} />
  </div>
);

/**
 * An Android phone drawn head-on: a thin dark frame, the punch-hole camera, and the screen as
 * `children` in dp. `shake` moves it sideways, for a buzz.
 */
export const Phone: React.FC<{ children?: React.ReactNode; screenOff?: boolean; shake?: number }> = ({ children, screenOff = false, shake = 0 }) => {
  const frame = 12;
  return (
    <div
      style={{
        width: phoneScreen.width + frame * 2,
        height: phoneScreen.height + frame * 2,
        borderRadius: 56,
        padding: frame - 2,
        background: 'linear-gradient(160deg, #3a3c40, #1a1b1d 40%, #2a2b2e)',
        boxShadow: '0 40px 100px rgba(0,0,0,0.55), inset 0 0 0 2px #46484c',
        transform: `translateX(${shake}px)`,
      }}
    >
      <div style={{ position: 'relative', width: phoneScreen.width + 4, height: phoneScreen.height + 4, borderRadius: 46, overflow: 'hidden', background: screenOff ? '#020203' : color.ink, padding: 2 }}>
        {!screenOff && children}
        <div style={{ position: 'absolute', left: '50%', top: 16, width: 14, height: 14, transform: 'translateX(-50%)', borderRadius: 14, background: '#050506', boxShadow: 'inset 0 0 0 2px #1b1c1e' }} />
      </div>
    </div>
  );
};

/** The phone's status bar: time on the left, Wi‑Fi and battery on the right. */
export const PhoneStatusBar: React.FC<{ time?: string }> = ({ time = '9:41' }) => (
  <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 28px', fontFamily: font.body, fontSize: 14, fontWeight: 600, color: color.white }}>
    <span>{time}</span>
    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
      <WifiGlyph size={14} />
      <BatteryGlyph level={0.64} />
    </div>
  </div>
);
