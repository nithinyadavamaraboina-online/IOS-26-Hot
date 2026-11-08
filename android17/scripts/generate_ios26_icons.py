#!/usr/bin/env python3
"""
Clean-Room Parametric 1:1 iOS 26 Multi-Plane App Icon & Restrained Liquid Glass Generator (v3.0).

Synthesizes the Step 1 system icon trio — Phone (Dialer), Messages, and Settings — across
Default, Dark, and Clear appearances from pure mathematical geometry and multi-plane Liquid Glass
optics:
  - G2 continuous-corner Bezier squircle (r = 0.2250 * W, matching SamuraiHardwareOverlay config_icon_mask)
  - Plane 0 satin vertical gradient clamped to [90..934] on 1024px + hair-fine 22px (2.15% W)
    directional 34-deg perimeter specular catchlight (zero thick inner bevel ring, zero dark trench)
  - Subtractive Linear-Burn Z-elevation shadow beneath floating foreground glass planes
  - Graded translucent frosted Liquid Glass foreground glyphs:
      * Phone: anti-diagonal symmetric handset + d=9.6px convex meniscus ridge
      * Messages: super-oval (rx=375, ry=309, k=0.5808) + parametric lower-left tail + vertical Y translucency
      * Settings: 3-plane watchmaker escapement — recessed 24-tooth translucent inner gear ring (Plane 1)
        beneath foreground 36-tooth shroud + 3 filleted radial spokes (0, 120, 240 deg) + central hub (Plane 2)
  - Exports 108dp AdaptiveIconDrawable density sets (mdpi..xxxhdpi) where the inner [18/108..90/108]
    72dp viewport aligns to 0.0px with Android 17's config_icon_mask while Plane 0 bleeds seamlessly
    across the outer 18dp margin for zero-seam launcher spring parallax.

100% clean-room mathematical synthesis in float32. Zero Apple IPSW / proprietary assets used.
"""

import math
import os
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

MASTER_72DP = 1024
CANVAS_108DP = 1536          # 1024 * (108 / 72) = 1536 exact integer!
BLEED_18DP = 256             # (1536 - 1024) // 2 = 256 exact integer!

DENSITIES = [
    ("mdpi", 108),
    ("hdpi", 162),
    ("xhdpi", 216),
    ("xxhdpi", 324),
    ("xxxhdpi", 432),
]


# ==============================================================================
# CORE DISTANCE TRANSFORM, G2 SQUIRCLE & CLEAN-ROOM SDF PRIMITIVES
# ==============================================================================
def edt_inside(mask: np.ndarray, max_d: int = 46) -> np.ndarray:
    """Exact windowed 2D Euclidean distance transform inside `mask` (with 1px False border)."""
    padded = np.pad(mask, 1, mode="constant", constant_values=False)
    inf_val = float((max_d + 4) ** 2)
    f = np.where(~padded, 0.0, inf_val)

    def edt_1d_rows(g: np.ndarray) -> np.ndarray:
        out = g.copy()
        for dx in range(1, max_d + 2):
            d2 = float(dx * dx)
            out[:, dx:] = np.minimum(out[:, dx:], g[:, :-dx] + d2)
            out[:, :-dx] = np.minimum(out[:, :-dx], g[:, dx:] + d2)
        return out

    return np.sqrt(edt_1d_rows(edt_1d_rows(f).T).T)[1:-1, 1:-1]


def build_continuous_squircle_mask(size: int = 1024, r_frac: float = 0.2250, ss: int = 4) -> np.ndarray:
    """4x supersampled G2 continuous-corner squircle alpha mask in [0.0, 1.0]."""
    s = float(size * ss)
    r = r_frac * s
    p = min(0.5 * s, 1.52866483 * r)
    c_1088, c_0868, c_0631 = 1.08849296 * r, 0.86840694 * r, 0.63149379 * r
    c_0372, c_0169, c_0074 = 0.37282383 * r, 0.16905956 * r, 0.07491139 * r

    def cubic(a, b, c, d, n=48):
        t = np.linspace(0.0, 1.0, n, endpoint=False)
        omt = 1.0 - t
        return (
            (omt[:, None] ** 3) * a
            + 3.0 * (omt[:, None] ** 2) * t[:, None] * b
            + 3.0 * omt[:, None] * (t[:, None] ** 2) * c
            + (t[:, None] ** 3) * d
        )

    tl = np.vstack([
        cubic(np.array([0.0, p]), np.array([0.0, c_1088]), np.array([0.0, c_0868]), np.array([c_0074, c_0631])),
        cubic(np.array([c_0074, c_0631]), np.array([c_0169, c_0372]), np.array([c_0372, c_0169]), np.array([c_0631, c_0074])),
        cubic(np.array([c_0631, c_0074]), np.array([c_0868, 0.0]), np.array([c_1088, 0.0]), np.array([p, 0.0])),
        [[p, 0.0]],
    ])
    tr = np.column_stack([s - tl[::-1, 0], tl[::-1, 1]])
    br = np.column_stack([s - tl[:, 0], s - tl[:, 1]])
    bl = np.column_stack([tl[::-1, 0], s - tl[::-1, 1]])

    hi = Image.new("L", (int(s), int(s)), 0)
    ImageDraw.Draw(hi).polygon([tuple(pt) for pt in np.vstack([tl, tr, br, bl])], fill=255)
    return np.array(hi.resize((size, size), Image.Resampling.BOX), dtype=np.float32) / 255.0


def build_cleanroom_handset_mask(size: int = 1024, ss: int = 4) -> np.ndarray:
    """4x supersampled clean-room parametric handset mask in [0.0, 1.0]."""
    def cubic_bez(p0, p1, p2, p3, n=64):
        t = np.linspace(0.0, 1.0, n, endpoint=False)[1:]
        omt = 1.0 - t
        pts = (
            (omt[:, None] ** 3) * p0
            + 3.0 * (omt[:, None] ** 2) * t[:, None] * p1
            + 3.0 * omt[:, None] * (t[:, None] ** 2) * p2
            + (t[:, None] ** 3) * p3
        )
        return pts[:, 0], pts[:, 1]

    u1 = np.linspace(0.0, 124.0, 60)
    v1 = -83.8 + 0.00053 * (u1 ** 2)
    ua, va = cubic_bez(np.array([124.0, -75.65]), np.array([153.5, -72.0]), np.array([163.0, -41.5]), np.array([172.0, -9.2]), 64)
    ub, vb = cubic_bez(np.array([172.0, -9.2]), np.array([181.2, 26.8]), np.array([194.5, 41.6]), np.array([220.0, 47.38]), 64)
    uc = np.linspace(220.0, 354.0, 60)[1:]
    vc = 47.38 + 0.229 * (uc - 220.0)
    ud1, vd1 = cubic_bez(np.array([354.0, 78.07]), np.array([385.5, 84.8]), np.array([414.4, 54.0]), np.array([414.4, 0.0]), 64)
    ud2, vd2 = cubic_bez(np.array([414.4, 0.0]), np.array([414.4, -44.5]), np.array([395.5, -69.5]), np.array([376.0, -84.85]), 64)

    u_bot = np.linspace(376.0, 0.0, 140)[1:]
    w = u_bot / 376.0
    v_bot = -186.75 + 63.8 * (w ** 2) + 21.0 * (w ** 4) + 17.1 * (w ** 6)

    u_top_half = np.concatenate([u1, ua, ub, uc, ud1, ud2])
    v_top_half = np.concatenate([v1, va, vb, vc, vd1, vd2])
    u_loop = np.concatenate([-u_top_half[::-1], u_top_half[1:], u_bot, -u_bot[::-1][1:]])
    v_loop = np.concatenate([v_top_half[::-1], v_top_half[1:], v_bot, v_bot[::-1][1:]])

    scale = size / 1024.0
    s2 = np.sqrt(2.0)
    c = (size * ss) / 2.0
    xs = c + ((u_loop + v_loop) / s2) * scale * ss
    ys = c + ((u_loop - v_loop) / s2) * scale * ss

    hi = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(hi).polygon(list(zip(xs.tolist(), ys.tolist())), fill=255)
    return np.array(hi.resize((size, size), Image.Resampling.BOX), dtype=np.float32) / 255.0


def build_cleanroom_messages_mask(size: int = 1024, ss: int = 4) -> np.ndarray:
    """4x supersampled clean-room parametric speech-bubble mask in [0.0, 1.0]."""
    def cbez(p0, p1, p2, p3, n=80):
        t = np.linspace(0.0, 1.0, n, endpoint=False)
        omt = 1.0 - t
        return (
            (omt[:, None] ** 3) * p0
            + 3.0 * (omt[:, None] ** 2) * t[:, None] * p1
            + 3.0 * omt[:, None] * (t[:, None] ** 2) * p2
            + (t[:, None] ** 3) * p3
        )

    cx, cy = 512.0, 494.0
    rx, ry = 375.0, 309.0
    kx, ky = 0.58075 * rx, 0.57916 * ry
    pts = np.vstack([
        cbez(np.array([cx, cy - ry]), np.array([cx + kx, cy - ry]), np.array([cx + rx, cy - ky]), np.array([cx + rx, cy]), 90),
        cbez(np.array([cx + rx, cy]), np.array([cx + rx, cy + ky]), np.array([cx + kx, cy + ry]), np.array([cx, cy + ry]), 90),
        cbez(np.array([512.0, 803.0]), np.array([434.9, 803.0]), np.array([365.4, 786.7]), np.array([308.3, 757.8]), 70),
        cbez(np.array([308.3, 757.8]), np.array([275.9, 782.9]), np.array([227.0, 803.0]), np.array([185.0, 803.0]), 60),
        cbez(np.array([185.0, 803.0]), np.array([175.8, 803.0]), np.array([173.2, 793.0]), np.array([180.5, 787.5]), 36),
        cbez(np.array([180.5, 787.5]), np.array([185.8, 783.5]), np.array([192.3, 778.2]), np.array([199.4, 766.9]), 36),
        cbez(np.array([199.4, 766.9]), np.array([218.7, 737.3]), np.array([220.0, 702.5]), np.array([184.7, 652.3]), 60),
        cbez(np.array([184.7, 652.3]), np.array([154.1, 606.8]), np.array([137.0, 553.1]), np.array([137.0, 494.0]), 60),
        cbez(np.array([cx - rx, cy]), np.array([cx - rx, cy - ky]), np.array([cx - kx, cy - ry]), np.array([cx, cy - ry]), 90),
    ]) * ((size * ss) / 1024.0)

    hi = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(hi).polygon([tuple(p) for p in pts], fill=255)
    return np.array(hi.resize((size, size), Image.Resampling.BOX), dtype=np.float32) / 255.0


def smin_circ(a: np.ndarray, b: np.ndarray, r: float) -> np.ndarray:
    u = np.maximum(r - a, 0.0)
    v = np.maximum(r - b, 0.0)
    return np.maximum(r, np.minimum(a, b)) - np.hypot(u, v)


def smax_circ(a: np.ndarray, b: np.ndarray, r: float) -> np.ndarray:
    return -smin_circ(-a, -b, r)


def gear_ring_sdf(
    xx: np.ndarray,
    yy: np.ndarray,
    cx: float,
    cy: float,
    r_inner: float,
    r_root: float,
    r_tip: float,
    n_teeth: int,
    ha_root: float,
    ha_tip: float,
    rf_root: float,
    rf_tip: float,
    phase_deg: float = 0.0,
) -> np.ndarray:
    """Closed-form 2D polar SDF for a tapered watchmaker gear ring with circular root & tip fillets."""
    dx, dy = xx - cx, yy - cy
    r = np.hypot(dx, dy)
    ang = np.arctan2(dy, dx) - math.radians(phase_deg)
    sec = 2.0 * math.pi / float(n_teeth)
    a_abs = np.abs(((ang + 0.5 * sec) % sec) - 0.5 * sec)
    u_rad = r * np.cos(a_abs)
    v_tan = r * np.sin(a_abs)
    w_root = r_root * math.sin(math.radians(ha_root))
    w_tip = r_tip * math.sin(math.radians(ha_tip))
    slope = (w_root - w_tip) / max(r_tip - r_root, 1e-4)
    d_flank = (v_tan - (w_root - slope * (u_rad - r_root))) / math.hypot(1.0, slope)
    d_tooth = smax_circ(d_flank, r - r_tip, rf_tip)
    d_outer = smin_circ(r - r_root, d_tooth, rf_root)
    return np.maximum(d_outer, r_inner - r)


def build_cleanroom_settings_masks(size: int = 1024, ss: int = 3):
    """
    Synthesizes the two foreground watchmaker gear masks for iOS 26 Settings:
      - Plane 1: Recessed 24-tooth translucent inner gear ring (r_in=184.55, r_root=223.93, r_tip=269.20)
      - Plane 2: Foreground 36-tooth gear shroud (r_in=305.20, r_root=351.61, r_tip=409.85)
                 + 3 filleted radial spokes at 0, 120, 240 deg + central hub (r_bore=27.80, r_hub=53.90)
    """
    n_hi = size * ss
    scale = float(ss) * (size / 1024.0)
    yy, xx = (np.mgrid[0:n_hi, 0:n_hi].astype(np.float32) + 0.5) / scale
    cx, cy = 512.0, 512.0
    dx, dy = xx - cx, yy - cy
    rr = np.hypot(dx, dy)
    ang = np.arctan2(dy, dx)

    sdf_p1 = gear_ring_sdf(xx, yy, cx, cy, 184.55, 223.93, 269.20, 24, 4.23, 2.50, 12.9, 12.5, phase_deg=-0.17)
    sdf_sh = gear_ring_sdf(xx, yy, cx, cy, 27.80, 351.61, 409.85, 36, 3.01, 1.88, 11.5, 12.5, phase_deg=-0.13)

    sec3 = 2.0 * math.pi / 3.0
    a_win = np.abs(((ang - math.radians(60.0) + 0.5 * sec3) % sec3) - 0.5 * sec3)
    dist_sp = rr * np.sin((0.5 * sec3) - a_win)
    d_win = smax_circ(smax_circ(53.90 - rr, 29.15 - dist_sp, 26.0), rr - 305.20, 58.0)
    sdf_p2 = np.maximum(sdf_sh, -d_win)

    cov_p1 = np.clip(0.5 - sdf_p1 * scale, 0.0, 1.0).reshape(size, ss, size, ss).mean(axis=(1, 3))
    cov_p2 = np.clip(0.5 - sdf_p2 * scale, 0.0, 1.0).reshape(size, ss, size, ss).mean(axis=(1, 3))
    return cov_p1.astype(np.float32), cov_p2.astype(np.float32)


