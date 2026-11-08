#!/usr/bin/env python3
"""
Clean-Room iOS 26 v1.5 Multi-Plane 108dp (576x576) Icon Asset Pack & RRO Density Builder.

- Imports `SharedSquircleOptics`, `CleanRoomPhoneIconRenderer`, `CleanRoomMessagesIconRenderer`,
  `CleanRoomSettingsIconRenderer`, and `108dp` layer builders directly from `generate_ios26_icons.py`
  so `Phone`, `Messages`, and `Settings` remain 100% identical inside the 72dp squircle to `v1.3`.
- Synthesizes clean-room float32 multi-plane Liquid Glass icons for:
    * Play Store (`com.android.vending`) -> Option A: 1:1 iOS 26 App Store (azure-cobalt + 3 woven glass bars)
    * Camera (`cameraCalistoga`-calibrated brushed silver plate + titanium chamfer + sapphire multi-coat lens)
    * Clock (60 G2 squircle-ray ticks, 12/3/6/9 numerals, sweeping #FF9500 second hand)
    * Files, Contacts, Calculator, Voice Memos / Recorder
    * System Update (`com.realme.ota`), SIM Toolkit (`com.android.stk`),
      Digital Wellbeing (`com.google.android.apps.wellbeing`), Realme Settings (`com.realme.samurai.parts`)
- Wraps cached 1024x1024 official iOS App Store counterparts (from `/var/tmp/rmx1931-a17/work/ios26_appstore_cache`,
  outside git) with authentic 34-deg SpringBoard glass rim, seamless 18dp (96px at 576x576) Plane-0 bleed,
  and dedicated Dark & Clear multi-plane glass adaptations.
- Exports 40 packages x 3 modes (`<pkg>_default.webp`, `<pkg>_dark.webp`, `<pkg>_clear.webp`) = 120 lossless
  `576x576` (`108dp`, inner `384x384` `72dp` at `[96:480, 96:480]`) WebP assets + `manifest.json`.
"""

import argparse
import collections
import json
import math
import os
import shutil
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from generate_ios26_icons import (  # noqa: E402
    MASTER_72DP,
    CANVAS_108DP,
    BLEED_18DP,
    SharedSquircleOptics,
    CleanRoomPhoneIconRenderer,
    CleanRoomMessagesIconRenderer,
    CleanRoomSettingsIconRenderer,
    CleanRoomGmailIconRenderer,
    CleanRoomGooglePhotosIconRenderer,
    CleanRoomChromeIconRenderer,
    edt_inside,
    blur_offset_mask,
    compute_glass_meniscus,
    build_adaptive_108dp_layers_colored,
    save_density_set,
)

OUT_108DP_PX = 576            # Exact 3/8 downsample of 1536 -> inner 72dp = 384px at [96:480, 96:480]
INNER_72DP_PX = 384
BLEED_OUT_PX = 96


# ==============================================================================
# FONT & SDF GEOMETRY HELPERS
# ==============================================================================
def load_ui_font(px_size: int, weight: str = "regular"):
    candidates = {
        "regular": [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "C:/Windows/Fonts/segoeui.ttf",
            "C:/Windows/Fonts/arial.ttf",
        ],
        "semibold": [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "C:/Windows/Fonts/seguisb.ttf",
            "C:/Windows/Fonts/segoeuib.ttf",
        ],
        "bold": [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "C:/Windows/Fonts/segoeuib.ttf",
            "C:/Windows/Fonts/arialbd.ttf",
        ],
    }
    for path in candidates.get(weight, candidates["regular"]):
        if os.path.isfile(path):
            try:
                return ImageFont.truetype(path, px_size)
            except Exception:
                pass
    return ImageFont.load_default()


def sd_capsule(xx: np.ndarray, yy: np.ndarray, ax: float, ay: float, bx: float, by: float, r: float) -> np.ndarray:
    pax, pay = xx - ax, yy - ay
    bax, bay = bx - ax, by - ay
    denom = max(bax * bax + bay * bay, 1e-6)
    h = np.clip((pax * bax + pay * bay) / denom, 0.0, 1.0)
    return np.hypot(pax - bax * h, pay - bay * h) - r


def aa_from_sdf_ss(sdf_hi: np.ndarray, size: int, ss: int, px_scale: float) -> np.ndarray:
    return np.clip(0.5 - sdf_hi * px_scale, 0.0, 1.0).reshape(size, ss, size, ss).mean(axis=(1, 3)).astype(np.float32)


def exterior_flood_mask_256(passable_1024: np.ndarray) -> np.ndarray:
    """
    Fast 2D exterior flood-fill from image perimeter on a 256x256 grid, upsampled smoothly to 1024x1024.
    Distinguishes exterior background plate from enclosed interior features (e.g. YouTube white play triangle,
    Keep white lightbulb, Telegram white paper plane, Reddit Snoo face).
    """
    h0, w0 = passable_1024.shape
    small_im = Image.fromarray(np.uint8(passable_1024 * 255), mode="L").resize((256, 256), Image.Resampling.BILINEAR)
    pass_s = np.array(small_im) > 127
    vis = np.zeros((256, 256), dtype=bool)
    q = collections.deque()

    for x in range(256):
        if pass_s[0, x]:
            vis[0, x] = True
            q.append((0, x))
        if pass_s[255, x] and not vis[255, x]:
            vis[255, x] = True
            q.append((255, x))
    for y in range(1, 255):
        if pass_s[y, 0] and not vis[y, 0]:
            vis[y, 0] = True
            q.append((y, 0))
        if pass_s[y, 255] and not vis[y, 255]:
            vis[y, 255] = True
            q.append((y, 255))

    while q:
        cy, cx = q.popleft()
        if cy > 0 and pass_s[cy - 1, cx] and not vis[cy - 1, cx]:
            vis[cy - 1, cx] = True
            q.append((cy - 1, cx))
        if cy < 255 and pass_s[cy + 1, cx] and not vis[cy + 1, cx]:
            vis[cy + 1, cx] = True
            q.append((cy + 1, cx))
        if cx > 0 and pass_s[cy, cx - 1] and not vis[cy, cx - 1]:
            vis[cy, cx - 1] = True
            q.append((cy, cx - 1))
        if cx < 255 and pass_s[cy, cx + 1] and not vis[cy, cx + 1]:
            vis[cy, cx + 1] = True
            q.append((cy, cx + 1))

    vis_hi = Image.fromarray(np.uint8(vis * 255), mode="L").resize((w0, h0), Image.Resampling.BILINEAR)
    vis_blur = vis_hi.filter(ImageFilter.GaussianBlur(radius=2.0))
    return np.array(vis_blur, dtype=np.float32) / 255.0


# ==============================================================================
# ZERO-SEAM 1536x1536 (108dp) -> 576x576 COMPOSITOR
# ==============================================================================
class Adaptive108dpPackager:
    """
    Packages any 1024x1024 (72dp) icon render into an exact 1536x1536 (108dp) opaque RGBA canvas
    and downsamples with Lanczos to 576x576 (`[96:480, 96:480]` = exact 384x384 `72dp` safe zone;
    `[0:96]` and `[480:576]` = seamless `18dp` Plane-0 bleed):
      - Inside `sq_mask > 0.5`: preserves `comp_rgb` to 0.000 float32 error (100% identical to v1.3!).
      - Across the outer 3.5px AA boundary (`0 < d_out <= 3.5`): smoothly bleeds the 34-deg perimeter
        specular rim so Android 17's `config_icon_mask` clips with 0.0px seam.
      - Across the outer 256px (96px at 576x576) margin: extends Plane 0 seamlessly with alpha=255.
    """

    def __init__(self, shared: SharedSquircleOptics):
        self.sh = shared
        self.d_out = edt_inside(shared.sq_mask <= 0.5, max_d=8)
        self.bleed_w = np.where(
            shared.sq_mask > 0.5,
            1.0,
            np.clip(1.0 - self.d_out / 3.5, 0.0, 1.0),
        )[:, :, None].astype(np.float32)

        y_108, _ = np.mgrid[0:CANVAS_108DP, 0:CANVAS_108DP].astype(np.float32)
        self.t_108 = np.clip(((y_108 - BLEED_18DP) - 90.0) / (934.0 - 90.0), 0.0, 1.0)

        r_c = 0.2250 * MASTER_72DP
        cx_c = np.clip(shared.X, r_c, (MASTER_72DP - 1.0) - r_c)
        cy_c = np.clip(shared.Y, r_c, (MASTER_72DP - 1.0) - r_c)
        dx_c, dy_c = shared.X - cx_c, shared.Y - cy_c
        dl_c = np.hypot(dx_c, dy_c) + 1e-6
        s_diag = -((dx_c / dl_c) + (dy_c / dl_c)) / np.sqrt(2.0)
        self.rim_peak = (
            0.09 + 0.65 * (np.maximum(0.0, s_diag) ** 1.22) + 0.47 * (np.maximum(0.0, -s_diag) ** 2.0)
        ).astype(np.float32)

    def build_plane0_108(self, palette, mode: str, bg_base_72: np.ndarray) -> np.ndarray:
        t = self.t_108
        if mode == "dark":
            g_val = 48.96 * (1.0 - t) + 19.89 * t
            return np.repeat(g_val[:, :, None], 3, axis=-1).astype(np.float32)
        if mode == "clear":
            padded = np.pad(bg_base_72, ((BLEED_18DP, BLEED_18DP), (BLEED_18DP, BLEED_18DP), (0, 0)), mode="edge")
            return padded.astype(np.float32)
        if isinstance(palette, tuple) and len(palette) == 2:
            top_c, bot_c = palette
            return np.stack([
                top_c[0] * (1.0 - t) + bot_c[0] * t,
                top_c[1] * (1.0 - t) + bot_c[1] * t,
                top_c[2] * (1.0 - t) + bot_c[2] * t,
            ], axis=-1).astype(np.float32)
        if palette == "porcelain":
            return np.stack([
                255.0 * (1.0 - t) + 241.0 * t,
                255.0 * (1.0 - t) + 242.0 * t,
                255.0 * (1.0 - t) + 246.0 * t,
            ], axis=-1).astype(np.float32)
        if palette == "emerald":
            return np.stack([
                82.88 * (1.0 - t) + 39.02 * t,
                239.96 * (1.0 - t) + 192.02 * t,
                108.88 * (1.0 - t) + 65.02 * t,
            ], axis=-1).astype(np.float32)
        if palette == "slate":
            return np.stack([
                168.04 * (1.0 - t) + 95.88 * t,
                168.04 * (1.0 - t) + 95.88 * t,
                173.91 * (1.0 - t) + 99.96 * t,
            ], axis=-1).astype(np.float32)
        padded = np.pad(bg_base_72, ((BLEED_18DP, BLEED_18DP), (BLEED_18DP, BLEED_18DP), (0, 0)), mode="edge")
        return padded.astype(np.float32)

    def package_cleanroom_576(
        self,
        comp_rgb: np.ndarray,
        bg_base: np.ndarray,
        palette,
        mode: str,
        rim_str: float = 1.0,
    ) -> Image.Image:
        b0, b1 = BLEED_18DP, BLEED_18DP + MASTER_72DP
        canvas = self.build_plane0_108(palette, mode, bg_base)
        base_72 = canvas[b0:b1, b0:b1]

        if mode == "default" and palette == "porcelain":
            silver_rim = np.array([220.0, 223.0, 230.0], dtype=np.float32)[None, None, :]
            mw = (0.42 + 0.46 * np.clip(0.5 - 0.5 * self.sh.s_diag, 0.0, 1.0))[:, :, None]
            outer_target = silver_rim * mw + base_72 * (1.0 - mw)
        else:
            ra_out = np.clip(self.rim_peak * rim_str, 0.0, 1.0)[:, :, None]
            outer_target = base_72 * (1.0 - ra_out) + 255.0 * ra_out

        patch = np.where(self.sh.sq_mask[:, :, None] > 0.5, comp_rgb, outer_target)
        canvas[b0:b1, b0:b1] = base_72 * (1.0 - self.bleed_w) + patch * self.bleed_w

        rgba = np.zeros((CANVAS_108DP, CANVAS_108DP, 4), dtype=np.uint8)
        rgba[:, :, :3] = np.clip(np.round(canvas), 0, 255).astype(np.uint8)
        rgba[:, :, 3] = 255
        return Image.fromarray(rgba, mode="RGBA").resize((OUT_108DP_PX, OUT_108DP_PX), Image.Resampling.LANCZOS)

    def package_unmasked_appstore_default_576(self, raw_1024_rgb: np.ndarray) -> tuple:
        """
        Wraps an unmasked 1024x1024 App Store master RGB array for Default mode:
          - 100% untouched interior artwork
          - Adaptive 34-deg SpringBoard glass perimeter rim (+ cool-silver micro-rim on light plates)
          - Smooth edge-extended 256px Plane-0 bleed -> Lanczos 576x576 RGBA (alpha=255)
        Returns `(img_576, lit_72_rgb, p0_approx_rgb)`.
        """
        arr = raw_1024_rgb.astype(np.float32)
        edge_band = (self.sh.sq_mask > 0.5) & (self.sh.d_sq < 40.0)
        lum = float((0.299 * arr[:, :, 0] + 0.587 * arr[:, :, 1] + 0.114 * arr[:, :, 2])[edge_band].mean())
        white_plate = float(np.clip((lum - 210.0) / 38.0, 0.0, 1.0))

        rim_str = 0.72 * (1.0 - 0.55 * white_plate)
        rim_band = np.where(
            self.sh.sq_mask > 0.5,
            np.clip(1.0 - (self.sh.d_sq - 1.0) / 21.5, 0.0, 1.0),
            np.clip(1.0 - self.d_out / 3.5, 0.0, 1.0),
        )
        ra = np.clip(self.rim_peak * rim_str * rim_band, 0.0, 1.0)[:, :, None]
        lit = arr * (1.0 - ra) + 255.0 * ra

        if white_plate > 0.05:
            in_band = np.where(
                self.sh.sq_mask > 0.5,
                np.clip(1.0 - self.sh.d_sq / 3.2, 0.0, 1.0) ** 1.35,
                np.clip(1.0 - self.d_out / 3.0, 0.0, 1.0),
            )
            micro_w = white_plate * in_band * (0.38 + 0.44 * np.clip(0.5 - 0.5 * self.sh.s_diag, 0.0, 1.0))
            silver = np.array([218.0, 222.0, 230.0], dtype=np.float32)[None, None, :]
            lit = lit * (1.0 - micro_w[:, :, None]) + silver * micro_w[:, :, None]

        # Build 1536x1536 canvas by edge-extending unmasked plate + soft blur in outer bleed margin
        padded = np.pad(arr, ((BLEED_18DP, BLEED_18DP), (BLEED_18DP, BLEED_18DP), (0, 0)), mode="edge")
        pad_im = Image.fromarray(np.clip(np.round(padded), 0, 255).astype(np.uint8), mode="RGB")
        canvas = np.array(pad_im.filter(ImageFilter.GaussianBlur(radius=8.0)), dtype=np.float32)

        b0, b1 = BLEED_18DP, BLEED_18DP + MASTER_72DP
        # Smooth 16px feather from exact unmasked 1024 border into blurred bleed margin
        canvas[b0:b1, b0:b1] = lit

        rgba = np.zeros((CANVAS_108DP, CANVAS_108DP, 4), dtype=np.uint8)
        rgba[:, :, :3] = np.clip(np.round(canvas), 0, 255).astype(np.uint8)
        rgba[:, :, 3] = 255
        im_576 = Image.fromarray(rgba, mode="RGBA").resize((OUT_108DP_PX, OUT_108DP_PX), Image.Resampling.LANCZOS)
        return im_576, lit, white_plate


