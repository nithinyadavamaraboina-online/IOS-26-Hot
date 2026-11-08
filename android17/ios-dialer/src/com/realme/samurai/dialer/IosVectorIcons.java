package com.realme.samurai.dialer;

import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Clean-room pixel-crisp Apple SF-Symbols vector glyph renderer for iOS 26 Tabs, Filter Orb,
 * Keypad, Contact Sheet, and In-Call controls.
 */
public final class IosVectorIcons {

    public static final int TAB_FAVORITES = 0;
    public static final int TAB_RECENTS = 1;
    public static final int TAB_CONTACTS = 2;
    public static final int TAB_KEYPAD = 3;
    public static final int TAB_VOICEMAIL = 4;
    public static final int TAB_UNIFIED_CALLS = 10;

    private static final Path TMP_PATH = new Path();
    private static final Path CUT_PATH = new Path();
    private static final Path AUX_PATH = new Path();
    private static final RectF TMP_RECT = new RectF();

    private IosVectorIcons() {}

    public static synchronized void drawTabIcon(
            Canvas c, int tab, float cx, float cy, float size, int color, boolean active, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        float r = size * 0.5f;
        float sw = Math.max(1.9f, size * 0.098f);

        switch (tab) {
            case TAB_UNIFIED_CALLS: {
                // SF Symbol `phone.fill`
                drawPhoneHandset(c, cx, cy, size * 0.94f, color, false, p);
                break;
            }
            case TAB_FAVORITES: {
                // SF Symbol `star.fill` — smooth rounded-tip solid 5-point star
                TMP_PATH.reset();
                float outer = r * 0.98f;
                float inner = outer * 0.44f;
                for (int i = 0; i < 10; i++) {
                    double ang = -Math.PI / 2.0 + i * (Math.PI / 5.0);
                    float rad = (i % 2 == 0) ? outer : inner;
                    float px = cx + (float) Math.cos(ang) * rad;
                    float py = cy + (float) Math.sin(ang) * rad + size * 0.02f;
                    if (i == 0) TMP_PATH.moveTo(px, py);
                    else TMP_PATH.lineTo(px, py);
                }
                TMP_PATH.close();
                p.setStyle(Paint.Style.FILL);
                p.setPathEffect(new CornerPathEffect(size * 0.055f));
                c.drawPath(TMP_PATH, p);
                p.setPathEffect(null);
                break;
            }
            case TAB_RECENTS: {
                // SF Symbol `clock.fill` — solid disc with negative-space 12:15 clock hands
                float cr = r * 0.90f;
                float hw = Math.max(1.05f, size * 0.052f);
                TMP_PATH.reset();
                TMP_PATH.addCircle(cx, cy, cr, Path.Direction.CW);

                CUT_PATH.reset();
                TMP_RECT.set(cx - hw, cy - cr * 0.60f, cx + hw, cy + hw);
                CUT_PATH.addRoundRect(TMP_RECT, hw, hw, Path.Direction.CW);

                AUX_PATH.reset();
                TMP_RECT.set(cx - hw, cy - hw, cx + cr * 0.52f, cy + hw);
                AUX_PATH.addRoundRect(TMP_RECT, hw, hw, Path.Direction.CW);

                CUT_PATH.op(AUX_PATH, Path.Op.UNION);
                TMP_PATH.op(CUT_PATH, Path.Op.DIFFERENCE);

                p.setStyle(Paint.Style.FILL);
                c.drawPath(TMP_PATH, p);
                break;
            }
            case TAB_CONTACTS: {
                // SF Symbol `person.crop.circle.fill` — solid circle with head + crescent shoulders cutout
                float cr = r * 0.92f;
                TMP_PATH.reset();
                TMP_PATH.addCircle(cx, cy, cr, Path.Direction.CW);

                CUT_PATH.reset();
                CUT_PATH.addCircle(cx, cy - cr * 0.20f, cr * 0.30f, Path.Direction.CW);

                AUX_PATH.reset();
                TMP_RECT.set(cx - cr * 0.64f, cy + cr * 0.20f, cx + cr * 0.64f, cy + cr * 1.16f);
                AUX_PATH.addOval(TMP_RECT, Path.Direction.CW);

                Path innerClip = new Path();
                innerClip.addCircle(cx, cy, cr * 0.78f, Path.Direction.CW);
                AUX_PATH.op(innerClip, Path.Op.INTERSECT);

                CUT_PATH.op(AUX_PATH, Path.Op.UNION);
                TMP_PATH.op(CUT_PATH, Path.Op.DIFFERENCE);

                p.setStyle(Paint.Style.FILL);
                c.drawPath(TMP_PATH, p);
                break;
            }
            case TAB_KEYPAD: {
                // Authentic iPhone Phone.app 10-dot Keypad glyph (3x3 + 1 bottom center `0` dot)
                p.setStyle(Paint.Style.FILL);
                float dotR = size * 0.088f;
                float stepX = size * 0.285f;
                float stepY = size * 0.252f;
                for (int row = 0; row < 3; row++) {
                    float ry = cy + (row - 1.42f) * stepY;
                    for (int col = -1; col <= 1; col++) {
                        c.drawCircle(cx + col * stepX, ry, dotR, p);
                    }
                }
                c.drawCircle(cx, cy + (3f - 1.42f) * stepY, dotR, p);
                break;
            }
            case TAB_VOICEMAIL: {
                drawVoicemailTape(c, cx, cy, size, color, sw, p);
                break;
            }
            default:
                break;
        }
    }

