"""Erzeugt App-Icon und Startbildschirm-Schriftzug von Easy Books.

Grüner Ledereinband mit Messingrahmen, Messingecken, rotem Lesebändchen und dem
Monogramm "EB" in Tangerine. Die Buchstaben werden mit fontTools in Pfade umgewandelt,
weil Vektor-Icons keine Schrift enthalten können.

Ausgaben:
  app/src/main/res/drawable/ic_launcher_fg.xml      Vordergrund des adaptiven Icons
  app/src/main/res/drawable/ic_launcher_mono.xml    einfarbiges Icon (Designfarben)
  app/src/main/res/drawable/splash_branding.xml     "Easy Books" unter dem Startbildschirm
  tools/icon/easybooks-icon.svg                     Vorlage (200 x 200, abgerundet)
  tools/icon/easybooks-wordmark.svg                 Schriftzug allein

Das PNG für die README rendert tools/render_icon.ps1 aus der SVG-Vorlage.

Aufruf: python tools/make_icon.py
"""
import os

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.ttLib import TTFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app", "src", "main", "res", "font", "tangerine_bold.ttf")
DRAWABLE = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
ICON_DIR = os.path.join(ROOT, "tools", "icon")

GOLD = "#E2BE6A"
BRASS = "#C9A043"
BRASS_LINE = "#4E3812"

font = TTFont(FONT)
glyphs = font.getGlyphSet()
cmap = font.getBestCmap()


def layout(text, overlap=0.0):
    """Glyphen mit Vorschub; [overlap] schiebt Großbuchstaben ineinander (Monogramm)."""
    x = 0.0
    placed = []
    for ch in text:
        name = cmap[ord(ch)]
        placed.append((name, x))
        x += glyphs[name].width * (1 - overlap)
    return placed


def bounds(placed):
    pen = BoundsPen(glyphs)
    for name, x in placed:
        glyphs[name].draw(TransformPen(pen, (1, 0, 0, 1, x, 0)))
    return pen.bounds


def text_path(text, box, overlap=0.0):
    """SVG-Pfad von [text], eingepasst und zentriert in box = (x, y, w, h), y nach unten."""
    placed = layout(text, overlap)
    x0, y0, x1, y1 = bounds(placed)
    bx, by, bw, bh = box
    s = min(bw / (x1 - x0), bh / (y1 - y0))
    ox = bx + (bw - (x1 - x0) * s) / 2 - x0 * s
    oy = by + (bh - (y1 - y0) * s) / 2 + y1 * s
    pen = SVGPathPen(glyphs, ntos=lambda v: ("%.2f" % v).rstrip("0").rstrip("."))
    for name, x in placed:
        glyphs[name].draw(TransformPen(pen, (s, 0, 0, -s, ox + x * s, oy)))
    return pen.getCommands()


# ------------------------------------------------------------------ Buch (200 x 200)

MONOGRAM = text_path("EB", (75, 60, 62, 78), overlap=0.38)

CORNER = "M0,0H37Q34,7 27,8.5Q20,9.5 17.5,14Q13,16 10.5,21Q8.5,28 8,37Q4,36 0,39Z"
CORNER_SHINE = "M4,4H24Q19,6 15,10Q10,13 8,18Q6,21 4,23"
RIVET = "M5.3,8.5a3.2,3.2 0,1 0,6.4 0a3.2,3.2 0,1 0,-6.4 0z"


def rect(x, y, w, h, r):
    return (f"M{x + r},{y}h{w - 2 * r}a{r},{r} 0,0 1,{r},{r}v{h - 2 * r}a{r},{r} 0,0 1,-{r},{r}"
            f"h-{w - 2 * r}a{r},{r} 0,0 1,-{r},-{r}v-{h - 2 * r}a{r},{r} 0,0 1,{r},-{r}z")


