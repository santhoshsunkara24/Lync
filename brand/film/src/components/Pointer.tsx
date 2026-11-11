import React from 'react';

/**
 * The pointer, drawn big and calm like in launch films: the macOS arrow or the pointing hand,
 * white with a dark outline and a soft shadow. `press` 0…1 squashes it a little and rings out
 * from the tip on a click.
 */
export const Pointer: React.FC<{ kind?: 'arrow' | 'hand'; size?: number; press?: number; ring?: number }> = ({ kind = 'arrow', size = 44, press = 0, ring = 0 }) => (
  <div style={{ position: 'relative', width: size, height: size }}>
    {ring > 0 && ring < 1 && (
      <div
        style={{
          position: 'absolute',
          left: kind === 'arrow' ? size * 0.12 : size * 0.38,
          top: kind === 'arrow' ? size * 0.08 : size * 0.06,
          width: size * 1.6 * ring,
          height: size * 1.6 * ring,
          transform: 'translate(-50%, -50%)',
          borderRadius: '50%',
          border: `2px solid rgba(255,255,255,${0.6 * (1 - ring)})`,
        }}
      />
    )}
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      style={{
        transform: `scale(${1 - 0.1 * press})`,
        transformOrigin: kind === 'arrow' ? '12% 8%' : '38% 6%',
        filter: 'drop-shadow(0 4px 6px rgba(0,0,0,0.45))',
        overflow: 'visible',
      }}
      aria-hidden
    >
      {kind === 'arrow' ? (
        <path d="M4 2 L4 25 L10 19.5 L14 28.5 L18 26.8 L14 18 L22 18 Z" fill="#fff" stroke="#111" strokeWidth="1.6" strokeLinejoin="round" />
      ) : (
        <path
          d="M12 2.5c1.4 0 2.5 1.1 2.5 2.5v8.2c.5-.4 1.1-.6 1.8-.6 1.2 0 2.2.7 2.6 1.8.5-.4 1.1-.6 1.8-.6 1.3 0 2.4.9 2.7 2.1.4-.2.9-.3 1.4-.3 1.5 0 2.7 1.2 2.7 2.7v5.4c0 4.4-3.6 8-8 8h-2.8c-2.5 0-4.8-1.2-6.3-3.2l-5.3-7.1c-.8-1.1-.6-2.6.5-3.4 1-.8 2.4-.7 3.3.2l1.6 1.6V5c0-1.4 1.1-2.5 2.5-2.5z"
          fill="#fff"
          stroke="#111"
          strokeWidth="1.5"
          strokeLinejoin="round"
        />
      )}
    </svg>
  </div>
);

/** A fingertip on a phone screen: a soft disc that presses in and rings out. */
export const Touch: React.FC<{ press: number; ring?: number; size?: number }> = ({ press, ring = 0, size = 56 }) => (
  <div style={{ position: 'relative', width: size, height: size }}>
    <div
      style={{
        position: 'absolute',
        inset: 0,
        borderRadius: '50%',
        background: 'rgba(255,255,255,0.35)',
        boxShadow: '0 0 0 6px rgba(255,255,255,0.12)',
        transform: `scale(${1 - 0.15 * press})`,
        opacity: press > 0 || ring > 0 ? 1 : 0,
      }}
    />
    {ring > 0 && ring < 1 && (
      <div style={{ position: 'absolute', left: '50%', top: '50%', width: size * 2 * ring, height: size * 2 * ring, transform: 'translate(-50%,-50%)', borderRadius: '50%', border: `2px solid rgba(255,255,255,${0.5 * (1 - ring)})` }} />
    )}
  </div>
);
