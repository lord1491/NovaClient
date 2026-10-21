package nova.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import nova.core.*;
import nova.modules.ClientModules.Theme;

/**
 * Drag every HUD element where you want it and resize it.
 * Left drag = move, drag the square handle (or scroll the mouse wheel) = resize, right click = reset that element,
 * arrow keys = nudge the selected element by one pixel. Esc / Done goes back to the menu.
 */
public class HudEditScreen extends Screen {
    private static final int TEXT = 0xFFF0E9E6, DIM = 0xFF8E8380, BTN_W = 104, BTN_H = 18, HANDLE = 7;

    private final Screen parent;
    private Mod selected;

    // drag state: 0 = none, 1 = move, 2 = resize
    private int dragMode;
    private Mod dragMod;
    private double startMx, startMy;
    private float startDx, startDy;

    public HudEditScreen(Screen parent) {
        super(Text.literal("HUD editor"));
        this.parent = parent;
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void removed() { Config.save(); }
    @Override public void close() { MinecraftClient.getInstance().setScreen(parent); }

    /** No blur / dim from the game: the real HUD behind this screen has to stay sharp, it is what you are editing. */
    @Override public void renderBackground(DrawContext ctx, int mx, int my, float delta) {}

    private static boolean in(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static boolean live(Mod m) {
        if (!HudLayout.editable(m) || !m.enabled) return false;
        HudLayout.E e = HudLayout.get(m);
        return e != null && e.has;
    }

    private float[] handle(HudLayout.E e) {
        float[] r = HudLayout.rect(e);
        float hx = e.ancR ? r[0] : r[0] + r[2];
        float hy = e.ancB ? r[1] : r[1] + r[3];
        return new float[]{hx - HANDLE / 2f, hy - HANDLE / 2f};
    }

    /** Smallest box under the mouse (so a small element on top of a big one can still be picked). */
    private Mod pick(double mx, double my) {
        Mod best = null;
        float bestArea = Float.MAX_VALUE;
        for (Mod m : ModuleManager.MODULES) {
            if (!live(m)) continue;
            float[] r = HudLayout.rect(HudLayout.get(m));
            if (!in(mx, my, r[0], r[1], r[2], r[3])) continue;
            float area = r[2] * r[3];
            if (area <= bestArea) { best = m; bestArea = area; }
        }
        return best;
    }

    private Mod pickHandle(double mx, double my) {
        for (Mod m : ModuleManager.MODULES) {
            if (!live(m)) continue;
            float[] h = handle(HudLayout.get(m));
            if (in(mx, my, h[0] - 2, h[1] - 2, HANDLE + 4, HANDLE + 4)) return m;
        }
        return null;
    }

    // ---------- buttons ----------
    private int btnX(int i) { return (width - (BTN_W * 3 + 12)) / 2 + i * (BTN_W + 6); }
    private int btnY() { return height - 30; }
    private int btnAt(double mx, double my) {
        for (int i = 0; i < 3; i++) if (in(mx, my, btnX(i), btnY(), BTN_W, BTN_H)) return i;
        return -1;
    }

    private void drawButton(DrawContext ctx, int i, String label, boolean hov, boolean enabled, int acc) {
        int x = btnX(i), y = btnY();
        int fill = !enabled ? 0xC0181416 : hov ? Gfx.mix(0xF0241E22, acc, 0.35f) : 0xE0141012;
        Gfx.rrect(ctx, x, y, BTN_W, BTN_H, 5, Gfx.mix(0xFF2A2326, acc, hov && enabled ? 0.7f : 0.25f));
        Gfx.rrect(ctx, x + 1, y + 1, BTN_W - 2, BTN_H - 2, 4, fill);
        U.text(ctx, label, x + BTN_W / 2 - U.width(label) / 2, y + 5, enabled ? TEXT : DIM);
    }

    // ---------- render ----------
    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        int acc = Theme.accent();
        ctx.fill(0, 0, width, height, 0x40000000);

        Mod hover = dragMode != 0 ? dragMod : pick(mx, my);
        Mod hHandle = dragMode != 0 ? null : pickHandle(mx, my);
        int shown = 0;
        for (Mod m : ModuleManager.MODULES) {
            if (!live(m)) continue;
            shown++;
            HudLayout.E e = HudLayout.get(m);
            float[] r = HudLayout.rect(e);
            int x = Math.round(r[0]), y = Math.round(r[1]), w = Math.max(2, Math.round(r[2])), h = Math.max(2, Math.round(r[3]));
            boolean sel = m == selected, hov = m == hover;
            ctx.fill(x, y, x + w, y + h, Gfx.a(acc, sel ? 0.20f : hov ? 0.14f : 0.07f));
            Gfx.outline(ctx, x - 1, y - 1, x + w, y + h, Gfx.a(acc, sel ? 1f : hov ? 0.9f : 0.5f));

            String label = m.name + (e.s != 1f ? "  " + String.format("%.2fx", e.s) : "");
            int ly = y - 12 >= 0 ? y - 12 : y + h + 2;
            int lw = U.width(label) + 8;
            int lx = Math.max(0, Math.min(width - lw, x));
            Gfx.rrect(ctx, lx, ly, lw, 11, 3, 0xD0100C0E);
            U.text(ctx, label, lx + 4, ly + 2, sel || hov ? 0xFFFFFFFF : 0xFFC8C0BC);

            float[] hd = handle(e);
            boolean hh = m == hHandle || (dragMode == 2 && m == dragMod);
            ctx.fill(Math.round(hd[0]), Math.round(hd[1]), Math.round(hd[0]) + HANDLE, Math.round(hd[1]) + HANDLE, hh ? 0xFFFFFFFF : Gfx.a(acc, 0.95f));
        }

        String hint = "Drag: move   Corner square / mouse wheel: resize   Right click: reset it   Arrows: nudge   Esc: back";
        Gfx.rrect(ctx, width / 2 - U.width(hint) / 2 - 6, 6, U.width(hint) + 12, 16, 5, 0xD0100C0E);
        U.text(ctx, hint, width / 2 - U.width(hint) / 2, 10, TEXT);

        if (shown == 0) {
            String msg = MinecraftClient.getInstance().player == null
                    ? "Join a world to edit your HUD."
                    : "No HUD module is showing anything yet. Turn some on in the HUD tab first (Watermark, Coordinates, ...).";
            Gfx.rrect(ctx, width / 2 - U.width(msg) / 2 - 8, height / 2 - 10, U.width(msg) + 16, 20, 6, 0xE0100C0E);
            U.text(ctx, msg, width / 2 - U.width(msg) / 2, height / 2 - 4, TEXT);
        }

        int b = btnAt(mx, my);
        drawButton(ctx, 0, "Reset selected", b == 0, selected != null && live(selected), acc);
        drawButton(ctx, 1, "Reset all HUD", b == 1, true, acc);
        drawButton(ctx, 2, "Done", b == 2, true, acc);
    }

    // ---------- input ----------
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int btn = click.button();

        int b = btnAt(mx, my);
        if (b >= 0 && btn == 0) {
            if (b == 0 && selected != null) { HudLayout.E e = HudLayout.get(selected); if (e != null) HudLayout.reset(e); }
            else if (b == 1) HudLayout.resetAll();
            else if (b == 2) close();
            return true;
        }

        if (btn == 0) {
            Mod hm = pickHandle(mx, my);
            if (hm != null) {
                selected = hm; dragMode = 2; dragMod = hm;
                return true;
            }
        }
        Mod m = pick(mx, my);
        if (m == null) { if (btn == 0) selected = null; return super.mouseClicked(click, doubled); }
        selected = m;
        HudLayout.E e = HudLayout.get(m);
        if (btn == 1) { HudLayout.reset(e); return true; }
        if (btn == 0) {
            dragMode = 1; dragMod = m;
            startMx = mx; startMy = my; startDx = e.dx; startDy = e.dy;
        }
        return true;
    }

