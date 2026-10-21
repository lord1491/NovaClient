package nova.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import nova.core.*;
import nova.modules.ClientModules;
import nova.modules.ClientModules.Theme;

import java.util.*;

/**
 * Flat, near-black window: icon rail on the left, top bar (logo, breadcrumb, search, Save),
 * one pill row per module (name, info, star, keybind, switch). Clicking a row opens that module's settings page.
 */
public class NovaScreen extends Screen {
    private static final int SIDE = 30, HEAD = 30, ROW = 22, ROW_N = 32, GAP = 4, PAD = 8;
    private static final int TEXT = 0xFFF2F2F2, DIM = 0xFF8A8A8A, WHITE = 0xFFFFFFFF;

    // everything is derived from Theme > Panel, pushed towards black
    private static int base() { return Gfx.mix(Theme.panel(), 0xFF000000, 0.55f); }
    private static int bgC() { return (Math.round(2.55f * ClientModules.Layout.opacity()) << 24) | (base() & 0xFFFFFF); }
    private static int shade(float t) { return Gfx.mix(base(), WHITE, t); }

    // ---- tiny monochrome glyphs ('#' = pixel) ----
    private static final String[] G_STAR = {"....#....", "....#....", "...###...", "#########", ".#######.", "..#####..", "..##.##..", ".##...##.", ".#.....#."};
    private static final String[] G_COMBAT = {"......###", ".....###.", "....###..", "...###...", "#.###....", ".###.....", "..##.....", ".#.##....", "#........"};
    private static final String[] G_MOVE = {"....#....", "...###...", "..#.#.#..", ".#..#..#.", "....#....", "....#....", "....#....", "....#....", "....#...."};
    private static final String[] G_PLAYER = {"...###...", "..#####..", "..#####..", "...###...", ".#######.", "#########", "#########", "#########", "#.......#"};
    private static final String[] G_RENDER = {".........", "..#####..", ".#.....#.", "#..###..#", "#..###..#", ".#.....#.", "..#####..", ".........", "........."};
    private static final String[] G_WORLD = {"..#####..", ".#..#..#.", "#...#...#", "#...#...#", "#########", "#...#...#", "#...#...#", ".#..#..#.", "..#####.."};
    private static final String[] G_MISC = {"####.####", "#..#.#..#", "#..#.#..#", "####.####", ".........", "####.####", "#..#.#..#", "#..#.#..#", "####.####"};
    private static final String[] G_HUD = {"#########", "#.......#", "#########", "#.......#", "#.......#", "#.......#", "#.......#", "#.......#", "#########"};
    private static final String[] G_CLIENT = {"......#..", "#########", "......#..", ".........", "..#......", "#########", "..#......", ".........", "........."};
    private static final String[] G_EDIT = {"....#....", "...###...", "....#....", ".#..#..#.", "#########", ".#..#..#.", "....#....", "...###...", "....#...."};
    private static final String[] G_RESET = {".#.....#.", "..#...#..", "...#.#...", "....#....", "...#.#...", "..#...#..", ".#.....#."};
    private static final String[] G_INFO = {"..###..", ".#...#.", "#..#..#", "#..#..#", "#..#..#", ".#...#.", "..###.."};
    private static final String[] G_STAR_S = {"...#...", "..###..", "#######", ".#####.", "..###..", ".##.##.", ".#...#."};
    private static final String[] G_STAR_O = {"...#...", "..#.#..", "###.###", ".#...#.", "..#.#..", ".#.#.#.", ".#...#."};
    private static final String[] G_KEYS = {"#########", "#.#.#.#.#", "#.#.#.#.#", "#..###..#", "#########"};
    private static final String[][] RAIL = {G_STAR, G_COMBAT, G_MOVE, G_PLAYER, G_RENDER, G_WORLD, G_MISC, G_HUD, G_CLIENT};
    private static final String[] RAIL_NAMES = {"Favourites", "Combat", "Movement", "Player", "Render", "World", "Misc", "HUD", "Client"};

    private record Hit(int kind, Mod m, Setting<?> s, int x, int y, int w, int h) {}
    private static final class MA { float en, hv; }

    private static final Map<Mod, MA> anims = new HashMap<>();
    private static final Map<Object, Float> smooth = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();

