"""Store screens drawn from Lync's design, not captured from a phone: the phone app's own fonts
(Figtree, Galindo), its default green palette (HomeColors.Default), 24 dp groups, the labelled tab
bar, and sample data with made-up names only. Each screen is 420 × 933 dp at 3× (1260 × 2799,
like a real phone), written to raw/ for screenshots.py to frame and caption.

Run from brand/store:  python3 mockups.py && python3 screenshots.py"""
import os, subprocess, tempfile
HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
RAW = os.path.join(HERE, 'raw'); os.makedirs(RAW, exist_ok=True)
CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
FONTS = os.path.join(ROOT, 'mac', 'Resources')
LINK = os.path.join(ROOT, 'brand', 'icon', 'out', 'android-foreground.svg')

# HomeColors.Default for Lync's green (seed #2FB57E), as the film uses them.
TOP, MID, CARD, PALE, ON_PALE = '#36a175', '#1e4837', '#162820', '#dafbee', '#165a3e'
CODE_CARD, CODE_ACCENT, RED = '#0F2E24', '#7FE3B0', '#ff8a7a'

CSS = f'''
@font-face {{ font-family: Galindo; src: url("file://{FONTS}/Galindo.ttf"); }}
@font-face {{ font-family: Figtree; src: url("file://{FONTS}/Figtree.ttf"); font-weight: 100 900; }}
* {{ box-sizing: border-box; }}
html, body {{ margin: 0; width: 420px; height: 933px; overflow: hidden; font-family: Figtree; color: #fff;
  background: linear-gradient(180deg, {TOP} 0%, {MID} 34%, #0b0b0c 62%); -webkit-font-smoothing: antialiased; }}
.black {{ background: #000; }}
.ms {{ font-family: 'Material Symbols Rounded'; font-weight: normal; font-style: normal; line-height: 1;
  font-variation-settings: 'FILL' 1, 'wght' 500, 'opsz' 24; display: inline-block; }}
.status {{ height: 54px; padding: 18px 28px 0; display: flex; justify-content: space-between; font: 600 15px Figtree; }}
.page {{ padding: 0 16px; }}
.title {{ font: 30px/38px Galindo; margin: 16px 4px 24px; display: flex; align-items: center; gap: 10px; }}
.title .grow {{ flex: 1; }}
.section {{ font: 18px/24px Galindo; margin: 24px 4px 12px; display: flex; align-items: center; gap: 4px; }}
.group {{ background: rgba(255,255,255,.09); border-radius: 24px; overflow: hidden; }}
.row {{ display: flex; align-items: center; gap: 14px; padding: 14px 16px; min-height: 72px; position: relative; }}
.row + .row::before {{ content: ''; position: absolute; left: 74px; right: 0; top: 0; height: 1px; background: rgba(255,255,255,.08); }}
.tile {{ width: 44px; height: 44px; border-radius: 50%; display: grid; place-items: center; flex: none; font-size: 22px; }}
.text {{ flex: 1; min-width: 0; }}
.t {{ font: 500 16px/21px Figtree; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }}
.s {{ font: 500 13px/18px Figtree; color: rgba(255,255,255,.68); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }}
.time {{ font: 500 13px Figtree; color: rgba(255,255,255,.6); align-self: flex-start; margin-top: 4px; }}
.switch {{ width: 52px; height: 32px; border-radius: 99px; background: {PALE}; position: relative; flex: none; }}
.switch::after {{ content: ''; position: absolute; right: 4px; top: 4px; width: 24px; height: 24px; border-radius: 50%; background: {ON_PALE}; }}
.value {{ font: 500 15px Figtree; color: rgba(255,255,255,.68); display: flex; align-items: center; gap: 2px; }}
.chips {{ display: flex; gap: 8px; }}
.chip {{ flex: 1; height: 48px; border-radius: 99px; background: rgba(255,255,255,.13); display: flex; align-items: center;
  justify-content: center; gap: 6px; font: 600 15px Figtree; }}
.chip.on {{ background: {PALE}; color: {ON_PALE}; }}
.tabs {{ position: absolute; left: 12px; right: 12px; bottom: 26px; height: 68px; border-radius: 99px; background: rgba(28,34,31,.94);
  display: flex; align-items: center; padding: 6px; box-shadow: 0 10px 30px rgba(0,0,0,.4); }}
.tab {{ flex: 1; height: 56px; border-radius: 99px; display: flex; align-items: center; justify-content: center; gap: 8px;
  font: 600 16px Figtree; color: rgba(255,255,255,.78); }}
.tab.on {{ background: {PALE}; color: {ON_PALE}; }}
.home-bar {{ position: absolute; left: 50%; bottom: 8px; width: 108px; height: 4px; margin-left: -54px; border-radius: 4px; background: rgba(255,255,255,.85); }}
.pill {{ height: 44px; padding: 0 18px; border-radius: 99px; display: inline-flex; align-items: center; font: 600 16px Figtree; }}
.circle {{ width: 44px; height: 44px; border-radius: 50%; background: rgba(0,0,0,.22); display: grid; place-items: center; font-size: 22px; }}
.day {{ font: 600 13px Figtree; color: rgba(255,255,255,.6); margin: 18px 6px 8px; }}
'''

