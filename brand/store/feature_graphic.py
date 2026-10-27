"""Play Store feature graphic, 1024 × 500: the link forming out of mint dots (the pairing moment),
the wordmark and one line. Rendered with headless Chrome like the icon (brand/icon/final.py).

Run from brand/store:  python3 feature_graphic.py   → out/feature-graphic.png"""
import math, os, random, subprocess, tempfile
HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
OUT = os.path.join(HERE, 'out'); os.makedirs(OUT, exist_ok=True)
CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
LINK = os.path.join(ROOT, 'brand', 'icon', 'out', 'android-foreground.svg')
FONTS = os.path.join(ROOT, 'mac', 'Resources')

# Dots streaming from the left into the link, thinning out as they arrive (as in the animation).
random.seed(7)
dots = []
for i in range(120):
    t = i / 150
    x = 560 + t * 200 + random.uniform(-26, 26)
    y = 250 + math.sin(t * math.pi * 1.4 + 0.6) * 60 * (1 - t) + random.uniform(-48, 48) * (1 - t * 0.7)
    a = 0.12 + 0.6 * t
    r = 2.6 + 1.6 * t
    dots.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r:.1f}" fill="#7FE3B0" fill-opacity="{a:.2f}"/>')

html = f'''<html><head><style>
@font-face {{ font-family: Galindo; src: url("file://{FONTS}/Galindo.ttf"); }}
@font-face {{ font-family: Figtree; src: url("file://{FONTS}/Figtree.ttf"); font-weight: 100 900; }}
html, body {{ margin: 0; width: 1024px; height: 500px; overflow: hidden; background: #050807; }}
.glow {{ position: absolute; left: 560px; top: -70px; width: 640px; height: 640px; border-radius: 50%;
        background: radial-gradient(closest-side, rgba(95,217,160,.30), rgba(95,217,160,.08) 55%, transparent); }}
.dots {{ position: absolute; left: 0; top: 0; }}
.link {{ position: absolute; left: 520px; top: -110px; width: 720px; height: 720px; }}
.text {{ position: absolute; left: 72px; top: 132px; width: 470px; color: #fff; }}
.word {{ font: 128px/1 Galindo; letter-spacing: -1px; }}
.line {{ margin-top: 22px; font: 500 30px/1.28 Figtree; color: rgba(255,255,255,.74); }}
.line b {{ color: #7FE3B0; font-weight: 600; }}
</style></head><body>
<div class="glow"></div>
<svg class="dots" width="1024" height="500" viewBox="0 0 1024 500">{''.join(dots)}</svg>
<img class="link" src="file://{LINK}">
<div class="text"><div class="word">Lync</div>
<div class="line">Codes, calls and texts from your Android, <b>on your Mac.</b></div></div>
</body></html>'''

with tempfile.TemporaryDirectory() as tmp:
    page = os.path.join(tmp, 'p.html'); open(page, 'w').write(html)
    png = os.path.join(OUT, 'feature-graphic.png')
    subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
                    '--allow-file-access-from-files', '--window-size=1024,500', f'--screenshot={png}', f'file://{page}'],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
# Play wants 24-bit PNG or JPEG: no alpha.
from PIL import Image
Image.open(png).convert('RGB').save(png)
print(png)
