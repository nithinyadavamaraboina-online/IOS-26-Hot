package com.realme.samurai.styler;

import android.graphics.Outline;
import android.graphics.Path;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * Exact UIKit `kCACornerCurveContinuous` G2 superellipse (n ~ 4.6) Path & Outline builder.
 */
public final class StylerSquircle {
    private static final float P = 1.52866483f;
    private static final float A = 1.08849323f;
    private static final float B = 0.86840689f;
    private static final float C = 0.63149399f;
    private static final float D = 0.07491139f;
    private static final float E = 0.37282392f;
    private static final float F = 0.16906013f;

    private StylerSquircle() {}

    public static float concentricRadius(float outerR, float pad, float minR) {
        return Math.max(minR, outerR - pad);
    }

    public static Path buildPath(float l, float t, float r, float b, float cornerRadius, Path out) {
        Path p = (out != null) ? out : new Path();
        p.reset();
        float w = r - l;
        float h = b - t;
        if (w <= 0f || h <= 0f) {
            return p;
        }
        float halfMin = 0.5f * Math.min(w, h);
        float cr = StylerPrefsBridge.clampF(cornerRadius, 0f, halfMin);
        if (cr <= 0.5f) {
            p.addRect(l, t, r, b, Path.Direction.CW);
            return p;
        }
        if (cr >= halfMin * 0.96f) {
            p.addRoundRect(l, t, r, b, halfMin, halfMin, Path.Direction.CW);
            return p;
        }

        float px = Math.min(w * 0.5f, P * cr);
        float py = Math.min(h * 0.5f, P * cr);
        float sx = px / (P * cr);
        float sy = py / (P * cr);

        float ax = A * cr * sx;
        float bx = B * cr * sx;
        float cx = C * cr * sx;
        float dx = D * cr * sy;
        float ex = E * cr * sx;
        float fx = F * cr * sy;
        float gx = F * cr * sx;
        float hx = E * cr * sy;
        float kx = D * cr * sx;
        float mx = C * cr * sy;
        float by = B * cr * sy;
        float ay = A * cr * sy;

        p.moveTo(l + px, t);
        p.lineTo(r - px, t);

        p.cubicTo(r - ax, t, r - bx, t, r - cx, t + dx);
        p.cubicTo(r - ex, t + fx, r - gx, t + hx, r - kx, t + mx);
        p.cubicTo(r, t + by, r, t + ay, r, t + py);

        p.lineTo(r, b - py);

        p.cubicTo(r, b - ay, r, b - by, r - kx, b - mx);
        p.cubicTo(r - gx, b - hx, r - ex, b - fx, r - cx, b - dx);
        p.cubicTo(r - bx, b, r - ax, b, r - px, b);

        p.lineTo(l + px, b);

        p.cubicTo(l + ax, b, l + bx, b, l + cx, b - dx);
        p.cubicTo(l + ex, b - fx, l + gx, b - hx, l + kx, b - mx);
        p.cubicTo(l, b - by, l, b - ay, l, b - py);

        p.lineTo(l, t + py);

        p.cubicTo(l, t + ay, l, t + by, l + kx, t + mx);
        p.cubicTo(l + gx, t + hx, l + ex, t + fx, l + cx, t + dx);
        p.cubicTo(l + bx, t, l + ax, t, l + px, t);

        p.close();
        return p;
    }

    public static ViewOutlineProvider outlineProvider(final float radiusPx) {
        return new ViewOutlineProvider() {
            private final Path mPath = new Path();

            @Override
            public void getOutline(View view, Outline outline) {
                int w = view.getWidth();
                int h = view.getHeight();
                if (w <= 0 || h <= 0) return;
                buildPath(0f, 0f, w, h, radiusPx, mPath);
                outline.setPath(mPath);
            }
        };
    }
}
