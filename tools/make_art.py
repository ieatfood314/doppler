#!/usr/bin/env python3
"""Writes the drawn assets: the widget numerals (the 1 and 2 as stars on a 5 by 7 grid, the same
grid as Dots.kt and desktop/Main.qml) as Android vectors, and the app icon (Doppler wavefronts) as an
Android vector and as the desktop SVG."""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "android", "app", "src", "main", "res")
DOTS = {
    "1": ["..#..", ".##..", "#.#..", "..#..", "..#..", "..#..", "#####"],
    "2": [".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"],
}
# The square widget writes the screen time and the playing key on the same grid: 0 to 9 (the 1 and 2 are the
# ones above), and a small H and M after the hours and minutes like the Nothing launcher's own clock widgets.
TIME = {
    "0": [".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "1": DOTS["1"],
    "2": DOTS["2"],
    "3": [".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."],
    "4": ["...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."],
    "5": ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
    "6": ["..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."],
    "7": ["#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."],
    "8": [".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."],
    "9": [".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."],
    "h": ["#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "m": ["#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"],
}
# The widget's mark for a playlist with no key: one star, on a numeral's grid so it sits the same way.
DOT = [".....", ".....", ".....", "..#..", ".....", ".....", "....."]

RED, BLUE = "#FF5A3C", "#5B8CFF"


def glyph_vector(ch, rows=None, what=None):
    pitch = 10
    rows = rows or DOTS[ch]
    w, h = pitch * len(rows[0]), pitch * len(rows)
    dots = "".join(circle_path((c + 0.5) * pitch, (r + 0.5) * pitch, pitch * 0.33)
                   for r, row in enumerate(rows) for c, on in enumerate(row) if on == "#")
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {what or "The numeral " + ch} as stars, made by tools/make_art.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{w / 2}dp" android:height="{h / 2}dp"
    android:viewportWidth="{w}" android:viewportHeight="{h}">
    <path android:fillColor="#FFFFFFFF" android:pathData="{dots}" />
</vector>
'''


def tight_vector(name, rows):
    """A glyph whose box is exactly its grid of stars, edge to edge, so layouts can line up the ink itself."""
    pitch, r = 10, 3.3
    inset = pitch / 2 - r
    w, h = pitch * len(rows[0]) - 2 * inset, pitch * len(rows) - 2 * inset
    dots = "".join(circle_path((c + 0.5) * pitch - inset, (r_ + 0.5) * pitch - inset, r)
                   for r_, row in enumerate(rows) for c, on in enumerate(row) if on == "#")
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {name} as stars, cropped to its grid, made by tools/make_art.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{w / 2:.2f}dp" android:height="{h / 2:.2f}dp"
    android:viewportWidth="{w:.2f}" android:viewportHeight="{h:.2f}">
    <path android:fillColor="#FFFFFFFF" android:pathData="{dots}" />
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
write(os.path.join(RES, "drawable", "glyph_dot.xml"), glyph_vector("dot", DOT, "One star (a playlist with no key)"))
for ch, rows in TIME.items():
    write(os.path.join(RES, "drawable", f"dots_{ch}.xml"), tight_vector(f"The {ch.upper()}", rows))
write(os.path.join(RES, "drawable", "dots_star.xml"), tight_vector("One star (a playlist with no key)", DOT))
write(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), icon_vector())
write(os.path.join(RES, "drawable", "ic_notification.xml"), mono_vector())
write(os.path.join(HERE, "..", "desktop", "doppler.svg"), icon_svg())
print("written")
