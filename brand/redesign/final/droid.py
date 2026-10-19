"""Lync notch droid — every dimension derives from the bar height H and the head radius R.

  Bar      full width W, height H, corner radius H/5.
  Head     circle of radius R centred on the bar's centre line (x = W/2, y = H/2); only the part below the
           bar shows. Its sides meet the bar's lower edge at x = W/2 ± sqrt(R² − (H/2)²).
  Eyes     radius R/12, centres at x = W/2 ± 0.45R, y = H/2 + 0.5R.
  Antennae stroke H/5 (the bar's corner radius), round ends; each starts on the head's outline directly
           below an eye and leans outwards at exactly 30° from vertical; visible length 0.36R.
"""
import json, os, sys
from math import sqrt, sin, cos, radians
P = json.loads(os.environ.get("D") or "{}")
W, H, R = P.get("W", 2000), P.get("H", 170), P.get("R", 570)
EYE_DX, EYE_DY, EYE_R = P.get("eye_dx", 0.45), P.get("eye_dy", 0.5), P.get("eye_r", 1/12)
ANT_DEG, ANT_LEN = P.get("ant_deg", 30), P.get("ant_len", 0.36)
GREEN = P.get("green", "#3DDC84")

cx, cy = W / 2, H / 2
rb = H / 5                                   # bar corner radius = antenna stroke
half = sqrt(R**2 - (H / 2)**2)               # where the head meets the bar's lower edge
eye = [(cx + s * EYE_DX * R, cy + EYE_DY * R) for s in (-1, 1)]
er = EYE_R * R
f = lambda v: f"{v:.2f}".rstrip("0").rstrip(".")

def circle(x, y, r):
    return f"M{f(x-r)} {f(y)}A{f(r)} {f(r)} 0 1 0 {f(x+r)} {f(y)}A{f(r)} {f(r)} 0 1 0 {f(x-r)} {f(y)}Z"

def outline():
    return (f"M{f(rb)} 0H{f(W-rb)}A{f(rb)} {f(rb)} 0 0 1 {f(W)} {f(rb)}V{f(H-rb)}A{f(rb)} {f(rb)} 0 0 1 {f(W-rb)} {f(H)}"
            f"H{f(cx+half)}A{f(R)} {f(R)} 0 0 1 {f(cx-half)} {f(H)}"
            f"H{f(rb)}A{f(rb)} {f(rb)} 0 0 1 0 {f(H-rb)}V{f(rb)}A{f(rb)} {f(rb)} 0 0 1 {f(rb)} 0Z")

def antennae(color, width=None):
    w = width or rb; out = []
    for s, (ex, _) in zip((-1, 1), eye):
        root_y = cy + sqrt(R**2 - (ex - cx)**2)          # the head's outline directly below the eye
        dx, dy = s * sin(radians(ANT_DEG)), cos(radians(ANT_DEG))
        x0, y0 = ex - dx * w, root_y - dy * w            # start one stroke inside, so the join is seamless
        x1, y1 = ex + dx * ANT_LEN * R, root_y + dy * ANT_LEN * R
        out.append(f'<line x1="{f(x0)}" y1="{f(y0)}" x2="{f(x1)}" y2="{f(y1)}" stroke="{color}" stroke-width="{f(w)}" stroke-linecap="round"/>')
    return "".join(out), y1 + w / 2

def mark(color=GREEN):
    ants, bottom = antennae(color)
    holes = "".join(circle(x, y, er) for x, y in eye)
    return f'{ants}<path fill="{color}" fill-rule="evenodd" d="{outline()}{holes}"/>', bottom

def construction():
    body, bottom = mark("#3DDC84")
    g = [f'<g opacity="0.55">{body}</g>']
    red = 'fill="none" stroke="#E2453C" stroke-width="4"'
    g.append(f'<line x1="0" y1="{f(cy)}" x2="{W}" y2="{f(cy)}" stroke="#E2453C" stroke-width="3" stroke-dasharray="14 10"/>')
    g.append(f'<circle cx="{f(cx)}" cy="{f(cy)}" r="{f(R)}" {red} stroke-dasharray="14 10"/>')
    g.append(f'<circle cx="{f(cx)}" cy="{f(cy)}" r="9" fill="#E2453C"/>')
    for x, y in eye:
        g.append(f'<line x1="{f(x)}" y1="{f(y)}" x2="{f(x)}" y2="{f(cy + sqrt(R**2-(x-cx)**2))}" stroke="#E2453C" stroke-width="3" stroke-dasharray="8 8"/>')
    return "".join(g), bottom

if __name__ == "__main__":
    body, bottom = mark()
    vb_h = bottom
    open("droid.svg", "w").write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {f(vb_h)}"><title>Lync</title>{body}</svg>')
    b2, _ = mark("#000")
    open("droid-black.svg", "w").write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {f(vb_h)}"><title>Lync</title>{b2}</svg>')
    c, _ = construction()
    pad = R * 0.1
    open("construction.svg", "w").write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{-pad:.0f} {-R+cy-pad:.0f} {W+2*pad:.0f} {2*R+2*pad:.0f}"><rect x="-9999" y="-9999" width="99999" height="99999" fill="#F7F5F2"/>{c}</svg>')
    print("viewBox height", round(vb_h, 1), "· head meets bar at x =", round(cx - half, 1), "and", round(cx + half, 1))