    private static Category cat = Category.COMBAT;
    private static boolean favTab;
    private static Mod openMod;
    private Mod binding;
    private Hit dragHit;
    private Setting.Num dragging;
    private static double scroll, scrollT;
    private static String search = "";
    private boolean searchFocus;
    private long savedAt;
    private float open, oa;
    private long lastNs = System.nanoTime();
    private int px, py, pw, ph, oy, contentH, viewH, listTop, hoverTab = -1;

    public NovaScreen() { super(Text.literal("Nova")); }

    @Override protected void init() {
        ClientModules.Background.scan();
        ClientModules.Gui.scan();
        pw = Math.min(width - 30, ClientModules.Layout.width());
        ph = Math.min(height - 30, ClientModules.Layout.height());
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void removed() { Gfx.glowScale = 1f; Config.save(); }

    private int cx() { return px + SIDE + PAD; }
    private int cw() { return pw - SIDE - PAD * 2; }
    private int ctop() { return py + oy + HEAD; }
    private int cbot() { return py + oy + ph - 16; }
    private int A(int c) { return Gfx.a(c, oa); }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private float sm(Object key, float target, float k) {
        float v = smooth.getOrDefault(key, target);
        v += (target - v) * k;
        smooth.put(key, v);
        return v;
    }

    private void click(float pitch) {
        if (!ClientModules.Gui.sounds()) return;
        ClientModules.Gui.play(pitch);
    }

    private static void glyph(DrawContext c, String[] g, int x, int y, int color) {
        for (int r = 0; r < g.length; r++) {
            String row = g[r];
            int start = -1;
            for (int i = 0; i <= row.length(); i++) {
                boolean on = i < row.length() && row.charAt(i) == '#';
                if (on && start < 0) start = i;
                if (!on && start >= 0) { c.fill(x + start, y + r, x + i, y + r + 1, color); start = -1; }
            }
        }
    }

    private List<Mod> visible() {
        List<Mod> out = new ArrayList<>();
        if (!search.isBlank()) {
            String q = search.toLowerCase();
            for (Mod m : ModuleManager.MODULES) if (m.name.toLowerCase().contains(q)) out.add(m);
            return out;
        }
        if (favTab) {
            for (Mod m : ModuleManager.MODULES) if (Config.FAVS.contains(m.name)) out.add(m);
            return out;
        }
        return ModuleManager.in(cat);
    }

    private static String keyName(int k) {
        if (k < 0) return "None";
        if (k >= 65 && k <= 90) return String.valueOf((char) k);
        if (k >= 48 && k <= 57) return String.valueOf((char) k);
        if (k >= 290 && k <= 301) return "F" + (k - 289);
        return switch (k) {
            case 32 -> "Space"; case 340 -> "LShift"; case 344 -> "RShift"; case 341 -> "LCtrl";
            case 345 -> "RCtrl"; case 342 -> "LAlt"; case 346 -> "RAlt"; case 258 -> "Tab";
            default -> "Key" + k;
        };
    }

    private static String fmt(Setting.Num n) { return n.step >= 1 ? String.valueOf(n.getInt()) : String.format("%.2f", n.get()); }

    private static String clip(String s, int maxW) {
        if (U.width(s) <= maxW) return s;
        while (s.length() > 1 && U.width(s + "..") > maxW) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    private void pill(DrawContext ctx, int x, int y, int w, int h, int r, float hv) {
        Gfx.rrect(ctx, x, y, w, h, r, A(shade(0.07f + 0.15f * hv)));
        Gfx.rrect(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), A(Gfx.a(Gfx.mix(shade(0.03f), shade(0.08f), hv), ClientModules.Background.cardAlpha())));
    }

    private void sw(DrawContext ctx, int x, int y, float v) {
        Gfx.rrect(ctx, x, y, 22, 11, 5, A(Gfx.mix(shade(0.17f), 0xFFE6E6E6, v)));
        Gfx.rrect(ctx, x + 2 + Math.round(11 * v), y + 2, 7, 7, 3, A(Gfx.mix(0xFFBDBDBD, 0xFF101010, v)));
    }

    // ---------- render ----------
    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        float k = 1f - (float) Math.exp(-dt * 16f);
        open += (1f - open) * (1f - (float) Math.exp(-dt * 11f));
        oa = Gfx.ease(Math.min(1f, open * 1.05f));
        oy = Math.round((1f - oa) * 14f);
        hits.clear();
        int x0 = px, y0 = py + oy;

        final int R = Math.max(ClientModules.Layout.radius(), 8);
        ClientModules.Background.drawScreen(ctx, width, height, oa);
        Gfx.shadow(ctx, x0, y0, pw, ph, R, oa);
        Gfx.rrect(ctx, x0, y0, pw, ph, R, A(shade(0.10f)));
        Gfx.rrect(ctx, x0 + 1, y0 + 1, pw - 2, ph - 2, Math.max(0, R - 1), A(bgC()));
        ClientModules.Background.drawWindow(ctx, x0 + 1, y0 + 1, pw - 2, ph - 2, Math.max(0, R - 1), oa);

        // ---- top bar ----
        U.text(ctx, "Nova", x0 + 4, y0 + 11, A(WHITE));
        String label = !search.isBlank() ? "Search" : favTab ? "Favourites" : cat.label;
        int bcw = U.width("Nova  >  " + label) + 14, bcx = x0 + SIDE + PAD;
        pill(ctx, bcx, y0 + 7, bcw, 16, 7, 0f);
        U.text(ctx, "Nova", bcx + 7, y0 + 11, A(DIM));
        U.text(ctx, ">", bcx + 7 + U.width("Nova "), y0 + 11, A(DIM));
        U.text(ctx, label, bcx + 7 + U.width("Nova  > "), y0 + 11, A(TEXT));

        int sw = 120, sx = Math.max(bcx + bcw + 10, x0 + (pw - sw) / 2), sy = y0 + 7;
        float sf = sm("search", searchFocus ? 1f : 0f, k);
        pill(ctx, sx, sy, sw, 16, 7, sf);
        boolean empty = search.isEmpty() && !searchFocus;
        String shown = empty ? "Search..." : search + (searchFocus && (System.currentTimeMillis() / 500) % 2 == 0 ? "|" : "");
        while (U.width(shown) > sw - 14 && shown.length() > 1) shown = shown.substring(1);
        U.text(ctx, shown, sx + 7, sy + 4, A(empty ? DIM : TEXT));

        int svx = x0 + pw - PAD - 46, svy = y0 + 7;
        boolean svh = in(mx, my, svx, svy, 46, 16);
        float svv = sm("save", svh ? 1f : 0f, k);
        pill(ctx, svx, svy, 46, 16, 7, svv);
        Gfx.outline(ctx, svx + 7, svy + 5, svx + 12, svy + 10, A(DIM));
        boolean flash = System.currentTimeMillis() - savedAt < 1200;
        U.text(ctx, flash ? "Saved" : "Save", svx + 16, svy + 4, A(TEXT));

        // ---- icon rail ----
        int railH = RAIL.length * 20 + 4;
        Gfx.rrect(ctx, x0 + 5, y0 + 32, SIDE - 10, railH, 8, A(shade(0.07f)));
        Gfx.rrect(ctx, x0 + 6, y0 + 33, SIDE - 12, railH - 2, 7, A(shade(0.02f)));
        hoverTab = -1;
        for (int i = 0; i < RAIL.length; i++) {
            int cell = y0 + 34 + i * 20;
            boolean sel = search.isBlank() && (i == 0 ? favTab : !favTab && cat.ordinal() == i - 1);
            boolean hov = in(mx, my, x0 + 6, cell, 18, 18);
            if (hov) hoverTab = i;
            float s = sm("tab" + i, sel ? 1f : 0f, k), h = sm("tabh" + i, hov ? 1f : 0f, k);
            if (s > 0.02f || h > 0.02f) Gfx.rrect(ctx, x0 + 6, cell, 18, 18, 6, A(Gfx.a(WHITE, 0.10f * s + 0.05f * h)));
            glyph(ctx, RAIL[i], x0 + 10, cell + 5, A(Gfx.mix(0xFF7C7C7C, WHITE, Math.max(s, h * 0.7f))));
        }
        int ey = y0 + ph - 46, ry = y0 + ph - 26;
        boolean eh = in(mx, my, x0 + 6, ey, 18, 18), rh = in(mx, my, x0 + 6, ry, 18, 18);
        float ehv = sm("hudedit", eh ? 1f : 0f, k), rhv = sm("reset", rh ? 1f : 0f, k);
        if (ehv > 0.02f) Gfx.rrect(ctx, x0 + 6, ey, 18, 18, 6, A(Gfx.a(WHITE, 0.08f * ehv)));
        glyph(ctx, G_EDIT, x0 + 10, ey + 4, A(Gfx.mix(0xFF7C7C7C, WHITE, ehv)));
        if (rhv > 0.02f) Gfx.rrect(ctx, x0 + 6, ry, 18, 18, 6, A(Gfx.a(0xFFFF5A5A, 0.18f * rhv)));
        glyph(ctx, G_RESET, x0 + 10, ry + 5, A(Gfx.mix(0xFF7C7C7C, 0xFFFF7A7A, rhv)));

        // ---- content ----
        int cx = cx(), cw = cw(), top = ctop(), bot = cbot();
        String hint = null;
        if (openMod == null) {
            listTop = top + 2;
            viewH = bot - listTop;
            int yy = 0;
            ctx.enableScissor(cx - 4, listTop, cx + cw + 4, bot);
            for (Mod m : visible()) {
                MA a = anims.computeIfAbsent(m, mm -> { MA n = new MA(); n.en = mm.enabled ? 1f : 0f; return n; });
                a.en += ((m.enabled ? 1f : 0f) - a.en) * k;
                int y = listTop - (int) Math.round(scroll) + yy;
                yy += ROW + GAP;
                if (y + ROW < listTop - 2 || y > bot + 2) continue;
                boolean hov = in(mx, my, cx, y, cw, ROW) && my >= listTop && my < bot;
                a.hv += ((hov ? 1f : 0f) - a.hv) * k;
                if (hov) hint = m.desc;
                drawRow(ctx, m, a, cx, y, cw, mx, my);
            }
            ctx.disableScissor();
            contentH = Math.max(0, yy - GAP);
        } else {
            drawDetail(ctx, openMod, cx, top, cw, bot, mx, my, k);
            if (in(mx, my, cx, listTop, cw, bot - listTop)) hint = null;
        }

        scrollT = Math.max(0, Math.min(scrollT, Math.max(0, contentH - viewH)));
        scroll += (scrollT - scroll) * (1f - (float) Math.exp(-dt * 18f));
        if (contentH > viewH) {
            int bh = Math.max(20, viewH * viewH / contentH);
            int by = listTop + (int) ((viewH - bh) * (scroll / (contentH - viewH)));
            Gfx.rrect(ctx, x0 + pw - 5, by, 2, bh, 1, A(Gfx.a(WHITE, 0.45f)));
        }

        String foot = hint != null ? hint : openMod != null ? "Esc: back   Right click a mode: previous" : "Click: settings   Right click: toggle   Middle click: bind key   RShift: close";
        U.text(ctx, clip(foot, cw), cx, y0 + ph - 12, A(DIM));

        if (hoverTab >= 0) {
            String l = RAIL_NAMES[hoverTab];
            int tw = U.width(l) + 10, tx = x0 + SIDE + 2, ty = y0 + 34 + hoverTab * 20 + 1;
            Gfx.rrect(ctx, tx, ty, tw, 16, 5, A(0xF0000000 | (shade(0.10f) & 0xFFFFFF)));
            U.text(ctx, l, tx + 5, ty + 4, A(TEXT));
        } else if (eh || rh) {
            String l = eh ? "Edit HUD" : "Reset ALL";
            int tx = x0 + SIDE + 2, ty = (eh ? ey : ry) + 1;
            Gfx.rrect(ctx, tx, ty, U.width(l) + 10, 16, 5, A(0xF0000000 | (shade(0.10f) & 0xFFFFFF)));
            U.text(ctx, l, tx + 5, ty + 4, A(TEXT));
        }
    }

