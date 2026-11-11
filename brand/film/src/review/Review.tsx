import React from 'react';
import { AbsoluteFill, OffthreadVideo, Sequence, staticFile, useCurrentFrame } from 'remotion';
import { color, font } from '../theme';

/**
 * The video for Play's permission review: three screen recordings (phone settings, a code reaching
 * the Mac, a call on the Mac), each after a short title. The Mac recordings are full-screen, so only
 * Lync's own card or panel shows: everything around it is cut away. Muted. The recordings live in
 * public/review/, which is never committed.
 */
export const REVIEW_FPS = 30;
const TITLE = 2.2;
const CLIPS = [
  { file: 'review/1-phone.mp4', seconds: 17.2, title: '1. Lync runs in the background', note: 'Codes, Calls and Texts on · Run in background on · ongoing notification' },
  { file: 'review/2-code.mp4', seconds: 15.4, title: '2. An SMS code reaches the Mac', note: 'Copied to the Mac’s clipboard, ready to paste' },
  { file: 'review/3-call.mp4', seconds: 32.9, title: '3. Calls show on the Mac', note: 'Who’s calling, with Answer and Decline' },
];
export const REVIEW_SECONDS = CLIPS.reduce((s, c) => s + TITLE + c.seconds, 0) + 1.5;

const W = 1080;
const H = 1920;

/** A part of a 2940 × 1912 Mac recording, scaled to fit the box, with only [show] visible. */
const MacWindow: React.FC<{ file: string; crop: [number, number, number, number]; show: (t: number) => [number, number, number, number] }> = ({ file, crop, show }) => {
  const t = useCurrentFrame() / REVIEW_FPS;
  const [cx, cy, cw, ch] = crop;
  const s = Math.min(960 / cw, 1500 / ch);
  const [sx, sy, sw, sh] = show(t);
  return (
    <div style={{ position: 'absolute', left: (W - cw * s) / 2, top: 300, width: cw * s, height: ch * s, overflow: 'hidden', borderRadius: 28, background: '#000', boxShadow: '0 0 0 2px rgba(255,255,255,.12)' }}>
      <div style={{ position: 'absolute', left: (sx - cx) * s, top: (sy - cy) * s, width: sw * s, height: sh * s, overflow: 'hidden', borderRadius: 30 * s }}>
        <OffthreadVideo muted src={staticFile(file)} style={{ position: 'absolute', left: -sx * s, top: -sy * s, width: 2940 * s, height: 1912 * s, maxWidth: 'none' }} />
      </div>
    </div>
  );
};

const Title: React.FC<{ text: string; note: string }> = ({ text, note }) => (
  <AbsoluteFill style={{ background: '#050807', justifyContent: 'center', alignItems: 'center', padding: 80, textAlign: 'center' }}>
    <div style={{ fontFamily: font.body, fontWeight: 700, fontSize: 64, lineHeight: 1.15, color: '#fff' }}>{text}</div>
    <div style={{ fontFamily: font.body, fontWeight: 500, fontSize: 34, lineHeight: 1.3, color: color.mint, marginTop: 28 }}>{note}</div>
  </AbsoluteFill>
);

const Caption: React.FC<{ text: string }> = ({ text }) => (
  <div style={{ position: 'absolute', top: 110, left: 60, right: 60, textAlign: 'center', fontFamily: font.body, fontWeight: 700, fontSize: 48, color: '#fff' }}>{text}</div>
);

export const Review: React.FC = () => {
  let at = 0;
  const parts: React.ReactNode[] = [];
  CLIPS.forEach((c, i) => {
    parts.push(
      <Sequence key={`t${i}`} from={Math.round(at * REVIEW_FPS)} durationInFrames={Math.round(TITLE * REVIEW_FPS)}>
        <Title text={c.title} note={c.note} />
      </Sequence>,
    );
    at += TITLE;
    let body: React.ReactNode;
    if (i === 0) {
      // The phone's own recording (720 × 1600), whole.
      body = <OffthreadVideo muted src={staticFile(c.file)} style={{ position: 'absolute', left: (W - 720 * 1.0) / 2, top: 300, width: 720, height: 1600, borderRadius: 36 }} />;
    } else if (i === 1) {
      // Nothing until the code's card has faded in (the browser's tabs are under it), then the
      // card, then the panel.
      body = <MacWindow file={c.file} crop={[1060, 0, 820, 1290]} show={(t) => (t < 2.3 ? [1078, 0, 0, 0] : t < 5.6 ? [1070, 0, 800, 214] : [1078, 0, 778, 1268])} />;
    } else {
      // The call card once it has faded in, then the slimmer "On phone" bar after answering.
      body = <MacWindow file={c.file} crop={[1060, 0, 820, 280]} show={(t) => (t < 3.2 ? [1070, 0, 0, 0] : t < 7.4 ? [1070, 0, 800, 280] : [1070, 0, 800, 146])} />;
    }
    parts.push(
      <Sequence key={`c${i}`} from={Math.round(at * REVIEW_FPS)} durationInFrames={Math.round(c.seconds * REVIEW_FPS)}>
        <AbsoluteFill style={{ background: '#050807' }}>
          <Caption text={c.title} />
          {body}
        </AbsoluteFill>
      </Sequence>,
    );
    at += c.seconds;
  });
  return <AbsoluteFill style={{ background: '#050807' }}>{parts}</AbsoluteFill>;
};

export const REVIEW_SIZE = { width: W, height: H };
