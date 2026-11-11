import React from 'react';
import { color, font } from '../theme';

/**
 * A caption that types on word by word: each word rises a little and fades in. `progress` 0…1
 * runs through the words; words after `dimFrom` stay quieter.
 */
export const Caption: React.FC<{ text: string; progress: number; size?: number; display?: boolean; dimFrom?: number; style?: React.CSSProperties }> = ({
  text,
  progress,
  size = 64,
  display = false,
  dimFrom,
  style,
}) => {
  const words = text.split(' ');
  return (
    <div
      style={{
        fontFamily: display ? font.display : font.body,
        fontWeight: display ? 400 : 600,
        fontSize: size,
        letterSpacing: display ? 0 : '-0.01em',
        color: color.white,
        textAlign: 'center',
        lineHeight: 1.15,
        ...style,
      }}
    >
      {words.map((w, i) => {
        const p = Math.max(0, Math.min(1, progress * words.length - i));
        const quiet = dimFrom !== undefined && i >= dimFrom;
        return (
          <span
            key={i}
            style={{
              display: 'inline-block',
              marginRight: '0.26em',
              opacity: p * (quiet ? 0.55 : 1),
              transform: `translateY(${(1 - p) * 0.3}em)`,
              filter: `blur(${(1 - p) * 6}px)`,
            }}
          >
            {w}
          </span>
        );
      })}
    </div>
  );
};