    /** Name, info icon and star on the left of a row; returns the star's x. */
    private int nameBlock(DrawContext ctx, Mod m, int x, int y, int color) {
        U.text(ctx, m.name, x + 10, y + 7, A(color));
        int ix = x + 10 + U.width(m.name) + 6;
        glyph(ctx, G_INFO, ix, y + 7, A(0xFF6E6E6E));
        int sx = ix + 7 + 5;
        boolean fav = Config.FAVS.contains(m.name);
        glyph(ctx, fav ? G_STAR_S : G_STAR_O, sx, y + 7, A(fav ? WHITE : 0xFF6E6E6E));
        return sx;
    }

    private void drawRow(DrawContext ctx, Mod m, MA a, int x, int y, int w, int mx, int my) {
        pill(ctx, x, y, w, ROW, 8, a.hv);
        int sx = nameBlock(ctx, m, x, y, Gfx.mix(0xFFCFCFCF, WHITE, a.en));
        int tx = x + w - 8 - 22;
        sw(ctx, tx, y + 5, a.en);
        boolean bound = m.key >= 0 || binding == m;
        String kt = binding == m ? "..." : m.key >= 0 ? keyName(m.key) : "";
        int bw = bound ? U.width(kt) + 10 : 20, bx = tx - 6 - bw;
        boolean kh = in(mx, my, bx, y + 4, bw, 14);
        Gfx.rrect(ctx, bx, y + 4, bw, 14, 5, A(shade(kh ? 0.14f : 0.07f)));
        if (bound) U.text(ctx, kt, bx + 5, y + 7, A(binding == m ? WHITE : TEXT));
        else glyph(ctx, G_KEYS, bx + 5, y + 8, A(0xFF8A8A8A));
        hits.add(new Hit(0, m, null, x, y, w, ROW));
        hits.add(new Hit(8, m, null, sx - 2, y + 3, 11, 16));
        hits.add(new Hit(9, m, null, bx, y + 4, bw, 14));
        hits.add(new Hit(7, m, null, tx - 2, y + 3, 26, 16));
    }