# ==============================================================================
# SHARED PLANE 0 BACKPLATE & CLEAR GLASS OPTICS
# ==============================================================================
class SharedSquircleOptics:
    """Precomputes the 1024x1024 G2 squircle mask, Plane 0 ramp, and 22px 34-deg specular rim."""

    def __init__(self, size: int = 1024):
        self.S = size
        self.sq_mask = build_continuous_squircle_mask(self.S, r_frac=0.2250, ss=4)
        y_g, x_g = np.mgrid[0:self.S, 0:self.S].astype(np.float32)
        self.X = x_g
        self.Y = y_g
        self.t_bg = np.clip((self.Y - 90.0) / (934.0 - 90.0), 0.0, 1.0)

        self.d_sq = edt_inside(self.sq_mask > 0.5, max_d=30)
        r_c = 0.2250 * self.S
        cx_c = np.clip(self.X, r_c, (self.S - 1.0) - r_c)
        cy_c = np.clip(self.Y, r_c, (self.S - 1.0) - r_c)
        dx_c, dy_c = self.X - cx_c, self.Y - cy_c
        dl_c = np.hypot(dx_c, dy_c) + 1e-6
        s_diag = -((dx_c / dl_c) + (dy_c / dl_c)) / np.sqrt(2.0)
        self.s_diag = s_diag
        rim_peak = 0.09 + 0.65 * (np.maximum(0.0, s_diag) ** 1.22) + 0.47 * (np.maximum(0.0, -s_diag) ** 2.0)
        self.rim_alpha = rim_peak * np.clip(1.0 - (self.d_sq - 1.0) / 21.5, 0.0, 1.0)

    def plane0_rgb(self, palette, mode: str, backdrop_rgb: Image.Image = None):
        t_bg = self.t_bg
        bright_factor = 0.0
        is_porcelain_default = False
        if mode == "default":
            if isinstance(palette, tuple) and len(palette) == 2:
                top_c, bot_c = palette
                bg_base = np.stack([
                    top_c[0] * (1.0 - t_bg) + bot_c[0] * t_bg,
                    top_c[1] * (1.0 - t_bg) + bot_c[1] * t_bg,
                    top_c[2] * (1.0 - t_bg) + bot_c[2] * t_bg,
                ], axis=-1)
                rim_str = 0.92
            elif palette == "porcelain":
                # Cool satin porcelain white vertical ramp (#FFFFFF at y<=90 -> #F1F2F6 at y>=934)
                bg_base = np.stack([
                    255.0 * (1.0 - t_bg) + 241.0 * t_bg,
                    255.0 * (1.0 - t_bg) + 242.0 * t_bg,
                    255.0 * (1.0 - t_bg) + 246.0 * t_bg,
                ], axis=-1)
                rim_str = 1.00
                is_porcelain_default = True
            elif palette == "emerald":
                bg_base = np.stack([
                    82.88 * (1.0 - t_bg) + 39.02 * t_bg,
                    239.96 * (1.0 - t_bg) + 192.02 * t_bg,
                    108.88 * (1.0 - t_bg) + 65.02 * t_bg,
                ], axis=-1)
                rim_str = 1.00
            elif palette == "slate":
                bg_base = np.stack([
                    168.04 * (1.0 - t_bg) + 95.88 * t_bg,
                    168.04 * (1.0 - t_bg) + 95.88 * t_bg,
                    173.91 * (1.0 - t_bg) + 99.96 * t_bg,
                ], axis=-1)
                rim_str = 1.00
            else:
                raise ValueError(palette)
        elif mode == "dark":
            g_val = 48.96 * (1.0 - t_bg) + 19.89 * t_bg
            bg_base = np.repeat(g_val[:, :, None], 3, axis=-1)
            rim_str = 1.00
        elif mode == "clear":
            if backdrop_rgb is not None:
                bd_1024 = backdrop_rgb.convert("RGB").resize((self.S, self.S), Image.Resampling.BICUBIC)
                bd_arr = np.array(bd_1024.filter(ImageFilter.GaussianBlur(radius=20.0)), dtype=np.float32)
            else:
                bd_arr = np.stack([
                    np.zeros_like(t_bg),
                    114.0 * (1.0 - t_bg) + 73.0 * t_bg,
                    148.0 * (1.0 - t_bg) + 136.0 * t_bg,
                ], axis=-1)
            mean_lum = float(0.299 * bd_arr[:, :, 0].mean() + 0.587 * bd_arr[:, :, 1].mean() + 0.114 * bd_arr[:, :, 2].mean())
            bright_factor = float(np.clip((mean_lum - 145.0) / 85.0, 0.0, 1.0))
            l_px = (0.299 * bd_arr[:, :, 0] + 0.587 * bd_arr[:, :, 1] + 0.114 * bd_arr[:, :, 2])[:, :, None]
            bd_refr = np.clip(l_px + (bd_arr - l_px) * 1.12, 0.0, 255.0)

            veil_dark_a = (0.215 * (1.0 - t_bg) + 0.165 * t_bg)[:, :, None]
            veil_light_a = (0.240 * (1.0 - t_bg) + 0.300 * t_bg)[:, :, None]
            veil_a = veil_dark_a * (1.0 - bright_factor) + veil_light_a * bright_factor
            veil_tint = (
                np.array([255.0, 255.0, 255.0], dtype=np.float32) * (1.0 - bright_factor)
                + np.array([140.0, 150.0, 166.0], dtype=np.float32) * bright_factor
            )
            bg_base = bd_refr * (1.0 - veil_a) + veil_tint[None, None, :] * veil_a
            rim_str = 0.92 + 0.08 * bright_factor
        else:
            raise ValueError(mode)

        ra = np.clip(self.rim_alpha * rim_str, 0.0, 1.0)[:, :, None]
        p0_lit = bg_base * (1.0 - ra) + 255.0 * ra
        if is_porcelain_default:
            # Hair-fine outer 3px cool-silver (#DCDFE6, ~-10 sRGB) micro-rim definition for daylight crispness
            micro_w = (np.clip(1.0 - self.d_sq / 3.2, 0.0, 1.0) ** 1.35) * (
                0.42 + 0.46 * np.clip(0.5 - 0.5 * self.s_diag, 0.0, 1.0)
            )
            silver_rim = np.array([220.0, 223.0, 230.0], dtype=np.float32)[None, None, :]
            p0_lit = p0_lit * (1.0 - micro_w[:, :, None]) + silver_rim * micro_w[:, :, None]
        if mode == "clear" and bright_factor > 0.05:
            inner_def = (0.07 * bright_factor * np.exp(-(((self.d_sq - 20.0) / 8.0) ** 2)))[:, :, None]
            p0_lit = p0_lit * (1.0 - inner_def)
        return bg_base, p0_lit, bright_factor

    def to_rgba_img(self, rgb_arr: np.ndarray, out_size: int = 1024) -> Image.Image:
        rgba = np.zeros((self.S, self.S, 4), dtype=np.uint8)
        rgba[:, :, :3] = np.clip(np.round(rgb_arr), 0, 255).astype(np.uint8)
        rgba[:, :, 3] = np.clip(np.round(self.sq_mask * 255.0), 0, 255).astype(np.uint8)
        im = Image.fromarray(rgba, mode="RGBA")
        if out_size != self.S:
            im = im.resize((out_size, out_size), Image.Resampling.LANCZOS)
        return im


# ==============================================================================
# 1:1 CLEAN-ROOM RENDERERS FOR PHONE, MESSAGES & SETTINGS
# ==============================================================================
class CleanRoomPhoneIconRenderer:
    def __init__(self, shared: SharedSquircleOptics):
        self.sh = shared
        self.S = shared.S
        self.ph_mask = build_cleanroom_handset_mask(self.S, ss=4)

        shad_src = Image.fromarray(np.uint8(np.clip(self.ph_mask * 255.0, 0, 255)), mode="L")
        shad_shift = Image.new("L", (self.S, self.S), 0)
        shad_shift.paste(shad_src, (2, 10))
        self.shad_field = np.array(shad_shift.filter(ImageFilter.GaussianBlur(radius=18.0)), dtype=np.float32) / 255.0

        d_ph = edt_inside(self.ph_mask > 0.5, max_d=46)
        d_sm = np.array(
            Image.fromarray(np.clip(d_ph * 4.0, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius=2.6)),
            dtype=np.float32,
        ) / 4.0
        gy, gx = np.gradient(d_sm)
        nl = np.hypot(gx, gy) + 1e-6
        nx, ny = -gx / nl, -gy / nl

        l34 = 0.555 * shared.X + 0.832 * shared.Y
        self.u_34 = np.clip((l34 - 385.0) / (1035.0 - 385.0), 0.0, 1.0)
        self.ridge_bell = np.where(
            d_ph < 9.6,
            0.12 + 0.88 * (np.sin(np.clip(d_ph / 9.6, 0.0, 1.0) * (np.pi / 2.0)) ** 1.45),
            np.exp(-(((d_ph - 9.6) / 7.0) ** 2)),
        )
        self.lip_micro = np.exp(-(((d_ph - 2.4) / 2.0) ** 2))
        self.top_a = np.maximum(0.0, -ny)
        self.bot_a = np.maximum(0.0, ny)
        self.tl_a = np.maximum(0.0, -(0.68 * nx + 0.73 * ny))
        self.br_a = np.maximum(0.0, 0.55 * nx + 0.83 * ny)

    def render(self, mode: str = "default", out_size: int = 1024, backdrop_rgb: Image.Image = None, return_dict: bool = False):
        bg_base, p0_lit, bf = self.sh.plane0_rgb("emerald", mode, backdrop_rgb)
        burn = 15.5 if mode != "clear" else (13.5 + 10.5 * bf)
        p0_shad = np.clip(p0_lit - burn * self.shad_field[:, :, None], 0.0, 255.0)

        cush_dir = 0.74 * (self.top_a ** 1.30) + 0.50 * (self.bot_a ** 1.40) + 0.34 * (self.tl_a ** 1.35) + 0.24 * (self.br_a ** 1.45) + 0.03
        cush = np.clip(self.ridge_bell * cush_dir, 0.0, 1.0)
        spark = self.lip_micro * (0.35 * (self.tl_a ** 1.3) + 0.24 * (self.br_a ** 1.4) + 0.15 * (self.top_a ** 1.4))
        u = self.u_34

        if mode == "default":
            ab = 0.992 - 0.408 * (u ** 0.92)
            ae = np.clip(ab + (1.0 - ab) * (0.94 * cush + 0.45 * spark), 0.0, 1.0)
            sheen = 0.42 * self.ridge_bell * (0.72 * (self.top_a ** 1.45) + 0.48 * (self.bot_a ** 1.50) + 0.28 * (self.br_a ** 1.4))
            a_white_total = np.clip(1.0 - (1.0 - ae) * (1.0 - sheen), 0.0, 1.0)
            fg_rgb = bg_base * (1.0 - a_white_total[:, :, None]) + 255.0 * a_white_total[:, :, None]
        elif mode == "dark":
            em_col = np.stack([82.88 * (1.0 - u) + 39.02 * u, 239.96 * (1.0 - u) + 192.02 * u, 108.88 * (1.0 - u) + 65.02 * u], axis=-1)
            ab = np.clip((0.968 - 0.392 * (u ** 0.92)) + 0.27 * self.ridge_bell * ((self.bot_a ** 1.35) + 0.45 * (self.br_a ** 1.4)), 0.0, 1.0)
            fg_rgb = bg_base * (1.0 - ab[:, :, None]) + em_col * ab[:, :, None]
            wspec = (self.ridge_bell * (0.27 * (self.top_a ** 1.30) + 0.19 * (self.tl_a ** 1.25) + 0.13 * (self.bot_a ** 1.40) + 0.08 * (self.br_a ** 1.4) + 0.01) + 0.14 * spark)[:, :, None]
            fg_rgb = np.clip(fg_rgb + (255.0 - fg_rgb) * wspec, 0.0, 255.0)
            a_white_total = ab
        else:
            bmin = 0.35 + 0.20 * bf
            ab = (0.960 + 0.025 * bf) - (0.960 - bmin) * (u ** 0.88)
            ae = np.clip(ab + (1.0 - ab) * (0.54 * cush + 0.35 * spark), 0.0, 1.0)
            sheen = 0.26 * self.ridge_bell * (0.80 * (self.top_a ** 1.4) + 0.52 * (self.bot_a ** 1.5) + 0.30 * (self.tl_a ** 1.3))
            a_white_total = np.clip(1.0 - (1.0 - ae) * (1.0 - sheen), 0.0, 1.0)
            fg_rgb = bg_base * (1.0 - a_white_total[:, :, None]) + 255.0 * a_white_total[:, :, None]

        comp_rgb = p0_shad * (1.0 - self.ph_mask[:, :, None]) + fg_rgb * self.ph_mask[:, :, None]
        im = self.sh.to_rgba_img(comp_rgb, out_size)
        if not return_dict:
            return im
        return {
            "composite": im,
            "comp_rgb_float": comp_rgb,
            "bg_base": bg_base,
            "p0_lit": p0_lit,
            "shad_field": self.shad_field,
            "burn": burn,
            "fg_mask": self.ph_mask,
            "fg_white_alpha": a_white_total,
        }


class CleanRoomMessagesIconRenderer:
    def __init__(self, shared: SharedSquircleOptics):
        self.sh = shared
        self.S = shared.S
        self.msg_mask = build_cleanroom_messages_mask(self.S, ss=4)

        shad_src = Image.fromarray(np.uint8(np.clip(self.msg_mask * 255.0, 0, 255)), mode="L")
        shad_shift = Image.new("L", (self.S, self.S), 0)
        shad_shift.paste(shad_src, (0, 10))
        self.shad_field = np.array(shad_shift.filter(ImageFilter.GaussianBlur(radius=18.0)), dtype=np.float32) / 255.0

        d_m = edt_inside(self.msg_mask > 0.5, max_d=38)
        d_sm = np.array(
            Image.fromarray(np.clip(d_m * 4.0, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius=2.6)),
            dtype=np.float32,
        ) / 4.0
        gy, gx = np.gradient(d_sm)
        nl = np.hypot(gx, gy) + 1e-6
        nx, ny = -gx / nl, -gy / nl

        self.u_my = np.clip((shared.Y - 265.0) / (775.0 - 265.0), 0.0, 1.0)
        self.u_my_em = np.clip((shared.Y - 220.0) / (775.0 - 220.0), 0.0, 1.0)
        self.ridge_m = np.where(
            d_m < 9.5,
            0.12 + 0.88 * (np.sin(np.clip(d_m / 9.5, 0.0, 1.0) * (np.pi / 2.0)) ** 1.45),
            np.exp(-(((d_m - 9.5) / 6.8) ** 2)),
        )
        self.lip_m = np.exp(-(((d_m - 2.4) / 2.0) ** 2))
        self.top_m = np.maximum(0.0, -ny)
        self.bot_m = np.maximum(0.0, ny)
        self.side_m = np.abs(nx)
        self.cush_m = self.ridge_m * (0.58 * (self.top_m ** 1.35) + 0.46 * (self.bot_m ** 1.45) + 0.24 * (self.side_m ** 1.6)) + 0.22 * self.lip_m

    def render(self, mode: str = "default", out_size: int = 1024, backdrop_rgb: Image.Image = None, return_dict: bool = False):
        bg_base, p0_lit, bf = self.sh.plane0_rgb("emerald", mode, backdrop_rgb)
        burn = 13.5 if mode != "clear" else (12.5 + 9.5 * bf)
        p0_shad = np.clip(p0_lit - burn * self.shad_field[:, :, None], 0.0, 255.0)

        u = self.u_my
        if mode == "default":
            ab = 1.000 - 0.462 * (u ** 1.05)
            a_white = np.clip(ab + (1.0 - ab) * self.cush_m, 0.0, 1.0)
            fg_rgb = bg_base * (1.0 - a_white[:, :, None]) + 255.0 * a_white[:, :, None]
        elif mode == "dark":
            ue = self.u_my_em
            em_col = np.stack([82.88 * (1.0 - ue) + 39.02 * ue, 239.96 * (1.0 - ue) + 192.02 * ue, 108.88 * (1.0 - ue) + 65.02 * ue], axis=-1)
            ab = np.clip((0.985 - 0.465 * (u ** 1.02)) + 0.22 * self.ridge_m * ((self.bot_m ** 1.4) + 0.35 * (self.side_m ** 1.6)), 0.0, 1.0)
            fg_rgb = bg_base * (1.0 - ab[:, :, None]) + em_col * ab[:, :, None]
            wspec = (self.ridge_m * (0.26 * (self.top_m ** 1.35) + 0.12 * (self.bot_m ** 1.45) + 0.10 * (self.side_m ** 1.6)) + 0.10 * self.lip_m)[:, :, None]
            fg_rgb = np.clip(fg_rgb + (255.0 - fg_rgb) * wspec, 0.0, 255.0)
            a_white = ab
        else:
            bmin = 0.40 + 0.16 * bf
            ab = (0.960 + 0.02 * bf) - (0.960 - bmin) * (u ** 0.96)
            a_white = np.clip(ab + (1.0 - ab) * (0.62 * self.cush_m), 0.0, 1.0)
            fg_rgb = bg_base * (1.0 - a_white[:, :, None]) + 255.0 * a_white[:, :, None]

        comp_rgb = p0_shad * (1.0 - self.msg_mask[:, :, None]) + fg_rgb * self.msg_mask[:, :, None]
        im = self.sh.to_rgba_img(comp_rgb, out_size)
        if not return_dict:
            return im
        return {
            "composite": im,
            "comp_rgb_float": comp_rgb,
            "bg_base": bg_base,
            "p0_lit": p0_lit,
            "shad_field": self.shad_field,
            "burn": burn,
            "fg_mask": self.msg_mask,
            "fg_white_alpha": a_white,
        }


class CleanRoomSettingsIconRenderer:
    def __init__(self, shared: SharedSquircleOptics):
        self.sh = shared
        self.S = shared.S
        self.p1_m, self.p2_m = build_cleanroom_settings_masks(self.S, ss=3)

        sh1_im = Image.new("L", (self.S, self.S), 0)
        sh1_im.paste(Image.fromarray(np.uint8(np.clip(self.p1_m * 255.0, 0, 255)), "L"), (0, 8))
        self.shad_s1 = np.array(sh1_im.filter(ImageFilter.GaussianBlur(radius=15.0)), dtype=np.float32) / 255.0

        sh2_im = Image.new("L", (self.S, self.S), 0)
        sh2_im.paste(Image.fromarray(np.uint8(np.clip(self.p2_m * 255.0, 0, 255)), "L"), (0, 10))
        self.shad_s2 = np.array(sh2_im.filter(ImageFilter.GaussianBlur(radius=18.0)), dtype=np.float32) / 255.0

        def bevel_y(mask_f, max_d=24, rp=4.8, rw=3.6):
            d = edt_inside(mask_f > 0.5, max_d=max_d)
            d_sm = np.array(
                Image.fromarray(np.clip(d * 5.0, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius=1.8)),
                dtype=np.float32,
            ) / 5.0
            gy, gx = np.gradient(d_sm)
            nl = np.hypot(gx, gy) + 1e-6
            nx, ny = -gx / nl, -gy / nl
            rb = np.where(d < rp, 0.16 + 0.84 * (np.sin(np.clip(d / rp, 0.0, 1.0) * (np.pi / 2.0)) ** 1.35), np.exp(-(((d - rp) / rw) ** 2)))
            lip = np.exp(-(((d - 1.8) / 1.5) ** 2))
            return rb, lip, np.maximum(0.0, -ny), np.maximum(0.0, ny), np.abs(nx)

        rb1, lip1, top1, bot1, _ = bevel_y(self.p1_m, 22, 4.6, 3.6)
        rb2, lip2, top2, bot2, side2 = bevel_y(self.p2_m, 26, 5.2, 4.0)

        u_s1y = np.clip((shared.Y - 260.0) / (760.0 - 260.0), 0.0, 1.0)
        a_p1_body = 0.425 - 0.080 * u_s1y
        self.a_p1 = np.clip(a_p1_body + (1.0 - a_p1_body) * (rb1 * (0.30 * (top1 ** 1.4) + 0.16 * (bot1 ** 1.5)) + 0.12 * lip1), 0.0, 1.0)

        u_s2y = np.clip((shared.Y - 150.0) / (855.0 - 150.0), 0.0, 1.0)
        a_p2_body = 0.940 - 0.445 * (u_s2y ** 1.02)
        a_p2_rim = rb2 * (0.72 * (top2 ** 1.45) + 0.38 * (bot2 ** 1.55) + 0.12 * (side2 ** 1.6)) + 0.22 * lip2 * (top2 + 0.5 * bot2)
        self.a_p2 = np.clip(a_p2_body + (1.0 - a_p2_body) * a_p2_rim, 0.0, 1.0)

    def render(self, mode: str = "default", out_size: int = 1024, backdrop_rgb: Image.Image = None, return_dict: bool = False):
        bg_base, p0_lit, bf = self.sh.plane0_rgb("slate", mode, backdrop_rgb)
        b1 = 4.2 if mode != "clear" else (3.8 + 2.5 * bf)
        b2 = 9.5 if mode != "clear" else (8.2 + 4.5 * bf)
        p1_sc = 1.02 if mode == "default" else (0.96 if mode == "dark" else (0.76 + 0.14 * bf))
        p2_sc = 1.01 if mode == "default" else (0.965 if mode == "dark" else (0.82 + 0.12 * bf))

        p0_shad = np.clip(p0_lit - (b1 * self.shad_s1 + b2 * self.shad_s2)[:, :, None], 0.0, 255.0)
        ap1 = np.clip(self.a_p1 * p1_sc, 0.0, 1.0)
        ap2 = np.clip(self.a_p2 * p2_sc, 0.0, 1.0)

        c1 = p0_shad * (1.0 - (ap1 * self.p1_m)[:, :, None]) + 255.0 * (ap1 * self.p1_m)[:, :, None]
        c1_sh = np.clip(c1 - (b2 * 0.65 * self.shad_s2 * self.p1_m)[:, :, None], 0.0, 255.0)
        comp_rgb = c1_sh * (1.0 - self.p2_m[:, :, None]) + (p0_shad * (1.0 - ap2[:, :, None]) + 255.0 * ap2[:, :, None]) * self.p2_m[:, :, None]

        im = self.sh.to_rgba_img(comp_rgb, out_size)
        if not return_dict:
            return im
        return {
            "composite": im,
            "comp_rgb_float": comp_rgb,
            "bg_base": bg_base,
            "p0_lit": p0_lit,
            "shad_s1": self.shad_s1,
            "shad_s2": self.shad_s2,
            "b1": b1,
            "b2": b2,
            "p1_m": self.p1_m,
            "p2_m": self.p2_m,
            "ap1": ap1,
            "ap2": ap2,
        }