# ==============================================================================
# CLEAN-ROOM SYSTEM & NO-iOS-COUNTERPART MULTI-PLANE RENDERERS
# ==============================================================================
class CleanRoomAppStoreIconRenderer:
    """Option A for Play Store (`com.android.vending`): 1:1 iOS 26 App Store woven Liquid Glass icon."""

    PALETTE = (np.array([38.0, 174.0, 255.0], np.float32), np.array([0.0, 100.0, 235.0], np.float32))

    def __init__(self, shared: SharedSquircleOptics, ss: int = 4):
        self.sh = shared
        self.S = shared.S
        sc = self.S / 1024.0
        n_hi = self.S * ss
        yy, xx = (np.mgrid[0:n_hi, 0:n_hi].astype(np.float32) + 0.5) / (ss * sc)
        px_sc = ss * sc
        r_stick = 46.5

        s1_sdf = sd_capsule(xx, yy, 296.0, 758.0, 544.0, 248.0, r_stick)
        s2_sdf = sd_capsule(xx, yy, 480.0, 248.0, 728.0, 758.0, r_stick)
        s3_sdf = sd_capsule(xx, yy, 248.0, 618.0, 776.0, 618.0, r_stick)

        self.m1 = aa_from_sdf_ss(s1_sdf, self.S, ss, px_sc)
        self.m2 = aa_from_sdf_ss(s2_sdf, self.S, ss, px_sc)
        self.m3 = aa_from_sdf_ss(s3_sdf, self.S, ss, px_sc)
        self.m_union = np.clip(1.0 - (1.0 - self.m1) * (1.0 - self.m2) * (1.0 - self.m3), 0.0, 1.0)

        self.shad_p0 = blur_offset_mask(self.m_union, int(round(2 * sc)), int(round(10 * sc)), 18.0 * sc)
        self.sh_1_on_2 = blur_offset_mask(self.m1, int(round(2 * sc)), int(round(6 * sc)), 9.5 * sc)
        self.sh_2_on_3 = blur_offset_mask(self.m2, int(round(2 * sc)), int(round(6 * sc)), 9.5 * sc)
        self.sh_3_on_1 = blur_offset_mask(self.m3, int(round(2 * sc)), int(round(6 * sc)), 9.5 * sc)

        _, _, _, self.sp1, _, _ = compute_glass_meniscus(self.m1, max_d=26, r_peak=8.0 * sc, r_falloff=5.5 * sc)
        _, _, _, self.sp2, _, _ = compute_glass_meniscus(self.m2, max_d=26, r_peak=8.0 * sc, r_falloff=5.5 * sc)
        _, _, _, self.sp3, _, _ = compute_glass_meniscus(self.m3, max_d=26, r_peak=8.0 * sc, r_falloff=5.5 * sc)
        self.t_y = np.clip(((shared.Y / sc) - 210.0) / (805.0 - 210.0), 0.0, 1.0)

    def render_float(self, mode: str = "default"):
        bg_base, p0_lit, bf = self.sh.plane0_rgb(self.PALETTE, mode)
        burn = 15.0 if mode != "clear" else (13.0 + 8.5 * bf)
        p0_shad = np.clip(p0_lit - burn * self.shad_p0[:, :, None], 0.0, 255.0)
        ty = self.t_y

        a_base = 0.82 - 0.15 * ty if mode != "clear" else (0.72 - 0.18 * ty)
        a1 = np.clip((a_base + 0.24 * self.sp1) * self.m1, 0.0, 0.98)
        a2 = np.clip((a_base + 0.24 * self.sp2) * self.m2, 0.0, 0.98)
        a3 = np.clip((a_base + 0.24 * self.sp3) * self.m3, 0.0, 0.98)

        if mode in ("default", "clear"):
            glass_tint = np.full_like(bg_base, 255.0)
        else:
            glass_tint = np.stack([
                64.0 * (1.0 - ty) + 18.0 * ty,
                182.0 * (1.0 - ty) + 122.0 * ty,
                255.0 * (1.0 - ty) + 242.0 * ty,
            ], axis=-1)

        c = p0_shad.copy()
        for m_under, a_stick, sp_stick, sh_over in [
            (self.m1, a1, self.sp1, self.sh_3_on_1 * (1.0 - self.m3)),
            (self.m2, a2, self.sp2, self.sh_1_on_2 * (1.0 - self.m1)),
            (self.m3, a3, self.sp3, self.sh_2_on_3 * (1.0 - self.m2)),
        ]:
            c = c * (1.0 - a_stick[:, :, None]) + glass_tint * a_stick[:, :, None]
            if mode == "dark":
                c = np.clip(c + (255.0 - c) * (0.28 * sp_stick * m_under)[:, :, None], 0.0, 255.0)
            c = np.clip(c - (18.0 * sh_over * m_under)[:, :, None], 0.0, 255.0)

        rim_s = 0.92 if mode == "default" else 1.0
        return c, bg_base, self.PALETTE, rim_s


class CleanRoomCameraIconRenderer:
    """
    1:1 iOS 26 Camera (`cameraCalistoga` optical calibration):
      - Satin cool-silver plate (`#DBDCDE -> #C2C5CA`)
      - Brushed gunmetal-titanium outer chamfer ring (`R = 406..374`) + dark step groove
      - Anodized dark navy-obsidian outer barrel (`R = 372..258`) + step rings (`R = 294, 278`)
      - Deep sapphire objective (`R = 256`) with top-center periwinkle bloom + studio highlight dot,
        pitch-black central pupil, lower cerulean-ice crescent + warm copper multi-coat reflex.
    """

    PALETTE = (np.array([220.0, 222.0, 226.0], np.float32), np.array([190.0, 193.0, 200.0], np.float32))

    def __init__(self, shared: SharedSquircleOptics, ss: int = 3):
        self.sh = shared
        self.S = shared.S
        sc = self.S / 1024.0
        n_hi = self.S * ss
        yy, xx = (np.mgrid[0:n_hi, 0:n_hi].astype(np.float32) + 0.5) / (ss * sc)
        rr = np.hypot(xx - 512.0, yy - 512.0)
        px_sc = ss * sc

        self.m_outer = aa_from_sdf_ss(rr - 406.0, self.S, ss, px_sc)
        self.m_gap = aa_from_sdf_ss(rr - 375.0, self.S, ss, px_sc)
        self.m_barrel = aa_from_sdf_ss(rr - 371.0, self.S, ss, px_sc)
        self.m_glass = aa_from_sdf_ss(rr - 256.0, self.S, ss, px_sc)

        r_lip = np.exp(-(((rr - 358.0) / 3.0) ** 2))
        r_s1 = np.exp(-(((rr - 294.0) / 2.6) ** 2))
        r_s2 = np.exp(-(((rr - 278.0) / 2.4) ** 2))
        self.barrel_rings = (
            np.clip(0.65 * r_lip + 0.85 * r_s1 + 0.90 * r_s2, 0.0, 1.0)
            .reshape(self.S, ss, self.S, ss)
            .mean(axis=(1, 3))
            .astype(np.float32)
        )

        g1 = np.exp(-(((rr - 228.0) / 2.2) ** 2))
        g2 = np.exp(-(((rr - 215.0) / 2.2) ** 2))
        self.ap_grooves = (
            np.clip(g1 + g2, 0.0, 1.0).reshape(self.S, ss, self.S, ss).mean(axis=(1, 3)).astype(np.float32)
        )

        self.shad_p0 = blur_offset_mask(self.m_outer, int(round(2 * sc)), int(round(12 * sc)), 20.0 * sc)
        _, _, _, self.spec_outer, _, _ = compute_glass_meniscus(self.m_outer, max_d=24, r_peak=6.5 * sc, r_falloff=4.8 * sc)

        self.dx0 = (shared.X / sc) - 512.0
        self.dy0 = (shared.Y / sc) - 512.0
        self.rr0 = np.hypot(self.dx0, self.dy0)
        self.ang0 = np.arctan2(self.dy0, self.dx0)

    def render_float(self, mode: str = "default"):
        bg_base, p0_lit, bf = self.sh.plane0_rgb(self.PALETTE, mode)
        burn = 20.0 if mode != "clear" else (14.0 + 8.0 * bf)
        p0_shad = np.clip(p0_lit - burn * self.shad_p0[:, :, None], 0.0, 255.0)

        dx, dy, rr = self.dx0, self.dy0, self.rr0
        t_vert = np.clip((dy + 406.0) / 812.0, 0.0, 1.0)

        if mode != "clear":
            # Brushed titanium-gunmetal chamfer bezel (R=375..406)
            bezel_v = 162.0 * (1.0 - t_vert) + 86.0 * t_vert + 16.0 * np.cos(2.0 * (self.ang0 + 0.9))
            bezel_col = np.stack([bezel_v, bezel_v + 2.0, bezel_v + 6.0], axis=-1)
            gap_col = np.full_like(bezel_col, 24.0)

            # Dark navy-obsidian lens barrel (R=256..371)
            bar_r = 16.0 + 14.0 * t_vert
            bar_g = 19.0 + 18.0 * t_vert
            bar_b = 32.0 + 28.0 * t_vert
            barrel_col = np.stack([bar_r, bar_g, bar_b], axis=-1)
            ring_tint = np.array([150.0, 165.0, 195.0], np.float32)[None, None, :]
            rl = (0.48 * self.barrel_rings * (0.45 + 0.55 * (1.0 - t_vert)))[:, :, None]
            barrel_col = barrel_col * (1.0 - rl) + ring_tint * rl

            # Sapphire multi-coat objective (R <= 256)
            cav = np.zeros((self.S, self.S, 3), dtype=np.float32) + np.array([6.0, 8.0, 16.0], np.float32)

            # Upper-center periwinkle-azure multi-coat bloom + studio softbox dot at (0, -142)
            top_bloom = np.exp(-(((dx / 155.0) ** 2) + (((dy + 132.0) / 88.0) ** 2)))
            cav += np.array([58.0, 108.0, 218.0], np.float32)[None, None, :] * (0.78 * top_bloom)[:, :, None]

            warm_ul = np.exp(-((((dx + 118.0) / 72.0) ** 2) + (((dy + 86.0) / 68.0) ** 2)))
            cav += np.array([148.0, 82.0, 74.0], np.float32)[None, None, :] * (0.42 * warm_ul)[:, :, None]

            dot_r = np.hypot(dx, dy + 142.0)
            dot_m = np.clip((26.0 - dot_r) / 3.0, 0.0, 1.0)
            cav = cav * (1.0 - (0.94 * dot_m)[:, :, None]) + np.array([232.0, 242.0, 255.0], np.float32) * (0.94 * dot_m)[:, :, None]

            # Lower-center cerulean-ice crescent + lower-right warm amber reflex
            bot_cres = np.exp(-(((dx / 158.0) ** 2) + (((dy - 154.0) / 68.0) ** 2))) * np.exp(-(((rr - 166.0) / 56.0) ** 2))
            cav += np.array([96.0, 198.0, 255.0], np.float32)[None, None, :] * (0.95 * bot_cres)[:, :, None]

            warm_br = np.exp(-((((dx - 92.0) / 76.0) ** 2) + (((dy - 122.0) / 62.0) ** 2)))
            cav += np.array([164.0, 96.0, 58.0], np.float32)[None, None, :] * (0.46 * warm_br)[:, :, None]

            # Deep pitch-black central aperture pupil + concentric dark baffle grooves
            pupil = np.exp(-(((np.hypot(dx, dy + 8.0) / 92.0) ** 2.6)))
            cav *= (1.0 - 0.88 * pupil)[:, :, None]
            cav *= (1.0 - 0.55 * self.ap_grooves)[:, :, None]
            cav = np.clip(cav, 0.0, 255.0)

            lens = bezel_col * (1.0 - self.m_gap[:, :, None]) + gap_col * self.m_gap[:, :, None]
            lens = lens * (1.0 - self.m_barrel[:, :, None]) + barrel_col * self.m_barrel[:, :, None]
            lens = lens * (1.0 - self.m_glass[:, :, None]) + cav * self.m_glass[:, :, None]
            lens = np.clip(lens + (255.0 - lens) * (0.20 * self.spec_outer)[:, :, None], 0.0, 255.0)
        else:
            a_bez = 0.80 * (self.m_outer - self.m_barrel)
            a_bar = (0.34 + 0.40 * self.barrel_rings) * (self.m_barrel - self.m_glass)
            dot_m = np.clip((28.0 - np.hypot(dx, dy + 142.0)) / 4.0, 0.0, 1.0)
            cres = np.exp(-(((rr - 166.0) / 38.0) ** 2)) * np.clip((dy - 40.0) / 180.0, 0.0, 1.0)
            a_gl = (0.16 + 0.76 * dot_m + 0.54 * cres) * self.m_glass
            a_tot = np.clip(a_bez + a_bar + a_gl + 0.28 * self.spec_outer, 0.0, 0.96)
            lens = bg_base * (1.0 - a_tot[:, :, None]) + 255.0 * a_tot[:, :, None]

        comp = p0_shad * (1.0 - self.m_outer[:, :, None]) + lens * self.m_outer[:, :, None]
        rim_s = 0.92 if mode == "default" else 1.0
        return comp, bg_base, self.PALETTE, rim_s