# Jedes Element: (Pfad, Füllung, Linienfarbe, Linienbreite). "leather" = Verlauf.
BOOK = [
    (rect(44, 36, 22, 132, 6), "#16291A", None, 0),
    ("M44,52h22v4h-22z M44,150h22v4h-22z", BRASS, None, 0),
    (rect(48, 64, 14, 24, 1.5) + " " + rect(48, 118, 14, 24, 1.5), "#8E2A1E", None, 0),
    (rect(156, 40, 7, 124, 2), "#E6D3A8", None, 0),
    ("M157.5,44V160 M160,44V160", None, "#B39A66", 0.7),
    (rect(58, 34, 100, 136, 6), "leather", None, 0),
    (rect(68, 45, 80, 114, 2), None, "#D9B562", 2.6),
    ("M73.5,50.5h69v103h-69z", None, "#C9A55A", 0.9),
    (MONOGRAM, GOLD, "#5A4214", 0.6),
    ("M86,143H130", None, "#C9A55A", 1.2),
]
CORNERS = [(58, 34, 0.6, 0.6), (158, 34, -0.6, 0.6), (58, 170, 0.6, -0.6), (158, 170, -0.6, -0.6)]
RIBBON = [
    ("M141,30h12v152l-6,-7 -6,7z", "#9A2E20", None, 0),
    ("M143,30v146", None, "#B3C2493A", 1.1),
]

# ------------------------------------------------------------------ Android


def vd_path(d, fill, stroke, width, indent="        "):
    attrs = [f'android:pathData="{d}"']
    if fill == "leather":
        return (f'{indent}<path {attrs[0]}>\n'
                f'{indent}    <aapt:attr name="android:fillColor">\n'
                f'{indent}        <gradient android:type="linear" android:startX="58" android:startY="34" android:endX="158" android:endY="170">\n'
                f'{indent}            <item android:offset="0" android:color="#FF3E6B44" />\n'
                f'{indent}            <item android:offset="0.6" android:color="#FF25432A" />\n'
                f'{indent}            <item android:offset="1" android:color="#FF17301C" />\n'
                f'{indent}        </gradient>\n'
                f'{indent}    </aapt:attr>\n'
                f'{indent}</path>\n')
    if fill:
        attrs.insert(0, f'android:fillColor="{argb(fill)}"')
    if stroke:
        attrs.insert(0, f'android:strokeWidth="{width}"')
        attrs.insert(0, f'android:strokeColor="{argb(stroke)}"')
    return f'{indent}<path {" ".join(attrs)} />\n'


def argb(color):
    c = color.lstrip("#")
    return "#" + (c if len(c) == 8 else "FF" + c)


VD_HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Erzeugt von tools/make_icon.py, nicht von Hand ändern. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="{w}dp"
    android:height="{h}dp"
    android:viewportWidth="{w}"
    android:viewportHeight="{h}">
