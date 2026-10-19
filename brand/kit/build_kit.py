"""Lync brand kit — every asset is generated from the geometry below. Run: python3 build_kit.py

Construction (all from the bar height H and the head radius R):
  Bar       height H, corner radius H/5. Full width W in the wide mark; head width + 2 x 0.30R in the compact one.
  Head      circle of radius R centred on the bar's centre line; only the part below the bar shows.
  Eyes      radius R/12 (small cut: 0.115R), centres at ±0.45R from the middle and 0.5R below the head's centre.
  Antennae  width H/5 (small cut: 2.2 x), round ends; each starts on the head's outline directly under an eye
            and leans out at exactly 30°; visible length 0.36R (small cut: 0.40R). Drawn as filled capsules.
Colours: Forest #0F2E24 (field), Mint #7FE3B0 (droid).
"""
import os, json, subprocess, shutil
from math import sqrt, sin, cos, radians, hypot

FOREST, MINT = "#0F2E24", "#7FE3B0"
HERE = os.path.dirname(os.path.abspath(__file__)); OUT = os.path.join(HERE, "out")
SKILL = os.path.expanduser("~/Downloads/logo-design-skill-main/skills/logo-design/scripts")
os.makedirs(OUT, exist_ok=True)
f = lambda v: f"{v:.3f}".rstrip("0").rstrip(".")

class Mark:
    def __init__(self, W=2000, H=170, R=570, small=False, compact=False):
        self.H, self.R = H, R
        self.eye_r = (0.115 if small else 1 / 12) * R
        self.ant_w = H / 5 * (2.2 if small else 1)
        self.ant_len = (0.40 if small else 0.36) * R
        self.half = sqrt(R**2 - (H / 2) ** 2)
        self.W = (2 * self.half + 2 * 0.30 * R) if compact else W
        self.cx, self.cy = self.W / 2, H / 2
        self.eyes = [(self.cx + s * 0.45 * R, self.cy + 0.5 * R) for s in (-1, 1)]

    def body(self, bleed=0):
        """Bar + head outline. bleed > 0 runs the bar past both edges with square ends (for icons)."""
        H, R, W, cx, half, rb = self.H, self.R, self.W, self.cx, self.half, self.H / 5
        if bleed:
            return (f"M{f(-bleed)} 0H{f(W + bleed)}V{f(H)}H{f(cx + half)}A{f(R)} {f(R)} 0 0 1 {f(cx - half)} {f(H)}"
                    f"H{f(-bleed)}Z")
        return (f"M{f(rb)} 0H{f(W - rb)}A{f(rb)} {f(rb)} 0 0 1 {f(W)} {f(rb)}V{f(H - rb)}A{f(rb)} {f(rb)} 0 0 1 {f(W - rb)} {f(H)}"
                f"H{f(cx + half)}A{f(R)} {f(R)} 0 0 1 {f(cx - half)} {f(H)}"
                f"H{f(rb)}A{f(rb)} {f(rb)} 0 0 1 0 {f(H - rb)}V{f(rb)}A{f(rb)} {f(rb)} 0 0 1 {f(rb)} 0Z")

    def holes(self):
        r = self.eye_r
        return "".join(f"M{f(x - r)} {f(y)}A{f(r)} {f(r)} 0 1 0 {f(x + r)} {f(y)}A{f(r)} {f(r)} 0 1 0 {f(x - r)} {f(y)}Z"
                       for x, y in self.eyes)

    def antennae(self):
        """Each antenna as a filled capsule (no strokes in the masters)."""
        w, out, self.bottom, self.caps = self.ant_w, [], 0, []
        for s, (ex, _) in zip((-1, 1), self.eyes):
            root = self.cy + sqrt(self.R**2 - (ex - self.cx) ** 2)
            dx, dy = s * sin(radians(30)), cos(radians(30))
            x0, y0 = ex - dx * w, root - dy * w
            x1, y1 = ex + dx * self.ant_len, root + dy * self.ant_len
            nx, ny = -dy * w / 2, dx * w / 2                 # perpendicular, half width
            r = w / 2
            # A straight bar plus a full circle at each end: the same capsule, built from shapes that can't flip.
            out.append(f"M{f(x0 + nx)} {f(y0 + ny)}L{f(x1 + nx)} {f(y1 + ny)}L{f(x1 - nx)} {f(y1 - ny)}L{f(x0 - nx)} {f(y0 - ny)}Z")
            self.caps = getattr(self, "caps", [])
            for cxp, cyp in ((x0, y0), (x1, y1)):
                self.caps.append(f"M{f(cxp - r)} {f(cyp)}A{f(r)} {f(r)} 0 1 0 {f(cxp + r)} {f(cyp)}A{f(r)} {f(r)} 0 1 0 {f(cxp - r)} {f(cyp)}Z")
            self.bottom = max(self.bottom, y1 + w / 2)
        return "".join(out)

    def cap_paths(self):
        return "".join(self.caps)

    def paths(self, color, bleed=0):
        a = self.antennae()
        return (f'<path fill="{color}" d="{a}"/><path fill="{color}" d="{self.cap_paths()}"/>'
                f'<path fill="{color}" fill-rule="evenodd" d="{self.body(bleed)}{self.holes()}"/>')