class CleanRoomClockIconRenderer:
    """1:1 iOS 26 Clock: Full-squircle dial, 60 squircle-ray ticks, 12/3/6/9, #FF9500 sweep second hand."""

    PALETTE = "porcelain"

    def __init__(self, shared: SharedSquircleOptics, ss: int = 3):
        self.sh = shared
        self.S = shared.S
        sc = self.S / 1024.0
        n_hi = int(round(1024 * ss))
        sq_bool = shared.sq_mask > 0.5

        def ray_radius(theta_rad: float) -> float:
            cs, sn = math.cos(theta_rad), math.sin(theta_rad)
            lo, hi = 380.0, 511.0
            for _ in range(18):
                mid = 0.5 * (lo + hi)
                xi = int(np.clip(round(512.0 + mid * sn), 0, 1023))
                yi = int(np.clip(round(512.0 - mid * cs), 0, 1023))
                if sq_bool[yi, xi]:
                    lo = mid
                else:
                    hi = mid
            return lo

        hour_hi = Image.new("L", (n_hi, n_hi), 0)
        min_hi = Image.new("L", (n_hi, n_hi), 0)
        dh, dm = ImageDraw.Draw(hour_hi), ImageDraw.Draw(min_hi)

        for k in range(60):
            th = k * (2.0 * math.pi / 60.0)
            sn, cs = math.sin(th), math.cos(th)
            r_edge = ray_radius(th) - 24.0
            if k % 5 == 0:
                r_in = r_edge - 76.0
                dh.line(
                    [(512.0 + r_in * sn) * ss, (512.0 - r_in * cs) * ss, (512.0 + r_edge * sn) * ss, (512.0 - r_edge * cs) * ss],
                    fill=255,
                    width=int(round(11.5 * ss)),
                )
            else:
                r_in = r_edge - 44.0
                dm.line(
                    [(512.0 + r_in * sn) * ss, (512.0 - r_in * cs) * ss, (512.0 + r_edge * sn) * ss, (512.0 - r_edge * cs) * ss],
                    fill=255,
                    width=int(round(5.0 * ss)),
                )

        num_hi = Image.new("L", (n_hi, n_hi), 0)
        dn = ImageDraw.Draw(num_hi)
        f_num = load_ui_font(int(round(108 * ss)), "semibold")
        for txt, tx, ty in [("12", 512.0, 212.0), ("3", 814.0, 512.0), ("6", 512.0, 812.0), ("9", 210.0, 512.0)]:
            bb = dn.textbbox((0, 0), txt, font=f_num)
            tw, th_b = bb[2] - bb[0], bb[3] - bb[1]
            dn.text((tx * ss - tw * 0.5 - bb[0], ty * ss - th_b * 0.5 - bb[1]), txt, fill=255, font=f_num)

        hands_hi = Image.new("L", (n_hi, n_hi), 0)
        sec_hi = Image.new("L", (n_hi, n_hi), 0)
        d_hnd, d_sec = ImageDraw.Draw(hands_hi), ImageDraw.Draw(sec_hi)

        def draw_watch_hand(drw, deg: float, r_len: float, w_stem: float, w_body: float):
            th = math.radians(deg)
            sn, cs = math.sin(th), math.cos(th)
            r_split = 84.0
            drw.line([512.0 * ss, 512.0 * ss, (512.0 + r_split * sn) * ss, (512.0 - r_split * cs) * ss], fill=255, width=int(round(w_stem * ss)))
            p0x, p0y = (512.0 + r_split * sn) * ss, (512.0 - r_split * cs) * ss
            p1x, p1y = (512.0 + r_len * sn) * ss, (512.0 - r_len * cs) * ss
            drw.line([p0x, p0y, p1x, p1y], fill=255, width=int(round(w_body * ss)))
            rb = 0.5 * w_body * ss
            for px, py in ((p0x, p0y), (p1x, p1y)):
                drw.ellipse([px - rb, py - rb, px + rb, py + rb], fill=255)

        draw_watch_hand(d_hnd, 281.5, 256.0, 14.0, 26.0)
        draw_watch_hand(d_hnd, 133.0, 392.0, 14.0, 24.0)
        rc_h = 22.0 * ss
        d_hnd.ellipse([512 * ss - rc_h, 512 * ss - rc_h, 512 * ss + rc_h, 512 * ss + rc_h], fill=255)

        th_s = math.radians(352.0)
        sn_s, cs_s = math.sin(th_s), math.cos(th_s)
        d_sec.line(
            [(512.0 - 92.0 * sn_s) * ss, (512.0 + 92.0 * cs_s) * ss, (512.0 + 422.0 * sn_s) * ss, (512.0 - 422.0 * cs_s) * ss],
            fill=255,
            width=int(round(10.0 * ss)),
        )
        rc_s, rc_pin = 16.0 * ss, 6.5 * ss
        d_sec.ellipse([512 * ss - rc_s, 512 * ss - rc_s, 512 * ss + rc_s, 512 * ss + rc_s], fill=255)
        d_sec.ellipse([512 * ss - rc_pin, 512 * ss - rc_pin, 512 * ss + rc_pin, 512 * ss + rc_pin], fill=0)

        def to_s(im_l):
            return np.array(im_l.resize((self.S, self.S), Image.Resampling.BOX), dtype=np.float32) / 255.0

        self.m_hour_ticks = to_s(hour_hi)
        self.m_min_ticks = to_s(min_hi)
        self.m_nums = to_s(num_hi)
        self.m_hands = to_s(hands_hi)
        self.m_sec = to_s(sec_hi)
        self.shad_hands = blur_offset_mask(np.clip(self.m_hands + self.m_sec, 0.0, 1.0), int(round(2 * sc)), int(round(8 * sc)), 9.0 * sc)

    def render_float(self, mode: str = "default"):
        bg_base, p0_lit, _ = self.sh.plane0_rgb(self.PALETTE, mode)
        comp = p0_lit.copy()

        if mode in ("default", "dark"):
            c_min = np.array([178.0, 182.0, 190.0] if mode == "default" else [96.0, 100.0, 108.0], np.float32)
            c_maj = np.array([28.0, 30.0, 34.0] if mode == "default" else [244.0, 246.0, 250.0], np.float32)
            c_sec = np.array([255.0, 149.0, 0.0] if mode == "default" else [255.0, 159.0, 10.0], np.float32)
            comp = comp * (1.0 - self.m_min_ticks[:, :, None]) + c_min * self.m_min_ticks[:, :, None]
            maj = np.clip(self.m_hour_ticks + self.m_nums, 0.0, 1.0)
            comp = comp * (1.0 - maj[:, :, None]) + c_maj * maj[:, :, None]
            comp = np.clip(comp - ((16.0 if mode == "default" else 20.0) * self.shad_hands)[:, :, None], 0.0, 255.0)
            comp = comp * (1.0 - self.m_hands[:, :, None]) + c_maj * self.m_hands[:, :, None]
            comp = comp * (1.0 - self.m_sec[:, :, None]) + c_sec * self.m_sec[:, :, None]
        else:
            a_fg = np.clip(0.42 * self.m_min_ticks + 0.85 * self.m_hour_ticks + 0.88 * self.m_nums + 0.96 * self.m_hands + 0.92 * self.m_sec, 0.0, 0.98)
            comp = np.clip(comp - (12.0 * self.shad_hands)[:, :, None], 0.0, 255.0)
            comp = comp * (1.0 - a_fg[:, :, None]) + 255.0 * a_fg[:, :, None]

        return comp, bg_base, self.PALETTE, 1.0