    private void drawDetail(DrawContext ctx, Mod m, int cx, int top, int cw, int bot, int mx, int my, float k) {
        MA a = anims.computeIfAbsent(m, mm -> { MA n = new MA(); n.en = mm.enabled ? 1f : 0f; return n; });
        a.en += ((m.enabled ? 1f : 0f) - a.en) * k;
        // header
        boolean bh = in(mx, my, cx, top + 1, 44, 16);
        pill(ctx, cx, top + 1, 44, 16, 7, sm("back", bh ? 1f : 0f, k));
        U.text(ctx, "< Back", cx + 7, top + 5, A(TEXT));
        hits.add(new Hit(10, m, null, cx, top + 1, 44, 16));
        int nx = cx + 54;
        U.text(ctx, m.name, nx, top + 5, A(WHITE));
        int ix = nx + U.width(m.name) + 6;
        glyph(ctx, G_INFO, ix, top + 5, A(0xFF6E6E6E));
        int stx = ix + 12;
        boolean fav = Config.FAVS.contains(m.name);
        glyph(ctx, fav ? G_STAR_S : G_STAR_O, stx, top + 5, A(fav ? WHITE : 0xFF6E6E6E));
        hits.add(new Hit(8, m, null, stx - 2, top + 2, 11, 14));
        int tx = cx + cw - 22;
        sw(ctx, tx, top + 3, a.en);
        hits.add(new Hit(7, m, null, tx - 2, top + 1, 26, 15));
        U.text(ctx, clip(m.desc, cw), cx, top + 22, A(DIM));

        listTop = top + 36;
        viewH = bot - listTop;
        ctx.enableScissor(cx - 4, listTop, cx + cw + 4, bot);
        int yy = 0;
        int x = cx, w = cw;
        for (Setting<?> s : m.settings) {
            int rh = s instanceof Setting.Num ? ROW_N : ROW;
            int y = listTop - (int) Math.round(scroll) + yy;
            yy += rh + GAP;
            if (y + rh < listTop - 2 || y > bot + 2) continue;
            boolean hov = in(mx, my, x, y, w, rh) && my >= listTop && my < bot;
            float hv = sm("rh" + System.identityHashCode(s), hov ? 1f : 0f, k);
            pill(ctx, x, y, w, rh, 8, hv);
            if (s instanceof Setting.Bool b) {
                U.text(ctx, s.name, x + 10, y + 7, A(TEXT));
                sw(ctx, x + w - 8 - 22, y + 5, sm(s, b.get() ? 1f : 0f, k));
                hits.add(new Hit(1, m, s, x, y, w, rh));
            } else if (s instanceof Setting.Mode md) {
                U.text(ctx, s.name, x + 10, y + 7, A(TEXT));
                String t = "<  " + md.value + "  >";
                int tw = U.width(t) + 12;
                Gfx.rrect(ctx, x + w - 8 - tw, y + 4, tw, 14, 5, A(shade(0.07f + 0.05f * hv)));
                U.text(ctx, t, x + w - 8 - tw + 6, y + 7, A(TEXT));
                hits.add(new Hit(2, m, s, x, y, w, rh));
            } else if (s instanceof Setting.Num n) {
                U.text(ctx, s.name, x + 10, y + 5, A(TEXT));
                String v = fmt(n);
                U.text(ctx, v, x + w - 10 - U.width(v), y + 5, A(DIM));
                int tx0 = x + 10, tw = w - 20, ty0 = y + 22;
                float ratio = (float) ((n.get() - n.min) / (n.max - n.min));
                float sr = sm(s, ratio, dragging == n ? 0.6f : k);
                Gfx.rrect(ctx, tx0, ty0, tw, 3, 1, A(shade(0.16f)));
                int fw = Math.max(0, Math.round(tw * sr));
                if (fw > 0) Gfx.rrect(ctx, tx0, ty0, Math.max(2, fw), 3, 1, A(0xFFEDEDED));
                Gfx.rrect(ctx, tx0 + fw - 3, ty0 - 2, 7, 7, 3, A(WHITE));
                hits.add(new Hit(3, m, s, tx0, y + 14, tw, 14));
            } else if (s instanceof Setting.Action) {
                U.text(ctx, s.name, x + 10, y + 7, A(TEXT));
                int tw = U.width("Run") + 14;
                Gfx.rrect(ctx, x + w - 8 - tw, y + 4, tw, 14, 5, A(shade(0.07f + 0.10f * hv)));
                U.text(ctx, "Run", x + w - 8 - tw + 7, y + 7, A(TEXT));
                hits.add(new Hit(4, m, s, x, y, w, rh));
            }
        }
        // footer: Reset | Bind
        int y = listTop - (int) Math.round(scroll) + yy;
        if (!(y + ROW < listTop - 2 || y > bot + 2)) {
            int half = (w - GAP) / 2;
            boolean h1 = in(mx, my, x, y, half, ROW) && my >= listTop && my < bot;
            boolean h2 = in(mx, my, x + half + GAP, y, half, ROW) && my >= listTop && my < bot;
            pill(ctx, x, y, half, ROW, 8, sm("freset", h1 ? 1f : 0f, k));
            U.text(ctx, "Reset", x + half / 2 - U.width("Reset") / 2, y + 7, A(TEXT));
            pill(ctx, x + half + GAP, y, half, ROW, 8, sm("fbind", h2 ? 1f : 0f, k));
            String bt = binding == m ? "Press a key..." : "Bind: " + keyName(m.key);
            U.text(ctx, bt, x + half + GAP + half / 2 - U.width(bt) / 2, y + 7, A(TEXT));
            hits.add(new Hit(5, m, null, x, y, half, ROW));
            hits.add(new Hit(6, m, null, x + half + GAP, y, half, ROW));
        }
        yy += ROW;
        ctx.disableScissor();
        contentH = yy;
    }

