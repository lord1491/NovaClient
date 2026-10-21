package nova.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import nova.modules.ClientModules;

/** 2D drawing helpers: rounded rectangles, lines, colour maths. */
public final class Gfx {
    private Gfx() {}

    /** Multiplier for every glow (set by the GUI from Layout > Glow while it draws). */
    public static float glowScale = 1f;

    public static int a(int argb, float alpha) {
        int al = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        if (al < 8) al = 8;
        return (al << 24) | (argb & 0x00FFFFFF);
    }

    public static int mix(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (((c1 >>> 24) & 255) * (1 - t) + ((c2 >>> 24) & 255) * t);
        int r = (int) (((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t);
        int g = (int) (((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t);
        int b = (int) ((c1 & 255) * (1 - t) + (c2 & 255) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Half-width of a rounded corner at vertical distance dy from the corner centre (shape: circle, squircle or cut corner). */
    private static double spanDy(double dy, int r) {
        switch (ClientModules.Layout.shapeIndex()) {
            case 1: {
                double q = Math.min(1.0, dy / r), q2 = q * q;
                return r * Math.pow(Math.max(0.0, 1.0 - q2 * q2), 0.25);
            }
            case 2: return Math.max(0.0, r - dy);
            default: return Math.sqrt(Math.max(0.0, (double) r * r - dy * dy));
        }
    }

    public static float ease(float t) { t = Math.max(0f, Math.min(1f, t)); return t * t * (3 - 2 * t); }

    public static void rrect(DrawContext c, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        HudLayout.track(x, y, w, h);
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) { c.fill(x, y, x + w, y + h, color); return; }
        c.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            double span = spanDy(dy, r);
            int inset = (int) Math.floor(r - span);
            c.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            c.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
            double frac = (r - span) - inset;
            if (inset > 0 || frac > 0.15) {
                int edge = a(color, (float) (1.0 - frac) * 0.55f);
                c.fill(x + inset - 1, y + i, x + inset, y + i + 1, edge);
                c.fill(x + w - inset, y + i, x + w - inset + 1, y + i + 1, edge);
                c.fill(x + inset - 1, y + h - 1 - i, x + inset, y + h - i, edge);
                c.fill(x + w - inset, y + h - 1 - i, x + w - inset + 1, y + h - i, edge);
            }
        }
    }

    /** Rounded rectangle filled with a vertical gradient (top colour to bottom colour). */
    public static void rrectV(DrawContext c, int x, int y, int w, int h, int r, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        HudLayout.track(x, y, w, h);
        r = Math.min(r, Math.min(w, h) / 2);
        for (int i = 0; i < h; i++) {
            int inset = 0;
            if (i < r) { double dy = r - i - 0.5; inset = (int) Math.floor(r - spanDy(dy, r)); }
            else if (i >= h - r) { double dy = i - (h - r) + 0.5; inset = (int) Math.floor(r - spanDy(dy, r)); }
            c.fill(x + inset, y + i, x + w - inset, y + i + 1, mix(top, bottom, i / (float) Math.max(1, h - 1)));
        }
    }

    public static void rrectBorder(DrawContext c, int x, int y, int w, int h, int r, int border, int fill) {
        rrect(c, x, y, w, h, r, border);
        rrect(c, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
    }

    public static void shadow(DrawContext c, int x, int y, int w, int h, int r, float strength) {
        boolean prev = HudLayout.mute;
        HudLayout.mute = true;   // a shadow must not enlarge a HUD module's box
        try {
            for (int i = 6; i >= 1; i--)
                rrect(c, x - i, y - i + 2, w + i * 2, h + i * 2, r + i, a(0xFF000000, strength * 0.05f * (7 - i) / 6f));
        } finally { HudLayout.mute = prev; }
    }

    /** Filled rounded rectangle without the anti-aliased edge pixels (used by glow rings, so the rings blend into one smooth falloff). */
    private static void rrectFlat(DrawContext c, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) { c.fill(x, y, x + w, y + h, color); return; }
        c.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - spanDy(dy, r));
            c.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            c.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    /** Soft halo around a rounded rectangle (draw it BEFORE the shape it surrounds).
     *  The rings are weighted so the light falls off smoothly (quadratic): bright right at the edge, gently fading out to nothing at `spread`.
     *  `strength` 1 = about a third opacity at the edge. */
    public static void glow(DrawContext c, int x, int y, int w, int h, int r, int color, float strength, int spread) {
        strength *= glowScale;
        if (strength <= 0.02f || w <= 0 || h <= 0 || spread < 1) return;
        int rgb = color & 0x00FFFFFF;
        float norm = 255f * Math.min(1f, strength) * 0.34f / (spread * spread);
        for (int i = spread; i >= 1; i--) {
            int al = Math.round(norm * (2 * (spread - i) + 1));
            if (al < 1) continue;
            rrectFlat(c, x - i, y - i, w + i * 2, h + i * 2, r + i, (al << 24) | rgb);
        }
    }

    public static void outline(DrawContext c, int x1, int y1, int x2, int y2, int color) {
        c.fill(x1, y1, x2 + 1, y1 + 1, color);
        c.fill(x1, y2, x2 + 1, y2 + 1, color);
        c.fill(x1, y1 + 1, x1 + 1, y2, color);
        c.fill(x2, y1 + 1, x2 + 1, y2, color);
    }

    private static void run(DrawContext c, boolean horiz, int from, int to, int fixed, int color) {
        int lo = Math.min(from, to), hi = Math.max(from, to) + 1;
        if (horiz) c.fill(lo, fixed, hi, fixed + 1, color); else c.fill(fixed, lo, fixed + 1, hi, color);
    }

    /** 1px line, clipped to the screen, drawn as short runs. */
    public static void line(DrawContext c, double x0, double y0, double x1, double y1, int color) {
        MinecraftClient mc = MinecraftClient.getInstance();
        double w = mc.getWindow().getScaledWidth(), h = mc.getWindow().getScaledHeight();
        double t0 = 0, t1 = 1, dx = x1 - x0, dy = y1 - y0;
        double[] p = {-dx, dx, -dy, dy}, q = {x0, w - 1 - x0, y0, h - 1 - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) { if (q[i] < 0) return; }
            else {
                double t = q[i] / p[i];
                if (p[i] < 0) { if (t > t1) return; if (t > t0) t0 = t; }
                else { if (t < t0) return; if (t < t1) t1 = t; }
            }
        }
        int ax = (int) Math.round(x0 + t0 * dx), ay = (int) Math.round(y0 + t0 * dy);
        int bx = (int) Math.round(x0 + t1 * dx), by = (int) Math.round(y0 + t1 * dy);
        int adx = Math.abs(bx - ax), ady = Math.abs(by - ay);
        if (ady == 0) { run(c, true, ax, bx, ay, color); return; }
        if (adx == 0) { run(c, false, ay, by, ax, color); return; }
        if (adx >= ady) {
            int dir = bx >= ax ? 1 : -1, start = ax, cy = ay;
            for (int i = 0; i <= adx; i++) {
                int x = ax + dir * i, y = ay + Math.round((float) (by - ay) * i / adx);
                if (y != cy) { run(c, true, start, x - dir, cy, color); start = x; cy = y; }
            }
            run(c, true, start, bx, cy, color);
        } else {
            int dir = by >= ay ? 1 : -1, start = ay, cx = ax;
            for (int i = 0; i <= ady; i++) {
                int y = ay + dir * i, x = ax + Math.round((float) (bx - ax) * i / ady);
                if (x != cx) { run(c, false, start, y - dir, cx, color); start = y; cx = x; }
            }
            run(c, false, start, by, cx, color);
        }
    }
}