    /**
     * Authentic iOS 26.1 3-bar diminishing horizontal Filter glyph (`line.3.horizontal.decrease`).
     */
    public static synchronized void drawFilterLines(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.9f, size * 0.105f));
        p.setStrokeCap(Paint.Cap.ROUND);
        float gapY = size * 0.22f;
        float w1 = size * 0.42f;
        float w2 = size * 0.27f;
        float w3 = size * 0.13f;
        c.drawLine(cx - w1, cy - gapY, cx + w1, cy - gapY, p);
        c.drawLine(cx - w2, cy, cx + w2, cy, p);
        c.drawLine(cx - w3, cy + gapY, cx + w3, cy + gapY, p);
    }

    /**
     * Authentic iOS 26.1 `person.badge.plus` glyph for Keypad top-right Add-Contact Liquid Glass orb.
     */
    public static synchronized void drawPersonBadgePlus(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        float sw = Math.max(1.7f, size * 0.095f);
        float pCx = cx - size * 0.08f;
        // Head
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawCircle(pCx, cy - size * 0.17f, size * 0.16f, p);
        // Shoulders arc
        TMP_RECT.set(pCx - size * 0.30f, cy + size * 0.03f, pCx + size * 0.30f, cy + size * 0.56f);
        c.drawArc(TMP_RECT, 195f, 150f, false, p);
        // Plus badge on right
        float bx = cx + size * 0.28f;
        float by = cy - size * 0.08f;
        float arm = size * 0.13f;
        c.drawLine(bx - arm, by, bx + arm, by, p);
        c.drawLine(bx, by - arm, bx, by + arm, p);
    }

    public static synchronized void drawCheckmark(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2.0f, size * 0.125f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        TMP_PATH.reset();
        TMP_PATH.moveTo(cx - size * 0.32f, cy + size * 0.02f);
        TMP_PATH.lineTo(cx - size * 0.08f, cy + size * 0.28f);
        TMP_PATH.lineTo(cx + size * 0.34f, cy - size * 0.26f);
        c.drawPath(TMP_PATH, p);
    }

    public static synchronized void drawCloseCross(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2.0f, size * 0.115f));
        p.setStrokeCap(Paint.Cap.ROUND);
        float d = size * 0.27f;
        c.drawLine(cx - d, cy - d, cx + d, cy + d, p);
        c.drawLine(cx + d, cy - d, cx - d, cy + d, p);
    }

    /**
     * Mini iOS 26 Phone wireframe icon for Classic vs Unified Popover selector (`04_phone_classic_vs_unified_tidbits.jpg`).
     */
    public static synchronized void drawLayoutWireframe(
            Canvas c, float cx, float cy, float size, int color, boolean unified, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        float pw = size * 0.64f;
        float ph = size * 0.96f;
        float cr = pw * 0.22f;
        TMP_RECT.set(cx - pw * 0.5f, cy - ph * 0.5f, cx + pw * 0.5f, cy + ph * 0.5f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.6f, size * 0.055f));
        c.drawRoundRect(TMP_RECT, cr, cr, p);

        p.setStyle(Paint.Style.FILL);
        if (unified) {
            // Pinned Favorite tiles at top + 3-tab capsule + detached search dot at bottom
            float tileS = pw * 0.22f;
            float ty = TMP_RECT.top + ph * 0.18f;
            TMP_RECT.set(cx - pw * 0.34f, ty, cx - pw * 0.34f + tileS, ty + tileS);
            c.drawRoundRect(TMP_RECT, tileS * 0.28f, tileS * 0.28f, p);
            TMP_RECT.set(cx - tileS * 0.5f, ty, cx + tileS * 0.5f, ty + tileS);
            c.drawRoundRect(TMP_RECT, tileS * 0.28f, tileS * 0.28f, p);
            TMP_RECT.set(cx + pw * 0.34f - tileS, ty, cx + pw * 0.34f, ty + tileS);
            c.drawRoundRect(TMP_RECT, tileS * 0.28f, tileS * 0.28f, p);

            float by = cy + ph * 0.34f;
            float bh = ph * 0.10f;
            TMP_RECT.set(cx - pw * 0.36f, by - bh * 0.5f, cx + pw * 0.14f, by + bh * 0.5f);
            c.drawRoundRect(TMP_RECT, bh * 0.5f, bh * 0.5f, p);
            c.drawCircle(cx + pw * 0.28f, by, bh * 0.5f, p);
        } else {
            // Classic list rows + full-width bottom bar
            float lx = cx - pw * 0.30f;
            float rx = cx + pw * 0.30f;
            p.setStrokeWidth(Math.max(1.5f, size * 0.05f));
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 3; i++) {
                float ry = cy - ph * 0.22f + i * ph * 0.15f;
                c.drawLine(lx, ry, rx, ry, p);
            }
            p.setStyle(Paint.Style.FILL);
            float by = cy + ph * 0.34f;
            float bh = ph * 0.10f;
            TMP_RECT.set(cx - pw * 0.36f, by - bh * 0.5f, cx + pw * 0.36f, by + bh * 0.5f);
            c.drawRoundRect(TMP_RECT, bh * 0.5f, bh * 0.5f, p);
        }
    }

    public static synchronized void drawVoicemailTape(
            Canvas c, float cx, float cy, float size, int color, float strokeW, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(strokeW);
        p.setStrokeCap(Paint.Cap.ROUND);
        float loopR = size * 0.21f;
        float dx = size * 0.27f;
        c.drawCircle(cx - dx, cy, loopR, p);
        c.drawCircle(cx + dx, cy, loopR, p);
        c.drawLine(cx - dx, cy + loopR, cx + dx, cy + loopR, p);
    }

    public static synchronized void drawPhoneHandset(
            Canvas c, float cx, float cy, float size, int color, boolean hangupHorizontal, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);

        c.save();
        c.translate(cx, cy);
        if (hangupHorizontal) {
            c.rotate(135f);
        }
        float s = size / 48f;
        c.scale(s, s);
        c.translate(-54f, -54f);
        TMP_PATH.reset();
        TMP_PATH.moveTo(36.5f, 31.2f);
        TMP_PATH.cubicTo(38.8f, 29.6f, 41.9f, 30.1f, 43.7f, 32.4f);
        TMP_PATH.lineTo(48.3f, 38.4f);
        TMP_PATH.cubicTo(49.8f, 40.3f, 49.6f, 43.0f, 47.9f, 44.7f);
        TMP_PATH.lineTo(44.3f, 48.3f);
        TMP_PATH.cubicTo(47.3f, 54.1f, 52.1f, 58.9f, 57.9f, 61.9f);
        TMP_PATH.lineTo(61.5f, 58.3f);
        TMP_PATH.cubicTo(63.2f, 56.6f, 65.9f, 56.4f, 67.8f, 57.9f);
        TMP_PATH.lineTo(73.8f, 62.5f);
        TMP_PATH.cubicTo(76.1f, 64.3f, 76.6f, 67.4f, 75.0f, 69.7f);
        TMP_PATH.lineTo(71.6f, 74.5f);
        TMP_PATH.cubicTo(69.3f, 77.7f, 65.1f, 79.1f, 61.3f, 77.9f);
        TMP_PATH.cubicTo(45.8f, 72.9f, 33.3f, 60.4f, 28.3f, 44.9f);
        TMP_PATH.cubicTo(27.1f, 41.1f, 28.5f, 36.9f, 31.7f, 34.6f);
        TMP_PATH.close();
        c.drawPath(TMP_PATH, p);
        c.restore();
    }

    public static synchronized void drawPlus(
            Canvas c, float cx, float cy, float size, int color, float strokeW, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(strokeW);
        p.setStrokeCap(Paint.Cap.ROUND);
        float arm = size * 0.42f;
        c.drawLine(cx - arm, cy, cx + arm, cy, p);
        c.drawLine(cx, cy - arm, cx, cy + arm, p);
    }

    public static synchronized void drawBackspaceTag(
            Canvas c, float cx, float cy, float size, int fillColor, int crossColor, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        float w = size * 1.12f;
        float h = size * 0.78f;
        float left = cx - w * 0.50f;
        float right = cx + w * 0.50f;
        float top = cy - h * 0.5f;
        float bot = cy + h * 0.5f;
        float tip = left + w * 0.28f;
        float cr = h * 0.22f;

        TMP_PATH.reset();
        TMP_PATH.moveTo(tip, top);
        TMP_PATH.lineTo(right - cr, top);
        TMP_PATH.quadTo(right, top, right, top + cr);
        TMP_PATH.lineTo(right, bot - cr);
        TMP_PATH.quadTo(right, bot, right - cr, bot);
        TMP_PATH.lineTo(tip, bot);
        TMP_PATH.lineTo(left, cy);
        TMP_PATH.close();

        if ((fillColor >>> 24) > 0) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(fillColor);
            p.setPathEffect(new CornerPathEffect(h * 0.15f));
            c.drawPath(TMP_PATH, p);
            p.setPathEffect(null);
        } else {
            p.setStyle(Paint.Style.STROKE);
            p.setColor(crossColor);
            p.setStrokeWidth(Math.max(1.8f, size * 0.095f));
            p.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(TMP_PATH, p);
        }

        p.setStyle(Paint.Style.STROKE);
        p.setColor(crossColor);
        p.setStrokeWidth(Math.max(1.8f, size * 0.095f));
        p.setStrokeCap(Paint.Cap.ROUND);
        float xCx = cx + w * 0.09f;
        float d = h * 0.21f;
        c.drawLine(xCx - d, cy - d, xCx + d, cy + d, p);
        c.drawLine(xCx + d, cy - d, xCx - d, cy + d, p);
    }

    public static synchronized void drawInfoCircle(
            Canvas c, float cx, float cy, float radius, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        float sw = Math.max(1.6f, radius * 0.15f);
        p.setStrokeWidth(sw);
        c.drawCircle(cx, cy, radius, p);

        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy - radius * 0.36f, sw * 0.72f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(sw * 1.15f);
        c.drawLine(cx, cy - radius * 0.06f, cx, cy + radius * 0.44f, p);
    }

    public static synchronized void drawOutgoingArrow(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, size * 0.14f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float d = size * 0.36f;
        c.drawLine(cx - d, cy + d, cx + d, cy - d, p);
        c.drawLine(cx - d * 0.1f, cy - d, cx + d, cy - d, p);
        c.drawLine(cx + d, cy - d, cx + d, cy + d * 0.1f, p);
    }

    public static synchronized void drawSearchMagnifier(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.8f, size * 0.125f));
        p.setStrokeCap(Paint.Cap.ROUND);
        float r = size * 0.30f;
        float ox = cx - size * 0.06f;
        float oy = cy - size * 0.06f;
        c.drawCircle(ox, oy, r, p);
        c.drawLine(ox + r * 0.72f, oy + r * 0.72f, cx + size * 0.38f, cy + size * 0.38f, p);
    }

    public static synchronized void drawSpeakerWave(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        float s = size * 0.5f;
        TMP_PATH.reset();
        TMP_PATH.moveTo(cx - s * 0.68f, cy - s * 0.24f);
        TMP_PATH.lineTo(cx - s * 0.34f, cy - s * 0.24f);
        TMP_PATH.lineTo(cx + s * 0.04f, cy - s * 0.58f);
        TMP_PATH.lineTo(cx + s * 0.04f, cy + s * 0.58f);
        TMP_PATH.lineTo(cx - s * 0.34f, cy + s * 0.24f);
        TMP_PATH.lineTo(cx - s * 0.68f, cy + s * 0.24f);
        TMP_PATH.close();
        c.drawPath(TMP_PATH, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.8f, size * 0.085f));
        p.setStrokeCap(Paint.Cap.ROUND);
        TMP_RECT.set(cx - s * 0.18f, cy - s * 0.38f, cx + s * 0.42f, cy + s * 0.38f);
        c.drawArc(TMP_RECT, -42f, 84f, false, p);
        TMP_RECT.set(cx - s * 0.34f, cy - s * 0.68f, cx + s * 0.72f, cy + s * 0.68f);
        c.drawArc(TMP_RECT, -45f, 90f, false, p);
    }

    public static synchronized void drawBluetoothGlyph(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.8f, size * 0.088f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float h = size * 0.44f;
        float w = size * 0.26f;
        TMP_PATH.reset();
        TMP_PATH.moveTo(cx - w * 0.85f, cy - h * 0.48f);
        TMP_PATH.lineTo(cx + w, cy + h * 0.48f);
        TMP_PATH.lineTo(cx, cy + h);
        TMP_PATH.lineTo(cx, cy - h);
        TMP_PATH.lineTo(cx + w, cy - h * 0.48f);
        TMP_PATH.lineTo(cx - w * 0.85f, cy + h * 0.48f);
        c.drawPath(TMP_PATH, p);
    }

    public static synchronized void drawMicSlash(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        float sw = Math.max(1.8f, size * 0.085f);
        float mw = size * 0.24f;
        float mh = size * 0.48f;
        TMP_RECT.set(cx - mw * 0.5f, cy - mh * 0.75f, cx + mw * 0.5f, cy + mh * 0.25f);
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(TMP_RECT, mw * 0.5f, mw * 0.5f, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        float arcR = size * 0.24f;
        TMP_RECT.set(cx - arcR, cy - mh * 0.45f, cx + arcR, cy + mh * 0.52f);
        c.drawArc(TMP_RECT, 0f, 180f, false, p);
        c.drawLine(cx, cy + mh * 0.52f, cx, cy + mh * 0.82f, p);
        c.drawLine(cx - arcR * 0.6f, cy + mh * 0.82f, cx + arcR * 0.6f, cy + mh * 0.82f, p);
        float d = size * 0.38f;
        c.drawLine(cx - d, cy - d, cx + d, cy + d, p);
    }

    public static synchronized void drawPauseHold(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        float bw = size * 0.13f;
        float bh = size * 0.56f;
        float gap = size * 0.12f;
        TMP_RECT.set(cx - gap - bw, cy - bh * 0.5f, cx - gap, cy + bh * 0.5f);
        c.drawRoundRect(TMP_RECT, bw * 0.45f, bw * 0.45f, p);
        TMP_RECT.set(cx + gap, cy - bh * 0.5f, cx + gap + bw, cy + bh * 0.5f);
        c.drawRoundRect(TMP_RECT, bw * 0.45f, bw * 0.45f, p);
    }

    public static synchronized void drawSwapMerge(
            Canvas c, float cx, float cy, float size, int color, boolean merge, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.8f, size * 0.088f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float s = size * 0.36f;
        if (merge) {
            c.drawLine(cx, cy - s, cx, cy + s * 0.1f, p);
            c.drawLine(cx - s * 0.55f, cy - s * 0.45f, cx, cy - s, p);
            c.drawLine(cx + s * 0.55f, cy - s * 0.45f, cx, cy - s, p);
            c.drawLine(cx, cy + s * 0.1f, cx - s * 0.7f, cy + s, p);
            c.drawLine(cx, cy + s * 0.1f, cx + s * 0.7f, cy + s, p);
        } else {
            c.drawLine(cx - s, cy - s * 0.38f, cx + s, cy - s * 0.38f, p);
            c.drawLine(cx + s * 0.45f, cy - s * 0.85f, cx + s, cy - s * 0.38f, p);
            c.drawLine(cx + s * 0.45f, cy + s * 0.08f, cx + s, cy - s * 0.38f, p);

            c.drawLine(cx + s, cy + s * 0.38f, cx - s, cy + s * 0.38f, p);
            c.drawLine(cx - s * 0.45f, cy - s * 0.08f, cx - s, cy + s * 0.38f, p);
            c.drawLine(cx - s * 0.45f, cy + s * 0.85f, cx - s, cy + s * 0.38f, p);
        }
    }

    public static synchronized void drawAlarmClock(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        float sw = Math.max(1.7f, size * 0.09f);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        float r = size * 0.34f;
        c.drawCircle(cx, cy + size * 0.04f, r, p);
        c.drawLine(cx, cy + size * 0.04f, cx, cy - r * 0.48f, p);
        c.drawLine(cx, cy + size * 0.04f, cx + r * 0.44f, cy + size * 0.04f, p);
        c.drawLine(cx - r * 0.92f, cy - r * 0.62f, cx - r * 0.44f, cy - r * 1.02f, p);
        c.drawLine(cx + r * 0.92f, cy - r * 0.62f, cx + r * 0.44f, cy - r * 1.02f, p);
    }

    public static synchronized void drawMessageBubble(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        float rx = size * 0.42f;
        float ry = size * 0.33f;
        TMP_RECT.set(cx - rx, cy - ry - size * 0.04f, cx + rx, cy + ry - size * 0.04f);
        c.drawOval(TMP_RECT, p);
        TMP_PATH.reset();
        TMP_PATH.moveTo(cx - rx * 0.55f, cy + ry * 0.55f);
        TMP_PATH.lineTo(cx - rx * 0.82f, cy + ry * 1.12f);
        TMP_PATH.lineTo(cx - rx * 0.12f, cy + ry * 0.78f);
        TMP_PATH.close();
        c.drawPath(TMP_PATH, p);
    }

    public static synchronized void drawVideoCamera(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        float bw = size * 0.56f;
        float bh = size * 0.42f;
        TMP_RECT.set(cx - size * 0.42f, cy - bh * 0.5f, cx - size * 0.42f + bw, cy + bh * 0.5f);
        c.drawRoundRect(TMP_RECT, bh * 0.25f, bh * 0.25f, p);
        TMP_PATH.reset();
        TMP_PATH.moveTo(cx + size * 0.16f, cy - bh * 0.18f);
        TMP_PATH.lineTo(cx + size * 0.42f, cy - bh * 0.44f);
        TMP_PATH.lineTo(cx + size * 0.42f, cy + bh * 0.44f);
        TMP_PATH.lineTo(cx + size * 0.16f, cy + bh * 0.18f);
        TMP_PATH.close();
        c.drawPath(TMP_PATH, p);
    }

    public static synchronized void drawEnvelope(
            Canvas c, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        float sw = Math.max(1.7f, size * 0.088f);
        p.setStrokeWidth(sw);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        float w = size * 0.78f;
        float h = size * 0.56f;
        TMP_RECT.set(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f);
        c.drawRoundRect(TMP_RECT, h * 0.16f, h * 0.16f, p);
        TMP_PATH.reset();
        TMP_PATH.moveTo(TMP_RECT.left + sw, TMP_RECT.top + sw);
        TMP_PATH.lineTo(cx, cy + h * 0.08f);
        TMP_PATH.lineTo(TMP_RECT.right - sw, TMP_RECT.top + sw);
        c.drawPath(TMP_PATH, p);
    }
}
