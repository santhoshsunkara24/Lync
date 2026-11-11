"""Prepares the film's sound effects into public/sfx: from sounds/ (originals, as downloaded) and
sounds/uisfx-minimal (the open-source UI SFX library's "minimal" style, CC0; see its licence there).

For each sound: mono 48 kHz, the silence before it trimmed, the muddy low end taken out (a gentle
high-pass), the peak evened out with the others, and tiny fades so nothing clicks. The film then
sets each one's own quiet level. Run from brand/film:  python3 scripts/prepare_sounds.py
"""
import os
import subprocess
import tempfile
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "sounds")
OUT = os.path.join(ROOT, "public", "sfx")
RATE = 48000

# Film name → (file in sounds/, seconds to keep after the sound starts, or None for all of it).
SOUNDS = {
    "click": ("musicholder-click-sfx-287654.mp3", 0.2),
    "success": ("universfield-bright-notification-352449.mp3", 1.2),
    "toggle": ("milanwulf-toggle-button-off-166328.mp3", 0.35),
    "riser": ("soundreality-riser-hole-391174.mp3", 3.0),
    "logo": ("universfield-logo-reveal-intro-352299.mp3", None),
    "typing": ("desastar-macbook-notebook-keyboard-typing-sound-effect-586600.mp3", 6.0),
    "appear": ("uisfx-minimal/notification.mp3", None),
    "tap": ("uisfx-minimal/select.mp3", None),
    "swipe": ("uisfx-minimal/swipe.mp3", None),
    "check": ("uisfx-minimal/check.mp3", None),
    "open": ("uisfx-minimal/open.mp3", None),
    "connect": ("uisfx-minimal/connect.mp3", None),
    "send": ("uisfx-minimal/send.mp3", None),
    "receive": ("uisfx-minimal/receive.mp3", None),
    "complete": ("uisfx-minimal/complete.mp3", None),
}
PEAK = 0.5


def decode(path):
    """Any audio file as mono floats at 48 kHz (Remotion's bundled ffmpeg)."""
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "x.wav")
        subprocess.run(["npx", "remotion", "ffmpeg", "-y", "-loglevel", "error", "-i", path, "-ac", "1", "-ar", str(RATE), wav], check=True, cwd=ROOT)
        with wave.open(wav) as w:
            return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(float) / 32768


def highpass(x, cutoff=110):
    """One-pole high-pass: takes out rumble and mud under the sound."""
    a = np.exp(-2 * np.pi * cutoff / RATE)
    y = np.zeros_like(x)
    prev_x = prev_y = 0.0
    for i, v in enumerate(x):
        prev_y = a * (prev_y + v - prev_x)
        prev_x = v
        y[i] = prev_y
    return y


def save(name, x):
    data = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(os.path.join(OUT, f"{name}.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data.tobytes())


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, (file, keep) in SOUNDS.items():
        x = decode(os.path.join(SRC, file))
        env = np.abs(x)
        start = max(0, int(np.argmax(env > 0.02 * env.max())) - int(0.01 * RATE))
        # The typing file is long: start in its first steady run of keys, a second in.
        if name == "typing":
            start += RATE
        x = x[start:]
        if keep:
            x = x[: int(keep * RATE)]
        x = highpass(x)
        x = x / (np.max(np.abs(x)) or 1) * PEAK
        fade_in, fade_out = int(0.003 * RATE), int(min(0.08, len(x) / RATE / 4) * RATE)
        x[:fade_in] *= np.linspace(0, 1, fade_in)
        x[-fade_out:] *= np.linspace(1, 0, fade_out)
        save(name, x)
        print(f"{name:8s} {len(x) / RATE:5.2f}s  from {file}")