# ==============================================================================
# STEP 2: 1:1 BESPOKE GOOGLE BRAND ICONS (GMAIL, GOOGLE PHOTOS, CHROME)
#         + UNIVERSAL PARAMETRIC iOS 26 ICON SYNTHESIZER + CONCENTRIC DOCK
# ==============================================================================
def compute_glass_meniscus(mask_f: np.ndarray, max_d: int = 28, r_peak: float = 8.0, r_falloff: float = 5.8):
    """Computes inward EDT convex meniscus ridge, micro-lip, and inward Sobel normals (nx, ny)."""
    d = edt_inside(mask_f > 0.5, max_d=max_d)
    d_sm = np.array(
        Image.fromarray(np.clip(d * 4.5, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius=2.1)),
        dtype=np.float32,
    ) / 4.5
    gy, gx = np.gradient(d_sm)
    nl = np.hypot(gx, gy) + 1e-6
    nx, ny = -gx / nl, -gy / nl
    ridge = np.where(
        d < r_peak,
        0.14 + 0.86 * (np.sin(np.clip(d / r_peak, 0.0, 1.0) * (np.pi / 2.0)) ** 1.40),
        np.exp(-(((d - r_peak) / r_falloff) ** 2)),
    )
    lip = np.exp(-(((d - 2.0) / 1.7) ** 2))
    top_a = np.maximum(0.0, -ny)
    bot_a = np.maximum(0.0, ny)
    tl_a = np.maximum(0.0, -(0.56 * nx + 0.83 * ny))
    br_a = np.maximum(0.0, 0.56 * nx + 0.83 * ny)
    spec = np.clip(
        ridge * (0.48 * (tl_a ** 1.30) + 0.30 * (top_a ** 1.35) + 0.24 * (br_a ** 1.45) + 0.14 * (bot_a ** 1.45))
        + lip * (0.24 * (tl_a ** 1.25) + 0.14 * (br_a ** 1.35)),
        0.0,
        1.0,
    )
    return d, ridge, lip, spec, nx, ny


def blur_offset_mask(mask_f: np.ndarray, dx: int, dy: int, sigma: float) -> np.ndarray:
    """Shifts a [0..1] float mask by (dx, dy) and applies GaussianBlur(sigma)."""
    h, w = mask_f.shape
    src = Image.fromarray(np.uint8(np.clip(np.round(mask_f * 255.0), 0, 255)), mode="L")
    dst = Image.new("L", (w, h), 0)
    dst.paste(src, (dx, dy))
    return np.array(dst.filter(ImageFilter.GaussianBlur(radius=sigma)), dtype=np.float32) / 255.0


class CleanRoomGmailIconRenderer:
    """
    1:1 iOS 26 Multi-Plane Gmail Icon Renderer (com.google.android.gm):
      - Plane 0: Porcelain Satin (#FFFFFF->#F1F2F6 + 3px #DCDFE6 micro-rim) / Dark Carbon / Clear Frost
      - Plane 1A/1B: Left Blue (#4285F4) & Right Green (#34A853) vertical glass pillars
      - Plane 2A/2B: Upper-Left Deep Crimson (#C5221F) & Upper-Right Gold (#FBBC04) folded shoulders
                     casting directional fold Z-shadows (dy=+6px, sigma=10px) onto vertical pillars
      - Plane 3: Center Coral-Red (#EA4335) V-chevron glass ribbon + d=7.6px 34-deg convex meniscus
    """

    def __init__(self, shared: SharedSquircleOptics, ss: int = 4):
        self.sh = shared
        self.S = shared.S
        n_hi = self.S * ss
        scale_s = self.S / 1024.0
        yy, xx = (np.mgrid[0:n_hi, 0:n_hi].astype(np.float32) + 0.5) / (ss * scale_s)

        # Map 640px-wide M (62.5% of 1024px) centered at (512, 516) into canonical 90x90 [28.5..61.5]x[31.5..57.0]
        k = 640.0 / 33.0
        u = 28.5 + (xx - 192.0) / k
        v = 31.5 + (yy - 268.7273) / k
        du = np.abs(u - 45.0)

        # Exact G1-continuous outer M silhouette
        inside = (du <= 16.5) & (v >= 31.5) & (v <= 57.0)
        # Bottom outer rounded corners (r = 3.0 at du=13.5, v=54.0)
        bot_corner_out = (du > 13.5) & (v > 54.0) & (np.hypot(du - 13.5, v - 54.0) > 3.0)
        inside &= ~bot_corner_out
        # Bottom V-arch cutout (du < 7.5: v <= 52.5 - 0.8 * du)
        inside &= ~((du < 7.5) & (v > (52.5 - 0.8 * du)))
        # Top V-valley (slope -0.8 tangent to shoulder circle r=3.75 at du=12.75, v=35.25)
        du_tan = 10.4074
        v_valley = 40.6476
        inside &= ~((du <= du_tan) & (v < (v_valley - 0.8 * du)))
        top_sh_out = (du > du_tan) & (v < 35.25) & (np.hypot(du - 12.75, v - 35.25) > 3.75)
        inside &= ~top_sh_out

        v_fold_l = 52.5 - 0.8 * (45.0 - u)
        v_fold_r = 52.5 - 0.8 * (u - 45.0)

        def ds(bmask):
            return bmask.astype(np.float32).reshape(self.S, ss, self.S, ss).mean(axis=(1, 3))

        self.m_total = ds(inside)
        self.m_blue = ds(inside & (u <= 37.5) & (v >= v_fold_l))
        self.m_green = ds(inside & (u >= 52.5) & (v >= v_fold_r))
        self.m_crimson = ds(inside & (u <= 37.5) & (v < v_fold_l))
        self.m_yellow = ds(inside & (u >= 52.5) & (v < v_fold_r))
        self.m_red = ds(inside & (u > 37.5) & (u < 52.5))
        self.m_upper = np.clip(self.m_crimson + self.m_yellow + self.m_red, 0.0, 1.0)

        self.shad_p0 = blur_offset_mask(self.m_total, 2, 10, 18.0)
        self.shad_fold = blur_offset_mask(self.m_upper, 0, 6, 10.0)
        self.shad_v = blur_offset_mask(self.m_red, 0, 5, 8.5)

        _, _, _, self.spec_outer, _, _ = compute_glass_meniscus(self.m_total, max_d=26, r_peak=7.6, r_falloff=5.5)
        _, _, _, self.spec_upper, _, _ = compute_glass_meniscus(self.m_upper, max_d=22, r_peak=6.8, r_falloff=4.8)
        self.t_y = np.clip((shared.Y - 268.0) / (764.0 - 268.0), 0.0, 1.0)

    def render(self, mode: str = "default", out_size: int = 1024, backdrop_rgb: Image.Image = None, return_dict: bool = False):
        bg_base, p0_lit, bf = self.sh.plane0_rgb("porcelain", mode, backdrop_rgb)
        burn_p0 = 14.5 if mode == "default" else (16.5 if mode == "dark" else (13.0 + 9.0 * bf))
        p0_shad = np.clip(p0_lit - burn_p0 * self.shad_p0[:, :, None], 0.0, 255.0)
        ty = self.t_y

        if mode in ("default", "dark"):
            c_blue = np.stack([78.0 * (1.0 - ty) + 51.0 * ty, 146.0 * (1.0 - ty) + 113.0 * ty, 249.0 * (1.0 - ty) + 224.0 * ty], axis=-1)
            c_green = np.stack([62.0 * (1.0 - ty) + 38.0 * ty, 186.0 * (1.0 - ty) + 145.0 * ty, 96.0 * (1.0 - ty) + 66.0 * ty], axis=-1)
            c_crim = np.stack([209.0 * (1.0 - ty) + 178.0 * ty, 44.0 * (1.0 - ty) + 27.0 * ty, 39.0 * (1.0 - ty) + 24.0 * ty], axis=-1)
            c_yel = np.stack([255.0 * (1.0 - ty) + 242.0 * ty, 204.0 * (1.0 - ty) + 166.0 * ty, 38.0 * (1.0 - ty) + 0.0 * ty], axis=-1)
            c_red = np.stack([245.0 * (1.0 - ty) + 217.0 * ty, 76.0 * (1.0 - ty) + 48.0 * ty, 62.0 * (1.0 - ty) + 37.0 * ty], axis=-1)

            raw_fg = (
                c_blue * self.m_blue[:, :, None]
                + c_green * self.m_green[:, :, None]
                + c_crim * self.m_crimson[:, :, None]
                + c_yel * self.m_yellow[:, :, None]
                + c_red * self.m_red[:, :, None]
            )
            # Normalize by total coverage inside M so antialiased seams have zero dark gap
            norm_m = np.maximum(self.m_total[:, :, None], 1e-4)
            fg_col = raw_fg / norm_m

            # Graded glass translucency (97% top -> 86% bottom) over Plane 0
            a_glass = (0.972 - 0.105 * (ty ** 0.95))[:, :, None]
            fg_glass = bg_base * (1.0 - a_glass) + fg_col * a_glass

            # Real subtractive fold Z-shadows where upper folds overlap pillars & center V meets shoulders
            fold_burn = (36.0 * self.shad_fold * np.clip(self.m_blue + self.m_green, 0.0, 1.0)) + (
                18.0 * self.shad_v * np.clip(self.m_crimson + self.m_yellow, 0.0, 1.0)
            )
            fg_shaded = np.clip(fg_glass - fold_burn[:, :, None], 0.0, 255.0)

            # Inward 34-deg convex glass meniscus highlight
            spec_tot = np.clip(0.32 * self.spec_outer + 0.16 * self.spec_upper * self.m_upper, 0.0, 0.46)[:, :, None]
            fg_final = np.clip(fg_shaded + (255.0 - fg_shaded) * spec_tot, 0.0, 255.0)
        else:
            # Clear mode: 5-piece sculpted luminance white glass hierarchy preserving folded-M architecture
            a_pillars = (0.56 - 0.14 * ty) * np.clip(self.m_blue + self.m_green, 0.0, 1.0)
            a_crim = (0.70 - 0.12 * ty) * self.m_crimson
            a_yel = (0.81 - 0.12 * ty) * self.m_yellow
            a_red = (0.95 - 0.18 * ty) * self.m_red
            a_raw = (a_pillars + a_crim + a_yel + a_red) / np.maximum(self.m_total, 1e-4)
            fg_glass = bg_base * (1.0 - a_raw[:, :, None]) + 255.0 * a_raw[:, :, None]
            fold_burn = (18.0 * self.shad_fold * np.clip(self.m_blue + self.m_green, 0.0, 1.0)) + (
                10.0 * self.shad_v * np.clip(self.m_crimson + self.m_yellow, 0.0, 1.0)
            )
            fg_shaded = np.clip(fg_glass - fold_burn[:, :, None], 0.0, 255.0)
            spec_tot = np.clip(0.36 * self.spec_outer + 0.20 * self.spec_upper * self.m_upper, 0.0, 0.50)[:, :, None]
            fg_final = np.clip(fg_shaded + (255.0 - fg_shaded) * spec_tot, 0.0, 255.0)

        comp_rgb = p0_shad * (1.0 - self.m_total[:, :, None]) + fg_final * self.m_total[:, :, None]
        im = self.sh.to_rgba_img(comp_rgb, out_size)
        if not return_dict:
            return im
        return {
            "composite": im,
            "p0_only": self.sh.to_rgba_img(p0_lit, out_size),
            "p0_with_shadow": self.sh.to_rgba_img(p0_shad, out_size),
            "planes_flat_fold": self.sh.to_rgba_img(
                p0_shad * (1.0 - self.m_total[:, :, None]) + fg_shaded * self.m_total[:, :, None], out_size
            ),
            "meniscus_field": np.clip((0.68 * self.spec_outer + 0.32 * self.spec_upper) * self.m_total, 0.0, 1.0),
            "comp_rgb_float": comp_rgb,
            "p0_lit": p0_lit,
        }


class CleanRoomGooglePhotosIconRenderer:
    """
    1:1 iOS 26 Multi-Plane Google Photos Icon Renderer (com.google.android.apps.photos):
      - Exact 2025 rounded 4-petal continuous-curve pinwheel (Top Red #EA4335, Right Yellow #FBBC04,
        Bottom Green #34A853, Left Blue #4285F4)
      - 4-fold rotationally symmetric pinwheel hub weave + graded 98%->84% petal glass translucency
      - d=8.2px 34-deg inward convex meniscus ridge on each semi-circular glass petal + Plane 0 Z-shadow
    """

    def __init__(self, shared: SharedSquircleOptics, ss: int = 4):
        self.sh = shared
        self.S = shared.S
        n_hi = self.S * ss
        scale_s = self.S / 1024.0
        yy, xx = (np.mgrid[0:n_hi, 0:n_hi].astype(np.float32) + 0.5) / (ss * scale_s)

        # Canonical 33-unit pinwheel scaled to 668px (65.2% of 1024px) around (512, 512)
        k = 668.0 / 33.0
        dx = (xx - 512.0) / k
        dy = (yy - 512.0) / k

        def sdf_petal_top(u_x: np.ndarray, u_y: np.ndarray) -> np.ndarray:
            # Exact G1-continuous union: right semicircle R=9 at (0.69, -7.5) + left rounded stem r=2.19
            d_circ = np.hypot(u_x - 0.69, u_y + 7.50) - 9.00
            qx = np.abs(u_x - 0.69) - (2.19 - 2.19)
            qy = np.abs(u_y + 7.50) - (9.00 - 2.19)
            d_box = np.hypot(np.maximum(qx, 0.0), np.maximum(qy, 0.0)) + np.minimum(np.maximum(qx, qy), 0.0) - 2.19
            return np.where(u_x >= 0.69, d_circ, d_box)

        def cov_from_sdf(sdf_arr: np.ndarray) -> np.ndarray:
            px_sdf = sdf_arr * k * ss * scale_s
            return np.clip(0.5 - px_sdf, 0.0, 1.0).reshape(self.S, ss, self.S, ss).mean(axis=(1, 3)).astype(np.float32)

        self.p_red = cov_from_sdf(sdf_petal_top(dx, dy))          # Top (12 o'clock, bulges right)
        self.p_yel = cov_from_sdf(sdf_petal_top(dy, -dx))         # Right (3 o'clock, bulges down)
        self.p_grn = cov_from_sdf(sdf_petal_top(-dx, -dy))        # Bottom (6 o'clock, bulges left)
        self.p_blu = cov_from_sdf(sdf_petal_top(-dy, dx))         # Left (9 o'clock, bulges up)
        self.p_union = np.clip(1.0 - (1.0 - self.p_red) * (1.0 - self.p_yel) * (1.0 - self.p_grn) * (1.0 - self.p_blu), 0.0, 1.0)

        self.shad_p0 = blur_offset_mask(self.p_union, 2, 10, 18.0)
        self.pets = [self.p_red, self.p_yel, self.p_grn, self.p_blu]
        self.pet_shads = [blur_offset_mask(p, 1, 4, 6.5) for p in self.pets]
        self.pet_specs = [compute_glass_meniscus(p, max_d=28, r_peak=8.2, r_falloff=5.8)[3] for p in self.pets]

        # Normalized radial/lobe translucency gradient (84% inner hub -> 98% outer rim)
        r_norm = np.clip(np.hypot(shared.X - 512.0, shared.Y - 512.0) / 334.0, 0.0, 1.0)
        self.lobe_t = r_norm

    def render(self, mode: str = "default", out_size: int = 1024, backdrop_rgb: Image.Image = None, return_dict: bool = False):
        bg_base, p0_lit, bf = self.sh.plane0_rgb("porcelain", mode, backdrop_rgb)
        burn_p0 = 14.0 if mode == "default" else (16.5 if mode == "dark" else (12.5 + 9.0 * bf))
        p0_shad = np.clip(p0_lit - burn_p0 * self.shad_p0[:, :, None], 0.0, 255.0)
        rt = self.lobe_t

        if mode in ("default", "dark"):
            cols = [
                np.stack([246.0 - 12.0 * rt, 92.0 - 25.0 * rt, 78.0 - 25.0 * rt], axis=-1),    # Red (#F65C4E -> #EA4335)
                np.stack([254.0 - 3.0 * rt, 214.0 - 26.0 * rt, 88.0 - 84.0 * rt], axis=-1),    # Yellow (#FED658 -> #FBBC04)
                np.stack([76.0 - 24.0 * rt, 194.0 - 26.0 * rt, 106.0 - 23.0 * rt], axis=-1),   # Green (#4CC26A -> #34A853)
                np.stack([98.0 - 32.0 * rt, 160.0 - 27.0 * rt, 255.0 - 11.0 * rt], axis=-1),   # Blue (#62A0FF -> #4285F4)
            ]
            # 4-fold symmetric cyclical pinwheel composite: each petal's straight stem overlaps the predecessor's bulge
            # Compute per-petal shaded translucent glass, then blend with 4-fold angular dominance in overlap zones
            dx_c = self.sh.X - 512.0
            dy_c = self.sh.Y - 512.0
            ang = np.arctan2(dy_c, dx_c)  # [-pi, pi]
            # Smooth cyclical weight for top-most petal in each 90-deg stem quadrant (-90 Red, 0 Yellow, +90 Green, 180 Blue)
            stem_angs = [-0.5 * np.pi, 0.0, 0.5 * np.pi, np.pi]
            a_lobe = 0.855 + 0.125 * (rt ** 0.85)

            acc_shaded = p0_shad.copy()
            acc_final = p0_shad.copy()
            # Two-pass symmetric pinwheel weave: base pass (tucked tails) then top stem pass
            dom_weights = []
            for k in range(4):
                da = np.abs(((ang - stem_angs[k] + np.pi) % (2.0 * np.pi)) - np.pi)
                dom_weights.append(np.clip(1.0 - (da - 0.22 * np.pi) / (0.18 * np.pi), 0.0, 1.0))

            for pass_id in (0, 1):
                for k in range(4):
                    pm = self.pets[k]
                    w_pass = (1.0 - dom_weights[k]) if pass_id == 0 else dom_weights[k]
                    eff_m = pm * w_pass
                    # Predecessor in clockwise stem order casts subtle hub shadow onto tucked tail
                    pred_sh = self.pet_shads[(k + 1) % 4] * (1.0 - dom_weights[k])
                    c_glass = bg_base * (1.0 - a_lobe[:, :, None]) + cols[k] * a_lobe[:, :, None]
                    c_sh = np.clip(c_glass - (22.0 * pred_sh)[:, :, None], 0.0, 255.0)
                    sp = (0.34 * self.pet_specs[k])[:, :, None]
                    c_lit = np.clip(c_sh + (255.0 - c_sh) * sp, 0.0, 255.0)
                    acc_shaded = acc_shaded * (1.0 - eff_m[:, :, None]) + c_sh * eff_m[:, :, None]
                    acc_final = acc_final * (1.0 - eff_m[:, :, None]) + c_lit * eff_m[:, :, None]

            comp_shaded = acc_shaded
            comp_rgb = acc_final
        else:
            # Clear mode: 4 overlapping frosted white glass petals with luminous central hub overlap
            a_pet_base = 0.54 + 0.34 * (rt ** 0.9)
            a_comb = np.zeros((self.S, self.S), dtype=np.float32)
            spec_comb = np.zeros((self.S, self.S), dtype=np.float32)
            for k in range(4):
                ak = a_pet_base * self.pets[k]
                a_comb = 1.0 - (1.0 - a_comb) * (1.0 - ak * 0.78)
                spec_comb = np.maximum(spec_comb, self.pet_specs[k] * self.pets[k])
            fg_sh = bg_base * (1.0 - a_comb[:, :, None]) + 255.0 * a_comb[:, :, None]
            fg_lit = np.clip(fg_sh + (255.0 - fg_sh) * (0.38 * spec_comb)[:, :, None], 0.0, 255.0)
            comp_shaded = p0_shad * (1.0 - self.p_union[:, :, None]) + fg_sh * self.p_union[:, :, None]
            comp_rgb = p0_shad * (1.0 - self.p_union[:, :, None]) + fg_lit * self.p_union[:, :, None]

        im = self.sh.to_rgba_img(comp_rgb, out_size)
        if not return_dict:
            return im
        spec_all = np.maximum.reduce([self.pet_specs[k] * self.pets[k] for k in range(4)])
        return {
            "composite": im,
            "p0_only": self.sh.to_rgba_img(p0_lit, out_size),
            "p0_with_shadow": self.sh.to_rgba_img(p0_shad, out_size),
            "planes_flat_fold": self.sh.to_rgba_img(comp_shaded, out_size),
            "meniscus_field": np.clip(spec_all, 0.0, 1.0),
            "comp_rgb_float": comp_rgb,
            "p0_lit": p0_lit,
        }


