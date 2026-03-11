#!/usr/bin/env python3
"""
Generate the expanded clipboard background texture for the Tablet Expansion addon.
Run from the project root: python3 scripts/generate_texture.py

Requires: pip install Pillow
"""
import os
from PIL import Image, ImageDraw

OUT_PATH = os.path.join(
    os.path.dirname(__file__), "..",
    "src", "main", "resources",
    "assets", "tabletexpansion", "textures", "gui",
    "expanded_clipboard.png"
)

W, H = 256, 400

# ── Colours matching Create's clipboard palette ──────────────────────────────
C_OUTER    = (107,  83,  56, 255)   # dark wooden outer frame
C_MID      = (139, 108,  74, 255)   # medium wood ring
C_PARCHMENT= (236, 222, 176, 255)   # warm parchment fill
C_CONTENT  = (245, 237, 212, 255)   # lighter inner content area
C_RULE     = (205, 189, 150, 255)   # subtle horizontal rule
C_CLIP_M   = (160, 144, 128, 255)   # metal clipboard clip
C_CLIP_L   = (186, 175, 168, 255)   # clip highlight
C_TRANS    = (0, 0, 0, 0)           # transparent (corners)

img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# ── Background layers ─────────────────────────────────────────────────────────
draw.rectangle([0, 0, W-1, H-1], fill=C_OUTER)
draw.rectangle([1, 1, W-2, H-2], fill=C_MID)
draw.rectangle([3, 3, W-4, H-4], fill=C_PARCHMENT)

# Round the outer corners very slightly (3×3 transparent circles)
for cx, cy in [(0,0), (W-3,0), (0,H-3), (W-3,H-3)]:
    draw.rectangle([cx, cy, cx+2, cy+2], fill=C_TRANS)

# Content area (lighter)
CONTENT_PAD_LEFT = 22
CONTENT_START_Y  = 36
draw.rectangle(
    [CONTENT_PAD_LEFT, CONTENT_START_Y, W - 18, H - 32],
    fill=C_CONTENT
)

# ── Metal clipboard clip (top-centre) ─────────────────────────────────────────
clipX = W // 2 - 18
clipY = 0
draw.rectangle([clipX,   clipY,   clipX+36, clipY+11], fill=C_CLIP_M)
draw.rectangle([clipX+1, clipY+1, clipX+35, clipY+10], fill=C_CLIP_L)
# Clip fastener tongue
draw.rectangle([clipX+6, clipY+8, clipX+30, clipY+18], fill=C_CLIP_M)
draw.rectangle([clipX+8, clipY+9, clipX+28, clipY+17], fill=C_PARCHMENT)

# ── Separator under title ─────────────────────────────────────────────────────
draw.line([(8, CONTENT_START_Y - 4), (W - 9, CONTENT_START_Y - 4)], fill=C_RULE, width=1)

# ── Bottom button-strip separator ─────────────────────────────────────────────
draw.line([(8, H - 30), (W - 9, H - 30)], fill=C_RULE, width=1)

# ── Decorative corner rosettes (top-left & top-right) ────────────────────────
def rosette(cx, cy, r=4):
    draw.ellipse([cx-r, cy-r, cx+r, cy+r], fill=C_MID)
    draw.ellipse([cx-r+1, cy-r+1, cx+r-1, cy+r-1], fill=C_CLIP_L)

rosette(10, 26)
rosette(W - 11, 26)

# ── Save ──────────────────────────────────────────────────────────────────────
os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
img.save(OUT_PATH)
print(f"Texture saved → {OUT_PATH}  ({W}×{H} px)")