    // ---------- input ----------
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int btn = click.button();
        if (binding != null) { binding = null; click(0.8f); return true; }
        int y0 = py + oy;
        int bcw = U.width("Nova  >  " + (!search.isBlank() ? "Search" : favTab ? "Favourites" : cat.label)) + 14;
        int sw = 120, sx = Math.max(px + SIDE + PAD + bcw + 10, px + (pw - sw) / 2), sy = y0 + 7;
        searchFocus = in(mx, my, sx, sy, sw, 16);
        if (searchFocus) { click(1.0f); return true; }
        if (in(mx, my, px + pw - PAD - 46, y0 + 7, 46, 16)) {
            Config.save(); savedAt = System.currentTimeMillis(); click(1.2f);
            return true;
        }
        for (int i = 0; i < RAIL.length; i++) {
            if (in(mx, my, px + 6, y0 + 34 + i * 20, 18, 18)) {
                if (i == 0) favTab = true; else { favTab = false; cat = Category.values()[i - 1]; }
                openMod = null; scroll = scrollT = 0; search = "";
                click(1.1f);
                return true;
            }
        }
        if (in(mx, my, px + 6, y0 + ph - 46, 18, 18)) {
            click(1.0f);
            MinecraftClient.getInstance().setScreen(new HudEditScreen(this));
            return true;
        }
        if (in(mx, my, px + 6, y0 + ph - 26, 18, 18)) {
            ModuleManager.resetAll(); openMod = null; click(0.7f);
            return true;
        }