    @Override public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragMode == 0 || dragMod == null) return super.mouseDragged(click, dx, dy);
        HudLayout.E e = HudLayout.get(dragMod);
        if (e == null) return true;
        double mx = click.x(), my = click.y();
        if (dragMode == 1) {
            e.dx = startDx + (float) (mx - startMx);
            e.dy = startDy + (float) (my - startMy);
            HudLayout.clamp(e, width, height);
        } else {
            // the corner of the box that stays put is the anchor; the scale is how far along the box diagonal the mouse is
            float ax = e.ax(), ay = e.ay();
            float ox = e.ancR ? e.minX : e.maxX, oy = e.ancB ? e.minY : e.maxY;
            float vx = ox - ax, vy = oy - ay;
            float len2 = vx * vx + vy * vy;
            if (len2 > 0.01f) {
                float px = ax + e.dx, py = ay + e.dy;
                float s = (float) (((mx - px) * vx + (my - py) * vy) / len2);
                s = Math.round(s * 20f) / 20f;
                e.s = Math.max(HudLayout.MIN_SCALE, Math.min(HudLayout.MAX_SCALE, s));
            }
        }
        return true;
    }

    @Override public boolean mouseReleased(Click click) {
        dragMode = 0; dragMod = null;
        return super.mouseReleased(click);
    }

    @Override public boolean mouseScrolled(double mx, double my, double h, double v) {
        Mod m = pick(mx, my);
        if (m == null) m = selected;
        if (m == null || !live(m)) return true;
        HudLayout.E e = HudLayout.get(m);
        float s = Math.round((e.s + (v > 0 ? 0.05f : -0.05f)) * 20f) / 20f;
        e.s = Math.max(HudLayout.MIN_SCALE, Math.min(HudLayout.MAX_SCALE, s));
        HudLayout.clamp(e, width, height);
        return true;
    }

    @Override public boolean keyPressed(KeyInput input) {
        int k = input.key();
        if (selected != null && live(selected)) {
            HudLayout.E e = HudLayout.get(selected);
            switch (k) {
                case 263 -> { e.dx -= 1; HudLayout.clamp(e, width, height); return true; }   // left
                case 262 -> { e.dx += 1; HudLayout.clamp(e, width, height); return true; }   // right
                case 265 -> { e.dy -= 1; HudLayout.clamp(e, width, height); return true; }   // up
                case 264 -> { e.dy += 1; HudLayout.clamp(e, width, height); return true; }   // down
                case 261 -> { HudLayout.reset(e); return true; }                             // delete
                default -> {}
            }
        }
        if (k == 257) { close(); return true; }                                              // enter
        return super.keyPressed(input);
    }
}
