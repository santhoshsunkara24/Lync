import React from 'react';
import { color, font } from '../theme';

/**
 * Lync's promises as calm pills, each with its own crossed-out glyph: no account, no cloud,
 * no tracking. Drawn in the app's icon style (2 pt rounded strokes) with a mint slash.
 */
const stroke = { fill: 'none', stroke: 'currentColor', strokeWidth: 1.9, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };

const Slash: React.FC<{ draw: number }> = ({ draw }) => (
  <>
    {/* A gap in the glyph under the slash, so the line reads cleanly. */}
    <path d="M4.5 4.5l15 15" stroke="#1d2421" strokeWidth={4.4} strokeLinecap="round" strokeDasharray="21.3" strokeDashoffset={21.3 * (1 - draw)} />
    <path d="M4.5 4.5l15 15" stroke={color.mint} strokeWidth={2} strokeLinecap="round" strokeDasharray="21.3" strokeDashoffset={21.3 * (1 - draw)} />
  </>
);

const glyphs: Record<string, React.ReactNode> = {
  account: (
    <>
      <circle cx="12" cy="8" r="3.6" {...stroke} />
      <path d="M5 20c.8-3.6 3.6-5.6 7-5.6s6.2 2 7 5.6" {...stroke} />
    </>
  ),
  cloud: <path d="M7.2 18.5h9.6a4.2 4.2 0 0 0 .6-8.35A5.8 5.8 0 0 0 6.3 11.2a3.7 3.7 0 0 0 .9 7.3z" {...stroke} />,
  tracking: (
    <>
      <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z" {...stroke} />
      <circle cx="12" cy="12" r="3" {...stroke} />
    </>
  ),
};

export const PromisePill: React.FC<{ kind: 'account' | 'cloud' | 'tracking'; label: string; show: number; slash: number }> = ({ kind, label, show, slash }) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      gap: 14,
      height: 76,
      padding: '0 30px 0 22px',
      borderRadius: 99,
      background: '#1d2421',
      border: `1px solid ${color.edge}`,
      boxShadow: '0 18px 50px rgba(0,0,0,0.45)',
      opacity: show,
      transform: `translateY(${(1 - show) * 18}px) scale(${0.94 + 0.06 * show})`,
      fontFamily: font.body,
      fontSize: 28,
      fontWeight: 600,
      color: color.white,
      whiteSpace: 'nowrap',
    }}
  >
    <svg width="40" height="40" viewBox="0 0 24 24" style={{ color: 'rgba(255,255,255,0.85)' }} aria-hidden>
      {glyphs[kind]}
      <Slash draw={slash} />
    </svg>
    {label}
  </div>
);