class CleanRoomFilesIconRenderer:
    PALETTE = "porcelain"

    def __init__(self, shared: SharedSquircleOptics, ss: int = 3):
        self.sh = shared
        self.S = shared.S
        sc = self.S / 1024.0
        n_hi = int(round(1024 * ss))

        back_hi = Image.new("L", (n_hi, n_hi), 0)
        db = ImageDraw.Draw(back_hi)
        db.rounded_rectangle([146 * ss, 224 * ss, 446 * ss, 420 * ss], radius=58 * ss, fill=255)
        db.rounded_rectangle([146 * ss, 282 * ss, 878 * ss, 792 * ss], radius=76 * ss, fill=255)
        db.polygon([(380 * ss, 224 * ss), (492 * ss, 282 * ss), (380 * ss, 340 * ss)], fill=255)
        back_hi = back_hi.filter(ImageFilter.GaussianBlur(radius=1.2 * ss))

        doc_hi = Image.new("L", (n_hi, n_hi), 0)
        ImageDraw.Draw(doc_hi).rounded_rectangle([194 * ss, 304 * ss, 830 * ss, 520 * ss], radius=38 * ss, fill=255)

        front_hi = Image.new("L", (n_hi, n_hi), 0)
        ImageDraw.Draw(front_hi).rounded_rectangle([146 * ss, 354 * ss, 878 * ss, 794 * ss], radius=76 * ss, fill=255)

        def to_s(im_l, thresh=False):
            arr = np.array(im_l.resize((self.S, self.S), Image.Resampling.BOX), dtype=np.float32) / 255.0
            return np.clip((arr - 0.45) * 8.0 + 0.5, 0.0, 1.0) if thresh else arr

        self.m_back = to_s(back_hi, thresh=True)
        self.m_doc = to_s(doc_hi)
        self.m_front = to_s(front_hi)
        self.m_union = np.clip(np.maximum(self.m_back, self.m_front), 0.0, 1.0)
        self.shad_p0 = blur_offset_mask(self.m_union, int(round(2 * sc)), int(round(11 * sc)), 19.0 * sc)
        self.shad_pocket = blur_offset_mask(self.m_front, 0, int(round(-4 * sc)), 10.0 * sc)
        _, _, _, self.spec_front, _, _ = compute_glass_meniscus(self.m_front, max_d=28, r_peak=8.8 * sc, r_falloff=6.0 * sc)
        _, _, _, self.spec_back, _, _ = compute_glass_meniscus(self.m_back, max_d=22, r_peak=6.5 * sc, r_falloff=4.5 * sc)
        self.t_y = np.clip(((shared.Y / sc) - 354.0) / (794.0 - 354.0), 0.0, 1.0)

    def render_float(self, mode: str = "default"):
        bg_base, p0_lit, bf = self.sh.plane0_rgb(self.PALETTE, mode)
        burn = 14.5 if mode == "default" else (17.0 if mode == "dark" else (13.0 + 8.5 * bf))
        comp = np.clip(p0_lit - burn * self.shad_p0[:, :, None], 0.0, 255.0)
        ty = self.t_y

        if mode in ("default", "dark"):
            c_back = np.zeros((self.S, self.S, 3), np.float32) + np.array([22.0, 122.0, 236.0], np.float32)
            c_back = np.clip(c_back + (255.0 - c_back) * (0.22 * self.spec_back)[:, :, None], 0.0, 255.0)
            comp = comp * (1.0 - self.m_back[:, :, None]) + c_back * self.m_back[:, :, None]
            doc_col = np.array([250.0, 252.0, 255.0], np.float32)[None, None, :]
            comp = comp * (1.0 - (0.94 * self.m_doc)[:, :, None]) + doc_col * (0.94 * self.m_doc)[:, :, None]
            comp = np.clip(comp - (20.0 * self.shad_pocket * self.m_back)[:, :, None], 0.0, 255.0)

            c_front = np.stack([
                86.0 * (1.0 - ty) + 12.0 * ty,
                204.0 * (1.0 - ty) + 126.0 * ty,
                255.0 * (1.0 - ty) + 252.0 * ty,
            ], axis=-1)
            a_front = (0.86 - 0.10 * ty)[:, :, None]
            pocket_glass = comp * (1.0 - a_front) + c_front * a_front
            pocket_lit = np.clip(pocket_glass + (255.0 - pocket_glass) * (0.38 * self.spec_front)[:, :, None], 0.0, 255.0)
            comp = comp * (1.0 - self.m_front[:, :, None]) + pocket_lit * self.m_front[:, :, None]
        else:
            comp = comp * (1.0 - (0.48 * self.m_back)[:, :, None]) + 255.0 * (0.48 * self.m_back)[:, :, None]
            comp = np.clip(comp - (14.0 * self.shad_pocket * self.m_back)[:, :, None], 0.0, 255.0)
            a_fr = np.clip((0.82 - 0.18 * ty) + 0.36 * self.spec_front, 0.0, 0.96) * self.m_front
            comp = comp * (1.0 - a_fr[:, :, None]) + 255.0 * a_fr[:, :, None]

        return comp, bg_base, self.PALETTE, 1.0


class CleanRoomCalculatorIconRenderer:
    PALETTE = (np.array([124.0, 129.0, 140.0], np.float32), np.array([68.0, 72.0, 80.0], np.float32))

    def __init__(self, shared: SharedSquircleOptics, ss: int = 3):
        self.sh = shared
        self.S = shared.S
        sc = self.S / 1024.0
        n_hi = int(round(1024 * ss))

        body_hi = Image.new("L", (n_hi, n_hi), 0)
        ImageDraw.Draw(body_hi).rounded_rectangle([238 * ss, 138 * ss, 786 * ss, 886 * ss], radius=108 * ss, fill=255)

        scr_hi = Image.new("L", (n_hi, n_hi), 0)
        ImageDraw.Draw(scr_hi).rounded_rectangle([294 * ss, 196 * ss, 730 * ss, 344 * ss], radius=44 * ss, fill=255)

        top_keys_hi = Image.new("L", (n_hi, n_hi), 0)
        num_keys_hi = Image.new("L", (n_hi, n_hi), 0)
        org_keys_hi = Image.new("L", (n_hi, n_hi), 0)
        dt, dn, do = ImageDraw.Draw(top_keys_hi), ImageDraw.Draw(num_keys_hi), ImageDraw.Draw(org_keys_hi)

        cols_x = [338.0, 454.0, 570.0, 686.0]
        rows_y = [428.0, 540.0, 652.0, 764.0]
        rk = 42.0 * ss
        for r_idx, ky in enumerate(rows_y):
            for c_idx, kx in enumerate(cols_x):
                if r_idx == 3 and c_idx == 0:
                    dn.rounded_rectangle([(cols_x[0] - 42.0) * ss, (ky - 42.0) * ss, (cols_x[1] + 42.0) * ss, (ky + 42.0) * ss], radius=int(round(rk)), fill=255)
                    continue
                if r_idx == 3 and c_idx == 1:
                    continue
                target = do if c_idx == 3 else (dt if r_idx == 0 else dn)
                target.ellipse([kx * ss - rk, ky * ss - rk, kx * ss + rk, ky * ss + rk], fill=255)

        def to_s(im_l):
            return np.array(im_l.resize((self.S, self.S), Image.Resampling.BOX), dtype=np.float32) / 255.0

        self.m_body = to_s(body_hi)
        self.m_scr = to_s(scr_hi)
        self.m_k_top = to_s(top_keys_hi)
        self.m_k_num = to_s(num_keys_hi)
        self.m_k_org = to_s(org_keys_hi)
        self.m_keys = np.clip(self.m_k_top + self.m_k_num + self.m_k_org, 0.0, 1.0)

        self.shad_p0 = blur_offset_mask(self.m_body, int(round(2 * sc)), int(round(12 * sc)), 20.0 * sc)
        self.shad_keys = blur_offset_mask(self.m_keys, int(round(1 * sc)), int(round(4 * sc)), 5.5 * sc)
        _, _, _, self.spec_body, _, _ = compute_glass_meniscus(self.m_body, max_d=26, r_peak=8.2 * sc, r_falloff=5.5 * sc)
        _, _, _, self.spec_keys, _, _ = compute_glass_meniscus(self.m_keys, max_d=18, r_peak=4.8 * sc, r_falloff=3.5 * sc)
        self.t_y = np.clip(((shared.Y / sc) - 138.0) / (886.0 - 138.0), 0.0, 1.0)

    def render_float(self, mode: str = "default"):
        bg_base, p0_lit, bf = self.sh.plane0_rgb(self.PALETTE, mode)
        burn = 17.0 if mode != "clear" else (13.5 + 8.0 * bf)
        comp = np.clip(p0_lit - burn * self.shad_p0[:, :, None], 0.0, 255.0)
        ty = self.t_y

        if mode != "clear":
            body_col = np.stack([52.0 * (1.0 - ty) + 26.0 * ty, 54.0 * (1.0 - ty) + 28.0 * ty, 60.0 * (1.0 - ty) + 32.0 * ty], axis=-1)
            body_col = np.clip(body_col + (255.0 - body_col) * (0.28 * self.spec_body)[:, :, None], 0.0, 255.0)
            comp = comp * (1.0 - self.m_body[:, :, None]) + body_col * self.m_body[:, :, None]
            scr_col = np.array([18.0, 20.0, 24.0], np.float32)[None, None, :]
            comp = comp * (1.0 - self.m_scr[:, :, None]) + scr_col * self.m_scr[:, :, None]
            comp = np.clip(comp - (14.0 * self.shad_keys * self.m_body)[:, :, None], 0.0, 255.0)

            c_top = np.array([166.0, 170.0, 178.0], np.float32)[None, None, :]
            c_num = np.array([86.0, 90.0, 98.0], np.float32)[None, None, :]
            c_org = np.array([255.0, 159.0, 10.0], np.float32)[None, None, :]
            k_rgb = (c_top * self.m_k_top[:, :, None] + c_num * self.m_k_num[:, :, None] + c_org * self.m_k_org[:, :, None]) / np.maximum(self.m_keys[:, :, None], 1e-4)
            k_rgb = np.clip(k_rgb + (255.0 - k_rgb) * (0.34 * self.spec_keys)[:, :, None], 0.0, 255.0)
            comp = comp * (1.0 - self.m_keys[:, :, None]) + k_rgb * self.m_keys[:, :, None]
        else:
            a_b = np.clip(0.42 + 0.34 * self.spec_body, 0.0, 0.92) * self.m_body
            comp = comp * (1.0 - a_b[:, :, None]) + 255.0 * a_b[:, :, None]
            comp = np.clip(comp - (16.0 * self.m_scr)[:, :, None], 0.0, 255.0)
            a_k = np.clip(0.72 * self.m_k_num + 0.86 * self.m_k_top + 0.94 * self.m_k_org + 0.25 * self.spec_keys, 0.0, 0.98)
            comp = comp * (1.0 - (a_k * self.m_keys)[:, :, None]) + 255.0 * (a_k * self.m_keys)[:, :, None]

        rim_s = 0.92 if mode == "default" else 1.0
        return comp, bg_base, self.PALETTE, rim_s


class CleanRoomContactsIconRenderer:
    PALETTE = (np.array([243.0, 239.0, 232.0], np.float32), np.array([222.0, 216.0, 206.0], np.float32))

    def __init__(self, shared: SharedSquircleOptics, ss: int = 3):
        self.sh = shared
        self.S = shared.S
        sc = self.S / 1024.0
        n_hi = self.S * ss
        yy, xx = (np.mgrid[0:n_hi, 0:n_hi].astype(np.float32) + 0.5) / (ss * sc)
        px_sc = ss * sc

        cx, cy = 492.0, 512.0
        rr = np.hypot(xx - cx, yy - cy)
        self.m_ring_outer = aa_from_sdf_ss(rr - 264.0, self.S, ss, px_sc)
        self.m_ring_inner = aa_from_sdf_ss(rr - 236.0, self.S, ss, px_sc)

        d_head = np.hypot(xx - cx, yy - 428.0) - 82.0
        d_shld = (np.hypot((xx - cx) / 188.0, (yy - 734.0) / 194.0) - 1.0) * 188.0
        d_bust = np.maximum(np.minimum(d_head, d_shld), rr - 224.0)
        self.m_bust = aa_from_sdf_ss(d_bust, self.S, ss, px_sc)

        self.shad_bust = blur_offset_mask(self.m_bust, int(round(2 * sc)), int(round(8 * sc)), 14.0 * sc)
        _, _, _, self.spec_bust, _, _ = compute_glass_meniscus(self.m_bust, max_d=24, r_peak=7.4 * sc, r_falloff=5.0 * sc)
        self.X0 = shared.X / sc
        self.Y0 = shared.Y / sc

    def render_float(self, mode: str = "default"):
        bg_base, p0_lit, _ = self.sh.plane0_rgb(self.PALETTE, mode)
        comp = p0_lit.copy()
        x0, y0 = self.X0, self.Y0

        if mode != "clear":
            tab_m = np.clip((x0 - 914.0) / 3.0, 0.0, 1.0)
            tab_cols = [
                (0.0, 260.0, [182.0, 178.0, 172.0]),
                (260.0, 430.0, [90.0, 200.0, 250.0]),
                (430.0, 595.0, [52.0, 199.0, 89.0]),
                (595.0, 760.0, [255.0, 149.0, 0.0]),
                (760.0, 1025.0, [255.0, 105.0, 97.0]),
            ]
            tab_rgb = np.zeros_like(comp)
            for y_lo, y_hi, rgb_c in tab_cols:
                in_band = ((y0 >= y_lo) & (y0 < y_hi)).astype(np.float32)[:, :, None]
                tab_rgb += np.array(rgb_c, np.float32)[None, None, :] * in_band
            tab_a = (0.78 if mode == "default" else 0.58) * tab_m[:, :, None]
            comp = comp * (1.0 - tab_a) + tab_rgb * tab_a

            groove = np.exp(-(((x0 - 168.0) / 4.5) ** 2))[:, :, None]
            comp = np.clip(comp - (18.0 if mode == "default" else 10.0) * groove, 0.0, 255.0)

            fg_col = np.array([92.0, 88.0, 84.0], np.float32) if mode == "default" else np.array([218.0, 214.0, 208.0], np.float32)
            ring_m = np.clip(self.m_ring_outer - self.m_ring_inner, 0.0, 1.0)
            comp = np.clip(comp - (14.0 * self.shad_bust)[:, :, None], 0.0, 255.0)
            comp = comp * (1.0 - (0.75 * ring_m)[:, :, None]) + fg_col[None, None, :] * (0.75 * ring_m)[:, :, None]

            ty = np.clip((y0 - 340.0) / 400.0, 0.0, 1.0)
            a_b = (0.94 - 0.16 * ty)[:, :, None]
            bust_glass = comp * (1.0 - a_b) + fg_col[None, None, :] * a_b
            bust_lit = np.clip(bust_glass + (255.0 - bust_glass) * (0.28 * self.spec_bust)[:, :, None], 0.0, 255.0)
            comp = comp * (1.0 - self.m_bust[:, :, None]) + bust_lit * self.m_bust[:, :, None]
        else:
            ring_m = np.clip(self.m_ring_outer - self.m_ring_inner, 0.0, 1.0)
            a_tot = np.clip(0.62 * ring_m + (0.86 + 0.20 * self.spec_bust) * self.m_bust, 0.0, 0.96)
            comp = np.clip(comp - (12.0 * self.shad_bust)[:, :, None], 0.0, 255.0)
            comp = comp * (1.0 - a_tot[:, :, None]) + 255.0 * a_tot[:, :, None]

        rim_s = 0.92 if mode == "default" else 1.0
        return comp, bg_base, self.PALETTE, rim_s


