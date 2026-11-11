import React from 'react';
import { color, font } from '../theme';
import { Icon } from './Icon';

/**
 * The phone's home screen widget (android/.../widget/MacWidget.kt, widget_mac.xml), in dp:
 * the Mac's name and link, the battery in Galindo, then Photos and Files.
 */
export const MacWidget: React.FC<{ name?: string; battery?: number; charging?: boolean; photosPressed?: number }> = ({
  name = 'MacBook Air',
  battery = 82,
  charging = true,
  photosPressed = 0,
}) => (
  <div style={{ width: 372, height: 186, borderRadius: 28, background: color.top, padding: '16px 16px 14px 18px', display: 'flex', flexDirection: 'column', fontFamily: font.body }}>
    <div style={{ display: 'flex', alignItems: 'flex-start' }}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 17, fontWeight: 600, color: color.white, whiteSpace: 'nowrap' }}>{name}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 5, marginTop: 3 }}>
          <Icon name="link" size={16} color={color.mint} />
          <span style={{ fontSize: 13, fontWeight: 500, color: 'rgba(255,255,255,0.85)' }}>Connected</span>
        </div>
      </div>
      <div style={{ textAlign: 'right' }}>
        <div style={{ fontFamily: font.display, fontSize: 26, lineHeight: 1, color: color.white }}>{battery}%</div>
        {charging && (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 2, marginTop: 3, fontSize: 12, fontWeight: 600, color: color.mint }}>
            <Bolt /> Charging
          </div>
        )}
      </div>
    </div>
    <div style={{ flex: 1 }} />
    <div style={{ display: 'flex', gap: 8, height: 44 }}>
      <Button label="Photos" icon="photo" bg={color.pale} fg={color.onPale} pressed={photosPressed} />
      <Button label="Files" icon="doc.fill" bg="rgba(255,255,255,0.16)" fg={color.white} />
    </div>
  </div>
);

const Button: React.FC<{ label: string; icon: string; bg: string; fg: string; pressed?: number }> = ({ label, icon, bg, fg, pressed = 0 }) => (
  <div style={{ flex: 1, borderRadius: 99, background: bg, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 7, transform: `scale(${1 - 0.05 * pressed})` }}>
    <Icon name={icon} size={18} color={fg} />
    <span style={{ fontSize: 14, fontWeight: 600, color: fg }}>{label}</span>
  </div>
);

const Bolt: React.FC = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" aria-hidden>
    <path d="M13.4 3.2 4.7 13.4c-.5.6-.2 1.1.6 1.1H11l-1 6.3c-.1.8.4 1 .9.4l8.8-10.3c.5-.6.2-1.1-.6-1.1H13.3l1-6.2c.1-.8-.4-1-.9-.4z" fill={color.mint} />
  </svg>
);
