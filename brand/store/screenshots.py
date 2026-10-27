"""Play Store screenshots, 1080 × 1920 (9:16): each raw phone screenshot on Lync's dark ground
with a one-line caption above it. Play allows at most 2:1, and the phone's own 1260 × 2800 is
taller than that, so the real screen goes inside a frame rather than being cropped.

Raw captures go in brand/store/raw/ (NN-name.png, taken with sample data:
`adb shell am start … --ez demo true`). Run from brand/store:  python3 screenshots.py"""
import glob, os, subprocess, tempfile
from PIL import Image
HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
RAW = os.path.join(HERE, 'raw'); OUT = os.path.join(HERE, 'out', 'screenshots'); os.makedirs(OUT, exist_ok=True)
CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
FONTS = os.path.join(ROOT, 'mac', 'Resources')

CAPTIONS = {
    'home': ('Your Mac,', 'one tap away.'),
    'codes': ('Codes on your Mac,', 'ready to paste.'),
    'send': ('Photos and files,', 'straight to your Mac.'),
    'linked': ('Scan once.', "You're linked."),
    'settings': ('Every feature has', 'its own switch.'),
    'activity': ('Everything you sent,', 'in one place.'),
}

def frame(raw, top, bottom, out):
    html = f'''<html><head><style>
@font-face {{ font-family: Galindo; src: url("file://{FONTS}/Galindo.ttf"); }}
html, body {{ margin: 0; width: 1080px; height: 1920px; overflow: hidden;
  background: radial-gradient(120% 70% at 50% 0%, #10261c 0%, #050807 60%); }}
.cap {{ position: absolute; top: 120px; width: 100%; text-align: center; color: #fff;
  font: 84px/1.08 Galindo; letter-spacing: -.5px; }}
.cap span {{ color: #7FE3B0; }}
.shot {{ position: absolute; left: 50%; top: 430px; transform: translateX(-50%); width: 700px;
  border-radius: 64px; box-shadow: 0 30px 90px rgba(0,0,0,.6), 0 0 0 10px #1a1f1d; }}
</style></head><body>
<div class="cap">{top}<br><span>{bottom}</span></div>
<img class="shot" src="file://{raw}">
</body></html>'''
    with tempfile.TemporaryDirectory() as tmp:
        page = os.path.join(tmp, 'p.html'); open(page, 'w').write(html)
        subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
                        '--allow-file-access-from-files', '--window-size=1080,1920', f'--screenshot={out}', f'file://{page}'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    Image.open(out).convert('RGB').save(out)  # Play wants no alpha.

# The phone's own status bar (54 dp at density 3) carries its owner's icons: cropped off.
STATUS_BAR_PX = 162

for raw in sorted(glob.glob(os.path.join(RAW, '*.png'))):
    name = os.path.basename(raw)[:-4]
    key = name.split('-', 1)[-1]
    top, bottom = CAPTIONS.get(key, ('Lync', ''))
    out = os.path.join(OUT, name + '.png')
    with tempfile.TemporaryDirectory() as tmp:
        clean = os.path.join(tmp, 'clean.png')
        im = Image.open(raw).convert('RGB')
        im.crop((0, STATUS_BAR_PX, im.width, im.height)).save(clean)
        frame(clean, top, bottom, out)
    print(out)
