// Lync's look, taken from the apps' code (mac/Sources/WallhopMac/UI/Theme.swift and the phone's
// HomeColors) for Lync's own green (seed #2FB57E). Sizes are in points, as in the Mac app.

export const color = {
  ink: '#0b0b0c',
  black: '#000000',
  // The phone's palette, from Theme.palette(hue: 155, sat: 0.59).
  top: '#36a175',
  mid: '#1e4837',
  card: '#162820',
  pale: '#dafbee',
  onPale: '#165a3e',
  glow: '#33ffab',
  // The neighbouring hue mixed into the glow's sides (Theme.swift: main hue - 35).
  glowAccent: '#29ff29',
  // One-time codes: Forest and Mint.
  forest: '#0f2e24',
  mint: '#7fe3b0',
  success: 'rgb(92, 214, 128)',
  danger: 'rgb(255, 107, 97)',
  white: '#ffffff',
  secondary: 'rgba(255,255,255,0.68)',
  tertiary: 'rgba(255,255,255,0.6)',
  chip: 'rgba(255,255,255,0.13)',
  surface: 'rgba(255,255,255,0.07)',
  edge: 'rgba(255,255,255,0.1)',
};

export const space = { xxs: 4, xs: 8, s: 12, m: 16, l: 24, xl: 32 };
export const radius = { row: 8, small: 12, field: 16, group: 24, card: 24, panel: 32 };

export const font = {
  body: 'Figtree, system-ui, sans-serif',
  display: 'Galindo, system-ui, sans-serif',
};

/** A MacBook Air's notch, in points: what the notch cards grow out of. */
export const notch = { width: 186, height: 32 };
/** The Mac app's notch card width (OverlayController.cardWidth). */
export const cardWidth = 400;