class CleanRoomChromeIconRenderer:
    """
    1:1 iOS 26 Multi-Plane Chrome Icon Renderer (com.android.chrome):
      - Concentric iris on 1024x1024: R_outer=324, R_ring=158, R_core=128
      - 3 exact 120-deg tangent-chord glass sectors (Red #EA4335, Yellow #FBBC04, Green #34A853)
        with directional chord-seam overlap Z-shadows
      - Recessed porcelain / dark-carbon / clear separator ring
      - Central 3D Sapphire Glass Lens (#5A9BFF -> #1A73E8) with d=8.0px 34-deg meniscus + internal caustic
    """

    def __init__(self, shared: SharedSquircleOptics, ss: int = 4):
        self.sh = shared
        self.S = shared.S
        n_hi = self.S * ss
        scale_s = self.S / 1024.0
        yy, xx = (np.mgrid[0:n_hi, 0:n_hi].astype(np.float32) + 0.5) / (ss * scale_s)
        dx = xx - 512.0
        dy = yy - 512.0
        rr = np.hypot(dx, dy)

        r_outer, r_ring, r_core = 324.0, 158.0, 128.0
        px_scale = ss * scale_s

        cov_outer = np.clip(0.5 - (rr - r_outer) * px_scale, 0.0, 1.0)
        cov_annulus = cov_outer * np.clip(0.5 - (r_ring - rr) * px_scale, 0.0, 1.0)
        cov_ring = cov_outer * np.clip(0.5 - (rr - r_ring) * px_scale, 0.0, 1.0) * np.clip(0.5 - (r_core - rr) * px_scale, 0.0, 1.0)
        cov_core = np.clip(0.5 - (rr - r_core) * px_scale, 0.0, 1.0)

        # Exact tangent-chord angle psi in [0, 2*pi):
        # psi = 0 along top horizontal ray (Red|Yellow), 2pi/3 along 4->6 o'clock ray (Yellow|Green),
        # 4pi/3 along 8->10 o'clock ray (Green|Red)
        phi_cw = np.arctan2(dx, -dy) % (2.0 * np.pi)
        alpha_tan = np.arccos(np.clip(r_ring / np.maximum(rr, r_ring), -1.0, 1.0))
        psi = (phi_cw - alpha_tan) % (2.0 * np.pi)

        twopi3 = 2.0 * np.pi / 3.0
        m_yel_hi = cov_annulus * ((psi >= 0.0) & (psi < twopi3)).astype(np.float32)
        m_grn_hi = cov_annulus * ((psi >= twopi3) & (psi < 2.0 * twopi3)).astype(np.float32)
        m_red_hi = cov_annulus * (psi >= 2.0 * twopi3).astype(np.float32)

        def ds(arr_hi):
            return arr_hi.reshape(self.S, ss, self.S, ss).mean(axis=(1, 3)).astype(np.float32)

        self.m_disc = ds(cov_outer)
        self.m_annulus = ds(cov_annulus)
        self.m_ring = ds(cov_ring)
        self.m_core = ds(cov_core)
        self.m_red = ds(m_red_hi)
        self.m_yel = ds(m_yel_hi)
        self.m_grn = ds(m_grn_hi)

        self.shad_p0 = blur_offset_mask(self.m_disc, 2, 10, 18.5)
        self.shad_core = blur_offset_mask(self.m_core, 1, 5, 8.5)
        # Directional chord-seam overlap shadows (Red->Yellow, Yellow->Green, Green->Red)
        self.sh_red_on_yel = blur_offset_mask(self.m_red, 0, 6, 8.5)
        self.sh_yel_on_grn = blur_offset_mask(self.m_yel, -5, 3, 8.5)
        self.sh_grn_on_red = blur_offset_mask(self.m_grn, 4, -4, 8.5)

        _, _, _, self.spec_ann, _, _ = compute_glass_meniscus(self.m_annulus, max_d=28, r_peak=8.0, r_falloff=5.8)
        _, _, _, self.spec_core, _, _ = compute_glass_meniscus(self.m_core, max_d=26, r_peak=8.0, r_falloff=5.6)

        self.t_y = np.clip((shared.Y - 188.0) / (836.0 - 188.0), 0.0, 1.0)
        dx0 = shared.X - 512.0
        dy0 = shared.Y - 512.0
        self.core_diag = np.clip(((dx0 + dy0) / (np.sqrt(2.0) * 128.0) + 1.0) * 0.5, 0.0, 1.0)
        # Subtle lower-right internal sapphire lens caustic glow
        self.core_caustic = np.exp(-((((dx0 - 38.0) ** 2) + ((dy0 - 44.0) ** 2)) / (2.0 * (46.0 ** 2))))

    def render(self, mode: str = "default", out_size: int = 1024, backdrop_rgb: Image.Image = None, return_dict: bool = False):
        bg_base, p0_lit, bf = self.sh.plane0_rgb("porcelain", mode, backdrop_rgb)
        burn_p0 = 14.5 if mode == "default" else (17.0 if mode == "dark" else (13.0 + 9.0 * bf))
        p0_shad = np.clip(p0_lit - burn_p0 * self.shad_p0[:, :, None], 0.0, 255.0)
        ty = self.t_y

        if mode in ("default", "dark"):
            c_red = np.stack([245.0 - 28.0 * ty, 78.0 - 30.0 * ty, 64.0 - 27.0 * ty], axis=-1)
            c_yel = np.stack([255.0 - 12.0 * ty, 212.0 - 44.0 * ty, 52.0 - 48.0 * ty], axis=-1)
            c_grn = np.stack([68.0 - 30.0 * ty, 194.0 - 48.0 * ty, 102.0 - 36.0 * ty], axis=-1)

            ann_raw = c_red * self.m_red[:, :, None] + c_yel * self.m_yel[:, :, None] + c_grn * self.m_grn[:, :, None]
            ann_col = ann_raw / np.maximum(self.m_annulus[:, :, None], 1e-4)
            a_ann = (0.972 - 0.095 * ty)[:, :, None]
            ann_glass = bg_base * (1.0 - a_ann) + ann_col * a_ann

            seam_burn = (
                32.0 * self.sh_red_on_yel * self.m_yel
                + 28.0 * self.sh_yel_on_grn * self.m_grn
                + 24.0 * self.sh_grn_on_red * self.m_red
            )
            ann_shaded = np.clip(ann_glass - seam_burn[:, :, None], 0.0, 255.0)
            ann_lit = np.clip(ann_shaded + (255.0 - ann_shaded) * (0.32 * self.spec_ann)[:, :, None], 0.0, 255.0)

            # Separator ring (recessed porcelain in Default, recessed carbon in Dark)
            if mode == "default":
                ring_col = np.stack([253.0 - 16.0 * ty, 254.0 - 16.0 * ty, 255.0 - 12.0 * ty], axis=-1)
                ring_sh = np.clip(ring_col - (18.0 * self.shad_core)[:, :, None], 0.0, 255.0)
            else:
                g_ring = 38.0 * (1.0 - ty) + 22.0 * ty
                ring_col = np.repeat(g_ring[:, :, None], 3, axis=-1)
                ring_sh = np.clip(ring_col - (14.0 * self.shad_core)[:, :, None], 0.0, 255.0)

            # Central 3D Sapphire Glass Lens
            cd = self.core_diag
            core_base = np.stack([
                94.0 * (1.0 - cd) + 22.0 * cd,
                158.0 * (1.0 - cd) + 96.0 * cd,
                255.0 * (1.0 - cd) + 216.0 * cd,
            ], axis=-1)
            caustic_col = np.array([120.0, 192.0, 255.0], dtype=np.float32)[None, None, :]
            core_shaded = core_base * (1.0 - (0.34 * self.core_caustic)[:, :, None]) + caustic_col * (0.34 * self.core_caustic)[:, :, None]
            core_lit = np.clip(core_shaded + (255.0 - core_shaded) * (0.42 * self.spec_core)[:, :, None], 0.0, 255.0)

            comp_shaded = (
                p0_shad * (1.0 - self.m_disc[:, :, None])
                + ann_shaded * self.m_annulus[:, :, None]
                + ring_sh * self.m_ring[:, :, None]
                + core_shaded * self.m_core[:, :, None]
            )
            comp_rgb = (
                p0_shad * (1.0 - self.m_disc[:, :, None])
                + ann_lit * self.m_annulus[:, :, None]
                + ring_sh * self.m_ring[:, :, None]
                + core_lit * self.m_core[:, :, None]
            )
        else:
            # Clear mode: 3-density frosted luminance iris + clear separator ring + high-density crystal sphere
            a_sec = (0.86 * self.m_red + 0.72 * self.m_yel + 0.58 * self.m_grn) / np.maximum(self.m_annulus, 1e-4)
            a_sec = a_sec * (1.0 - 0.14 * ty)
            ann_glass = bg_base * (1.0 - a_sec[:, :, None]) + 255.0 * a_sec[:, :, None]
            seam_burn = (
                16.0 * self.sh_red_on_yel * self.m_yel
                + 14.0 * self.sh_yel_on_grn * self.m_grn
                + 12.0 * self.sh_grn_on_red * self.m_red
            )
            ann_shaded = np.clip(ann_glass - seam_burn[:, :, None], 0.0, 255.0)
            ann_lit = np.clip(ann_shaded + (255.0 - ann_shaded) * (0.36 * self.spec_ann)[:, :, None], 0.0, 255.0)

            ring_sh = np.clip(p0_shad - (12.0 * self.shad_core)[:, :, None], 0.0, 255.0)
            a_core = (0.94 - 0.28 * self.core_diag) + 0.14 * self.core_caustic
            a_core = np.clip(a_core, 0.0, 0.98)
            core_shaded = bg_base * (1.0 - a_core[:, :, None]) + 255.0 * a_core[:, :, None]
            core_lit = np.clip(core_shaded + (255.0 - core_shaded) * (0.44 * self.spec_core)[:, :, None], 0.0, 255.0)

            comp_shaded = (
                p0_shad * (1.0 - self.m_disc[:, :, None])
                + ann_shaded * self.m_annulus[:, :, None]
                + ring_sh * self.m_ring[:, :, None]
                + core_shaded * self.m_core[:, :, None]
            )
            comp_rgb = (
                p0_shad * (1.0 - self.m_disc[:, :, None])
                + ann_lit * self.m_annulus[:, :, None]
                + ring_sh * self.m_ring[:, :, None]
                + core_lit * self.m_core[:, :, None]
            )

        im = self.sh.to_rgba_img(comp_rgb, out_size)
        if not return_dict:
            return im
        return {
            "composite": im,
            "p0_only": self.sh.to_rgba_img(p0_lit, out_size),
            "p0_with_shadow": self.sh.to_rgba_img(p0_shad, out_size),
            "planes_flat_fold": self.sh.to_rgba_img(comp_shaded, out_size),
            "meniscus_field": np.clip(self.spec_ann * self.m_annulus + self.spec_core * self.m_core, 0.0, 1.0),
            "comp_rgb_float": comp_rgb,
            "p0_lit": p0_lit,
        }


