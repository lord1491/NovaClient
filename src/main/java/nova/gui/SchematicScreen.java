package nova.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import nova.core.*;
import nova.modules.BuildModules;
import nova.modules.ClientModules;
import nova.modules.ClientModules.Theme;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Stand-alone window for the schematic builder (opened with "Open builder window" in World > Schematic).
 * Build tab: files, overview with progress / speed / ETA, position controls and a start button.
 * Materials tab: everything the schematic needs, what you carry and what is missing.
 * Settings tab: every Schematic setting, grouped and foldable.
 */
public class SchematicScreen extends Screen {
    private static final int HEAD = 30, TABS = 22, PAD = 10, GAP = 8, ROW_B = 15, ROW_N = 23, FOOT = 16, CARD_HEAD = 20, RUN_H = 22, RUN_GAP = 6;
    private static final int TEXT = 0xFFF0E9E6, DIM = 0xFF8E8380, GREEN = 0xFF4DDC7A, AMBER = 0xFFFFA23A, RED = 0xFFFF5A5A;
    private static int shade(float t) { return Gfx.mix(Theme.panel(), 0xFFFFFFFF, t); }
    private static int bgC() { return (Math.round(2.55f * ClientModules.Layout.opacity()) << 24) | (Theme.panel() & 0xFFFFFF); }
    private static int cardC() { return shade(0.03f); }
    private static int trackC() { return shade(0.17f); }
    private static int edgeC() { return shade(0.11f); }
    private static int cardEdgeC() { return shade(0.07f); }
    private static int slotC() { return shade(0.08f); }
    private static int inputC() { return shade(0.01f); }
    private static int sliderC() { return shade(0.13f); }

    /** Setting names per group. Anything not listed here (and not handled on the Build tab) lands in "Other", so nothing is ever hidden. */
    private static final String[][] GROUPS = {
        {"Placement", "Order", "Build mode", "First layer", "Last layer (0 = all)", "Disable when finished", "Pause below health (0 = off)", "Reach", "Blocks / tick", "Delay (ticks)", "Rotate", "Line of sight check", "Humanize", "Match facing", "Aim speed", "Max turn / tick", "Creative fill"},
        {"Position", "Origin is", "Rotation", "Offset X", "Offset Y", "Offset Z"},
        {"Restock", "Take items from chests", "Restock from", "Chest range", "Stacks per item", "Free slots to keep", "Copy material list", "Restock current layer only", "Restock filler too", "Mark / unmark chest I look at", "Mark chests near me", "Clear marked chests", "Show marked chests"},
        {"Water", "Use water buckets", "Water buckets to carry", "Water search radius"},
        {"Walking & filler", "Auto walk", "Sneak at edges", "Show path", "Filler blocks", "Filler block", "Remove filler as I build", "Remove filler when done", "Auto unstick", "Unstick after (s)"},
        {"Fixing", "Fix wrong blocks", "Replace other wrong blocks"},
        {"Display", "Load into Litematica", "Litematica layers", "Built-in preview", "Show outline", "Show next block", "Progress HUD", "Preview range", "Preview blocks"},
    };
    private static final Set<String> BUILD_TAB = Set.of("Open builder window", "File", "Refresh file list", "Load selected file", "Origin: my position", "Origin: block I look at", "Unload");
    private static final String[] TAB_NAMES = {"Build", "Materials", "Settings"};

    private record Hit(int x, int y, int w, int h, IntConsumer click, Setting.Num drag) {}

    private static int tab;
    /** Layer picker mode: 0 = all layers, 1 = one layer, 2 = range, -1 = derive from the settings. */
    private static int layerUi = -1;
    private static final double[] scrollT = new double[3], scrollV = new double[3];
    private static double fileScrollT, fileScrollV;
    private static final Set<String> openGroups = new HashSet<>(Set.of("Placement"));
    private static final Map<Object, Float> smooth = new HashMap<>();
    private static final Map<Item, ItemStack> stacks = new HashMap<>();

    private final BuildModules.Schematic mod;
    private final List<Hit> hits = new ArrayList<>(), fixed = new ArrayList<>();
    private boolean fixedMode, lastDouble;
    private Setting.Num dragging;
    private Hit dragHit;
    private float open, oa, kk;
    private long lastNs = System.nanoTime();
    private int px, py, pw, ph, oy, contentH, mouseX, mouseY, viewTop, viewBot, listX, listY, listW, listH;

    public SchematicScreen(BuildModules.Schematic mod) {
        super(Text.literal("Schematic Builder"));
        this.mod = mod;
    }

