"""Lync icon: the rounded-tube link on deep mint. Builds every platform asset.

Run from brand/icon:  python3 final.py
Writes: out/*.svg + PNGs (rendered with headless Chrome), the Mac AppIcon.icns and menu bar
template, and the Android adaptive-icon layers, monochrome and notification icons."""
import os, subprocess, shutil, tempfile
HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
OUT = os.path.join(HERE, 'out'); os.makedirs(OUT, exist_ok=True)
CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

# Brand colours.
MINT_TOP, MINT_BOTTOM = '#5FD9A0', '#13875A'
SHADOW = '#0B5A3A'

# The link: two hooks sharing one band, drawn level in a 256 box, then turned 45°.
r, sw, half, long_, short = 31, 19, 62, 82, 15
y0, y1 = 128 - r, 128 + r; ax, bx = 128 - half, 128 + half
A = f'M{ax+long_},{y0} H{ax} A{r},{r} 0 0 0 {ax},{y1} H{ax+short}'
B = f'M{bx-long_},{y1} H{bx} A{r},{r} 0 0 0 {bx},{y0} H{bx-short}'

def p(d, stroke, w=sw, extra=''):
    return f'<path d="{d}" stroke="{stroke}" stroke-width="{w}" stroke-linecap="round" fill="none" {extra}/>'
def g(body, dx=0, dy=0, filt=''):
    f = f' filter="url(#{filt})"' if filt else ''
    return f'<g transform="translate({dx} {dy}) rotate(-45 128 128)"{f}>{body}</g>'
DEFS = '''<defs>
<linearGradient id="tile" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5FD9A0"/><stop offset="1" stop-color="#13875A"/></linearGradient>
<linearGradient id="sheen" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".22"/><stop offset=".5" stop-color="#fff" stop-opacity="0"/></linearGradient>
<filter id="blur6" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="6"/></filter>
<filter id="blur1" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="1"/></filter>
<filter id="tileShadow" x="-20%" y="-20%" width="140%" height="150%"><feDropShadow dx="0" dy="3" stdDeviation="3.5" flood-color="#000" flood-opacity=".28"/></filter>
<linearGradient id="tube" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff"/><stop offset=".55" stop-color="#E8FFF4"/><stop offset="1" stop-color="#B9EED3"/></linearGradient>
<linearGradient id="tubeB" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#D6FBE9"/><stop offset="1" stop-color="#8FDDB8"/></linearGradient>
</defs>'''
LINK = (g(p(A, SHADOW) + p(B, SHADOW), 0, 6, 'blur6').replace('<path', '<path opacity=".4"')
        + g(p(A, '#9FE6C3') + p(B, '#7FD5AE'))
        + g(p(A, 'url(#tube)', sw - 3) + p(B, 'url(#tubeB)', sw - 3), -1, -1)
        + g(p(A, '#fff', 3, 'stroke-opacity=".9"') + p(B, '#fff', 3, 'stroke-opacity=".6"'), -3, -3, 'blur1'))
# The link sits at about half the tile's width, with room around it, not edge to edge.
LINK_SCALE = 0.74
LINK = f'<g transform="translate({128 - 128 * LINK_SCALE} {128 - 128 * LINK_SCALE}) scale({LINK_SCALE})">{LINK}</g>'
TILE = '<rect x="8" y="8" width="240" height="240" rx="56" fill="url(#tile)"/><rect x="8" y="8" width="240" height="240" rx="56" fill="url(#sheen)"/>'

def svg(body, size, view='0 0 256 256'):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}" width="{size}" height="{size}">{DEFS}{body}</svg>'
def write(name, text):
    path = os.path.join(OUT, name); open(path, 'w').write(text); return path
def render(svg_path, png_path, size):
    """Headless Chrome, transparent background, exact pixel size."""
    with tempfile.TemporaryDirectory() as tmp:
        html = os.path.join(tmp, 'p.html')
        open(html, 'w').write(f'<html><body style="margin:0;background:transparent"><img src="file://{svg_path}" width="{size}" height="{size}" style="display:block"></body></html>')
        subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
                        '--default-background-color=00000000', f'--window-size={size},{size}', f'--screenshot={png_path}', f'file://{html}'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

# 1. The icon as a tile (store graphics, previews).
tile_svg = write('lync-icon.svg', svg(TILE + LINK, 1024))
render(tile_svg, os.path.join(OUT, 'lync-icon-1024.png'), 1024)
render(tile_svg, os.path.join(OUT, 'play-icon-512.png'), 512)