class CleanRoomVoiceMemosIconRenderer:
    PALETTE = (np.array([40.0, 42.0, 48.0], np.float32), np.array([15.0, 17.0, 21.0], np.float32))

    def __init__(self, shared: SharedSquircleOptics, ss: int = 3):
        self.sh = shared
        self.S = shared.S
        sc = self.S / 1024.0
        n_hi = self.S * ss
        yy, xx = (np.mgrid[0:n_hi, 0:n_hi].astype(np.float32) + 0.5) / (ss * sc)
        px_sc = ss * sc

        heights = [52, 108, 198, 134, 286, 358, 212, 316, 152, 248, 332, 178, 118, 74, 42]
        xs = np.linspace(162.0, 862.0, len(heights))
        sdf_bars = np.full_like(xx, 1e4)
        for bx, bh in zip(xs, heights):
            half_h = 0.5 * float(bh)
            sdf_bars = np.minimum(sdf_bars, sd_capsule(xx, yy, bx, 512.0 - half_h, bx, 512.0 + half_h, 15.5))

        self.m_bars = aa_from_sdf_ss(sdf_bars, self.S, ss, px_sc)
        self.glow_bars = blur_offset_mask(self.m_bars, 0, 0, 22.0 * sc)
        _, _, _, self.spec_bars, _, _ = compute_glass_meniscus(self.m_bars, max_d=16, r_peak=4.8 * sc, r_falloff=3.5 * sc)
        self.Y0 = shared.Y / sc

    def render_float(self, mode: str = "default"):
        bg_base, p0_lit, _ = self.sh.plane0_rgb(self.PALETTE, mode)
        comp = p0_lit.copy()
        axis_m = np.exp(-(((self.Y0 - 512.0) / 2.2) ** 2))[:, :, None]
        comp = comp * (1.0 - 0.14 * axis_m) + 255.0 * (0.14 * axis_m)

        if mode != "clear":
            crimson_glow = np.array([255.0, 52.0, 58.0], np.float32)[None, None, :]
            comp = np.clip(comp + crimson_glow * (0.20 * self.glow_bars)[:, :, None], 0.0, 255.0)
            ty = np.clip(np.abs(self.Y0 - 512.0) / 360.0, 0.0, 1.0)
            bar_col = np.stack([255.0 * np.ones_like(ty), 82.0 * (1.0 - ty) + 46.0 * ty, 68.0 * (1.0 - ty) + 62.0 * ty], axis=-1)
            bar_lit = np.clip(bar_col + (255.0 - bar_col) * (0.36 * self.spec_bars)[:, :, None], 0.0, 255.0)
            comp = comp * (1.0 - self.m_bars[:, :, None]) + bar_lit * self.m_bars[:, :, None]
        else:
            a_b = np.clip((0.88 + 0.22 * self.spec_bars) * self.m_bars, 0.0, 0.98)
            comp = comp * (1.0 - a_b[:, :, None]) + 255.0 * a_b[:, :, None]

        rim_s = 0.92 if mode == "default" else 1.0
        return comp, bg_base, self.PALETTE, rim_s


class CleanRoomSystemUpdateIconRenderer:
    """`com.realme.ota` — Cerulean-Azure Liquid Glass Orbit Ring & Upward Upgrade Arrow."""

    PALETTE = (np.array([40.0, 198.0, 255.0], np.float32), np.array([0.0, 106.0, 222.0], np.float32))

    def __init__(self, shared: SharedSquircleOptics, ss: int = 3):
        self.sh = shared
        self.S = shared.S
        sc = self.S / 1024.0
        n_hi = self.S * ss
        yy, xx = (np.mgrid[0:n_hi, 0:n_hi].astype(np.float32) + 0.5) / (ss * sc)
        px_sc = ss * sc

        rr = np.hypot(xx - 512.0, yy - 512.0)
        self.m_ring = aa_from_sdf_ss(np.maximum(rr - 332.0, 254.0 - rr), self.S, ss, px_sc)
        d_shaft = sd_capsule(xx, yy, 512.0, 684.0, 512.0, 342.0, 42.0)
        d_wl = sd_capsule(xx, yy, 512.0, 326.0, 368.0, 470.0, 42.0)
        d_wr = sd_capsule(xx, yy, 512.0, 326.0, 656.0, 470.0, 42.0)
        self.m_arrow = aa_from_sdf_ss(np.minimum(d_shaft, np.minimum(d_wl, d_wr)), self.S, ss, px_sc)

        self.shad_r = blur_offset_mask(self.m_ring, int(round(1 * sc)), int(round(8 * sc)), 14.0 * sc)
        self.shad_a = blur_offset_mask(self.m_arrow, int(round(2 * sc)), int(round(10 * sc)), 18.0 * sc)
        _, _, _, self.spec_a, _, _ = compute_glass_meniscus(self.m_arrow, max_d=26, r_peak=8.6 * sc, r_falloff=5.8 * sc)
        _, _, _, self.spec_r, _, _ = compute_glass_meniscus(self.m_ring, max_d=18, r_peak=5.2 * sc, r_falloff=3.8 * sc)
        self.t_y = np.clip(((shared.Y / sc) - 284.0) / (726.0 - 284.0), 0.0, 1.0)

    def render_float(self, mode: str = "default"):
        bg_base, p0_lit, _ = self.sh.plane0_rgb(self.PALETTE, mode)
        p0_shad = np.clip(p0_lit - (5.5 * self.shad_r + 15.0 * self.shad_a)[:, :, None], 0.0, 255.0)
        ty = self.t_y

        a_r = np.clip((0.40 - 0.07 * ty) + 0.16 * self.spec_r, 0.0, 0.85)
        if mode == "dark":
            tint_col = np.stack([52.0 - 24.0 * ty, 208.0 - 52.0 * ty, 255.0 - 18.0 * ty], axis=-1)
        else:
            tint_col = np.full_like(bg_base, 255.0)

        c1 = p0_shad * (1.0 - (a_r * self.m_ring)[:, :, None]) + tint_col * (a_r * self.m_ring)[:, :, None]
        a_ar = np.clip((0.992 - 0.385 * (ty ** 0.96)) + 0.40 * self.spec_a, 0.0, 1.0)
        ar_rgb = bg_base * (1.0 - a_ar[:, :, None]) + tint_col * a_ar[:, :, None]
        if mode == "dark":
            ar_rgb = np.clip(ar_rgb + (255.0 - ar_rgb) * (0.30 * self.spec_a)[:, :, None], 0.0, 255.0)
        comp = c1 * (1.0 - self.m_arrow[:, :, None]) + ar_rgb * self.m_arrow[:, :, None]
        return comp, bg_base, self.PALETTE, 0.92 if mode == "default" else 1.0


class CleanRoomSimToolkitIconRenderer:
    """`com.android.stk` — Cellular Emerald + Sculpted Frosted Nano-SIM & Champagne-Gold Contacts."""

    PALETTE = (np.array([64.0, 218.0, 128.0], np.float32), np.array([22.0, 162.0, 84.0], np.float32))

    def __init__(self, shared: SharedSquircleOptics, ss: int = 3):
        self.sh = shared
        self.S = shared.S
        sc = self.S / 1024.0

        hi = Image.new("L", (self.S * ss, self.S * ss), 0)
        ImageDraw.Draw(hi).polygon(
            [(312 * ss, 208 * ss), (600 * ss, 208 * ss), (716 * ss, 324 * ss), (716 * ss, 816 * ss), (312 * ss, 816 * ss)],
            fill=255,
        )
        hi_b = hi.filter(ImageFilter.GaussianBlur(radius=22 * ss))
        m_c = (np.array(hi_b.resize((self.S, self.S), Image.Resampling.BILINEAR), dtype=np.float32) > 124.0).astype(np.float32)
        self.m_card = np.array(Image.fromarray(np.uint8(m_c * 255)).filter(ImageFilter.GaussianBlur(radius=1.5)), dtype=np.float32) / 255.0

        hi2 = Image.new("L", (self.S * ss, self.S * ss), 0)
        dr2 = ImageDraw.Draw(hi2)
        for yc in [472, 578, 684]:
            for xc in [430, 594]:
                dr2.rounded_rectangle([(xc - 56) * ss, (yc - 38) * ss, (xc + 56) * ss, (yc + 38) * ss], radius=18 * ss, fill=255)
        self.m_pads = np.array(hi2.resize((self.S, self.S), Image.Resampling.BOX), dtype=np.float32) / 255.0

        self.shad_card = blur_offset_mask(self.m_card, int(round(2 * sc)), int(round(10 * sc)), 17.0 * sc)
        self.shad_pads = blur_offset_mask(self.m_pads, 0, int(round(5 * sc)), 8.0 * sc)
        _, _, _, self.spec_card, _, _ = compute_glass_meniscus(self.m_card, max_d=28, r_peak=9.2 * sc, r_falloff=6.0 * sc)
        _, _, _, self.spec_pads, _, _ = compute_glass_meniscus(self.m_pads, max_d=18, r_peak=5.2 * sc, r_falloff=3.6 * sc)
        self.t_y = np.clip(((shared.Y / sc) - 208.0) / (816.0 - 208.0), 0.0, 1.0)

    def render_float(self, mode: str = "default"):
        bg_base, p0_lit, _ = self.sh.plane0_rgb(self.PALETTE, mode)
        p0_shad = np.clip(p0_lit - (16.0 if mode != "clear" else 13.0) * self.shad_card[:, :, None], 0.0, 255.0)
        ty = self.t_y

        if mode == "default":
            card_tint = np.stack([255.0 - 28.0 * ty, 255.0 - 4.0 * ty, 255.0 - 18.0 * ty], axis=-1)
            a_c = np.clip((0.94 - 0.36 * ty) + 0.38 * self.spec_card, 0.0, 1.0)
            c_card = bg_base * (1.0 - a_c[:, :, None]) + card_tint * a_c[:, :, None]
        elif mode == "dark":
            card_tint = np.stack([72.0 - 30.0 * ty, 228.0 - 52.0 * ty, 134.0 - 40.0 * ty], axis=-1)
            a_c = np.clip((0.92 - 0.34 * ty) + 0.28 * self.spec_card, 0.0, 1.0)
            c_card = bg_base * (1.0 - a_c[:, :, None]) + card_tint * a_c[:, :, None]
            c_card = np.clip(c_card + (255.0 - c_card) * (0.28 * self.spec_card)[:, :, None], 0.0, 255.0)
        else:
            a_c = np.clip((0.86 - 0.32 * ty) + 0.36 * self.spec_card, 0.0, 0.96)
            c_card = bg_base * (1.0 - a_c[:, :, None]) + 255.0 * a_c[:, :, None]

        comp = p0_shad * (1.0 - self.m_card[:, :, None]) + c_card * self.m_card[:, :, None]
        comp = np.clip(comp - (6.0 * self.shad_pads * self.m_card)[:, :, None], 0.0, 255.0)

        if mode != "clear":
            pad_col = np.stack([255.0 - 20.0 * ty, 234.0 - 46.0 * ty, 165.0 - 78.0 * ty], axis=-1)
            pad_lit = np.clip(pad_col + (255.0 - pad_col) * (0.34 * self.spec_pads)[:, :, None], 0.0, 255.0)
            a_p = (0.88 - 0.14 * ty)[:, :, None] * self.m_pads[:, :, None]
            comp = comp * (1.0 - a_p) + pad_lit * a_p
        else:
            a_p = np.clip(0.94 + 0.16 * self.spec_pads, 0.0, 0.99) * self.m_pads
            comp = comp * (1.0 - a_p[:, :, None]) + 255.0 * a_p[:, :, None]

        return comp, bg_base, self.PALETTE, 0.92 if mode == "default" else 1.0