    @Override protected void init() {
        ClientModules.Background.scan();
        ClientModules.Gui.scan();
        pw = Math.min(width - 30, Math.max(ClientModules.Layout.width(), 480));
        ph = Math.min(height - 30, Math.max(ClientModules.Layout.height(), 330));
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        layerUi = -1;
        if (mod.fileNames().isEmpty()) mod.reloadFiles();
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void removed() { Gfx.glowScale = 1f; Config.save(); }

    // ---------- small helpers ----------
    private int A(int c) { return Gfx.a(c, oa); }
    private static boolean in(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private boolean hov(int x, int y, int w, int h) { return in(mouseX, mouseY, x, y, w, h) && (fixedMode || (mouseY >= viewTop && mouseY < viewBot)); }

    private float sm(Object key, float target, float k) {
        float v = smooth.getOrDefault(key, target);
        v += (target - v) * k;
        smooth.put(key, v);
        return v;
    }

    private void snd(float pitch) { if (ClientModules.Gui.sounds()) ClientModules.Gui.play(pitch); }

    private void addHit(Hit h) { (fixedMode ? fixed : hits).add(h); }

    private static String fit(String s, int maxW) {
        if (U.width(s) <= maxW) return s;
        while (s.length() > 1 && U.width(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private static String fmt(Setting.Num n) { return n.step >= 1 ? String.valueOf(n.getInt()) : String.format("%.2f", n.get()); }

    private static String itemName(Item it) {
        String s = Registries.ITEM.getId(it).getPath().replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String stackText(int n) {
        if (n < 64) return String.valueOf(n);
        int st = n / 64, r = n % 64;
        return n + " (" + st + " st" + (r > 0 ? " + " + r : "") + ")";
    }

    private static String etaText(int s) {
        if (s < 0) return "-";
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + String.format("%02d", s % 60) + "s";
        return (s / 3600) + "h " + String.format("%02d", (s % 3600) / 60) + "m";
    }

    private Setting<?> find(String name) {
        for (Setting<?> s : mod.settings) if (s.name.equals(name)) return s;
        return null;
    }

    private static void chevron(DrawContext c, int cx, int cy, boolean up, int color) {
        int a = up ? 2 : -1, b = up ? -1 : 2;
        Gfx.line(c, cx - 3, cy + a, cx, cy + b, color);
        Gfx.line(c, cx, cy + b, cx + 3, cy + a, color);
    }

    private ItemStack stackOf(Item it) { return stacks.computeIfAbsent(it, key -> new ItemStack(key)); }

    // ---------- widgets ----------
    private void frame(DrawContext ctx, int x, int y, int w, int h, int acc) {
        int cr = Math.round(ClientModules.Layout.radius() * 0.625f);
        float ca = ClientModules.Background.cardAlpha();
        Gfx.rrect(ctx, x, y, w, h, cr, A(Gfx.mix(cardEdgeC(), acc, 0.18f)));
        Gfx.rrectV(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0, cr - 1),
                A(Gfx.a(Gfx.mix(cardC(), acc, 0.07f), ca)), A(Gfx.a(Gfx.mix(cardC(), acc, 0.02f), ca)));
        Gfx.rrect(ctx, x + 6, y + 1, w - 12, 1, 0, A(Gfx.a(acc, 0.25f)));
    }

    /** Card with a title; returns the y where the content starts. */
    private int card(DrawContext ctx, int x, int y, int w, int h, String title, int acc) {
        frame(ctx, x, y, w, h, acc);
        U.text(ctx, title, x + 8, y + 6, A(Gfx.mix(DIM, acc, 0.55f)));
        Gfx.rrect(ctx, x + 6, y + 17, w - 12, 1, 0, A(edgeC()));
        return y + 21;
    }

    /** style: 0 normal, 1 primary (accent), 2 danger. */
    private void btn(DrawContext ctx, int x, int y, int w, int h, String label, int style, Runnable r, int acc) {
        boolean hv = hov(x, y, w, h);
        float v = sm("b" + label + "|" + x, hv ? 1f : 0f, kk);
        int fill;
        if (style == 1) {
            Gfx.glow(ctx, x, y, w, h, 5, acc, oa * (0.35f + 0.4f * v), 6);
            fill = Gfx.mix(Gfx.mix(slotC(), acc, 0.50f), acc, 0.35f * v);
        } else if (style == 2) fill = Gfx.mix(0xFF4A1F28, 0xFF7A2A35, v);
        else fill = Gfx.mix(slotC(), Gfx.mix(edgeC(), acc, 0.35f), v);
        Gfx.rrect(ctx, x, y, w, h, 5, A(fill));
        U.text(ctx, label, x + w / 2 - U.width(label) / 2, y + (h - 8) / 2, A(style == 1 ? 0xFFFFFFFF : TEXT));
        addHit(new Hit(x, y, w, h, b -> { if (b == 0) { snd(1.0f); r.run(); } }, null));
    }

    private void segmented(DrawContext ctx, int x, int y, int w, int h, String[] opts, String cur, Consumer<String> set, int acc, String key) {
        Gfx.rrect(ctx, x, y, w, h, 5, A(slotC()));
        int sw = w / opts.length;
        for (int i = 0; i < opts.length; i++) {
            int sx = x + i * sw, ww = i == opts.length - 1 ? w - i * sw : sw;
            boolean sel = opts[i].equals(cur);
            float v = sm(key + "#" + i, sel ? 1f : 0f, kk);
            if (v > 0.02f) Gfx.rrect(ctx, sx + 1, y + 1, ww - 2, h - 2, 4, A(Gfx.a(acc, 0.32f * v)));
            else if (hov(sx, y, ww, h)) Gfx.rrect(ctx, sx + 1, y + 1, ww - 2, h - 2, 4, A(Gfx.a(0xFFFFFFFF, 0.05f)));
            String t = opts[i];
            U.text(ctx, t, sx + ww / 2 - U.width(t) / 2, y + (h - 8) / 2, A(Gfx.mix(DIM, TEXT, v)));
            final String o = opts[i];
            addHit(new Hit(sx, y, ww, h, b -> { if (b == 0) { snd(1.1f); set.accept(o); } }, null));
        }
    }

    private void stepper(DrawContext ctx, int x, int y, int w, int h, String label, Setting.Num n, int acc) {
        Gfx.rrect(ctx, x, y, w, h, 5, A(slotC()));
        int bw = 15;
        boolean hm = hov(x, y, bw, h), hp = hov(x + w - bw, y, bw, h);
        if (hm) Gfx.rrect(ctx, x + 1, y + 1, bw - 1, h - 2, 4, A(Gfx.a(acc, 0.28f)));
        if (hp) Gfx.rrect(ctx, x + w - bw, y + 1, bw - 1, h - 2, 4, A(Gfx.a(acc, 0.28f)));
        U.text(ctx, "-", x + bw / 2 - 2, y + (h - 8) / 2, A(TEXT));
        U.text(ctx, "+", x + w - bw / 2 - 3, y + (h - 8) / 2, A(TEXT));
        String t = label + " " + n.getInt();
        U.text(ctx, t, x + w / 2 - U.width(t) / 2, y + (h - 8) / 2, A(n.getInt() != 0 ? acc : DIM));
        addHit(new Hit(x, y, bw, h, b -> { snd(0.95f); n.set(n.get() - (b == 1 ? 8 : 1)); }, null));
        addHit(new Hit(x + w - bw, y, bw, h, b -> { snd(1.05f); n.set(n.get() + (b == 1 ? 8 : 1)); }, null));
    }

    private int rowH(Setting<?> s) { return s instanceof Setting.Num ? ROW_N : ROW_B; }

    /** One setting row (toggle / mode / slider / button) inside a card of width w. Returns its height. */
    private int settingRow(DrawContext ctx, int x, int yy, int w, Setting<?> s, float ra, int acc) {
        int tint = (acc & 0x00FFFFFF) | 0xFF000000;
        if (s instanceof Setting.Bool b) {
            if (hov(x + 4, yy, w - 8, ROW_B)) Gfx.rrect(ctx, x + 4, yy, w - 8, ROW_B, 4, Gfx.a(0xFFFFFFFF, 0.04f * ra));
            U.text(ctx, s.name, x + 9, yy + 4, Gfx.a(TEXT, ra));
            float v = sm(s, b.get() ? 1f : 0f, kk);
            int bx = x + w - 8 - 18;
            if (v > 0.03f) Gfx.glow(ctx, bx, yy + 3, 18, 9, 4, tint, ra * v * 0.9f, 5);
            Gfx.rrect(ctx, bx, yy + 3, 18, 9, 4, Gfx.a(Gfx.mix(trackC(), tint, v), ra));
            Gfx.rrect(ctx, bx + 1 + Math.round(9 * v), yy + 4, 7, 7, 3, Gfx.a(0xFFFFFFFF, ra));
            if (ra > 0.9f * oa) addHit(new Hit(x + 4, yy, w - 8, ROW_B, bt -> { b.value = !b.value; snd(b.value ? 1.2f : 0.9f); }, null));
            return ROW_B;
        }
        if (s instanceof Setting.Mode md) {
            if (hov(x + 4, yy, w - 8, ROW_B)) Gfx.rrect(ctx, x + 4, yy, w - 8, ROW_B, 4, Gfx.a(0xFFFFFFFF, 0.04f * ra));
            U.text(ctx, s.name, x + 9, yy + 4, Gfx.a(TEXT, ra));
            String t = "< " + fit(md.value, Math.max(30, w / 2 - 20)) + " >";
            U.text(ctx, t, x + w - 9 - U.width(t), yy + 4, Gfx.a(acc, ra));
            if (ra > 0.9f * oa) addHit(new Hit(x + 4, yy, w - 8, ROW_B, bt -> { md.cycle(bt == 1 ? -1 : 1); snd(1.05f); }, null));
            return ROW_B;
        }
        if (s instanceof Setting.Num n) {
            U.text(ctx, s.name, x + 9, yy + 3, Gfx.a(TEXT, ra));
            String v = fmt(n);
            U.text(ctx, v, x + w - 9 - U.width(v), yy + 3, Gfx.a(DIM, ra));
            int tx0 = x + 9, tw = w - 18, ty0 = yy + 15;
            float ratio = (float) ((n.get() - n.min) / (n.max - n.min));
            float sr = sm(s, ratio, dragging == n ? 0.6f : kk);
            Gfx.rrect(ctx, tx0, ty0, tw, 3, 1, Gfx.a(sliderC(), ra));
            int fw = Math.max(0, Math.round(tw * sr));
            if (fw > 0) {
                Gfx.glow(ctx, tx0, ty0, Math.max(2, fw), 3, 1, acc, ra * 0.9f, 5);
                Gfx.rrect(ctx, tx0, ty0, Math.max(2, fw), 3, 1, Gfx.a(acc, ra));
            }
            Gfx.rrect(ctx, tx0 + fw - 3, ty0 - 2, 7, 7, 3, Gfx.a(0xFFFFFFFF, ra));
            if (ra > 0.9f * oa) addHit(new Hit(tx0, yy + 8, tw, 13, bt -> {}, n));
            return ROW_N;
        }
        if (s instanceof Setting.Action a) {
            boolean hv = hov(x + 8, yy + 1, w - 16, ROW_B - 2);
            Gfx.rrect(ctx, x + 8, yy + 1, w - 16, ROW_B - 2, 4, Gfx.a(hv ? Gfx.mix(edgeC(), tint, 0.35f) : slotC(), ra));
            U.text(ctx, s.name, x + w / 2 - U.width(s.name) / 2, yy + 4, Gfx.a(TEXT, ra));
            if (ra > 0.9f * oa) addHit(new Hit(x + 8, yy + 1, w - 16, ROW_B - 2, bt -> { a.run.run(); snd(1.0f); }, null));
            return ROW_B;
        }
        return 0;
    }

    // ---------- render ----------
    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        kk = 1f - (float) Math.exp(-dt * 16f);
        open += (1f - open) * (1f - (float) Math.exp(-dt * 11f));
        oa = Gfx.ease(Math.min(1f, open * 1.05f));
        oy = Math.round((1f - oa) * 14f);
        mouseX = mx; mouseY = my;
        int acc = Theme.accent();
        hits.clear(); fixed.clear();
        int x0 = px, y0 = py + oy;

        final int R = ClientModules.Layout.radius();
        Gfx.glowScale = ClientModules.Layout.glow();
        ClientModules.Background.drawScreen(ctx, width, height, oa);
        Gfx.shadow(ctx, x0, y0, pw, ph, R, oa);
        Gfx.glow(ctx, x0, y0, pw, ph, R, acc, oa * 0.6f, 16);
        Gfx.rrect(ctx, x0, y0, pw, ph, R, A(Gfx.mix(edgeC(), acc, 0.40f)));
        int wa = bgC() & 0xFF000000;
        if (ClientModules.Layout.sheen()) {
            Gfx.rrectV(ctx, x0 + 1, y0 + 1, pw - 2, ph - 2, Math.max(0, R - 1),
                    A(wa | (Gfx.mix(Theme.panel(), acc, 0.10f) & 0xFFFFFF)), A(wa | (Gfx.mix(Theme.panel(), 0xFF000000, 0.30f) & 0xFFFFFF)));
        } else {
            Gfx.rrect(ctx, x0 + 1, y0 + 1, pw - 2, ph - 2, Math.max(0, R - 1), A(wa | (Gfx.mix(Theme.panel(), acc, 0.045f) & 0xFFFFFF)));
        }
        ClientModules.Background.drawWindow(ctx, x0 + 1, y0 + 1, pw - 2, ph - 2, Math.max(0, R - 1), oa);

        // ----- header -----
        fixedMode = true;
        U.glowText(ctx, "Schematic Builder", x0 + PAD, y0 + 11, A(acc), oa * 0.4f);
        int backW = 70;
        btn(ctx, x0 + pw - PAD - backW, y0 + 7, backW, 16, "< Modules", 0, () -> MinecraftClient.getInstance().setScreen(new NovaScreen()), acc);
        String st; int stc;
        if (!mod.isLoaded()) { st = "NO FILE"; stc = DIM; }
        else if (mod.originPos() == null) { st = "NO ORIGIN"; stc = AMBER; }
        else if (mod.finished()) { st = "DONE"; stc = GREEN; }
        else if (mod.enabled) { st = "BUILDING"; stc = GREEN; }
        else { st = "READY"; stc = acc; }
        int pillW = U.width(st) + 14, pillX = x0 + pw - PAD - backW - 8 - pillW;
        Gfx.rrect(ctx, pillX, y0 + 7, pillW, 16, 8, A(Gfx.a(stc, 0.20f)));
        U.text(ctx, st, pillX + 7, y0 + 11, A(stc));
        if (ClientModules.Layout.sheen()) Gfx.rrect(ctx, x0 + PAD, y0 + HEAD - 3, pw - PAD * 2, 1, 0, A(Gfx.a(acc, 0.30f)));

        // ----- tabs -----
        int tabY = y0 + HEAD + 1;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int tx = x0 + PAD + i * 82, tw = 78;
            boolean sel = tab == i, hv = hov(tx, tabY, tw, 18);
            float s = sm("tab" + i, sel ? 1f : 0f, kk);
            if (s > 0.02f) { Gfx.glow(ctx, tx, tabY, tw, 18, 6, acc, s * oa * 0.5f, 6); Gfx.rrect(ctx, tx, tabY, tw, 18, 6, A(Gfx.a(acc, 0.24f * s))); }
            else if (hv) Gfx.rrect(ctx, tx, tabY, tw, 18, 6, A(Gfx.a(0xFFFFFFFF, 0.06f)));
            String t = TAB_NAMES[i];
            U.text(ctx, t, tx + tw / 2 - U.width(t) / 2, tabY + 5, A(Gfx.mix(DIM, TEXT, s)));
            final int ti = i;
            addHit(new Hit(tx, tabY, tw, 18, b -> { if (tab != ti) { tab = ti; snd(1.1f); } }, null));
        }
        fixedMode = false;

        // ----- content -----
        int cx = x0 + PAD, cw = pw - PAD * 2;
        viewTop = y0 + HEAD + TABS + 4;
        // on the Build tab the Start / Stop bar is pinned under the scrolling area so it never scrolls out of view
        int runBar = tab == 0 ? RUN_H + RUN_GAP : 0;
        viewBot = y0 + ph - FOOT - runBar;
        int viewH = viewBot - viewTop;
        scrollV[tab] += (scrollT[tab] - scrollV[tab]) * (1f - (float) Math.exp(-dt * 18f));
        int top = viewTop - (int) Math.round(scrollV[tab]);
        listX = listY = listW = listH = 0;
        ctx.enableScissor(x0 + 2, viewTop, x0 + pw - 2, viewBot);
        int used = switch (tab) {
            case 0 -> drawBuild(ctx, cx, top, cw, viewH, acc);
            case 1 -> drawMaterials(ctx, cx, top, cw, viewH, acc);
            default -> drawSettings(ctx, cx, top, cw, acc);
        };
        ctx.disableScissor();
        contentH = used;
        scrollT[tab] = Math.max(0, Math.min(scrollT[tab], Math.max(0, contentH - viewH)));
        if (contentH > viewH) {
            int bh = Math.max(20, viewH * viewH / contentH);
            int by = viewTop + (int) ((viewH - bh) * (scrollV[tab] / (contentH - viewH)));
            Gfx.rrect(ctx, x0 + pw - 5, by, 2, bh, 1, A(Gfx.a(acc, 0.8f)));
        }

        if (tab == 0) {
            fixedMode = true;
            drawRun(ctx, cx, viewBot + RUN_GAP, cw, acc);
            fixedMode = false;
        }

        // ----- footer -----
        String hint; int hc = DIM;
        if (!mod.isLoaded()) { hint = "Next: pick a file on the Build tab and press Load."; hc = AMBER; }
        else if (mod.originPos() == null) { hint = "Next: set the origin (stand where it should go, or look at a block)."; hc = AMBER; }
        else if (!mod.enabled && !mod.finished()) { hint = "Ready - press Start building. Right Shift or Esc closes this window."; }
        else hint = "Left click: change   Right click: previous / bigger step   Scroll: move   Right Shift: close";
        U.text(ctx, fit(hint, pw - PAD * 2), x0 + PAD, y0 + ph - 12, A(hc));
        Gfx.glowScale = 1f;
    }

    // ---------- Build tab ----------
    private int drawBuild(DrawContext ctx, int x, int y, int w, int viewH, int acc) {
        int leftW = Math.max(150, w * 38 / 100), rightW = w - leftW - GAP, rx = x + leftW + GAP;
        int cardH = Math.max(viewH, 200);
        drawFiles(ctx, x, y, leftW, cardH, acc);
        int yy = y;
        yy += drawOverview(ctx, rx, yy, rightW, acc) + GAP;
        yy += drawLayers(ctx, rx, yy, rightW, acc) + GAP;
        yy += drawPosition(ctx, rx, yy, rightW, acc);
        return Math.max(cardH, yy - y);
    }

    private void drawFiles(DrawContext ctx, int x, int y, int w, int h, int acc) {
        int in = card(ctx, x, y, w, h, "Schematic files", acc);
        List<String> names = mod.fileNames();
        int btnY = y + h - 22;
        listX = x + 6; listY = in; listW = w - 12; listH = btnY - 4 - in;
        Gfx.rrect(ctx, listX, listY, listW, listH, 4, A(inputC()));
        if (names.isEmpty()) {
            U.text(ctx, "No .litematic files", listX + 6, listY + 6, A(TEXT));
            U.text(ctx, fit("Put them in .minecraft/schematics", listW - 10), listX + 6, listY + 18, A(DIM));
            U.text(ctx, fit("or config/nova/schematics", listW - 10), listX + 6, listY + 29, A(DIM));
        } else {
            int total = names.size() * 16 + 4;
            fileScrollT = Math.max(0, Math.min(fileScrollT, Math.max(0, total - listH)));
            fileScrollV += (fileScrollT - fileScrollV) * kk;
            ctx.enableScissor(listX, listY, listX + listW, listY + listH);
            for (int i = 0; i < names.size(); i++) {
                final String nm = names.get(i);
                int ry = listY + 2 + i * 16 - (int) Math.round(fileScrollV);
                if (ry + 16 < listY || ry > listY + listH) continue;
                boolean sel = nm.equals(mod.pickedFile()), loaded = nm.equals(mod.loadedFile());
                boolean hv = hov(listX, ry, listW, 16) && in(mouseX, mouseY, listX, listY, listW, listH);
                if (sel) Gfx.rrect(ctx, listX + 2, ry, listW - 4, 15, 4, A(Gfx.a(acc, 0.26f)));
                else if (hv) Gfx.rrect(ctx, listX + 2, ry, listW - 4, 15, 4, A(Gfx.a(0xFFFFFFFF, 0.06f)));
                if (loaded) Gfx.rrect(ctx, listX + 6, ry + 5, 5, 5, 2, A(GREEN));
                String disp = nm.endsWith(".litematic") ? nm.substring(0, nm.length() - 10) : nm;
                U.text(ctx, fit(disp, listW - 26), listX + 15, ry + 4, A(sel ? 0xFFFFFFFF : Gfx.mix(DIM, TEXT, 0.7f)));
                int c0 = Math.max(ry, listY), c1 = Math.min(ry + 16, listY + listH);
                if (c1 > c0) addHit(new Hit(listX, c0, listW, c1 - c0, b -> {
                    if (b != 0) return;
                    mod.pickFile(nm);
                    snd(1.0f);
                    if (lastDouble) mod.loadPicked();
                }, null));
            }
            ctx.disableScissor();
            if (total > listH) {
                int bh = Math.max(14, listH * listH / total);
                int by = listY + (int) ((listH - bh) * (fileScrollV / Math.max(1, total - listH)));
                Gfx.rrect(ctx, listX + listW - 3, by, 2, bh, 1, A(Gfx.a(acc, 0.7f)));
            }
        }
        int half = (w - 18) / 2;
        btn(ctx, x + 6, btnY, half, 16, "Refresh", 0, mod::reloadFiles, acc);
        btn(ctx, x + 12 + half, btnY, half, 16, "Load", 1, mod::loadPicked, acc);
    }

    private int drawOverview(DrawContext ctx, int x, int y, int w, int acc) {
        int h = mod.isLoaded() ? 119 : 108;
        int yy = card(ctx, x, y, w, h, "Overview", acc);
        int tx = x + 9, iw = w - 18;
        if (!mod.isLoaded()) {
            U.text(ctx, "No schematic loaded", tx, yy + 2, A(TEXT));
            U.text(ctx, fit("Pick a file on the left and press Load.", iw), tx, yy + 14, A(DIM));
            U.text(ctx, fit("(or double click a file)", iw), tx, yy + 25, A(DIM));
            String s = mod.errorLine().isEmpty() ? mod.statusLine() : "Error: " + mod.errorLine();
            if (!s.isEmpty()) U.text(ctx, fit(s, iw), tx, y + h - 14, A(mod.errorLine().isEmpty() ? DIM : RED));
            return h;
        }
        int[] sz = mod.size();
        String dims = sz[0] + " x " + sz[1] + " x " + sz[2];
        U.text(ctx, fit(mod.schemName(), iw - U.width(dims) - 8), tx, yy, A(Gfx.mix(TEXT, acc, 0.25f)));
        U.text(ctx, dims, tx + iw - U.width(dims), yy, A(DIM));
        yy += 12;
        BlockPos o = mod.originPos();
        U.text(ctx, o == null ? "Origin not set" : "Origin  " + o.getX() + "  " + o.getY() + "  " + o.getZ(), tx, yy, A(o == null ? AMBER : DIM));
        yy += 13;
        int total = Math.max(1, mod.totalBlocks());
        float pct = Math.min(1f, mod.builtBlocks() / (float) total);
        float ps = sm("prog", pct, kk);
        Gfx.rrect(ctx, tx, yy, iw, 8, 4, A(sliderC()));
        int fw = Math.round(iw * ps);
        if (fw > 0) {
            Gfx.glow(ctx, tx, yy, Math.max(4, fw), 8, 4, acc, oa * 0.8f, 5);
            Gfx.rrect(ctx, tx, yy, Math.max(4, fw), 8, 4, A(acc));
        }
        yy += 12;
        U.text(ctx, String.format("%,d / %,d  (%d%%)", mod.builtBlocks(), mod.totalBlocks(), Math.round(pct * 100)), tx, yy, A(TEXT));
        if (mod.wrongBlocks() > 0) {
            String wr = mod.wrongBlocks() + " wrong";
            U.text(ctx, wr, tx + iw - U.width(wr), yy, A(RED));
        }
        yy += 11;
        double rate = mod.blocksPerMinute();
        String sp = (rate > 0.5 ? Math.round(rate) + " blocks/min   ETA " + etaText(mod.etaSeconds()) : "Speed: -");
        int ly = mod.currentLayer();
        String lt = "";
        if (ly > 0) lt = "Layer " + ly + " / " + sz[1];
        else if (mod.layerLimited()) lt = mod.firstLayer() == mod.lastLayer() ? "Layer " + mod.firstLayer() + " only" : "Layers " + mod.firstLayer() + "-" + mod.lastLayer();
        U.text(ctx, fit(sp, iw - (lt.isEmpty() ? 0 : U.width(lt) + 8)), tx, yy, A(DIM));
        if (!lt.isEmpty()) U.text(ctx, lt, tx + iw - U.width(lt), yy, A(mod.layerLimited() ? acc : DIM));
        yy += 11;
        // layer check: the builder compares the layer with the real world (not with its own bookkeeping) before it moves on
        {
            int cl = mod.checkedLayer(), ct = mod.checkedTotal(), cm = mod.checkedMissing();
            String ck; int cc = DIM;
            if (mod.finalChecking()) { ck = "Final check: layer " + Math.max(1, cl) + " / " + sz[1] + " ..."; cc = AMBER; }
            else if (cl < 0 || ct <= 0) ck = "Layer check: waiting";
            else if (cm == 0) { ck = "Layer " + cl + " complete  " + ct + " / " + ct + " verified"; cc = GREEN; }
            else { ck = "Layer " + cl + ": " + (ct - cm) + " / " + ct + "  " + cm + " missing"; cc = AMBER; }
            U.text(ctx, fit(ck, iw), tx, yy, A(cc));
            yy += 11;
        }
        String line; int lc = DIM;
        if (!mod.errorLine().isEmpty()) { line = "Error: " + mod.errorLine(); lc = RED; }
        else if (mod.finished()) { line = "Everything is built."; lc = GREEN; }
        else if (mod.enabled && !mod.walkLine().isEmpty()) line = mod.walkLine();
        else line = mod.statusLine();
        if (!line.isEmpty()) U.text(ctx, fit(line, iw), tx, yy, A(lc));
        return h;
    }

    /** Layer picker: build everything, one layer, or a range. Shows the choice, per-layer progress and how many blocks it covers. */
    private int drawLayers(DrawContext ctx, int x, int y, int w, int acc) {
        if (!mod.isLoaded()) {
            int h0 = 40;
            int y0 = card(ctx, x, y, w, h0, "Layers", acc);
            U.text(ctx, fit("Load a schematic to choose which layers to build.", w - 18), x + 9, y0 + 2, A(DIM));
            return h0;
        }
        final int M = Math.max(1, mod.size()[1]);
        final int lo = mod.firstLayer(), hi = mod.lastLayer();
        int derived = !mod.layerLimited() ? 0 : (lo == hi ? 1 : 2);
        boolean ok = layerUi == derived || (layerUi == 2 && derived <= 1) || (layerUi == 1 && M == 1 && derived == 0);
        if (layerUi < 0 || !ok) layerUi = derived;
        final int ui = layerUi;

        int h = 100;
        int yy = card(ctx, x, y, w, h, "Layers", acc);
        int tx = x + 8, iw = w - 16, lw = 62;

        // mode switch
        String[] modes = {"All", "One layer", "Range"};
        U.text(ctx, "Build", tx + 1, yy + 4, A(TEXT));
        segmented(ctx, tx + lw, yy, iw - lw, 16, modes, modes[ui], v -> {
            if (v.equals("All")) { layerUi = 0; mod.setLayers(1, 0); }
            else if (v.equals("One layer")) {
                layerUi = 1;
                int l = ui == 0 ? mod.firstOpenLayer() : lo;
                mod.setLayers(l, l);
            } else { layerUi = 2; }
        }, acc, "layerMode");
        yy += 21;

        // selector row
        if (ui == 0) {
            U.text(ctx, fit("Whole schematic: layers 1 - " + M, iw), tx + 1, yy + 4, A(DIM));
        } else if (ui == 1) {
            int bw = 80, sw = iw - bw - 4;
            layerStep(ctx, tx, yy, sw, 16, "Layer " + lo + " / " + M, true, d -> mod.setLayers(lo + d, lo + d), acc);
            btn(ctx, tx + sw + 4, yy, bw, 16, "Lowest open", 0, () -> { int l = mod.firstOpenLayer(); mod.setLayers(l, l); }, acc);
        } else {
            int hw = (iw - 4) / 2;
            layerStep(ctx, tx, yy, hw, 16, "From " + lo, true, d -> mod.setLayers(lo + d, hi), acc);
            layerStep(ctx, tx + hw + 4, yy, iw - hw - 4, 16, "To " + hi, true, d -> mod.setLayers(lo, hi + d), acc);
        }
        yy += 21;

        // layer strip: one column per layer (or per group of layers when there are more layers than pixels), bottom layer on the left
        int sh = 12;
        int n = Math.max(1, Math.min(M, iw));
        Gfx.rrect(ctx, tx, yy, iw, sh, 3, A(slotC()));
        int hovLayer = -1;
        if (hov(tx, yy, iw, sh)) hovLayer = Math.max(1, Math.min(M, 1 + (int) ((mouseX - tx) * (long) M / iw)));
        for (int k = 0; k < n; k++) {
            int l0 = (int) ((long) k * M / n), l1 = Math.max(l0 + 1, (int) ((long) (k + 1) * M / n));   // 0-based, l1 exclusive
            int cx0 = tx + (int) ((long) k * iw / n), cx1 = tx + (int) ((long) (k + 1) * iw / n);
            int cw = Math.max(1, cx1 - cx0 - (n <= iw / 3 ? 1 : 0));
            int tot = 0, bl = 0;
            for (int l = l0; l < l1; l++) { tot += mod.layerTotal(l + 1); bl += mod.layerBuilt(l + 1); }
            float frac = tot <= 0 ? 0f : Math.min(1f, bl / (float) tot);
            boolean sel = l1 > lo - 1 && l0 < hi;   // overlaps the chosen layers
            int col;
            if (sel) col = Gfx.mix(acc, GREEN, frac);
            else col = Gfx.mix(trackC(), GREEN, 0.55f * frac);
            int ch = sel ? sh : sh - 4, cy = yy + (sh - ch) / 2;
            Gfx.rrect(ctx, cx0, cy, cw, ch, 0, A(sel ? col : Gfx.a(col, 0.8f)));
        }
        if (hovLayer > 0) {
            int hx = tx + (int) ((long) (hovLayer - 1) * iw / M);
            Gfx.rrect(ctx, hx, yy - 1, Math.max(2, iw / M), sh + 2, 0, A(0xFFFFFFFF));
        }
        addHit(new Hit(tx, yy, iw, sh, b -> {
            int l = Math.max(1, Math.min(M, 1 + (int) ((mouseX - tx) * (long) M / iw)));
            snd(1.0f);
            if (ui == 0) { layerUi = 1; mod.setLayers(l, l); }
            else if (ui == 1) mod.setLayers(l, l);
            else if (b == 1 || Math.abs(l - hi) < Math.abs(l - lo)) mod.setLayers(lo, l);
            else mod.setLayers(l, hi);
        }, null));
        yy += sh + 5;

        // info line: hovered layer, otherwise the selection
        String info;
        if (hovLayer > 0) {
            int t = mod.layerTotal(hovLayer), bl = mod.layerBuilt(hovLayer);
            info = "Layer " + hovLayer + ": " + (t > 0 ? bl + " / " + t + " blocks" : "no blocks");
        } else {
            long tot = 0, bl = 0;
            for (int l = lo; l <= hi; l++) { tot += mod.layerTotal(l); bl += mod.layerBuilt(l); }
            String what = ui == 0 ? "All layers" : lo == hi ? "Layer " + lo : "Layers " + lo + "-" + hi;
            info = tot > 0 ? what + ": " + String.format("%,d / %,d", bl, tot) + " blocks (" + Math.round(100f * bl / tot) + "%)" : what + (mod.originPos() == null ? " (set the origin to count blocks)" : "");
        }
        U.text(ctx, fit(info, iw), tx + 1, yy, A(hovLayer > 0 ? TEXT : DIM));
        return h;
    }

    /** - / + selector with a text in the middle. Left click +-1, right click +-8. */
    private void layerStep(DrawContext ctx, int x, int y, int w, int h, String text, boolean lit, IntConsumer step, int acc) {
        Gfx.rrect(ctx, x, y, w, h, 5, A(slotC()));
        int bw = 15;
        boolean hm = hov(x, y, bw, h), hp = hov(x + w - bw, y, bw, h);
        if (hm) Gfx.rrect(ctx, x + 1, y + 1, bw - 1, h - 2, 4, A(Gfx.a(acc, 0.28f)));
        if (hp) Gfx.rrect(ctx, x + w - bw, y + 1, bw - 1, h - 2, 4, A(Gfx.a(acc, 0.28f)));
        U.text(ctx, "-", x + bw / 2 - 2, y + (h - 8) / 2, A(TEXT));
        U.text(ctx, "+", x + w - bw / 2 - 3, y + (h - 8) / 2, A(TEXT));
        String t = fit(text, w - bw * 2 - 4);
        U.text(ctx, t, x + w / 2 - U.width(t) / 2, y + (h - 8) / 2, A(lit ? acc : DIM));
        addHit(new Hit(x, y, bw, h, b -> { snd(0.95f); step.accept(b == 1 ? -8 : -1); }, null));
        addHit(new Hit(x + w - bw, y, bw, h, b -> { snd(1.05f); step.accept(b == 1 ? 8 : 1); }, null));
    }

    private int drawPosition(DrawContext ctx, int x, int y, int w, int acc) {
        int h = 112;
        int yy = card(ctx, x, y, w, h, "Position", acc);
        int tx = x + 8, iw = w - 16, half = (iw - 6) / 2;
        btn(ctx, tx, yy, half, 16, "Origin: my position", 0, mod::originAtMe, acc);
        btn(ctx, tx + half + 6, yy, iw - half - 6, 16, "Origin: crosshair", 0, mod::originAtCrosshair, acc);
        yy += 21;
        int lw = 62;
        if (find("Rotation") instanceof Setting.Mode rot) {
            U.text(ctx, "Rotation", tx + 1, yy + 4, A(TEXT));
            segmented(ctx, tx + lw, yy, iw - lw, 16, rot.modes, rot.value, v -> rot.value = v, acc, "rot");
            yy += 20;
        }
        if (find("Origin is") instanceof Setting.Mode anc) {
            U.text(ctx, "Origin is", tx + 1, yy + 4, A(TEXT));
            segmented(ctx, tx + lw, yy, iw - lw, 16, anc.modes, anc.value, v -> anc.value = v, acc, "anc");
            yy += 20;
        }
        if (find("Offset X") instanceof Setting.Num ox && find("Offset Y") instanceof Setting.Num oyy && find("Offset Z") instanceof Setting.Num oz) {
            int cw3 = (iw - 8) / 3;
            stepper(ctx, tx, yy, cw3, 16, "X", ox, acc);
            stepper(ctx, tx + cw3 + 4, yy, cw3, 16, "Y", oyy, acc);
            stepper(ctx, tx + (cw3 + 4) * 2, yy, iw - (cw3 + 4) * 2, 16, "Z", oz, acc);
        }
        return h;
    }

    private int drawRun(DrawContext ctx, int x, int y, int w, int acc) {
        int uw = 66;
        btn(ctx, x, y, w - uw - 6, RUN_H, mod.enabled ? "Stop building" : "Start building", mod.enabled ? 2 : 1, mod::toggle, acc);
        btn(ctx, x + w - uw, y, uw, RUN_H, "Unload", 0, mod::unloadAll, acc);
        return RUN_H;
    }

    // ---------- Materials tab ----------
    private int drawMaterials(DrawContext ctx, int x, int y, int w, int viewH, int acc) {
        Map<Item, Integer> need = mod.needed(), lack = mod.lacking();
        Set<Item> all = new LinkedHashSet<>(need.keySet());
        all.addAll(lack.keySet());
        List<Item> items = new ArrayList<>(all);
        items.sort((a, b) -> {
            int c = Integer.compare(lack.getOrDefault(b, 0), lack.getOrDefault(a, 0));
            return c != 0 ? c : Integer.compare(need.getOrDefault(b, 0), need.getOrDefault(a, 0));
        });
        int rowH = 22;
        int h = Math.max(viewH, 21 + 16 + items.size() * rowH + 8);
        int yy = card(ctx, x, y, w, h, "Materials", acc);
        int missingTypes = lack.size();
        long totalNeed = 0;
        for (int n : need.values()) totalNeed += n;
        String sum = items.size() + " item types   " + String.format("%,d", totalNeed) + " blocks left to place";
        U.text(ctx, sum, x + 9, yy + 1, A(DIM));
        String ms = missingTypes == 0 ? "nothing missing" : missingTypes + " missing";
        U.text(ctx, ms, x + w - 9 - U.width(ms), yy + 1, A(missingTypes == 0 ? GREEN : AMBER));
        yy += 16;
        if (items.isEmpty()) {
            String m = mod.isLoaded() ? "Nothing left to place (or still counting)." : "Load a schematic to see what it needs.";
            U.text(ctx, m, x + 9, yy + 6, A(DIM));
            return h;
        }
        for (Item it : items) {
            int n = need.getOrDefault(it, 0), l = lack.getOrDefault(it, 0), have = mod.carrying(it);
            n = Math.max(n, have + l);
            if (yy + rowH >= viewTop - 4 && yy <= viewBot + 4) {
                if (hov(x + 4, yy, w - 8, rowH)) Gfx.rrect(ctx, x + 4, yy, w - 8, rowH - 1, 4, A(Gfx.a(0xFFFFFFFF, 0.04f)));
                ctx.drawItem(stackOf(it), x + 8, yy + 3);
                String right = have + " / " + n;
                U.text(ctx, right, x + w - 9 - U.width(right), yy + 3, A(l > 0 ? AMBER : GREEN));
                String nm = itemName(it);
                int nameW = w - 9 - 28 - U.width(right) - 12;
                U.text(ctx, fit(nm, nameW), x + 28, yy + 3, A(TEXT));
                String sub = l > 0 ? "missing " + stackText(l) : "ok";
                U.text(ctx, fit(sub, nameW), x + 28, yy + 12, A(l > 0 ? AMBER : DIM));
                int bw = 60, bx = x + w - 9 - bw;
                float r = n <= 0 ? 1f : Math.min(1f, have / (float) n);
                Gfx.rrect(ctx, bx, yy + 14, bw, 3, 1, A(sliderC()));
                if (r > 0f) Gfx.rrect(ctx, bx, yy + 14, Math.max(2, Math.round(bw * r)), 3, 1, A(l > 0 ? AMBER : GREEN));
            }
            yy += rowH;
        }
        return h;
    }

    // ---------- Settings tab ----------
    private int drawSettings(DrawContext ctx, int x, int y, int w, int acc) {
        List<String> groupNames = new ArrayList<>();
        List<List<Setting<?>>> groupSettings = new ArrayList<>();
        Set<String> used = new HashSet<>(BUILD_TAB);
        for (String[] g : GROUPS) {
            List<Setting<?>> l = new ArrayList<>();
            for (int i = 1; i < g.length; i++) { Setting<?> s = find(g[i]); if (s != null) { l.add(s); used.add(g[i]); } }
            if (!l.isEmpty()) { groupNames.add(g[0]); groupSettings.add(l); }
        }
        List<Setting<?>> other = new ArrayList<>();
        for (Setting<?> s : mod.settings) if (!used.contains(s.name)) other.add(s);
        if (!other.isEmpty()) { groupNames.add("Other"); groupSettings.add(other); }

        int cols = w >= 470 ? 2 : 1;
        int colW = (w - GAP * (cols - 1)) / cols;
        int[] colY = new int[cols];
        int tint = (acc & 0x00FFFFFF) | 0xFF000000;
        for (int g = 0; g < groupNames.size(); g++) {
            final String name = groupNames.get(g);
            List<Setting<?>> list = groupSettings.get(g);
            int inner = 3;
            for (Setting<?> s : list) inner += rowH(s);
            inner += 3;
            boolean isOpen = openGroups.contains(name);
            float ex = Gfx.ease(sm("grp" + name, isOpen ? 1f : 0f, kk));
            int h = CARD_HEAD + Math.round(inner * ex);
            int ci = 0;
            for (int c = 1; c < cols; c++) if (colY[c] < colY[ci]) ci = c;
            int cx = x + ci * (colW + GAP), cy = y + colY[ci];
            colY[ci] += h + GAP;
            if (cy + h < viewTop - 2 || cy > viewBot + 2) continue;

            frame(ctx, cx, cy, colW, h, acc);
            boolean hv = hov(cx, cy, colW, CARD_HEAD);
            if (hv) Gfx.rrect(ctx, cx + 1, cy + 1, colW - 2, CARD_HEAD - 2, 5, A(Gfx.a(0xFFFFFFFF, 0.04f)));
            U.text(ctx, name, cx + 8, cy + 6, A(Gfx.mix(DIM, TEXT, 0.4f + 0.6f * ex)));
            String cnt = String.valueOf(list.size());
            U.text(ctx, cnt, cx + colW - 22 - U.width(cnt), cy + 6, A(DIM));
            chevron(ctx, cx + colW - 11, cy + 9, ex > 0.5f, A(DIM));
            addHit(new Hit(cx, cy, colW, CARD_HEAD, b -> { if (!openGroups.remove(name)) openGroups.add(name); snd(1.0f); }, null));

            if (ex < 0.02f) continue;
            float ra = oa * ex;
            ctx.enableScissor(cx + 1, cy + CARD_HEAD, cx + colW - 1, cy + h - 1);
            Gfx.rrect(ctx, cx + 6, cy + CARD_HEAD - 1, colW - 12, 1, 0, Gfx.a(edgeC(), ra));
            int yy = cy + CARD_HEAD + 2;
            for (Setting<?> s : list) yy += settingRow(ctx, cx, yy, colW, s, ra, acc);
            ctx.disableScissor();
        }
        int max = 0;
        for (int v : colY) max = Math.max(max, v);
        return Math.max(0, max - GAP);
    }

    // ---------- input ----------
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int btn = click.button();
        lastDouble = doubled;
        for (int i = fixed.size() - 1; i >= 0; i--) {
            Hit h = fixed.get(i);
            if (in(mx, my, h.x(), h.y(), h.w(), h.h())) { h.click().accept(btn); return true; }
        }
        if (my >= viewTop && my < viewBot) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (!in(mx, my, h.x(), h.y(), h.w(), h.h())) continue;
                if (h.drag() != null) {
                    if (btn == 0) { dragging = h.drag(); dragHit = h; updateDrag(mx); snd(1.3f); }
                } else h.click().accept(btn);
                return true;
            }
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
        if (tab == 0 && listW > 0 && in(mx, my, listX, listY, listW, listH)) { fileScrollT = Math.max(0, fileScrollT - v * 16); return true; }
        scrollT[tab] = Math.max(0, scrollT[tab] - v * 28);
        return true;
    }

    @Override public boolean keyPressed(KeyInput input) {
        if (input.key() == 344) { close(); return true; }
        return super.keyPressed(input);
    }
}
