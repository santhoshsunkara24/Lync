import React from 'react';
import { staticFile } from 'remotion';

/** One of Lync's icons (the Mac app's set, by SF Symbol name), tinted like a template image. */
export const Icon: React.FC<{ name: string; size: number; color: string; style?: React.CSSProperties }> = ({ name, size, color, style }) => {
  const url = `url(${staticFile(`icons/${name}.svg`)})`;
  return (
    <div
      style={{
        width: size,
        height: size,
        flex: 'none',
        backgroundColor: color,
        WebkitMaskImage: url,
        maskImage: url,
        WebkitMaskSize: 'contain',
        maskSize: 'contain',
        WebkitMaskRepeat: 'no-repeat',
        maskRepeat: 'no-repeat',
        WebkitMaskPosition: 'center',
        maskPosition: 'center',
        ...style,
      }}
    />
  );
};
