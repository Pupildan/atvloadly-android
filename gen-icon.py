#!/usr/bin/env python3
"""Generate an iOS-style launcher icon for atvloadly.

Design: rounded-square, diagonal blue gradient (Apple-ish), a white TV /
display glyph with two broadcast arcs above it — representing casting an IPA
to an Apple TV. Drawn at high resolution then downsampled to each mipmap size.
"""
import os
from PIL import Image, ImageDraw

SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
MASTER = 1024  # draw big, downscale for smooth antialiasing


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient_bg(size):
    """Diagonal gradient, top-left indigo -> bottom-right bright blue."""
    img = Image.new("RGB", (size, size))
    px = img.load()
    c1 = (58, 70, 180)    # indigo
    c2 = (90, 140, 245)   # light blue
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = lerp(c1, c2, t)
    return img


def rounded_mask(size, radius_ratio=0.22):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    r = int(size * radius_ratio)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    return m


def draw_glyph(img, s):
    """Draw a white TV/display with broadcast arcs, centered."""
    d = ImageDraw.Draw(img)
    w = (255, 255, 255)

    # Layout box
    cx = s // 2
    # TV body
    tv_w = int(s * 0.46)
    tv_h = int(s * 0.30)
    tv_x0 = cx - tv_w // 2
    tv_y0 = int(s * 0.50)
    tv_x1 = tv_x0 + tv_w
    tv_y1 = tv_y0 + tv_h
    rad = int(s * 0.035)
    d.rounded_rectangle([tv_x0, tv_y0, tv_x1, tv_y1], radius=rad,
                        outline=w, width=int(s * 0.022))

    # Stand
    sw = int(s * 0.16)
    d.line([cx - sw // 2, tv_y1 + int(s * 0.02), cx + sw // 2, tv_y1 + int(s * 0.02)],
           fill=w, width=int(s * 0.022))

    # Broadcast arcs above the TV (centered on the TV top-center)
    arc_cx = cx
    arc_cy = tv_y0
    for i, (rw, alpha) in enumerate([
        (int(s * 0.16), 255),
        (int(s * 0.28), 200),
    ]):
        box = [arc_cx - rw, arc_cy - rw, arc_cx + rw, arc_cy + rw]
        # top quarter arcs (from 200deg to 340deg), like a wifi/cast symbol
        d.arc(box, start=200, end=340, fill=w, width=int(s * 0.022))

    # Dot at the broadcast origin
    dot_r = int(s * 0.028)
    d.ellipse([arc_cx - dot_r, arc_cy - dot_r - int(s*0.02),
               arc_cx + dot_r, arc_cy + dot_r - int(s*0.02)], fill=w)


def make(size):
    bg = gradient_bg(MASTER)
    draw_glyph(bg, MASTER)
    bg = bg.convert("RGBA")
    mask = rounded_mask(MASTER)
    out = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    res = os.path.join(here, "res")
    # generic mipmap
    os.makedirs(os.path.join(res, "mipmap"), exist_ok=True)
    make(48).save(os.path.join(res, "mipmap", "ic_launcher.png"))
    for density, size in SIZES.items():
        d = os.path.join(res, f"mipmap-{density}")
        os.makedirs(d, exist_ok=True)
        make(size).save(os.path.join(d, "ic_launcher.png"))
        print(f"mipmap-{density}: {size}x{size}")
    print("icons generated")


if __name__ == "__main__":
    main()