GREEN_TILE = 'background: rgba(95,217,160,.18); color: #9FE6C3;'
PINK_TILE = 'background: rgba(255,138,160,.16); color: #ffb3c4;'
AMBER_TILE = 'background: rgba(255,190,90,.18); color: #ffcf7a;'
GREY_TILE = 'background: rgba(255,255,255,.14); color: #fff;'

def status(dark=False):
    return '''<div class="status"><span>9:41</span><span class="ms" style="font-size:17px;letter-spacing:2px">signal_cellular_alt wifi battery_full</span></div>'''

def tabs(on):
    def t(name, icon):
        return f'<div class="tab{" on" if name == on else ""}"><span class="ms" style="font-size:22px">{icon}</span>{name}</div>'
    return f'<div class="tabs">{t("Home", "laptop_mac")}{t("Activity", "inbox")}{t("Settings", "settings")}</div><div class="home-bar"></div>'

def row(icon, tile, title, sub=None, trailing='', time=None):
    s = f'<div class="s">{sub}</div>' if sub else ''
    tm = f'<div class="time">{time}</div>' if time else ''
    return f'<div class="row"><div class="tile" style="{tile}"><span class="ms">{icon}</span></div><div class="text"><div class="t">{title}</div>{s}</div>{tm}{trailing}</div>'

SWITCH = '<div class="switch"></div>'
def value(v): return f'<div class="value">{v}<span class="ms" style="font-size:22px;font-variation-settings:\'FILL\' 0">chevron_right</span></div>'

HOME = f'''{status()}<div class="page">
<div class="title"><span class="ms" style="font-size:26px;color:{CODE_ACCENT};transform:rotate(-45deg)">link</span><span class="grow">MacBook Air</span><div class="circle"><span class="ms">more_horiz</span></div></div>
<div style="display:flex;gap:12px;margin-right:-16px">
  <div style="flex:none;width:340px;border-radius:24px;background:{CODE_CARD};padding:20px 20px 18px;box-shadow:inset 0 0 0 1px rgba(127,227,176,.12)">
    <div style="display:flex;align-items:center;gap:8px;font:600 13px Figtree;color:{CODE_ACCENT}"><span class="ms" style="font-size:18px">password</span>Latest code</div>
    <div style="font:600 46px/1.1 Figtree;letter-spacing:2px;margin:14px 0 6px;font-variant-numeric:tabular-nums">482 913</div>
    <div class="s">From Halo Bank · 2 min ago</div>
    <div style="display:flex;gap:8px;margin-top:18px">
      <div class="pill" style="background:{CODE_ACCENT};color:{CODE_CARD}"><span class="ms" style="font-size:20px;margin-right:6px">check</span>Copied on your Mac</div>
    </div>
  </div>
  <div style="flex:none;width:200px;border-radius:24px;background:rgba(255,255,255,.09);padding:20px">
    <div class="t">MacBook Air</div><div class="s">82%, charging</div>
  </div>
</div>
<div class="section">Send to Mac</div>
<div class="chips"><div class="chip"><span class="ms" style="font-size:20px">photo</span>Photos</div><div class="chip"><span class="ms" style="font-size:20px">description</span>Files</div><div class="chip"><span class="ms" style="font-size:20px">content_paste</span>Clipboard</div></div>
<div class="section">Recent<span class="ms" style="font-size:22px;font-variation-settings:'FILL' 0">chevron_right</span></div>
<div class="group">
{row('photo_library', GREY_TILE, '3 photos', 'Sent to your Mac', time='19:59')}
{row('call_missed', PINK_TILE, 'Missed call', 'Mi amor ❤️', time='18:59')}
{row('link', GREY_TILE, 'Link opened on your Mac', 'northwind.app', time='17:59')}
{row('laptop_mac', GREY_TILE, '2 files from your Mac', 'Saved to Downloads › Lync', time='16:20')}
</div></div>{tabs("Home")}'''