def svg(vb, inner, title="Lync"):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}"><title>{title}</title>{inner}</svg>'

def write(name, text):
    p = os.path.join(OUT, name); open(p, "w").write(text); return p

def render(src, dst, w, h=None):
    subprocess.run(["python3", "-I", f"{SKILL}/render_png.py", src, "-o", dst, "--width", str(w), "--height", str(h or w)],
                   check=True, capture_output=True)

# ---- Masters ------------------------------------------------------------------------------------
wide, small, compact, compact_small = Mark(), Mark(small=True), Mark(compact=True), Mark(compact=True, small=True)
for m in (wide, small, compact, compact_small): m.antennae()
def vb(m): return f"0 0 {f(m.W)} {f(m.bottom)}"
for name, m in (("lync-mark", wide), ("lync-mark-small", small), ("lync-compact", compact), ("lync-compact-small", compact_small)):
    write(f"{name}.svg", svg(vb(m), m.paths(MINT)))
    write(f"{name}-forest.svg", svg(vb(m), m.paths(FOREST)))
    write(f"{name}-black.svg", svg(vb(m), m.paths("#000000")))
    write(f"{name}-white.svg", svg(vb(m), m.paths("#FFFFFF")))

# ---- App icon art (square) -----------------------------------------------------------------------
def icon_inner(size=2000, color=MINT, field=FOREST, m=wide):
    """Field + the wide mark at full width; the bar bleeds past the tile edges. Centred on bar-top..chin."""
    s = size / m.W
    chin = m.cy + m.R
    dy = (size / s - chin) / 2 + 20 * (2000 / size) * 0  # centred on the bar and head
    dy = 1000 - chin / 2 + 20
    field_rect = f'<rect width="{size}" height="{size}" fill="{field}"/>' if field else ""
    return f'{field_rect}<g transform="scale({f(s)}) translate(0 {f(dy)})">{m.paths(color, bleed=400)}</g>'

# macOS: Apple's template — 824 px rounded square inset 100 px in a 1024 canvas, corner radius 185.
mac = (f'<defs><clipPath id="sq"><rect x="100" y="100" width="824" height="824" rx="185"/></clipPath></defs>'
       f'<g clip-path="url(#sq)"><g transform="translate(100 100) scale({824/2000})">{icon_inner()}</g></g>')
write("icon-mac.svg", svg("0 0 1024 1024", mac, "Lync app icon"))
write("icon-square.svg", svg("0 0 2000 2000", f'<defs><clipPath id="t"><rect width="2000" height="2000" rx="450"/></clipPath></defs><g clip-path="url(#t)">{icon_inner()}</g>', "Lync app icon"))

# ---- Android adaptive icon (108 x 108 viewport, 66 dp safe circle) ------------------------------
def android_vector(m, color, bleed, scale, dy):
    a = m.antennae()
    def tx(d):  # VectorDrawable has no transforms on paths here; wrap in a group instead
        return d
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by brand/kit/build_kit.py — edit the script, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:scaleX="{f(scale)}" android:scaleY="{f(scale)}" android:translateX="{f(54 - m.cx * scale)}" android:translateY="{f(dy)}">
        <path android:fillColor="{color}" android:pathData="{a}"/>
        <path android:fillColor="{color}" android:pathData="{m.cap_paths()}"/>
        <path android:fillColor="{color}" android:fillType="evenOdd" android:pathData="{m.body(bleed)}{m.holes()}"/>
    </group>
</vector>
"""
# The head plus antennae must sit inside the 66 dp safe circle: head width 2*half, from bar top to antenna tips.
safe = 60.0
scale = safe / max(2 * wide.half, wide.bottom) * 1.0
scale = min(safe / (2 * wide.half), 52 / wide.bottom)
chin = wide.cy + wide.R
dy = 54 - (chin * scale) / 2 + 1
fg = android_vector(wide, "#FF7FE3B0", bleed=wide.W, scale=scale, dy=dy)
mono = android_vector(wide, "#FF000000", bleed=wide.W, scale=scale, dy=dy)
write("ic_launcher_foreground.xml", fg); write("ic_launcher_monochrome.xml", mono)

# Notification icon: 24 dp, white, compact small cut, fitted to a 20 dp live area.
cs = compact_small
ns = min(20 / cs.W, 20 / cs.bottom)
notif = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by brand/kit/build_kit.py — edit the script, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <group android:scaleX="{f(ns)}" android:scaleY="{f(ns)}" android:translateX="{f(12 - cs.W * ns / 2)}" android:translateY="{f(12 - cs.bottom * ns / 2)}">
        <path android:fillColor="#FFFFFFFF" android:pathData="{cs.antennae()}"/>
        <path android:fillColor="#FFFFFFFF" android:pathData="{cs.cap_paths()}"/>
        <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{cs.body()}{cs.holes()}"/>
    </group>
</vector>
"""
write("ic_stat_lync.xml", notif)