'''

# Buch auf die sichere Zone des adaptiven Icons (108 x 108) verkleinern.
ICON_GROUP = '    <group android:scaleX="0.37" android:scaleY="0.37" android:translateX="15.7" android:translateY="16.3">\n'


def foreground():
    out = VD_HEAD.format(w=108, h=108) + ICON_GROUP
    for item in BOOK:
        out += vd_path(*item)
    for tx, ty, sx, sy in CORNERS:
        out += (f'        <group android:translateX="{tx}" android:translateY="{ty}" '
                f'android:scaleX="{sx}" android:scaleY="{sy}">\n')
        out += vd_path(CORNER, BRASS, BRASS_LINE, 0.9, "            ")
        out += vd_path(CORNER_SHINE, None, "#8CFFF1C4", 0.8, "            ")
        out += vd_path(RIVET, "#F1D48A", BRASS_LINE, 0.9, "            ")
        out += "        </group>\n"
    for item in RIBBON:
        out += vd_path(*item)
    return out + "    </group>\n</vector>\n"


def monochrome():
    white = "#FFFFFFFF"
    out = VD_HEAD.format(w=108, h=108) + ICON_GROUP
    out += vd_path(rect(44, 36, 22, 132, 6), white, None, 0)
    out += vd_path(rect(61.5, 37.5, 93, 129, 2.5), None, white, 7)
    out += vd_path("M70,47h76v110h-76z", None, white, 2.6)
    out += vd_path(MONOGRAM, white, None, 0)
    out += vd_path("M141,30h12v152l-6,-7 -6,7z", white, None, 0)
    return out + "    </group>\n</vector>\n"


def branding():
    # Android zeigt das Bild unten auf dem Startbildschirm in 200 x 80 dp.
    out = VD_HEAD.format(w=200, h=80)
    out += vd_path(text_path("Easy Books", (14, 10, 172, 60)), "#EBCD86", None, 0, "    ")
    return out + "</vector>\n"


# ------------------------------------------------------------------ SVG


def svg_path(d, fill, stroke, width):
    attrs = [f'd="{d}"']
    attrs.append(f'fill="{"url(#leather)" if fill == "leather" else (fill or "none")}"')
    if stroke:
        c = stroke if len(stroke) == 7 else "#" + stroke[3:]
        opacity = "" if len(stroke) == 7 else f' stroke-opacity="{int(stroke[1:3], 16) / 255:.2f}"'
        attrs.append(f'stroke="{c}" stroke-width="{width}"{opacity}')
    return f'  <path {" ".join(attrs)}/>\n'


def svg(rounded, size=200):
    radius = 46 if rounded else 0
    out = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 200 200">\n'
           '  <!-- Erzeugt von tools/make_icon.py -->\n'
           '  <defs>\n'
           '    <radialGradient id="bg" cx="0.5" cy="0.46" r="0.72">\n'
           '      <stop offset="0" stop-color="#2F4E31"/><stop offset="0.65" stop-color="#15261A"/><stop offset="1" stop-color="#0B140D"/>\n'
           '    </radialGradient>\n'
           '    <radialGradient id="glow" cx="0.5" cy="0.5" r="0.5">\n'
           '      <stop offset="0" stop-color="#E9B95C" stop-opacity="0.14"/><stop offset="1" stop-color="#E9B95C" stop-opacity="0"/>\n'
           '    </radialGradient>\n'
           '    <linearGradient id="leather" gradientUnits="userSpaceOnUse" x1="58" y1="34" x2="158" y2="170">\n'
           '      <stop offset="0" stop-color="#3E6B44"/><stop offset="0.6" stop-color="#25432A"/><stop offset="1" stop-color="#17301C"/>\n'
           '    </linearGradient>\n'
           '  </defs>\n'
           f'  <rect width="200" height="200" rx="{radius}" fill="url(#bg)"/>\n'
           '  <circle cx="100" cy="100" r="74" fill="url(#glow)"/>\n')
    for item in BOOK:
        out += svg_path(*item)
    for tx, ty, sx, sy in CORNERS:
        out += f'  <g transform="translate({tx} {ty}) scale({sx} {sy})">\n'
        out += "  " + svg_path(CORNER, BRASS, BRASS_LINE, 0.9)
        out += "  " + svg_path(CORNER_SHINE, None, "#8CFFF1C4", 0.8)
        out += "  " + svg_path(RIVET, "#F1D48A", BRASS_LINE, 0.9)
        out += "  </g>\n"
    for item in RIBBON:
        out += svg_path(*item)
    return out + "</svg>\n"


def wordmark_svg():
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="600" height="240" viewBox="0 0 200 80">\n'
            '  <!-- Erzeugt von tools/make_icon.py -->\n'
            f'  <path d="{text_path("Easy Books", (14, 10, 172, 60))}" fill="#EBCD86"/>\n'
            '</svg>\n')


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("geschrieben:", os.path.relpath(path, ROOT))


if __name__ == "__main__":
    write(os.path.join(DRAWABLE, "ic_launcher_fg.xml"), foreground())
    write(os.path.join(DRAWABLE, "ic_launcher_mono.xml"), monochrome())
    write(os.path.join(DRAWABLE, "splash_branding.xml"), branding())
    write(os.path.join(ICON_DIR, "easybooks-icon.svg"), svg(rounded=True))
    write(os.path.join(ICON_DIR, "easybooks-wordmark.svg"), wordmark_svg())