class CleanRoomWellbeingIconRenderer:
    """`com.google.android.apps.wellbeing` — iOS 26 Screen Time Indigo Liquid Glass Hourglass."""

    PALETTE = (np.array([116.0, 108.0, 252.0], np.float32), np.array([74.0, 62.0, 214.0], np.float32))

    def __init__(self, shared: SharedSquircleOptics, ss: int = 3):
        self.sh = shared
        self.S = shared.S
        sc = self.S / 1024.0

        hi = Image.new("L", (self.S * ss, self.S * ss), 0)
        dr = ImageDraw.Draw(hi)
        dr.polygon([(310 * ss, 256 * ss), (714 * ss, 256 * ss), (546 * ss, 512 * ss), (478 * ss, 512 * ss)], fill=255)
        dr.polygon([(478 * ss, 512 * ss), (546 * ss, 512 * ss), (714 * ss, 768 * ss), (310 * ss, 768 * ss)], fill=255)
        hi_b = hi.filter(ImageFilter.GaussianBlur(radius=20 * ss))
        mb = (np.array(hi_b.resize((self.S, self.S), Image.Resampling.BILINEAR), dtype=np.float32) > 118.0).astype(np.float32)
        self.m_bulbs = np.array(Image.fromarray(np.uint8(mb * 255)).filter(ImageFilter.GaussianBlur(radius=1.5)), dtype=np.float32) / 255.0

        hi_c = Image.new("L", (self.S * ss, self.S * ss), 0)
        drc = ImageDraw.Draw(hi_c)
        drc.rounded_rectangle([274 * ss, 206 * ss, 750 * ss, 268 * ss], radius=31 * ss, fill=255)
        drc.rounded_rectangle([274 * ss, 756 * ss, 750 * ss, 818 * ss], radius=31 * ss, fill=255)
        self.m_caps = np.array(hi_c.resize((self.S, self.S), Image.Resampling.BOX), dtype=np.float32) / 255.0

        self.shad_b = blur_offset_mask(self.m_bulbs, int(round(1 * sc)), int(round(9 * sc)), 16.0 * sc)
        self.shad_c = blur_offset_mask(self.m_caps, int(round(2 * sc)), int(round(10 * sc)), 16.0 * sc)
        _, _, _, self.spec_b, _, _ = compute_glass_meniscus(self.m_bulbs, max_d=24, r_peak=8.5 * sc, r_falloff=5.6 * sc)
        _, _, _, self.spec_c, _, _ = compute_glass_meniscus(self.m_caps, max_d=20, r_peak=6.5 * sc, r_falloff=4.5 * sc)
        self.t_y = np.clip(((shared.Y / sc) - 206.0) / (818.0 - 206.0), 0.0, 1.0)

    def render_float(self, mode: str = "default"):
        bg_base, p0_lit, _ = self.sh.plane0_rgb(self.PALETTE, mode)
        comp = np.clip(p0_lit - (12.0 * self.shad_b + 14.0 * self.shad_c)[:, :, None], 0.0, 255.0)
        ty = self.t_y

        if mode == "dark":
            tint_b = np.stack([142.0 - 34.0 * ty, 134.0 - 38.0 * ty, 255.0 - 14.0 * ty], axis=-1)
            tint_c = np.stack([178.0 - 26.0 * ty, 172.0 - 30.0 * ty, 255.0 - 8.0 * ty], axis=-1)
        else:
            tint_b = np.full_like(bg_base, 255.0)
            tint_c = np.full_like(bg_base, 255.0)

        a_b = np.clip((0.70 - 0.24 * ty) + 0.34 * self.spec_b, 0.0, 0.95) * self.m_bulbs
        comp = comp * (1.0 - a_b[:, :, None]) + tint_b * a_b[:, :, None]
        a_c = np.clip((0.97 - 0.14 * ty) + 0.36 * self.spec_c, 0.0, 0.99) * self.m_caps
        caps_rgb = comp * (1.0 - a_c[:, :, None]) + tint_c * a_c[:, :, None]
        if mode == "dark":
            caps_rgb = np.clip(caps_rgb + (255.0 - caps_rgb) * (0.28 * self.spec_c * self.m_caps)[:, :, None], 0.0, 255.0)
        comp = comp * (1.0 - self.m_caps[:, :, None]) + caps_rgb * self.m_caps[:, :, None]
        return comp, bg_base, self.PALETTE, 0.92 if mode == "default" else 1.0


class CleanRoomRealmeSettingsIconRenderer:
    """`com.realme.samurai.parts` — Cobalt-Slate Liquid Glass Hardware Control Sliders."""

    PALETTE = (np.array([54.0, 114.0, 255.0], np.float32), np.array([18.0, 58.0, 202.0], np.float32))

    def __init__(self, shared: SharedSquircleOptics, ss: int = 3):
        self.sh = shared
        self.S = shared.S
        sc = self.S / 1024.0
        n_hi = self.S * ss
        yy, xx = (np.mgrid[0:n_hi, 0:n_hi].astype(np.float32) + 0.5) / (ss * sc)
        px_sc = ss * sc

        t1 = sd_capsule(xx, yy, 224.0, 382.0, 800.0, 382.0, 34.0)
        t2 = sd_capsule(xx, yy, 224.0, 642.0, 800.0, 642.0, 34.0)
        k1 = np.hypot(xx - 644.0, yy - 382.0) - 86.0
        k2 = np.hypot(xx - 380.0, yy - 642.0) - 86.0

        self.m_tracks = aa_from_sdf_ss(np.minimum(t1, t2), self.S, ss, px_sc)
        self.m_knobs = aa_from_sdf_ss(np.minimum(k1, k2), self.S, ss, px_sc)
        self.shad_t = blur_offset_mask(self.m_tracks, int(round(1 * sc)), int(round(8 * sc)), 14.0 * sc)
        self.shad_k = blur_offset_mask(self.m_knobs, int(round(2 * sc)), int(round(10 * sc)), 17.0 * sc)
        _, _, _, self.spec_k, _, _ = compute_glass_meniscus(self.m_knobs, max_d=26, r_peak=8.4 * sc, r_falloff=5.8 * sc)
        _, _, _, self.spec_t, _, _ = compute_glass_meniscus(self.m_tracks, max_d=18, r_peak=5.0 * sc, r_falloff=3.8 * sc)
        self.t_y = np.clip(((shared.Y / sc) - 280.0) / (744.0 - 280.0), 0.0, 1.0)

    def render_float(self, mode: str = "default"):
        bg_base, p0_lit, _ = self.sh.plane0_rgb(self.PALETTE, mode)
        p0_shad = np.clip(p0_lit - (6.0 * self.shad_t + 15.0 * self.shad_k)[:, :, None], 0.0, 255.0)
        ty = self.t_y

        a_tr = np.clip((0.40 - 0.06 * ty) + 0.18 * self.spec_t, 0.0, 0.85)
        if mode == "dark":
            tr_col = np.array([76.0, 134.0, 255.0], np.float32)[None, None, :]
            knob_tint = np.stack([92.0 - 30.0 * ty, 152.0 - 40.0 * ty, 255.0 - 12.0 * ty], axis=-1)
        else:
            tr_col = np.full_like(bg_base, 255.0)
            knob_tint = np.full_like(bg_base, 255.0)

        c1 = p0_shad * (1.0 - (a_tr * self.m_tracks)[:, :, None]) + tr_col * (a_tr * self.m_tracks)[:, :, None]
        c1 = np.clip(c1 - (14.0 * self.shad_k * self.m_tracks)[:, :, None], 0.0, 255.0)
        a_kn = np.clip((0.985 - 0.30 * ty) + 0.38 * self.spec_k, 0.0, 1.0)
        kn_rgb = bg_base * (1.0 - a_kn[:, :, None]) + knob_tint * a_kn[:, :, None]
        if mode == "dark":
            kn_rgb = np.clip(kn_rgb + (255.0 - kn_rgb) * (0.32 * self.spec_k)[:, :, None], 0.0, 255.0)
        comp = c1 * (1.0 - self.m_knobs[:, :, None]) + kn_rgb * self.m_knobs[:, :, None]
        return comp, bg_base, self.PALETTE, 0.92 if mode == "default" else 1.0


