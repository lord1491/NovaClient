package nova.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Projects world positions onto the screen so ESP / tracers / outlines can be drawn on the HUD layer. */
public final class Proj {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double NEAR = 0.05;
    private static final Map<Integer, Vec3d[]> trail = new HashMap<>();
    private static Vec3d eyePrev, eyeCur;
    private static float fovPrev = 1f, fovCur = 1f;
    private static long tickNs = System.nanoTime();

    public final Vec3d eye;
    private final Vec3d f, r, u;
    private final double tanV, aspect;
    public final int sw, sh;

    private Proj(Vec3d eye, Vec3d f, Vec3d r, Vec3d u, double tanV, int sw, int sh) {
        this.eye = eye; this.f = f; this.r = r; this.u = u; this.tanV = tanV; this.sw = sw; this.sh = sh;
        this.aspect = sw / (double) sh;
    }

    public static void tick() {
        var p = mc.player; var w = mc.world;
        if (p == null || w == null) { trail.clear(); eyePrev = eyeCur = null; return; }
        tickNs = System.nanoTime();
        eyePrev = eyeCur == null ? p.getEyePos() : eyeCur;
        eyeCur = p.getEyePos();
        Set<Integer> seen = new HashSet<>();
        for (Entity e : w.getEntities()) {
            int id = e.getId();
            Vec3d cur = U.pos(e);
            Vec3d[] t = trail.get(id);
            if (t == null) trail.put(id, new Vec3d[]{cur, cur}); else { t[0] = t[1]; t[1] = cur; }
            seen.add(id);
        }
        trail.keySet().retainAll(seen);

        double walk = p.getAbilities().getWalkSpeed();
        double spd = p.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
        float target = (float) ((spd / walk + 1.0) / 2.0);
        float scale = mc.options.getFovEffectScale().getValue().floatValue();
        target = MathHelper.lerp(scale, 1.0f, target);
        fovPrev = fovCur;
        fovCur += (target - fovCur) * 0.5f;
        fovCur = MathHelper.clamp(fovCur, 0.1f, 1.5f);
    }

    public static float frac() { return (float) Math.min(1.0, (System.nanoTime() - tickNs) / 50_000_000.0); }

    public static Vec3d lerpPos(Entity e) {
        Vec3d[] t = trail.get(e.getId());
        return t == null ? U.pos(e) : t[0].lerp(t[1], frac());
    }

    public static Box lerpBox(Entity e) { return e.getBoundingBox().offset(lerpPos(e).subtract(U.pos(e))); }

    /** Null when the camera is not first person (the projection would be wrong). */
    public static Proj begin() {
        var p = mc.player;
        if (p == null || eyeCur == null || mc.options.hudHidden) return null;
        if (!mc.options.getPerspective().isFirstPerson() || CamState.active()) return null;
        float fr = frac();
        Vec3d eye = eyePrev.lerp(eyeCur, fr);
        double yaw = Math.toRadians(p.getYaw()), pitch = Math.toRadians(p.getPitch());
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        Vec3d f = new Vec3d(-Math.sin(yaw) * cp, -sp, Math.cos(yaw) * cp);
        Vec3d r = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3d u = r.crossProduct(f);
        double fov = mc.options.getFov().getValue() * MathHelper.lerp(fr, fovPrev, fovCur);
        return new Proj(eye, f, r, u, Math.tan(Math.toRadians(fov) / 2.0),
                mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
    }

    private double[] cam(Vec3d p) {
        Vec3d d = p.subtract(eye);
        return new double[]{d.dotProduct(r), d.dotProduct(u), d.dotProduct(f)};
    }

    private double[] toScreen(double cx, double cy, double cz) {
        double x = sw / 2.0 + cx / (cz * tanV * aspect) * (sw / 2.0);
        double y = sh / 2.0 - cy / (cz * tanV) * (sh / 2.0);
        return new double[]{x, y, cz};
    }

    public double[] project(Vec3d p) {
        double[] c = cam(p);
        if (c[2] <= NEAR) return null;
        return toScreen(c[0], c[1], c[2]);
    }

    /** Like project, but points behind you are mirrored and clamped on screen (for tracers). */
    public double[] screen(Vec3d p) {
        double[] c = cam(p);
        double cx = c[0], cy = c[1], cz = c[2];
        if (cz <= NEAR) { cx = -cx; cy = -cy; cz = NEAR; if (Math.abs(cx) + Math.abs(cy) < 1e-4) cx = 1; }
        double[] s = toScreen(cx, cy, cz);
        double mx = sw / 2.0, my = sh / 2.0;
        double dx = s[0] - mx, dy = s[1] - my;
        double k = Math.max(Math.abs(dx) / (mx - 6), Math.abs(dy) / (my - 6));
        if (k > 1) { dx /= k; dy /= k; }
        return new double[]{mx + dx, my + dy};
    }

    public float[] rect(Box b) {
        double x1 = 1e9, y1 = 1e9, x2 = -1e9, y2 = -1e9;
        for (int i = 0; i < 8; i++) {
            Vec3d c = new Vec3d((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ);
            double[] s = project(c);
            if (s == null) return null;
            x1 = Math.min(x1, s[0]); y1 = Math.min(y1, s[1]); x2 = Math.max(x2, s[0]); y2 = Math.max(y2, s[1]);
        }
        if (x2 < 0 || y2 < 0 || x1 > sw || y1 > sh) return null;
        return new float[]{(float) x1, (float) y1, (float) x2, (float) y2};
    }

    public void line3(DrawContext ctx, Vec3d a, Vec3d b, int color) {
        double[] ca = cam(a), cb = cam(b);
        if (ca[2] <= NEAR && cb[2] <= NEAR) return;
        if (ca[2] <= NEAR) { double t = (NEAR - ca[2]) / (cb[2] - ca[2]); ca = lerp(ca, cb, t); ca[2] = NEAR; }
        else if (cb[2] <= NEAR) { double t = (NEAR - cb[2]) / (ca[2] - cb[2]); cb = lerp(cb, ca, t); cb[2] = NEAR; }
        double[] sa = toScreen(ca[0], ca[1], ca[2]), sb = toScreen(cb[0], cb[1], cb[2]);
        Gfx.line(ctx, sa[0], sa[1], sb[0], sb[1], color);
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static final int[][] EDGES = {{0,1},{2,3},{4,5},{6,7},{0,2},{1,3},{4,6},{5,7},{0,4},{1,5},{2,6},{3,7}};

    public void wire(DrawContext ctx, Box b, int color) {
        Vec3d[] c = new Vec3d[8];
        for (int i = 0; i < 8; i++)
            c[i] = new Vec3d((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ);
        for (int[] e : EDGES) line3(ctx, c[e[0]], c[e[1]], color);
    }
}
