import { loadFont } from '@remotion/fonts';
import { staticFile } from 'remotion';

// The apps' own fonts: Figtree (a variable font, weights 300-900) and Galindo for big words.
export const fontsReady = Promise.all([
  loadFont({ family: 'Figtree', url: staticFile('fonts/Figtree.ttf'), weight: '300 900' }),
  loadFont({ family: 'Galindo', url: staticFile('fonts/Galindo.ttf') }),
]);
