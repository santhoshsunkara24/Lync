import React from 'react';
import { color, font, notch, space } from '../theme';
import { Icon } from './Icon';

/**
 * The drag-to-send glow (DragGlow in the Mac app): the phone's colour pouring out from under
 * the notch like light, a chevron, and the pill. `approach` 0…1 is how close the files are;
 * `ready` is the moment they're over the notch and letting go sends them.
 */
export const DragGlow: React.FC<{
  approach: number;
  ready?: boolean;
  phone?: string;
  summary?: string;
  /** Offset for the chevron's slow breathing, in points. */
  breathe?: number;
  /** Just the light, without the chevron and pill (the reveal and the wide shot). */
  lightOnly?: boolean;
}> = ({ approach, ready = false, phone = 'your phone', summary, breathe = 0, lightOnly = false }) => {
  const width = Math.max(notch.width * 1.7, 300);
  const height = notch.height + 110;
  const pill = Math.min(Math.max((approach - 0.4) / 0.4, 0), 1);
  const light = (c: string, strength: number, w: number, x: number, blend?: 'plus-lighter') => {
    const a = (k: number) => {
      const v = Math.round(Math.min(1, strength * k) * 255).toString(16).padStart(2, '0');
      return `${c}${v}`;
    };
    return (
      <div
        style={{
          position: 'absolute',
          left: `calc(50% - ${w / 2}px + ${x}px)`,
          top: 0,
          width: w,
          height,
          background: `radial-gradient(${w / 2}px ${height}px at 50% 0%, ${a(1)} 0%, ${a(0.65)} 30%, ${a(0.22)} 65%, ${a(0)} 100%)`,
          mixBlendMode: blend,
        }}
      />
    );
  };
  return (
    <div style={{ position: 'relative', width, height: height + 60 }}>
      <div style={{ position: 'absolute', inset: 0, isolation: 'isolate', opacity: 0.5 + 0.5 * approach }}>
        {light(color.glowAccent, 0.75, width * 0.8, -width * 0.14)}
        {light(color.glowAccent, 0.75, width * 0.8, width * 0.14)}
        {light(color.glow, 0.9, width * 0.7, 0, 'plus-lighter')}
        {light(color.glow, 0.45, width * 0.32, 0, 'plus-lighter')}
      </div>
      <div
        style={{
          display: lightOnly ? 'none' : 'flex',
          position: 'absolute',
          left: 0,
          right: 0,
          top: notch.height + space.xxs,
          flexDirection: 'column',
          alignItems: 'center',
          gap: space.xxs,
        }}
      >
        <Icon
          name="chevron.compact.down"
          size={24}
          color={`rgba(255,255,255,${0.55 + 0.4 * approach})`}
          style={{ filter: `drop-shadow(0 0 6px ${color.glow})`, transform: `translateY(${breathe}px)` }}
        />
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: space.xs,
            height: ready ? 40 : 32,
            padding: `0 ${space.m}px`,
            borderRadius: 99,
            background: 'rgba(0,0,0,0.9)',
            border: `${ready ? 1.5 : 1}px solid ${color.glow}${ready ? 'e6' : '73'}`,
            boxShadow: `0 0 ${ready ? 16 : 12}px ${color.glow}${ready ? 'b3' : '73'}`,
            opacity: pill,
            transform: `scale(${0.9 + 0.1 * pill})`,
            transformOrigin: '50% 0%',
            fontFamily: font.body,
            fontSize: 13,
            fontWeight: 600,
            whiteSpace: 'nowrap',
          }}
        >
          <Icon name={ready ? 'arrow.up.doc.fill' : 'hand.point.up.left.fill'} size={16} color={color.glow} />
          <span style={{ color: color.white }}>{ready ? 'Release to send' : `Drop to send to ${phone}`}</span>
          {ready && summary && <span style={{ color: 'rgba(255,255,255,0.6)' }}>&nbsp;·&nbsp;{summary}</span>}
        </div>
      </div>
    </div>
  );
};