ACTIVITY = f'''{status()}<div class="page">
<div class="title"><span class="grow">Activity</span><div class="pill" style="height:48px;background:rgba(0,0,0,.28);box-shadow:inset 0 0 0 1px rgba(255,255,255,.1);color:{RED}">Clear all</div></div>
<div class="chips"><div class="chip on">All</div><div class="chip">Codes</div><div class="chip">Files</div><div class="chip">Calls</div></div>
<div class="day">Today</div>
<div class="group">
{row('password', GREEN_TILE, '482 913', 'From Halo Bank', time='20:57')}
{row('photo_library', GREY_TILE, '3 photos', 'Sent to your Mac', time='19:59')}
{row('call_missed', PINK_TILE, 'Missed call', 'Mi amor ❤️', time='18:59')}
{row('link', GREY_TILE, 'Link opened on your Mac', 'northwind.app', time='17:59')}
</div>
<div class="day">Yesterday</div>
<div class="group">
{row('password', GREEN_TILE, '771 204', 'From Pine Market', time='18:59')}
{row('laptop_mac', GREY_TILE, '2 files from your Mac', 'Saved to Downloads › Lync', time='17:59')}
{row('description', GREY_TILE, 'Invoice.pdf', 'Sent to your Mac', time='16:59')}
{row('password', GREEN_TILE, '903 311', 'From Orbit Mail', time='14:59')}
</div></div>{tabs("Activity")}'''

SETTINGS = f'''{status()}<div class="page">
<div class="title"><span class="grow">Settings</span></div>
<div class="section" style="margin-top:0">On your Mac</div>
<div class="group">
{row('password', GREEN_TILE, 'Codes', 'Only the code, never the message', SWITCH)}
{row('call', GREEN_TILE, 'Calls', "See who's calling", SWITCH)}
{row('sms', GREEN_TILE, 'Texts', 'Read and reply from your Mac', SWITCH)}
</div>
<div class="s" style="white-space:normal;margin:10px 6px 0">Turn on only what you want. Everything goes only to your paired Mac, encrypted.</div>
<div class="section">Notifications</div>
<div class="group">
{row('notifications_active', PINK_TILE, 'Phone notifications on Mac', None, value('3 apps'))}
{row('notifications', PINK_TILE, 'Alerts on this phone', 'Files and text from your Mac', SWITCH)}
{row('ring_volume', AMBER_TILE, 'Find my phone', 'Ring it from your Mac, even on silent', value('On'))}
</div>
<div class="section">Stay connected</div>
<div class="group">
{row('bolt', GREEN_TILE, 'Run in background', 'Stay connected when Lync is closed', SWITCH)}
{row('bluetooth', GREEN_TILE, 'Bluetooth backup', 'Codes and calls still arrive off Wi‑Fi', SWITCH)}
</div></div>{tabs("Settings")}'''

