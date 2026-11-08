#!/usr/bin/env python3
"""
1:1 Visual & Optical Geometry Showcase Renderer (`1080x2400`) for
Samurai Android 17 iOS 26 Phone & InCallService (`com.realme.samurai.dialer`).

Mirrors `DialerGlassEngine.SURFACE_AGSL`, `DialerGlassEngine.buildProceduralBackdrop`,
`DialerGlassEngine.captureLiveScene` (`!isCapturingScene()` self-refraction guard + `1/10` linear
2-pass box-blurred frosted backdrop), `DialerGlassEngine.drawZeroAllocTabIcon` (solid SF Symbols
with `Path.Op.DIFFERENCE` cutouts + 10-dot `3x3 + 0` keypad glyph), 13-orb Keypad + Call neighbor
caustic coupling, and `IosCallActivity` In-Call headers (status/timer above caller name, zero
cartoon monogram circle when `hasPhoto == false`).
"""
import math
import os
import sys
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont


def clamp(v, lo, hi):
    return lo if v < lo else (hi if v > hi else v)


def circle_map(x):
    xc = clamp(x, 0.0, 1.0)
    return 1.0 - math.sqrt(max(0.0, 1.0 - xc * xc))


def load_font(size, bold=False):
    candidates = (
        [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        ]
        if bold
        else [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
        ]
    )
    for path in candidates:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def make_aurora(w, h, mode="aurora"):
    """
    Exact 1:1 mirror of `DialerGlassEngine.buildProceduralBackdrop(int theme)` 4-lobe + wave
    studio optical fields (`DialerGlassEngine.java:1358-1425`):
      - light:  231..253 (22/255 span)
      - dark:   10..38   (28/255 span)
      - aurora: 14..78   (64/255 span)
    """
    img = Image.new("RGBA", (max(2, w // 2), max(2, h // 2)))
    px = img.load()
    iw, ih = img.size
    for y in range(ih):
        yn = y / max(1, ih - 1)
        for x in range(iw):
            xn = x / max(1, iw - 1)
            dx1, dy1 = xn - 0.18, yn - 0.16
            lobe_tl = math.exp(-(dx1 * dx1 + dy1 * dy1) / 0.13)

            dx2, dy2 = xn - 0.84, yn - 0.50
            lobe_mr = math.exp(-(dx2 * dx2 + dy2 * dy2) / 0.11)

            dx3, dy3 = xn - 0.22, yn - 0.72
            trough_ll = math.exp(-(dx3 * dx3 + dy3 * dy3) / 0.12)

            dx4, dy4 = xn - 0.64, yn - 0.90
            trough_bc = math.exp(-(dx4 * dx4 + dy4 * dy4) / 0.14)

            diag_wave = 0.5 + 0.5 * math.sin((xn * 2.35 - yn * 1.85) * math.pi)
            cross_wave = 0.5 + 0.5 * math.cos((xn * 1.65 + yn * 2.15) * math.pi)

            f_warm = clamp(
                0.44 + 0.34 * lobe_tl + 0.28 * lobe_mr
                - 0.30 * trough_ll - 0.24 * trough_bc + 0.14 * diag_wave,
                0.0,
                1.0,
            )
            f_mid = clamp(
                0.45 + 0.32 * lobe_tl + 0.32 * lobe_mr
                - 0.28 * trough_ll - 0.25 * trough_bc + 0.14 * cross_wave,
                0.0,
                1.0,
            )
            f_cool = clamp(
                0.48 + 0.28 * lobe_tl + 0.36 * lobe_mr
                - 0.26 * trough_ll - 0.24 * trough_bc + 0.16 * cross_wave,
                0.0,
                1.0,
            )

            if mode == "light":
                r = int(round(clamp(231.0 + 22.0 * f_warm, 0.0, 255.0)))
                g = int(round(clamp(232.0 + 21.0 * f_mid, 0.0, 255.0)))
                b = int(round(clamp(234.0 + 19.0 * f_cool, 0.0, 255.0)))
            elif mode == "dark":
                r = int(round(clamp(10.0 + 24.0 * f_warm, 0.0, 255.0)))
                g = int(round(clamp(11.0 + 25.0 * f_mid, 0.0, 255.0)))
                b = int(round(clamp(13.0 + 25.0 * f_cool, 0.0, 255.0)))
            else:
                r = int(round(clamp(14.0 + 46.0 * f_warm, 0.0, 255.0)))
                g = int(round(clamp(17.0 + 52.0 * f_mid, 0.0, 255.0)))
                b = int(round(clamp(22.0 + 56.0 * f_cool, 0.0, 255.0)))
            px[x, y] = (r, g, b, 255)
    return img.resize((w, h), Image.Resampling.BICUBIC)


def build_live_soft_backdrop(scene_img):
    """
    Mirrors `DialerGlassEngine.captureLiveScene` -> `sBlurExecutor` 1/10-scale 2-pass box blur
    (`108x240` R=7 on `1080x2400` == `54x116` R=4 2-pass box blur on `486x1044`), melting sharp
    row glyph strokes into creamy multi-hue glass clouds while preserving underlying avatar/row color!
    """
    w, h = scene_img.size
    sw = max(12, int(round(w / 9.0)))
    sh = max(12, int(round(h / 9.0)))
    small = scene_img.resize((sw, sh), Image.Resampling.BILINEAR)
    small = small.filter(ImageFilter.BoxBlur(4)).filter(ImageFilter.BoxBlur(4))
    return small.resize((w, h), Image.Resampling.BICUBIC)


def sd_round_box(px, py, hw, hh, r):
    qx = abs(px) - (hw - r)
    qy = abs(py) - (hh - r)
    ox = max(qx, 0.0)
    oy = max(qy, 0.0)
    return math.hypot(ox, oy) + min(max(qx, qy), 0.0) - r


def sample_bilinear(px_access, w, h, fx, fy):
    fx = clamp(fx, 0.0, w - 1.001)
    fy = clamp(fy, 0.0, h - 1.001)
    ix = int(fx)
    iy = int(fy)
    tx = fx - ix
    ty = fy - iy
    c00 = px_access[ix, iy]
    c10 = px_access[min(w - 1, ix + 1), iy]
    c01 = px_access[ix, min(h - 1, iy + 1)]
    c11 = px_access[min(w - 1, ix + 1), min(h - 1, iy + 1)]
    r0 = c00[0] + (c10[0] - c00[0]) * tx
    r1 = c01[0] + (c11[0] - c01[0]) * tx
    g0 = c00[1] + (c10[1] - c00[1]) * tx
    g1 = c01[1] + (c11[1] - c01[1]) * tx
    b0 = c00[2] + (c10[2] - c00[2]) * tx
    b1 = c01[2] + (c11[2] - c01[2]) * tx
    return (
        r0 + (r1 - r0) * ty,
        g0 + (g1 - g0) * ty,
        b0 + (b1 - b0) * ty,
    )


def draw_refractive_glass(
    base,
    box,
    radius,
    dark=False,
    incall=False,
    tint_boost=0.0,
    tint_col=None,
    tint_mix=0.0,
    touch_x=None,
    touch_y=None,
    touch_amp=0.0,
    neighbor_dir=(0.0, -1.0),
    neighbor_glow=0.0,
    backdrop_src=None,
    soft_src=None,
):
    """
    Software reference implementation of `DialerGlassEngine.SURFACE_AGSL`:
      - Spherical convex `circleMap` meniscus displacement (`18px + 9px * touch_amp`)
      - Chromatic R/B dispersion (`2.35px`)
      - Creamy frosted interior (`uSoftBg`) -> crisp refractive meniscus rim (`1.0 - 0.48 * meniscus`)
      - -34 deg specular Fresnel rim + inner counter-caustic (`0.042`) + neighbor caustic glow
      - Optional colored glass (`tint_col`, `tint_mix`) and lit-white in-call mode
    """
    src_img = backdrop_src if backdrop_src is not None else base
    sw, sh = src_img.size
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    bw, bh = max(2, x1 - x0), max(2, y1 - y0)
    r = min(float(radius), min(bw, bh) * 0.5)
    hw, hh = bw * 0.5, bh * 0.5

    # Soft ambient drop shadow onto `base`
    sh_img = Image.new("RGBA", (bw + 20, bh + 20), (0, 0, 0, 0))
    ImageDraw.Draw(sh_img).rounded_rectangle(
        (10, 12, bw + 9, bh + 11),
        radius=int(round(r)),
        fill=(0, 8, 22, 46 if (dark or incall) else 20),
    )
    sh_img = sh_img.filter(ImageFilter.GaussianBlur(radius=4))
    base.alpha_composite(sh_img, (max(0, x0 - 10), max(0, y0 - 8)))

    pad = 36
    cx0 = max(0, x0 - pad)
    cy0 = max(0, y0 - pad)
    cx1 = min(sw, x1 + pad)
    cy1 = min(sh, y1 + pad)
    sharp_crop = src_img.crop((cx0, cy0, cx1, cy1))
    if soft_src is not None:
        soft_crop = soft_src.crop((cx0, cy0, cx1, cy1))
    else:
        soft_crop = sharp_crop.filter(ImageFilter.GaussianBlur(radius=18))
    cw, ch = sharp_crop.size
    sharp_px = sharp_crop.load()
    soft_px = soft_crop.load()

    out = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
    out_px = out.load()

    tx_local = hw if touch_x is None else float(touch_x)
    ty_local = hh if touch_y is None else float(touch_y)
    bevel_band = clamp(min(bw, bh) * 0.36, 10.0, 28.0)
    n_dx, n_dy = neighbor_dir
    n_len = math.hypot(n_dx, n_dy)
    if n_len > 1e-4:
        n_dx /= n_len
        n_dy /= n_len
    else:
        n_dx, n_dy = 0.0, -1.0

    rim_scale = 0.52 if (dark or incall) else 0.46
    base_frost = 0.90 if soft_src is not None else 0.72

    for ly in range(bh):
        py = (ly + 0.5) - hh
        y_norm = clamp((ly + 0.5) / max(float(bh), 1.0), 0.0, 1.0)
        for lx in range(bw):
            px_c = (lx + 0.5) - hw
            d = sd_round_box(px_c, py, hw, hh, r)
            if d > 0.75:
                continue
            alpha = clamp((0.75 - d) / 1.60, 0.0, 1.0)

            # Outward SDF normal via central finite differences (`eps = 1.15`)
            eps = 1.15
            gx = sd_round_box(px_c + eps, py, hw, hh, r) - sd_round_box(px_c - eps, py, hw, hh, r)
            gy = sd_round_box(px_c, py + eps, hw, hh, r) - sd_round_box(px_c, py - eps, hw, hh, r)
            glen = math.hypot(gx, gy)
            if glen > 1e-4:
                nx, ny = gx / glen, gy / glen
            else:
                nx, ny = 0.0, -1.0

            edge_frac = 1.0 - clamp(-d / max(bevel_band, 1.0), 0.0, 1.0)
            meniscus = circle_map(edge_frac)

            rip_x, rip_y, touch_glow = 0.0, 0.0, 0.0
            if touch_amp > 0.005:
                tdx = (lx + 0.5) - tx_local
                tdy = (ly + 0.5) - ty_local
                tdist = math.hypot(tdx, tdy)
                sigma = 80.0
                bump = touch_amp * math.exp(-(tdist * tdist) / (sigma * sigma))
                if tdist > 1.0:
                    rip_x = -(tdx / tdist) * bump * 12.0
                    rip_y = -(tdy / tdist) * bump * 12.0
                touch_glow = touch_amp * math.exp(-(tdist * tdist) / (88.0 * 88.0))

            dome_x = px_c / max(hw, 10.0)
            dome_y = py / max(hh, 10.0)
            disp_mag = (18.5 + 8.5 * touch_amp) * meniscus
            dx = -nx * disp_mag - dome_x * 2.2 + rip_x
            dy = -ny * disp_mag - dome_y * 2.2 + rip_y
            disp_rx = nx * 2.35 * meniscus
            disp_ry = ny * 2.35 * meniscus

            sx_base = (x0 + lx + 0.5) - cx0 + dx
            sy_base = (y0 + ly + 0.5) - cy0 + dy

            if abs(disp_rx) + abs(disp_ry) > 0.05:
                sr_r, _, _ = sample_bilinear(sharp_px, cw, ch, sx_base + disp_rx, sy_base + disp_ry)
                _, sr_g, _ = sample_bilinear(sharp_px, cw, ch, sx_base, sy_base)
                _, _, sr_b = sample_bilinear(sharp_px, cw, ch, sx_base - disp_rx, sy_base - disp_ry)
            else:
                sr_r, sr_g, sr_b = sample_bilinear(sharp_px, cw, ch, sx_base, sy_base)

            fr_r, fr_g, fr_b = sample_bilinear(soft_px, cw, ch, sx_base, sy_base)

            # Exact `SURFACE_AGSL` polarity: creamy diffused `uSoftBg` interior with refractive meniscus rim
            meniscus_unfrost = 0.20 if soft_src is not None else 0.48
            frost_mix = clamp(base_frost * (1.0 - meniscus_unfrost * meniscus), 0.0, 0.96)
            col_r = sr_r + (fr_r - sr_r) * frost_mix
            col_g = sr_g + (fr_g - sr_g) * frost_mix
            col_b = sr_b + (fr_b - sr_b) * frost_mix

            # Translucent body veil (`crystalBody + frostVeil + boostVeil` parity)
            if incall:
                vr, vg, vb = 214.0, 230.0, 255.0
                base_veil = 0.22
            elif dark:
                vr = 41.0 + (26.0 - 41.0) * y_norm
                vg = 46.0 + (28.0 - 46.0) * y_norm
                vb = 56.0 + (36.0 - 56.0) * y_norm
                base_veil = 0.40 if soft_src is not None else 0.36
            else:
                vr = 244.0 - 8.0 * y_norm
                vg = 246.0 - 8.0 * y_norm
                vb = 252.0 - 6.0 * y_norm
                base_veil = 0.26
            boost_veil = max(tint_boost, 0.0) * 0.26
            v_amt = clamp(base_veil + boost_veil + 0.10 * touch_glow, 0.0, 0.80)
            col_r = col_r + (vr - col_r) * v_amt
            col_g = col_g + (vg - col_g) * v_amt
            col_b = col_b + (vb - col_b) * v_amt

            # Optional colored / lit-white glass tint (`bindToPaintTinted` / `bindToPaintInCallLit`)
            if tint_col is not None and tint_mix > 0.005:
                top_r = min(255.0, tint_col[0] * 1.16 + 15.0)
                top_g = min(255.0, tint_col[1] * 1.16 + 15.0)
                top_b = min(255.0, tint_col[2] * 1.16 + 15.0)
                bot_r, bot_g, bot_b = tint_col[0] * 0.86, tint_col[1] * 0.86, tint_col[2] * 0.86
                lr = top_r + (bot_r - top_r) * y_norm
                lg = top_g + (bot_g - top_g) * y_norm
                lb = top_b + (bot_b - top_b) * y_norm
                tm = clamp(tint_mix * (1.0 - 0.18 * meniscus), 0.0, 0.92)
                col_r = col_r + (lr - col_r) * tm
                col_g = col_g + (lg - col_g) * tm
                col_b = col_b + (lb - col_b) * tm

            # Convex top dome sheen + -34 deg Fresnel rim + inner counter-caustic + neighbor caustic
            if dark or incall:
                top_sheen = (1.0 - y_norm) * (0.036 + 0.045 * max(tint_boost, 0.0))
                col_r += 255.0 * top_sheen
                col_g += 255.0 * top_sheen
                col_b += 255.0 * top_sheen

            key_spec = max(0.0, nx * -0.5592 + ny * -0.8290) ** 2.2
            back_spec = max(0.0, nx * 0.5592 + ny * 0.8290) ** 3.0
            rim_mask = math.exp(-abs(d) * 1.15)
            inner_caustic = math.exp(-abs(d + 3.2) * 0.65) * (0.042 if (dark or incall) else 0.016)

            if not (dark or incall):
                meniscus_depth = (0.35 + 0.65 * meniscus) * (0.62 - 0.35 * key_spec)
                col_r -= 22.0 * meniscus_depth
                col_g -= 20.0 * meniscus_depth
                col_b -= 14.0 * meniscus_depth

            spec = rim_mask * (rim_scale * (0.28 + 0.72 * key_spec) + 0.18 * back_spec) + inner_caustic
            col_r += 255.0 * spec
            col_g += 255.0 * spec
            col_b += 255.0 * spec

            if neighbor_glow > 0.004:
                ndot = max(0.0, nx * n_dx + ny * n_dy)
                nb_band = math.exp(-(((d + 5.5) / 6.5) ** 2))
                nc = neighbor_glow * (ndot ** 1.35) * (0.34 * meniscus + 0.62 * nb_band)
                col_r += 245.0 * nc
                col_g += 250.0 * nc
                col_b += 255.0 * nc

            out_px[lx, ly] = (
                int(round(clamp(col_r, 0.0, 255.0))),
                int(round(clamp(col_g, 0.0, 255.0))),
                int(round(clamp(col_b, 0.0, 255.0))),
                int(round(clamp(alpha * 255.0, 0.0, 255.0))),
            )

    base.alpha_composite(out, (x0, y0))


def draw_phone_glyph(draw, cx, cy, size, color, hangup=False):
    ang_base = math.radians(135 if hangup else 0)
    pts_local = [
        (-0.34, -0.40), (-0.14, -0.44), (-0.06, -0.22), (-0.16, -0.10),
        (0.10, 0.16), (0.22, 0.06), (0.44, 0.14), (0.40, 0.34),
        (0.20, 0.44), (-0.18, 0.24), (-0.44, -0.20),
    ]
    pts = []
    for lx, ly in pts_local:
        rx = lx * math.cos(ang_base) - ly * math.sin(ang_base)
        ry = lx * math.sin(ang_base) + ly * math.cos(ang_base)
        pts.append((cx + rx * size, cy + ry * size))
    draw.polygon(pts, fill=color)


def draw_solid_tab_icon(screen, tab_idx, cx, cy, size, color):
    """
    Exact 1:1 mirror of `DialerGlassEngine.drawZeroAllocTabIcon` (`DialerGlassEngine.java:1753-1848`):
      - 0 (`TAB_FAVORITES`): solid `star.fill`
      - 1 (`TAB_RECENTS`):   solid `clock.fill` disc with `Path.Op.DIFFERENCE` transparent cutout hands
      - 2 (`TAB_CONTACTS`):  solid `person.crop.circle.fill` disc with head + shoulders cutout
      - 3 (`TAB_KEYPAD`):    solid 10-dot `3x3 + 0` keypad glyph
      - 4 (`TAB_VOICEMAIL`): twin-reel `recordingtape`
    """
    scale = 3
    patch_sz = int(math.ceil(size * 1.5)) * scale
    if patch_sz % 2 == 1:
        patch_sz += 1
    pc = patch_sz * 0.5
    s = size * scale
    r = s * 0.5

    mask = Image.new("L", (patch_sz, patch_sz), 0)
    md = ImageDraw.Draw(mask)

    if tab_idx == 0:  # star.fill
        outer = r * 0.98
        inner = outer * 0.44
        pts = []
        for k in range(10):
            ang = -math.pi / 2.0 + k * (math.pi / 5.0)
            rad = outer if k % 2 == 0 else inner
            pts.append((pc + math.cos(ang) * rad, pc + math.sin(ang) * rad + s * 0.02))
        md.polygon(pts, fill=255)
    elif tab_idx == 1:  # clock.fill with DIFFERENCE cutout hands
        cr = r * 0.90
        hw = max(3.2, s * 0.060)
        md.ellipse((pc - cr, pc - cr, pc + cr, pc + cr), fill=255)
        md.rounded_rectangle((pc - hw, pc - cr * 0.60, pc + hw, pc + hw), radius=hw, fill=0)
        md.rounded_rectangle((pc - hw, pc - hw, pc + cr * 0.52, pc + hw), radius=hw, fill=0)
    elif tab_idx == 2:  # person.crop.circle.fill with DIFFERENCE head + clipped shoulders cutout
        cr = r * 0.92
        md.ellipse((pc - cr, pc - cr, pc + cr, pc + cr), fill=255)
        cut = Image.new("L", (patch_sz, patch_sz), 0)
        cd = ImageDraw.Draw(cut)
        hr = cr * 0.30
        hcy = pc - cr * 0.20
        cd.ellipse((pc - hr, hcy - hr, pc + hr, hcy + hr), fill=255)

        sh_mask = Image.new("L", (patch_sz, patch_sz), 0)
        ImageDraw.Draw(sh_mask).ellipse(
            (pc - cr * 0.64, pc + cr * 0.20, pc + cr * 0.64, pc + cr * 1.16),
            fill=255,
        )
        clip_mask = Image.new("L", (patch_sz, patch_sz), 0)
        ir = cr * 0.78
        ImageDraw.Draw(clip_mask).ellipse((pc - ir, pc - ir, pc + ir, pc + ir), fill=255)
        sh_clipped = ImageChops.darker(sh_mask, clip_mask)
        cut = ImageChops.lighter(cut, sh_clipped)
        mask = ImageChops.subtract(mask, cut)
    elif tab_idx == 3:  # 10-dot `3x3 + 0` keypad glyph (`DialerGlassEngine.java:1828-1840`)
        dot_r = s * 0.088
        step_x = s * 0.285
        step_y = s * 0.252
        for row in range(3):
            ry = pc + (row - 1.42) * step_y
            for col_i in (-1, 0, 1):
                dx = pc + col_i * step_x
                md.ellipse((dx - dot_r, ry - dot_r, dx + dot_r, ry + dot_r), fill=255)
        ry0 = pc + (3.0 - 1.42) * step_y
        md.ellipse((pc - dot_r, ry0 - dot_r, pc + dot_r, ry0 + dot_r), fill=255)
    elif tab_idx == 4:  # recordingtape twin reel
        sw = max(4, int(round(s * 0.098)))
        rr = s * 0.22
        lx = pc - s * 0.24
        rx = pc + s * 0.24
        md.ellipse((lx - rr, pc - rr, lx + rr, pc + rr), outline=255, width=sw)
        md.ellipse((rx - rr, pc - rr, rx + rr, pc + rr), outline=255, width=sw)
        md.line((lx, pc + rr - sw * 0.35, rx, pc + rr - sw * 0.35), fill=255, width=sw)

    out_sz = patch_sz // scale
    mask_1x = mask.resize((out_sz, out_sz), Image.Resampling.LANCZOS)
    if len(color) == 4 and color[3] < 255:
        mask_1x = mask_1x.point(lambda a: int(round(a * (color[3] / 255.0))))
    glyph = Image.new("RGBA", (out_sz, out_sz), (color[0], color[1], color[2], 0))
    glyph.putalpha(mask_1x)
    screen.alpha_composite(glyph, (int(round(cx - out_sz * 0.5)), int(round(cy - out_sz * 0.5))))


def get_tab_bar_bounds(w, h):
    mx = 18
    bh = 78
    y0 = h - bh - 20
    y1 = h - 20
    return (mx, y0, w - mx, y1)


def draw_tab_bar(screen, dark, active_idx, missed_badge=2, backdrop_src=None, soft_src=None):
    w, h = screen.size
    mx, y0, x1, y1 = get_tab_bar_bounds(w, h)
    bh = y1 - y0
    scene_snapshot = backdrop_src if backdrop_src is not None else screen.copy()
    live_soft = soft_src if soft_src is not None else build_live_soft_backdrop(scene_snapshot)

    draw_refractive_glass(
        screen,
        (mx, y0, x1, y1),
        radius=bh * 0.5,
        dark=dark,
        backdrop_src=scene_snapshot,
        soft_src=live_soft,
    )

    pad_x = 10
    pad_y = 6
    slot_w = (w - 2 * mx - 2 * pad_x) / 5.0
    px0 = mx + pad_x + active_idx * slot_w
    px1 = px0 + slot_w
    bar_frosted = screen.copy()
    draw_refractive_glass(
        screen,
        (px0, y0 + pad_y, px1, y1 - pad_y),
        radius=(bh - 2 * pad_y) * 0.5,
        dark=dark,
        tint_boost=0.28 if dark else 0.34,
        backdrop_src=bar_frosted,
        soft_src=live_soft,
    )

    d = ImageDraw.Draw(screen)
    blue = (10, 132, 255, 255) if dark else (0, 122, 255, 255)
    inact = (174, 174, 178, 228) if dark else (24, 24, 28, 235)
    labels = ["Favorites", "Recents", "Contacts", "Keypad", "Voicemail"]
    f_lbl = load_font(12, bold=True)
    f_bdg = load_font(11, bold=True)

    for i, lbl in enumerate(labels):
        cx = mx + pad_x + (i + 0.5) * slot_w
        icy = y0 + 27
        col = blue if i == active_idx else inact
        draw_solid_tab_icon(screen, i, cx, icy, 26.0, col)
        if i == 1 and missed_badge > 0:
            d.rounded_rectangle((cx + 5, icy - 16, cx + 22, icy - 2), radius=7, fill=(255, 59, 48, 255))
            d.text((cx + 13.5, icy - 9), str(missed_badge), fill=(255, 255, 255, 255), font=f_bdg, anchor="mm")
        d.text((cx, y1 - 15), lbl, fill=col, font=f_lbl, anchor="mm")


def draw_status_bar(screen, dark_text=True):
    d = ImageDraw.Draw(screen)
    w, _ = screen.size
    col = (12, 14, 18, 255) if dark_text else (255, 255, 255, 245)
    f = load_font(19, bold=True)
    d.text((42, 26), "9:41", fill=col, font=f, anchor="lm")
    bx = w - 66
    for i in range(4):
        bh = 6 + i * 3
        d.rounded_rectangle((bx - 34 + i * 6, 32 - bh, bx - 30 + i * 6, 32), radius=1, fill=col)
    d.rounded_rectangle((bx, 19, bx + 28, 32), radius=4, outline=col, width=2)
    d.rounded_rectangle((bx + 3, 22, bx + 23, 29), radius=2, fill=col)


def get_keypad_geometry(w):
    dia = 108
    gap_x = 26
    gap_y = 20
    total_w = 3 * dia + 2 * gap_x
    sx = (w - total_w) // 2
    sy = 240
    boxes = []
    for idx in range(12):
        r, c = divmod(idx, 3)
        x0 = sx + c * (dia + gap_x)
        y0 = sy + r * (dia + gap_y)
        boxes.append((x0, y0, x0 + dia, y0 + dia))
    return sx, sy, dia, gap_x, gap_y, boxes


def compute_neighbor_caustic(idx, centers, press_amps, inv_sigma_sq):
    cx, cy = centers[idx]
    sum_x, sum_y, sum_w = 0.0, 0.0, 0.0
    for j, (ox, oy) in enumerate(centers):
        if j == idx or press_amps[j] <= 0.005:
            continue
        ddx = ox - cx
        ddy = oy - cy
        dsq = ddx * ddx + ddy * ddy
        dist = math.sqrt(dsq)
        if dist > 1.0:
            w_j = press_amps[j] * math.exp(-dsq * inv_sigma_sq)
            sum_x += (ddx / dist) * w_j
            sum_y += (ddy / dist) * w_j
            sum_w += w_j
    if sum_w > 0.002:
        vlen = math.hypot(sum_x, sum_y)
        if vlen > 1e-4:
            return (sum_x / vlen, sum_y / vlen), clamp(sum_w, 0.0, 1.0)
    return (0.0, -1.0), 0.0


def render_keypad_screen(w, h, pressed_idx=4, enable_neighbor_caustics=True):
    base_bg = make_aurora(w, h, "light")
    scr = base_bg.copy()
    draw_status_bar(scr, dark_text=True)

    # Dual-SIM selector capsule
    draw_refractive_glass(scr, (w // 2 - 86, 54, w // 2 + 86, 94), radius=20, dark=False, backdrop_src=base_bg)
    d = ImageDraw.Draw(scr)
    d.text((w // 2, 74), "S1 · Jio True5G", fill=(0, 122, 255, 255), font=load_font(16, bold=True), anchor="mm")

    # Formatted Readout + Contact Match (`pre_glass_scene` excludes all glass surfaces per `!isCapturingScene()`)
    d.text((w // 2, 152), "(415) 555-0192", fill=(10, 12, 16, 255), font=load_font(48, bold=False), anchor="mm")
    d.text((w // 2, 198), "Zoe Sterling · mobile", fill=(108, 108, 114, 255), font=load_font(19, bold=True), anchor="mm")
    pre_glass_scene = scr.copy()

    keys = [
        ("1", "➿"), ("2", "A B C"), ("3", "D E F"),
        ("4", "G H I"), ("5", "J K L"), ("6", "M N O"),
        ("7", "P Q R S"), ("8", "T U V"), ("9", "W X Y Z"),
        ("*", ""), ("0", "+"), ("#", ""),
    ]
    sx, sy, dia, gap_x, gap_y, boxes = get_keypad_geometry(w)
    call_x0 = sx + (dia + gap_x)
    call_y0 = sy + 4 * (dia + gap_y)

    f_num = load_font(44, bold=False)
    f_sub = load_font(13, bold=True)

    # Exact 13-orb neighbor caustic coupling (`KeypadTabView.java:156, 360-384`: 12 digit keys + Call orb)
    centers = [((b[0] + b[2]) * 0.5, (b[1] + b[3]) * 0.5) for b in boxes]
    centers.append((call_x0 + dia * 0.5, call_y0 + dia * 0.5))
    press_amps = [0.85 if i == pressed_idx else 0.0 for i in range(13)]
    inv_sigma_sq = 1.0 / ((1.35 * dia) ** 2)

    for idx, (k, sub) in enumerate(keys):
        x0, y0, x1, y1 = boxes[idx]
        cx, cy = centers[idx]
        bulge = press_amps[idx] > 0.01
        pad = -4 if bulge else 0

        ndir, nglow = ((0.0, -1.0), 0.0)
        if enable_neighbor_caustics:
            ndir, nglow = compute_neighbor_caustic(idx, centers, press_amps, inv_sigma_sq)

        draw_refractive_glass(
            scr,
            (x0 + pad, y0 + pad, x1 - pad, y1 - pad),
            radius=(dia - 2 * pad) * 0.5,
            dark=False,
            tint_boost=0.05 if bulge else 0.0,
            touch_x=(dia - 2 * pad) * 0.46,
            touch_y=(dia - 2 * pad) * 0.44,
            touch_amp=press_amps[idx],
            neighbor_dir=ndir,
            neighbor_glow=nglow,
            backdrop_src=base_bg,
        )
        d = ImageDraw.Draw(scr)
        if sub == "":
            d.text((cx, cy + 2), k, fill=(12, 14, 18, 255), font=f_num, anchor="mm")
        elif sub == "➿":
            d.text((cx, cy - 8), k, fill=(12, 14, 18, 255), font=f_num, anchor="mm")
            d.ellipse((cx - 11, cy + 19, cx - 1, cy + 29), outline=(108, 108, 114, 255), width=2)
            d.ellipse((cx + 1, cy + 19, cx + 11, cy + 29), outline=(108, 108, 114, 255), width=2)
            d.line((cx - 6, cy + 29, cx + 6, cy + 29), fill=(108, 108, 114, 255), width=2)
        else:
            d.text((cx, cy - 8), k, fill=(12, 14, 18, 255), font=f_num, anchor="mm")
            d.text((cx, cy + 24), sub, fill=(32, 34, 40, 255), font=f_sub, anchor="mm")

    # 13th coupled orb: Emerald Refractive Liquid Glass Call Lens (`mCallGlass.bindToPaintTinted`)
    call_ndir, call_nglow = ((0.0, -1.0), 0.0)
    if enable_neighbor_caustics:
        call_ndir, call_nglow = compute_neighbor_caustic(12, centers, press_amps, inv_sigma_sq)
    draw_refractive_glass(
        scr,
        (call_x0, call_y0, call_x0 + dia, call_y0 + dia),
        radius=dia * 0.5,
        dark=False,
        tint_boost=0.18,
        tint_col=(52, 199, 89),
        tint_mix=0.76,
        touch_amp=press_amps[12],
        neighbor_dir=call_ndir,
        neighbor_glow=call_nglow,
        backdrop_src=base_bg,
    )
    d = ImageDraw.Draw(scr)
    draw_phone_glyph(d, call_x0 + dia / 2, call_y0 + dia / 2, dia * 0.46, (255, 255, 255, 255), hangup=False)

    # Refractive Backspace Orb (`mBackGlass`) + Backspace Tag
    bx = sx + 2 * (dia + gap_x) + dia // 2
    by = call_y0 + dia // 2
    back_r = 34
    draw_refractive_glass(
        scr,
        (bx - back_r, by - back_r, bx + back_r, by + back_r),
        radius=back_r,
        dark=False,
        backdrop_src=base_bg,
    )
    d = ImageDraw.Draw(scr)
    d.polygon(
        [(bx - 20, by), (bx - 9, by - 13), (bx + 18, by - 13), (bx + 18, by + 13), (bx - 9, by + 13)],
        outline=(78, 82, 92, 235),
        width=2,
    )
    d.line((bx, by - 5, bx + 10, by + 5), fill=(28, 28, 30, 255), width=2)
    d.line((bx + 10, by - 5, bx, by + 5), fill=(28, 28, 30, 255), width=2)

    draw_tab_bar(scr, dark=False, active_idx=3, missed_badge=2, backdrop_src=pre_glass_scene)
    return scr, base_bg


def render_recents_screen(w, h):
    base_bg = make_aurora(w, h, "dark")
    scr = base_bg.copy()
    draw_status_bar(scr, dark_text=False)

    d = ImageDraw.Draw(scr)
    # Bold 34sp Recents Title + Scrolled Call Rows rendered BEFORE glass surfaces so `scene_snapshot`
    # honors `!DialerGlassEngine.isCapturingScene()` (zero self-refraction of `(i)` micro-orbs!)
    d.text((28, 142), "Recents", fill=(255, 255, 255, 255), font=load_font(44, bold=True), anchor="lm")

    rows = [
        ("ZS", (90, 200, 250), "Zoe Sterling (2)", "mobile · (415) 555-0192", "S1", "12:07 PM", True, False),
        ("AS", (52, 199, 89), "Aarav Sharma", "mobile · +91 98201…", "S2", "Yesterday", False, True),
        ("ML", (191, 90, 242), "Maya Lin", "mobile · (650) 555-0148", "S1", "Monday", True, False),
        ("MO", (255, 159, 10), "Mom", "home · (408) 555-0119", "S1", "Sunday", False, True),
        ("CV", (100, 210, 255), "Carrier Voicemail", "voicemail", "S1", "10/29/26", False, True),
        ("DK", (255, 55, 95), "Dev Kapoor (3)", "work · +91 98112…", "S2", "10/27/26", False, False),
        ("RK", (48, 209, 88), "Rohan Kulkarni", "mobile · +91 98450…", "S1", "10/25/26", True, False),
        ("NP", (94, 92, 230), "Nisha Patel", "work · (408) 555-0199", "S2", "10/22/26", False, True),
    ]

    ry = 250
    row_h = 98
    f_t = load_font(21, bold=True)
    f_s = load_font(16, bold=False)
    f_b = load_font(13, bold=True)
    orb_centers = []

    for idx, (ini, av_col, name, sub, sim, dt, missed, outg) in enumerate(rows):
        cy = ry + idx * row_h + row_h // 2
        if cy - 32 > h:
            break
        d.ellipse((26, cy - 28, 82, cy + 28), fill=av_col + (255,))
        d.text((54, cy), ini, fill=(255, 255, 255, 255), font=load_font(20, bold=True), anchor="mm")

        tx = 98
        if outg:
            d.line((tx, cy - 8, tx + 11, cy - 19), fill=(142, 142, 147, 255), width=2)
            d.line((tx + 4, cy - 19, tx + 11, cy - 19), fill=(142, 172, 187, 255), width=2)
            d.line((tx + 11, cy - 19, tx + 11, cy - 12), fill=(142, 142, 147, 255), width=2)
            tx += 18

        t_col = (255, 69, 58, 255) if missed else (255, 255, 255, 255)
        d.text((tx, cy - 12), name, fill=t_col, font=f_t, anchor="lm")

        # [S1]/[S2] Dual-SIM badge + subtitle with >= 22px right margin before timestamp
        d.rounded_rectangle((98, cy + 5, 124, cy + 23), radius=5, fill=(44, 44, 48, 255))
        d.text((111, cy + 14), sim, fill=(168, 168, 176, 255), font=f_b, anchor="mm")
        d.text((132, cy + 14), sub, fill=(152, 154, 162, 255), font=f_s, anchor="lm")

        d.text((w - 68, cy), dt, fill=(152, 154, 162, 255), font=load_font(17), anchor="rm")
        orb_centers.append((w - 36, cy))

        if idx < len(rows) - 1:
            d.line((98, ry + (idx + 1) * row_h, w - 20, ry + (idx + 1) * row_h), fill=(56, 56, 60, 255), width=1)

    # Capture pre-glass live scene (`!isCapturingScene()` parity) + 1/9-scale 2-pass box-blurred soft buffer
    scene_snapshot = scr.copy()
    soft_scene = build_live_soft_backdrop(scene_snapshot)

    # Now composite all glass surfaces (`Edit`, `All|Missed`, `Search`, row `(i)` micro-orbs, and `TabBar`)
    draw_refractive_glass(scr, (26, 56, 104, 102), radius=23, dark=True, backdrop_src=scene_snapshot, soft_src=soft_scene)
    draw_refractive_glass(scr, (w // 2 - 114, 56, w // 2 + 114, 102), radius=23, dark=True, backdrop_src=scene_snapshot, soft_src=soft_scene)
    draw_refractive_glass(
        scr,
        (w // 2 - 110, 60, w // 2 - 2, 98),
        radius=19,
        dark=True,
        tint_boost=0.22,
        backdrop_src=scene_snapshot,
        soft_src=soft_scene,
    )
    draw_refractive_glass(scr, (26, 178, w - 26, 228), radius=25, dark=True, backdrop_src=scene_snapshot, soft_src=soft_scene)

    d = ImageDraw.Draw(scr)
    d.text((65, 79), "Edit", fill=(10, 132, 255, 255), font=load_font(19, bold=False), anchor="mm")
    d.text((w // 2 - 56, 79), "All", fill=(255, 255, 255, 255), font=load_font(17, bold=True), anchor="mm")
    d.text((w // 2 + 56, 79), "Missed", fill=(172, 174, 184, 255), font=load_font(17, bold=True), anchor="mm")
    d.ellipse((44, 194, 60, 210), outline=(168, 170, 178, 255), width=2)
    d.line((58, 208, 65, 215), fill=(168, 170, 178, 255), width=2)
    d.text((76, 203), "Search", fill=(168, 170, 178, 255), font=load_font(20), anchor="lm")

    for icx, cy in orb_centers:
        draw_refractive_glass(
            scr,
            (icx - 16, cy - 16, icx + 16, cy + 16),
            radius=16,
            dark=True,
            backdrop_src=scene_snapshot,
            soft_src=soft_scene,
        )
        d = ImageDraw.Draw(scr)
        d.ellipse((icx - 11, cy - 11, icx + 11, cy + 11), outline=(10, 132, 255, 255), width=2)
        d.text((icx, cy + 1), "i", fill=(10, 132, 255, 255), font=load_font(15, bold=True), anchor="mm")

    draw_tab_bar(scr, dark=True, active_idx=1, missed_badge=2, backdrop_src=scene_snapshot, soft_src=soft_scene)
    return scr, base_bg


def render_incoming_call_screen(w, h):
    base_bg = make_aurora(w, h, "aurora")
    scr = base_bg.copy()
    draw_status_bar(scr, dark_text=False)

    # Untinted refractive locked `Decline` pill (`mLockedDeclineGlass`) with `#FF453A` text (`IosCallActivity.java:1028-1047`)
    draw_refractive_glass(scr, (w - 118, 52, w - 24, 92), radius=20, incall=True, backdrop_src=base_bg)
    d = ImageDraw.Draw(scr)
    d.text((w - 71, 72), "Decline", fill=(255, 69, 58, 255), font=load_font(16, bold=True), anchor="mm")

    # Authentic iOS 26 Call Header (`IosCallActivity.java:937-979`):
    # Status/SIM line ABOVE large 36sp Caller Name, ZERO cartoon initials monogram circle when `hasPhoto == false`!
    d.text((w // 2, 148), "mobile · S1 · Jio True5G", fill=(235, 235, 245, 196), font=load_font(21, bold=False), anchor="mm")
    d.text((w // 2, 200), "Zoe Sterling", fill=(255, 255, 255, 255), font=load_font(48, bold=True), anchor="mm")

    # Remind Me & Message refractive glass circles (`mRemindGlass`, `mMsgGlass`)
    sm_r = 34
    sm_y = h - 265
    lx, rx = int(w * 0.26), int(w * 0.74)
    draw_refractive_glass(scr, (lx - sm_r, sm_y - sm_r, lx + sm_r, sm_y + sm_r), radius=sm_r, incall=True, backdrop_src=base_bg)
    draw_refractive_glass(scr, (rx - sm_r, sm_y - sm_r, rx + sm_r, sm_y + sm_r), radius=sm_r, incall=True, backdrop_src=base_bg)

    d = ImageDraw.Draw(scr)
    d.ellipse((lx - 13, sm_y - 12, lx + 13, sm_y + 14), outline=(255, 255, 255, 255), width=2)
    d.line((lx, sm_y + 1, lx, sm_y - 6), fill=(255, 255, 255, 255), width=2)
    d.line((lx, sm_y + 1, lx + 6, sm_y + 1), fill=(255, 255, 255, 255), width=2)
    d.text((lx, sm_y + sm_r + 22), "Remind Me", fill=(255, 255, 255, 255), font=load_font(16, bold=True), anchor="mm")

    d.ellipse((rx - 15, sm_y - 12, rx + 15, sm_y + 10), fill=(255, 255, 255, 255))
    d.polygon([(rx - 8, sm_y + 7), (rx - 14, sm_y + 16), (rx - 1, sm_y + 9)], fill=(255, 255, 255, 255))
    d.text((rx, sm_y + sm_r + 22), "Message", fill=(255, 255, 255, 255), font=load_font(16, bold=True), anchor="mm")

    # Iconic iOS 26 Liquid Glass `slide to answer` track + Lit-White Refractive Glass Knob (`mSliderKnobGlass`)
    tx0, ty0, tx1, ty1 = 42, h - 148, w - 42, h - 48
    draw_refractive_glass(scr, (tx0, ty0, tx1, ty1), radius=50, incall=True, backdrop_src=base_bg)
    knob_r = 43
    kcx, kcy = tx0 + 7 + knob_r, (ty0 + ty1) // 2
    track_snapshot = scr.copy()
    draw_refractive_glass(
        scr,
        (kcx - knob_r, kcy - knob_r, kcx + knob_r, kcy + knob_r),
        radius=knob_r,
        incall=True,
        tint_boost=0.24,
        tint_col=(244, 247, 255),
        tint_mix=0.78,
        backdrop_src=track_snapshot,
    )
    d = ImageDraw.Draw(scr)
    draw_phone_glyph(d, kcx, kcy, knob_r * 0.92, (52, 199, 89, 255), hangup=False)
    d.text(((tx0 + tx1) // 2 + 28, kcy), "slide to answer", fill=(255, 255, 255, 235), font=load_font(24, bold=True), anchor="mm")
    return scr


def render_active_call_screen(w, h):
    base_bg = make_aurora(w, h, "aurora")
    scr = base_bg.copy()
    draw_status_bar(scr, dark_text=False)

    # Top Swap Banner (`mSecondaryBannerGlass` via untinted `bindToPaint`)
    draw_refractive_glass(scr, (34, 52, w - 34, 96), radius=22, incall=True, backdrop_src=base_bg)
    d = ImageDraw.Draw(scr)
    d.text((w // 2, 74), "Aarav Sharma · On Hold (Tap to Swap)", fill=(255, 255, 255, 255), font=load_font(16, bold=True), anchor="mm")

    # Authentic iOS 26 Call Header (`IosCallActivity.java:937-979`):
    # Live MM:SS Timer + HD + SIM ABOVE 36sp Caller Name, ZERO cartoon initials monogram circle!
    d.text((w // 2, 162), "02:48 · HD · S1", fill=(235, 235, 245, 210), font=load_font(22, bold=True), anchor="mm")
    d.text((w // 2, 214), "Zoe Sterling", fill=(255, 255, 255, 255), font=load_font(48, bold=True), anchor="mm")

    # 2x3 Circular Liquid Glass Button Grid (`bindToPaintInCallLit` for lit toggles, `bindToPaintTinted` for End)
    dia = 98
    gap_x = 38
    gap_y = 54
    tw = 3 * dia + 2 * gap_x
    sx = (w - tw) // 2
    sy = h - 2 * dia - gap_y - 84

    items = [
        ("Speaker", True), ("Hold", False), ("Mute", True),
        ("Merge", False), ("End", False), ("Keypad", False),
    ]
    f_lbl = load_font(16, bold=True)
    grid_boxes = []

    for idx, (lbl, lit) in enumerate(items):
        r, c = divmod(idx, 3)
        x0 = sx + c * (dia + gap_x)
        y0 = sy + r * (dia + gap_y)
        cx, cy = x0 + dia / 2, y0 + dia / 2
        grid_boxes.append((x0, y0, x0 + dia, y0 + dia))

        if idx == 4:
            draw_refractive_glass(
                scr,
                (x0, y0, x0 + dia, y0 + dia),
                radius=dia * 0.5,
                incall=True,
                tint_boost=0.18,
                tint_col=(255, 59, 48),
                tint_mix=0.74,
                backdrop_src=base_bg,
            )
            d = ImageDraw.Draw(scr)
            draw_phone_glyph(d, cx, cy, dia * 0.45, (255, 255, 255, 255), hangup=True)
        elif lit:
            draw_refractive_glass(
                scr,
                (x0, y0, x0 + dia, y0 + dia),
                radius=dia * 0.5,
                incall=True,
                tint_boost=0.24,
                tint_col=(244, 247, 255),
                tint_mix=0.78,
                backdrop_src=base_bg,
            )
        else:
            draw_refractive_glass(
                scr,
                (x0, y0, x0 + dia, y0 + dia),
                radius=dia * 0.5,
                incall=True,
                backdrop_src=base_bg,
            )

        d = ImageDraw.Draw(scr)
        ic = (28, 28, 30, 255) if lit else (255, 255, 255, 255)
        if idx == 0:  # Speaker
            d.polygon([(cx - 16, cy - 6), (cx - 7, cy - 6), (cx + 2, cy - 15), (cx + 2, cy + 15), (cx - 7, cy + 6), (cx - 16, cy + 6)], fill=ic)
            d.arc((cx - 4, cy - 11, cx + 12, cy + 11), -45, 45, fill=ic, width=3)
            d.arc((cx - 8, cy - 18, cx + 19, cy + 18), -45, 45, fill=ic, width=3)
        elif idx == 1:  # Hold pause bars
            d.rounded_rectangle((cx - 10, cy - 14, cx - 3, cy + 14), radius=3, fill=ic)
            d.rounded_rectangle((cx + 3, cy - 14, cx + 10, cy + 14), radius=3, fill=ic)
        elif idx == 2:  # Mute mic.slash
            d.rounded_rectangle((cx - 6, cy - 16, cx + 6, cy + 2), radius=6, fill=ic)
            d.arc((cx - 12, cy - 10, cx + 12, cy + 10), 0, 180, fill=ic, width=3)
            d.line((cx - 16, cy - 16, cx + 16, cy + 16), fill=ic, width=3)
        elif idx == 3:  # Merge
            d.line((cx, cy - 16, cx, cy + 2), fill=ic, width=3)
            d.line((cx - 9, cy - 7, cx, cy - 16), fill=ic, width=3)
            d.line((cx + 9, cy - 7, cx, cy - 16), fill=ic, width=3)
            d.line((cx, cy + 2, cx - 12, cy + 16), fill=ic, width=3)
            d.line((cx, cy + 2, cx + 12, cy + 16), fill=ic, width=3)
        elif idx == 5:  # Exact 10-dot `3x3 + 0` keypad glyph (`IosCallActivity.java:1184`)
            draw_solid_tab_icon(scr, 3, cx, cy, dia * 0.42, ic)

        d = ImageDraw.Draw(scr)
        d.text((cx, y0 + dia + 22), lbl, fill=(255, 255, 255, 255), font=f_lbl, anchor="mm")
    return scr, grid_boxes


def channel_stats(vals):
    if not vals:
        return 0.0, 0.0
    mean = sum(vals) / len(vals)
    var = sum((v - mean) ** 2 for v in vals) / len(vals)
    return mean, math.sqrt(var)


def verify_optical_assertions(p_keypad, bg_light, p_keypad_no_neighbor, p_recents, p_active, active_grid_boxes):
    pw, ph = p_keypad.size
    _, _, dia, _, _, boxes = get_keypad_geometry(pw)
    kp_px = p_keypad.load()
    bg_px = bg_light.load()
    no_nb_px = p_keypad_no_neighbor.load()

    # 1. Assert non-zero meniscus refraction & bevel variance across ALL 12 keypad orbs
    rad = dia * 0.5
    for idx, (x0, y0, x1, y1) in enumerate(boxes):
        cx, cy = (x0 + x1) * 0.5, (y0 + y1) * 0.5
        orb_rad = (dia + 8) * 0.5 if idx == 4 else rad
        pad = -4 if idx == 4 else 0
        bevel_luma = []
        diffs = []
        for y in range(max(0, y0 + pad), min(ph, y1 - pad)):
            for x in range(max(0, x0 + pad), min(pw, x1 - pad)):
                dist = math.hypot((x + 0.5) - cx, (y + 0.5) - cy)
                if 0.72 * orb_rad <= dist <= 0.96 * orb_rad:
                    c = kp_px[x, y]
                    b = bg_px[x, y]
                    luma = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
                    bevel_luma.append(luma)
                    diffs.append(abs(c[0] - b[0]) + abs(c[1] - b[1]) + abs(c[2] - b[2]))
        _, std_luma = channel_stats(bevel_luma)
        mean_diff, _ = channel_stats(diffs)
        assert std_luma > 1.2 and mean_diff > 3.5, (
            f"Keypad orb {idx} failed meniscus refraction gate: std_luma={std_luma:.2f}, mean_diff={mean_diff:.2f}"
        )

    # 2. Assert neighbor-key facing-rim brightening around pressed key '5' (idx=4) on orthogonal neighbors (1, 3, 5, 7)
    pressed_cx, pressed_cy = (boxes[4][0] + boxes[4][2]) * 0.5, (boxes[4][1] + boxes[4][3]) * 0.5
    for nb_idx in (1, 3, 5, 7):
        x0, y0, x1, y1 = boxes[nb_idx]
        cx, cy = (x0 + x1) * 0.5, (y0 + y1) * 0.5
        to_px = pressed_cx - cx
        to_py = pressed_cy - cy
        tlen = math.hypot(to_px, to_py)
        to_px /= tlen
        to_py /= tlen
        facing_deltas = []
        away_deltas = []
        for y in range(y0, y1):
            for x in range(x0, x1):
                dx = (x + 0.5) - cx
                dy = (y + 0.5) - cy
                dist = math.hypot(dx, dy)
                if 0.80 * rad <= dist <= 0.98 * rad:
                    dot = (dx / dist) * to_px + (dy / dist) * to_py
                    c_with = kp_px[x, y]
                    c_without = no_nb_px[x, y]
                    dl = (0.299 * (c_with[0] - c_without[0])
                          + 0.587 * (c_with[1] - c_without[1])
                          + 0.114 * (c_with[2] - c_without[2]))
                    if dot > 0.65:
                        facing_deltas.append(dl)
                    elif dot < -0.65:
                        away_deltas.append(dl)
        mean_facing, _ = channel_stats(facing_deltas)
        mean_away, _ = channel_stats(away_deltas)
        assert mean_facing > 4.0 and mean_facing > mean_away + 3.5, (
            f"Neighbor key {nb_idx} failed facing-rim caustic gate: facing=+{mean_facing:.2f}, away=+{mean_away:.2f}"
        )

    # 3. Assert multi-channel backdrop variance through `IosBottomTabCapsuleBar` & active droplet on `Recents`
    mx, y0, x1, y1 = get_tab_bar_bounds(pw, ph)
    rec_px = p_recents.load()
    rs, gs, bs = [], [], []
    for y in range(y0 + 8, y1 - 8, 2):
        for x in range(mx + 14, x1 - 14, 2):
            c = rec_px[x, y]
            rs.append(c[0])
            gs.append(c[1])
            bs.append(c[2])
    _, std_r = channel_stats(rs)
    _, std_g = channel_stats(gs)
    _, std_b = channel_stats(bs)
    assert std_r > 4.0 and std_g > 4.0 and std_b > 4.0, (
        f"Recents bottom tab capsule failed multi-channel refraction gate: std=({std_r:.2f}, {std_g:.2f}, {std_b:.2f})"
    )

    # 4. Assert non-opaque refraction inside Active Call lit Speaker orb (`idx=0`) and crimson End orb (`idx=4`)
    act_px = p_active.load()
    for check_idx, name in ((0, "Lit Speaker"), (4, "Crimson End")):
        gx0, gy0, gx1, gy1 = active_grid_boxes[check_idx]
        gcx, gcy = (gx0 + gx1) * 0.5, (gy0 + gy1) * 0.5
        grad_r = (gx1 - gx0) * 0.5
        ring_vals = []
        for y in range(gy0, gy1):
            for x in range(gx0, gx1):
                dist = math.hypot((x + 0.5) - gcx, (y + 0.5) - gcy)
                if 0.68 * grad_r <= dist <= 0.94 * grad_r:
                    c = act_px[x, y]
                    ring_vals.append(0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2])
        _, ring_std = channel_stats(ring_vals)
        assert ring_std > 2.0, f"Active Call {name} lens appears flat/opaque (ring_std={ring_std:.2f})"

    print(
        f"[OPTICAL-QA PASS] 12/12 keypad meniscus orbs + 13-orb neighbor caustics verified | "
        f"bottom capsule RGB std=({std_r:.1f}, {std_g:.1f}, {std_b:.1f}) | "
        f"in-call lit & crimson lenses refractive!"
    )


def frame_phone_card(canvas, panel, x, y, caption):
    pw, ph = panel.size
    mask = Image.new("L", (pw, ph), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, pw - 1, ph - 1), radius=42, fill=255)

    sh = Image.new("RGBA", (pw + 28, ph + 28), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((14, 16, pw + 13, ph + 15), radius=44, fill=(0, 6, 18, 125))
    sh = sh.filter(ImageFilter.GaussianBlur(radius=9))
    canvas.alpha_composite(sh, (x - 14, y - 10))

    canvas.paste(panel, (x, y), mask)
    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle((x, y, x + pw - 1, y + ph - 1), radius=42, outline=(255, 255, 255, 85), width=2)
    d.text((x + pw // 2, y + ph + 24), caption, fill=(210, 222, 245, 255), font=load_font(20, bold=True), anchor="mm")


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    out_path = (
        sys.argv[1]
        if len(sys.argv) > 1
        else os.environ.get(
            "DIALER_SHOWCASE_OUT",
            "/mnt/c/Users/paink/.gemini/antigravity/brain/17987213-b62f-4065-a161-6816f03e8517/ios_dialer_v215_showcase.png",
        )
    )
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    W, H = 1080, 2400
    canvas = Image.new("RGBA", (W, H), (10, 14, 24, 255))
    cd = ImageDraw.Draw(canvas)
    for y in range(H):
        t = y / (H - 1)
        r = int(12 + 10 * (1 - t))
        g = int(18 + 18 * (1 - t))
        b = int(34 + 28 * (1 - t))
        cd.line((0, y, W, y), fill=(r, g, b, 255))

    bg_ic = os.path.join(script_dir, "res/drawable-xxxhdpi/ios26_phone_bg.png")
    fg_ic = os.path.join(script_dir, "res/drawable-xxxhdpi/ios26_phone_fg.png")
    if os.path.exists(bg_ic) and os.path.exists(fg_ic):
        ib = Image.open(bg_ic).convert("RGBA").resize((96, 96), Image.Resampling.LANCZOS)
        ifg = Image.open(fg_ic).convert("RGBA").resize((96, 96), Image.Resampling.LANCZOS)
        ib.alpha_composite(ifg)
        imask = Image.new("L", (96, 96), 0)
        ImageDraw.Draw(imask).rounded_rectangle((12, 12, 84, 84), radius=18, fill=255)
        canvas.paste(ib, (38, 30), imask)

    cd.text((138, 56), "iOS 26 Phone & InCallService", fill=(255, 255, 255, 255), font=load_font(34, bold=True), anchor="lm")
    cd.text(
        (138, 96),
        "com.realme.samurai.dialer · v1.7.6-ios26 (OTA v2.1.5-b1) · 90Hz AGSL Liquid Glass",
        fill=(148, 182, 235, 255),
        font=load_font(20, bold=False),
        anchor="lm",
    )

    pw, ph = 486, 1044
    p_keypad, bg_light = render_keypad_screen(pw, ph, pressed_idx=4, enable_neighbor_caustics=True)
    p_keypad_no_nb, _ = render_keypad_screen(pw, ph, pressed_idx=4, enable_neighbor_caustics=False)
    p_recents, _ = render_recents_screen(pw, ph)
    p_incoming = render_incoming_call_screen(pw, ph)
    p_active, active_grid_boxes = render_active_call_screen(pw, ph)

    verify_optical_assertions(p_keypad, bg_light, p_keypad_no_nb, p_recents, p_active, active_grid_boxes)

    frame_phone_card(canvas, p_keypad, 34, 146, "1 · Refractive Keypad + Neighbor Caustics")
    frame_phone_card(canvas, p_recents, 560, 146, "2 · Live-Scrolled Recents + Droplet Bar")
    frame_phone_card(canvas, p_incoming, 34, 1264, "3 · Locked Incoming Call (Lit Glass Knob)")
    frame_phone_card(canvas, p_active, 560, 1264, "4 · Active Call Lit & Crimson Glass Grid")

    canvas.convert("RGB").save(out_path, "PNG", optimize=True)
    print("Saved showcase:", out_path, os.path.getsize(out_path), "bytes")


if __name__ == "__main__":
    main()
