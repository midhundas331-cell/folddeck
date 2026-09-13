#!/usr/bin/env python3
"""Generate the FoldDeck launcher icon: white pixel art on a transparent field.

The mark is the app itself -- a screen above, a keyboard deck below, and the gap
between them is the crease. Drawn on a 16x16 grid and scaled with nearest
neighbour so the pixels stay square and crisp at every density instead of going
soft the way a resampled bitmap would.

    python3 make_icon.py
"""
from __future__ import annotations

import os

from PIL import Image

# 16x16. '#' is an opaque white pixel, '.' is transparent.
ART = [
    "................",
    "................",
    "..############..",
    "..#..........#..",
    "..#..........#..",
    "..#..........#..",
    "..#..........#..",
    "..############..",
    "................",   # the crease
    ".##############.",
    ".#............#.",
    ".#.##########.#.",
    ".#............#.",
    ".##############.",
    "................",
    "................",
]

# Launcher densities. Adaptive icons are 108dp; the inner 72dp is the safe zone,
# so the art is inset to survive whatever mask One UI applies.
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
SAFE_FRACTION = 72 / 108


def render(grid: list[str]) -> Image.Image:
    img = Image.new("RGBA", (len(grid[0]), len(grid)), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(grid):
        for x, ch in enumerate(row):
            if ch == "#":
                px[x, y] = (255, 255, 255, 255)
    return img


def scale(base: Image.Image, size: int, inset: float = 1.0) -> Image.Image:
    """Nearest-neighbour scale, optionally inset inside a transparent canvas."""
    art_px = int(size * inset)
    art_px -= art_px % base.width          # keep an integer pixel ratio
    art_px = max(art_px, base.width)
    scaled = base.resize((art_px, art_px), Image.NEAREST)
    if art_px == size:
        return scaled
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    off = (size - art_px) // 2
    canvas.paste(scaled, (off, off))
    return canvas


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    base = render(ART)

    for density, size in LEGACY.items():
        out = os.path.join(here, "res", f"mipmap-{density}")
        os.makedirs(out, exist_ok=True)
        scale(base, size).save(os.path.join(out, "ic_launcher.png"))

    for density, size in ADAPTIVE.items():
        out = os.path.join(here, "res", f"mipmap-{density}")
        os.makedirs(out, exist_ok=True)
        scale(base, size, SAFE_FRACTION).save(os.path.join(out, "ic_launcher_fg.png"))

    print(f"wrote {len(LEGACY)} legacy + {len(ADAPTIVE)} adaptive-foreground icons")
    print("preview:")
    for row in ART:
        print("   " + row.replace("#", "██").replace(".", "  "))


if __name__ == "__main__":
    main()