LINK_GLOW = f'''<div style="position:absolute;left:50%;top:{{top}}px;width:300px;height:300px;margin-left:-150px;border-radius:50%;
  background:radial-gradient(closest-side, rgba(95,217,160,.32), rgba(95,217,160,.06) 60%, transparent)"></div>
<img src="file://{LINK}" style="position:absolute;left:50%;top:{{top}}px;width:300px;height:300px;margin-left:-150px">'''

def streaks(cx, cy):
    out = []
    for i, (d, l, o) in enumerate([(70, 44, .9), (110, 36, .7), (95, 30, .55), (150, 40, .45), (135, 26, .35)]):
        off = (i - 2) * 22
        x, y = cx - d * .707 + off * .707, cy + d * .707 + off * .707
        out.append(f'<div style="position:absolute;left:{x:.0f}px;top:{y:.0f}px;width:{l}px;height:4px;border-radius:4px;background:rgba(226,236,231,{o});transform:rotate(-45deg);transform-origin:0 50%"></div>')
    return ''.join(out)

SEND = f'''{status()}<div style="position:absolute;right:20px;top:66px" class="circle"><span class="ms" style="font-variation-settings:'FILL' 0">expand_more</span></div>
{LINK_GLOW.format(top=230)}{streaks(178, 412)}
<div style="position:absolute;left:0;right:0;top:560px;text-align:center"><span style="font:600 64px Figtree;letter-spacing:-1px">63</span><span style="font:600 28px Figtree">%</span><span style="font:500 16px Figtree;color:rgba(255,255,255,.6);margin-left:10px">4.6 of 7.2 MB</span></div>
<div style="position:absolute;left:50%;top:660px;transform:translateX(-50%);display:flex;align-items:center;gap:12px;padding:10px 18px 10px 10px;border-radius:20px;background:rgba(255,255,255,.08);white-space:nowrap">
  <div style="width:44px;height:44px;border-radius:12px;background:linear-gradient(135deg,#4a2d86,#2fb57e,#e0386a)"></div>
  <div><div class="t" style="font-size:15px">3 photos</div><div class="s">IMG_2041.jpg and 2 more</div></div></div>
<div style="position:absolute;left:0;right:0;top:740px;text-align:center" class="s"><span class="ms" style="font-size:16px;vertical-align:-3px">laptop_mac</span> to MacBook Air</div>
<div style="position:absolute;left:20px;right:20px;bottom:44px;height:56px;border-radius:99px;background:rgba(255,255,255,.12);display:grid;place-items:center;font:600 17px Figtree">Cancel</div>
<div class="home-bar"></div>'''

LINKED = f'''{status()}{LINK_GLOW.format(top=250)}
<div style="position:absolute;left:0;right:0;top:600px;text-align:center;font:36px/44px Galindo">You're linked.</div>
<div style="position:absolute;left:0;right:0;top:658px;text-align:center;font:500 16px Figtree;color:rgba(255,255,255,.7)"><span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#5CD680;margin-right:8px;vertical-align:1px"></span>MacBook Air</div>
<div style="position:absolute;left:20px;right:20px;bottom:44px;height:56px;border-radius:99px;background:#5FD9A0;color:#0b2a1d;display:grid;place-items:center;font:600 17px Figtree">Continue</div>
<div class="home-bar"></div>'''

SCREENS = {'01-home': (HOME, False), '02-activity': (ACTIVITY, False), '03-send': (SEND, True), '04-linked': (LINKED, True), '05-settings': (SETTINGS, False)}

for name, (body, black) in SCREENS.items():
    html = f'''<html><head><link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400..600,0..1,0&display=block" rel="stylesheet">
<style>{CSS}</style></head><body class="{'black' if black else ''}" style="position:relative">{body}</body></html>'''
    with tempfile.TemporaryDirectory() as tmp:
        page = os.path.join(tmp, 'p.html'); open(page, 'w').write(html)
        out = os.path.join(RAW, name + '.png')
        subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=3',
                        '--allow-file-access-from-files', '--virtual-time-budget=6000', '--window-size=420,933',
                        f'--screenshot={out}', f'file://{page}'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(out)
