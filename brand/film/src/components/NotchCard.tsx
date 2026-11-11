import React from 'react';
import { cardWidth, color, notch, radius, space } from '../theme';

/** The curve where the card meets the top of the screen (NotchShape's earRadius). */
const EAR = 10;

/**
 * A card growing out of the MacBook's notch, as the Mac app draws it (OverlayController,
 * NotchShape): black at the top so it flows out of the notch, the phone's colour at the bottom,
 * small curved "ears" where it meets the top of the screen.
 */
export const NotchCard: React.FC<{
  children: React.ReactNode;
  width?: number;
  /** 0…1: grows out of the notch (scale from the top, like the app's bouncy insertion). */
  open?: number;
  style?: React.CSSProperties;
}> = ({ children, width = cardWidth, open = 1, style }) => (
  <div
    style={{
      position: 'relative',
      width,
      transformOrigin: '50% 0%',
      transform: `scale(${0.9 + 0.1 * open})`,
      opacity: Math.min(1, open * 1.6),
      ...style,
    }}
  >
    <div
      style={{
        position: 'absolute',
        inset: 0,
        borderRadius: `0 0 ${radius.group}px ${radius.group}px`,
        background: `linear-gradient(180deg, ${color.black} 0%, ${color.black} 45%, ${color.card} 100%)`,
      }}
    />
    <Ear side="left" />
    <Ear side="right" />
    <div style={{ position: 'relative', padding: `${notch.height + space.s}px ${space.m}px ${space.m}px` }}>{children}</div>
  </div>
);

const Ear: React.FC<{ side: 'left' | 'right' }> = ({ side }) => (
  <div
    style={{
      position: 'absolute',
      top: 0,
      [side]: -EAR,
      width: EAR,
      height: EAR,
      background: `radial-gradient(circle at ${side === 'left' ? '0% 100%' : '100% 100%'}, transparent ${EAR - 0.5}px, ${color.black} ${EAR}px)`,
    }}
  />
);