# ---- Wordmark lockup: "Lync" in Figtree SemiBold, outlined ---------------------------------------
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
font = TTFont(os.path.join(HERE, "..", "..", "mac", "Resources", "Figtree.ttf"))
if "fvar" in font: font = instancer.instantiateVariableFont(font, {"wght": 600})
gs, cmap, upm = font.getGlyphSet(), font.getBestCmap(), font["head"].unitsPerEm
def word(text, size, tracking=-0.03):
    pen = SVGPathPen(gs); x = 0; scale = size / upm
    for ch in text:
        g = cmap[ord(ch)]
        gs[g].draw(TransformPen(pen, (scale, 0, 0, -scale, x, 0)))
        x += (gs[g].width + tracking * upm) * scale
    return pen.getCommands(), x - tracking * size
cap = font["OS/2"].sCapHeight
# Lockup: the capitals are 55% of the bar-to-chin height and centred on it (the thin antennae hang below,
# like a descender); the gap to the word is 0.40R.
m = compact; mark_h = m.bottom; chin = m.cy + m.R
size = chin * 0.55 / (cap / upm)
d, width = word("Lync", size)
gap = m.R * 0.40
base = chin / 2 + (cap / upm * size) / 2
lock_w = m.W + gap + width
for name, mc, tc in (("lockup", MINT, MINT), ("lockup-forest", FOREST, FOREST), ("lockup-black", "#000", "#000"), ("lockup-white", "#fff", "#fff")):
    write(f"lync-{name}.svg", svg(f"0 0 {f(lock_w)} {f(mark_h)}",
          m.paths(mc) + f'<path fill="{tc}" transform="translate({f(m.W + gap)} {f(base)})" d="{d}"/>', "Lync"))

# ---- Raster exports ------------------------------------------------------------------------------
iconset = os.path.join(OUT, "AppIcon.iconset"); os.makedirs(iconset, exist_ok=True)
render(os.path.join(OUT, "icon-mac.svg"), os.path.join(OUT, "icon-mac-1024.png"), 1024)
from PIL import Image
big = Image.open(os.path.join(OUT, "icon-mac-1024.png")).convert("RGBA")
for pt in (16, 32, 128, 256, 512):
    for k in (1, 2):
        px = pt * k; name = f"icon_{pt}x{pt}{'@2x' if k == 2 else ''}.png"
        big.resize((px, px), Image.LANCZOS).save(os.path.join(iconset, name))
subprocess.run(["iconutil", "-c", "icns", iconset, "-o", os.path.join(OUT, "AppIcon.icns")], check=True)

mb = compact_small
# Menu bar template: compact small cut, 18 pt tall; exported at @2x (36 px) and @1x (18 px).
for k in (1, 2):
    h = 18 * k
    render(os.path.join(OUT, "lync-compact-small-black.svg"), os.path.join(OUT, f"MenuBarIcon{'@2x' if k == 2 else ''}.png"), round(h * mb.W / mb.bottom), h)
# ---- Google Play listing -------------------------------------------------------------------------
# Icon: 512 px, full square with no corners (Play applies its own mask). Feature graphic: 1024 x 500,
# Forest field with the mint lockup centred at 56% of the width.
write("play-icon.svg", svg("0 0 2000 2000", icon_inner(), "Lync app icon"))
render(os.path.join(OUT, "play-icon.svg"), os.path.join(OUT, "play-icon-512.png"), 512)
fw, fh = 1024, 500
ls = fw * 0.56 / lock_w
lx, ly = (fw - lock_w * ls) / 2, (fh - mark_h * ls) / 2
feature = (f'<rect width="{fw}" height="{fh}" fill="{FOREST}"/><g transform="translate({f(lx)} {f(ly)}) scale({f(ls)})">'
           + m.paths(MINT) + f'<path fill="{MINT}" transform="translate({f(m.W + gap)} {f(base)})" d="{d}"/></g>')
write("play-feature.svg", svg(f"0 0 {fw} {fh}", feature, "Lync"))
render(os.path.join(OUT, "play-feature.svg"), os.path.join(OUT, "play-feature-1024x500.png"), fw, fh)
Image.open(os.path.join(OUT, "play-icon-512.png")).convert("RGB").save(os.path.join(OUT, "play-icon-512.png"))
Image.open(os.path.join(OUT, "play-feature-1024x500.png")).convert("RGB").save(os.path.join(OUT, "play-feature-1024x500.png"))

print("built", len(os.listdir(OUT)), "files · android scale", round(scale, 4), "· notif scale", round(ns, 4))
