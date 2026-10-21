package nova.core;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Lets every on-screen (HUD) module be moved and resized from the HUD editor.
 *
 * How it works without touching each module: while a module draws its HUD, Gfx/U report every rectangle and text they draw
 * ({@link #track}), which gives the module's bounding box. The module's whole drawing is then wrapped in a matrix
 * (translate + scale), so it appears moved/scaled. The offset is measured from a fixed corner of the box (the corner that
 * is closest to the screen corner the module sits in by default), so right/bottom-aligned modules keep their alignment.
 */
public final class HudLayout {
    private HudLayout() {}

    public static final float MIN_SCALE = 0.5f, MAX_SCALE = 3.0f;

    public static final class E {
        public float dx, dy, s = 1f;
        /** Box of the module's own (untransformed) drawing, from the last frame it drew. */
        public float minX, minY, maxX, maxY;
        public boolean has, anchored, ancR, ancB;

        public boolean isDefault() { return dx == 0 && dy == 0 && s == 1f; }
        public float ax() { return ancR ? maxX : minX; }
        public float ay() { return ancB ? maxY : minY; }
    }

    private static final Map<String, E> MAP = new HashMap<>();

    // state of the module that is drawing right now
    private static E cur;
    private static boolean pushed;
    private static float tx0, ty0, tx1, ty1;
    private static boolean any;
    /** Set while drawing things that must not count towards a module's box (drop shadows). */
    public static boolean mute;

    /** Which modules can be moved: every module of the HUD category plus the StaffList panel. */
    public static boolean editable(Mod m) {
        return m.category == Category.HUD || m.name.equals("StaffList");
    }

    public static E get(Mod m) { return MAP.get(m.name); }

    public static E entry(Mod m) { return MAP.computeIfAbsent(m.name, k -> new E()); }

    // ---------- drawing ----------

    public static void begin(Mod m, DrawContext ctx) {
        cur = null; pushed = false; any = false;
        if (!editable(m)) return;
        E e = entry(m);
        cur = e;
        tx0 = ty0 = Float.MAX_VALUE; tx1 = ty1 = -Float.MAX_VALUE;
        if (e.has && !e.isDefault()) {
            float ax = e.ax(), ay = e.ay();
            var st = ctx.getMatrices();
            st.pushMatrix();
            pushed = true;
            st.translate(ax + e.dx, ay + e.dy);
            st.scale(e.s, e.s);
            st.translate(-ax, -ay);
        }
    }

    /** Always called after begin (in a finally block). Pops the matrix and stores the box the module just drew. */
    public static void end(DrawContext ctx) {
        if (pushed) { try { ctx.getMatrices().popMatrix(); } catch (Throwable ignored) {} }
        pushed = false;
        E e = cur;
        cur = null;
        if (e == null || !any) return;
        e.minX = tx0; e.minY = ty0; e.maxX = tx1; e.maxY = ty1;
        e.has = true;
        if (!e.anchored) {
            MinecraftClient mc = MinecraftClient.getInstance();
            e.ancR = (tx0 + tx1) / 2f > mc.getWindow().getScaledWidth() / 2f;
            e.ancB = (ty0 + ty1) / 2f > mc.getWindow().getScaledHeight() / 2f;
            e.anchored = true;
        }
    }

    /** Called by Gfx / U (and a few modules) for everything a HUD module draws. Cheap no-op when nothing is being tracked. */
    public static void track(int x, int y, int w, int h) {
        if (cur == null || mute || w <= 0 || h <= 0) return;
        any = true;
        if (x < tx0) tx0 = x;
        if (y < ty0) ty0 = y;
        if (x + w > tx1) tx1 = x + w;
        if (y + h > ty1) ty1 = y + h;
    }

    // ---------- geometry for the editor ----------

    /** Where the module's box is on screen right now: {x, y, w, h}. */
    public static float[] rect(E e) {
        float ax = e.ax(), ay = e.ay();
        return new float[]{
                ax + e.dx + e.s * (e.minX - ax), ay + e.dy + e.s * (e.minY - ay),
                (e.maxX - e.minX) * e.s, (e.maxY - e.minY) * e.s};
    }

    /** Keeps the module's box inside the screen. */
    public static void clamp(E e, int sw, int sh) {
        float[] r = rect(e);
        if (r[2] < sw) {
            if (r[0] < 0) e.dx -= r[0];
            else if (r[0] + r[2] > sw) e.dx -= r[0] + r[2] - sw;
        }
        if (r[3] < sh) {
            if (r[1] < 0) e.dy -= r[1];
            else if (r[1] + r[3] > sh) e.dy -= r[1] + r[3] - sh;
        }
    }

    public static void reset(E e) { e.dx = 0; e.dy = 0; e.s = 1f; }

    public static void resetAll() { for (E e : MAP.values()) reset(e); }

    // ---------- saving ----------

    public static JsonObject save() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, E> en : MAP.entrySet()) {
            E e = en.getValue();
            if (e.isDefault()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("dx", e.dx);
            o.addProperty("dy", e.dy);
            o.addProperty("scale", e.s);
            root.add(en.getKey(), o);
        }
        return root;
    }

    public static void load(JsonObject root) {
        for (Map.Entry<String, com.google.gson.JsonElement> en : root.entrySet()) {
            try {
                JsonObject o = en.getValue().getAsJsonObject();
                E e = MAP.computeIfAbsent(en.getKey(), k -> new E());
                if (o.has("dx")) e.dx = o.get("dx").getAsFloat();
                if (o.has("dy")) e.dy = o.get("dy").getAsFloat();
                if (o.has("scale")) e.s = Math.max(MIN_SCALE, Math.min(MAX_SCALE, o.get("scale").getAsFloat()));
            } catch (Exception ignored) {}
        }
    }
}