# 2. Mac: Apple's grid puts an 824 px tile with a soft shadow inside the 1024 canvas.
k = 824 / 240; t = 100 - 8 * k
mac_svg = write('lync-mac-icon.svg', f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">{DEFS}'
                f'<g transform="translate({t} {t}) scale({k})"><g filter="url(#tileShadow)">{TILE}</g>{LINK}</g></svg>')
iconset = os.path.join(OUT, 'AppIcon.iconset'); shutil.rmtree(iconset, ignore_errors=True); os.makedirs(iconset)
for base in (16, 32, 128, 256, 512):
    render(mac_svg, os.path.join(iconset, f'icon_{base}x{base}.png'), base)
    render(mac_svg, os.path.join(iconset, f'icon_{base}x{base}@2x.png'), base * 2)
subprocess.run(['iconutil', '-c', 'icns', iconset, '-o', os.path.join(ROOT, 'mac', 'Resources', 'AppIcon.icns')], check=True)

# 3. Mac menu bar: a flat template (black + alpha), 18×18 pt.
mb = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="34 34 188 188" width="18" height="18">'
      f'<g transform="rotate(-45 128 128)">{p(A, "#000", 24)}{p(B, "#000", 24)}</g></svg>')
mb_svg = write('menubar.svg', mb)
for scale, name in ((1, 'MenuBarIcon.png'), (2, 'MenuBarIcon@2x.png')):
    tmp_png = os.path.join(OUT, name)
    with tempfile.TemporaryDirectory() as tmp:
        html = os.path.join(tmp, 'p.html')
        open(html, 'w').write(f'<html><body style="margin:0;background:transparent"><img src="file://{mb_svg}" width="18" height="18" style="display:block"></body></html>')
        subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', f'--force-device-scale-factor={scale}',
                        '--default-background-color=00000000', '--window-size=18,18', f'--screenshot={tmp_png}', f'file://{html}'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    shutil.copy(tmp_png, os.path.join(ROOT, 'mac', 'Resources', name))

# 4. Android adaptive icon. Foreground: the shaded link inside the 66 dp safe zone of a 108 dp canvas.
fg_scale = 0.74  # LINK is already scaled: about half the visible icon, well inside the 66 dp safe zone
fg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="432" height="432">{DEFS}'
      f'<g transform="translate({128 - 128 * fg_scale} {128 - 128 * fg_scale}) scale({fg_scale})">{LINK}</g></svg>')
fg_svg = write('android-foreground.svg', fg)
res = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res')
for density, px in (('mdpi', 108), ('hdpi', 162), ('xhdpi', 216), ('xxhdpi', 324), ('xxxhdpi', 432)):
    os.makedirs(os.path.join(res, f'mipmap-{density}'), exist_ok=True)
    render(fg_svg, os.path.join(res, f'mipmap-{density}', 'ic_launcher_foreground.png'), px)

def vector(size, viewport, s, width, color, comment):
    tr = viewport / 2 - 128 * s
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} Generated by brand/icon/final.py: edit the script, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp" android:height="{size}dp"
    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">
    <group android:translateX="{tr:.3f}" android:translateY="{tr:.3f}" android:scaleX="{s}" android:scaleY="{s}">
        <group android:rotation="-45" android:pivotX="128" android:pivotY="128">
            <path android:strokeColor="{color}" android:strokeWidth="{width}" android:strokeLineCap="round" android:pathData="{A.replace('H', ' H').replace('A', ' A')}"/>
            <path android:strokeColor="{color}" android:strokeWidth="{width}" android:strokeLineCap="round" android:pathData="{B.replace('H', ' H').replace('A', ' A')}"/>
        </group>
    </group>
</vector>
'''
open(os.path.join(res, 'drawable', 'ic_launcher_monochrome.xml'), 'w').write(
    vector(108, 108, 0.27, sw + 4, '#FF000000', 'Themed-icon layer: the link, flat.'))
open(os.path.join(res, 'drawable', 'ic_stat_wallhop.xml'), 'w').write(
    vector(24, 24, 0.118, 26, '#FFFFFFFF', 'Notification icon: the link, flat and heavier.'))
open(os.path.join(res, 'drawable', 'ic_launcher_background.xml'), 'w').write('''<?xml version="1.0" encoding="utf-8"?>
<!-- Adaptive-icon background: Lync's deep mint. Generated by brand/icon/final.py. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:angle="270" android:startColor="#FF5FD9A0" android:endColor="#FF13875A" />
</shape>
''')
print('done')
