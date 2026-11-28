#!/usr/bin/env python3
"""The liquid glass shader (app/.../ui/LiquidGlass.kt, LIQUID_GLASS_SKSL) run on the CPU with numpy.

Robolectric compiles the shader in CI but cannot run it on its GPU nor read pixels back, so its optics
are checked here: the same math, line for line, over a picture of the app. Writes a PNG with the bar
(frosted), the start button (tinted) and a held lens (clear, magnifying) drawn over that picture.

usage: preview.py <screen.png> <out.png> [density] [light]
"""
import sys

import numpy as np
from PIL import Image

LIGHT = np.array([-0.34, -0.94])


def bilinear(img, x, y):
    h, w = img.shape[:2]
    x = np.clip(x, 0.5, w - 0.5) - 0.5
    y = np.clip(y, 0.5, h - 0.5) - 0.5
    x0 = np.floor(x).astype(int)
    y0 = np.floor(y).astype(int)
    x1 = np.minimum(x0 + 1, w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    fx = (x - x0)[..., None]
    fy = (y - y0)[..., None]
    a = img[y0, x0] * (1 - fx) + img[y0, x1] * fx
    b = img[y1, x0] * (1 - fx) + img[y1, x1] * fx
    return a * (1 - fy) + b * fy


def box_blur(img, r, passes=3):
    out = img.copy()
    r = max(int(round(r)), 1)
    for _ in range(passes):
        for axis in (0, 1):
            pad = [(0, 0)] * 3
            pad[axis] = (r + 1, r)
            p = np.pad(out, pad, mode="edge")
            c = np.cumsum(p, axis=axis)
            hi = np.take(c, range(2 * r + 1, c.shape[axis]), axis=axis)
            lo = np.take(c, range(0, c.shape[axis] - 2 * r - 1), axis=axis)
            out = (hi - lo) / (2 * r + 1)
    return out


def glass(page, x0, y0, w, h, radius, band, depth, split, frost, blur, mag, tint, vibrance, specular, edge, dark):
    """Draws one piece of glass over `page` (float RGB 0..1) in place, as drawLiquid + the shader do."""
    pad = int(np.ceil(depth * (1 + split) + 2))
    radius = min(radius, w / 2, h / 2)
    band = min(band, max(radius * 0.95, 1))
    # the layer: the backdrop with pad to spare (what drawLiquid records) …
    ys = np.arange(int(y0) - pad, int(np.ceil(y0 + h)) + pad)
    xs = np.arange(int(x0) - pad, int(np.ceil(x0 + w)) + pad)
    sharp = page[np.clip(ys, 0, page.shape[0] - 1)][:, np.clip(xs, 0, page.shape[1] - 1)]
    # … and its blurred quarter-scale copy (the strip)
    small = np.array(Image.fromarray((sharp * 255).astype(np.uint8)).resize(
        (max(sharp.shape[1] // 4, 1), max(sharp.shape[0] // 4, 1)), Image.BILINEAR)) / 255.0
    soft = box_blur(small, blur * 0.25 / 1.7) if frost > 0 else None

    gy, gx = np.mgrid[0:sharp.shape[0], 0:sharp.shape[1]].astype(float) + 0.5
    hs = np.array([w / 2, h / 2])
    px = gx - pad - hs[0]
    py = gy - pad - hs[1]
    r = radius
    qx = np.abs(px) - hs[0] + r
    qy = np.abs(py) - hs[1] + r
    d = np.minimum(np.maximum(qx, qy), 0) + np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) - r
    cover = np.clip(0.5 - d, 0, 1)
    depth_ = np.maximum(-d, 0)
    sx = np.where(px < 0, -1.0, 1.0)
    sy = np.where(py < 0, -1.0, 1.0)
    corner = (qx > 0) & (qy > 0)
    ln = np.hypot(qx, qy) + 1e-9
    nx = np.where(corner, sx * qx / ln, np.where(qx > qy, sx, 0.0))
    ny = np.where(corner, sy * qy / ln, np.where(qx > qy, 0.0, sy))
    x = np.clip(1 - depth_ / max(band, 1), 0, 1)
    smooth = np.clip(depth_ / 1.5, 0, 1)
    smooth = smooth * smooth * (3 - 2 * smooth)
    bend = (1 - np.sqrt(np.maximum(1 - x * x, 0))) * smooth
    bx = gx - px * (mag / (1 + mag))
    by = gy - py * (mag / (1 + mag))
    pushx = nx * bend * depth
    pushy = ny * bend * depth
    col = np.stack([
        bilinear(sharp, bx + pushx * (1 - split), by + pushy * (1 - split))[..., 0],
        bilinear(sharp, bx + pushx, by + pushy)[..., 1],
        bilinear(sharp, bx + pushx * (1 + split), by + pushy * (1 + split))[..., 2],
    ], -1)
    if frost > 0:
        e = 1 - x
        s = np.clip(e, 0, 1)
        f = frost * (0.45 + 0.55 * s * s * (3 - 2 * s))
        sm = bilinear(soft, (bx + pushx) * 0.25, (by + pushy) * 0.25)
        col = col * (1 - f[..., None]) + sm * f[..., None]
    lum = col @ np.array([0.2126, 0.7152, 0.0722])
    col = np.clip(lum[..., None] + (col - lum[..., None]) * vibrance, 0, 1)
    col = col * (1 - tint[3]) + np.array(tint[:3]) * tint[3]
    rim = np.exp(-depth_ / max(edge, 0.5))
    lit = np.maximum(nx * LIGHT[0] + ny * LIGHT[1], 0)
    back = np.maximum(-(nx * LIGHT[0] + ny * LIGHT[1]), 0)
    spec = (lit ** 3 + 0.5 * back ** 3) * rim
    sheen = x * x * (0.035 if dark else 0.055)
    col = np.clip(col + (spec * specular + sheen)[..., None], 0, 1)
    # composite over the page (the shader's output is premultiplied by coverage)
    yy = np.clip(ys, 0, page.shape[0] - 1)
    xx = np.clip(xs, 0, page.shape[1] - 1)
    region = page[np.ix_(yy, xx)]
    page[np.ix_(yy, xx)] = col * cover[..., None] + region * (1 - cover[..., None])


def main():
    src, out = sys.argv[1], sys.argv[2]
    dp = float(sys.argv[3]) if len(sys.argv) > 3 else 3.0
    light = len(sys.argv) > 4 and sys.argv[4] == "light"
    page = np.asarray(Image.open(src).convert("RGB")).astype(float) / 255.0
    page = page.copy()
    H, W = page.shape[:2]
    # the bar and the button as Shell lays them out, over the content in the middle of the screen
    bar_w, bar_h = (3 * 70 + 10) * dp, 66 * dp
    btn = 66 * dp
    gap = 10 * dp
    total = bar_w + gap + 150 * dp
    left = (W - total) / 2
    top = H * 0.40
    dark = 0 if light else 1
    bar_tint = (0xF7 / 255, 0xFB / 255, 0xFA / 255, 0.56) if light else (0x0B / 255, 0x14 / 255, 0x13 / 255, 0.52)
    glass(page, left + 150 * dp + gap, top, bar_w, bar_h, bar_h / 2, 16 * dp, 10 * dp, 0.10, 1.0, 24 * dp, 0.0,
          bar_tint, 1.5, 0.7 if light else 0.55, 1.6 * dp, dark)
    glass(page, left, top, 150 * dp, btn, btn / 2, 14 * dp, 8 * dp, 0.12, 1.0, 18 * dp, 0.0,
          (0x5F / 255, 0xD4 / 255, 0xC4 / 255, 0.80), 1.25, 0.9, 1.5 * dp, dark)
    # a held lens over the bar's first tab, grown out of the bar (growX 0.16, growY 0.28)
    lw, lh = 70 * dp * 1.16, 56 * dp * 1.28
    cx = left + 150 * dp + gap + bar_w - 5 * dp - 35 * dp - 20 * dp
    cy = top + bar_h / 2
    glass(page, cx - lw / 2, cy - lh / 2, lw, lh, lh / 2, 11 * dp, 7 * dp, 0.15, 0.0, 0, 0.15,
          (1, 1, 1, 0.10 if light else 0.035), 1.12, 0.95, 1.2 * dp, dark)
    Image.fromarray((page * 255).astype(np.uint8)).save(out)


if __name__ == "__main__":
    main()