class UniversalParametricIconSynthesizer:
    """
    Clean-room reference implementation of Phase 2B's `SamuraiIconSynthesizer.kt`:
    Takes ANY standard Android AdaptiveIconDrawable (flat bg + flat fg bitmaps) or legacy icon
    and automatically synthesizes genuine 2-plane / multi-facet iOS 26 Default, Dark, and Clear icons
    with ZERO per-app vector rules:
      1. Automatic Plane 0 Chromatic Classification (Porcelain Satin / Dark Obsidian / Brand Satin Ramp;
         Dark Carbon #313131->#141414 in Dark Mode with automatic brand-hue foreground glass re-tinting)
      2. Subtractive Linear-Burn Z-Elevation Shadow (sigma = 1.8% W, dy = +1.0% W)
      3. Inward EDT + Sobel Alpha Normal 34-deg Convex Glass Meniscus Ridge (d_peak = 0.85% W)
      4. Automatic Internal Chromatic-Seam Facet Detection (||grad(RGB)||) so multi-color glyphs
         (e.g. Play Store) and inner cutouts (e.g. YouTube play prism) acquire crisp glass bevels!
    """

    def __init__(self, shared: SharedSquircleOptics):
        self.sh = shared
        self.S = shared.S

    @staticmethod
    def _rgb_to_hsv_scalar(rgb: np.ndarray):
        r, g, b = float(rgb[0]) / 255.0, float(rgb[1]) / 255.0, float(rgb[2]) / 255.0
        mx, mn = max(r, g, b), min(r, g, b)
        d = mx - mn
        s = 0.0 if mx < 1e-5 else d / mx
        v = mx
        if d < 1e-5:
            h = 0.0
        elif mx == r:
            h = ((g - b) / d) % 6.0
        elif mx == g:
            h = ((b - r) / d) + 2.0
        else:
            h = ((r - g) / d) + 4.0
        return h / 6.0, s, v

    @staticmethod
    def _hsv_to_rgb_scalar(h: float, s: float, v: float) -> np.ndarray:
        i = int(h * 6.0)
        f = (h * 6.0) - i
        p = v * (1.0 - s)
        q = v * (1.0 - f * s)
        t = v * (1.0 - (1.0 - f) * s)
        m = i % 6
        if m == 0:
            r, g, b = v, t, p
        elif m == 1:
            r, g, b = q, v, p
        elif m == 2:
            r, g, b = p, v, t
        elif m == 3:
            r, g, b = p, q, v
        elif m == 4:
            r, g, b = t, p, v
        else:
            r, g, b = v, p, q
        return np.array([r * 255.0, g * 255.0, b * 255.0], dtype=np.float32)

    def render_flat_android_before(self, bg_72: Image.Image, fg_72: Image.Image, out_size: int = 1024) -> Image.Image:
        """Renders the unmodified stock flat Android adaptive icon inside the squircle mask for Before->After proof."""
        bg_arr = np.array(bg_72.convert("RGB").resize((self.S, self.S), Image.Resampling.LANCZOS), dtype=np.float32)
        fg_rgba = np.array(fg_72.convert("RGBA").resize((self.S, self.S), Image.Resampling.LANCZOS), dtype=np.float32)
        fg_a = (fg_rgba[:, :, 3] / 255.0)[:, :, None]
        comp = bg_arr * (1.0 - fg_a) + fg_rgba[:, :, :3] * fg_a
        return self.sh.to_rgba_img(comp, out_size)

    def synthesize(
        self,
        bg_72: Image.Image,
        fg_72: Image.Image,
        mode: str = "default",
        out_size: int = 1024,
        backdrop_rgb: Image.Image = None,
    ) -> Image.Image:
        bg_arr = np.array(bg_72.convert("RGB").resize((self.S, self.S), Image.Resampling.BILINEAR), dtype=np.float32)
        fg_rgba = np.array(fg_72.convert("RGBA").resize((self.S, self.S), Image.Resampling.LANCZOS), dtype=np.float32)
        fg_rgb = fg_rgba[:, :, :3]
        fg_a = np.clip(fg_rgba[:, :, 3] / 255.0, 0.0, 1.0)

        # 1. Classify Plane 0 background chromaticity & luminance
        med_bg = np.median(bg_arr.reshape(-1, 3), axis=0)
        h0, s0, v0 = self._rgb_to_hsv_scalar(med_bg)
        lum0 = 0.299 * med_bg[0] + 0.587 * med_bg[1] + 0.114 * med_bg[2]

        is_white_bg = (s0 < 0.08) and (lum0 > 215.0)
        is_dark_bg = (lum0 < 60.0) and (s0 < 0.22)
        is_brand_chromatic_bg = s0 >= 0.18

        if mode == "default":
            if is_white_bg:
                bg_base, p0_lit, bf = self.sh.plane0_rgb("porcelain", "default")
            elif is_dark_bg:
                top_c = np.array([46.0, 46.0, 49.0], dtype=np.float32)
                bot_c = np.array([18.0, 18.0, 20.0], dtype=np.float32)
                bg_base, p0_lit, bf = self.sh.plane0_rgb((top_c, bot_c), "default")
            else:
                top_c = self._hsv_to_rgb_scalar(h0, max(0.0, s0 * 0.88), min(1.0, v0 * 1.10 + 0.05))
                bot_c = self._hsv_to_rgb_scalar(h0, min(1.0, s0 * 1.05), max(0.08, v0 * 0.84))
                bg_base, p0_lit, bf = self.sh.plane0_rgb((top_c, bot_c), "default")
        elif mode == "dark":
            bg_base, p0_lit, bf = self.sh.plane0_rgb("porcelain", "dark")
        else:
            bg_base, p0_lit, bf = self.sh.plane0_rgb("porcelain", "clear", backdrop_rgb)

        # 2. Subtractive Linear-Burn Z-Shadow beneath foreground silhouette
        shad_p0 = blur_offset_mask(fg_a, 2, 10, 18.0)
        burn_p0 = 14.0 if mode == "default" else (16.5 if mode == "dark" else (13.0 + 8.5 * bf))
        p0_shad = np.clip(p0_lit - burn_p0 * shad_p0[:, :, None], 0.0, 255.0)

        # 3. Foreground glyph material analysis (white glyph vs colored/multi-color glyph)
        active = fg_a > 0.35
        if np.any(active):
            act_rgb = fg_rgb[active]
            fg_max = act_rgb.max(axis=-1)
            fg_min = act_rgb.min(axis=-1)
            fg_sat_mean = float(np.mean((fg_max - fg_min) / np.maximum(fg_max, 1.0)))
            fg_lum_mean = float(np.mean(0.299 * act_rgb[:, 0] + 0.587 * act_rgb[:, 1] + 0.114 * act_rgb[:, 2]))
            y_min, y_max = float(np.min(self.sh.Y[active])), float(np.max(self.sh.Y[active]))
        else:
            fg_sat_mean, fg_lum_mean = 0.0, 255.0
            y_min, y_max = 200.0, 824.0

        is_white_fg = (fg_sat_mean < 0.10) and (fg_lum_mean > 220.0)
        u_fg = np.clip((self.sh.Y - y_min) / max(y_max - y_min, 64.0), 0.0, 1.0)

        # Outer silhouette 34-deg convex meniscus
        _, _, _, spec_outer, _, _ = compute_glass_meniscus(fg_a, max_d=28, r_peak=8.6, r_falloff=6.0)

        # Internal chromatic-seam / inner-cutout detection (e.g. Play Store 4-color facets, YouTube inner white triangle)
        sm_rgb = np.stack([
            np.array(Image.fromarray(np.uint8(np.clip(fg_rgb[:, :, c], 0, 255)), "L").filter(ImageFilter.GaussianBlur(1.4)), dtype=np.float32)
            for c in range(3)
        ], axis=-1)
        gy_c, gx_c = np.gradient(sm_rgb, axis=(0, 1))
        chroma_edge = np.sqrt(np.sum(gx_c ** 2 + gy_c ** 2, axis=-1)) * (fg_a > 0.85).astype(np.float32)
        has_internal_facets = bool(np.percentile(chroma_edge[active], 98.5) > 22.0) if np.any(active) else False

        if has_internal_facets:
            seam_mask = (chroma_edge > 18.0).astype(np.float32)
            # Distance from internal chromatic seams for crisp inter-facet glass bevels
            d_seam = edt_inside((fg_a > 0.6) & (seam_mask < 0.5), max_d=18)
            seam_ridge = np.where(
                d_seam < 5.5,
                0.15 + 0.85 * np.sin(np.clip(d_seam / 5.5, 0.0, 1.0) * (np.pi / 2.0)),
                np.exp(-(((d_seam - 5.5) / 4.2) ** 2)),
            ) * (fg_a > 0.75).astype(np.float32)
            seam_shad = blur_offset_mask(seam_mask, 1, 4, 5.5) * (fg_a > 0.85).astype(np.float32)
        else:
            seam_ridge = np.zeros_like(fg_a)
            seam_shad = np.zeros_like(fg_a)

        if mode == "clear":
            px_max_c = np.maximum.reduce([fg_rgb[:, :, 0], fg_rgb[:, :, 1], fg_rgb[:, :, 2]])
            px_min_c = np.minimum.reduce([fg_rgb[:, :, 0], fg_rgb[:, :, 1], fg_rgb[:, :, 2]])
            px_w_in = ((px_max_c > 235.0) & ((px_max_c - px_min_c) < 18.0)).astype(np.float32)
            has_inner_white_cutout = (not is_white_fg) and bool(np.mean(px_w_in[active]) > 0.04 and np.mean(px_w_in[active]) < 0.55)
            # Hue-derived optical density variation so multi-color glyphs (e.g. Play Store) retain facet hierarchy in Clear mode
            hue_val = (0.35 * fg_rgb[:, :, 0] + 0.50 * fg_rgb[:, :, 1] + 0.15 * fg_rgb[:, :, 2]) / 255.0
            if is_white_fg:
                a_clear = 0.93 - 0.36 * u_fg
            elif has_inner_white_cutout:
                # Outer colored body becomes luminous frosted glass; inner white emblem becomes recessed clear glass window
                a_clear = (0.88 - 0.24 * u_fg) * (1.0 - px_w_in) + (0.34 - 0.08 * u_fg) * px_w_in
            else:
                a_clear = (0.56 + 0.36 * hue_val) * (1.0 - 0.22 * u_fg)
            a_clear = np.clip(a_clear + (1.0 - a_clear) * (0.48 * spec_outer + 0.22 * seam_ridge), 0.0, 0.98)
            fg_glass = bg_base * (1.0 - a_clear[:, :, None]) + 255.0 * a_clear[:, :, None]
            fg_final = np.clip(fg_glass - (16.0 * seam_shad)[:, :, None], 0.0, 255.0)
        elif is_white_fg:
            if mode == "default":
                a_w = np.clip((0.990 - 0.400 * (u_fg ** 0.96)) + 0.42 * spec_outer, 0.0, 1.0)
                fg_final = bg_base * (1.0 - a_w[:, :, None]) + 255.0 * a_w[:, :, None]
            else:
                # Dark mode: re-tint white glyph with vibrant brand hue from Plane 0 (like iOS 26 WhatsApp / Phone / Messages!)
                if is_brand_chromatic_bg:
                    c_top = self._hsv_to_rgb_scalar(h0, max(0.45, s0 * 0.85), min(1.0, v0 * 1.14 + 0.08))
                    c_bot = self._hsv_to_rgb_scalar(h0, min(1.0, s0 * 1.02), max(0.35, v0 * 0.88))
                    brand_ramp = c_top[None, None, :] * (1.0 - u_fg[:, :, None]) + c_bot[None, None, :] * u_fg[:, :, None]
                else:
                    brand_ramp = np.full_like(fg_rgb, 240.0)
                a_b = np.clip((0.965 - 0.360 * (u_fg ** 0.95)) + 0.22 * spec_outer, 0.0, 1.0)
                fg_glass = bg_base * (1.0 - a_b[:, :, None]) + brand_ramp * a_b[:, :, None]
                fg_final = np.clip(fg_glass + (255.0 - fg_glass) * (0.28 * spec_outer)[:, :, None], 0.0, 255.0)
        else:
            # Colored or multi-color foreground (e.g. YouTube, Spotify, Play Store)
            # Subtle vertical satin lift (+6% top, -7% bottom) while preserving inner white elements pure crystal
            px_max = np.maximum.reduce([fg_rgb[:, :, 0], fg_rgb[:, :, 1], fg_rgb[:, :, 2]])
            px_min = np.minimum.reduce([fg_rgb[:, :, 0], fg_rgb[:, :, 1], fg_rgb[:, :, 2]])
            px_white = ((px_max > 235.0) & ((px_max - px_min) < 18.0)).astype(np.float32)

            v_scale = (1.055 - 0.125 * u_fg)[:, :, None]
            tinted_fg = np.clip(fg_rgb * v_scale, 0.0, 255.0)
            # Inner white elements (e.g. YouTube Play triangle) get crisp frosted white glass ramp
            white_glass_col = 255.0 * (1.0 - 0.06 * u_fg)[:, :, None]
            base_fg_col = tinted_fg * (1.0 - px_white[:, :, None]) + white_glass_col * px_white[:, :, None]

            a_col = (0.968 - 0.110 * u_fg) * (1.0 - px_white) + (0.985 - 0.08 * u_fg) * px_white
            fg_glass = bg_base * (1.0 - a_col[:, :, None]) + base_fg_col * a_col[:, :, None]
            fg_shaded = np.clip(fg_glass - (18.0 * seam_shad)[:, :, None], 0.0, 255.0)
            spec_all = np.clip(0.32 * spec_outer + 0.16 * seam_ridge, 0.0, 0.45)[:, :, None]
            fg_final = np.clip(fg_shaded + (255.0 - fg_shaded) * spec_all, 0.0, 255.0)

        comp_rgb = p0_shad * (1.0 - fg_a[:, :, None]) + fg_final * fg_a[:, :, None]
        return self.sh.to_rgba_img(comp_rgb, out_size)


def build_g2_squircle_rect_mask(w: int, h: int, radius: float, ss: int = 4) -> np.ndarray:
    """
    Exact UIKit kCACornerCurveContinuous G2 Bezier superellipse mask on an arbitrary (w x h) rectangle.
    Matches `SamuraiSquircle.kt` shoulder constants to 0.00px.
    """
    sw, sh = float(w * ss), float(h * ss)
    r = min(float(radius * ss), 0.5 * min(sw, sh))
    p = min(0.5 * min(sw, sh), 1.52866483 * r)
    c_a, c_b, c_c = 1.08849296 * r, 0.86840694 * r, 0.63149379 * r
    c_d, c_e, c_f = 0.07491139 * r, 0.37282383 * r, 0.16905956 * r

    def cubic(p0, p1, p2, p3, n=40):
        t = np.linspace(0.0, 1.0, n, endpoint=False)
        omt = 1.0 - t
        return (
            (omt[:, None] ** 3) * p0
            + 3.0 * (omt[:, None] ** 2) * t[:, None] * p1
            + 3.0 * omt[:, None] * (t[:, None] ** 2) * p2
            + (t[:, None] ** 3) * p3
        )

    if r >= 0.499 * min(sw, sh):
        hi = Image.new("L", (int(sw), int(sh)), 0)
        ImageDraw.Draw(hi).rounded_rectangle([0, 0, int(sw) - 1, int(sh) - 1], radius=int(round(r)), fill=255)
        return np.array(hi.resize((w, h), Image.Resampling.BOX), dtype=np.float32) / 255.0

    tl = np.vstack([
        cubic(np.array([0.0, p]), np.array([0.0, c_a]), np.array([0.0, c_b]), np.array([c_d, c_c])),
        cubic(np.array([c_d, c_c]), np.array([c_f, c_e]), np.array([c_e, c_f]), np.array([c_c, c_d])),
        cubic(np.array([c_c, c_d]), np.array([c_b, 0.0]), np.array([c_a, 0.0]), np.array([p, 0.0])),
        [[p, 0.0]],
    ])
    tr = np.column_stack([sw - tl[::-1, 0], tl[::-1, 1]])
    br = np.column_stack([sw - tl[:, 0], sh - tl[:, 1]])
    bl = np.column_stack([tl[::-1, 0], sh - tl[::-1, 1]])

    hi = Image.new("L", (int(sw), int(sh)), 0)
    ImageDraw.Draw(hi).polygon([tuple(pt) for pt in np.vstack([tl, tr, br, bl])], fill=255)
    return np.array(hi.resize((w, h), Image.Resampling.BOX), dtype=np.float32) / 255.0


def _sample_bilinear_rgb(img_f32: np.ndarray, fx: np.ndarray, fy: np.ndarray) -> np.ndarray:
    """Exact subpixel bilinear sampler on an (H, W, 3) float32 image."""
    ih, iw = img_f32.shape[:2]
    cx = np.clip(fx, 0.0, iw - 1.001)
    cy = np.clip(fy, 0.0, ih - 1.001)
    x0 = np.floor(cx).astype(np.int32)
    y0 = np.floor(cy).astype(np.int32)
    x1 = np.minimum(x0 + 1, iw - 1)
    y1 = np.minimum(y0 + 1, ih - 1)
    wx = (cx - x0)[:, :, None]
    wy = (cy - y0)[:, :, None]
    c00 = img_f32[y0, x0]
    c10 = img_f32[y0, x1]
    c01 = img_f32[y1, x0]
    c11 = img_f32[y1, x1]
    return (c00 * (1.0 - wx) + c10 * wx) * (1.0 - wy) + (c01 * (1.0 - wx) + c11 * wx) * wy


def _sample_dispersed_rgb(
    img_f32: np.ndarray,
    base_x: np.ndarray,
    base_y: np.ndarray,
    pull_x: np.ndarray,
    pull_y: np.ndarray,
    disp_strength: float = 1.0,
) -> np.ndarray:
    """Samples R, G, B with physical crown-flint prismatic index dispersion (IOR 1.485 / 1.515 / 1.548)."""
    scales = (1.0 - 0.085 * disp_strength, 1.0, 1.0 + 0.095 * disp_strength)
    chans = []
    for c_idx, sc in enumerate(scales):
        fx = base_x + pull_x * sc
        fy = base_y + pull_y * sc
        chans.append(_sample_bilinear_rgb(img_f32, fx, fy)[:, :, c_idx])
    return np.stack(chans, axis=-1)


