import React from 'react';
import { Easing, interpolate, spring } from 'remotion';

export const FPS = 60;
export const sec = (s: number) => Math.round(s * FPS);

/** The film's one curve: slow out, slow in, the way a camera operator eases a move. */
const gentle = Easing.bezier(0.45, 0, 0.2, 1);

/** 0…1 between two times (in seconds), eased. */
export const ramp = (frame: number, from: number, to: number, easing = gentle) =>
  interpolate(frame, [sec(from), sec(to)], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing });

/** Linear 0…1 between two times, for steady things like typing. */
export const linear = (frame: number, from: number, to: number) =>
  interpolate(frame, [sec(from), sec(to)], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });

/** A spring starting at a time, like the app's bouncy and smooth animations. `slow` stretches it (slow motion). */
export const springAt = (frame: number, at: number, kind: 'bouncy' | 'smooth' | 'snappy' = 'smooth', slow = 1) =>
  spring({
    frame: (frame - sec(at)) / slow,
    fps: FPS,
    config: kind === 'bouncy' ? { damping: 11, stiffness: 120, mass: 1 } : kind === 'snappy' ? { damping: 20, stiffness: 220 } : { damping: 18, stiffness: 110 },
  });

/** Mixes two numbers. */
export const mix = (a: number, b: number, t: number) => a + (b - a) * t;

/** A camera framing point (fx, fy) of a 1920×1080 stage at zoom z, with an optional tilt. */
export type Shot = { fx: number; fy: number; z: number; rx?: number; ry?: number };

/** Moves between camera framings over time: [time, framing] pairs, eased between each. */
export const cameraAt = (frame: number, keys: [number, Shot][]): Shot => {
  if (frame <= sec(keys[0][0])) return keys[0][1];
  for (let i = 0; i < keys.length - 1; i++) {
    const [t0, a] = keys[i];
    const [t1, b] = keys[i + 1];
    if (frame <= sec(t1)) {
      const t = ramp(frame, t0, t1);
      return { fx: mix(a.fx, b.fx, t), fy: mix(a.fy, b.fy, t), z: mix(a.z, b.z, t), rx: mix(a.rx ?? 0, b.rx ?? 0, t), ry: mix(a.ry ?? 0, b.ry ?? 0, t) };
    }
  }
  return keys[keys.length - 1][1];
};

/** The stage seen through the camera. */
export const Camera: React.FC<{ shot: Shot; children: React.ReactNode }> = ({ shot, children }) => (
  <div style={{ position: 'absolute', inset: 0, perspective: 2400, overflow: 'hidden' }}>
    <div
      style={{
        position: 'absolute',
        left: 0,
        top: 0,
        width: 1920,
        height: 1080,
        transformOrigin: `${shot.fx}px ${shot.fy}px`,
        transform: `translate(${960 - shot.fx}px, ${540 - shot.fy}px) scale(${shot.z}) rotateX(${shot.rx ?? 0}deg) rotateY(${shot.ry ?? 0}deg)`,
      }}
    >
      {children}
    </div>
  </div>
);