# ==============================================================================
# OFFICIAL iOS APP STORE 1024x1024 COUNTERPART ADAPTER (DEFAULT, DARK, CLEAR)
# ==============================================================================
class AppStoreCounterpartAdapter:
    """
    Adapts official 1024x1024 App Store master artwork across Default, Dark, and Clear modes:
      - `default`: 100% untouched official App Store interior artwork + 34-deg SpringBoard glass rim
        + seamless 18dp (96px at 576x576) Plane-0 bleed.
      - `dark`:
          * Light-plate icons (Google suite, Telegram, ChatGPT, Files): extracts foreground cleanly
            (using exterior flood-fill on solid badges with inner white glyphs like YouTube, YT Music,
            Keep, Telegram!) and composites over `#313131 -> #141414` obsidian-carbon glass plate
            (inverting ChatGPT's black knot to luminous silver-white `#F4F6FA`).
          * Brand-plate icons (`whatsapp`, `instagram`, `reddit`): renders brand-tinted Liquid Glass
            foreground over `#313131 -> #141414` carbon plate (and preserves 3D Snoo on Reddit).
          * Native dark-plate icons (`spotify`, `netflix`, `x_twitter`, `apple_voice_memos`): keeps
            native dark glyph artwork over harmonized `#28282C -> #121214` obsidian plate + 34-deg rim.
      - `clear`: Sculpted multi-plane frosted white crystal glass over `SharedSquircleOptics` Clear plate,
        preserving interior recessed details (YouTube play triangle, YT Music ring, Keep lightbulb,
        Spotify acoustic waves, Reddit Snoo face).
    """

    SOLID_BADGE_WITH_INNER_WHITE = {"youtube.png", "youtube_music.png", "telegram.png"}

    def __init__(self, shared: SharedSquircleOptics, packager: Adaptive108dpPackager, cache_dir: str):
        self.sh = shared
        self.pkg = packager
        self.cache_dir = cache_dir
        self._raw_cache = {}

    def load_raw_1024(self, filename: str):
        if filename in self._raw_cache:
            return self._raw_cache[filename]
        p = os.path.join(self.cache_dir, filename)
        if not os.path.isfile(p):
            return None
        im = Image.open(p).convert("RGB").resize((self.sh.S, self.sh.S), Image.Resampling.LANCZOS)
        arr = np.array(im, dtype=np.float32)
        self._raw_cache[filename] = arr
        return arr

    def render_576(self, filename: str, mode: str, fallback_renderer=None) -> Image.Image:
        arr = self.load_raw_1024(filename)
        if arr is None:
            if fallback_renderer is None:
                raise FileNotFoundError(f"Missing App Store cache icon {filename} and no fallback renderer set")
            comp, bg_base, pal, rim_s = fallback_renderer.render_float(mode)
            return self.pkg.package_cleanroom_576(comp, bg_base, pal, mode, rim_s)

        if mode == "default":
            im_576, _, _ = self.pkg.package_unmasked_appstore_default_576(arr)
            return im_576

        # If dedicated clean-room renderer is provided for Apple system counterparts in Dark/Clear, use it cleanly
        if fallback_renderer is not None and filename.startswith("apple_"):
            comp, bg_base, pal, rim_s = fallback_renderer.render_float(mode)
            return self.pkg.package_cleanroom_576(comp, bg_base, pal, mode, rim_s)

        # Estimate per-row border plate color (handles vertical light-plate gradients such as Telegram)
        full_bdr = np.concatenate([arr[:, 48:96], arr[:, 928:976]], axis=1)
        bg_row_col = np.median(full_bdr, axis=1, keepdims=True)  # (1024, 1, 3)
        bdr_strip = np.concatenate([arr[120:904, 60:105], arr[120:904, 919:964]], axis=1)
        bg_med = np.median(bdr_strip.reshape(-1, 3), axis=0)
        b_lum = float(0.299 * bg_med[0] + 0.587 * bg_med[1] + 0.114 * bg_med[2])

        px_lum = 0.299 * arr[:, :, 0] + 0.587 * arr[:, :, 1] + 0.114 * arr[:, :, 2]
        px_max = np.max(arr, axis=-1)
        px_min = np.min(arr, axis=-1)
        px_sat = (px_max - px_min) / np.maximum(px_max, 1.0)

        bg_base, p0_lit, bf = self.sh.plane0_rgb("porcelain", mode)
        u_y = np.clip((self.sh.Y - 180.0) / 664.0, 0.0, 1.0)

        # Case 1: Native dark/black plates (`spotify.png`, `netflix.png`, `x_twitter.png`)
        if b_lum < 45.0:
            dist_dark = np.linalg.norm(arr - bg_med[None, None, :], axis=-1)
            fg_a = np.clip((dist_dark - 16.0) / 36.0, 0.0, 1.0)
            if mode == "dark":
                # Harmonize exterior pure-black plate to iOS 26 dark obsidian while keeping glyph 100% crisp
                ext_dark = exterior_flood_mask_256(dist_dark < 22.0)
                comp = arr * (1.0 - ext_dark[:, :, None]) + p0_lit * ext_dark[:, :, None]
                ra = np.clip(self.sh.rim_alpha * 0.90, 0.0, 1.0)[:, :, None]
                comp = comp * (1.0 - ra) + 255.0 * ra
                return self.pkg.package_cleanroom_576(comp, bg_base, "porcelain", "dark", 0.90)
            else:
                # Clear mode for Spotify, Netflix, X
                _, _, _, spec_m, _, _ = compute_glass_meniscus(fg_a, max_d=22, r_peak=6.8, r_falloff=4.8)
                shad = blur_offset_mask(fg_a, 2, 9, 16.0)
                p0_shad = np.clip(p0_lit - (12.5 + 8.0 * bf) * shad[:, :, None], 0.0, 255.0)
                rel_lum = np.clip(px_lum / 220.0, 0.35, 1.0)
                a_cl = np.clip((0.88 - 0.24 * u_y) * rel_lum + 0.34 * spec_m, 0.0, 0.98) * fg_a
                comp = p0_shad * (1.0 - a_cl[:, :, None]) + 255.0 * a_cl[:, :, None]
                return self.pkg.package_cleanroom_576(comp, bg_base, "porcelain", "clear", 1.0)

        # Case 2: Brand-colored vivid plates (`whatsapp.png`, `instagram.png`, `reddit.png`)
        if filename in ("whatsapp.png", "instagram.png"):
            # White foreground glyph over vivid brand plate
            fg_a = np.clip(((px_lum - 210.0) / 34.0) * np.clip((0.26 - px_sat) / 0.18, 0.0, 1.0), 0.0, 1.0)
            shad = blur_offset_mask(fg_a, 2, 10, 17.0)
            _, _, _, spec_m, _, _ = compute_glass_meniscus(fg_a, max_d=24, r_peak=7.6, r_falloff=5.2)
            p0_shad = np.clip(p0_lit - (15.5 if mode == "dark" else (12.5 + 8.0 * bf)) * shad[:, :, None], 0.0, 255.0)
            if mode == "dark":
                if filename == "whatsapp.png":
                    tint = np.stack([52.0 - 22.0 * u_y, 234.0 - 44.0 * u_y, 118.0 - 32.0 * u_y], axis=-1)
                else:
                    # Vivid Instagram diagonal sunset-magenta-violet gradient on glyph
                    tx = np.clip((self.sh.X - 200.0) / 624.0, 0.0, 1.0)
                    c_bl = np.array([255.0, 206.0, 36.0], np.float32)
                    c_mid = np.array([255.0, 38.0, 114.0], np.float32)
                    c_tr = np.array([168.0, 52.0, 255.0], np.float32)
                    d_ig = np.clip(0.55 * tx + 0.45 * (1.0 - u_y), 0.0, 1.0)
                    tint = np.where(
                        (d_ig < 0.5)[:, :, None],
                        c_bl * (1.0 - 2.0 * d_ig[:, :, None]) + c_mid * (2.0 * d_ig[:, :, None]),
                        c_mid * (1.0 - 2.0 * (d_ig - 0.5)[:, :, None]) + c_tr * (2.0 * (d_ig - 0.5)[:, :, None]),
                    )
                fg_col = np.clip(tint + (255.0 - tint) * (0.28 * spec_m)[:, :, None], 0.0, 255.0)
                comp = p0_shad * (1.0 - fg_a[:, :, None]) + fg_col * fg_a[:, :, None]
                return self.pkg.package_cleanroom_576(comp, bg_base, "porcelain", "dark", 1.0)
            else:
                a_cl = np.clip((0.93 - 0.30 * u_y) + 0.36 * spec_m, 0.0, 0.98) * fg_a
                comp = p0_shad * (1.0 - a_cl[:, :, None]) + 255.0 * a_cl[:, :, None]
                return self.pkg.package_cleanroom_576(comp, bg_base, "porcelain", "clear", 1.0)

        if filename == "reddit.png":
            dist_or = np.linalg.norm(arr - bg_med[None, None, :], axis=-1)
            ext_or = exterior_flood_mask_256(dist_or < 38.0)
            fg_a = np.clip(1.0 - ext_or, 0.0, 1.0)
            shad = blur_offset_mask(fg_a, 2, 10, 17.0)
            p0_shad = np.clip(p0_lit - (16.0 if mode == "dark" else (12.5 + 8.0 * bf)) * shad[:, :, None], 0.0, 255.0)
            if mode == "dark":
                comp = p0_shad * (1.0 - fg_a[:, :, None]) + arr * fg_a[:, :, None]
                return self.pkg.package_cleanroom_576(comp, bg_base, "porcelain", "dark", 1.0)
            else:
                _, _, _, spec_m, _, _ = compute_glass_meniscus(fg_a, max_d=24, r_peak=7.6, r_falloff=5.2)
                # Preserve Snoo eyes/smile contrast via luminance modulation inside Snoo mask
                eye_mod = np.clip((px_lum - 80.0) / 160.0, 0.25, 1.0)
                a_cl = np.clip(((0.92 - 0.24 * u_y) * eye_mod) + 0.30 * spec_m, 0.0, 0.98) * fg_a
                comp = p0_shad * (1.0 - a_cl[:, :, None]) + 255.0 * a_cl[:, :, None]
                return self.pkg.package_cleanroom_576(comp, bg_base, "porcelain", "clear", 1.0)

        # Case 3: Light / White / Porcelain plates (Google suite, Telegram, ChatGPT)
        dist_bg = np.linalg.norm(arr - bg_row_col, axis=-1)
        if filename == "chatgpt.png":
            # Reject subtle 235..245 ambient shadow on ChatGPT's #F5F5F5 plate so dark/clear strokes have zero halo
            fg_a = np.clip((220.0 - px_lum) / (220.0 - 44.0), 0.0, 1.0)
            inner_white = np.zeros_like(fg_a)
        elif filename in self.SOLID_BADGE_WITH_INNER_WHITE:
            ext_white = exterior_flood_mask_256(dist_bg < 34.0)
            fg_a = np.clip(1.0 - ext_white, 0.0, 1.0)
            fg_a = np.where(ext_white > 0.05, np.minimum(fg_a, np.clip((dist_bg - 10.0) / 28.0, 0.0, 1.0)), fg_a)
            inner_white = np.clip(((px_lum - 225.0) / 24.0) * np.clip((0.20 - px_sat) / 0.14, 0.0, 1.0), 0.0, 1.0) * fg_a
        else:
            fg_a = np.clip((dist_bg - 10.0) / 28.0, 0.0, 1.0)
            inner_white = np.zeros_like(fg_a)

        shad = blur_offset_mask(fg_a, 2, 10, 17.0)
        p0_shad = np.clip(p0_lit - (15.5 if mode == "dark" else (12.5 + 8.0 * bf)) * shad[:, :, None], 0.0, 255.0)
        _, _, _, spec_m, _, _ = compute_glass_meniscus(fg_a, max_d=22, r_peak=7.0, r_falloff=5.0)

        if mode == "dark":
            if filename == "chatgpt.png":
                # Invert ChatGPT black knot to crisp silver-white Liquid Glass with exact stroke weight
                knot_col = np.stack([248.0 - 18.0 * u_y, 250.0 - 16.0 * u_y, 255.0 - 12.0 * u_y], axis=-1)
                knot_lit = np.clip(knot_col + (255.0 - knot_col) * (0.28 * spec_m)[:, :, None], 0.0, 255.0)
                comp = p0_shad * (1.0 - fg_a[:, :, None]) + knot_lit * fg_a[:, :, None]
            else:
                # De-halo light-plate edge premultiplication from anti-aliased App Store edges
                a_safe = np.maximum(fg_a[:, :, None], 0.12)
                fg_dehalo = np.clip((arr - bg_row_col * (1.0 - fg_a[:, :, None])) / a_safe, 0.0, 255.0)
                fg_rgb = np.where(fg_a[:, :, None] > 0.85, arr, fg_dehalo)
                fg_rgb = np.clip(fg_rgb + (255.0 - fg_rgb) * (0.16 * spec_m)[:, :, None], 0.0, 255.0)
                comp = p0_shad * (1.0 - fg_a[:, :, None]) + fg_rgb * fg_a[:, :, None]
            return self.pkg.package_cleanroom_576(comp, bg_base, "porcelain", "dark", 1.0)
        else:
            # Clear mode: 2-tier frosted crystal if badge has inner white symbol (YouTube, YT Music, Keep, Telegram)
            if filename in self.SOLID_BADGE_WITH_INNER_WHITE:
                badge_a = (0.50 - 0.14 * u_y) + 0.28 * spec_m
                sym_a = 0.96 - 0.10 * u_y
                a_cl = np.clip(badge_a * (1.0 - inner_white) + sym_a * inner_white, 0.0, 0.98) * fg_a
            else:
                a_cl = np.clip((0.92 - 0.28 * u_y) + 0.34 * spec_m, 0.0, 0.98) * fg_a
            comp = p0_shad * (1.0 - a_cl[:, :, None]) + 255.0 * a_cl[:, :, None]
            return self.pkg.package_cleanroom_576(comp, bg_base, "porcelain", "clear", 1.0)