        if (my < ctop() || my >= cbot() || mx < cx() - 2 || mx > cx() + cw() + 2) return super.mouseClicked(click, doubled);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!in(mx, my, h.x(), h.y(), h.w(), h.h())) continue;
            boolean header = h.y() + h.h() <= listTop;
            if (!header && (my < listTop || my >= cbot())) continue;
            switch (h.kind()) {
                case 0 -> {
                    if (btn == 0) {
                        if (h.m().settings.isEmpty()) { h.m().toggle(); click(h.m().enabled ? 1.25f : 0.85f); }
                        else { openMod = h.m(); scroll = scrollT = 0; click(1.0f); }
                    }
                    else if (btn == 1) { h.m().toggle(); click(h.m().enabled ? 1.25f : 0.85f); }
                    else if (btn == 2) { binding = h.m(); click(1.0f); }
                }
                case 1 -> { Setting.Bool b = (Setting.Bool) h.s(); b.value = !b.value; click(b.value ? 1.2f : 0.9f); }
                case 2 -> { ((Setting.Mode) h.s()).cycle(btn == 1 ? -1 : 1); click(1.05f); }
                case 3 -> { if (btn == 0) { dragging = (Setting.Num) h.s(); dragHit = h; updateDrag(mx); click(1.3f); } }
                case 4 -> { ((Setting.Action) h.s()).run.run(); click(1.0f); }
                case 5 -> { h.m().reset(); click(0.8f); }
                case 6 -> { binding = h.m(); click(1.0f); }
                case 7 -> { h.m().toggle(); click(h.m().enabled ? 1.25f : 0.85f); }
                case 8 -> { if (!Config.FAVS.remove(h.m().name)) Config.FAVS.add(h.m().name); click(1.1f); }
                case 9 -> { if (btn == 1) { h.m().key = -1; click(0.8f); } else { binding = h.m(); click(1.0f); } }
                case 10 -> { openMod = null; scroll = scrollT = 0; click(0.9f); }
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private void updateDrag(double mx) {
        if (dragging == null || dragHit == null) return;
        double ratio = Math.max(0, Math.min(1, (mx - dragHit.x()) / dragHit.w()));
        dragging.set(dragging.min + ratio * (dragging.max - dragging.min));
    }

    @Override public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging != null) { updateDrag(click.x()); return true; }
        return super.mouseDragged(click, dx, dy);
    }

    @Override public boolean mouseReleased(Click click) {
        dragging = null; dragHit = null;
        return super.mouseReleased(click);
    }

    @Override public boolean mouseScrolled(double mx, double my, double h, double v) {
        scrollT = Math.max(0, Math.min(scrollT - v * 28, Math.max(0, contentH - viewH)));
        return true;
    }

    @Override public boolean keyPressed(KeyInput input) {
        int k = input.key();
        if (binding != null) {
            if (k == 261) binding.key = -1;
            else if (k != 256) binding.key = k;
            binding = null;
            click(1.0f);
            return true;
        }
        if (searchFocus) {
            if (k == 256) { searchFocus = false; return true; }
            if (k == 259 && !search.isEmpty()) { search = search.substring(0, search.length() - 1); scroll = scrollT = 0; }
            return true;
        }
        if (k == 256 && openMod != null) { openMod = null; scroll = scrollT = 0; return true; }
        if (k == 344) { close(); return true; }
        return super.keyPressed(input);
    }

    @Override public boolean charTyped(CharInput input) {
        if (searchFocus && input.codepoint() >= 32) {
            search += Character.toString(input.codepoint());
            openMod = null;
            scroll = scrollT = 0;
            return true;
        }
        return super.charTyped(input);
    }
}
