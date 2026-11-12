#!/usr/bin/env python3
"""Writes the drawn assets from tools/glyphs.json (the 1 and 2 of Fraunces at opsz 144, wght 340,
SOFT 100, WONK 1, pulled out with fontTools): the widget numerals as Android vectors, the app
icon (Doppler wavefronts) as an Android vector and as the desktop SVG."""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "android", "app", "src", "main", "res")
glyphs = json.load(open(os.path.join(HERE, "glyphs.json")))

RED, BLUE = "#FF5A3C", "#5B8CFF"


def glyph_vector(ch):
    g = glyphs[ch]
    x0, y0, x1, y1 = g["bounds"]
    pad = 40
    w, h = x1 - x0 + 2 * pad, y1 - y0 + 2 * pad
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- The numeral {ch} from Fraunces (SIL OFL), made by tools/make_art.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{w / 40:.1f}dp" android:height="{h / 40:.1f}dp"
    android:viewportWidth="{w:.1f}" android:viewportHeight="{h:.1f}">
    <group android:translateX="{pad - x0:.1f}" android:translateY="{h - pad + y0:.1f}" android:scaleY="-1">
        <path android:fillColor="#FFFFFFFF" android:pathData="{g['d']}" />
    </group>
</vector>
'''


def mix(a, b, t):
    pa = [int(a[i:i + 2], 16) for i in (1, 3, 5)]
    pb = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * t):02X}" for x, y in zip(pa, pb))


def rings(cx, cy, scale):
    """Wavefronts from a source moving right: each older ring is centred further left."""
    out = []
    n = 6
    for i in range(n):
        t = i / (n - 1)
        r = (8 + i * 6.2) * scale
        x = cx - i * 3.1 * scale
        out.append((x, cy, r, mix(BLUE, RED, t), (2.6 - i * 0.22) * scale))
    return out


def circle_path(x, y, r):
    return f"M{x - r:.2f},{y:.2f}a{r:.2f},{r:.2f} 0 1,0 {2 * r:.2f},0a{r:.2f},{r:.2f} 0 1,0 {-2 * r:.2f},0"


def icon_vector():
    # 108dp adaptive canvas, art inside the 66dp safe circle
    paths = "\n".join(
        f'    <path android:strokeColor="{c}" android:strokeWidth="{w:.2f}" android:pathData="{circle_path(x, y, r)}" />'
        for x, y, r, c, w in rings(60, 54, 0.62))
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Doppler wavefronts, made by tools/make_art.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
{paths}
    <path android:fillColor="#E9E4FF" android:pathData="{circle_path(61.6, 54, 2.4)}" />
</vector>
'''


def icon_svg():
    circles = "\n".join(
        f'  <circle cx="{x:.2f}" cy="{y:.2f}" r="{r:.2f}" fill="none" stroke="{c}" stroke-width="{w:.2f}"/>'
        for x, y, r, c, w in rings(68, 64, 1.12))
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">
  <rect width="128" height="128" rx="28" fill="#000"/>
{circles}
  <circle cx="70.8" cy="64" r="4.2" fill="#E9E4FF"/>
</svg>
'''


def mono_vector():
    paths = "\n".join(
        f'    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="{w:.2f}" android:pathData="{circle_path(x, y, r)}" />'
        for x, y, r, c, w in rings(13, 12, 0.13))
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
{paths}
</vector>
'''


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as handle:
        handle.write(text)


write(os.path.join(RES, "drawable", "glyph_1.xml"), glyph_vector("1"))
write(os.path.join(RES, "drawable", "glyph_2.xml"), glyph_vector("2"))
write(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), icon_vector())
write(os.path.join(RES, "drawable", "ic_notification.xml"), mono_vector())
write(os.path.join(HERE, "..", "desktop", "doppler.svg"), icon_svg())
print("written")