# ==============================================================================
# COMPLETE 40-PACKAGE CATALOG & RRO DENSITY BUILDER
# ==============================================================================
def build_all(asset_out_dir: str, appstore_cache_dir: str, rro_staging_dir: str) -> dict:
    os.makedirs(asset_out_dir, exist_ok=True)
    print(f"[ios26-v1.5] Initializing 1024x1024 SharedSquircleOptics & 1536->576 (108dp) Packager...")
    shared = SharedSquircleOptics(MASTER_72DP)
    packager = Adaptive108dpPackager(shared)
    adapter = AppStoreCounterpartAdapter(shared, packager, appstore_cache_dir)

    # 1. Exact untouched v1.3 Trio
    ph_ren = CleanRoomPhoneIconRenderer(shared)
    ms_ren = CleanRoomMessagesIconRenderer(shared)
    st_ren = CleanRoomSettingsIconRenderer(shared)

    # 2. Clean-Room System & Category-3 Multi-Plane Renderers
    as_ren = CleanRoomAppStoreIconRenderer(shared)
    cam_ren = CleanRoomCameraIconRenderer(shared)
    clk_ren = CleanRoomClockIconRenderer(shared)
    fil_ren = CleanRoomFilesIconRenderer(shared)
    cnt_ren = CleanRoomContactsIconRenderer(shared)
    cal_ren = CleanRoomCalculatorIconRenderer(shared)
    rec_ren = CleanRoomVoiceMemosIconRenderer(shared)
    ota_ren = CleanRoomSystemUpdateIconRenderer(shared)
    stk_ren = CleanRoomSimToolkitIconRenderer(shared)
    wb_ren = CleanRoomWellbeingIconRenderer(shared)
    pts_ren = CleanRoomRealmeSettingsIconRenderer(shared)

    # Clean-room fallbacks for Gmail, Photos, Chrome if App Store cache absent
    gm_fb = CleanRoomGmailIconRenderer(shared)
    gp_fb = CleanRoomGooglePhotosIconRenderer(shared)
    ch_fb = CleanRoomChromeIconRenderer(shared)

    def render_v13_pkg(ren, palette: str, mode: str) -> Image.Image:
        info = ren.render(mode, out_size=MASTER_72DP, return_dict=True)
        bg_base, _, _ = shared.plane0_rgb(palette, mode)
        return packager.package_cleanroom_576(info["comp_rgb_float"], bg_base, palette, mode, 1.0)

    def render_cleanroom_pkg(ren, mode: str) -> Image.Image:
        comp, bg_base, pal, rim_s = ren.render_float(mode)
        return packager.package_cleanroom_576(comp, bg_base, pal, mode, rim_s)

    def render_legacy_fb(ren, palette: str):
        class _Wrap:
            def render_float(self_inner, mode: str):
                inf = ren.render(mode, out_size=MASTER_72DP, return_dict=True)
                bg_b, _, _ = shared.plane0_rgb(palette, mode)
                return inf["comp_rgb_float"], bg_b, palette, 1.0
        return _Wrap()

    # Exact 40 required packages mapped to canonical render keys so shared icons compute once
    package_specs = [
        # Tier A — Approved v1.3 Trio (byte-for-byte identical interior)
        ("com.google.android.dialer", "Phone", "tier_a_v13", "v13_phone"),
        ("com.realme.samurai.dialer", "Phone", "tier_a_v13", "v13_phone"),
        ("com.google.android.apps.messaging", "Messages", "tier_a_v13", "v13_messages"),
        ("com.realme.samurai.messages", "Messages", "tier_a_v13", "v13_messages"),
        ("com.android.settings", "Settings", "tier_a_v13", "v13_settings"),
        # Tier A — Option A App Store & Clean-Room iOS 26 System Counterparts
        ("com.android.vending", "App Store", "tier_a_system", "cr_appstore"),
        ("com.google.android.GoogleCameraEng", "Camera", "tier_a_system", "cr_camera"),
        ("com.android.camera2", "Camera", "tier_a_system", "cr_camera"),
        ("com.android.deskclock", "Clock", "tier_a_system", "cr_clock"),
        ("com.google.android.deskclock", "Clock", "tier_a_system", "cr_clock"),
        ("com.google.android.documentsui", "Files", "tier_a_system", "as_apple_files"),
        ("com.android.documentsui", "Files", "tier_a_system", "as_apple_files"),
        ("com.google.android.contacts", "Contacts", "tier_a_system", "as_apple_contacts"),
        ("com.android.contacts", "Contacts", "tier_a_system", "as_apple_contacts"),
        ("com.google.android.calculator", "Calculator", "tier_a_system", "as_apple_calculator"),
        ("com.android.calculator2", "Calculator", "tier_a_system", "as_apple_calculator"),
        ("com.google.android.apps.recorder", "Recorder", "tier_a_system", "as_apple_voice_memos"),
        # Tier B — Google Suite Official iOS App Store Counterparts
        ("com.google.android.gm", "Gmail", "tier_b_appstore", "as_gmail"),
        ("com.google.android.apps.photos", "Photos", "tier_b_appstore", "as_google_photos"),
        ("com.android.chrome", "Chrome", "tier_b_appstore", "as_chrome"),
        ("org.cromite.cromite", "Chrome", "tier_b_appstore", "as_chrome"),
        ("com.google.android.youtube", "YouTube", "tier_b_appstore", "as_youtube"),
        ("com.google.android.apps.youtube.music", "YT Music", "tier_b_appstore", "as_youtube_music"),
        ("com.google.android.apps.maps", "Maps", "tier_b_appstore", "as_google_maps"),
        ("com.google.android.apps.docs", "Drive", "tier_b_appstore", "as_google_drive"),
        ("com.google.android.googlequicksearchbox", "Google", "tier_b_appstore", "as_google"),
        ("com.google.android.videos", "Google TV", "tier_b_appstore", "as_google_tv"),
        ("com.google.android.apps.tachyon", "Meet", "tier_b_appstore", "as_google_meet"),
        ("com.google.android.calendar", "Calendar", "tier_b_appstore", "as_google_calendar"),
        ("com.google.android.keep", "Keep", "tier_b_appstore", "as_google_keep"),
        # Tier C — Bespoke Multi-Plane Liquid Glass Android System Apps
        ("com.realme.ota", "System Update", "tier_c_bespoke", "cr_ota"),
        ("com.android.stk", "SIM Toolkit", "tier_c_bespoke", "cr_stk"),
        ("com.google.android.apps.wellbeing", "Wellbeing", "tier_c_bespoke", "cr_wellbeing"),
        ("com.realme.samurai.parts", "Realme Settings", "tier_c_bespoke", "cr_parts"),
        # Tier B — Popular 3rd-Party Official iOS App Store Counterparts
        ("com.whatsapp", "WhatsApp", "tier_b_appstore", "as_whatsapp"),
        ("com.instagram.android", "Instagram", "tier_b_appstore", "as_instagram"),
        ("org.telegram.messenger", "Telegram", "tier_b_appstore", "as_telegram"),
        ("com.spotify.music", "Spotify", "tier_b_appstore", "as_spotify"),
        ("com.openai.chatgpt", "ChatGPT", "tier_b_appstore", "as_chatgpt"),
        ("com.netflix.mediaclient", "Netflix", "tier_b_appstore", "as_netflix"),
        ("com.reddit.frontpage", "Reddit", "tier_b_appstore", "as_reddit"),
        ("com.twitter.android", "X", "tier_b_appstore", "as_x_twitter"),
    ]

    key_dispatch = {
        "v13_phone": lambda m: render_v13_pkg(ph_ren, "emerald", m),
        "v13_messages": lambda m: render_v13_pkg(ms_ren, "emerald", m),
        "v13_settings": lambda m: render_v13_pkg(st_ren, "slate", m),
        "cr_appstore": lambda m: render_cleanroom_pkg(as_ren, m),
        "cr_camera": lambda m: render_cleanroom_pkg(cam_ren, m),
        "cr_clock": lambda m: render_cleanroom_pkg(clk_ren, m),
        "as_apple_files": lambda m: adapter.render_576("apple_files.png", m, fil_ren),
        "as_apple_contacts": lambda m: adapter.render_576("apple_contacts.png", m, cnt_ren),
        "as_apple_calculator": lambda m: adapter.render_576("apple_calculator.png", m, cal_ren),
        "as_apple_voice_memos": lambda m: adapter.render_576("apple_voice_memos.png", m, rec_ren),
        "as_gmail": lambda m: adapter.render_576("gmail.png", m, render_legacy_fb(gm_fb, "porcelain")),
        "as_google_photos": lambda m: adapter.render_576("google_photos.png", m, render_legacy_fb(gp_fb, "porcelain")),
        "as_chrome": lambda m: adapter.render_576("chrome.png", m, render_legacy_fb(ch_fb, "porcelain")),
        "as_youtube": lambda m: adapter.render_576("youtube.png", m),
        "as_youtube_music": lambda m: adapter.render_576("youtube_music.png", m),
        "as_google_maps": lambda m: adapter.render_576("google_maps.png", m),
        "as_google_drive": lambda m: adapter.render_576("google_drive.png", m),
        "as_google": lambda m: adapter.render_576("google.png", m),
        "as_google_tv": lambda m: adapter.render_576("google_tv.png", m),
        "as_google_meet": lambda m: adapter.render_576("google_meet.png", m),
        "as_google_calendar": lambda m: adapter.render_576("google_calendar.png", m),
        "as_google_keep": lambda m: adapter.render_576("google_keep.png", m),
        "cr_ota": lambda m: render_cleanroom_pkg(ota_ren, m),
        "cr_stk": lambda m: render_cleanroom_pkg(stk_ren, m),
        "cr_wellbeing": lambda m: render_cleanroom_pkg(wb_ren, m),
        "cr_parts": lambda m: render_cleanroom_pkg(pts_ren, m),
        "as_whatsapp": lambda m: adapter.render_576("whatsapp.png", m),
        "as_instagram": lambda m: adapter.render_576("instagram.png", m),
        "as_telegram": lambda m: adapter.render_576("telegram.png", m),
        "as_spotify": lambda m: adapter.render_576("spotify.png", m),
        "as_chatgpt": lambda m: adapter.render_576("chatgpt.png", m),
        "as_netflix": lambda m: adapter.render_576("netflix.png", m),
        "as_reddit": lambda m: adapter.render_576("reddit.png", m),
        "as_x_twitter": lambda m: adapter.render_576("x_twitter.png", m),
    }

    rendered_cache = {}
    modes = ("default", "dark", "clear")
    manifest_pkgs = {}

    for idx, (pkg, label, cat, rkey) in enumerate(package_specs, 1):
        entry = {"label": label, "category": cat}
        for mode in modes:
            ck = (rkey, mode)
            if ck not in rendered_cache:
                rendered_cache[ck] = key_dispatch[rkey](mode)
            im_576 = rendered_cache[ck]
            out_fn = f"{pkg}_{mode}.webp"
            out_path = os.path.join(asset_out_dir, out_fn)
            im_576.save(out_path, format="WEBP", lossless=True, quality=100, method=4)
            entry[mode] = out_fn
        manifest_pkgs[pkg] = entry
        print(f"  [{idx:02d}/{len(package_specs):02d}] {pkg} ({label}) -> default/dark/clear 576x576 lossless WebP")

    manifest_data = {
        "version": "1.5",
        "canvas_px": OUT_108DP_PX,
        "canvas_dp": 108,
        "inner_72dp_px": INNER_72DP_PX,
        "bleed_18dp_px": BLEED_OUT_PX,
        "inner_bounds": [BLEED_OUT_PX, BLEED_OUT_PX, BLEED_OUT_PX + INNER_72DP_PX, BLEED_OUT_PX + INNER_72DP_PX],
        "modes": list(modes),
        "format": "webp_lossless",
        "play_store_variant": "Option A (1:1 iOS 26 App Store)",
        "package_count": len(package_specs),
        "package_list": [p[0] for p in package_specs],
        "packages": manifest_pkgs,
    }
    manifest_path = os.path.join(asset_out_dir, "manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as fh:
        json.dump(manifest_data, fh, indent=2)

    # Also build updated 108dp RRO density sets for Gmail & Google Photos in WSL staging (outside git)
    if rro_staging_dir:
        a17_root = os.path.dirname(SCRIPT_DIR)
        _, p0_porc, _ = shared.plane0_rgb("porcelain", "default")
        for rro_name, cache_png, prefix_name in [
            ("SamuraiGmailOverlay", "gmail.png", "ios26_gmail"),
            ("SamuraiPhotosOverlay", "google_photos.png", "ios26_photos"),
        ]:
            raw_arr = adapter.load_raw_1024(cache_png)
            if raw_arr is None:
                continue
            src_rro = os.path.join(a17_root, "overlays", rro_name)
            dst_rro = os.path.join(rro_staging_dir, rro_name)
            if os.path.exists(dst_rro):
                shutil.rmtree(dst_rro)
            shutil.copytree(src_rro, dst_rro)

            _, lit_72, _ = packager.package_unmasked_appstore_default_576(raw_arr)
            bdr_med = np.median(raw_arr[120:904, 60:105].reshape(-1, 3), axis=0)
            fg_m = np.clip((np.linalg.norm(raw_arr - bdr_med[None, None, :], axis=-1) - 8.0) / 24.0, 0.0, 1.0)
            bg_108, fg_108 = build_adaptive_108dp_layers_colored(
                shared,
                "porcelain",
                {"p0_lit": p0_porc, "comp_rgb_float": lit_72},
                fg_m,
            )
            res_dst = os.path.join(dst_rro, "res")
            save_density_set(bg_108, res_dst, "drawable", f"{prefix_name}_bg.png")
            save_density_set(fg_108, res_dst, "drawable", f"{prefix_name}_fg.png")
            print(f"  [RRO] Staged updated 108dp density set for {rro_name} -> {dst_rro}")

    return manifest_data


def main() -> int:
    parser = argparse.ArgumentParser(description="Build Samurai iOS 26 v1.5 108dp (576x576) Icon Asset Pack")
    parser.add_argument(
        "--out-dir",
        default="/var/tmp/rmx1931-a17/work/ios26_v15_assets/assets/ios26_icons",
        help="Destination directory for 576x576 lossless WebP icons and manifest.json",
    )
    parser.add_argument(
        "--appstore-dir",
        default="/var/tmp/rmx1931-a17/work/ios26_appstore_cache",
        help="Cache directory containing 1024x1024 official iOS App Store PNGs (outside git)",
    )
    parser.add_argument(
        "--rro-staging-dir",
        default="/var/tmp/rmx1931-a17/work/apk-build-rros-v15/staging",
        help="WSL staging directory for updated Gmail & Photos 108dp RRO overlays",
    )
    args = parser.parse_args()

    manifest = build_all(args.out_dir, args.appstore_dir, args.rro_staging_dir)
    files = sorted(os.listdir(args.out_dir))
    total_bytes = sum(os.path.getsize(os.path.join(args.out_dir, f)) for f in files)
    print(
        f"[ios26-v1.5] Complete: {len(files)} files ({manifest['package_count']} packages x 3 modes + manifest.json), "
        f"{total_bytes:,} bytes ({total_bytes / (1024 * 1024):.2f} MiB) in {args.out_dir}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
