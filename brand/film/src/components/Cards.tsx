import React from 'react';
import { color, font, space } from '../theme';
import { Icon } from './Icon';

// The contents of the Mac app's notch cards (LiveCardView), point for point.

const text = (size: number, weight: number, c: string = color.white): React.CSSProperties => ({
  fontFamily: font.body,
  fontSize: size,
  fontWeight: weight,
  color: c,
  lineHeight: 1.25,
  whiteSpace: 'nowrap',
});

/** Lync's code glyph: a pill with six dots, ••• •••, on a 256 grid (CodeGlyph.swift). */
export const CodeGlyph: React.FC<{ size: number; color: string }> = ({ size, color: c }) => (
  <svg width={size} height={size} viewBox="0 0 256 256" aria-hidden>
    <path
      fillRule="evenodd"
      fill={c}
      d={
        'M64 76h128a52 52 0 0 1 0 104H64a52 52 0 0 1 0-104z' +
        [39, 73, 107, 149, 183, 217].map((x) => `M${x - 13} 128a13 13 0 1 0 26 0a13 13 0 1 0 -26 0z`).join('')
      }
    />
  </svg>
);

/** The code glyph in its round tinted tile (CodeIconTile). */
export const CodeIconTile: React.FC<{ size?: number }> = ({ size = 32 }) => (
  <div style={{ width: size, height: size, borderRadius: size, background: 'rgba(127,227,176,0.16)', display: 'grid', placeItems: 'center', flex: 'none' }}>
    <CodeGlyph size={size * 0.56} color={color.mint} />
  </div>
);

/** "✓ Copied": the badge the code card shows once the code is on the clipboard. */
export const CopiedBadge: React.FC<{ opacity?: number }> = ({ opacity = 1 }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 6, height: 24, padding: '0 10px', borderRadius: 99, background: 'rgba(255,255,255,0.12)', opacity }}>
    <Icon name="checkmark" size={14} color={color.white} />
    <span style={text(12, 600)}>Copied</span>
  </div>
);

/**
 * A one-time code arriving: the code itself stays hidden as dots in the notch (it's on the
 * clipboard), with who sent it and "Copied".
 * `dots` 0…1 rolls the six dots in one after another, like the app's RollingCode.
 */
export const CodeCard: React.FC<{ sender: string; dots?: number; copied?: number }> = ({ sender, dots = 1, copied = 1 }) => (
  <div style={{ display: 'flex', alignItems: 'flex-start', gap: space.s }}>
    <CodeIconTile />
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: space.xxs }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: space.xs }}>
        <div style={{ display: 'flex', ...text(26, 600), letterSpacing: 1, fontVariantNumeric: 'tabular-nums' }}>
          {'••• •••'.split('').map((ch, i) => {
            const p = Math.max(0, Math.min(1, dots * 7 - i));
            return (
              <span key={i} style={{ display: 'inline-block', width: ch === ' ' ? 10 : 'auto', opacity: p, transform: `translateY(${(1 - p) * 18}px)` }}>
                {ch === ' ' ? '' : ch}
              </span>
            );
          })}
        </div>
        <div style={{ flex: 1 }} />
        <CopiedBadge opacity={copied} />
      </div>
      <span style={text(12, 500, 'rgba(255,255,255,0.6)')}>{sender}</span>
    </div>
  </div>
);

const CallButton: React.FC<{ title: string; icon: string; fill: string; pressed?: number }> = ({ title, icon, fill, pressed = 0 }) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      gap: 6,
      height: 32,
      padding: `0 ${space.m}px`,
      borderRadius: 99,
      background: fill,
      transform: `scale(${1 - 0.06 * pressed})`,
    }}
  >
    <Icon name={icon} size={15} color={color.ink} />
    <span style={text(13, 600, color.ink)}>{title}</span>
  </div>
);