def _smoothstep(edge0, edge1, x: np.ndarray) -> np.ndarray:
    t = np.clip((x - edge0) / np.maximum(edge1 - edge0, 1e-5), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def build_ios26_cyan_cobalt_petals_wallpaper(w: int, h: int, dark_variant: bool = False) -> Image.Image:
    """
    Clean-room parametric synthesis of Apple's signature iOS 26 overlapping translucent
    Mint / Electric-Cerulean / Royal-Sapphire glass-petal wallpaper, calibrated 1:1 to
    position diagonal petal contours across the floating Search dewdrop and Dock lip.
    """
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    xn = xx / max(1.0, w - 1.0)
    yn = yy / max(1.0, h - 1.0)

    if not dark_variant:
        # 1. Base field: luminous mint-ice upper/mid -> seafoam -> deep ocean-teal lower-left
        t_mid = _smoothstep(0.68, 0.86, yn)
        t_deep_teal = _smoothstep(0.85, 0.945, yn) * (1.0 - _smoothstep(0.08, 0.42, xn))
        r = (176.0 * (1.0 - t_mid) + 95.0 * t_mid) * (1.0 - t_deep_teal) + 2.0 * t_deep_teal
        g = (224.0 * (1.0 - t_mid) + 196.0 * t_mid) * (1.0 - t_deep_teal) + 130.0 * t_deep_teal
        b = (224.0 * (1.0 - t_mid) + 197.0 * t_mid) * (1.0 - t_deep_teal) + 168.0 * t_deep_teal

        # 2. Upper-right deep midnight-cobalt glass petal (sweeping arc from top-center to mid-right)
        yn_p1 = np.clip(yn / 0.57, 0.0, 1.0)
        x_p1 = 0.44 - 0.09 * np.sin(np.clip(yn / 0.58, 0.0, 1.0) * math.pi) + 0.62 * (yn_p1 ** 1.85)
        d_p1 = (xn - x_p1) * (yn < 0.62).astype(np.float32)
        m_p1 = _smoothstep(-0.004, 0.005, xn - x_p1) * (1.0 - _smoothstep(0.54, 0.60, yn))
        rim_p1 = np.exp(-(((np.maximum(d_p1, 0.0) - 0.025) / 0.045) ** 2))
        p1_r = 4.0 + 46.0 * rim_p1 + 38.0 * _smoothstep(0.22, 0.55, yn)
        p1_g = 16.0 + 78.0 * rim_p1 + 48.0 * _smoothstep(0.22, 0.55, yn)
        p1_b = 52.0 + 118.0 * rim_p1 + 108.0 * _smoothstep(0.22, 0.55, yn)
        r = r * (1.0 - m_p1) + p1_r * m_p1
        g = g * (1.0 - m_p1) + p1_g * m_p1
        b = b * (1.0 - m_p1) + p1_b * m_p1

        # 3. Signature diagonal Turquoise -> Electric-Cerulean -> Royal-Sapphire petal
        # Calibrated to Apple's exact 9-zone RGB grid (left mint endcap [45,136,180], right royal-cobalt [29,100,186])
        yn_diag = 0.938 - 0.205 * xn - 0.065 * (xn ** 2)
        d_diag = yn - yn_diag
        edge_w = 0.016 * (1.0 - _smoothstep(0.81, 0.85, yn)) + 0.0042
        m_p2 = _smoothstep(-edge_w, edge_w, d_diag)

        t_ceru = _smoothstep(0.790, 0.852, yn)
        t_sapp = _smoothstep(0.885, 0.990, yn + 0.03 * (1.0 - xn))
        # Notice right half (xn > 0.45) keeps richer cobalt-cerulean [29, 102, 194] at bottom (matching Apple x=165 [29,100,186])!
        sapp_g = 68.0 + 36.0 * _smoothstep(0.28, 0.78, xn)
        sapp_b = 166.0 + 30.0 * _smoothstep(0.28, 0.78, xn)
        p2_r = (92.0 * (1.0 - t_ceru) + 36.0 * t_ceru) * (1.0 - t_sapp) + 28.0 * t_sapp
        p2_g = (196.0 * (1.0 - t_ceru) + 162.0 * t_ceru) * (1.0 - t_sapp) + sapp_g * t_sapp
        p2_b = (216.0 * (1.0 - t_ceru) + 201.0 * t_ceru) * (1.0 - t_sapp) + sapp_b * t_sapp

        edge_glow = np.exp(-((np.maximum(d_diag, 0.0) / 0.012) ** 2)) * (1.0 - 0.55 * t_sapp)
        p2_r += 12.0 * edge_glow
        p2_g += 14.0 * edge_glow
        p2_b += 20.0 * edge_glow
        r = r * (1.0 - m_p2) + p2_r * m_p2
        g = g * (1.0 - m_p2) + p2_g * m_p2
        b = b * (1.0 - m_p2) + p2_b * m_p2

        # 4. Far-right luminous sky-azure border sweep (xn > 0.86, matching Apple right endcap [86, 184, 238])
        x_p3 = 0.862 + 0.052 * (np.maximum(1.0 - yn, 0.0) ** 1.3)
        m_p3 = _smoothstep(-0.032, 0.068, xn - x_p3) * _smoothstep(0.56, 0.74, yn)
        t_p3_low = _smoothstep(0.76, 0.98, yn)
        p3_r = 154.0 * (1.0 - t_p3_low) + 78.0 * t_p3_low
        p3_g = 206.0 * (1.0 - t_p3_low) + 185.0 * t_p3_low
        p3_b = 242.0
        r = r * (1.0 - 0.90 * m_p3) + p3_r * (0.90 * m_p3)
        g = g * (1.0 - 0.90 * m_p3) + p3_g * (0.90 * m_p3)
        b = b * (1.0 - 0.90 * m_p3) + p3_b * (0.90 * m_p3)
    else:
        # Midnight Dark iOS 26 variant: deep obsidian-navy base + electric cyan/cobalt/sapphire glass petals
        r = 6.0 + 14.0 * (1.0 - yn)
        g = 14.0 + 34.0 * (1.0 - 0.7 * yn)
        b = 34.0 + 62.0 * (1.0 - 0.5 * yn)

        yn_p1 = np.clip(yn / 0.58, 0.0, 1.0)
        x_p1 = 0.42 - 0.08 * np.sin(np.clip(yn / 0.60, 0.0, 1.0) * math.pi) + 0.60 * (yn_p1 ** 1.8)
        m_p1 = _smoothstep(-0.005, 0.005, xn - x_p1) * (1.0 - _smoothstep(0.54, 0.62, yn))
        rim_p1 = np.exp(-(((np.maximum(xn - x_p1, 0.0) - 0.02) / 0.045) ** 2))
        r = r * (1.0 - m_p1) + (28.0 + 62.0 * rim_p1) * m_p1
        g = g * (1.0 - m_p1) + (42.0 + 96.0 * rim_p1) * m_p1
        b = b * (1.0 - m_p1) + (118.0 + 124.0 * rim_p1) * m_p1

        yn_diag = 0.940 - 0.195 * xn - 0.095 * (xn ** 2)
        d_diag = yn - yn_diag
        m_p2 = _smoothstep(-0.004, 0.004, d_diag)
        t_sapp = _smoothstep(0.88, 0.985, yn + 0.03 * (1.0 - xn))
        edge_glow = np.exp(-((np.maximum(d_diag, 0.0) / 0.013) ** 2))
        p2_r = (20.0 * (1.0 - t_sapp) + 26.0 * t_sapp) + 42.0 * edge_glow
        p2_g = (156.0 * (1.0 - t_sapp) + 44.0 * t_sapp) + 62.0 * edge_glow
        p2_b = (224.0 * (1.0 - t_sapp) + 162.0 * t_sapp) + 28.0 * edge_glow
        r = r * (1.0 - m_p2) + p2_r * m_p2
        g = g * (1.0 - m_p2) + p2_g * m_p2
        b = b * (1.0 - m_p2) + p2_b * m_p2

        x_p3 = 0.85 + 0.055 * (np.maximum(1.0 - yn, 0.0) ** 1.3)
        m_p3 = _smoothstep(-0.035, 0.075, xn - x_p3) * _smoothstep(0.58, 0.76, yn)
        r = r * (1.0 - 0.78 * m_p3) + 74.0 * (0.78 * m_p3)
        g = g * (1.0 - 0.78 * m_p3) + 132.0 * (0.78 * m_p3)
        b = b * (1.0 - 0.78 * m_p3) + 238.0 * (0.78 * m_p3)

    arr = np.clip(np.stack([r, g, b], axis=-1), 0, 255).astype(np.uint8)
    return Image.fromarray(arr, "RGB")


def render_concentric_ios26_dock(
    scene_rgba: Image.Image,
    box: tuple,
    radius_px: float,
    dock_mode: str = "daylight",
    px_per_dp: float = 2.625,
    icon_glow_rgba: Image.Image = None,
    return_stages: bool = False,
):
    """
    Dual-Buffer Local Toroidal Liquid Glass Dock & Capsule Renderer (calibrated 1:1 to iOS 26 ground truth):
      1. Dual Pre-Filtered Silky Optical Buffers (ZERO long-range ghosting, ZERO dark border ring!):
         - `uSharpWallpaper` (sigma ~ 6.0dp optical lens blur) sampled across the curved 18.5dp quarter-torus
           lip with purely local Snell inward ray-bending (`max 12.8dp`, decaying smoothly as `u_lip^1.55`)
           + subtle crown-flint R/G/B dispersion so nearby diagonal contours bend fluidly into the lip
           while keeping local endcaps 100% faithful to adjacent wallpaper colors (zero ghost clones!).
         - `uSoftWallpaper` (sigma ~ 13.8dp silky crystal defocus, 1.16x vibrancy) blended cleanly across
           the interior plateau (`d > 4.5dp .. 18dp`).
      2. Pure Chromatic Clarity:
         - Near-zero milky veil (~3.0% crystal sheen in Daylight, ~2.0% in Clear, ~15% smoked crystal in Dark;
           54% only in accessibility Tinted mode) and ZERO dark trench at `d = 1..5dp`.
      3. Soft Feathered Rim Specular Cushion (`d = 0..3.5dp`):
         - Smooth Gaussian-feathered `2.5-3.5dp` luminous frost/specular rim cushion (~23% TL / ~17% BR)
           plus subtle `d ~ 6.5dp` top caustic sheen & ~9% blurred dock-icon subsurface bloom.
    """
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    dp = px_per_dp
    mask_f = build_g2_squircle_rect_mask(w, h, radius_px, ss=4)

    # 1. Whisper-subtle exterior ambient drop shadow (masked strictly outside dock so rim stays 100% pure!)
    sh_op = 0.018 if dock_mode == "waterdrop" else (0.045 if dock_mode != "dark" else 0.18)
    dy_sh = int(round((2.0 if dock_mode == "waterdrop" else 4.2) * dp))
    sig_sh = max(2.0, (4.5 if dock_mode == "waterdrop" else 9.5) * dp * 0.55)
    pad = int(round(sig_sh * 2.6)) + dy_sh + 4
    sh_l = Image.new("L", (w + 2 * pad, h + 2 * pad), 0)
    sh_l.paste(Image.fromarray(np.uint8(np.clip(np.round(mask_f * 255.0), 0, 255)), "L"), (pad, pad + dy_sh))
    sh_blur = np.array(sh_l.filter(ImageFilter.GaussianBlur(radius=sig_sh)), dtype=np.float32) / 255.0
    interior = np.zeros_like(sh_blur)
    interior[pad:pad + h, pad:pad + w] = mask_f
    sh_alpha = sh_blur * np.clip(1.0 - interior * 1.45, 0.0, 1.0) * sh_op
    sh_rgba = np.zeros((h + 2 * pad, w + 2 * pad, 4), dtype=np.uint8)
    sh_rgba[:, :, 3] = np.clip(np.round(sh_alpha * 255.0), 0, 255).astype(np.uint8)
    scene_rgba.alpha_composite(Image.fromarray(sh_rgba, "RGBA"), (x0 - pad, y0 - pad))

    # 2. Extract backdrop region & build Dual Silky Optical Buffers: uSharp (6.2dp) + uSoft (23.0dp)
    scene_rgb = scene_rgba.convert("RGB")
    margin = int(round(48.0 * dp))
    cx0, cy0 = max(0, x0 - margin), max(0, y0 - margin)
    cx1, cy1 = min(scene_rgb.width, x1 + margin), min(scene_rgb.height, y1 + margin)
    wide_crop = scene_rgb.crop((cx0, cy0, cx1, cy1))
    ox, oy = float(x0 - cx0), float(y0 - cy0)

    raw_stage = np.array(scene_rgb.crop((x0, y0, x1, y1)), dtype=np.float32)
    if dock_mode == "waterdrop":
        sig_sharp = 3.2 * (dp / 2.625)
        sig_soft = 5.8 * (dp / 2.625)
    elif dock_mode == "tinted":
        sig_sharp = 9.0 * (dp / 2.625)
        sig_soft = 25.0 * (dp / 2.625)
    else:
        sig_sharp = 6.2 * (dp / 2.625)
        sig_soft = 23.0 * (dp / 2.625)

    u_sharp = np.array(wide_crop.filter(ImageFilter.GaussianBlur(radius=max(1.5, sig_sharp))), dtype=np.float32)
    u_soft = np.array(wide_crop.filter(ImageFilter.GaussianBlur(radius=max(2.5, sig_soft))), dtype=np.float32)

    # 3. Smooth inward SDF distance & C2-smooth outward surface normal (nx, ny)
    bevel_px = min(0.45 * min(w, h), 19.5 * dp)
    max_edt = int(round(bevel_px)) + 10
    d_in = edt_inside(mask_f > 0.5, max_d=max_edt)
    d_sm_pil = Image.fromarray(np.clip(d_in * (255.0 / max_edt), 0, 255).astype(np.uint8), "L")
    d_sm = np.array(d_sm_pil.filter(ImageFilter.GaussianBlur(radius=max(1.5, 1.35 * dp))), dtype=np.float32) * (max_edt / 255.0)
    gy, gx = np.gradient(d_sm)
    nl = np.hypot(gx, gy) + 1e-6
    nx, ny = -gx / nl, -gy / nl  # Outward unit normal everywhere along G2 squircle lip

    # 4. Local Cylindrical-Capsule Quarter-Torus Snell Refraction (11.5dp X, 20.5dp Y — ZERO ghosting!)
    u_lip = np.clip(1.0 - d_in / max(bevel_px, 1.0), 0.0, 1.0)
    d_dp = d_in / max(dp, 1e-4)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)

    if dock_mode == "waterdrop":
        pull_x = -nx * ((3.4 * dp) * (u_lip ** 1.45))
        pull_y = -ny * ((4.2 * dp) * (u_lip ** 1.45))
        disp_amt = 0.30
        plateau_w = _smoothstep(2.0 * dp, bevel_px * 0.88, d_in)[:, :, None]
    else:
        slab_scale = min(1.0, h / max(32.0 * dp, 1.0))
        mode_gain = 0.45 if dock_mode == "tinted" else 1.0
        # Horizontal bar capsule bends stronger vertically (-ny: 20.5dp) than horizontally (-nx: 11.5dp),
        # folding the nearby top-lip cyan/cobalt wave while keeping left/right endcaps 100% local!
        pull_x = -nx * ((11.5 * dp) * (u_lip ** 1.55) * slab_scale * mode_gain)
        pull_y = -ny * ((20.5 * dp) * (u_lip ** 1.48) * slab_scale * mode_gain)
        disp_amt = 0.65 if dock_mode != "tinted" else 0.25
        plateau_w = _smoothstep(4.8 * dp, bevel_px * 0.95, d_in)[:, :, None]
        if dock_mode == "tinted":
            plateau_w = np.clip(plateau_w + 0.50, 0.0, 1.0)

    sharp_refr = _sample_dispersed_rgb(u_sharp, xx + ox, yy + oy, pull_x, pull_y, disp_strength=disp_amt)
    soft_refr = _sample_bilinear_rgb(u_soft, xx + ox + 0.30 * pull_x, yy + oy + 0.30 * pull_y)
    refracted = sharp_refr * (1.0 - plateau_w) + soft_refr * plateau_w

    # Gentle chroma vibrancy (1.05x) so deep blues keep natural red/green luminosity (~[56, 128, 194])
    lum = (0.299 * refracted[:, :, 0] + 0.587 * refracted[:, :, 1] + 0.114 * refracted[:, :, 2])[:, :, None]
    if dock_mode == "waterdrop":
        sat_boost = 1.04
    elif dock_mode in ("daylight", "clear"):
        sat_boost = 1.05
    elif dock_mode == "dark":
        sat_boost = 1.18
    else:
        sat_boost = 1.04
    stage2_refract = np.clip(lum + (refracted - lum) * sat_boost, 0.0, 255.0)

    # 5. Calibrated Luminous Crystal Veil (~10.5% Daylight, ~6.5% Clear, ZERO Dark Trench, Feathered Rim)
    if dock_mode in ("daylight", "clear"):
        veil_col = np.array([232.0, 244.0, 252.0], dtype=np.float32)
        veil_a = 0.105 if dock_mode == "daylight" else 0.065
        caustic_str = 0.068
        rim_tl, rim_br, rim_gr = 0.22, 0.16, 0.10
    elif dock_mode == "waterdrop":
        veil_col = np.array([236.0, 246.0, 252.0], dtype=np.float32)
        veil_a = 0.015
        caustic_str = 0.025
        rim_tl, rim_br, rim_gr = 0.085, 0.065, 0.045
    elif dock_mode == "dark":
        veil_col = np.array([10.0, 16.0, 30.0], dtype=np.float32)
        veil_a = 0.16
        caustic_str = 0.075
        rim_tl, rim_br, rim_gr = 0.28, 0.19, 0.10
    else:  # tinted (iOS 26.1 high-contrast accessibility option)
        veil_col = np.array([240.0, 245.0, 252.0], dtype=np.float32)
        veil_a = 0.54
        caustic_str = 0.05
        rim_tl, rim_br, rim_gr = 0.44, 0.30, 0.12

    body = stage2_refract * (1.0 - veil_a) + veil_col[None, None, :] * veil_a
    if dock_mode == "waterdrop":
        body = body * 0.952

    # Inner soft caustic sheen (d ~ 6.2dp along top lip + d ~ 2.8dp bottom internal rebound)
    top_w = np.maximum(0.0, -ny)
    bot_w = np.maximum(0.0, ny)
    side_w = np.abs(nx)
    caustic_peak_dp = min(6.2, 0.24 * (h / max(dp, 1e-4)))
    caustic_top = caustic_str * ((0.85 * (top_w ** 1.15) + 0.35 * (side_w ** 1.3)) * np.exp(-(((d_dp - caustic_peak_dp) / 3.6) ** 2)))
    rebound_bot = (caustic_str * 0.85) * ((bot_w ** 1.2) * np.exp(-(((d_dp - min(2.8, 0.16 * h / dp)) / 2.0) ** 2)))
    caustic_lift = np.clip(caustic_top + rebound_bot, 0.0, 0.20)[:, :, None]
    caustic_tint = np.array([236.0, 249.0, 255.0], dtype=np.float32)[None, None, :]
    stage3_caustic = np.clip(body + (caustic_tint - body) * caustic_lift, 0.0, 255.0)

    # Optional subtle subsurface scatter from dock icons sitting on the crystal slab (~14%)
    lit_base = stage3_caustic
    if icon_glow_rgba is not None:
        ig_crop = icon_glow_rgba.crop((x0, y0, x1, y1)).filter(ImageFilter.GaussianBlur(radius=max(3.0, 15.5 * (dp / 2.625))))
        ig_arr = np.array(ig_crop, dtype=np.float32)
        ig_a = (ig_arr[:, :, 3:4] / 255.0) * 0.145
        lit_base = lit_base * (1.0 - ig_a) + ig_arr[:, :, :3] * ig_a

    # 6. Soft Feathered Rim Specular Cushion (d = 0..3.5dp, sigma ~ 1.25dp rolloff — NEVER a harsh wire!)
    s_diag = -(0.50 * nx + 0.866 * ny)
    rim_peak = (
        rim_gr
        + (rim_tl - rim_gr) * (np.maximum(0.0, s_diag) ** 1.20)
        + (rim_br - rim_gr) * (np.maximum(0.0, -s_diag) ** 1.50)
    )
    cushion_profile = 0.72 * np.exp(-((d_dp / (1.55 if dock_mode == "waterdrop" else 2.45)) ** 1.40)) + 0.28 * np.clip(1.0 - d_dp / 1.15, 0.0, 1.0)
    rim_a = (rim_peak * cushion_profile)[:, :, None]
    rim_tint = np.array([246.0, 252.0, 255.0], dtype=np.float32)[None, None, :]
    lit = np.clip(lit_base + (rim_tint - lit_base) * rim_a, 0.0, 255.0)

    out = np.zeros((h, w, 4), dtype=np.uint8)
    out[:, :, :3] = np.clip(np.round(lit), 0, 255).astype(np.uint8)
    out[:, :, 3] = np.clip(np.round(mask_f * 255.0), 0, 255).astype(np.uint8)
    out_pil = Image.fromarray(out, "RGBA")
    scene_rgba.alpha_composite(out_pil, (x0, y0))

    if not return_stages:
        return None

    def _pack(rgb_f):
        a = np.zeros((h, w, 4), dtype=np.uint8)
        a[:, :, :3] = np.clip(np.round(rgb_f), 0, 255).astype(np.uint8)
        a[:, :, 3] = np.clip(np.round(mask_f * 255.0), 0, 255).astype(np.uint8)
        return Image.fromarray(a, "RGBA")

    return {
        "stage1_raw": _pack(raw_stage),
        "stage2_snell_dispersed": _pack(stage2_refract),
        "stage3_meniscus_caustic": _pack(stage3_caustic),
        "stage4_final_glass": out_pil,
    }


