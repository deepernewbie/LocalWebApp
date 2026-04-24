"""Generate Android launcher icons and wordmark drawables from icon/logo.png.

Called by the GitHub Actions build workflow before Gradle runs.
"""
from PIL import Image, ImageDraw
import os
import numpy as np

SRC = 'icon/logo.png'
if not os.path.exists(SRC):
    raise SystemExit(f"ERROR: {SRC} not found. Upload the logo sheet to icon/logo.png")

RES = 'app/src/main/res'
PANEL_COLOR = (15, 22, 38, 255)  # #0F1626 — matches logo's dark panel

im = Image.open(SRC).convert('RGB')
w, h = im.size
sx = w / 1536
sy = h / 1024
print(f"source: {w}x{h}")

# ========== Wordmark lockup (from dark-panel version) ==========
# The dark rectangle in the bottom-right of the source sheet contains the
# "CouchFlow" lockup with its original typography. We extract it with its
# own dark background, which matches our app's bg_dark color.
panel = im.crop((int(800 * sx), int(640 * sy),
                 int(1500 * sx), int(860 * sy)))
panel_arr = np.array(panel)
ref = np.array([15, 22, 38])
diff = np.abs(panel_arr.astype(int) - ref).sum(axis=2)
content = diff > 40
ys, xs = np.where(content)
if len(xs) == 0:
    raise SystemExit("Could not locate lockup content")
x1, x2 = xs.min(), xs.max()
y1, y2 = ys.min(), ys.max()
pad = 8
lockup = panel.crop((max(0, x1 - pad), max(0, y1 - pad),
                     min(panel.width, x2 + pad),
                     min(panel.height, y2 + pad)))
print(f"lockup: {lockup.size}")

wm_densities = {'mdpi': 40, 'hdpi': 60, 'xhdpi': 80, 'xxhdpi': 120, 'xxxhdpi': 160}
for name, hh in wm_densities.items():
    ww = int(lockup.width * (hh / lockup.height))
    d = f'{RES}/drawable-{name}'
    os.makedirs(d, exist_ok=True)
    lockup.resize((ww, hh), Image.LANCZOS).save(f'{d}/logo_wordmark.png')
    print(f"Wordmark {name}: {ww}x{hh}")

# ========== Couch mark for launcher (from light version) ==========
# Top-left of sheet has the couch mark on white bg. Strip the white.
# The </> glyph renders in dark navy on white — we recolor it to white
# after extraction so it's visible on the dark launcher tile.
mark = im.crop((int(200 * sx), int(245 * sy),
                int(615 * sx), int(430 * sy)))
rgba = mark.convert('RGBA')
arr = np.array(rgba)

# White -> transparent
white_mask = (arr[:, :, 0] > 240) & (arr[:, :, 1] > 240) & (arr[:, :, 2] > 240)
arr[white_mask, 3] = 0

# Near-white -> partial transparency for anti-aliased edges
near_white = ((arr[:, :, 0] > 220) & (arr[:, :, 1] > 220) &
              (arr[:, :, 2] > 220) & (~white_mask))
brightness = (arr[near_white, 0].astype(int) +
              arr[near_white, 1] +
              arr[near_white, 2]) / 3
arr[near_white, 3] = (255 - (brightness - 220) / 20 * 255).clip(0, 255).astype('uint8')

# Dark </> -> recolor to white so it shows on dark tile
dark = ((arr[:, :, 3] > 50) &
        (arr[:, :, 0] < 80) & (arr[:, :, 1] < 80) & (arr[:, :, 2] < 80))
arr[dark, 0] = 255
arr[dark, 1] = 255
arr[dark, 2] = 255
mark_rgba = Image.fromarray(arr, 'RGBA')

densities = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}

def make_icon(size, circular=False):
    bg = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    mask = Image.new('L', (size, size), 0)
    d = ImageDraw.Draw(mask)
    if circular:
        d.ellipse((0, 0, size - 1, size - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, size - 1, size - 1),
                            radius=int(size * 0.22), fill=255)
    tile = Image.new('RGBA', (size, size), PANEL_COLOR)
    bg.paste(tile, (0, 0), mask)
    mw = int(size * (0.72 if circular else 0.80))
    mh = int(mw * (mark_rgba.height / mark_rgba.width))
    mr = mark_rgba.resize((mw, mh), Image.LANCZOS)
    bg.paste(mr, ((size - mw) // 2, (size - mh) // 2), mr)
    return bg

for name, size in densities.items():
    d = f'{RES}/mipmap-{name}'
    os.makedirs(d, exist_ok=True)
    make_icon(size, False).save(f'{d}/ic_launcher.png')
    make_icon(size, True).save(f'{d}/ic_launcher_round.png')
    print(f"Launcher {name}: {size}x{size}")

# Adaptive icon foreground (API 26+)
fg_size = 432
fg_mw = int(fg_size * 0.70)
fg_mh = int(fg_mw * (mark_rgba.height / mark_rgba.width))
fg = Image.new('RGBA', (fg_size, fg_size), (0, 0, 0, 0))
mr = mark_rgba.resize((fg_mw, fg_mh), Image.LANCZOS)
fg.paste(mr, ((fg_size - fg_mw) // 2, (fg_size - fg_mh) // 2), mr)
fg.save(f'{RES}/mipmap-xxxhdpi/ic_launcher_foreground.png')

# Adaptive icon background (solid panel color)
os.makedirs(f'{RES}/drawable', exist_ok=True)
with open(f'{RES}/drawable/ic_launcher_background.xml', 'w') as f:
    f.write('<vector xmlns:android="http://schemas.android.com/apk/res/android" '
            'android:width="108dp" android:height="108dp" '
            'android:viewportWidth="108" android:viewportHeight="108">'
            '<path android:pathData="M0,0h108v108h-108z" '
            'android:fillColor="#0F1626" /></vector>')

# Adaptive icon wrapper XML
os.makedirs(f'{RES}/mipmap-anydpi-v26', exist_ok=True)
adaptive = ('<?xml version="1.0" encoding="utf-8"?>'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">'
            '<background android:drawable="@drawable/ic_launcher_background" />'
            '<foreground android:drawable="@mipmap/ic_launcher_foreground" />'
            '</adaptive-icon>')
with open(f'{RES}/mipmap-anydpi-v26/ic_launcher.xml', 'w') as f:
    f.write(adaptive)
with open(f'{RES}/mipmap-anydpi-v26/ic_launcher_round.xml', 'w') as f:
    f.write(adaptive)

# Remove stale foreground vector if it exists
stale = f'{RES}/drawable/ic_launcher_foreground.xml'
if os.path.exists(stale):
    os.remove(stale)

print("All visual assets generated.")