/** An incoming call: who, "Incoming call" and the SIM, Decline and Answer on phone. */
export const CallCard: React.FC<{ name: string; initials: string; sim?: string; answerPressed?: number }> = ({ name, initials, sim, answerPressed = 0 }) => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: space.s }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: space.s }}>
      <div style={{ width: 40, height: 40, borderRadius: 40, background: 'rgba(255,255,255,0.14)', display: 'grid', placeItems: 'center', ...text(15, 600) }}>{initials}</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: space.xxs }}>
        <span style={text(15, 600)}>{name}</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: space.xs }}>
          <span style={text(11, 500, 'rgba(255,255,255,0.6)')}>Incoming call</span>
          {sim && (
            <span style={{ ...text(11, 500, 'rgba(255,255,255,0.6)'), height: 16, padding: `0 ${space.xs}px`, borderRadius: 99, background: 'rgba(255,255,255,0.14)', display: 'flex', alignItems: 'center' }}>{sim}</span>
          )}
        </div>
      </div>
    </div>
    <div style={{ display: 'flex', gap: space.xs, paddingLeft: 40 + space.s }}>
      <CallButton title="Decline" icon="phone.down.fill" fill={color.danger} />
      <CallButton title="Answer on phone" icon="phone.fill" fill={color.success} pressed={answerPressed} />
    </div>
  </div>
);

/** A generic messaging app's icon: a speech bubble on a rounded square (no real brand). */
export const MessageAppIcon: React.FC<{ size?: number }> = ({ size = 32 }) => (
  <div style={{ width: size, height: size, borderRadius: size * 0.225, background: '#2a2b2e', display: 'grid', placeItems: 'center', flex: 'none' }}>
    <Icon name="message" size={size * 0.56} color={color.white} />
  </div>
);

/**
 * A phone notification: app and time, title, text, then Reply. With `reply` set, the reply
 * field shows that much typed text and its Send button.
 */
export const NotificationCard: React.FC<{
  app: string;
  time: string;
  title: string;
  body: string;
  reply?: string;
  caret?: boolean;
}> = ({ app, time, title, body, reply, caret }) => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: space.s }}>
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: space.s }}>
      <MessageAppIcon />
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: space.xxs }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: space.xs }}>
          <span style={text(11, 500, 'rgba(255,255,255,0.6)')}>{app} · {time}</span>
          <div style={{ flex: 1 }} />
          <div style={{ width: 24, height: 24, borderRadius: 24, background: 'rgba(255,255,255,0.12)', display: 'grid', placeItems: 'center' }}>
            <Icon name="xmark" size={13} color={color.white} style={{ filter: "drop-shadow(0 0 0.4px #fff)" }} />
          </div>
        </div>
        <span style={text(14, 600)}>{title}</span>
        <span style={{ ...text(13, 500, 'rgba(255,255,255,0.8)'), whiteSpace: 'normal' }}>{body}</span>
      </div>
    </div>
    <div style={{ paddingLeft: 32 + space.s, display: 'flex', gap: space.xs, alignItems: 'center' }}>
      {reply === undefined ? (
        <div style={{ height: 24, padding: `0 ${space.s}px`, borderRadius: 99, background: color.pale, display: 'flex', alignItems: 'center', ...text(12, 600, color.onPale) }}>Reply</div>
      ) : (
        <>
          <div style={{ flex: 1, height: 28, borderRadius: 99, background: 'rgba(255,255,255,0.12)', display: 'flex', alignItems: 'center', padding: `0 ${space.s}px`, ...text(13, 500, reply ? color.white : 'rgba(255,255,255,0.45)') }}>
            {reply || `Reply to ${title}…`}
            {caret && <span style={{ width: 1.5, height: 15, background: color.white, marginLeft: 1 }} />}
          </div>
          <div style={{ height: 28, padding: `0 ${space.s}px`, borderRadius: 99, background: color.pale, display: 'flex', alignItems: 'center', ...text(12, 600, color.onPale) }}>Send</div>
        </>
      )}
    </div>
  </div>
);
