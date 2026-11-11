import React, { useEffect, useState } from 'react';
import { Composition, continueRender, delayRender } from 'remotion';
import { fontsReady } from './fonts';
import { CardsCheck, DevicesCheck } from './checks/Checks';
import { FPS, sec } from './film/motion';
import { FILM_LENGTH, OneTake } from './film/OneTake';
import { Short, SHORT_SECONDS } from './short/Short';
import { Wide, WIDE_SECONDS } from './wide/Wide';
import { Review, REVIEW_FPS, REVIEW_SECONDS, REVIEW_SIZE } from './review/Review';

/** Holds each frame until Figtree and Galindo have loaded, so nothing renders in a fallback face. */
const WithFonts: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [handle] = useState(() => delayRender('fonts'));
  const [ready, setReady] = useState(false);
  useEffect(() => {
    fontsReady.then(() => {
      setReady(true);
      continueRender(handle);
    });
  }, [handle]);
  return ready ? <>{children}</> : null;
};

export const Root: React.FC = () => (
  <>
    {/* The launch film: one continuous take. */}
    <Composition id="Film" component={() => <WithFonts><OneTake /></WithFonts>} durationInFrames={sec(FILM_LENGTH)} width={1920} height={1080} fps={FPS} />
    {/* The vertical short: the same story, fast, on the beat. */}
    <Composition id="Short" component={() => <WithFonts><Short /></WithFonts>} durationInFrames={sec(SHORT_SECONDS)} width={1080} height={1920} fps={FPS} />
    {/* The wide film in the short's style. */}
    <Composition id="Wide" component={(p: { bg?: 'silk' | 'grid' | 'aurora' }) => <WithFonts><Wide bg={p.bg} /></WithFonts>} defaultProps={{ bg: 'aurora' as const }} durationInFrames={sec(WIDE_SECONDS)} width={1920} height={1080} fps={FPS} />
    {/* Play's permission review video, from screen recordings in public/review (not committed). */}
    <Composition id="Review" component={() => <WithFonts><Review /></WithFonts>} durationInFrames={Math.round(REVIEW_SECONDS * REVIEW_FPS)} {...REVIEW_SIZE} fps={REVIEW_FPS} />
    <Composition id="CardsCheck" component={() => <WithFonts><CardsCheck /></WithFonts>} width={896} height={1300} fps={FPS} durationInFrames={1} />
    <Composition id="DevicesCheck" component={() => <WithFonts><DevicesCheck /></WithFonts>} width={1920} height={1080} fps={FPS} durationInFrames={1} />
  </>
);
