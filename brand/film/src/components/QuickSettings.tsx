import React from 'react';
import { color, font } from '../theme';
import { WifiGlyph } from './Devices';

/**
 * Android's quick settings, pulled down over the screen: the clock, then tiles. Wi‑Fi turns off
 * with `wifiOff` 0…1; Bluetooth stays on. `pull` 0…1 slides the shade down from the top.
 */
export const QuickSettings: React.FC<{ pull: number; wifiOff: number; wifiPressed?: number }> = ({ pull, wifiOff, wifiPressed = 0 }) => {
  const tile = (on: number, title: string, subtitle: string, glyph: React.ReactNode, pressed = 0) => (
    <div
      style={{
        height: 72,
        borderRadius: 36,
        padding: '0 18px',
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        background: on > 0.5 ? color.pale : 'rgba(255,255,255,0.12)',
        color: on > 0.5 ? color.onPale : color.white,
        transform: `scale(${1 - 0.04 * pressed})`,
      }}
    >
      <div style={{ width: 24, height: 24, display: 'grid', placeItems: 'center' }}>{glyph}</div>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 15, fontWeight: 600 }}>{title}</div>
        <div style={{ fontSize: 12.5, fontWeight: 500, opacity: 0.75 }}>{subtitle}</div>
      </div>
    </div>
  );
  const wifiOn = 1 - wifiOff;
  const ink = (on: number) => (on > 0.5 ? color.onPale : color.white);
  return (
    <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', opacity: Math.min(1, pull * 3) }}>
      <div style={{ position: 'absolute', inset: 0, background: `rgba(0,0,0,${0.55 * pull})` }} />
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: 0,
          height: 470,
          padding: '56px 18px 18px',
          borderRadius: '0 0 32px 32px',
          background: '#141816',
          transform: `translateY(${(1 - pull) * -470}px)`,
          fontFamily: font.body,
          color: color.white,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', padding: '0 6px 18px' }}>
          <span style={{ fontSize: 40, fontWeight: 400 }}>9:41</span>
          <span style={{ fontSize: 14, fontWeight: 500, opacity: 0.7 }}>Mon, Nov 9</span>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          {tile(wifiOn, 'Wi‑Fi', wifiOn > 0.5 ? 'Home' : 'Off', <WifiGlyph size={22} c={ink(wifiOn)} />, wifiPressed)}
          {tile(1, 'Bluetooth', 'On', <BluetoothGlyph c={color.onPale} />)}
          {tile(0, 'Flashlight', 'Off', <FlashGlyph />)}
          {tile(0, 'Do Not Disturb', 'Off', <MoonGlyph />)}
          {tile(0, 'Airplane mode', 'Off', <PlaneGlyph />)}
          {tile(0, 'Auto-rotate', 'Off', <RotateGlyph />)}
        </div>
        <div style={{ height: 4, width: 40, borderRadius: 4, background: 'rgba(255,255,255,0.3)', margin: '20px auto 0' }} />
      </div>
    </div>
  );
};

const stroke = { fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };

export const BluetoothGlyph: React.FC<{ c?: string; size?: number }> = ({ c = 'currentColor', size = 22 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" style={{ color: c }} aria-hidden>
    <path d="M7 7l10 10-5 5V2l5 5L7 17" {...stroke} />
  </svg>
);
const FlashGlyph: React.FC = () => (
  <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden>
    <path d="M8 3h8l-1 6H9zM9 9h6v12H9z" {...stroke} />
  </svg>
);
const MoonGlyph: React.FC = () => (
  <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden>
    <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" {...stroke} />
  </svg>
);
const PlaneGlyph: React.FC = () => (
  <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden>
    <path d="M10.5 3.5c0-.8.7-1.5 1.5-1.5s1.5.7 1.5 1.5V9l7 4v2l-7-2v5l2 1.5V21l-3.5-1-3.5 1v-1.5l2-1.5v-5l-7 2v-2l7-4z" {...stroke} strokeWidth={1.6} />
  </svg>
);
const RotateGlyph: React.FC = () => (
  <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden>
    <path d="M4 12a8 8 0 0 1 14-5.3M20 12a8 8 0 0 1-14 5.3M18 3v4h-4M6 21v-4h4" {...stroke} />
  </svg>
);
