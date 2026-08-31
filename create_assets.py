"""Gera fundos e molduras padrão do photobooth."""
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
BG_DIR = ROOT / "assets" / "backgrounds"
FRAME_DIR = ROOT / "assets" / "frames"
W, H = 1280, 720


def vertical_gradient(c_top, c_bot):
    t = np.linspace(0, 1, H, dtype=np.float32)[:, None]
    channels = [
        (1 - t) * c_top[i] + t * c_bot[i] for i in range(3)
    ]
    return np.repeat(np.stack(channels, axis=-1), W, axis=1).astype(np.uint8)


def add_bokeh(img, n=40, seed=1):
    rng = np.random.default_rng(seed)
    overlay = img.copy()
    for _ in range(n):
        x, y = int(rng.integers(0, W)), int(rng.integers(0, H))
        r = int(rng.integers(20, 90))
        color = tuple(int(c) for c in rng.integers(80, 255, 3))
        cv2.circle(overlay, (x, y), r, color, -1, cv2.LINE_AA)
    return cv2.addWeighted(img, 0.72, overlay, 0.28, 0)


def make_backgrounds():
    BG_DIR.mkdir(parents=True, exist_ok=True)

    studio = add_bokeh(vertical_gradient((72, 18, 48), (140, 70, 180)), 35, 2)
    cv2.imwrite(str(BG_DIR / "estudio.png"), studio)

    praia = vertical_gradient((80, 160, 210), (200, 140, 40))
    cv2.circle(praia, (980, 140), 90, (160, 230, 255), -1, cv2.LINE_AA)
    cv2.imwrite(str(BG_DIR / "praia.png"), praia)

    noite = add_bokeh(vertical_gradient((28, 10, 8), (70, 20, 40)), 55, 7)
    cv2.imwrite(str(BG_DIR / "noite.png"), noite)


def make_frame(name, fill_bgr, accent_bgr, border=78):
    fill = (fill_bgr[2], fill_bgr[1], fill_bgr[0], 255)
    accent = (accent_bgr[2], accent_bgr[1], accent_bgr[0], 255)

    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((6, 6, W - 7, H - 7), radius=34, fill=fill)
    draw.rounded_rectangle(
        (border - 12, border - 12, W - border + 12, H - border + 12),
        radius=24,
        outline=accent,
        width=6,
    )

    arr = np.array(img)
    hole = Image.new("L", (W, H), 0)
    ImageDraw.Draw(hole).rounded_rectangle(
        (border, border, W - border, H - border),
        radius=18,
        fill=255,
    )
    hole_np = np.array(hole)
    arr[hole_np > 0, 3] = 0
    Image.fromarray(arr).save(FRAME_DIR / name)


def make_frames():
    FRAME_DIR.mkdir(parents=True, exist_ok=True)
    make_frame("dourada.png", (48, 160, 212), (150, 230, 255))
    make_frame("rosa.png", (120, 70, 190), (210, 180, 255), border=70)
    make_frame("preta.png", (22, 18, 18), (220, 220, 220), border=64)


if __name__ == "__main__":
    make_backgrounds()
    make_frames()
    print("OK", BG_DIR, FRAME_DIR)