def render_ios26_waterdrop_search_pill(
    scene_rgba: Image.Image,
    box: tuple,
    px_per_dp: float = 2.625,
    dark_mode: bool = False,
) -> None:
    """Renders Apple's compact transparent water-droplet 'Q Search' capsule with soft white glyph & label."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    render_concentric_ios26_dock(
        scene_rgba,
        box,
        radius_px=0.5 * h,
        dock_mode="dark" if dark_mode else "waterdrop",
        px_per_dp=px_per_dp,
    )
    # Supersampled compact translucent white rgba(255,255,255,192) magnifying glass + 'Search' (~28.5% pill height)
    ss = 2
    ov = Image.new("RGBA", (w * ss, h * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(ov, "RGBA")
    f_sz = max(11, int(round(h * 0.285 * ss)))
    fnt = get_font(f_sz, True)
    txt = "Search"
    tb = d.textbbox((0, 0), txt, font=fnt)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    icon_d = int(round(h * 0.235 * ss))
    gap = int(round(h * 0.095 * ss))
    tot_w = icon_d + gap + tw
    sx = (w * ss - tot_w) // 2
    cy = (h * ss) // 2

    col = (255, 255, 255, 194 if not dark_mode else 218)
    sw = max(2, int(round(1.02 * px_per_dp * ss / 2.625)))
    r_glass = int(round(icon_d * 0.42))
    gcx, gcy = sx + r_glass + 2, cy - int(round(icon_d * 0.03))
    d.ellipse([gcx - r_glass, gcy - r_glass, gcx + r_glass, gcy + r_glass], outline=col, width=sw)
    hx0 = int(round(gcx + r_glass * 0.68))
    hy0 = int(round(gcy + r_glass * 0.68))
    hx1 = int(round(gcx + r_glass * 1.38))
    hy1 = int(round(gcy + r_glass * 1.38))
    d.line([(hx0, hy0), (hx1, hy1)], fill=col, width=sw)
    d.text((sx + icon_d + gap - tb[0], cy - th // 2 - tb[1]), txt, fill=col, font=fnt)

    ov_sm = ov.resize((w, h), Image.Resampling.LANCZOS)
    scene_rgba.alpha_composite(ov_sm, (x0, y0))


# ==============================================================================
# ANDROID 17 108DP -> 72DP ADAPTIVE ICON LAYER BUILDER (ZERO-SEAM PARALLAX)
# ==============================================================================
def build_adaptive_108dp_layers_2plane(shared: SharedSquircleOptics, palette: str, info: dict):
    """
    Constructs 1536x1536 (108dp) `<background>` and `<foreground>` PIL images whose central
    [256:1280, 256:1280] (72dp) viewport reproduces our exact 1024x1024 Default icon when masked
    by Android's `config_icon_mask`, while extending Plane 0 cleanly across the 256px bleed margin.
    """
    y_108, _ = np.mgrid[0:CANVAS_108DP, 0:CANVAS_108DP].astype(np.float32)
    t_108 = np.clip(((y_108 - BLEED_18DP) - 90.0) / (934.0 - 90.0), 0.0, 1.0)

    if palette == "emerald":
        bg_108 = np.stack([
            82.88 * (1.0 - t_108) + 39.02 * t_108,
            239.96 * (1.0 - t_108) + 192.02 * t_108,
            108.88 * (1.0 - t_108) + 65.02 * t_108,
        ], axis=-1)
    else:
        bg_108 = np.stack([
            168.04 * (1.0 - t_108) + 95.88 * t_108,
            168.04 * (1.0 - t_108) + 95.88 * t_108,
            173.91 * (1.0 - t_108) + 99.96 * t_108,
        ], axis=-1)

    # Embed 22px 34-deg specular squircle rim inside [256:1280, 256:1280] (extending 2px across boundary for zero dark AA seam)
    b0, b1 = BLEED_18DP, BLEED_18DP + MASTER_72DP
    # Recompute outer rim without clipping to sq_mask=0 on the outer 2px AA fringe
    r_c = 0.2250 * MASTER_72DP
    cx_c = np.clip(shared.X, r_c, (MASTER_72DP - 1.0) - r_c)
    cy_c = np.clip(shared.Y, r_c, (MASTER_72DP - 1.0) - r_c)
    dx_c, dy_c = shared.X - cx_c, shared.Y - cy_c
    dl_c = np.hypot(dx_c, dy_c) + 1e-6
    s_diag = -((dx_c / dl_c) + (dy_c / dl_c)) / np.sqrt(2.0)
    rim_peak = 0.09 + 0.65 * (np.maximum(0.0, s_diag) ** 1.22) + 0.47 * (np.maximum(0.0, -s_diag) ** 2.0)
    # Active inside squircle (d_sq in [0..22]) + 3px outward bleed for clean config_icon_mask antialiasing
    d_out = edt_inside(shared.sq_mask <= 0.5, max_d=6)
    rim_bleed = np.where(shared.sq_mask > 0.5, np.clip(1.0 - (shared.d_sq - 1.0) / 21.5, 0.0, 1.0), np.clip(1.0 - d_out / 3.5, 0.0, 1.0))
    ra_72 = np.clip(rim_peak * rim_bleed, 0.0, 1.0)[:, :, None]
    bg_108[b0:b1, b0:b1] = bg_108[b0:b1, b0:b1] * (1.0 - ra_72) + 255.0 * ra_72

    bg_rgba = np.zeros((CANVAS_108DP, CANVAS_108DP, 4), dtype=np.uint8)
    bg_rgba[:, :, :3] = np.clip(np.round(bg_108), 0, 255).astype(np.uint8)
    bg_rgba[:, :, 3] = 255
    bg_pil = Image.fromarray(bg_rgba, mode="RGBA")

    # Foreground layer: exact straight-RGBA representation of (shadow + white glass foreground)
    fg_rgba = np.zeros((CANVAS_108DP, CANVAS_108DP, 4), dtype=np.uint8)
    p0_lum = np.maximum(0.299 * info["p0_lit"][:, :, 0] + 0.587 * info["p0_lit"][:, :, 1] + 0.114 * info["p0_lit"][:, :, 2], 40.0)

    if "fg_mask" in info:
        # 2-plane icon (Phone / Messages)
        fg_m = info["fg_mask"]
        a_w = info["fg_white_alpha"]
        a_sh = np.clip((info["burn"] * info["shad_field"]) / p0_lum, 0.0, 0.45) * (1.0 - fg_m)
        # Combine shadow (RGB=0, alpha=a_sh) outside mask + white glass (RGB=255, alpha=a_w*fg_m) inside mask
        a_tot = np.clip(a_w * fg_m + a_sh, 0.0, 1.0)
        rgb_val = np.where(a_tot > 1e-4, (255.0 * a_w * fg_m) / np.maximum(a_tot, 1e-4), 0.0)
    else:
        # 3-plane Settings icon (Plane 1 + Plane 2 + dual shadows)
        p1_m, p2_m = info["p1_m"], info["p2_m"]
        ap1, ap2 = info["ap1"], info["ap2"]
        sh_tot = info["b1"] * info["shad_s1"] + info["b2"] * info["shad_s2"]
        a_sh0 = np.clip(sh_tot / p0_lum, 0.0, 0.45) * (1.0 - np.clip(p1_m + p2_m, 0.0, 1.0))

        # Effective white & shadow blend across P1 and P2:
        # Solve exact straight-RGBA (C_fg, A_fg) such that P0 * (1 - A_fg) + C_fg * A_fg == comp_rgb!
        # Since both P1 and P2 are pure white + black shadow over neutral/emerald P0:
        comp = info["comp_rgb_float"]
        p0 = info["p0_lit"]
        # Where comp >= p0 (brightened by white glass): C_fg = 255, A_fg = (comp - p0)/(255 - p0)
        # Where comp < p0 (darkened by Z-shadow): C_fg = 0, A_fg = (p0 - comp)/p0
        delta = (comp[:, :, 0] - p0[:, :, 0] + comp[:, :, 1] - p0[:, :, 1] + comp[:, :, 2] - p0[:, :, 2]) / 3.0
        a_pos = np.clip(delta / np.maximum(255.0 - p0.mean(axis=-1), 1.0), 0.0, 1.0)
        a_neg = np.clip(-delta / np.maximum(p0.mean(axis=-1), 1.0), 0.0, 0.55)
        a_tot = np.where(delta >= 0.0, a_pos, a_neg + a_sh0 * 0.0)
        rgb_val = np.where(delta >= 0.0, 255.0, 0.0)

    fg_patch = np.zeros((MASTER_72DP, MASTER_72DP, 4), dtype=np.uint8)
    fg_patch[:, :, 0] = np.clip(np.round(rgb_val), 0, 255).astype(np.uint8)
    fg_patch[:, :, 1] = fg_patch[:, :, 0]
    fg_patch[:, :, 2] = fg_patch[:, :, 0]
    fg_patch[:, :, 3] = np.clip(np.round(a_tot * 255.0), 0, 255).astype(np.uint8)
    fg_rgba[b0:b1, b0:b1] = fg_patch
    fg_pil = Image.fromarray(fg_rgba, mode="RGBA")
    return bg_pil, fg_pil


def save_density_set(src_108dp_pil: Image.Image, res_root: str, prefix: str, filename: str) -> None:
    for dpi_name, px in DENSITIES:
        out_dir = os.path.join(res_root, f"{prefix}-{dpi_name}")
        os.makedirs(out_dir, exist_ok=True)
        resized = src_108dp_pil.resize((px, px), Image.Resampling.LANCZOS)
        resized.save(os.path.join(out_dir, filename), format="PNG", optimize=True)


def build_adaptive_108dp_layers_colored(
    shared: SharedSquircleOptics,
    palette: str,
    info: dict,
    fg_mask: np.ndarray,
):
    """
    Constructs exact 1536x1536 (108dp) `<background>` and `<foreground>` PIL images for multi-color
    multi-plane Default icons (e.g. Gmail, Google Photos, Chrome) on porcelain/colored Plane 0:
      - `bg_108` holds the full 108dp Plane 0 vertical satin ramp + inner 72dp `p0_lit` (including
        the 34-deg specular rim and porcelain cool-silver micro-rim bled 3px across the AA fringe).
      - `fg_108` solves exact straight-RGBA `(C_fg, A_fg)` in `[0, 255]^3 x [0, 1]` such that
        `p0 * (1 - A_fg) + C_fg * A_fg == comp_rgb_float` across all 3 RGB channels simultaneously,
        while enforcing `A_fg >= fg_mask` so spring parallax translates the entire colored glass glyph cleanly.
    """
    y_108, _ = np.mgrid[0:CANVAS_108DP, 0:CANVAS_108DP].astype(np.float32)
    t_108 = np.clip(((y_108 - BLEED_18DP) - 90.0) / (934.0 - 90.0), 0.0, 1.0)

    if palette == "porcelain":
        bg_108 = np.stack([
            255.0 * (1.0 - t_108) + 241.0 * t_108,
            255.0 * (1.0 - t_108) + 242.0 * t_108,
            255.0 * (1.0 - t_108) + 246.0 * t_108,
        ], axis=-1)
    elif palette == "emerald":
        bg_108 = np.stack([
            82.88 * (1.0 - t_108) + 39.02 * t_108,
            239.96 * (1.0 - t_108) + 192.02 * t_108,
            108.88 * (1.0 - t_108) + 65.02 * t_108,
        ], axis=-1)
    else:
        bg_108 = np.stack([
            168.04 * (1.0 - t_108) + 95.88 * t_108,
            168.04 * (1.0 - t_108) + 95.88 * t_108,
            173.91 * (1.0 - t_108) + 99.96 * t_108,
        ], axis=-1)

    b0, b1 = BLEED_18DP, BLEED_18DP + MASTER_72DP
    p0 = info["p0_lit"]
    comp = info["comp_rgb_float"]

    # Embed p0_lit inside squircle + bleed rim 3.5px outward across config_icon_mask AA boundary
    d_out = edt_inside(shared.sq_mask <= 0.5, max_d=6)
    bleed_w = np.where(shared.sq_mask > 0.5, 1.0, np.clip(1.0 - d_out / 3.5, 0.0, 1.0))[:, :, None]
    if palette == "porcelain":
        silver_rim = np.array([220.0, 223.0, 230.0], dtype=np.float32)[None, None, :]
        outer_target = silver_rim * (0.42 + 0.46 * np.clip(0.5 - 0.5 * shared.s_diag, 0.0, 1.0))[:, :, None] + bg_108[b0:b1, b0:b1] * (
            1.0 - (0.42 + 0.46 * np.clip(0.5 - 0.5 * shared.s_diag, 0.0, 1.0))[:, :, None]
        )
        p0_ext = np.where(shared.sq_mask[:, :, None] > 0.5, p0, outer_target)
        bg_108[b0:b1, b0:b1] = bg_108[b0:b1, b0:b1] * (1.0 - bleed_w) + p0_ext * bleed_w
    else:
        bg_108[b0:b1, b0:b1] = bg_108[b0:b1, b0:b1] * (1.0 - bleed_w) + p0 * bleed_w

    bg_rgba = np.zeros((CANVAS_108DP, CANVAS_108DP, 4), dtype=np.uint8)
    bg_rgba[:, :, :3] = np.clip(np.round(bg_108), 0, 255).astype(np.uint8)
    bg_rgba[:, :, 3] = 255
    bg_pil = Image.fromarray(bg_rgba, mode="RGBA")

    # Exact 3-channel straight-RGBA foreground decomposition: p0*(1 - A_fg) + C_fg*A_fg == comp
    diff = comp - p0
    req_pos = np.where(diff > 0.0, diff / np.maximum(255.0 - p0, 1e-3), 0.0)
    req_neg = np.where(diff < 0.0, (-diff) / np.maximum(p0, 1e-3), 0.0)
    a_min = np.max(np.maximum(req_pos, req_neg), axis=-1)
    a_fg = np.clip(np.maximum(a_min, fg_mask), 0.0, 1.0)
    c_fg = np.where(
        a_fg[:, :, None] > 1e-4,
        np.clip(p0 + diff / np.maximum(a_fg[:, :, None], 1e-4), 0.0, 255.0),
        0.0,
    )

    fg_rgba = np.zeros((CANVAS_108DP, CANVAS_108DP, 4), dtype=np.uint8)
    fg_patch = np.zeros((MASTER_72DP, MASTER_72DP, 4), dtype=np.uint8)
    fg_patch[:, :, :3] = np.clip(np.round(c_fg), 0, 255).astype(np.uint8)
    fg_patch[:, :, 3] = np.clip(np.round(a_fg * 255.0), 0, 255).astype(np.uint8)
    fg_rgba[b0:b1, b0:b1] = fg_patch
    fg_pil = Image.fromarray(fg_rgba, mode="RGBA")
    return bg_pil, fg_pil


# ==============================================================================
# RESTRAINED iOS 26 / 26.1 LIQUID GLASS UI SURFACE RENDERER (FOR PREVIEW SHEET)
# ==============================================================================
def render_restrained_glass_pill(
    scene_rgb_u8: Image.Image,
    box: tuple,
    radius: int,
    tinted_mode: bool = False,
    is_bright_scene: bool = False,
) -> Image.Image:
    """
    Renders a genuine, restrained Apple iOS 26 / 26.1 Liquid Glass capsule/dock over `scene_rgb_u8`:
      - Soft optical backdrop blur (sigma=14 Clear, sigma=22 Tinted) + subtle edge lens compression
      - Restrained frosted glass veil (~20% Clear, ~68% Tinted iOS 26.1) — zero black meniscus trench!
      - Hair-fine ~1.5px directional 34-deg perimeter catchlight (74% TL, 48% BR, 9% grazing TR/BL)
    """
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    crop = scene_rgb_u8.crop(box)

    blur_r = 22.0 if tinted_mode else 13.5
    blurred = np.array(crop.filter(ImageFilter.GaussianBlur(radius=blur_r)), dtype=np.float32)

    # Mask & inward distance for gentle bevel refraction + hair-fine 34-deg rim
    ss = 3
    m_hi = Image.new("L", (w * ss, h * ss), 0)
    ImageDraw.Draw(m_hi).rounded_rectangle([0, 0, w * ss - 1, h * ss - 1], radius=radius * ss, fill=255)
    mask_f = np.array(m_hi.resize((w, h), Image.Resampling.BOX), dtype=np.float32) / 255.0
    d_in = edt_inside(mask_f > 0.5, max_d=26)

    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    r_c = float(min(radius, w // 2, h // 2))
    cx_c = np.clip(xx, r_c, (w - 1.0) - r_c)
    cy_c = np.clip(yy, r_c, (h - 1.0) - r_c)
    dx_c, dy_c = xx - cx_c, yy - cy_c
    dl_c = np.hypot(dx_c, dy_c) + 1e-6
    nx, ny = dx_c / dl_c, dy_c / dl_c

    # Gentle outer bevel Snell compression (pulling background 8px inward along curved 16px edge)
    bevel_u = np.clip(1.0 - d_in / 16.0, 0.0, 1.0) ** 1.85
    raw_arr = np.array(crop.filter(ImageFilter.GaussianBlur(radius=3.5 if not tinted_mode else 14.0)), dtype=np.float32)
    sx = np.clip(np.round(xx - nx * bevel_u * (11.0 if not tinted_mode else 5.0)).astype(np.int32), 0, w - 1)
    sy = np.clip(np.round(yy - ny * bevel_u * (11.0 if not tinted_mode else 5.0)).astype(np.int32), 0, h - 1)
    edge_refr = raw_arr[sy, sx]
    glass_src = blurred * (1.0 - (bevel_u * 0.65)[:, :, None]) + edge_refr * (bevel_u * 0.65)[:, :, None]

    # Subtle +8% chroma vibrancy in Clear mode
    lum = (0.299 * glass_src[:, :, 0] + 0.587 * glass_src[:, :, 1] + 0.114 * glass_src[:, :, 2])[:, :, None]
    glass_src = np.clip(lum + (glass_src - lum) * (1.10 if not tinted_mode else 1.03), 0.0, 255.0)

    if tinted_mode:
        veil_col = np.array([242.0, 245.0, 250.0]) if is_bright_scene else np.array([34.0, 40.0, 52.0])
        veil_a = 0.70 if is_bright_scene else 0.66
    else:
        veil_col = np.array([246.0, 248.0, 252.0]) if is_bright_scene else np.array([255.0, 255.0, 255.0])
        veil_a = 0.24 if is_bright_scene else 0.19

    body = glass_src * (1.0 - veil_a) + veil_col[None, None, :] * veil_a

    # Hair-fine ~2.2px 34-deg perimeter specular catchlight
    s_diag = -(0.559 * nx + 0.829 * ny)
    rim_peak = 0.10 + 0.66 * (np.maximum(0.0, s_diag) ** 1.35) + 0.46 * (np.maximum(0.0, -s_diag) ** 1.95)
    rim_a = (rim_peak * np.clip(1.0 - (d_in - 0.8) / 2.8, 0.0, 1.0))[:, :, None]
    lit = np.clip(body * (1.0 - rim_a) + 255.0 * rim_a, 0.0, 255.0)

    out = np.zeros((h, w, 4), dtype=np.uint8)
    out[:, :, :3] = np.round(lit).astype(np.uint8)
    out[:, :, 3] = np.clip(np.round(mask_f * 255.0), 0, 255).astype(np.uint8)
    return Image.fromarray(out, mode="RGBA")


def get_font(sz: int, bold: bool = False):
    paths = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
    ]
    for p in paths:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, sz)
            except Exception:
                pass
    return ImageFont.load_default()


def build_signature_ios26_wallpaper(w: int, h: int, daylight: bool = False) -> Image.Image:
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    xn, yn = xx / max(1.0, w - 1.0), yy / max(1.0, h - 1.0)
    if daylight:
        wave = 0.5 + 0.5 * np.sin(xn * 3.4 - yn * 2.2)
        r = 178.0 * (1.0 - 0.35 * yn) + 58.0 * wave
        g = 224.0 * (1.0 - 0.15 * yn) + 22.0 * np.cos(yn * 2.5)
        b = 242.0 - 18.0 * yn * xn + 12.0 * wave
    else:
        wave = 0.5 + 0.5 * np.sin(xn * 3.8 - yn * 2.5)
        r = 8.0 + 52.0 * (1.0 - yn) * wave
        g = 86.0 + 118.0 * (1.0 - 0.52 * yn) * (0.42 + 0.58 * wave)
        b = 152.0 + 82.0 * (0.5 + 0.5 * np.cos(yn * 2.3 - xn * 1.7))
    return Image.fromarray(np.clip(np.stack([r, g, b], axis=-1), 0, 255).astype(np.uint8), "RGB")


def generate_preview_sheet_v3(
    out_path: str,
    ph_ren: CleanRoomPhoneIconRenderer,
    ms_ren: CleanRoomMessagesIconRenderer,
    st_ren: CleanRoomSettingsIconRenderer,
) -> None:
    W, H = 2040, 1420
    wp = build_signature_ios26_wallpaper(W, H, daylight=False)
    sheet = wp.copy().convert("RGBA")
    draw = ImageDraw.Draw(sheet, "RGBA")

    f_h1 = get_font(28, True)
    f_h2 = get_font(20, True)
    f_b = get_font(16, False)
    f_b2 = get_font(15, True)
    f_sm = get_font(14, False)
    EM, DEG = "\u2014", "\u00b0"

    # Header bar in restrained iOS 26 Clear Liquid Glass
    hdr_box = (44, 28, W - 44, 118)
    sheet.alpha_composite(render_restrained_glass_pill(wp, hdr_box, radius=34, tinted_mode=False), (hdr_box[0], hdr_box[1]))
    draw.rounded_rectangle([60, 40, W - 60, 106], radius=24, fill=(8, 18, 34, 108))
    draw.text((82, 46), f"iOS 26 Clean-Room Step 1 Trio (v3.0) {EM} Phone, Messages & Settings + Restrained Liquid Glass UI", fill=(250, 252, 255), font=f_h1)
    draw.text((82, 80), f"Unified Multi-Plane Glass Synthesis  |  Hair-Fine 22px (2.15% W) 34{DEG} Specular Rim  |  0.0px Android 108dp->72dp config_icon_mask Alignment", fill=(185, 235, 255), font=f_sm)

    # 3 Mode Showcase Cards (Default, Dark, Clear)
    modes = [
        ("DEFAULT APPEARANCE", "Satin Emerald & Cool Silver-Slate Backplates", "default"),
        ("DARK APPEARANCE", "Carbon #313131 -> #141414 + Translucent Emerald/Silver Glass", "dark"),
        ("CLEAR APPEARANCE", "21.5%->16.5% Frosted Wallpaper Veil + Luminance Sculptures", "clear"),
    ]
    cw = (W - 44 * 4) // 3
    ch = 450
    cy = 148
    for idx, (mtitle, msub, mkey) in enumerate(modes):
        cx = 44 + idx * (cw + 44)
        card_glass = render_restrained_glass_pill(wp, (cx, cy, cx + cw, cy + ch), radius=38, tinted_mode=(idx == 1))
        sheet.alpha_composite(card_glass, (cx, cy))
        draw.rounded_rectangle([cx + 20, cy + 18, cx + cw - 20, cy + 86], radius=20, fill=(8, 18, 34, 128))
        draw.text((cx + 34, cy + 25), mtitle, fill=(248, 250, 255), font=f_h2)
        draw.text((cx + 34, cy + 54), msub, fill=(190, 224, 248), font=f_sm)

        isz = 172
        gap = (cw - 3 * isz) // 4
        iy = cy + 138
        cur_bg = sheet.convert("RGB")
        for j, (lbl, ren) in enumerate([("Phone", ph_ren), ("Messages", ms_ren), ("Settings", st_ren)]):
            ix = cx + gap + j * (isz + gap)
            patch = cur_bg.crop((ix, iy, ix + isz, iy + isz))
            ic = ren.render(mkey, out_size=isz, backdrop_rgb=patch if mkey == "clear" else None)
            pad_s = 22
            sh_pad = Image.new("L", (isz + 2 * pad_s, isz + 2 * pad_s), 0)
            sh_pad.paste(ic.split()[3], (pad_s, pad_s))
            sh_m = sh_pad.filter(ImageFilter.GaussianBlur(radius=9))
            sh_rgba = np.zeros((isz + 2 * pad_s, isz + 2 * pad_s, 4), dtype=np.uint8)
            sh_rgba[:, :, 3] = (np.array(sh_m, dtype=np.float32) * 0.22).astype(np.uint8)
            sheet.alpha_composite(Image.fromarray(sh_rgba, "RGBA"), (ix - pad_s, iy - pad_s + 7))
            sheet.alpha_composite(ic, (ix, iy))
            tw = draw.textbbox((0, 0), lbl, font=f_b2)[2]
            lx = ix + (isz - tw) // 2
            draw.rounded_rectangle([lx - 14, iy + isz + 16, lx + tw + 14, iy + isz + 44], radius=12, fill=(8, 18, 34, 145))
            draw.text((lx, iy + isz + 21), lbl, fill=(246, 249, 255), font=f_b2)

    # Bottom Section: Restrained iOS 26 UI Controls (Search Pill, Droplet Switch, Clear vs Tinted 26.1, Floating Dock)
    by0 = 636
    cur_scene = sheet.convert("RGB")

    # 1. Floating Search Pill
    sp_box = (64, by0 + 28, 384, by0 + 116)
    sheet.alpha_composite(render_restrained_glass_pill(cur_scene, sp_box, radius=44, tinted_mode=False), (sp_box[0], sp_box[1]))
    draw.ellipse([136, by0 + 58, 160, by0 + 82], outline=(255, 255, 255, 245), width=3)
    draw.line([(157, by0 + 79), (168, by0 + 90)], fill=(255, 255, 255, 245), width=3)
    draw.text((182, by0 + 60), "Search", fill=(255, 255, 255, 248), font=f_h2)

    # 2. Restrained Droplet Switch Card (showing ON resting crisp white pill + mid-drag clear droplet)
    sw_box = (424, by0 + 16, 1180, by0 + 128)
    sheet.alpha_composite(render_restrained_glass_pill(cur_scene, sw_box, radius=44, tinted_mode=False), (sw_box[0], sw_box[1]))
    draw.rounded_rectangle([446, by0 + 32, 858, by0 + 112], radius=22, fill=(8, 18, 34, 118))
    draw.text((466, by0 + 40), "Restrained Liquid Droplet Switch", fill=(250, 252, 255), font=f_h2)
    draw.text((466, by0 + 70), "Crisp white resting pill \u2022 Clear Snell droplet on drag", fill=(195, 228, 250), font=f_sm)
    # Resting ON switch
    draw.rounded_rectangle([878, by0 + 44, 1002, by0 + 100], radius=28, fill=(52, 199, 89, 250), outline=(255, 255, 255, 130), width=1)
    draw.rounded_rectangle([928, by0 + 49, 997, by0 + 95], radius=23, fill=(255, 255, 255, 255))
    # Mid-drag lifted droplet switch
    draw.rounded_rectangle([1022, by0 + 44, 1152, by0 + 100], radius=28, fill=(52, 199, 89, 235), outline=(255, 255, 255, 130), width=1)
    sw_scene = sheet.convert("RGB")
    drp_box = (1054, by0 + 34, 1144, by0 + 110)
    sheet.alpha_composite(render_restrained_glass_pill(sw_scene, drp_box, radius=38, tinted_mode=False), (drp_box[0], drp_box[1]))

    # 3. iOS 26.1 Tinted Mode Comparison Card
    tn_box = (1220, by0 + 16, W - 64, by0 + 128)
    sheet.alpha_composite(render_restrained_glass_pill(cur_scene, tn_box, radius=44, tinted_mode=True), (tn_box[0], tn_box[1]))
    draw.text((1254, by0 + 40), "iOS 26.1 'Tinted' Mode (SamuraiParts)", fill=(250, 252, 255), font=f_h2)
    draw.text((1254, by0 + 70), "Elevated frosted contrast veil + restrained 34\u00b0 catchlight", fill=(205, 225, 246), font=f_sm)

    # 4. Full-Width Restrained Frosted Liquid Glass Home Dock
    dock_box = (64, by0 + 168, W - 64, H - 38)
    sheet.alpha_composite(render_restrained_glass_pill(cur_scene, dock_box, radius=56, tinted_mode=False), (dock_box[0], dock_box[1]))
    dock_rgb = sheet.convert("RGB")

    dic_sz = 168
    iy_d = dock_box[1] + (dock_box[3] - dock_box[1] - dic_sz) // 2
    slot_w = (dock_box[2] - dock_box[0] - 120) // 6
    rens = [ph_ren, ms_ren, st_ren]
    for k in range(6):
        ix_d = dock_box[0] + 60 + k * slot_w + (slot_w - dic_sz) // 2
        if k == 3:
            mx = (dock_box[0] + dock_box[2]) // 2
            draw.line([(mx, dock_box[1] + 38), (mx, dock_box[3] - 38)], fill=(255, 255, 255, 78), width=2)
        mkey = "default" if k < 3 else "clear"
        patch_d = dock_rgb.crop((ix_d, iy_d, ix_d + dic_sz, iy_d + dic_sz))
        ic_d = rens[k % 3].render(mkey, out_size=dic_sz, backdrop_rgb=patch_d if mkey == "clear" else None)
        pad_d = 22
        sh_pad = Image.new("L", (dic_sz + 2 * pad_d, dic_sz + 2 * pad_d), 0)
        sh_pad.paste(ic_d.split()[3], (pad_d, pad_d))
        sh_m = sh_pad.filter(ImageFilter.GaussianBlur(radius=9))
        sh_rgba = np.zeros((dic_sz + 2 * pad_d, dic_sz + 2 * pad_d, 4), dtype=np.uint8)
        sh_rgba[:, :, 3] = (np.array(sh_m, dtype=np.float32) * 0.20).astype(np.uint8)
        sheet.alpha_composite(Image.fromarray(sh_rgba, "RGBA"), (ix_d - pad_d, iy_d - pad_d + 7))
        sheet.alpha_composite(ic_d, (ix_d, iy_d))

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    sheet.convert("RGB").save(out_path, format="PNG", optimize=True)


def main() -> int:
    script_dir = os.path.dirname(os.path.abspath(__file__))
    a17_root = os.path.dirname(script_dir)
    preview_out = (
        sys.argv[1]
        if len(sys.argv) > 1
        else os.path.join(a17_root, "..", "work", "a17", "ios26_step1_icons_preview_v2.png")
    )

    print("[ios26-icons-v3.0] Precomputing 1024x1024 G2 continuous squircle & multi-plane SDF fields...")
    shared = SharedSquircleOptics(MASTER_72DP)
    ph_ren = CleanRoomPhoneIconRenderer(shared)
    ms_ren = CleanRoomMessagesIconRenderer(shared)
    st_ren = CleanRoomSettingsIconRenderer(shared)
    gm_ren = CleanRoomGmailIconRenderer(shared)
    gp_ren = CleanRoomGooglePhotosIconRenderer(shared)
    ch_ren = CleanRoomChromeIconRenderer(shared)

    print("[ios26-icons-v3.0] Synthesizing 1:1 Default icons & 1536x1536 (108dp) zero-seam AdaptiveIconDrawable layers...")
    ph_info = ph_ren.render("default", out_size=MASTER_72DP, return_dict=True)
    ms_info = ms_ren.render("default", out_size=MASTER_72DP, return_dict=True)
    st_info = st_ren.render("default", out_size=MASTER_72DP, return_dict=True)
    gm_info = gm_ren.render("default", out_size=MASTER_72DP, return_dict=True)
    gp_info = gp_ren.render("default", out_size=MASTER_72DP, return_dict=True)
    ch_info = ch_ren.render("default", out_size=MASTER_72DP, return_dict=True)

    ph_bg_108, ph_fg_108 = build_adaptive_108dp_layers_2plane(shared, "emerald", ph_info)
    ms_bg_108, ms_fg_108 = build_adaptive_108dp_layers_2plane(shared, "emerald", ms_info)
    st_bg_108, st_fg_108 = build_adaptive_108dp_layers_2plane(shared, "slate", st_info)
    gm_bg_108, gm_fg_108 = build_adaptive_108dp_layers_colored(shared, "porcelain", gm_info, gm_ren.m_total)
    gp_bg_108, gp_fg_108 = build_adaptive_108dp_layers_colored(shared, "porcelain", gp_info, gp_ren.p_union)

    dial_res = os.path.join(a17_root, "overlays", "SamuraiDialerOverlay", "res")
    save_density_set(ph_bg_108, dial_res, "drawable", "ios26_phone_bg.png")
    save_density_set(ph_fg_108, dial_res, "drawable", "ios26_phone_fg.png")

    msg_res = os.path.join(a17_root, "overlays", "SamuraiMessagesOverlay", "res")
    save_density_set(ms_bg_108, msg_res, "drawable", "ios26_messages_bg.png")
    save_density_set(ms_fg_108, msg_res, "drawable", "ios26_messages_fg.png")

    set_res = os.path.join(a17_root, "overlays", "SamuraiSettingsOverlay", "res")
    save_density_set(st_bg_108, set_res, "drawable", "ios26_settings_bg.png")
    save_density_set(st_fg_108, set_res, "drawable", "ios26_settings_fg.png")

    gm_res = os.path.join(a17_root, "overlays", "SamuraiGmailOverlay", "res")
    save_density_set(gm_bg_108, gm_res, "drawable", "ios26_gmail_bg.png")
    save_density_set(gm_fg_108, gm_res, "drawable", "ios26_gmail_fg.png")

    gp_res = os.path.join(a17_root, "overlays", "SamuraiPhotosOverlay", "res")
    save_density_set(gp_bg_108, gp_res, "drawable", "ios26_photos_bg.png")
    save_density_set(gp_fg_108, gp_res, "drawable", "ios26_photos_fg.png")

    parts_nodpi = os.path.join(a17_root, "SamuraiParts", "res", "drawable-nodpi")
    os.makedirs(parts_nodpi, exist_ok=True)
    for out_fn, inf in [
        ("ic_showcase_ios26_phone.png", ph_info),
        ("ic_showcase_ios26_messages.png", ms_info),
        ("ic_showcase_ios26_settings.png", st_info),
        ("ic_showcase_ios26_gmail.png", gm_info),
        ("ic_showcase_ios26_photos.png", gp_info),
        ("ic_showcase_ios26_chrome.png", ch_info),
    ]:
        inf["composite"].resize((256, 256), Image.Resampling.LANCZOS).save(
            os.path.join(parts_nodpi, out_fn), format="PNG", optimize=True
        )

    print(f"[ios26-icons-v3.0] Rendering restrained iOS 26 preview sheet -> {preview_out}")
    generate_preview_sheet_v3(preview_out, ph_ren, ms_ren, st_ren)
    print("[ios26-icons-v3.0] Done! Exported 50 RRO density PNGs + 6 SamuraiParts nodpi icons + preview sheet.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
