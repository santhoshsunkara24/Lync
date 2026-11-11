import React from 'react';
import { AbsoluteFill } from 'remotion';
import { CallCard, CodeCard, NotificationCard } from '../components/Cards';
import { MacBook, MenuBar, Phone, PhoneStatusBar, Wallpaper, macScreen } from '../components/Devices';
import { DragGlow } from '../components/DragGlow';
import { NotchCard } from '../components/NotchCard';
import { Pointer } from '../components/Pointer';
import { MacWidget } from '../components/Widget';
import { color } from '../theme';

/**
 * The rebuilt notch cards at 2×, laid out like the Mac app's own snapshot renders
 * (scripts/snapshots.sh: 5_live_code, 7d_live_call, 7e_live_notification, 10e_overlay_drag_glow),
 * so the two can be compared side by side.
 */
export const CardsCheck: React.FC = () => (
  <AbsoluteFill style={{ background: '#3c3c3c' }}>
    <div style={{ transform: 'scale(2)', transformOrigin: '0 0', width: 448, display: 'flex', flexDirection: 'column', gap: 5 }}>
      <div style={{ padding: '0 12px' }}>
        <NotchCard>
          <CodeCard sender="HDFC Bank" />
        </NotchCard>
      </div>
      <div style={{ padding: '0 12px' }}>
        <NotchCard>
          <CallCard name="Mi amor ❤️" initials="MA" sim="SIM 1" />
        </NotchCard>
      </div>
      <div style={{ padding: '0 12px' }}>
        <NotchCard>
          <NotificationCard app="Messages" time="now" title="Design team" body="Can you share the new home screen?" />
        </NotchCard>
      </div>
      <div style={{ background: '#8e8e8e', height: 220, display: 'flex', justifyContent: 'center' }}>
        <DragGlow approach={0.85} phone="Pixel 9" />
      </div>
    </div>
  </AbsoluteFill>
);

/** The MacBook with a code card in its notch, and the phone with the widget: the film's two stars. */
export const DevicesCheck: React.FC = () => (
  <AbsoluteFill style={{ background: color.ink }}>
    <div style={{ position: 'absolute', left: 80, top: 120, transform: 'scale(0.62)', transformOrigin: '0 0' }}>
      <MacBook linked>
        <Wallpaper />
        <MenuBar />
        <div style={{ position: 'absolute', left: (macScreen.width - 400) / 2, top: 0 }}>
          <NotchCard>
            <CodeCard sender="HDFC Bank" />
          </NotchCard>
        </div>
        <div style={{ position: 'absolute', left: 900, top: 420 }}>
          <Pointer kind="hand" size={44} />
        </div>
      </MacBook>
    </div>
    <div style={{ position: 'absolute', right: 110, top: 150, transform: 'scale(0.82)', transformOrigin: '100% 0' }}>
      <Phone>
        <div style={{ position: 'absolute', inset: 0, background: `linear-gradient(180deg, #24402f, ${color.ink})` }} />
        <PhoneStatusBar />
        <div style={{ position: 'absolute', left: 20, top: 120 }}>
          <MacWidget />
        </div>
      </Phone>
    </div>
  </AbsoluteFill>
);
